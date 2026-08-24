# Concurrency & Crash Audit — android-sip-gateway

Scope: `app/src/main/java/org/onetwoone/**` + `app/src/main/cpp/gsm_audio_jni.c`
(~11k LOC Java, 1 JNI file). Server-side `asterisk-config/` and `freepbx/` are out of scope.

Method: static read of every source file that participates in the call, audio, SIP,
SMS or power lifecycle, cross-referenced against the thread that actually reaches it.
Nothing here has been reproduced on-device yet — each finding names the thread pair and
the interleaving, so it can be confirmed or dismissed cheaply.

---

## 1. The actual threading model (as built)

There is no declared threading model. Eleven distinct execution contexts touch shared
mutable state, most of it unguarded:

| # | Thread | Reaches |
|---|--------|---------|
| 1 | **Main looper** | Service lifecycle, `Call.Callback`, `onCallAdded/Removed`, `PhoneStateListener`, watchdog, reconnection, `GsmDtmfSender`, `SipTestCallManager`, SMS `ContentObserver`, UI status poll (1 Hz) |
| 2 | **pjsua worker** | `GatewayCall.onCallState/onCallMediaState/onDtmfDigit/onDtmfEvent`, `GatewayAccount.onRegState/onIncomingCall/onInstantMessage` |
| 3 | **pjmedia RT thread** | `GsmAudioPort.onFrameRequested` / `onFrameReceived` (50 Hz, must never block) |
| 4 | `SipInit` | `createEndpoint`, `audioBridge.initialize()`, `createAccount` |
| 5 | `ConfigReload` | `stopBridge`, `stopAudioStreams`, `deleteAccount`, `createAccount` |
| 6 | `GsmAudioOpen` | `GsmAudioNative.open()`, `profile.setupMixer()` |
| 7 | `MixerEnforce` | `profile.enforceMixer()` every 2 s |
| 8 | `MuteControls` | `DeviceMuteManager.muteAll()` (~6 s of `su` shell-outs) |
| 9 | **NanoHTTPD workers** | `reloadConfig`, prefs writes, `stopWebServer` |
| 10 | `SetCharging` (one per tick) | sysfs writes via root |
| 11 | `BatteryOptDisable`, `ProcessRestart`, RootHelper stdout/stderr readers | root shell-outs |

**Root cause of most findings:** PJSIP callbacks are handled inconsistently.
`onIncomingCall` and `onRegistrationState` are posted to main
(`PjsipSipService.java:325`, `:302`), but `onCallState`, `onCallMediaState` and
`onDtmfDigit` are **not** (`PjsipSipService.java:393`, `:405`, `:423`) — they run the
call state machine, the audio bridge teardown and Telecom hangups directly on a pjsua
worker thread, concurrently with the main thread doing the same things.

---

## 2. Findings

Severity: **P0** = crashes the process, bricks the device, or loses calls silently ·
**P1** = wrong behaviour under a realistic race · **P2** = latent / hygiene.

### P0 — native memory safety

#### A1. Use-after-free: `pcm_close()` races in-flight `pcm_read`/`pcm_write`
`cpp/gsm_audio_jni.c:209` `close()` takes `g_ctx->lock`; `readFrame` (`:244`) and
`writeFrame` (`:277`) **never take it**. They test `g_ctx->is_open` (a plain `int`, no
barrier), then dereference `g_ctx->capture_pcm` / `playback_pcm`.

`pcm_read` blocks up to one period (~20 ms). `GsmAudioPort.stopCapture()`
(`GsmAudioPort.java:357`) calls `GsmAudioNative.close()` from the main / pjsua /
ConfigReload thread while the pjmedia RT thread sits inside `pcm_read`.
`pcm_close()` frees the `struct pcm`; the RT thread then reads freed memory.

**Failure:** SIGSEGV in `pcm_read` on any hangup that lands mid-frame — i.e. exactly the
"stop the call before the phone handles it" case. Non-deterministic, so it presents as
a random native crash at end-of-call.

> **CORRECTION — on-device evidence, 2026-08-23. This finding was over-stated.**
>
> "On any hangup that lands mid-frame" is not what the hardware shows. Across **33
> teardowns** (13 in Step 3 with the hangup deliberately placed inside the first 3 s, 20 in
> Step 4), `drain_io_locked()` logged **zero** `close: draining N in-flight PCM I/O` lines —
> meaning `active_io == 0` **every single time** `close()` ran. Close latency was 1–20 ms,
> never near the 250 ms bound.
>
> The reason is teardown ordering. On both paths the conference port stops feeding our
> callbacks *before* `close()` is reached:
> - **GSM-initiated:** `terminateAllCalls` → `stopBridge` unwires the conference → then
>   `stopCapture` → `close()`.
> - **SIP-initiated:** pjsua removes the conference port first — and **E5** proves it
>   *blocks* until the in-flight `pcm_read` returns. That block, ironically, guarantees the
>   RT thread is quiescent before `close()` runs. The E5 bug is currently *protecting*
>   against A1.
>
> **Crash evidence is against A1 too.** All 8 gateway tombstones on this device
> (2026-08-01 → 2026-08-23, three of them on 08-23 alone) are the *same* crash, and it is
> **F2**, not A1:
> ```
> assertion "Calling pjlib from unknown/external thread..." failed
>   pj_mutex_lock <- pjsua_enum_transports <- Endpoint::transportEnum()
> ```
> Not one is a SIGSEGV in `pcm_read`. The random native end-of-call crash this project
> actually suffered was `hasTransport()` on an unregistered thread, fixed by `2626f5d`
> *before* Phase 0 began. Since deploying that fix: **zero new tombstones in 33 cycles.**
>
> **What this means for GW-01.** The refcount is still correct and worth keeping — but it
> is *insurance against a window the current ordering already closes*, not a fix for an
> observed crash. Its real value arrives with **GW-12** and **GW-23**, which deliberately
> change that ordering (GW-23 removes the blocking read that E5 currently relies on, and
> with it the accidental protection above). Re-rank A1 from "P0, happens on every mid-frame
> hangup" to **"P1, latent — unobserved in 33 cycles, but the guard must be in place before
> the ordering changes."** Do not cite A1 as a shipped crash fix.

#### A2. `open()` writes `g_ctx` fields without the lock
`cpp/gsm_audio_jni.c:139-202`. A `startCapture` on `GsmAudioOpen` overlapping a
`stopCapture` on main leaves `is_open = 1` with a closed/NULL pcm, or vice versa.

---

### P0 — device left in a broken state ("brick" class)

#### B1. Mute applied after the call already ended → mic + speaker dead until next call
`PjsipSipService.java:488-494` spawns `MuteControls` to run `DeviceMuteManager.muteAll()`
(~6 s of `su -c tinymix` per control, `DeviceMuteManager.java:250`). The DISCONNECTED
branch (`:498-500`) calls `unmuteAll()` **synchronously on the main thread**.

Two bad interleavings:
- Call ends before `MuteControls` is scheduled → `unmuteAll()` sees `isMuted == false`,
  returns immediately; `muteAll()` then runs and mutes the device **permanently**.
- Call ends during `muteAll()` → `unmuteAll()` blocks on the monitor for up to 6 s **on
  the main thread** → ANR.

#### B2. `AudioProfile` original-value maps are unsynchronized across three threads
`QualcommAudioProfile.java:40-41` (`micOriginalValues`, `micOriginalEnumValues`,
plain `HashMap`), `MediaTekAudioProfile.java:53` (`LinkedHashMap`).
Written by `setupMixer` on `GsmAudioOpen` (`:61` / `:69`), read+cleared by
`teardownMixer` on main / pjsua / ConfigReload (`:124` / `:98`), read by `enforceMixer`
on `MixerEnforce` (`:109` / `:87`).

**Failure:** `ConcurrentModificationException` inside teardown, or teardown reading an
already-cleared map → **the local mic stays muted after the call ends**. On MediaTek the
same map holds the `ADDA_UL` un-mute values, so the phone becomes unusable as a phone
until a full audio-path cycle or reboot.

#### B3. `openWithRetry` can re-arm capture after `stopCapture` finished
`GsmAudioPort.java:246-297`. `stopCapture` (`:338`) sets `isCapturing=false`, interrupts
and joins `openThread` for 1 s, closes native, tears down the mixer. But
`GsmAudioNative.open()` is **not interruptible** and can take longer than the join; when
it returns `true`, line `:293` sets `isCapturing.set(true)` and `:294` starts a fresh
`MixerEnforce` thread — after teardown.

**Failure:** an orphan `MixerEnforce` thread re-asserts call routing (and the mic mute)
every 2 s forever, with no open PCM. Same brick symptom as B2, plus a leaked thread per
occurrence.

#### B4. Charging can be left disabled
`BatteryLimitService.java:493` spawns a **new `SetCharging` thread per decision**, every
5 s from the enforce runnable (`:195-268`). `setCharging` is `synchronized`, so the
threads serialize — but in **arrival order, not decision order**. A stale
`setCharging(false)` can be applied after a fresh `setCharging(true)`.
`activeChargingPaths` (`:54`, plain `ArrayList`) is populated on the init background
thread (`:112`) and iterated by every `SetCharging` thread. `chargingDisabled` (`:53`) is
written from the receiver (main), the init thread and `SetCharging` threads, non-volatile.

**Failure:** charging stays off below the limit → the gateway phone discharges and dies.
Safety-relevant: this device is expected to run unattended.

---

### P0 — NPE / lost calls in the Telecom path

#### C1. `GatewayInCallService.currentCall` TOCTOU
`GatewayInCallService.java:31` — non-volatile field, written on main
(`onCallAdded:88`, `onCallRemoved:298`), read from pjsua workers via
`CallManager.hangupGsmCall → disconnectCall` (`:325`) and
`PjsipSipService.onSipCallConnected → getCurrentCall()` (`PjsipSipService.java:357-359`).

`disconnectCall()` reads the field three times (`:326`, `:327`, `:333`); `answerCall()`
twice (`:307`, `:311`); `rejectCall()` twice (`:319`, `:321`); the timeout runnable twice
(`:234`, `:236`). `onCallRemoved` can null it between any pair → **NPE on hangup**.
No `volatile` → a pjsua worker may also never observe the write at all.

GW-03 fixed every reader inside `GatewayInCallService` and made the field `volatile`. The
one left outside it — `PjsipSipService.onSipCallConnected`, which read
`getCurrentCall()` twice and dereferenced the second read — is **fixed by GW-10**, because
posting that callback widens the window rather than leaving it as it was. C1 is now closed.

#### C2. A second GSM call silently orphans the first
`onCallAdded` (`:88`) overwrites `currentCall` unconditionally; `onCallRemoved` (`:297`)
only clears it when the identity matches. A call-waiting / second inbound leg replaces
the tracked call; the original is never hung up and never bridged.

#### C3. Unbounded SIP retry chain per incoming GSM call
`makeSipCallWithRetry` (`:267-285`) re-posts itself every 500 ms with no attempt cap and
no cancellation other than `currentCall == null`. Two overlapping calls start two
independent chains; if SIP never registers the chain runs for the life of the process.

---

### P1 — call state machine races

#### D1. `CallManager` state is unguarded and driven from three threads — ✅ fixed by GW-11
`CallManager.java:47-50` and `:62` (`state`, `currentSipCall`, `pendingGsmDestination`,
`pendingGsmSimSlot`, `gsmCallPlacedTime`) — all plain fields. Only `hangupSipCall()`
(`:439`) is `synchronized`; `terminateAllCalls()` (`:468`) is not.

Writers: pjsua worker (`onSipCallState:241`), main (`onIncomingSipCall:135`,
`onGsmCallConnected:383`, `onGsmCallEnded:395`, watchdog `terminateAllCalls`),
ConfigReload (indirectly).

**Failure:** two concurrent `terminateAllCalls()` → two `onCallsTerminated()` →
two concurrent `audioBridge.stopBridge()` (see E1); state flips
`TERMINATING → IDLE → TERMINATING` and the watchdog reads a torn value.

**GW-11** closed all three halves. Every public method now opens with
`control.assertOnControlThread(...)`, so there is one writer and one reader thread; `state`
is assigned only by `transition(from, to, reason)`, which rejects — logs loudly, no-ops,
never throws — anything outside an explicit eight-row table; and `terminateAllCalls()`
returns immediately when the machine is already `TERMINATING` or `IDLE`, so the second
concurrent teardown cannot fire `onCallsTerminated()` again. The `synchronized` on
`hangupSipCall()` and the outer one on `PjsipSipService.hangupCall()` are gone with it
(plan §3c) — they protected nothing and were held across a pjsua2 BYE, a Telecom
`disconnectCall()` and a ~250 ms native drain.

The overloaded `SIP_INCOMING` is also gone: the GSM→SIP direction now runs
`IDLE → GSM_INCOMING → SIP_DIALING → BRIDGED` and `getStatusString()` stops telling the UI
an inbound GSM call is an incoming SIP one.

#### D1b. `onSipCallState(CONFIRMED)` does not check the call is the current one
`CallManager.onSipCallState` fires `listener.onSipCallConnected(call)` on CONFIRMED
without comparing `call` to `currentSipCall`. A stale or superseded call's CONFIRMED
therefore wires the audio bridge to **the wrong call** — `AudioBridgeManager.startBridge`
takes whatever call it is handed. With the diagnostic test call able to coexist with a
gateway call, this is reachable rather than theoretical.

Not in the original audit — found by the GW-06 agent. Fix belongs with **GW-12**
(generation-tagged bridge wiring) or **GW-11**; a bare identity check is not enough once
calls can legitimately be replaced.

#### D1c. `hangupSipCall()` never disposes on the disconnect path
`CallManager.onSipCallState` clears `currentSipCall` **before** calling
`terminateAllCalls()`, so `hangupSipCall()` hits its `currentSipCall == null` early return
and never calls `dispose()`. It works today only because `GatewayCall.onCallState` sets
`disposed = true` itself for DISCONNECTED before dispatching — an undocumented coupling
between two classes, either of which could be "cleaned up" independently.
Formalise in **GW-11**'s transition table.

#### D2. Outgoing SIP call is registered *after* it is placed
`PjsipSipService.java:679-682`:
```java
call.makeCall(uri, prm);
callManager.setOutgoingSipCall(call);   // too late
```
`makeCall` can deliver `onCallState(DISCONNECTED)` synchronously on the calling thread.
`onSipCallState` then finds `currentSipCall == null`, skips the clear, and
`setOutgoingSipCall` stores an **already-dead call** as the current one.

#### D3. Two independent sources drive the same GSM transitions — ✅ fixed by GW-13
`handlePhoneState` (`PjsipSipService.java:448`, from `PhoneStateListener`) and
`onGsmCallStateChanged` (`:473`, from `Call.Callback`) both call
`startAudioStreams()` + `onGsmCallConnected()` on connect and the stop pair on end,
with no defined ordering. Neither is idempotent: the connect path also spawns a
`MuteControls` thread each time, and the end path can run **after** a subsequent call's
start path.

**GW-13** made `Call.Callback` the only driver. `GatewayInCallService` now mints a
`gsmCallId` per tracked leg and threads it through `onGsmCallStateChanged` /
`onIncomingGsmCall` into `CallManager`, so an event naming a leg that is no longer current is
ignored and logged rather than applied — which is what closes the stale-stop race
independently of ordering. Connect and end are idempotent per leg, so exactly one
`startAudioStreams()` and one `DeviceMuteManager` lease happen per call.
`handlePhoneState` mutates nothing any more: it compares the modem's process-wide state
against the tracked leg and logs a discrepancy. Per plan §3d the unconditional
`stopAudioStreams()` moved onto the Telecom path, so a leg that never reaches `BRIDGED` still
tears the mixer down. `checkOrphanedCalls` asks `GatewayInCallService` for the tracked leg
instead of reading `lastPhoneState`.

