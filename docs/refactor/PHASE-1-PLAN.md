# Phase 1 execution plan — installing the threading model

Companion to [ROADMAP.md](ROADMAP.md) §2 and the `GW-1x` issue files. This document is the
*execution* layer: what order things land in, who does what, and which parts of the
original briefs are stale.

Written 2026-08-23, after Phase 0 was verified on hardware
(see [PHASE-0-VERIFICATION.md](PHASE-0-VERIFICATION.md)).

**Baseline at start of Phase 1:** `refactor/phase-0` @ `67d0089` — 106 tests, 0 failures,
`lintDebug` clean, deployed and registered on both merlinx (MediaTek MT6768) and lavender
(Qualcomm SDM660).

---

## 1. Why Phase 1 is not shaped like Phase 0

Phase 0 was eight independent small diffs. Every agent got its own worktree, they ran
fully in parallel, and merge order barely mattered.

Phase 1 is one high-risk structural change followed by five that are only mechanical
*because* it exists. The ROADMAP's dependency diagram draws GW-11…GW-15 as a flat parallel
fan-out after GW-10. **That diagram is wrong in two ways**, both visible in the issue
files' own `Conflicts with` lines:

1. **Four of the five rewrite `PjsipSipService.java`** — GW-11, GW-13, GW-14, GW-15. The
   ROADMAP already flags it as a conflict hot-spot but then draws them in parallel anyway.
2. **Two have real API dependencies on their siblings**, not just on GW-10:
   - GW-13 §3 requires call identity to be threaded into `CallManager` — that API is
     defined by GW-11.
   - GW-14's target code calls `audioBridge.stopBridge(generation)` — generations are
     introduced by GW-12.

So the true graph is:

```
GW-10  (alone, blocking, high risk)
  │
  ├── wave 1 ──┬─ GW-11  CallManager transition table      → CallManager.java
  │            ├─ GW-12  audio bridge generations          → AudioBridgeManager, GsmAudioPort
  │            └─ GW-15  endpoint lifecycle                → SipEndpointManager, ReconnectionStrategy
  │
  └── wave 2 ──┬─ GW-13  single GSM state source           (needs GW-11's identity API)
               └─ GW-14  sequenced reload pipeline         (needs GW-12's stopBridge(gen))
```

Wave 1's three agents touch largely disjoint *regions* of `PjsipSipService.java`
(callback dispatch / — / init+reconnect), so textual conflicts are manageable. Wave 2's
two touch the phone-state region and the reload region respectively — also disjoint.
Merge in the listed order and rebase each agent's worktree before it starts.

---

## 2. Corrections to the issue briefs

The `GW-1x` files were written **before** Phase 0 landed. Phase 0 changed the threading
landscape they describe. Every agent brief must carry these corrections, because the issue
files are otherwise authoritative and an agent will follow them literally.

### 2.1 GW-10 §4 is wrong about `MuteControls` — do not fold it in

> *"Delete `SipInit`, `ConfigReload`, `MuteControls` and `BatteryOptDisable` as bare
> threads; their bodies become posts."*

`MuteControls` is **no longer a bare thread**. Phase 0 (GW-02, commit `70641d2`) made it a
dedicated `HandlerThread` inside `DeviceMuteManager` (`DeviceMuteManager.java:300`, `:341`)
and that separation is the entire point of the mute-lease design:

- the mute is ~6 s of serialised mixer writes;
- it must be **cancellable mid-write** when the call ends first, which is what stops a
  mute landing after hangup and stranding the microphone (AUDIT B1);
- it must never run on a thread that a call teardown is waiting on (AUDIT H2c).

Folding it into the control thread would block that thread for ~6 s on every single call
and would re-open B1. **Leave `DeviceMuteManager`'s thread exactly as it is.**

Likewise `BatteryLimitService` owns a `BatteryControl` `HandlerThread`
(`BatteryLimitService.java:187`) from Phase 0's GW-05. It is a different service with its
own lifecycle — out of scope, do not touch.

