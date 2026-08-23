# GW-10 — Introduce `GatewayControlThread`: one owner for call/audio/SIP lifecycle state

**Phase** 1 (**land first**) · **Severity** P1 (structural) · **Enables** GW-11…GW-15, GW-25, GW-26
**Files** new `sip/GatewayControlThread.java` (or `core/`), `PjsipSipService.java`, `GatewayCall.java`, `sip/SipAccountManager.java`
**Depends on** Phase 0 merged · **Conflicts with** every other Phase 1 issue — land alone

## Problem

See AUDIT §1. There is no owning thread for call state. pjsua worker callbacks are
handled inconsistently: `onIncomingCall` (`PjsipSipService.java:325`) and
`onRegistrationState` (`:302`) post to main, but `onCallState` (`:393`),
`onCallMediaState` (`:405`) and `onDtmfDigit` (`:423`) run the state machine, the audio
bridge and Telecom hangups **directly on the pjsua worker**, concurrently with main doing
the same.

Everything that blocks — root shell-outs (~6 s), ALSA open with retry (up to 10 s), SIP
REGISTER, `hangupAllCalls` — is split between the main thread (findings G1–G3: ANR) and
ad-hoc bare threads named `SipInit`, `ConfigReload`, `GsmAudioOpen`, `MixerEnforce`,
`MuteControls` (findings D1–F6: races).

## Required change

Create a single serialising thread that owns lifecycle state, and route every entry point
through it.

### 1. The thread

```java
/**
 * The one thread that owns call, audio-bridge and SIP-account lifecycle state.
 *
 * Registered with pjlib exactly once at construction, so anything posted here may call
 * pjsua2 freely. It is NOT the main looper: these operations block for seconds
 * (root shell-outs, ALSA open retries, SIP REGISTER) and must never run on main.
 *
 * The pjmedia RT callbacks (GsmAudioPort.onFrameRequested/Received) do NOT post here —
 * they must never block. See GW-01/GW-23.
 */
public final class GatewayControlThread { … }
```

- Backed by a `HandlerThread` named `GatewayControl`.
- `start()` creates the thread and, once the endpoint exists, registers it with pjlib
  **once** via `SipEndpointManager.registerThread("GatewayControl")`.
- `post(Runnable)`, `postDelayed(Runnable, long)`, `removeCallbacks(Runnable)`.
- `assertOnControlThread()` — throws in debug builds, logs in release.
- `isCurrent()` so re-entrant calls can run inline instead of deadlocking.
- `quitSafely()` on service destroy, with a bounded join.

### 2. Annotation + enforcement

Add `@ControlThread` (a simple `@Retention(SOURCE)` annotation) and put
`controlThread.assertOnControlThread()` as the first statement of every method that
mutates lifecycle state. This is the mechanism that keeps the model from eroding — it is
not optional decoration.

### 3. Route the pjsua callbacks

In `PjsipSipService`, the three unrouted callbacks become posts:

```java
@Override public void onCallState(GatewayCall call, int state) {
    control.post(() -> handleCallState(call, state));
}
```

Same for `onCallMediaState` and `onDtmfDigit`. Note the consequence, and rely on it:
posting means the pjsua worker returns immediately, which also removes the pjsua2
re-entrancy hazard of calling back into pjsua from inside its own callback.

`onIncomingCall`, `onRegistrationState` and `onInstantMessage` move from `mainHandler` to
`control` for the same reason.

**Careful:** `GatewayAccount.onIncomingCall` constructs a `GatewayCall` from the callId
(`PjsipSipService.java:324`). That construction must still happen on the callback thread
(the callId is only valid within the callback) — post the *handling*, not the
construction. Keep the existing split, just change the destination.

### 4. Move the ad-hoc threads onto it

Delete `SipInit`, `ConfigReload`, `MuteControls` and `BatteryOptDisable` as bare threads;
their bodies become posts. Keep `GsmAudioOpen` and `MixerEnforce` for now — GW-12 folds
`GsmAudioOpen` in and decides `MixerEnforce`'s fate (it is a periodic timer, so it may
legitimately stay a separate thread or become a `postDelayed` loop on the control thread).

Keep `ProcessRestart` separate — it kills the process and must not depend on a looper it
is about to destroy.

### 5. UI / status reads

`MainViewModel` polls `PjsipSipService.getStatus()` at 1 Hz from main
(`ui/MainViewModel.java:119`), which reads `callManager`, `accountManager` and
`audioBridge` state. Replace with an immutable `GatewayStatus` snapshot object published
from the control thread to a `volatile` field; the UI reads the snapshot, never the
managers.

## Acceptance criteria

- [ ] `GatewayControlThread` exists, is registered with pjlib once, and is quit safely on
      service destroy.
- [ ] All six pjsua callbacks reach their handlers via `control.post(...)`.
- [ ] `SipInit`, `ConfigReload`, `MuteControls`, `BatteryOptDisable` bare threads are gone.
- [ ] `assertOnControlThread()` guards every lifecycle mutator, and a full call cycle runs
      clean with the assertion armed in a debug build.
- [ ] `MainViewModel` reads a snapshot, not live manager state.
- [ ] No lifecycle operation runs on the main thread; no lifecycle operation blocks a
      pjsua worker.
- [ ] The pjmedia RT callbacks are untouched by this issue.

## Verification

1. Debug build with assertions armed: full call matrix (SIP→GSM, GSM→SIP, both SIMs,
   hangup from each side, hangup during ring, hangup during ALSA open) — zero assertion
   failures.
2. `logcat` for `Skipped \d+ frames` around call setup/teardown: should disappear.
3. `adb shell dumpsys activity service org.onetwoone.gateway/.PjsipSipService` before and
   after — thread count should *drop*.
4. `./gradlew test` green.

## Risk

**High** — this is the structural change. Mitigations:
- Land it alone, on its own branch, with no other Phase 1 issue in flight.
- Do not change any logic in this issue. Move code between threads; change nothing else.
  Anything that looks like a bug while doing so goes into AUDIT.md, not into this diff.
- The riskiest move is posting `onCallState`: a hangup now completes asynchronously. Watch
  for anything that assumed the callback had already run when it returned — GW-11 will
  formalise those assumptions into a transition table.