#### D4. `SipTestCallManager` ownership check is racy — ✅ ownership half fixed by GW-10
`SipTestCallManager.java:90` (`call`, non-volatile) is read by `owns()` (`:141`) from
pjsua workers and written by `startInternal`/`stopInternal` on main. A stale read routes
a real gateway call into the test-call handler (skipping the GSM state machine entirely)
or the reverse. `PjsipSipService.startTestCall` (`:704`) checks
`callManager.getCurrentSipCall()` on the caller's thread and then posts — an incoming
gateway call can land in the gap, leaving both calls fighting over the single static
`gsmAudioPort`.

**GW-10** pulled GW-11 §4 forward: `GatewayCall` now carries a `final Owner`
(`GATEWAY` | `DIAGNOSTIC`) set at construction, and `PjsipSipService` demuxes on it rather
than on `owns()`. Ownership is immutable, so it can no longer go stale — which posting the
callbacks made mandatory, not merely tidy. The **start-gate race is still open**: the
`hasLiveSipCall()` check and `testCall.start()` now both run on the control thread, so
nothing can land between them there, but `start()` then hops to main where the diagnostic
internals live, and an incoming gateway call can still arrive in *that* gap.

**GW-11 §5** confirmed the control-thread half: the gate and `testCall.start()` are one
task on the control thread, and every route into `CallManager` goes through that same
queue, so nothing can land *between* them. What is still open is below.

#### D4c. The diagnostic-call admission gate asks the wrong question — NEW, for GW-13/GW-31
`PjsipSipService.startTestCall` admits a diagnostic call when
`callManager.hasLiveSipCall()` is false. That predicate is about the SIP *call object*, not
about the gateway being busy, and the two are not the same thing:

- in `GSM_INCOMING` a GSM call is ringing and no SIP call object exists yet, so the gate
  reads "free";
- in `SIP_INCOMING` / `SIP_ANSWERED` / `GSM_DIALING` the registered call may already be
  disposed while the GSM leg is very much up.

In each case a diagnostic call is admitted on top of a live gateway session and the two
fight over the single static `gsmAudioPort` — the same collision D4 describes, reached by
predicate rather than by race. The obvious repair, gating on `hasActiveCall()`, would
re-open **D2**: refusing on a disposed leftover is exactly what used to make the audio
bridge undiagnosable after a failed outgoing call. The gate that is both correct and
D2-safe is `getState() == IDLE`, which only became expressible once GW-11 split the states —
but changing admission behaviour is not a threading move, so it is filed rather than folded
into GW-11's diff.

#### D5. A bridged call logs `Audio streams stopped` twice — NEW, for GW-25/GW-31
Pre-existing, unchanged by GW-13, and benign — filed so it is not mistaken for a leak the
way `Audio bridge started` 3× was (plan §6).

When the GSM side ends a bridged call, `PjsipSipService.handleGsmCallEnded` calls
`audioBridge.stopAudioStreams()` unconditionally (it must — plan §3d), then tells
`CallManager`, whose `terminateAllCalls()` fires `onCallsTerminated()`, which stops the
streams again. Before GW-13 the first of the two was `handlePhoneState`'s IDLE branch, so the
count is the same as it always was.

Harmless at the port level: the second `GsmAudioPort.stopCapture()` finds
`isSessionActive(current)` false, so `ended = 0`, `releaseLocked(0)` releases nothing and the
enforce thread is already gone. Only the two log lines are real. It cannot simply be removed
from `onCallsTerminated()`: a **SIP-side** hangup reaches teardown through that listener and
the GSM `DISCONNECTED` can be up to 50.7 s behind it (finding E5), so dropping it would leave
the ALSA capture open for that whole window. The fix, if it is worth one, is to make
`stopAudioStreams()` log only when it actually stopped a session — which is an
`AudioBridgeManager`/`GsmAudioPort` change, out of GW-13's scope.

#### D6. Nothing acts on a GSM source discrepancy — ✅ FIXED (GW-25)
GW-13 kept `PhoneStateListener` as a cross-check: it logs
`GSM source cross-check: modem is IDLE but GSM leg N is still tracked` when the modem says
the call is over and the Telecom path never reported the end. That line describes precisely
the state plan §3d warns about — audio streams up, `MixerEnforce` re-asserting the mic mute
every 2 s, no call — and **nothing repairs it**, because repairing from that path is exactly
the second source of truth GW-13 removed.

The watchdog is the right owner, but `checkOrphanedCalls` cannot see this case either: it
returns early unless `callManager.hasActiveCall()`, and the leg in question has already left
the state machine. GW-25 should give the watchdog a GSM-liveness check that runs regardless
of `CallManager` state and stops the audio streams when Telecom has no live leg. Until then
the discrepancy is observable in `logcat -s GatewaySvc` and nothing more.

**Fixed by GW-25.** The tick's rule 2 runs before any `CallManager`-state check and fires on
`currentGsmCallId != NO_GSM_CALL && !hasLiveGsmCall()`, i.e. "we adopted this leg on Telecom
`STATE_ACTIVE` and Telecom no longer has it". The repair is `handleGsmCallEnded`, which stops
the audio streams unconditionally and releases the mute lease — so the state this finding
describes (streams up, `MixerEnforce` re-asserting the mic mute every 2 s, no call) is
repaired within one 3 s tick instead of persisting until reboot.

**The trigger is deliberately Telecom-based, never the modem.** Reading `lastPhoneState` here
would re-create the second source of truth GW-13 deleted; the `PhoneStateListener` stays
observational and its cross-check log stays exactly what it was. What changed is that
something now acts on the same condition, from the source GW-13 kept.

---

### P1 — audio bridge races

#### E1. `AudioBridgeManager` wiring state is unguarded; `stopBridge` can abort() the process
`AudioBridgeManager.java:28-35`: `gsmAudioPort` is **static**, `bridgeActive`,
`wiredCallMedia`, `wiredConfSlot` are plain instance fields.

`startBridge` (`:90`) is called from pjsua workers (`onSipCallConnected`,
`onCallMediaState`) and from main (`SipTestCallManager.wireMedia`).
`stopBridge` (`:177`) from whichever thread ran `terminateAllCalls`, from main
(`shutdownSip`), from ConfigReload (`doReloadConfig`), and from main
(`SipTestCallManager.unwireMedia`).

The class's own comment (`:196-203`) documents that disconnecting a destroyed conference
port trips a pjmedia assertion — an `abort()`, **not catchable**. `unwireBridge` (`:204`)
guards with `isLiveConfPort()` checks, but check and use are on different threads with no
lock: `startBridge` can re-wire between the liveness check and `stopTransmit`.

#### E2. Static port + instance flag desynchronise across a service restart
`gsmAudioPort` is static and survives `onDestroy`; `bridgeActive` does not. A new
`AudioBridgeManager` starts with `bridgeActive == false` while the static port is still
wired to a stale call → `stopBridge()` early-returns at `:178` and the conference links
leak permanently.

#### E3. `release()` is unreachable-but-live foot-gun
`AudioBridgeManager.release()` (`:274`) nulls the static `gsmAudioPort` while the pjmedia
RT thread may be inside `onFrameReceived`. It has **no callers** today — the codebase
works around it by never releasing (`PjsipSipService.java:260-262` comments say so), which
is a leak documented as a fix.

#### E4. `GsmAudioPort.openThread` / `enforceThread` are unguarded fields
`GsmAudioPort.java:56`, `:58`. `startCapture` (`:228`) reads/writes `openThread` on main;
`stopCapture` (`:338`) nulls it from main / pjsua / ConfigReload.

#### E5. A SIP-side hangup leaves the GSM leg up for 2–51 s, burning real minutes — P0
**Measured on device 2026-08-23**, five SIP-initiated hangups, `BYE` received → media
detached: **23.74 s, 4.47 s, 50.69 s, 1.79 s, 4.86 s.** The user-visible symptom is a GSM
call that stays connected and billed for up to a minute after the SIP party hung up.

**Not a Phase 0 regression.** `onFrameRequested`/`onFrameReceived` are byte-identical to
`2626f5d`, and the blocking `pcm_read` inside the JNI call predates Phase 0. GW-01's
`io_acquire`/`io_release` correctly take the reference and **release the lock before**
blocking in `pcm_read`, so they add no hold time. This bug was always there; it was simply
never measured.