The threads GW-10 *should* fold: `SipInit` (`PjsipSipService.java:193`) and `ConfigReload`
(`:832`) — those two only.

**`BatteryOptDisable` must NOT be folded either**, contrary to GW-10 §4 (and contrary to an
earlier draft of this document). `PowerController.disableBatteryOptimizations()`
(`PowerController.java:118-142`) is six `RootHelper.execRoot` calls at a 5 s timeout each —
a **~30 s worst case** — and it touches *zero* call/audio/SIP lifecycle state: it is
`dumpsys deviceidle`, `appops`, `am set-inactive`, `oom_score_adj`. Folding it in makes the
control thread unavailable for up to 30 s at every service start, which is precisely when
inbound calls arrive. Leave it as its own thread, or move it to a small IO executor —
either is fine, but it does not belong on the control thread.

`ProcessRestart` (`:919`) stays separate — it destroys the process and must not depend on
a looper it is about to tear down. `GsmAudioOpen` and `MixerEnforce`
(`GsmAudioPort.java:298`, `:497`) stay for now; GW-12 §7 decides their fate.

### 2.2 Line numbers throughout are stale

Phase 0 added ~200 lines to `PjsipSipService.java`. Every `file:line` reference in the
`GW-1x` files is off by a drifting offset — e.g. GW-10 cites `onCallState` at `:393`; it
is now at `:441`. **Locate code by method name, never by the cited line number.**

### 2.3 GW-15 §1 is already half-done

> *"`endpoint` / `endpointUseTls` (`SipEndpointManager.java:31-32`) are static and
> non-volatile."*

They are `private static volatile` today (`:36-37`) — Phase 0's GW-07 (`9d03f7c`) did
that. F1's check-then-act race is still real and still needs control-thread
serialisation; only the volatility half of the remedy is already in place.

### 2.4 NEW HAZARD — after GW-10, main must never block on the control thread

`createEndpoint` deliberately hops to the **main** thread to construct `new Endpoint()`,
because pjsua auto-registers the thread that loaded the native library, and that is main.
It then blocks the caller on `latch.await(30, SECONDS)`
(`SipEndpointManager.java:190`, `:205`).

Today the caller is the `SipInit` bare thread, so the await is harmless. After GW-10 the
caller is the **control thread**, which will block for up to 30 s waiting on main. That is
acceptable — the control thread is allowed to block — but it establishes a hard,
one-directional rule:

> **Control thread may block on main. Main must NEVER block waiting on the control
> thread.** Any main-thread code that needs a result from the control thread must take a
> callback, not a latch/`Future.get`/`join`.

Violating this deadlocks the app for 30 s and then leaves the endpoint uncreated. GW-15 §1
says this await's safety should be "documented explicitly, because on the current code it
is accidental" — this is the documentation, and it is now a phase-wide invariant, not a
GW-15 detail.

### 2.5 `GatewayControlThread` must be unit-testable

Not mentioned in GW-10 at all, but the test suite is Robolectric-based and drives loopers
with `shadowOf(looper).idle()` (see `GsmDtmfSenderTest`, `ReconnectionStrategyTest`). If
the class hard-constructs its own `HandlerThread` and hides the `Looper`, none of Phase 1
is testable on the JVM. Requirement: expose the `Looper`, or accept one by injection, so a
test can drive the queue deterministically.

---

## 2.6 What posting the pjsua callbacks actually breaks

GW-10's whole mechanism is "the three unrouted callbacks become `control.post(...)`". Its
§Risk warns, correctly, to "watch for anything that assumed the callback had already run".
Recon enumerated those. **Three of them are not cosmetic — they are how the code avoids an
uncatchable `abort()` or a wrong-call teardown.** GW-10 must handle all of these *in its
own diff*; deferring them to GW-11 means shipping a known call-tearing bug in between.

