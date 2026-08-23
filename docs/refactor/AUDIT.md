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

#### D1. `CallManager` state is unguarded and driven from three threads
`CallManager.java:47-50` and `:62` (`state`, `currentSipCall`, `pendingGsmDestination`,
`pendingGsmSimSlot`, `gsmCallPlacedTime`) — all plain fields. Only `hangupSipCall()`
(`:439`) is `synchronized`; `terminateAllCalls()` (`:468`) is not.

Writers: pjsua worker (`onSipCallState:241`), main (`onIncomingSipCall:135`,
`onGsmCallConnected:383`, `onGsmCallEnded:395`, watchdog `terminateAllCalls`),
ConfigReload (indirectly).

**Failure:** two concurrent `terminateAllCalls()` → two `onCallsTerminated()` →
two concurrent `audioBridge.stopBridge()` (see E1); state flips
`TERMINATING → IDLE → TERMINATING` and the watchdog reads a torn value.

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

#### D3. Two independent sources drive the same GSM transitions
`handlePhoneState` (`PjsipSipService.java:448`, from `PhoneStateListener`) and
`onGsmCallStateChanged` (`:473`, from `Call.Callback`) both call
`startAudioStreams()` + `onGsmCallConnected()` on connect and the stop pair on end,
with no defined ordering. Neither is idempotent: the connect path also spawns a
`MuteControls` thread each time, and the end path can run **after** a subsequent call's
start path.

#### D4. `SipTestCallManager` ownership check is racy
`SipTestCallManager.java:90` (`call`, non-volatile) is read by `owns()` (`:141`) from
pjsua workers and written by `startInternal`/`stopInternal` on main. A stale read routes
a real gateway call into the test-call handler (skipping the GSM state machine entirely)
or the reverse. `PjsipSipService.startTestCall` (`:704`) checks
`callManager.getCurrentSipCall()` on the caller's thread and then posts — an incoming
gateway call can land in the gap, leaving both calls fighting over the single static
`gsmAudioPort`.

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

#### F1. Endpoint creation is check-then-act on a static
`SipEndpointManager.java:31` (`endpoint`, `endpointUseTls`, static, non-volatile),
`createEndpoint:132`. `SipInit` and a reconnect (or ConfigReload) can both observe
`endpoint == null` and both call `new Endpoint().libCreate()` → the second `libCreate`
on an already-created pjsua library aborts natively.

#### F2. `hasTransport()` permanently registers whatever thread calls it
`SipEndpointManager.java:85` calls `registerThread(Thread.currentThread().getName())`
from inside a *query*. Callers include NanoHTTPD workers, `ConfigReload`, the reconnect
runnable — all short-lived. pjlib allocates a thread descriptor from the pjsua pool and
**never frees it**; when the thread dies the descriptor dangles. Pool grows monotonically.

#### F3. `initializeSip()` runs on the main thread on the reconnect path
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

#### F6. `ReconnectionStrategy` flags are non-volatile and set from three threads
`ReconnectionStrategy.java:24-25`. `scheduleReconnect()` is called from `initializeSip`
on `SipInit` (`PjsipSipService.java:253`) and from `attemptReconnect` on main (`:293`);
`setEnabled` from main and from the broadcast receiver. `pending` races → duplicate or
dropped reconnects.

---

### P1 — main-thread blocking (ANR)

#### G1. SMS forwarding runs entirely on the main thread
`SmsHandler.processInbox()` (`:149`) is invoked from a `ContentObserver` bound to the main
handler (`:97`) and does a `ContentResolver.query` inline, then calls back into
`PjsipSipService.handleIncomingGsmSms → sendSipMessage` (`:543`) which performs
`buddy.sendInstantMessage` — network I/O — still on main.

#### G2. `shutdownSip()` blocks main
`PjsipSipService.onDestroy:196` → `shutdownSip:257` → `hangupAllCalls()` +
`deleteAccount()` (un-REGISTER, network) on the main thread.

#### G3. `unmuteAll()` on main — see B1.

---

### P2 — resource, correctness and hygiene

#### H1. `RootHelper` static state is unsynchronized; output capture is not thread-safe
`RootHelper.java:21-23` (`hasRoot`, `suProcess`, `suOutputStream`, all static, plain).
`execRoot` (`:61`) builds output in a `StringBuilder` written by a reader thread and read
by the caller after `join(1000)`; if the join **times out**, `:111` reads a
`StringBuilder` that is still being appended → `StringIndexOutOfBoundsException` or torn
output. `execInShell`/`startRootShell` (`:137-171`) can spawn two `su` processes or NPE.
Each `execRoot` spawns 3 threads; `setupAlsaPermissions` runs on every capture open.

#### H2. Per-frame JNI churn on the RT thread
`GsmAudioPort.onFrameRequested:160-168` and `onFrameReceived:205-207` copy 160 samples
one `ByteVector.add()` / `get()` at a time — ~8000 SWIG/JNI round-trips per second per
direction. Any GC pause here is an audible dropout.