**The app's own code is not slow.** Once pjsua delivers `DISCONNECTED`, the GSM leg drops
in **6 ms** and the whole teardown is clean — `Closing audio` → `Audio closed` in 2 ms
(GW-01's drain), every mixer switch restored to `0`. The entire delay is upstream.

**Where it blocks.** On the pjsua worker, between two adjacent pjmedia log lines, with
nothing logged in between for the whole interval:
```
21:41:47.242  pjsua_media.c  Call 1: deinitializing media..
21:41:47.245  [DISCONNECTED] ... RTP stats dump ...
              <-- 50.687 s, thread logs nothing -->
21:42:37.932  udp0x...       UDP media transport detached
21:42:37.936  GatewayCall: Call state: DISCONNECTED (6)
21:42:37.942  GatewayInCall: Disconnecting GSM call (state: ACTIVE)
```
That span is where `stop_media_session` removes the call's conference port.

**Why it is starvation, not a timeout.** Four independent signs:
1. The durations are scattered over 1.8–50.7 s with no clustering — no timeout constant
   behaves like that.
2. The conference clock thread runs *throughout*: `onFrameReceived` keeps incrementing on
   schedule (22000 @ 21:37:10, 22500 @ 21:37:20, mid-block), and `MixerEnforce` keeps
   re-asserting every 2 s. The bridge is fully live while the removal waits.
3. Nothing external correlates with the release — no GSM event, no timer, no config
   change. It simply eventually wins.
4. **The asymmetry is decisive.** GSM-initiated hangups take **15 ms / 107 ms / 1.77 s**,
   because `terminateAllCalls` runs first and stops the audio port *before* pjsua touches
   the media. Same teardown code, port already quiet, no delay.

**Mechanism — PROVEN.** Native backtraces captured 2026-08-23 22:04:44, ~0.5 s into a
block, by a `debuggerd -b` trap armed on the `deinitializing media` log line. Full dumps
in [evidence/E5-conf-mutex-starvation.md](evidence/E5-conf-mutex-starvation.md). Both
threads, same instant:

*Waiter* — pjsua worker, still inside handling the received BYE:
```
#02 NonPI::MutexLockWithTimeout
#03 pj_mutex_lock+28
#04 pjmedia_conf_remove_port+44
#05 pjsua_aud_stop_stream+148
#07 pjsua_media_channel_deinit+536
#12 pjsip_dlg_on_tsx_state ... #20 pjsip_tpmgr_receive_packet
```

*Holder* — conference clock thread, inside our callback:
```
#00 __ioctl+8
#02 pcm_read+232                             libgsm_audio.so
#03 Java_..._GsmAudioNative_readFrame+208    libgsm_audio.so
#05 GsmAudioPort.onFrameRequested+388
#06 SwigDirector_AudioMediaPort_onFrameRequested+176
#14-#17 libpjsua2.so                         (conf.c get_frame — owns the mutex)
```

So the conference bridge holds its mutex across the SWIG director callback, and that
callback blocks in an ALSA `ioctl`. `pjmedia_conf_remove_port`'s first act is to take that
same mutex. The callback re-enters every 20 ms tick, so the mutex is held almost
continuously and a plain non-FIFO `pthread_mutex` acquire starves unboundedly — matching
the measured 1.8–50.7 s spread. This is exactly the violation ROADMAP rule 3 warns about,
reached from the opposite direction.

**Caveat on the numbers.** The measured build is debuggable, so `CheckJNI` is active
(frames #10–#11 of the holder) and inflates every JNI crossing. That worsens the hold time
but is not the cause — the block is the `ioctl`, which a release build does identically.
Re-measure the spread on a release build before quoting these figures elsewhere.

**The fix.** With the mechanism proven, only one option actually addresses it:

**Decouple ALSA from the conference callback.** A dedicated I/O thread owns `pcm_read` /
`pcm_write`; `onFrameRequested`/`onFrameReceived` do nothing but copy from/to a lock-free
ring buffer. The conference mutex is then held for a `memcpy` instead of a device
round-trip, and *every* operation that needs that mutex stops starving — not just this
teardown. This is **GW-23**'s territory and closes H2/H3 at the same time.
Underrun/overrun policy must be explicit: the RT side emits silence rather than waiting,
which is what a real-time path should have done from the start.

**An earlier "stop the port first" hook was considered and rejected.** The idea was to
mirror the GSM-initiated path — which is fast precisely because `terminateAllCalls` quiets
the port before pjsua touches the media. It does not work on the SIP-initiated path: the
backtrace shows `pjsua_media_channel_deinit` is called *underneath*
`pjsip_dlg_on_tsx_state` while processing the BYE, i.e. **before** any state callback
reaches Java. `onCallState(DISCONNECTED)` fires after the block, not before it — that is
the 6 ms measured at the end. `onCallTsxState` exists in the bindings and fires earlier,
but relying on the internal ordering of pjsua's disconnect path to win a race against its
own media teardown is exactly the kind of "prove liveness with no window in between"
reasoning ROADMAP rule 4 forbids. Do not ship it.

**Backstop, not a fix.** **GW-25**/H9's reverse-orphan detection bounds the billing damage
(GSM leg live, SIP leg gone → terminate) and is worth having regardless, but it does not
touch the block. If GW-23 is far off, this is the mitigation to ship first, because it caps
the worst case at one watchdog interval instead of 51 s.

---

### P1 — SIP endpoint & account lifecycle

#### F1. Endpoint creation is check-then-act on a static — P1 — ✅ FIXED (GW-15)
`SipEndpointManager.java:31` (`endpoint`, `endpointUseTls`, static, non-volatile),
`createEndpoint:132`. `SipInit` and a reconnect (or ConfigReload) can both observe
`endpoint == null` and both call `new Endpoint().libCreate()` → the second `libCreate`
on an already-created pjsua library aborts natively.

GW-07 made both fields `volatile`, which made the reads defined but left the race. GW-15
closed it by serialisation: `createEndpoint()` asserts the control thread, so the two threads
that could both observe `null` no longer exist. The main-thread hop for `new Endpoint()`
itself is preserved — pjsua auto-registers only the thread that loaded the native library.

#### F2. `hasTransport()` permanently registers whatever thread calls it — P1 — ✅ FIXED (GW-15)
`SipEndpointManager.java:85` calls `registerThread(Thread.currentThread().getName())`
from inside a *query*. Callers include NanoHTTPD workers, `ConfigReload`, the reconnect
runnable — all short-lived. pjlib allocates a thread descriptor from the pjsua pool and
**never frees it**; when the thread dies the descriptor dangles. Pool grows monotonically.

GW-15 removed the registration from the query and from `createEndpoint`'s endpoint-reuse
path; both assert the control thread instead. The transitive caller set was enumerated and
each proved control-thread first — `createEndpoint`'s reuse branch (only caller
`initializeSip`), `attemptReconnect`, and `SipAccountManager.createAccount` (only callers
`initializeSip` and `doReloadConfig`). `registerThread` stays public for its one legitimate
caller, `GatewayControlThread`, and the `libIsThreadRegistered()` short-circuit stays.

Outstanding: the "pool capacity is flat across ~200 reconnects" measurement is on-device work
and has not been run.

#### F3. `initializeSip()` runs on the main thread on the reconnect path — P1 — ✅ FIXED (GW-10)
`attemptReconnect` (`PjsipSipService.java:275`) executes on the main handler and calls
`initializeSip()` (`:283-286`) when the endpoint isn't ready. That runs
`audioBridge.initialize()` (root shell-out + full mixer enumeration) and
`accountManager.createAccount()` (network) **on main** → multi-second freeze / ANR.
It also registers the main thread with pjlib under the name `"SipInit"`.

#### F4. `account` can be deleted from under an in-flight `sendSipMessage`
`SipAccountManager.java:24` (`account`, non-volatile), `deleteAccount:142` sets it null on
`ConfigReload`. `PjsipSipService.sendSipMessage` (`:543`) captures the account at `:547`
and calls `buddy.create(account, …)` at `:565` — potentially on a deleted native object.

#### F5. `reloadConfig` synchronises with `Thread.sleep`
`PjsipSipService.java:782-793`: posts `terminateAllCalls()` to main, sleeps 100 ms, then
tears the bridge down and deletes the account, then sleeps 500 ms. If main is busy the
hangup has not happened when the account is deleted.

#### F6. `ReconnectionStrategy` flags are non-volatile and set from three threads — P1 — ✅ FIXED (GW-15)
`ReconnectionStrategy.java:24-25`. `scheduleReconnect()` is called from `initializeSip`
on `SipInit` (`PjsipSipService.java:253`) and from `attemptReconnect` on main (`:293`);
`setEnabled` from main and from the broadcast receiver. `pending` races → duplicate or
dropped reconnects.

GW-15 moved both `ReconnectionStrategy` and `ServiceWatchdog` onto the control thread's
looper — handler, timer body and state — and asserts the thread on every mutator. The
check-then-set is atomic by confinement, so the `volatile` GW-07 added could come back off.
`ServiceWatchdog.running` is confined the same way; its old `assertMainThread` helper became a
control-thread assertion.

Note both classes own their `Handler` rather than posting through `GatewayControlThread`, so
that `cancel()`/`stop()` cannot remove other components' messages. That bypasses
`GatewayControlThread.dispatch()` and with it the lazy pjlib registration, so both timer
bodies invoke their callback through `runOrPost` — which dispatches inline and registers. Not
theoretical: a SIP init that constructs the `Endpoint` and then fails before
`registerWithPjlib()` leaves a non-null endpoint and an unregistered control thread, and the
reconnect it schedules calls `hasTransport()` on exactly that.

---

### P1 — main-thread blocking (ANR)

#### G1. SMS forwarding runs entirely on the main thread — ✅ FIXED (GW-21)
`SmsHandler.processInbox()` (`:149`) is invoked from a `ContentObserver` bound to the main
handler (`:97`) and does a `ContentResolver.query` inline, then calls back into
`PjsipSipService.handleIncomingGsmSms → sendSipMessage` (`:543`) which performs
`buddy.sendInstantMessage` — network I/O — still on main.

**Fixed by GW-21**, and by the time it got there most of the finding had already moved:
GW-10/GW-14 put the SIP send *and* `markAsRead` (including its `su` fallback) on the control
thread. What was left on main was exactly the `ContentObserver` and the `ContentResolver`
query behind it. The observer is now built with `new Handler(control.getLooper())`, so
`onChange → processInbox → send → markAsRead` share one thread and **no part of the inbound
SMS path touches main any more**. `processInbox`, `markAsRead` and `unprocessSms` assert it.

Three things had to come with it:

- **The cursor.** `onIncomingSms` reaches the service through `control.runOrPost(...)`, which
  dispatches *inline* when the caller is already the control thread — so once the observer
  moved, the blocking SIP send would have run **inside the open `Cursor` loop on every path**,
  not just on the post-registration retry where it already did. `processInbox` is now two
  phases: `collectForwardable` reads the batch and closes the cursor, `forward` sends. The
  add-then-send invariant is kept by moving the `inFlightIds.add` *earlier* — a row is marked
  the moment it passes the suppression checks, while the cursor is still open — so the whole
  batch is marked before any of it is sent. That can only over-suppress within a batch, never
  under-suppress; the tail of a batch that is never handed over (stop mid-scan) is released in
  a `finally`.
- **Debounce.** Every `markAsRead` mutates the provider and re-triggers `onChange`, so each
  forwarded message cost a redundant full inbox scan. Changes now coalesce into a 250 ms
  window **anchored on the first change of a burst**, not restarted by each one, so a stream
  of provider writes cannot starve the scan. The old "no debounce, race with MessagingApp"
  comment named a real race — `read = 0` means a message another app marks read first is never
  forwarded — but not one 250 ms changes: nothing marks a message read but a human opening the
  conversation, and the path already carried seconds of latency (the post-REGISTER retry scan).
- **Teardown.** Unregistering the observer from main while `onChange` runs on the control
  thread is a race the pre-GW-21 code did not have. `stop()` latches a `volatile` flag on the
  caller's thread — an in-flight scan sees it and stops handing messages over — and **posts**
  the unregister to the control thread, where it cannot overlap a scan. `onDestroy` already
  calls `stop()` before `control.quitSafely(...)` (GW-26), which drains what is queued, so it
  still runs; main never waits for it.

Verified by `SmsPipelineThreadingTest` (8 cases: the pipeline's thread identity, the observer's
dispatch looper, no cursor open at callback time, the whole batch marked before the first send,
stop-during-an-in-flight-scan, stop-before-start, burst coalescing, and no starvation) with
GW-27's 14 `SmsDuplicateSuppressionTest` cases still green.

#### G2. `shutdownSip()` blocks main — ✅ FIXED (GW-26)
`PjsipSipService.onDestroy:196` → `shutdownSip:257` → `hangupAllCalls()` +
`deleteAccount()` (un-REGISTER, network) on the main thread.

GW-12 had already moved `stopBridge`/`stopAudioStreams`/`teardownMixer` onto the control
thread, leaving `deleteAccount()` + `endpointManager.shutdown()` as the residue. GW-26 moves
those too: `onDestroy` now posts one `teardownOnControlThread()` task carrying the timer
disarm, the bridge unwire **and** `shutdownSip()`, and waits only on the bounded
`quitSafely` join. `shutdownSip()` asserts the control thread. Nothing on main touches pjsua2
at destroy any more, which is also what closes **H11**.

The join bound went 1500 → 3000 ms while main's worst case went *down*, from "1.5 s plus
however long the PBX takes to answer an un-REGISTER" to a hard
`CONTROL_QUIT_TIMEOUT_MS + MUTE_RESTORE_TIMEOUT_MS` = 5 s. `onDestroy` logs its own
main-thread duration.

#### G3. `unmuteAll()` on main — see B1.

---

### P2 — resource, correctness and hygiene

#### H1. `RootHelper` static state is unsynchronized; output capture is not thread-safe — P2 — ✅ FIXED (GW-20)
`RootHelper.java:21-23` (`hasRoot`, `suProcess`, `suOutputStream`, all static, plain).
`execRoot` (`:61`) builds output in a `StringBuilder` written by a reader thread and read
by the caller after `join(1000)`; if the join **times out**, `:111` reads a
`StringBuilder` that is still being appended → `StringIndexOutOfBoundsException` or torn
output. `execInShell`/`startRootShell` (`:137-171`) can spawn two `su` processes or NPE.
Each `execRoot` spawns 3 threads; `setupAlsaPermissions` runs on every capture open.

**Fixed by GW-20.** What landed, and what deliberately did not:

- **The return contract, which was the systemic bug and is not in this finding's original
  text.** `execRoot` logged a non-zero exit and then returned `output.toString().trim()`
  anyway — an empty string, never `null` — so every caller testing `execRoot(...) != null`
  was blind to a failed command. That is the shared root cause of **H13** and half of
  **B1e**. There is now a `RootResult` carrying exit code + stdout + stderr + `success()`,
  where `success()` is `exitCode == 0` and every failure path uses a negative sentinel, so
  a non-zero exit cannot be reported as success. `RootHelper.run(String[,int])` is the new
  entry point; **GW-27 consumes it**. `execRoot` is kept source-compatible but returns
  `null` for any failure, which corrects `SmsHandler`'s and `BatteryLimitService`'s
  existing null checks without touching either file.
- **The handoff race.** Each stream is drained by its own daemon thread into a
  thread-confined `StringBuilder` and published as a finished `String` through a
  `FutureTask`. A reader that does not complete in time yields a **failed result**, never
  partial output.
- **The timeout ordering.** The process timeout is checked and the process killed *before*
  the readers are reaped. The old code joined both readers (1 s each) first, so a hung `su`
  cost the caller `timeoutMs + 2000 ms`.
- **Both pipes are always drained**, so a chatty command cannot deadlock. `execRootCode`
  was the concrete instance (bare `waitFor()` on an undrained process) and now delegates to
  `run()`.
- **`setenforce` once per process**, behind a CAS flag reset on failure; `chmod 666
  /dev/snd/*` still runs on every call, because the HAL recreates those nodes. Implemented
  inside `setupAlsaPermissions` rather than at `GsmAudioPort`'s two call sites, which GW-23a
  owns — see PHASE-2-PLAN §2.1.
- **Every remaining unbounded `Process.waitFor()` is bounded and drained**:
  `QualcommAudioProfile` ×2 (deleted with `TinymixControls`, see B1e), `TinymixManager`,
  `PermissionManager` ×3, `BootReceiver`. `BootReceiver`'s fallback now also fires when
  `am start` runs and fails, not only when it throws.
- **`destroy()` rather than `destroyForcibly()`** — the latter is API 26 against
  `minSdkVersion` 23 and was only passing lint via a baseline entry. On Android `destroy()`
  is already SIGKILL.
- **NOT done: the single-thread root executor** the brief's §2 asks for. Serialising is a
  net loss until two other things move: `PowerController.disableBatteryOptimizations` is
  6 × `execRoot` at 5 s each on its own thread, and putting the per-call
  `setupAlsaPermissions` behind that burst stalls call setup at service-start time; and
  `SmsHandler.markAsReadWithRoot` still runs on **main**, so serialising lets main block
  behind another thread's `su` — a stall the current design does not have. The reasoning is
  recorded in `RootHelper`'s class javadoc so it is not silently re-litigated.
- **NOT done: deleting the dead surface.** Per ROADMAP rule 8 that is **GW-31**'s sweep.
  See H1c below for the list; everything in it is now marked `@Deprecated` with a pointer.

#### H1c. `RootHelper`'s live surface is three methods; eight more have no callers — for GW-31
The only production entry points are `execRoot(String)`, `execRoot(String,int)` (both now
`run(...)` wrappers) and `setupAlsaPermissions()`. Zero callers anywhere in the tree:
`startRootShell`, `execInShell`, `stopRootShell`, `checkRoot`, `execRootCode`,
`copyFileAsRoot`, `extractAsset`, `grantAllPermissions` (`ui/PermissionManager` has its own
duplicate). GW-20 left them correct rather than deleting them: `execRootCode`'s pipe
deadlock is fixed by delegation, `checkRoot`'s unbounded `waitFor` is gone, and all eight
carry `@Deprecated` naming GW-31. **H1b** (the `startRootShell` double-spawn) is deliberately
*not* fixed — the API it lives in is scheduled for deletion, and the javadoc says so.
Add to the GW-31 sweep alongside H7d/H7e and dead `deleteSms` / `setSoundCard`.
(`SmsHandler.deleteSms` now carries the same `@Deprecated`-naming-GW-31 marker, added by
GW-27, which had to touch it and confirmed again that it has no callers anywhere.)

#### H2. Per-frame JNI churn on the RT thread — ✅ FIXED (GW-23a)
`GsmAudioPort.onFrameRequested:160-168` and `onFrameReceived:205-207` copied the frame
one `ByteVector.add()` / `get()` at a time. Any GC pause here is an audible dropout.

**The original numbers were 2× low, and they were not the worst part.** `frameSize` is
**320 bytes**, not 160 samples, and `ByteVector` is `std::vector<unsigned char>` — one
element per byte. So the loops ran 320 times, i.e. **≈32 500 JNI transitions/s**, not
16 000. Alongside that, per frame:
- `add(Short)` / `get(int)→Short` **autobox**, and the values are `b & 0xFF` ∈ [0,255]
  while `Short.valueOf` caches only −128…127 → **~4 000 allocations/s per direction on the
  RT thread**;
- `MediaFrame.getBuf()` returns `new ByteVector(cPtr, false)` — a fresh **finalizable**
  wrapper, twice per frame → **100 finalizable objects/s** onto the finalizer queue;
- repeated `push_back` into a fresh vector reallocates ~10 times per frame.

The boxing and the finalizer pressure explain GC-pause dropouts far better than the
transition count does.

Fixed by moving both copies into C as a single `memcpy` each
(`GsmAudioNative.pjBufRead/pjBufWrite`), reaching the vector's storage through the
hand-written `org.pjsip.pjsua2.PjByteVectorAccess`. Java-side allocation on both callbacks
is now zero. **This creates an ABI dependency** on `pj::ByteVector` being
`std::vector<unsigned char>` and on libc++'s vector layout — verified by disassembling the
vendored `libpjsua2.so`, and re-verified at runtime by `GsmAudioPort`'s constructor, which
falls back to the old loops on any mismatch. See that class before rebuilding PJSIP.

One C++ allocation per frame is irreducible from the app side: pjsua2's `get_frame`
stack-constructs a fresh `MediaFrame` per tick, so its `buf` always starts empty.

#### H3. Per-frame `malloc`/`free` in the resampler — ✅ FIXED (GW-23a)
`cpp/gsm_audio_jni.c:304-319` allocated the upsample buffer on every frame (50 Hz) on the
RT thread. Now allocated once in `open()` (`playback_rate / 50 * playback_channels`
samples = 960 on the MediaTek path), borrowed through GW-01's `io_ref`, and freed in
`close()` **after** the drain — the same lifetime discipline the PCM handles have.
`writeFrame` bounds-checks against the capacity instead of trusting the arithmetic.

**MediaTek-only.** Qualcomm has `capture_rate == playback_rate` and never enters the
branch, so this can only be validated on merlinx.

#### H4. Web UI writes a preference key nothing reads — P1 — ✅ FIXED (GW-24), on-device check outstanding
`WebConfigServer.java:156` reads and `:267` writes `mic_mute_controls` as a **StringSet**;
`GatewayConfig.KEY_MIC_MUTE_CONTROLS` (`:70`) is `"mic_mute_decs"` read as a **String**.
The in-app UI (`MainViewModel.java:404`, `:517`) uses `GatewayConfig` and is correct — only
the web interface is disconnected, so its mute-control selection is silently discarded.
`postConfig` also calls `audioEditor.apply()` three times (`:251`, `:268`, `:274`) →
partially-applied config.

**Fixed by GW-24.** One key, one type: the canonical `mic_mute_decs` comma-separated
String, with a one-shot migration in `GatewayConfig.init` that folds the legacy StringSet
in and removes it only after committing the new value and reading it back. Values are read
through `getAll()` and type-checked, so the both-keys-with-mismatched-types device — the
one that would have thrown `ClassCastException` inside `PjsipSipService.onCreate` — is
handled, and `getMicMuteControls()` itself can no longer throw even if the migration could
not write. Conflict rule: the in-app list wins, because it is the one that has actually
been reaching the mixer.

The migration is only sound if nothing reads preferences ahead of it, so `GatewayConfig.init`
now runs in **`GatewayApplication.onCreate`** (it was called from three components only,
while five others read the same files by raw name and could run first), *and* every one of
those five now goes through `GatewayConfig` — `WebConfigServer`, `GatewayControlReceiver`,
`DeviceMuteManager`, `BootReceiver`, `BatteryLimitService`. Entry points that can start a
process use `GatewayConfig.from(context)`, which cannot bypass `init`. Nothing outside
`config/` names a preference file or key any more, which is what makes the drift structural
rather than fixed once.

Writes are batched through `GatewayConfig.Editor`: one `SharedPreferences.Editor` per
preference file, one `apply()` each, at the end of the request. `postConfig` was up to five
applies across three editors; `GatewayControlReceiver.configure` was three, with the mute
preset applied outside the `changed` guard the other two obeyed.

`WebConfigServer`'s hardcoded defaults (`192.168.5.95`, `gateway`, `gateway123`, `101`, and
an empty realm where `GatewayConfig` uses `*`) are gone — it renders `GatewayConfig`'s. That
also shrinks GW-30's blast radius: the page is served without authentication and was
publishing a credential that was not even the real one.