### 🔴 P0 — the diagnostic call gets mis-routed into the gateway state machine

`PjsipSipService.onCallState` demuxes gateway vs. diagnostic calls by asking
`testCall.owns(call)`. Today that runs inline on the pjsua worker. Posted, it is evaluated
**later** — and `SipTestCallManager.startInternal`'s catch block sets its `call` field to
`null` on a failed dial (`SipTestCallManager.java:236`).

Sequence after posting: dial fails → PJSIP delivers `DISCONNECTED` inline → handler is
*queued* → catch sets `call = null` → queued handler runs → `owns()` now returns **false**
→ the diagnostic call's disconnect falls through into `callManager.onSipCallState(...)` →
**`terminateAllCalls()` on a live, unrelated gateway call.**

**Resolution: pull GW-11 §4 forward into GW-10.** Give `GatewayCall` a `final Owner` enum
(`GATEWAY` | `DIAGNOSTIC`) set in the constructor and dispatch on `call.getOwner()`.
Because it is immutable, evaluating it late is safe by construction. This is a ~10-line
change and it is the *only* clean fix — capturing the `owns()` result at post time works
but leaves the trap armed for the next callback that gets posted. Strike §4 from GW-11's
scope when it runs.

### 🔴 P0 — `mediaValid` and `disposed` must keep flipping on the callback thread

`SipTestCallManager.mediaValid` (`:178`) and `GatewayCall.disposed` (`GatewayCall.java:83`)
are both set the instant `DISCONNECTED` arrives, synchronously. They gate every subsequent
call into pjsua2. `mediaValid` in particular guards `stopTransmit` against a destroyed
conference port — and per `AudioBridgeManager.java:210-216`, that failure is a pjmedia
assertion, i.e. **`abort()`, not a catchable exception**.

If the whole callback body is posted, these flip late, and any teardown already queued
ahead of them (`autoHangup`, a user `stop()`, the 2 s poller) runs against media PJSIP has
already destroyed.

**Rule: post the *handling*, never the flag.** `disposed`, `mediaValid` and
`SipAccountManager.registered` are set on the callback thread; only the work that follows
is posted. This is the same split GW-10 §3 already mandates for `GatewayCall` construction
in `onIncomingCall` — apply it consistently.

Corollary: the `if (disposed) return;` guards must be **re-checked inside** the posted
runnable, since `dispose()` can be called from another thread in the interim.

### ⚠️ P1 — a stale queued DISCONNECTED can terminate the *next* call

`CallManager.onSipCallState`'s DISCONNECTED branch nulls `currentSipCall` under an identity
check (posting-safe), but then calls `terminateAllCalls()` (`CallManager.java:401-403`)
with **no** identity guard. After posting, a queued disconnect for call A can tear down a
call B that started in the meantime. Add the identity guard in GW-10.

### ⚠️ P1 — comments that become false

`CallManager.onOutgoingCallFailed` (`:226-254`) is built entirely around synchronous
delivery: the compare-and-clear and the "deliberately a FRESH read" at `:250` exist only
because a `DISCONNECTED` could already have run. Once posted, that re-read is always
redundant. The code still behaves correctly — but the 18-line rationale at `:153-169` and
the comment at `:247-249` become actively misleading, and the next person to simplify them
will be reasoning from a false premise. **Rewrite the comments in the same diff.**

The same applies to `makeSipCallWithCallerId`'s register-before-dial comment
(`:741-749`): after GW-10 the ordering is guaranteed *only if the dial itself runs on the
control thread*. Assert that, and say so.

### 🐛 Pre-existing NPE that GW-10 widens

`PjsipSipService.java:406-407` reads `inCallService.getCurrentCall()` **twice** and
dereferences the second read — exactly what `GatewayInCallService`'s own class doc
(`:28-30`) forbids, because `onCallRemoved` nulls the field from main. Posting widens the
window. Rule 1 below says don't fix unrelated bugs, but this one is *aggravated by* the
change, so fix the double-read (one local) and note it in the commit body.

