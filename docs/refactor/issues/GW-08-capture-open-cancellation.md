# GW-08 — Cancelled ALSA open can re-arm capture after teardown, leaking a `MixerEnforce` thread

**Phase** 0 · **Severity** P0 (device unusable + thread leak) · **Closes** AUDIT B3, E4
**Files** `GsmAudioPort.java`
**Depends on** nothing · **Conflicts with** GW-01, GW-04, GW-23

## Problem

`startCapture()` (`:228`) spawns `GsmAudioOpen`, which retries `GsmAudioNative.open()` up
to 20 times at 500 ms intervals (`:261-284`) — up to ~10 s.

`stopCapture()` (`:338`) tries to cancel it:

```java
isCapturing.set(false);
if (openThread != null) {
    openThread.interrupt();
    try { openThread.join(1000); } catch (InterruptedException ignored) { ... }   // :349
    openThread = null;
}
stopEnforceThread();
GsmAudioNative.close();
profile.teardownMixer(card);
```

The worker checks `isInterrupted()` only **between** attempts (`:262`) and catches
`InterruptedException` only in the `sleep` (`:279`). `GsmAudioNative.open()` itself is a
blocking native call and is **not interruptible** — it can run past the 1 s join. When it
returns `true`, the worker unconditionally does:

```java
isCapturing.set(true);      // :293
startEnforceThread();       // :294
```

— after `stopCapture()` has already closed the device and torn the mixer down.

`openThread` and `enforceThread` (`:56`, `:58`) are also plain fields written from main
and read/nulled from whichever thread ran `stopCapture` (main, a pjsua worker via
`onCallsTerminated`, or `ConfigReload`).

## Failure scenario

Incoming SIP-first call where the modem voice path is slow to come up:
1. GSM answers → `startCapture()` → `GsmAudioOpen` begins retrying.
2. Caller hangs up 2 s later → `stopCapture()` on a pjsua worker: `isCapturing=false`,
   interrupt, join times out after 1 s, `close()`, `teardownMixer()`.
3. Attempt 6 succeeds at t=3 s → `isCapturing.set(true)`, new `MixerEnforce` thread.
4. `MixerEnforce` now re-asserts the call routing **and the mic mute** every 2 s, forever,
   with no open PCM and no call.

Symptom: **the microphone stays muted indefinitely** (same user-visible outcome as GW-02
and GW-04, different cause), plus one leaked thread per occurrence. The routing
re-assertion also fights the audio HAL for the rest of the process's life.

## Required change

Introduce an explicit **session generation** so a stale worker cannot publish its result.

1. `private final AtomicInteger sessionId = new AtomicInteger();`
2. `startCapture()` increments it and captures the value; the worker carries it.
3. `stopCapture()` increments it first — that alone invalidates any in-flight worker.
4. The worker publishes only if its generation is still current:
   ```java
   if (sessionId.get() != mySession) {
       // Superseded by stopCapture(): own the cleanup of what we opened.
       GsmAudioNative.close();
       profile.teardownMixer(card);
       return;
   }
   isCapturing.set(true);
   startEnforceThread();
   ```
   Note the worker must clean up the device **it** opened — `stopCapture()` already ran
   and closed nothing (there was nothing open yet).
5. Same generation check before `profile.setupMixer(card)` (`:257`) and after each retry
   sleep, so a cancelled session stops touching the mixer promptly.
6. Guard the double-teardown: `openWithRetry`'s failure path (`:290`) and `stopCapture()`
   can both call `teardownMixer`. With GW-04 landed, teardown is idempotent — depend on
   that, and state the dependency in a comment.
7. Make `openThread` / `enforceThread` `volatile` and snapshot before use (this overlaps
   GW-07 — whichever lands first wins; do not revert the other).
8. `startCapture()`'s guard at `:229-236` (`isCapturing` + `openThread.isAlive()`) becomes
   redundant noise once generations exist; replace it with a single check that logs and
   returns when a session is already current.

## Acceptance criteria

- [ ] A `stopCapture()` issued at any point during `openWithRetry` results in: no
      `isCapturing == true`, no live `MixerEnforce` thread, mixer restored, device closed.
- [ ] A superseded worker closes whatever it opened before returning.
- [ ] No `MixerEnforce` thread outlives its session.
- [ ] `openThread` / `enforceThread` are never read twice without a snapshot.
- [ ] The open-retry policy (20 attempts, 500 ms) is unchanged — it is tuned for the
      modem voice path coming up late on SIP-first calls.

## Verification

1. On-device, the exact failure scenario: force a slow open by pointing the profile at a
   busy PCM (or temporarily raise `OPEN_RETRY_MS`), start a call, hang up at t≈2 s.
   Then:
   ```
   adb shell su -c "ls /proc/$(pidof org.onetwoone.gateway)/task" | wc -l
   ```
   Thread count must return to baseline; repeat 10× and confirm it does not grow.
2. `logcat -s GsmAudioPort` must show `Open aborted` / superseded lines and **no**
   `Native audio started` after the corresponding `Stopping native audio`.
3. Mic check after each cycle, as in GW-04.

## Risk

Low-medium. The subtle part is who owns cleanup when the worker is superseded — get that
wrong and the device is left open with the mixer patched. Assert both post-conditions in
the test.