Regression tests: `MicMuteControlMigrationTest` (11), `WebConfigRoutingTest` (8),
`MuteConfigLivenessTest` (4), plus 4 in `GatewayConfigTest` for the batch semantics and the
single source of defaults.

> **⚠️ This is the change that arms `QualcommAudioProfile`'s mute loop.** See B1e: the loop
> over `config.getAllMuteControls()` has never executed on a real device, because the list
> was always empty *because of this bug*. GW-20 prepared the landing (an unreadable control
> is skipped rather than muted against a fabricated original), and
> `QualcommConfigReloadTest.unreadableControlIsStillSkippedNotMuted` pins that, but the
> first real execution is on hardware. **Verify before trusting it:** with the custom preset
> and a control selected, place a GSM call and check `tinymix -D 0 get "DEC1 Volume"` reads
> 0 during the call and its pre-call value after it, and that `Not muting '…'` in logcat
> only ever names controls that genuinely do not exist on the device.

**Also found in the same area** (Phase 2 plan §2.5, fixed here): `DeviceMuteManager` read
`currentPreset` once in its constructor and `savePreset` had no callers, so a preset change
from either UI wrote preferences the live singleton never re-read — switching *to* `custom`
did nothing until the process restarted. `refreshSoundCard()` is now `refreshFromConfig()`
and re-reads the preset as well as the card before every mute.

#### H4b. Mute-control config is captured once and never refreshed — P2 — ✅ FIXED (GW-24) for everything but the sound card
`QualcommAudioProfile` copies `config.getAllMuteControls()` into a final list **in its
constructor** (`:48`). The profile is built by `GsmAudioPort`'s constructor (`:75`), which
runs once from `AudioBridgeManager.initialize()` (`:68`) — and `gsmAudioPort` is `static`
(`:28`), so it survives service restarts. Changing the mute controls therefore has no
effect until the **process** restarts, even though the UI reports the change as saved and
`reloadConfig` claims to have applied it. Same shape for `captureDevice`, `playbackDevice`
and `multimediaRoute` (`:45-47`).

**Narrower than first written.** Gains were never affected — `AudioBridgeManager` re-reads
`getTxGain()/getRxGain()` on every `startBridge`. `MediaTekAudioProfile` takes no
`GatewayConfig` at all, only compile-time constants. And since GW-12 the port is not a bare
`static` field but lives in the process-scoped `AudioBridgeManager.Wiring` holder — same
lifetime, different shape.

**Fixed by GW-24** by re-reading in `setupMixer`, which runs per call, rather than by
rebuilding the port. Rebuilding was rejected: `Wiring.port` is documented "never replaced
once published", so a rebuild would have to deal with a live call holding the old port
*and* the `MixerEnforce` thread it started — the orphaned-enforce-thread failure GW-08
exists to prevent. `doReloadConfig` does not rebuild it either, and `audioBridge.initialize()`
would early-return.

The four values are one immutable `Session` object behind one volatile reference, not four
fields, because teardown and enforce **must** use the values setup used: restoring against a
control list the operator edited mid-call would leave a muted control with nobody left to
unmute it (the shape of B1c/B2). A config change therefore lands at the next `setupMixer`,
never mid-call.

**Residual, deliberate:** the sound *card* still needs a process restart. `GsmAudioPort`
snapshots `config.getAudioCard()` in its own constructor (`:227`) and passes it into
`setupMixer(card)`; that file is out of GW-24's scope and changing it runs into the same
port-lifetime problem. So does the SoC profile selector (`AudioProfileFactory.select` runs
once). `MainViewModel`'s toast says exactly this instead of the old flat "Restart to apply",
which now under-claims: *"Route, devices and mute controls apply on the next call; sound
card and SoC profile need a restart."*

#### H5. `GatewayConfig` singleton is unsafely published
`GatewayConfig.java:97` — non-volatile static, `init` is `synchronized` but
`getInstance()` (`:119`) is not → another thread can observe a partially constructed
object. Same shape for `PjsipSipService.instance` (`:50`) and
`GatewayInCallService.instance` (`:30`), both read from pjsua workers and NanoHTTPD.

#### H6. `GatewayCall.service` is nulled while callbacks may be reading it
`GatewayCall.java:25` — `disposed` is volatile, `service` is not. `dispose()` (`:50`)
nulls it; `onCallState` (`:79`) and `onCallMediaState` (`:97`) do
`if (service != null) service.…` — TOCTOU. `relayDtmf` (`:158`) gets this right by
copying to a local; the others don't.

#### H7. pjsua2 objects are never deleted — ✅ FIXED (GW-22)
No `Call.delete()` (deliberate, `CallManager.java:258-260`), no `delete()` on the
`CallInfo` / `CallMediaInfoVector` / `AudioMedia` values pulled per callback.
Each call leaks a SWIG director plus several C++ shadow objects. Over days of unattended
operation this is unbounded.

> **AMENDED by GW-22 on three counts.**
>
> - **"Unbounded" is wrong.** Every SWIG proxy's `finalize()` calls `delete()`, so these were
>   *finalizer-deferred releases*, not permanent leaks. The soak's native-heap slope will be
>   far flatter than this text implies, and the finding must not be sold on unboundedness. The
>   real cost is non-determinism: native memory the GC heuristic cannot see, a finalizer queue
>   growing at ~30 owned objects per completed call, and — for `Call` — a native destructor on
>   an unregistered thread.
> - **Half the named objects are not ours.** `CallMediaInfoVector` (`CallInfo.getMedia()`),
>   `CallMediaInfo` and the `AudioMedia` from `typecastFromMedia` are all constructed
>   `(ptr, **false**)` — views into something pjsua owns. Deleting them is a no-op at best.
>   The owned set is `CallInfo`, `AccountInfo`, `AudioMediaVector2`, `ConfPortInfo`,
>   `StreamInfo`, `StreamStat`, `CodecInfoVector2`, `IntVector` *from `transportEnum()`*, and
>   anything the Java side constructs.
> - **The two hottest sites are not in this text.** `GatewayCall.onCallState` takes a `CallInfo`
>   on *every* SIP state change (~5–6 per call), and `AudioBridgeManager.startBridge` called
>   `SipDiagnostics.dumpAndLog` unconditionally on every successful wiring — a **production**
>   path costing ~8 owned objects and ~20 logcat lines per call for a diagnostic. It now asks
>   `isTransmitting` and dumps only when the answer is wrong.
>
> **"It will be GC'd eventually" cuts the opposite way, and that is the headline for `Call`.**
> `Call(Account)` is constructed with `swigCMemOwn = true` and
> `director_connect(..., true, true)` — a **weak** global ref. The Java object *is* collectible,
> so `finalize()` runs `delete_Call` on the **FinalizerDaemon thread, which is not registered
> with pjlib** — the same thread class behind all eight historical tombstones. The "never
> delete" policy never prevented deletion; it deferred it to an unpredictable moment on the
> worst possible thread.
>
> **The brief's safety argument for deleting was false and was not built on.** "Callbacks are
> posted, so the disconnect handler runs after the pjsua worker returned" confuses queue entry
> with stack unwind; **E5** measured that worker inside `pjsua_media_channel_deinit` for up to
> 50 s around exactly this point. `call/CallGraveyard` therefore defers the delete onto the
> control thread (which *is* pjlib-registered) and gates it on the only checkable signal the
> bindings expose, `Call.getId() == PJSUA_INVALID_ID`. That is **evidence, not proof**, and the
> class says so: ROADMAP rule 4's "no window" cannot be satisfied here. A call whose id never
> goes invalid within 60 s is **abandoned to the finalizer, never force-deleted** — refusing to
> delete is always an option that deleting a live call is not.
>
> `GatewayCall` now counts every construction and every destruction, whoever performs it, and
> `GatewayStatus` publishes them as `callsCreated` / `callsDeleted` (`getCallsAlive()` is the
> difference). A widening gap plus a `Call deleted on FinalizerDaemon` line is the exact
> evidence that the deterministic path failed.
>
> **Owed on hardware:** the 500-cycle soak, `callsAlive` equal to the number of active calls at
> the end, and **zero tombstones** — a premature delete presents as a native crash, so the soak
> is the safety test as much as the leak test. Unmeasured until then. `PjsipLogWriter`'s strong
> reference is deliberately untouched.

#### H8. `onDestroy` has no null-guards for a partially constructed service — ✅ FIXED (GW-26)
`PjsipSipService.onDestroy:177` calls `watchdog.stop()`, `reconnection.setEnabled(false)`,
`audioBridge.stopBridge()`, `powerController.release()` unconditionally. If `onCreate`
threw after `instance = this` (e.g. `GatewayConfig.init` failure at `:107`), every one of
those is an NPE inside `onDestroy`.

**The stated premise is weak; the conclusion was right for a different reason.** An exception
out of `Service.onCreate` crashes the process before `onDestroy` runs, so "onCreate threw
partway" is barely reachable. Two paths that *are*:

1. **onCreate ran, `onStartCommand` never did** — a bind-only service. `smsHandler` and
   `webServer` are null.
2. **`libpjsua2` is missing.** The static initialiser catches `UnsatisfiedLinkError` and lets
   the service run without it. Every pjsua2 call then throws an `Error`, and
   `SipAccountManager.deleteAccount()` catches only `Exception` — so that `Error` escaped
   `shutdownSip()` on main and **skipped `powerController.release()`, the telephony unlisten,
   the mute restore and `stopForeground`**, leaking `Gateway::CpuWakeLock`. A null check would
   not have helped; catching `Exception` would not either.

GW-26 wraps every teardown step in its own `teardownStep(name, step)`, which catches
**`Throwable`**, and null-guards each manager. `powerController.release()` now runs on every
path. Covered by `PjsipSipServiceLifecycleTest`, which fault-injects an `UnsatisfiedLinkError`
from the step immediately before it.

#### H9. Watchdog only detects one orphan direction — ✅ FIXED (GW-25), false-positive run outstanding
`checkOrphanedCalls` (`PjsipSipService.java:630`) terminates a SIP call with no GSM leg.
The reverse — a live GSM call with no SIP leg — is never detected, so a failed bridge can
burn GSM minutes indefinitely.

**Fixed by GW-25.** The tick now carries five rules, and the interesting part of each is the
predicate that keeps it off a healthy call:

| Rule | Predicate | Remedy |
|---|---|---|
| Max call duration (2 h) | `anyCallUp && now - callUpSinceWallMs >= 2 h` | terminate |
| **D6** | `currentGsmCallId != NO_GSM_CALL && !hasLiveGsmCall()` | terminate |
| **H9 reverse orphan** | `currentGsmCallId != NO_GSM_CALL && hasLiveGsmCall() && !hasLiveSipCall() && !isInGracePeriod()`, sustained 45 s | terminate |
| §2b | `state == IDLE && hasLiveSipCall()` | `hangupSipCall()` |
| H9 original | `state != IDLE && !isInGracePeriod() && !hasLiveGsmCall() && currentSipCall != null` | terminate |

**"The GSM leg reached ACTIVE" is spelled `currentGsmCallId != NO_GSM_CALL`, not
`hasLiveGsmCall()`.** The latter is also true for RINGING, DIALING, CONNECTING and HOLDING;
`currentGsmCallId` is set only by `handleGsmCallConnected`, i.e. only on Telecom
`STATE_ACTIVE`, and is control-thread-confined. Both directions read the same two signals —
treating RINGING as "live GSM" in one rule and "no GSM" in another is how a watchdog produces
contradictory terminations.

**The 45 s dwell is the entire safety argument for the inbound direction**, and it is not
optional. `isInGracePeriod()` is permanently `false` for the whole GSM→SIP direction —
`gsmCallPlacedTime` is assigned in exactly one place, `placeGsmCall()`, the SIP→GSM dial. On
`MODE_ANSWER_FIRST` the GSM leg is answered *first*, so it goes ACTIVE and is adopted while
`makeSipCallWithRetry` still has up to `40 × 500 ms ≈ 20 s` of retries ahead of it — and going
ACTIVE has already cancelled the 30 s `INCOMING_TIMEOUT_MS`. During that window a healthy
inbound call presents every signal a naive reverse-orphan rule looks for. 45 s is past both
mechanisms that are supposed to act first, so the watchdog only fires once both have failed.

**Remedy, not `terminateAllCalls()`.** That method returns early from `IDLE` and does not stop
the audio streams for a leg that never left it (PHASE-1-PLAN §3d) — which is the shape most of
these rules fire on. `watchdogTerminate` goes through `handleGsmCallEnded`, which stops the
streams unconditionally and releases the mute lease, and then disconnects at Telecom directly
for the one case neither reaches (a live Telecom leg while the machine never left `IDLE`).

**A transient `InCallService` unbind is no longer an orphan.** `getInstance() == null` reads as
"no GSM leg" by design, so an unbind used to be indistinguishable from a real orphan. The
orphan rules are skipped for that tick; the max-duration fail-safe still runs, which is what
stops a *permanently* unbound service from parking a call forever. The instance is resolved
**once** per tick and held in a local — resolving it twice would put an unbind between the two
reads, which is exactly the false positive the guard exists to prevent. `isGsmLegLive()` was
removed for the same reason: it folded "unbound" into "no leg".

**Only two of the brief's four hard deadlines were missing.** The mute lease
(`DeviceMuteManager.MUTE_MAX_HOLD_MS`, 4 h, GW-02) and charging-disabled
(`BatteryLimitService.MAX_DISABLE_MS`, 12 h, GW-05) already existed with error logs and were
not rebuilt. Max call duration is new and needed a new anchor — there was no call-start
timestamp anywhere, `gsmCallPlacedTime` being SIP→GSM-only and cleared on end. **TERMINATING
dwell ships as a log and nothing more, deliberately:** `terminateAllCalls()` walks in and out
of that state synchronously with no suspension point, `transition()` is private, and there is
no API to force `TERMINATING → IDLE`. A tick can only observe that state if the control thread
is wedged inside `terminateAllCalls()` — in which case the tick is not running either.