### Tests that will silently stop covering production

`CallManagerTest.testSynchronousDisconnectDuringMakeCallLeavesNoPhantom` (`:274-291`) and
`testSlotIsReusableAfterASynchronousFailure` (`:299-319`) drive `onSipCallState` inline.
They call `CallManager` directly, so they keep passing — while testing a path production no
longer takes. Add posted-callback variants; do not delete the inline ones (PJSIP can still
deliver inline *to the callback thread*, which is what they model).

## 2.7 The `GatewayStatus` snapshot — smaller than expected, with two traps

Recon mapped the full read surface. It is narrower than GW-10 §5 implies:

- **The 1 Hz UI poll needs exactly four values**: `isRunning`, `isSipRegistered`, the
  composite `getStatus()` string, and the test-call report.
- **`WebConfigServer` reads no live gateway state at all** — every endpoint goes straight
  to `SharedPreferences`. The "NanoHTTPD reads manager state" hazard documented in three
  class comments is *latent, not actual*. Nothing to migrate.
- **`GET_STATUS` is a `TODO` stub** (`GatewayControlReceiver.java:93-96`) that logs and
  returns. The snapshot is what finally makes it implementable — so design it to flatten
  into a `Bundle`/JSON, because that is its second consumer.
- Several getters are entirely dead: `isWebServerRunning`, `isTestCallActive`,
  `AudioBridgeManager.isBridgeActive`/`isInitialized`, `CallManager.getPendingGsm*`,
  `SipEndpointManager.isRunning`/`getStateInfo`. Do not build snapshot fields for callers
  that do not exist; note them for GW-31 instead.

**Trap 1 — never snapshot `isInGracePeriod()` as a boolean.** It is
`now - gsmCallPlacedTime < 5000` (`CallManager.java:121-126`). Frozen into a snapshot it
reports "in grace period" for the whole life of that snapshot, and the watchdog acts on it.
Carry the raw `gsmCallPlacedAtWallMs` and make `isInGracePeriod()` a *derived accessor*
that re-reads the clock. Same rule for any future "time since X" value.

**Trap 2 — keep the test-call report out of the snapshot.** It is a `StringBuilder` capped
at 20 000 chars, appended from two threads and polled at 1 Hz. Copying it into every
snapshot makes publish cost proportional to report length. Leave it as its own field.

**Commands still need the live instance.** The snapshot replaces *reads*. Of the eight
`getInstance()` call sites, seven are commands (`stop`, `reloadConfig`, `startTestCall`,
`hangupCall`, `onGsmCallStateChanged`, …) and keep the handle; only
`GatewayInCallService:398`'s `isSipRegistered()` becomes a snapshot read. A dozen more
sites need a genuinely live pjsua2 object (`getAccount()` for dialling and for SIP MESSAGE,
`getGsmAudioPort()` for conference wiring, the Telecom `Call`) — those must be *posted to
the control thread and dereferenced there*, never described in a snapshot.

---

## 3. Standing rules for every Phase 1 agent

These are in addition to [ROADMAP.md](ROADMAP.md) §4, which still applies in full.

1. **Move code between threads. Change nothing else.** This is GW-10's own §Risk
   mitigation and it applies to the whole phase. Anything that looks like a bug goes into
   `AUDIT.md` as a new finding — not into the diff.
2. **The pjmedia RT callbacks are untouchable.** `GsmAudioPort.onFrameRequested` /
   `onFrameReceived` must never post, block, allocate unboundedly, or take a lock the
   control thread can hold across I/O. A change that makes them wait on anything the
   control thread holds for >1 ms is wrong by construction.
3. **`pjmedia` assertion failures are `abort()`, not exceptions.** A `try/catch` around a
   conference-port operation proves nothing. Prove liveness on the same thread with no
   window in between.