#### H3. Per-frame `malloc`/`free` in the resampler
`cpp/gsm_audio_jni.c:304-319` allocates the upsample buffer on every frame (50 Hz) on the
RT thread.

#### H4. Web UI writes a preference key nothing reads
`WebConfigServer.java:156` reads and `:267` writes `mic_mute_controls` as a **StringSet**;
`GatewayConfig.KEY_MIC_MUTE_CONTROLS` (`:70`) is `"mic_mute_decs"` read as a **String**.
The in-app UI (`MainViewModel.java:404`, `:517`) uses `GatewayConfig` and is correct — only
the web interface is disconnected, so its mute-control selection is silently discarded.
`postConfig` also calls `audioEditor.apply()` three times (`:251`, `:268`, `:274`) →
partially-applied config.

#### H4b. Mute-control config is captured once and never refreshed
`QualcommAudioProfile` copies `config.getAllMuteControls()` into a final list **in its
constructor** (`:48`). The profile is built by `GsmAudioPort`'s constructor (`:75`), which
runs once from `AudioBridgeManager.initialize()` (`:68`) — and `gsmAudioPort` is `static`
(`:28`), so it survives service restarts. Changing the mute controls therefore has no
effect until the **process** restarts, even though the UI reports the change as saved and
`reloadConfig` claims to have applied it. Same shape for `captureDevice`, `playbackDevice`
and `multimediaRoute` (`:45-47`).

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

#### H7. pjsua2 objects are never deleted
No `Call.delete()` (deliberate, `CallManager.java:258-260`), no `delete()` on the
`CallInfo` / `CallMediaInfoVector` / `AudioMedia` values pulled per callback.
Each call leaks a SWIG director plus several C++ shadow objects. Over days of unattended
operation this is unbounded.

#### H8. `onDestroy` has no null-guards for a partially constructed service
`PjsipSipService.onDestroy:177` calls `watchdog.stop()`, `reconnection.setEnabled(false)`,
`audioBridge.stopBridge()`, `powerController.release()` unconditionally. If `onCreate`
threw after `instance = this` (e.g. `GatewayConfig.init` failure at `:107`), every one of
those is an NPE inside `onDestroy`.

#### H9. Watchdog only detects one orphan direction
`checkOrphanedCalls` (`PjsipSipService.java:630`) terminates a SIP call with no GSM leg.
The reverse — a live GSM call with no SIP leg — is never detected, so a failed bridge can
burn GSM minutes indefinitely.

#### H2b. The Java-side `isOpen()` pre-check is now redundant overhead
Post-GW-01, `readFrame`/`writeFrame` check `is_open` under the lock and return -1 safely.
`GsmAudioPort.onFrameRequested` (`:155`) and `onFrameReceived` (`:196`) still call
`GsmAudioNative.isOpen()` first, which is now a third lock acquisition per frame per
direction for no benefit. Dropping it is a free win — fold into **GW-23**.

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

#### B1c. On Qualcomm the mic mute records **no** originals, so every gateway call bricks the microphone — P0
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

#### H9b. `handleIncomingGsmCall` leaks a ringing call when `answer()` throws
`GatewayInCallService.handleIncomingGsmCall`, MODE_ANSWER_FIRST branch: if
`call.answer()` throws, it cancels the incoming timeout and returns, leaving
`currentCall` set with no SIP leg **and no timeout**. The GSM call then rings until the
network gives up. It should disconnect the leg rather than just returning.
Found by the GW-03 agent; not in GW-03's scope. Assign to GW-25 (watchdog invariants) or
a follow-up.

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
→ **GW-20** (which deletes this API outright — see GW-31).

#### H7b. `AudioBridgeManager.wiredConfSlot` is write-only dead state
Assigned at three sites, never read — `unwireBridge` deliberately asks the media objects
for their port ids instead, because that is what `pjsua_conf_disconnect` will actually be
handed. Candidate for deletion in **GW-12**.

#### H7c. `SipEndpointManager.destroyEndpoint()` is unreachable
No caller anywhere in the tree. Either wire it up or delete it in **GW-15**.

#### H10. Dead code that violates a documented hard rule
`GatewayInCallService.setMicrophoneMute` (`:410`) uses `AudioManager.setMicrophoneMute`,
which `CLAUDE.md` explicitly forbids ("it breaks the `Incall_Music` playback path").
Currently unreferenced — a trap for the next contributor.

---

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
| P2 | 12 | resource hygiene, correctness, security |

The P1 block is not 14 independent bugs — it is one missing decision (which thread owns
call/audio/SIP state) expressed 14 times. The roadmap treats it that way.

See [ROADMAP.md](ROADMAP.md) for the phased plan and [issues/](issues/) for the
agent-ready work items.