**Silent bridge is detection only** (`BRIDGED && isAudioStreaming() && getFramesRequested()`
unchanged for 10 s), per the brief: confirm it never false-positives over a week of real calls
before deciding whether to act on it. GW-23a's counter accessors exist and are safely
published (`AtomicLong`, one RT writer), so GW-25 reads them and does **not** touch
`GsmAudioPort`. The `SipDiagnostics.dumpAndLog` is **latched to once per episode** — ~20
logcat lines and ~8 owned SWIG objects per dump, which at a 3 s tick would be ~1200 an hour.

**Findings are in the snapshot**, not only in logcat: `GatewayStatus.WatchdogFindings` carries
the termination count (the acceptance number for the 30-call run), the silent-bridge episode
count, and the last finding, and all of it flattens into `GET_STATUS`'s bundle. Nothing
time-derived is frozen — the raw `callUpSinceWallMs` is carried and `getCallDurationMs()`
re-reads the clock, per plan §2.7 trap 1.

**Outstanding, needs hardware:** the 30-call false-positive run (§Verification 4), and the
deliberately-broken-bridge check that makes the silent-bridge dump actually fire. Neither is
reachable from the JVM. Covered by `WatchdogInvariantsTest` (20 tests), whose three
false-positive cases were confirmed to fail against a naive implementation.

#### H2b. The Java-side `isOpen()` pre-check is now redundant overhead — ✅ FIXED (GW-23a)
Post-GW-01, `readFrame`/`writeFrame` check `is_open` under the lock and refuse safely.
`GsmAudioPort.onFrameRequested` (`:155`) and `onFrameReceived` (`:196`) still called
`GsmAudioNative.isOpen()` first — a third acquisition of the same native mutex per frame
per direction, 100/s during a call, for no benefit.

Removing it required one contract change, or the error counters would have got worse:
`io_acquire()` failure and a real `pcm_read()` failure both reported `-1`, so every
end-of-call teardown race would have been counted as a capture error. `readFrame` /
`writeFrame` are now three-valued — *n* on success, **0 when the device is closed** (the
ordinary race, not an error), `-1` only when ALSA itself failed — and
`captureErrors`/`playbackErrors` count only the last, so they stay comparable against the
pre-change baseline.

#### H2c. `stopCapture()` worst case is now ~1.75 s, on the main thread — RAISED to P1
After GW-01 and GW-08 landed, `GsmAudioPort.stopCapture()` can block its caller for the
sum of three bounded waits:

| Wait | Constant | Worst case |
|---|---|---|
| join the open worker | `OPEN_JOIN_MS` | 1000 ms |
| join the enforce thread (held under `stateLock`) | `ENFORCE_JOIN_MS` | 500 ms |
| native in-flight I/O drain | `IO_DRAIN_TIMEOUT_MS` | 250 ms |
| `teardownMixer` waiting on GW-04's `mixerLock` behind an in-flight `setupMixer` | unbounded — one `Runtime.exec("su -c tinymix …")` per configured Volume control | hundreds of ms × N controls |

**= 1.75 s plus the mixer lock wait**, and it runs on the **main thread** via the `Call.Callback` →
`onGsmCallStateChanged` → `stopAudioStreams()` path, and on a pjsua worker via
`onCallsTerminated`. Typical cost is a few ms — all three waits only reach their bound in
the pathological cases — but the tail is now long enough to matter on a call-teardown
path.

Neither GW-01 nor GW-08 introduced the problem (both bounds are correct and necessary);
they made an existing main-thread blocking call measurably worse. The fix is GW-10/GW-26:
`stopCapture` must not run on the main thread at all. Track on **GW-26**'s ANR ledger and
re-check the number once the control thread exists.

#### B1b. A mute held when the process is killed survives it
Same class as B4b, different resource. GW-02's fail-safe (`MUTE_MAX_HOLD_MS`) and the
`onDestroy` release both live *in the process*. `am force-stop`, SIGKILL or a crash while
a lease is held leaves the mixer muted with nothing left to restore it — the mic stays
dead until the next call cycle happens to complete in the right order.

Closing it needs out-of-process state: persist a restore record (control → original
value) when a lease is taken, clear it on release, and replay any record found at next
startup. That also covers B4b's charging case, so **the two should be solved together** —
one "restore what the previous process left patched" pass at service start.
Found by the GW-02 agent.

#### B1c. On Qualcomm the mic mute records **no** originals, so every gateway call bricks the microphone — P0 — ✅ FIXED `bf22992`
**Reproduced on device 2026-08-23, lavender (Redmi Note 7, SDM660), release build.** After
10 gateway call cycles a normal dialler call had no microphone. `DEC1-5 Volume` read **0**;
a reboot was required to recover (see the note on un-bricking below).

**Pre-existing, not a Phase 0 regression.** `2626f5d` contains the identical logic, comment
included:
```java
// Always try to set, even if we can't read current value
int original = readIntControl(control);
if (original >= 0) { originalIntValues.put(control, original); }
setIntControl(control, 0);          // mutes regardless of whether the read worked
```
GW-02 preserved that semantics faithfully. What GW-02 *did* change is that the failure is
now **visible**: `Lease 8 muted 0 controls` is the lease honestly reporting it has nothing
to restore, where the old code silently ended up with empty maps. The lease machinery is
working correctly — it is being handed nothing.