4. **Do not touch** `app/src/main/java/org/pjsip/pjsua2/**` (SWIG-generated, vendored),
   `targetSdkVersion` (27, deliberate), or the ALSA control names and their ordering.
5. **Never mute the mic via `AudioManager`** — ALSA mixer only.
6. Verify with `./gradlew test` and `./gradlew lintDebug` before reporting done. Baseline
   is 106 tests / 0 failures; only *new* lint issues fail the build.
7. **Revert-and-confirm-failure**: for every new test, verify it actually fails against the
   unfixed code, and say so in the report. A test that passes both ways is not evidence.
   If a fix's nature makes that impossible, say that explicitly instead of claiming it.
8. State in the commit body which AUDIT finding IDs the change closes and how each was
   verified.

---

## 3b. Findings recon turned up that are NOT Phase 1 work

File these in `AUDIT.md`; do not fold them into a Phase 1 diff.

- **NEW — `GsmAudioPort` statistics counters are a real data race.**
  `framesRequested`/`framesReceived`/`captureErrors`/`playbackErrors`
  (`GsmAudioPort.java:117-120`) are plain non-volatile `long`s, **written on the pjmedia RT
  thread** (`:201`, `:218`, `:245`, `:264`) and read *and reset to zero* from
  main/pjsua/reload in `stopCapture` (`:592-600`). No volatile, no atomic, no lock, on
  either side — and as 64-bit non-volatile they can tear (JLS 17.7). Consequence today is
  only wrong log lines and a reset the RT thread may never observe. **The dangerous part is
  the obvious fix:** adding `synchronized`/`stateLock` here would park the RT thread behind
  `GsmAudioNative.close()`'s ~250 ms drain and cause actual audio dropouts. Fix with a
  control-side baseline snapshot (`framesRequested - baseline`), never with a lock.
- **NEW — unsafe publication of the manager references.** `PjsipSipService`'s `config`,
  `endpointManager`, `accountManager`, `callManager`, `audioBridge`, `testCall`,
  `mainHandler`, plus `CallManager.listener` and `SipAccountManager.listener`, are all
  non-`final` **and** non-`volatile`, written on main during `onCreate`, and read from
  pjsua workers / `SipInit` / `ConfigReload`. They work today only via incidental
  happens-before from `Thread.start()` and `Handler.post()`. Make them `final`. (GW-07
  covered the *state* fields; it did not cover these.)
- **NEW — `DeviceMuteManager.getInstance()` is `static synchronized` and, on first call,
  starts a `HandlerThread` *and* does SharedPreferences disk I/O (`:378`) under the class
  monitor — on main, on the Telecom `STATE_ACTIVE` path** (`PjsipSipService.java:541`).
- **NEW — two executors are never shut down.** `PermissionManager.shutdown()` (`:166`) and
  `AudioDeviceManager.shutdown()` (`:134`) have no callers; both leak for the process
  lifetime.
- **Dead code for GW-31**, confirmed by two independent agents: `AudioBridgeManager.release()`,
  `GsmAudioPort.stop()`, `SipEndpointManager.destroyEndpoint()`/`isEndpointValid()`/`getStateInfo()`,
  `GatewayInCallService.rejectCall()`, `AudioBridgeManager.isBridgeActive()`/`isInitialized()`,
  `PjsipSipService.isWebServerRunning()`/`isTestCallActive()`,
  `CallManager.getPendingGsmDestination()`/`getPendingGsmSimSlot()`,
  `SipAccountManager.getLastError()`. Also `AudioBridgeManager.wiredConfSlot` (written three
  times, never read) and both classes' `listener` fields (their `setListener` is never
  called, so `onBridgeStarted`/`onBridgeError`/`onEndpointStarted` can never fire).

## 3c. Two locks that GW-10 makes urgent

Not GW-10's to fix, but it changes their risk profile — flag them to whoever takes GW-11:

