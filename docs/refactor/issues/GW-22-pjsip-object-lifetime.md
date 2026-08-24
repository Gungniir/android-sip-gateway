# GW-22 — pjsua2 object lifetime: an unbounded leak per call

**Phase** 2 · **Severity** P2 (unbounded growth on a 24/7 device) · **Closes** AUDIT H7
**Files** `GatewayCall.java`, `call/CallManager.java`, `audio/AudioBridgeManager.java`, `diag/SipDiagnostics.java`, `PjsipSipService.java`
**Depends on** GW-11, GW-12 · **Conflicts with** GW-12

## Problem

The codebase deliberately never deletes `Call` objects:

```java
// DON'T delete the call object - PJSIP manages the native lifecycle.
// Calling delete() crashes because PJSIP may still reference it.
// The call object will be GC'd eventually - the disposed flag prevents callback issues.
```
(`CallManager.java:258-260`, and again at `:461-462`, `SipTestCallManager.java:394-395`.)

That workaround is understandable — deleting a `Call` while pjsua still holds the director
pointer *does* crash — but the conclusion is wrong. A pjsua2 `Call` is a **SWIG director**:
the Java object holds a C++ shadow that will not be reclaimed by the JVM's GC. "It will be
GC'd eventually" does not happen for the native half.

Beyond `Call`, the per-callback value objects are also never released:
- `CallInfo` from `getInfo()` — `CallManager.java:147`, `AudioBridgeManager.java:98`,
  `PjsipSipService.java:412`, `SipDiagnostics.java:66`, and every poll of
  `SipTestCallManager` (every 2 s during a diagnostic call).
- `CallMediaInfoVector` / `CallMediaInfo` — `AudioBridgeManager.java:99-102`.
- `AudioMedia` from `typecastFromMedia` — `AudioBridgeManager.java:127`.
- `IntVector` from `transportEnum()` — `SipEndpointManager.java:88` (this one **is**
  deleted, at `:91` — the correct pattern already exists in the codebase).
- `AudioMediaVector2` from `mediaEnumPorts2()` — `SipDiagnostics.java:98`, `:172`.
  `isLiveConfPort` is called on every bridge teardown, and the diagnostics poller calls
  `dump()` every 2 s.

On a gateway handling hundreds of calls a day, unattended for weeks, this is unbounded
native heap growth.

## Required change

1. **Establish the rule and write it down** (in `CLAUDE.md` and in a comment on
   `GatewayCall`): every pjsua2 object the Java side *creates* must be deleted by the Java
   side; every object pjsua2 *hands back by value* must be deleted after use. The one
   exception is objects pjsua2 owns and hands back by reference.
2. **Delete the value objects.** Wrap the short-lived ones in try/finally, following the
   `transportEnum()` pattern already at `SipEndpointManager.java:88-92`. Start with the
   ones on repeating paths — they matter most:
   - `SipDiagnostics.isLiveConfPort` (`:172`) — runs on every teardown.
   - `SipDiagnostics.dump` (`:66`, `:98`) — runs every 2 s during diagnostics.
   - `AudioBridgeManager.startBridge` (`:98-102`, `:127`) — runs per call and per re-wire.
3. **Solve `Call` deletion properly.** The crash the comment describes is real, so the fix
   is ordering, not omission:
   - A `Call` may be deleted once pjsua has delivered `PJSIP_INV_STATE_DISCONNECTED`
     **and** the callback has returned. After GW-10, callbacks are posted, so the
     disconnect handler runs on the control thread *after* the pjsua worker has returned —
     which is exactly the safe point.
   - Keep a deferred-deletion queue on the control thread: on DISCONNECTED, `dispose()`
     the call and enqueue it; delete on the next control-thread turn (a
     `postDelayed(…, 0)` or a short delay), so nothing on the current stack can still
     reference it.
   - Add a counter (`callsCreated` / `callsDeleted`) exposed in `getStatus()` so the leak
     is observable rather than theoretical.
4. **Do not touch `PjsipLogWriter`.** Its singleton must stay strongly reachable forever
   (`diag/PjsipLogWriter.java:23-27`) — it is a director held by native code. The comment
   there is correct; reinforce it, do not "clean it up".

## Acceptance criteria

- [x] The lifetime rule is documented in `CLAUDE.md`, and codified in `sip/Pjsua2Lifetime`.
- [x] Every repeating-path **owned** value object is deleted in a `finally`. Note the
      correction in PHASE-2-PLAN §2.3: `CallMediaInfoVector`, `CallMediaInfo` and the
      `AudioMedia` from `typecastFromMedia` are `(ptr, false)` and are deliberately **not**
      deleted.
- [x] `Call` objects are deleted by `call/CallGraveyard` on the control thread, never from
      inside a pjsua callback — gated on `getId() == PJSUA_INVALID_ID`, which is a labelled
      heuristic, not the "no window" proof ROADMAP rule 4 asks for.
- [x] `callsCreated` / `callsDeleted` counters are exposed — as fields on `GatewayStatus`
      (with `getCallsAlive()`), in `toBundle()` and in `toString()`. Not added to the three-line
      UI composite, which is unchanged.
- [x] `PjsipLogWriter`'s strong reference is untouched.
- [ ] **Owed on hardware:** the 500-cycle soak, `callsAlive` == active calls at the end, and
      zero tombstones. Unmeasured.

## Verification

1. **Soak test.** 500 call cycles (scripted, ~2 s each). Sample native heap before and
   after:
   ```
   adb shell dumpsys meminfo org.onetwoone.gateway | grep -E 'Native Heap|TOTAL'
   ```
   Native heap must be flat within noise. Run the same soak *without* the fix first to
   record the baseline slope — the delta is the proof.
2. `callsCreated - callsDeleted` must equal the number of currently active calls (0 or 1)
   at the end of the soak.
3. Zero tombstones (`adb shell ls /data/tombstones`) — a premature delete presents as a
   native crash, so the soak is also the safety test.

## Risk

**High for the `Call` deletion half; low for the value objects.** Split the work:
land the value-object deletions first (low risk, immediately measurable), soak them, and
only then attempt `Call` deletion. If the deferred deletion produces any tombstone, revert
that half and keep the counters — knowing the leak rate is still worth having.