**Root cause: the read path shells out to a `tinymix` subcommand that does not exist.**
`DeviceMuteManager.tinymixGet` (`:241-243`):
```java
String cmd = "su -c 'tinymix -D " + card + " get \"" + name + "\"'";
```
Two independent reasons this fails on real hardware:
1. **No `get` subcommand.** Both test devices' `tinymix` takes `tinymix [options] [control]
   [value]`; `tinymix -D 0 get "X"` is parsed as *control named `get`* and errors with
   `Invalid mixer control: get`. The working form is `tinymix -D 0 -v "X"`.
2. **`tinymix` is not installed at all** on lavender — it had to be pushed from merlinx to
   `/data/local/tmp` just to run this audit.

So every read returns the failure sentinel — `-1` for INT, `""` for ENUM — nothing is
recorded, and `unmuteAll()` faithfully restores the empty set. Log evidence:
```
Muted mic volume: DEC1 Volume (was: -1)     <- read failed
Muted mic routing: DEC1 MUX  (was: )        <- read failed
Lease 8 muted 0 controls                    <- nothing to restore
```

**Why it is the microphone specifically.** Of the preset's controls, only `DEC* Volume`
matters: the mute writes `ZERO` to `EAR_S`, `SPK` and `DEC* MUX`, which is **also their
idle value**, so failing to restore those is harmless *and* undetectable. `DEC* Volume`
(84 live / 0 muted) is the only control that both causes the damage and can diagnose it.

**The fix is small and already half-built.** The comment at `DeviceMuteManager:188` —
"the native bridge has no ENUM getter, and the INT getter needs the ALSA permissions that
`tinymix` obtains for itself via `su`" — is **wrong on both counts**:
- `Java_org_onetwoone_gateway_GsmAudioNative_getMixerControl` **exists**
  (`gsm_audio_jni.c:582`, via `mixer_ctl_get_value`).
- The app's native *writes* already succeed (that is why the controls get muted at all), so
  it plainly has the ALSA permissions.

Therefore:
1. Point `getValue()` at the existing native getter. **This alone closes the brick**, since
   `DEC* Volume` is the only damaging control.
2. Add a native ENUM getter for `EAR_S` / `SPK` / `DEC* MUX` and drop the shell-out
   entirely.
3. Consider refusing to mute a control whose original could not be read — muting something
   you cannot restore is how this bug does its damage.

**Secondary: the mute takes ~13 s.** Twelve controls, each a `su -c` process spawn at ~1 s.
Measured 22:39:46 → 22:39:59. The doc's "~6 s" figure is optimistic for this preset. It
also explains the **~5 s gap between call start and media start** reported on this device.
Native reads/writes would make the whole sequence milliseconds. Related: G3, H1.

**Also observed: setup and teardown overlap.** `QualcommAudioProfile: Tearing down mixer...`
at 22:39:56 while the mute was still writing `DEC3/4/5 MUX` at 22:39:58-59. Bounded here
because both run on the `MuteControls` thread, but it means teardown ran against a
half-applied mute.

> **Un-bricking a device in this state:** reboot. `tinymix` borrowed from another device
> can *read* the controls but its writes are rejected (`Error: invalid value` for a value
> well inside the reported `dsrange 0->124`), so it cannot restore them. A reboot resets
> the mixer to kernel defaults — verified: `DEC1-5 Volume` back to `84`.

#### B1e. B1c's twin survives in `QualcommAudioProfile`, which fabricates every saved original — P1 — ✅ FIXED (GW-20), on-device check outstanding
**Verified on device 2026-08-24, lavender.** `bf22992` migrated `DeviceMuteManager` to the
native mixer API and closed B1c — but the *same* broken read pattern lives on untouched in
`QualcommAudioProfile.TinymixControls`, and it is the component that saves the originals
`setupMixer`/`teardownMixer` restore. Both of its readers are dead:

**`getValue` (INT)** shells out to `su -c 'tinymix -D 0 get "DEC1 Volume"'`. There is **no
`tinymix` on lavender at all** — running the app's exact command returns
`/system/bin/sh: tinymix: inaccessible or not found`, exit 127. `readLine()` therefore
returns null (the message goes to stderr, which this path never drains) and the method
returns its `fallback` argument. `setupMixer` passes `VOLUME_READ_FALLBACK = 84`
(`QualcommAudioProfile.java:38`, used at `:123`), so **every DEC control's "original" is
recorded as 84 without anything ever having been read.**

**`getEnum` (ENUM)** execs `filesDir/tinymix` directly. That file **does not exist**: the
`tinymix-arm64` asset is only unpacked by `ui/TinymixManager.ensureTinymixExtracted()`, a
UI-path component reached from the web interface's `/api/mixer-controls` and
`MainViewModel.detectMixerControls`. `QualcommAudioProfile` never extracts it and never
checks. On lavender `filesDir` holds only `profileInstalled`, so the exec throws
`IOException`, is swallowed, and the method returns `""` — which `restoreSaved` skips.

So the Qualcomm profile's saved state is entirely fictional, and note the direction of the
error: **84 is not a harmless sentinel.** Per
[[qualcomm-dec-volume-zero-at-rest]] the true resting value of `DEC* Volume` on this device
is **0**, so teardown attempts to write a value that is wrong, not merely unverified. B1d
observes that the kernel rejects that write while the decimator is inactive, which is
probably what has kept this from doing visible damage — the app is being saved by a driver
refusal, not by correctness.

**Why it is latent rather than firing today:** `setupMixer`'s mute loop iterates
`config.getAllMuteControls()`, which is empty by default *because of the GW-24 key mismatch*
(H4). The broken read path only executes once that list is non-empty. **This is a live
ordering hazard: GW-24's key unification is exactly what arms it.** GW-20's migration of
`TinymixControls` to `GsmAudioNative.getMixerControl` / `getMixerControlEnum` (both of which
exist and are already in production use in `DeviceMuteManager`) must land **before, or in
the same change as**, GW-24's fix.

Also note `getValue`'s `p.waitFor()` has no timeout and neither reader drains stderr — the
same defects GW-20 §4 lists.

**Common root cause with H13.** Both this and the SMS duplicate bug are a root shell-out to
a binary that is not present, whose failure the caller cannot distinguish from success. That
is `RootHelper.execRoot`'s return contract (H13 defect 3) in the shared case, and bare
`Runtime.exec` + `readLine() == null` here. → **GW-20** owns the mechanism; H13/**GW-27**
owns the SMS instance.

**Fixed by GW-20.** `TinymixControls` is gone — deleted rather than left for GW-31's sweep,
because it is not incidental dead code, it *is* the defect. Both reads now go through
`GsmAudioNative.getMixerControl` / `getMixerControlEnum`, which have been in production use
in `DeviceMuteManager` since `bf22992`. `MixerControls.NATIVE.getEnum` was a hardcoded
`return ""` carrying a javadoc that claimed no native ENUM getter existed; it now delegates,
mapping native `null` to `""` exactly as `DeviceMuteManager.NATIVE` does.

`VOLUME_READ_FALLBACK = 84` is replaced by `VALUE_UNREADABLE = -1`, and **a control whose
original cannot be read is now left alone rather than muted with an invented original** —
the policy `DeviceMuteManager` already applies (`muteInt`/`muteEnum` with `requireRead=true`
at every call site). `enforceMixer` is deliberately unchanged: by the `AudioProfile` contract
it reads only its static control lists, and that is sound here because the native getter
fails exactly when the mixer cannot be opened or the control does not exist, in which case
the corresponding native *write* fails too — so enforce's re-assert is a logged no-op, not
an unrestorable mute.

Regression tests: `QualcommMixerReadTest` (8) — an unreadable control is neither muted nor
fabricated, `84` is never written again, `0` round-trips as a real reading, the production
backend is asserted to be `MixerControls.NATIVE`, and `NATIVE.getEnum` is asserted to reach
JNI rather than return a constant.

**GW-24 has now landed, so the loop is armed.** The mute list is no longer empty on a
configured device: the web UI's selection is migrated to the key the profile reads, and
`setupMixer` re-reads it per call (H4, H4b). The first execution of this loop on real
hardware is therefore the next call placed on a device with the custom preset selected —
which makes the check below not merely outstanding but the gate on trusting the saved
originals at all.

> **⚠️ Still outstanding — the on-device value-by-value check.** GW-20's Risk section is
> explicit that a native getter returning a different representation than `tinymix` would
> silently corrupt the saved originals and break restore, and that this must be verified
> before the write path is trusted. It agrees *by construction* — `tinymix` prints INT
> controls from `mixer_ctl_get_value(ctl, i)` and ENUM controls from
> `mixer_ctl_get_enum_string()`, the same tinyalsa primitives the JNI getters call, both at
> value index 0 — but that is an argument, not a measurement.
>
> `TinymixManager.verifyNativeReads()` performs the comparison and runs automatically at the
> end of `detectControls()`. **Procedure:** tap "Detect mixer controls" (or GET
> `/api/mixer-controls`), then `logcat | grep 'B1e native-vs-tinymix'`. It must read
> **0 mismatched, 0 unreadable** on each SoC before the saved originals are trusted. It has
> not been run on hardware. It lives on the UI path, not the call path, because that is the
> component that already extracts a `tinymix` binary to compare against.

#### B1d. The mic-volume restore is **rejected by the kernel**, and the failure is discarded — P1
Found 2026-08-23 on lavender, after B1c and the mixer-handle cache were both in place.
The mute/restore bookkeeping is now correct — `Lease N muted 12 controls` followed by
`Restoring 12 controls`, three cycles in a row, with true originals (`was: 84`). The
controls still do not come back.

Raw log, one cycle:
```
23:30:07.306 setMixerControl: 'DEC1 Volume', value=0
23:30:07.306 Set mixer control 'DEC1 Volume' = 0                     <- mute OK (mid-call)
23:30:14.153 setMixerControl: 'DEC1 Volume', value=84
23:30:14.153 Failed to set mixer control 'DEC1 Volume' to 84: -1     <- restore REJECTED
23:30:14.153 DeviceMute: Restored: DEC1 Volume                       <- logged as success
```

Two separate defects:

1. **The write is refused.** `mixer_ctl_set_value(ctl, 0, 84)` returns `-1`. Writing `0`
   to the same control succeeds. Reproduced outside the app: `tinymix -D 0 23 84` gives
   `Error: invalid value` while `tinymix -D 0 23 1` succeeds, on a control that reports
   `dsrange 0->124`. The mute lands *during* the call and the restore lands *after* it, so
   the likely cause is that the Qualcomm decimator is gone by restore time and the driver
   rejects a non-zero volume on an inactive path. **Not yet proven** — the test is to
   restore while the call is still up and see whether the write is accepted.
2. **The failure is invisible.** `MixerBackend.setValue`/`setEnum` return `void`, so
   `restoreOne()` cannot tell a rejected write from a successful one and logs
   `Restored: <control>` either way. That log is actively misleading: it is what made this
   look like "the restore never ran" for two rounds of debugging.

Note the ENUM controls **do** restore correctly (`EAR_S` back to `Switch`, `DEC1 MUX` back
to `ADC1` on every cycle) — only the INT volumes are refused.

Fix, smallest first:
- Make the backend setters return `boolean`, check them in the restore path, and log a
  warning naming the control and the attempted value. Cheap, and turns a silent brick into
  a diagnosable one.
- Then establish *when* the write is accepted. If it only works while the decimator is
  live, the restore has to happen before the call fully tears down, or be re-applied when
  the path next activates — which is a real design question, not a patch.

Impact is limited on a dedicated gateway phone (the device owner has accepted it there),
but on any phone also used for normal calls this is still the B1 brick.

#### B4b. `BatteryWatchdog` only rescues a phone below 25% — the kill path has no real backstop
`BatteryWatchdog.java:27` (`CRITICAL_LEVEL`). If the process is killed
(`am force-stop`, SIGKILL, crash) `BatteryLimitService.onDestroy` never runs, so GW-05's
force-enable hatch does not fire and charging stays disabled. The only out-of-process
backstop is this WorkManager job — but it force-enables only below 25%. **A phone
stranded at 60% with a 60% limit is not recovered; it silently discharges through the
entire buffer down to 25% before anything acts.**

`BatteryWatchdog.forceEnableCharging()` (`:75-81`) also covers only 3 of the 7
`CHARGING_PATHS` — it is now inconsistent with the full sweep GW-05 gave the service.

Fix: force-enable whenever `isPluggedIn && !isCharging` and `BatteryLimitService` is not
running, and share the path list. Found by the GW-05 agent; genuinely out of its scope.
**Deserves its own issue** — it is the last gap in the "device never strands itself"
property, and GW-05 explicitly cannot close it from inside the service.

#### B4c. Nothing in the app ever stops `BatteryLimitService`, so GW-05's escape hatch is nearly unreachable — P1
Found on device, 2026-08-23, while running Phase 0 verification Step 1.

**The hatch itself works, and works well.** Reaching `onDestroy` produces a force-enable in
**~217 ms** against its 7 s budget, with both halves firing in the designed order — inline
on main (`+4 ms`), then re-applied on the control thread (`+110 ms`). GW-05 is correct.

The problem is that **nothing calls it.** `grep stopService` over the whole app finds three
call sites and none targets `BatteryLimitService`:
- `GatewayControlReceiver.stopGateway` (`:130-140`) stops `PjsipSipService` only, though
  its `startGateway` (`:116-127`) starts *both*. The `STOP` broadcast is asymmetric with
  the `START` broadcast.
- `MainViewModel.stopService` (`:220`) — the UI's Disconnect button — likewise only stops
  `PjsipSipService`.
- No UI control stops it. Lowering `battery_limit` to 100 only makes the *next* `START`
  skip it; a running instance keeps enforcing.

So the ways `BatteryLimitService` actually ends are: `am force-stop`, APK reinstall, OOM
kill, or a crash — and **`onDestroy` runs in none of them**. The escape hatch guards the
one path that essentially never happens, while every real termination path is B4b.

Both were reproduced on device the same evening:
- `adb install -r` killed the process at `input_suspend=1`; it stayed `1` until the service
  was started again.
- `am stopservice` from shell is refused outright —
  `Permission Denial: ... not exported from uid 10352` (the service is
  `android:exported="false"`), so even a knowledgeable user cannot reach the hatch without
  root. It succeeded only under `su`.

This changes B4b's priority: it is not a corner case behind a rare kill, it is the
**normal** shutdown path. Fix alongside B1b/B4b, and additionally make `stopGateway` and
`MainViewModel.stopService` symmetric with the start path.

#### H9b. `handleIncomingGsmCall` leaks a ringing call when `answer()` throws — ✅ FIXED (GW-25)
`GatewayInCallService.handleIncomingGsmCall`, MODE_ANSWER_FIRST branch: if
`call.answer()` throws, it cancels the incoming timeout and returns, leaving
`currentCall` set with no SIP leg **and no timeout**. The GSM call then rings until the
network gives up. It should disconnect the leg rather than just returning.
Found by the GW-03 agent; not in GW-03's scope. Assign to GW-25 (watchdog invariants) or
a follow-up.

**Fixed by GW-25.** The branch now disconnects the leg before returning. It acts on the `Call`
the callback was handed rather than on the `currentCall` field, so a racing `onCallRemoved`
cannot make it disconnect a different leg, and it is state-guarded the way `disconnectCall()`
is.

**This could not be left to the watchdog**, which is worth recording because the assignment
suggested otherwise: every watchdog rule keys off `currentGsmCallId`, and that is set only by
`handleGsmCallConnected`, i.e. only on Telecom `STATE_ACTIVE`. A leg whose `answer()` threw
never reaches ACTIVE, so it is invisible to the tick — pinned by
`WatchdogInvariantsTest.aLegThatNeverReachedActiveIsInvisibleToTheWatchdog`. Needs hardware to
observe the leg actually stop ringing; `answer()` throwing is not reproducible on the JVM.

#### H8b. `instance` is published before the object is usable — two places
Both found by the GW-07 agent; both are *ordering*, not visibility, so GW-07 correctly
left them alone.

- `PjsipSipService.onCreate` assigns `instance = this` **before** `mainHandler` and the
  managers are constructed. A NanoHTTPD worker that grabs the instance in that window and
  calls `reloadConfig()` NPEs on `mainHandler`. → **GW-26** (adjacent to H8).
- `SipEndpointManager.createEndpointInternal` assigns `endpoint` **before** `libInit()` /
  `libStart()`. Another thread can observe a created-but-not-started endpoint — and pjsua
  aborts rather than throwing when used in that state. → **GW-15**; needs a
  build-then-publish restructure, not a keyword.

#### F6b. `ReconnectionStrategy.pending` is a check-then-set race, not a visibility gap
Two callers can both observe `pending == false` and queue two reconnects. `volatile`
(added by GW-07) makes the reads defined but does not make the sequence atomic. → **GW-15**,
which moves the class onto the control thread and dissolves the race.

#### H1b. `RootHelper.startRootShell` check-then-act spawns duplicate `su` processes
Two callers can each observe `suProcess == null` and each spawn a shell, orphaning one.
→ **GW-31**. Reassigned: GW-20 considered and declined to fix it, because the whole
persistent-shell API has zero callers and is scheduled for deletion — fixing a race in code
that is about to be removed is churn. It is unreachable in the meantime, and both the
javadoc and H1c say so.

#### H7b. `AudioBridgeManager.wiredConfSlot` is write-only dead state
Assigned at three sites, never read — `unwireBridge` deliberately asks the media objects
for their port ids instead, because that is what `pjsua_conf_disconnect` will actually be
handed. Candidate for deletion in **GW-12**.

#### H7c. `SipEndpointManager.destroyEndpoint()` is unreachable
No caller anywhere in the tree. Either wire it up or delete it in **GW-15**.

#### H8c. Service destroy can collide with an in-flight SIP init — ~~P2, bounded~~ **P1** — ✅ FIXED (GW-26)
Found by the GW-10 agent while installing the control thread; a consequence of the fold, not
of a mistake in it.

> **AMENDED by GW-26. Step 4 below is wrong, and the severity with it.** The original text
> says the abandoned control thread "waits out the full 30 s … and schedules a reconnect for a
> service that no longer exists". Verified against the code, neither half holds:
>
> - **It does not burn 30 s.** The runnable the latch waits for is queued behind `onDestroy`
>   on main, so it runs *the instant `onDestroy` returns* — the latch resolves in
>   milliseconds, not seconds.
> - **The leaked reconnect timer is a non-event.** `scheduleReconnect()` posts to the control
>   looper, which `quitSafely` has already quit; the post fails harmlessly and Android logs
>   "sending message to a Handler on a dead thread". Nothing is armed.
> - **The real damage is a leaked SIP account,** and the original never mentions it.
>   `initializeSip` had **no cancellation check anywhere**, so once the latch resolved the
>   abandoned thread walked straight on through `audioBridge.initialize()` to
>   `accountManager.createAccount(this)` — `this` being the destroyed service — registering a
>   fresh SIP account against the `static` Endpoint that main had only run `hangupAllCalls()`
>   on. Its callbacks post to the quit looper and are dropped, so **nothing ever tears it
>   down**: the PBX sees a registered gateway that answers nothing, until the process dies.
>
> That is a live registration outliving its service, not a wasted thread — P1, not P2.

**The fix (GW-26): cancellation, not a longer join.** `quitSafely` cannot be the mechanism —
it still drains messages that are already due, so anything `onDestroy` posts *will* run on the
abandoned thread. "Post it and quit" is not cancellation.

- `core/LifecycleCancellation` — a unit of work takes a `Token` at entry and checks it before
  each blocking step; the `Token` indirection hands `SipEndpointManager` the right to *ask*
  without the right to cancel.
- **Terminal, not the "cancellation generation" PHASE-2-PLAN §2.7 asks for.** A generation
  would let a later unit of work be live again after a `cancel()`, and that is precisely the
  hole here: the doomed init is often still *queued*, so it calls `begin()` only when the
  control thread dequeues it — **after** destroy — and would snapshot the new generation, find
  itself live, and create the very account this exists to prevent. The object belongs to one
  service instance, that instance is destroyed once, and after `cancel()` nothing it hands out
  is ever live again.
- `PjsipSipService.onDestroy` calls `cancel()` **first**, before anything is torn down.
- `initializeSip` checks the token before each blocking step and returns silently on
  cancellation — no reconnect, no notification, no status publish.
- `SipEndpointManager.createEndpoint(Token)` threads it into `createEndpointOnMainThread`,
  whose 30 s latch is now polled at `CANCEL_POLL_MS` (50 ms) instead of awaited in one shot,
  so the parked thread gives up **at destroy** rather than when main happens to drain.

Cancellation is advisory, and the residue is closed by ordering rather than by the check: if
teardown lands between the last check and `createAccount`, the account is created and then
deleted by `teardownOnControlThread`, which is queued behind the init on the same thread.

Covered by `PjsipSipServiceLifecycleTest` (the real control thread genuinely parked in the
hop, destroyed, then asserted dead and cancelled rather than abandoned) and by
`SipEndpointManagerTest`'s `awaitCancellably` cases.

`PjsipSipService.onDestroy` calls `control.quitSafely(1500)` — the **only** place main waits
on the control thread anywhere in the app, and deliberately bounded (plan §2.4). The
pathological interleaving:

1. SIP init is running on the control thread and is parked inside
   `SipEndpointManager.createEndpointOnMainThread`'s 30 s latch, waiting for a runnable it
   posted to main.
2. The service is destroyed. `onDestroy` is itself a main-looper callback, so the runnable
   from step 1 is queued **behind** it and cannot run.
3. Main sits in the join for 1.5 s, gives up, logs, and finishes teardown.
4. The control thread waits out the full 30 s, throws "Timeout waiting for endpoint
   creation", and schedules a reconnect for a service that no longer exists.

Not a deadlock — both waits are bounded, which is exactly why the join must stay bounded and
why no second main-blocks-on-control wait may ever be added. But it wastes 30 s of a thread
and leaves a reconnect scheduled against a dead service. The real fix is for destroy to
**cancel** in-flight SIP init rather than wait for it, which is non-blocking shutdown →
**GW-26**.

#### D4b. A stale diagnostic DISCONNECTED clears the *next* test call's `mediaValid` — P2
`SipTestCallManager.onCallState(int)` takes a state and no call identity, so it cannot tell
whose disconnect it is being told about. If test call A's DISCONNECTED arrives while test
call B is running, B's `mediaValid` is dropped and B then skips its `stopTransmit` calls and
its final diagnostics.

This **fails closed** (a skipped `stopTransmit` is safe; the unsafe direction is calling it
on a destroyed port, which is an `abort()`), and it is strictly better than the pre-GW-10
behaviour, where the same stale callback was routed into `CallManager` and ran
`terminateAllCalls()` on a live gateway call. Fix by threading the call identity into
`onCallState`, evaluated **on the callback thread** — where it is not stale, since the
manager assigns `call` before `makeCall`. → **GW-11**, alongside the D4 start-gate.

#### H7d. Two getters became dead when the snapshot landed — for GW-31
`SipTestCallManager.owns()` no longer has a caller: callback dispatch moved to
`GatewayCall.getOwner()` and only that is correct under posting. `PjsipSipService.getStatus()`
likewise — `MainViewModel` reads `GatewayStatus.getStatusText()` now. Both kept in GW-10
because that diff does not delete code. Add to the GW-31 sweep alongside the getters already
listed in PHASE-1-PLAN §3b.

#### H7e. Five more timer getters have no production caller — for GW-31
Found while moving the two timers onto the control thread in GW-15.
`ServiceWatchdog.checkNow()`, `ReconnectionStrategy.resetDelay()`, `isEnabled()`,
`isPending()` and `getCurrentDelay()` are called only from their unit tests. Kept in GW-15
because that diff does not delete code, and `checkNow()` in particular is the natural entry
point for the `GET_STATUS` broadcast when that stub is implemented. Add to the GW-31 sweep
alongside H7d and the getters in PHASE-1-PLAN §3b.

#### H2d. The mute-lease release is now one control-thread hop from the Telecom event — P2
GW-10 moved `PjsipSipService.onGsmCallStateChanged`'s body onto the control thread, so the
`DeviceMuteManager.release(lease)` that cancels an in-flight mute is queued rather than run
inline on main.

The ordering itself is unchanged — `STATE_ACTIVE` and `STATE_DISCONNECTED` were already
serialised against each other on main, and are now serialised against each other on the
control thread. What is new is that the control thread also carries SIP init and config
reload, so a call that ends *during a reload* waits behind that reload's ~600 ms of
`Thread.sleep` (F5). `DeviceMuteManager`'s own lease design absorbs this — the cancel still
wins whenever it lands, and the fail-safe backstops a lost release — so this is latency, not
a brick. It disappears when **GW-14** removes the sleeps from the reload pipeline.

#### H10. Dead code that violates a documented hard rule
`GatewayInCallService.setMicrophoneMute` (`:410`) uses `AudioManager.setMicrophoneMute`,
which `CLAUDE.md` explicitly forbids ("it breaks the `Incall_Music` playback path").
Currently unreferenced — a trap for the next contributor.

#### H11. `onDestroy` can still overlap main with an abandoned control thread — P2 — ✅ FIXED (GW-26)
Found while landing GW-12. `PjsipSipService.onDestroy` queues the audio-bridge teardown,
then calls `control.quitSafely(1500 ms)`, then runs `shutdownSip()` on main. `quitSafely`'s
join is deliberately bounded and, when it expires, the control thread is *abandoned*
(`GatewayControlThread.quitSafely` logs exactly that) — so main can enter
`accountManager.deleteAccount()` while the control thread is still inside `stopBridge()`.
Deleting the account destroys the call's conference port, which is precisely what
`unwireBridge()`'s liveness check must not race.

The window is narrow: it needs the control thread to be busy for ~1.5 s and then to reach
the teardown at the instant main gives up. If it is busy *longer* (the 30 s
`createEndpoint` latch), the teardown never runs at all and there is no overlap — the
wiring is simply left marked active, which E2's process-scoped holder now makes harmless
(the next `startBridge`/`stopBridge` finds dead ports and clears them).

This is not new in kind — before GW-12, `shutdownSip()` ran `stopBridge()` on main against
the same abandoned thread, which was strictly worse. It is recorded because GW-12's whole
argument is that liveness-check and `stopTransmit` are adjacent *on one thread*, and this
is the one path where that can still be violated. The clean fix is to move `shutdownSip()`
itself onto the control thread — **AUDIT G2, owned by GW-26** — after which nothing on main
touches pjsua2 at destroy and the bound can go away.

**Fixed exactly that way.** `shutdownSip()` is now the last step of
`teardownOnControlThread()`, immediately after `stopAudioBridge()`, on the one thread that owns
both — so `deleteAccount()` and `unwireBridge()`'s liveness check are adjacent statements
rather than two threads, and the abandoned-thread window has nothing left in it to race. The
bound stays (it is still the only main-blocks-on-control wait) but what it bounds is now a
queue drain, not a collision hazard.

#### F4b. The diagnostic call still reads `getAccount()` on main — P2, residual F4
Found while landing GW-14. F4 is closed for the two production users of the account —
`sendSipMessage` and `makeSipCallWithCallerId` both run on the control thread now and
re-check `SipAccountManager.isCurrentAccount(...)` immediately before the pjsua2 call. The
third user was left alone: `SipTestCallManager.startInternal` (`:242`) reads
`accountManager.getAccount()` and hands it to `new GatewayCall(...)` **on main**, and the
class is main-thread-only by design (`assertMainThread("startInternal")`, `onMediaState`
posts to `mainHandler`). A config reload deleting the account between that read and the
dial is the same use-after-free F4 describes, just on the diagnostic path.

It was out of GW-14's scope (moving the diagnostic manager onto the control thread is a
threading change to a whole class, not a guard), and it is lower severity: the diagnostic
call is operator-initiated from the UI, so it does not overlap a web-interface config POST
in normal use. The fix is the same one F4 got — run it on the control thread — and it
belongs with whoever revisits `SipTestCallManager`.

#### H12. `SmsHandler.processedSmsIds` is a plain `HashSet` mutated from two threads — P2 — ✅ FIXED (GW-21)
`SmsHandler.java:56` is a plain `HashSet<Long>`. `processInbox()` iterates and `add()`s to
it, and it runs on **main** (the `ContentObserver` is constructed with the main handler,
`:97`) *and* on the **control thread** (`handleRegistrationState` calls `processInbox()` on
the post-registration retry). `unprocessSms`/`deleteSms` `remove()` from it. No
synchronization on any side, so a concurrent `add` during another thread's iteration is a
`ConcurrentModificationException` and a lost/duplicated SMS forward.

Pre-existing, and GW-14 *reduced* the exposure rather than creating it: `markAsRead` and
`unprocessSms` used to be called from main inside `sendSipMessage` and now run on the
control thread with the rest of the send path. `processInbox()` itself still runs on both.
The fix is to route the `ContentObserver` through the control thread (give it
`new Handler(control.getLooper())`) so the set has one owner — cheap, but it changes when
inbox scans happen and wants its own verification, so it is not folded into GW-14.

Related: `markAsRead` falls back to `markAsReadWithRoot`, which spawns `su`. That used to
happen on **main**; it now happens on the control thread, which is a strict improvement
(bounded by `RootHelper`'s timeout, and off the UI thread) but is worth knowing about when
reading control-thread latency.

**Partially mitigated by GW-27, not closed.** GW-27 had to grow this state (a persisted
confirmed-id map, an in-flight set, attempt counts, retry deadlines) and did not want to
leave a `HashSet` being iterated on one thread and `add`ed on another, so the collections are
now `ConcurrentHashMap`-backed and the persisted record's read-modify-write is behind a lock
that is **never held across the callback** — main must not end up blocked behind the control
thread's blocking SIP send. The `ConcurrentModificationException` is therefore gone.

**Closed by GW-21.** The `ContentObserver` is now constructed with
`new Handler(control.getLooper())`, so `processInbox()` has exactly one owner and the
check-then-act between "is this id suppressed?" and "mark it in flight" — several map
operations, never atomic under `ConcurrentHashMap` — is atomic by confinement. That is the
part GW-27 could not reach: it removed the `ConcurrentModificationException`, not the
interleaving, and two concurrent scans could still both forward one message.

The concurrent types went back with it, as GW-27's javadoc and this finding both said they
should: `confirmedIds`, `inFlightIds`, `forwardAttempts` and `retryNotBefore` are plain
`HashMap`/`HashSet` again, `rootFlagFailures` is an `int`, `rootFlagWriteGivenUp` and
`readFlagWriteEnabled` are plain `boolean`s, and `persistLock` is gone — the persisted
record's read-modify-write needs no lock on a single thread. Guarded by
`control.assertOnControlThread(...)` on `processInbox`, `markAsRead` and `unprocessSms`;
one mechanism, not two. One latent bug surfaced in the swap and was fixed with it:
`pruneProcessedIds` removed from `confirmedIds` inside a for-each over its own `entrySet()`,
which `ConcurrentHashMap` tolerates and `HashMap` answers with a
`ConcurrentModificationException` — it now uses `Iterator.remove()`.

Two fields are deliberately still `volatile`: `started` and `stopped`. `start()`/`stop()` are
called from the service lifecycle on main and only latch a flag before handing the work to the
control thread, and `stopped` is what lets a scan already in flight there stop handing
messages over while main tears the service down. Neither is suppression state.

#### F6c. `ReconnectionStrategy.onSuccess()` clears `pending` without cancelling the timer — P2 — ✅ FIXED (GW-26)
`onSuccess()` (`:108-113`) sets `pending = false` but does not
`handler.removeCallbacksAndMessages(null)` the way `cancel()` (`:119`) does. The already
scheduled runnable therefore still fires and calls `attemptReconnect()`, which — endpoint,
transport and account all being fine by then — sends a redundant re-REGISTER.

The reload path makes this easy to see: `deleteAccount()`'s un-REGISTER produces
`onRegState(false)`, whose handling is queued on the control thread and so runs *after* the
reload has already created the replacement account; it calls `scheduleReconnect()`, and the
subsequent `onRegState(true)` only clears the flag. Harmless today (one extra REGISTER),
but it makes `isPending()` and the backoff state disagree with what is actually armed. Fix
is one line in `onSuccess()`; left out of GW-14 because it is `ReconnectionStrategy`'s bug,
not the reload's.

Taken by GW-26 per PHASE-2-PLAN §6, and it stayed the promised one line:
`handler.removeCallbacksAndMessages(null)` alongside the `pending = false`, matching what
`cancel()` already did. Same-thread confinement means no new race.

---

#### H13. Every forwarded SMS is re-forwarded after a restart — NEW, P1, user-visible — ✅ FIXED (GW-20 + GW-27)
Reported from the field (13:04 on merlinx) and reproduced from the device log in the same
session. Three independent defects stack into one symptom: **the gateway re-sends the entire
inbox to the PBX every time the process restarts.**

1. **The app is not the default SMS app**, so `SmsHandler.markAsRead`'s
   `ContentResolver.update(content://sms/<id>, read=1)` is refused and returns 0. The log
   line is `Normal update failed for SMS id=N, trying root`.
2. **The root fallback runs a binary that does not exist.** `markAsReadWithRoot` shells out
   to `sqlite3 <mmssms.db> "UPDATE sms SET read=1 ..."`. There is no `sqlite3` on either test
   device — `su -c 'which sqlite3'` fails, and running the app's exact command returns
   `/system/bin/sh: sqlite3: inaccessible or not found`, **exit 127**.
3. **`RootHelper.execRoot` reports that failure as success.** It logs the non-zero exit code
   and then returns `output.toString().trim()` regardless — an empty string, never `null`.
   `markAsReadWithRoot`'s `if (result != null)` is therefore always true, so it logs
   `Marked SMS id=N as read (root sqlite3)` for a command that did nothing. The false success
   is why this went unnoticed: the logs claim the inbox is being drained.
   **✅ FIXED (GW-20).** `execRoot` now returns `null` for any failure, including a non-zero
   exit, so `markAsReadWithRoot`'s existing `if (result != null)` became correct without
   `SmsHandler` being touched — the log will now say the root fallback failed. That is
   defect 3 only: defects 1 and 2 and the persisted suppression set are still **GW-27**'s,
   and it should move off `execRoot` onto `RootHelper.run(...)`/`RootResult.success()` and
   onto `/system/bin/content`. See H1 for the new API.

The read flag is consequently never written. The **only** thing preventing re-delivery is
`SmsHandler.processedSmsIds`, an in-memory `HashSet` (see H12) that starts empty on every
process start, while `processInbox`'s `selection = "read = 0"` still matches every message
ever received. The device log shows the closed loop exactly:

```
13:04:25  [1] processInbox START, processedIds=[]        <- fresh process
13:04:25  [1] Found 9 unread SMS                          <- all 9 re-forwarded
13:04:27  Marked SMS id=8 as read (root sqlite3)          <- false success, x9
13:04:27  [2] Found 9 unread SMS                          <- count never drops
13:09:21  [3] Found 9 unread SMS
13:14:15  [4] Found 9 unread SMS
```

**A working mechanism exists on-device and was verified:** `/system/bin/content` is present,
and `su -c 'content update --uri content://sms/1 --bind read:i:1'` returns exit 0 and the row
flips to `read=1`. That is the natural replacement for the `sqlite3` path.

**But the read flag must not be the only defence.** It is provider state the app cannot be
sure of writing on an arbitrary device or Android version. `processedSmsIds` must be
persisted (and pruned by date) so that duplicate suppression survives a restart even when the
flag write fails. Fixing only the flag would leave the same class of bug one OEM away.

Also note the blast radius grows with `processInbox`'s trigger set: it runs on the
`ContentObserver`, at `start()`, and on every SIP re-registration
(`PjsipSipService`'s registration handler), so a flapping registration replays the burst
without any process restart at all.

→ New issue **GW-27**. Related: **H12** (same set, threading), **H1** / **GW-20**
(`execRoot`'s return contract is the root cause of defect 3, and it is not SMS-specific —
*every* caller that tests `execRoot(...) != null` for success is equally blind).

**✅ FIXED (GW-27)** — defect 3 was GW-20's; defects 1 and 2 and the missing persistence are
closed here. What landed:

- **A mechanism that exists.** `markAsReadWithRoot` runs
  `content update --uri content://sms/<id> --bind read:i:1` through `RootHelper.run(...)` and
  tests `RootResult.success()`. No `sqlite3` invocation remains in the tree.
- **Verified, not assumed.** Both write paths re-read the row afterwards. Exit 0 is treated
  as necessary but not sufficient — `content update` reports success for a URI it matched but
  a row it did not change — and "could not re-read" is kept distinct from "verified still
  unread". A write that did nothing logs an error naming the id.
- **The flag is no longer load-bearing.** The suppression set is persisted through
  `GatewayConfig.getProcessedSmsRecord()` / `setProcessedSmsRecord()` (`commit()`, not
  `apply()` — the failure being guarded against is the process going away) and reloaded in
  `SmsHandler`'s constructor. `SmsDuplicateSuppressionTest` fault-injects the flag write off
  and requires **zero** duplicates across a restart anyway; that is the test that proves the
  record, not the flag, carries correctness.
- **Bounded.** TTL 30 days plus a 1000-id cap, pruned oldest-first and written back.
  **The TTL is keyed on when the forward was confirmed, not on the SMS's own `date` as the
  brief asked.** Keying it on the SMS date silently re-opens the bug for exactly the messages
  that provoked it: the merlinx fixture is a pile of long-unread SMS, and every one of them
  would have been pruned the instant it was recorded.
- **Persisted after the forward, never before**, so a crash mid-send retries rather than
  drops. `inFlightIds` (not persisted) covers the window between the callback and the send.
- **Bounded retry.** `unprocessSms` counts attempts and backs off exponentially, giving up
  after 5 with an error and marking the message read. The **first** retry is deliberately not
  delayed: the commonest caller is "not registered yet" and the event that brings the message
  back is the successful REGISTER seconds later, which a backoff covering attempt 1 would
  swallow. It cannot spin — nothing on the failure path mutates the provider, so it does not
  re-trigger the observer; re-delivery is event-driven, not a loop, and the original brief
  overstated this.
- **The flag write is given up on** after 3 consecutive failures for the life of the process
  (issue §5), so a device that can never write it does not spawn a doomed `su` per message.

Still owed: on-device verification against the merlinx fixture (PHASE-2-PLAN §5). Everything
above is covered by JVM tests only.

#### H2e. A short SIP frame replays the tail of the previous one to the modem — NEW, P2 — ✅ FIXED (GW-23a)
Found during GW-23a recon, in neither brief. `GsmAudioPort.onFrameReceived` admitted any
frame with `0 < size <= frameSize` and copied exactly `size` bytes into the **reused**
`playbackBuffer` — then passed the *whole array* to `GsmAudioNative.writeFrame`, which
sized the write with `GetArrayLength` and so always wrote **320 bytes**. For a frame
shorter than 320 bytes the last `320 - size` bytes handed to the modem were stale audio
left over from the previous frame.

Latent today, not live: `pjmedia_conf`'s `write_port` always calls `put_frame` with
`cport->samples_per_frame * BYTES_PER_SAMPLE`, so `size` is invariably the full 320. It
becomes reachable the moment anything upstream delivers a partial frame — a different
conference frame length, a codec with a shorter last packet, or a future ring buffer
draining a partial period (**GW-23b**).

Fixed by making the length explicit: `writeFrame(byte[] buffer, int length)` on both sides
of the JNI boundary, with the length taken from the frame and range-checked natively. The
selection rule is now the pure, tested `GsmAudioPort.usableFrameBytes(reportedSize,
capacity)`. Dropping (not clamping) an oversized frame is preserved deliberately.

#### H14. The reload's give-up branch stops the gateway for good — NEW, P2
Found while landing GW-26. `PjsipSipService.doReloadConfig` step 0: if the endpoint is not
initialised it posts `stop()` to main, with the comment *"Service will be restarted by system
due to START_STICKY"*. **It will not be.** `START_STICKY` only restarts a service the *system*
killed; one that ends via `stopSelf()` stays stopped. So a reload that arrives while the
endpoint is down — which is exactly when the endpoint most needs recreating — takes the
gateway down and leaves it down until something starts it again.

Not introduced by GW-26 and not fixed by it. GW-26 only made the branch call `stop(false)` so
it does not additionally latch the persisted user-stop flag (§5), and replaced the false
comment with a pointer here. The real fix is to re-initialise rather than stop — the endpoint
is `static` and survives, so `initializeSip()` on the control thread is the natural remedy —
or, if a process restart really is wanted, to call `restartProcess()`, which is honest about
what it does. → New issue **GW-28**.

#### H15. An `Error` from endpoint creation escaped onto the main looper — NEW, P2 — ✅ FIXED (GW-26)
Found while adding the cancellation plumbing, and reproduced immediately in the new
`PjsipSipServiceLifecycleTest` (a JVM has no `libpjsua2`, which is the same state as a device
whose native library failed to load — see **H8**).

`SipEndpointManager.createEndpointOnMainThread`'s posted runnable caught only `Exception`
while its `finally` counted the latch down. `createEndpointInternal` calls `new Endpoint()`,
which throws `UnsatisfiedLinkError` / `NoClassDefFoundError` when the library is absent — an
`Error`. Two consequences at once:

1. the `Error` propagated out of a main-looper runnable, killing the process;
2. `errorRef` stayed null and the latch still fired, so the control thread **continued as if
   the endpoint had been created**. It only failed later, and for the wrong stated reason
   ("Failed to register the control thread with pjlib").

Fixed by catching `Throwable` there and wrapping a non-`Exception` into an `Exception` before
rethrowing — wrapped rather than rethrown so `initializeSip`'s handler treats it as a failed
init and schedules a reconnect, instead of an `Error` killing the control thread.

#### H16. The status bundle has no consumer: GET_STATUS is a stub — NEW, P2
Found by the Phase 4 UI session at `b3b2c0e`, and confirmed here. Everything Phase 2 added
to the observable surface is computed and then discarded at the last hop.

`GatewayControlReceiver.ACTION_GET_STATUS` is documented in the class header and in
`CLAUDE.md` as part of the remote-control API, but its handler is
`Log.i(TAG, "GET_STATUS not yet implemented")`. Meanwhile `GatewayStatus.toBundle()` now
carries `call_state`, `gsm_call_placed_at`, `config_generation`, `calls_created`,
`calls_deleted`, `calls_alive`, `watchdog_terminations` and `silent_bridge_episodes` —
**none of which reach any consumer.**

Two separate consequences:

1. **The documented broadcast API silently does nothing.** `START`, `STOP` and `CONFIGURE`
   work; `GET_STATUS` answers nothing, so anything driving the gateway remotely cannot read
   its state.
2. **It made a validation step unrunnable.** `PHASE-2-VALIDATION.md` originally told the
   operator to score GW-25's 30-call false-positive run — the acceptance test for the whole
   watchdog change — from `GET_STATUS`'s `watchdog_terminations`. That command returns
   nothing. Corrected in place to grep logcat for the `INVARIANT` prefix, which every
   termination logs at ERROR.

The UI half is the same defect one layer up and is **GW-45**'s (Phase 4): `MainViewModel`
reads the snapshot and keeps three fields, flattening everything into
`getStatusText()`'s three-line string, so call state, duration, grace period, the call
counters and the whole `WatchdogFindings` block never reach the screen either.

Wiring the broadcast is small — `toBundle()` already exists and `ServiceWatchdog.checkNow()`
is the natural trigger — but the receiver is `exported="true"` with no permission (**S1**),
so implementing it *widens an unauthenticated surface*. It therefore belongs with
**GW-30**, not before it. → **GW-30**.
#### H17. The SMS send outcome still arrives on main, so SMS cannot be published — NEW, P2 — filed as GW-46
Found while doing GW-45 (the status surface), checking whether SMS counters could be added to
`GatewayStatus` without a new cross-thread read. They cannot, for two reasons that compound.

**GW-21 finished half the job.** It moved the inbound pipeline onto the control thread by
giving the `ContentObserver` `new Handler(control.getLooper())` (`SmsHandler.java:355`). The
two *send-status* receivers were left as they were — `registerReceiver(receiver, filter)` with
no handler, `SmsHandler.java:1238-1244` — so every `sent` / `failed` / `delivered` /
`delivery_failed` verdict is dispatched on **main**. The submission side is clean
(`PjsipSipService.handleIncomingSipMessage:1404` is `@ControlThread` and calls `sendSms` from
there); only the outcome is split off. Same family as **F4b**: a single residual main-thread
touch in a path the refactor otherwise owns.

**And there is nothing to count yet.** No lifetime tally of forwarded or sent messages exists.
The `@ControlThread` state is all gauges and bookkeeping — `inFlightIds:230`,
`forwardAttempts:234`, `retryNotBefore:238`, the flag-write health at `:242`/`:246`/`:255`,
and `processInboxCounter:456`, which counts inbox *scans* rather than messages. The one field
that reads like a count, `confirmedIds:221`, is a **pruned** suppression record — TTL 30 days
(`:147`), hard cap 1000 (`:150`), `pruneProcessedIds():987` — so `size()` is a retention
window that silently diverges from the real total on exactly the long-running deployment this
gateway is built for. Publishing it as "SMS forwarded" would be a number that lies.

Consequence, and why it is a finding rather than a preference: **SMS is unobservable on the
device.** `GatewayStatus` carries no SMS fields, and since GW-45 the snapshot is the UI's only
status surface, so neither the app nor the web page can show a count, a last-forwarded time or
a failure — including the `rootFlagWriteGivenUp` state, in which duplicate suppression is
resting entirely on the persisted set. **H13** was reported from the field by a person who
noticed duplicate messages, because the appliance itself had nothing to show.

Not fixed in GW-45, deliberately: its brief allowed SMS counters only if they could be read
from state `SmsHandler` already keeps, on the thread that already owns it, with no new
locking. Both halves of that fail. The fix is to move the receivers onto the control looper
first and *then* add plain confined counters — not to buy an `AtomicLong`. Full plan in
[issues/GW-46-sms-status.md](issues/GW-46-sms-status.md).

**Severity: latent, not a live race** — corrected after review with the Phase 2 session, and
verified here. The sole production implementation of `onSmsSendStatus` is
`PjsipSipService.initSmsHandler:1290`, a single `Log.d` touching no shared state, so nothing
is read or written off-thread *today*. It becomes a real threading problem the moment anything
publishes the outcome — which is precisely what a status surface does. The two-line Handler
fix is therefore a **prerequisite of GW-46's publishing work, to land with the first real
consumer**, not a separate precondition blocking anything else.

**A second defect, found while confirming the first: the callback contract is
thread-ambiguous.** `onSmsSendStatus` is invoked from *two different threads* depending on
which way the send fails:

- `SmsHandler:1127` — the synchronous `catch` in the send path, on the **control thread**.
- `SmsHandler:1210`, `:1224`, `:1229` — the sent/delivered receivers, on **main**.

So an implementer has no single thread to reason about, and which one they get depends on
whether the failure was synchronous. That is worse than a callback that is simply always on
main, because testing the failure route you can most easily trigger — the synchronous
`catch` — tells you the callback is control-thread-confined, and it is not.

**`assertOnControlThread` is the right detector for this**, and an earlier revision of this
finding claimed the opposite. `GatewayControlThread:192` returns silently when `isCurrent()`
and only then; off the control thread it throws in debug and `Log.e`s in release. So it
passes at `:1127` (already on the control thread) and fires at the three receiver sites — a
true positive on the broken path, not a false green on the working one.

**Order the fix Handler → assertion → counters**, but for the opposite reason to the one
first recorded here: installing the assertion first would not hide anything, it would
*crash debug builds on every SMS send verdict*, turning a latent constraint into an immediate
hard failure on merlinx. Passing a control-thread `Handler` to both `registerReceiver` calls
collapses all four sites onto one thread; the assertion then documents and enforces that.

Until the `Handler` lands, no implementation of this interface may touch shared state — the
current `Log.d` is conformant by accident, not by design. The constraint is unenforced by
choice, not for want of a mechanism.
#### H20. SoC auto-detection uses a locale-sensitive `toLowerCase()` — NEW, P2

`AudioProfileFactory.select:34` lowercases `/proc/asound/cards` with the default locale
before sniffing for the SoC:

```java
String cards = readSoundCards().toLowerCase();
boolean isMediaTek = cards.contains("mt6768") || cards.contains("mt6358")
        || cards.contains("mediatek") || cards.contains("mtk");
```

On a device whose default locale is Turkish or Azerbaijani, `String.toLowerCase()` maps
`I` to the dotless `\u0131`, so a card list containing `MEDIATEK` lowercases to `med\u0131atek`
and `contains("mediatek")` is false. The gateway then loads `QualcommAudioProfile` on a
MediaTek phone and drives the wrong ALSA mixer controls entirely — the audio bridge is the
whole product, and this is a silent misdetection of which one to build.

`mt6768`, `mt6358` and `mtk` happen to survive the mapping (no `I`), so whether the bug
bites depends on which of the four markers the specific kernel prints. That is worse than a
deterministic failure, not better.

Fix: `toLowerCase(Locale.ROOT)`. This is ASCII protocol text being matched against ASCII
literals, which is exactly the case `Locale.ROOT` exists for. The identical argument applies
to the `equalsIgnoreCase` calls just above it, though those are safe today because both
sides are ASCII literals.

Found by lint (`DefaultLocale`) while landing GW-40, which does not touch this file. Not
folded into that change.

Worth noting where it bites hardest: **merlinx prints `mt6768`, so the bench cannot reproduce
it.** The one marker that fails is `mediatek`, on a kernel that prints that string, on a
Turkish or Azerbaijani device. So this will never show up in validation here — it is a
reasoning defect, closed by reading the code, not by testing. → **GW-31**.

### P2 — security posture

#### S1. Exported control receiver with no permission
`AndroidManifest.xml:115-124` — `GatewayControlReceiver` is `exported="true"` with no
`android:permission`. Any app on the device can rewrite the SIP server/user/password,
start/stop the gateway, or place calls (`GatewayControlReceiver.configure:165`).

#### S2. Web config server has no authentication and echoes the SIP password
`WebConfigServer.java:128` returns `sip_password` in cleartext over plain HTTP on
`0.0.0.0:8080`, and `postConfig` (`:219`) accepts unauthenticated writes.

---

## 3. Summary counts

| Severity | Count | Theme |
|---|---|---|
| P0 | 9 | native UAF (2), device-brick (4), Telecom NPE/lost-call (3) |
| P1 | 14 | call state machine (4), audio bridge (4), SIP lifecycle (6) → all downstream of the missing threading model; plus 3 ANR |
| P2 | 17 | resource hygiene, correctness, observability, security |

The P1 block is not 14 independent bugs — it is one missing decision (which thread owns
call/audio/SIP state) expressed 14 times. The roadmap treats it that way.

See [ROADMAP.md](ROADMAP.md) for the phased plan and [issues/](issues/) for the
agent-ready work items.

#### H18. The silent-bridge detector false-positives on every slow-answering inbound call — NEW, P2
Found on hardware during the wave-3 validation run (2026-08-24, merlinx, 6 real calls).

`PjsipSipService.checkSilentBridge` fires when `CallManager` is `BRIDGED`, audio is
streaming, and `GsmAudioPort.getFramesRequested()` has not moved for 12 s. Its predicate has
no condition that the **SIP leg has actually answered**.

For an inbound GSM call the state machine is
`IDLE → GSM_INCOMING → SIP_DIALING → BRIDGED`, and the observed transition to `BRIDGED`
("both legs up") happens when the INVITE goes out and the audio streams start — **not** when
the SIP leg confirms. So while the PBX extension is merely *ringing*, the call is `BRIDGED`,
audio is streaming, and pjmedia has legitimately never asked for a frame. Past 12 s the
detector fires.

Measured, from the run:

| Call | `BRIDGED` at | SIP `CONFIRMED` at | Ring gap | Detector |
|---|---|---|---|---|
| 1 | 21:39:57.565 | 21:40:07.134 | 9.6 s | silent (under 12 s) |
| 2 | 21:41:18.191 | 21:41:47.883 | **29.7 s** | **fired at 21:41:32.041**, `framesRequested=0` |

Nobody had seen this before because both handsets were `MODE_SIP_FIRST` until this run; it
needs `MODE_ANSWER_FIRST`, where the GSM leg is answered first and audio starts long before
the SIP side picks up.

**Why it matters even though it is detection-only.** It does not terminate anything today, so
no call is harmed. The damage is to the evidence: GW-25 §2 is explicit that
`silent_bridge_episodes` is where the case for promoting this rule to auto-terminating
accumulates ("must not auto-terminate until it has been shown not to false-positive over a
week of real calls"). That counter is now polluted by ordinary ringing, and a decision taken
on it would ship a rule that **kills healthy inbound calls whose SIP leg rings more than
12 s**. It also emits a ~20-line conference dump per episode.

**Fix direction.** Gate the rule on the SIP media actually being established — start the
stall clock at SIP `CONFIRMED` rather than at bridge start — so the ringing window cannot
count toward the 12 s. Until then, treat any `silent_bridge_episodes` figure gathered in
`ANSWER_FIRST` as unreliable, and do not use it as promotion evidence.

Related: H16 (the counter has no consumer either), GW-25.

#### H19. D6 fires inside the DISCONNECTING window and misreports it as a missed callback — NEW, P2
Found on hardware during the wave-3 lavender run (2026-08-24). It did **not** reproduce on
merlinx, and the reason it did not is the whole finding.

D6's repair fired 103 ms **before** the DISCONNECTED it claimed had been missed:

```
22:09:48.067  GatewayInCall: Call state changed: DISCONNECTING (gsmCallId=2)
22:09:48.450  E GatewaySvc: INVARIANT (AUDIT D6): GSM leg 2 is tracked but Telecom no
              longer has it - a DISCONNECTED was missed ... repairing
22:09:48.553  GatewayInCall: Call state changed: DISCONNECTED (gsmCallId=2)
22:09:48.554  GatewaySvc: GSM call 2 already ended (disconnected) - ignoring
```

Nothing was missed. The leg was mid-teardown, in `DISCONNECTING`, where Telecom no longer
lists it but the final callback has not yet arrived. D6 has no tolerance for that window, so
it reads "not in Telecom + still tracked" as a dropped callback.

**Why lavender and not merlinx** — the window is an order of magnitude wider on the older
device:

| Device | `DISCONNECTING` → `DISCONNECTED` |
|---|---|
| merlinx (MT6768) | ~46 ms |
| lavender (SDM660) | **486 ms** |

At a 3 s tick, a 46 ms window is hit roughly 1.5% of the time and a 486 ms window ~16%. This
is a race whose probability is set by device speed, so it will look like "an old-phone bug"
and be dismissed. It is not — merlinx is simply winning the race most of the time.

**Impact is low but not nil.**

1. No healthy call was harmed: the call was already terminating. The repair stopped a port
   that the normal path had already stopped 288 ms earlier, and `stopCapture()` proved
   idempotent (no `Starting native audio` in between, second stop clean). The real
   DISCONNECTED was then correctly absorbed as `already ended - ignoring`.
2. It logs at **ERROR**, asserting a Telecom defect that did not occur. Anyone debugging a
   real dropped-callback problem will be chasing this first.
3. It increments `watchdog_terminations` — the same evidence counter H18 pollutes. Two
   independent false sources now feed the number that is supposed to decide whether the
   watchdog rules are trustworthy enough to act automatically.
4. The repair path runs concurrently with the normal teardown, on the same objects. It was
   safe here only because the stop is idempotent; that is a property worth keeping
   deliberately rather than by luck.

**Fix direction.** Require the leg to have been absent from Telecom for more than one tick
before concluding a callback was missed, or exempt legs whose last known state was
`DISCONNECTING`. Either removes the race without weakening the genuine D6 case, which is a
leg that stays untracked indefinitely.

Related: H18 (the other false contributor to `watchdog_terminations`), GW-25.