**`CallManager.hangupSipCall()` (`:578`, `synchronized`) nested inside
`PjsipSipService.hangupCall()` (`:758`, also `synchronized`)** holds two monitors across a
pjsua2 BYE, a Telecom `disconnectCall()`, *and* `GsmAudioPort.stopCapture()` — a ~1.75 s
join plus a ~250 ms native drain. It is entered from main *and* from pjsua workers, while
`terminateAllCalls()` — the actual caller — is not synchronized at all, so the monitor
protects nothing today. Once `onCallState` is posted, both monitors become simultaneously
redundant *and* a deadlock surface. GW-11 §1 already deletes the inner one; delete the
outer one with it.

**`GsmAudioPort.stateLock` is held across blocking I/O in three places** — a
`Thread.join(500)` (`:527`), `GsmAudioNative.close()`'s ~250 ms drain (`:474`), and on
Qualcomm N unbounded `su` spawns inside `profile.setupMixer()` (`:334`). The class javadoc
(`:82-89`) claims the lock is never held across blocking work; that claim is true only for
the two operations it names. Nothing on the RT path may ever take this lock.

---

## 3d. GW-11 leaves a trap for GW-13 — read this before demoting PhoneStateListener

GW-11 made `terminateAllCalls()` idempotent: it now returns immediately when the machine is
already `IDLE`, so it no longer fires `onCallsTerminated()` and therefore no longer calls
`stopBridge()` / `stopAudioStreams()` from `IDLE`. That is correct and intended.

It is safe **today** only because a second, unconditional teardown path still exists:
`PjsipSipService.handlePhoneState`'s `CALL_STATE_IDLE` branch calls
`audioBridge.stopAudioStreams()` regardless of `CallManager` state, with this comment:

> *"Always stop the audio streams so the mixer routing is torn down even for calls that never
> reached the BRIDGED state (otherwise the enforce thread would keep the local mic muted)."*

That branch is on the **`PhoneStateListener` path — exactly the path GW-13 exists to
demote or delete.** The Telecom `Call.Callback` path (`onGsmCallStateChanged`
→ `STATE_DISCONNECTED`) does **not** stop audio streams; it only calls
`callManager.onGsmCallEnded()` and releases the mute lease.

So if GW-13 removes `handlePhoneState`'s side effects without first moving the
unconditional `stopAudioStreams()` onto the Telecom path, a GSM call that ends **without
ever reaching `BRIDGED`** loses both teardown routes at once:

- `onGsmCallEnded()` → `terminateAllCalls()` → returns early, because the machine never
  left `IDLE` for such a call;
- `handlePhoneState(IDLE)` → deleted.

The symptom is the one the comment warns about and that GW-08 was written to stop: the
orphaned `MixerEnforce` thread keeps re-asserting the call routing and the mic mute every
2 s, with no open PCM and no call — i.e. **a phone with a dead microphone until reboot.**

**Requirement on GW-13:** whichever path becomes the single source of truth must call
`stopAudioStreams()` unconditionally on GSM end, independent of `CallManager` state. Verify
it specifically with a call that never reaches `BRIDGED` — reject the SIP leg, or hang up
the GSM leg during ring.

## 4. Exit criterion for Phase 1

From the ROADMAP, unchanged:

> a `@ControlThread` annotation + a debug-build assertion on every state-mutating method,
> and the full Phase 0 call-cycle suite still green with the assertion armed.

Concretely, before Phase 1 is declared done:

- [ ] Debug build with `assertOnControlThread()` armed survives the full call matrix
      (SIP→GSM, GSM→SIP, both SIMs, hangup from each side, hangup during ring, hangup
      during ALSA open) with **zero** assertion failures, on **both** SoCs.
- [ ] `logcat` shows no `Skipped \d+ frames` around call setup/teardown.
- [ ] Service thread count drops relative to the `67d0089` baseline
      (`dumpsys activity service org.onetwoone.gateway/.PjsipSipService`).
- [ ] Two-way audio still works — the `Conference links lost … rewiring` path still fires.
      This is the regression that matters most; losing it brings back one-way audio.
- [ ] No new tombstones across the cycle suite (`adb shell ls /data/tombstones`).

---

## 5. Carried forward into Phase 1 from Phase 0

Findings that Phase 1 interacts with but does not close:

- **E5 (P0, mechanism proven)** — SIP-side hangup leaves the GSM leg up 1.8–50.7 s;
  conference-mutex starvation, backtrace in `evidence/E5-conf-mutex-starvation.md`. Fixed
  by **GW-23 (Phase 2)**, not by Phase 1.
- **A1 (re-ranked P1-latent)** — the native UAF has *never* been observed in 33 teardowns;
  all 8 historical tombstones were F2, already fixed by `2626f5d`. Important sequencing
  note: **E5's blocking read currently, accidentally, protects against A1** by quiescing
  the RT thread before `close()`. GW-23 removes that read. So GW-23 must not land before
  GW-01's refcount is confirmed sound, or a latent bug becomes a live one.
- **B1d (P1, open)** — the Qualcomm mic-volume restore is refused by the kernel. Now
  *visible* (commit `67d0089`) but not solved. Untouched by Phase 1.
- **B1b / B4b / B4c** — out-of-process restore state: a process kill leaves a mute or a
  charging block with nothing to restore it. Still unfiled as an issue.

---

## 6. Wave 1 on-device verification — 2026-08-24

Build: `refactor/phase-1` with GW-10, GW-11, GW-12, GW-15 merged. 180 tests/variant
(from a 106 baseline at the start of Phase 1), 0 failures, `lintDebug` clean.
Debug build (assertions **armed**, they throw) on merlinx; release on lavender.

| Criterion | merlinx (MT6768) | lavender (SDM660) |
|---|---|---|
| Two-way audio, both directions | ✅ user-confirmed | ✅ user-confirmed |
| `assertOnControlThread` failures | **0** | **0** |
| `ILLEGAL TRANSITION` (GW-11 table) | **0** | **0** |
| `Skipped N frames` | **0** | **0** |
| `Conference links lost … rewiring` | ✅ fired | ✅ fired |
| New tombstones | **0** | **0** |
| `RESTORE REFUSED` (B1d) | 0 | 0 |
| Web `/api/config` from a NanoHTTPD worker | ✅ HTTP 200, no assertion | — |

Both call directions were exercised on both phones, and **GW-11's state split is visibly
working** — the GSM→SIP direction now walks `IDLE → GSM_INCOMING → SIP_DIALING → BRIDGED`
instead of sitting in `SIP_INCOMING` and lying to the UI. Every lifecycle line is on the
control thread's tid.

### Do not chase this: "Audio bridge started" 3× vs "stopped" 2×

The counts look unbalanced and they are not a leak. The re-wire branch logs
`Audio bridge started` a *second* time for the **same** call, immediately after
`Conference links lost … rewiring`. Two calls therefore produce three "started" lines and
two "stopped" lines. Verified against the full timeline on both SoCs.

Not fixed deliberately: that log line sits inside the region proved byte-identical to
`ce18980` during the GW-12 review, and re-wording it would invalidate that proof for no
functional gain. If it is ever changed, `Audio bridge rewired` is the honest wording.

### Still not measured

- **Thread-count drop** (a stated exit criterion) — no clean pre-GW-10 baseline was
  captured from an equivalent device state, so the comparison was never made. `SipInit` and
  `ConfigReload` are confirmed gone from the thread table; the numeric claim is unproven.
- **F2 pool-capacity flatness** across ~200 reconnect cycles (GW-15) — the unit test covers
  the assertion that *replaces* the registration, not the leak itself.
- **50-cycle random-offset hangup soak** and the **E2 restart-with-a-bridged-call** case
  (GW-12) — neither has been run.
- A call that **never reaches `BRIDGED`** — see §3d; that is GW-13's gate, not wave 1's.
