# GW-25 — Watchdog only detects one orphan direction; no fail-safe deadlines

**Phase** 2 · **Severity** P1 (burns GSM minutes; device left in a bad state) · **Closes** AUDIT H9
**Files** `PjsipSipService.java`, `sip/ServiceWatchdog.java`, `call/CallManager.java`
**Depends on** GW-10, GW-11, GW-13 · **Conflicts with** GW-11, GW-13

## Problem

`checkOrphanedCalls` (`PjsipSipService.java:630`) handles exactly one case:

```java
if (!callManager.hasActiveCall()) return;
if (callManager.isInGracePeriod()) return;
if (lastPhoneState == TelephonyManager.CALL_STATE_IDLE) {
    GatewayCall sipCall = callManager.getCurrentSipCall();
    if (sipCall != null) { callManager.terminateAllCalls(); }
}
```

— a SIP call with no GSM leg. Three gaps:

1. **The reverse orphan is never detected.** A live GSM call with no SIP leg (SIP call
   failed, was rejected, or the bridge never wired) stays up until the far end hangs up.
   On an outbound SIP→GSM call this **burns GSM minutes silently**. There is no timeout on
   the GSM leg at all once it is answered — `INCOMING_TIMEOUT_MS`
   (`GatewayInCallService.java:28`) only covers the pre-answer window.
2. **A bridged call with no audio is invisible.** The bridge can be up
   (`state == BRIDGED`) while the conference links are dead or the ALSA device never
   opened. `AudioBridgeManager.isAudioStreaming()` (`:310`) and the
   `framesRequested`/`framesReceived` counters (`GsmAudioPort.java:66-67`) already carry
   the evidence; nothing acts on it.
3. **It depends on `lastPhoneState`**, which GW-13 removes.

There are also no upper bounds anywhere: a call, a mute lease, a disabled charger and a
held wake lock can all persist indefinitely if a state transition is missed.

## Required change

1. **Both orphan directions.** Rewrite `checkOrphanedCalls` against the post-GW-13 source
   of truth (`GatewayInCallService`'s tracked call), covering:
   - SIP call live, no GSM call → terminate (existing behaviour).
   - GSM call live (`STATE_ACTIVE`), no SIP call and not in grace period → terminate.
   - `CallManager.state == BRIDGED` but either leg missing → terminate.
   Keep `GSM_CALL_GRACE_PERIOD_MS` (`CallManager.java:40`) as the guard against
   terminating a call that is legitimately mid-setup.
2. **Detect the silent bridge.** If `state == BRIDGED` for more than N seconds
   (suggest 10) and `framesRequested` has not advanced, the transmit leg is dead. Log it
   loudly with the `SipDiagnostics` dump — that dump exists precisely to explain this case
   (`diag/SipDiagnostics.java:35-45`). Do **not** auto-terminate on this signal initially:
   ship it as detection-only, confirm it never false-positives over a week of real calls,
   and only then decide whether to act on it.
2b. **Reap a registered call the state machine has forgotten.** `checkOrphanedCalls`
   short-circuits on `!hasActiveCall()` (i.e. `state == IDLE`), so a non-null
   `currentSipCall` while IDLE is never reaped. GW-06 made that unreachable on the
   outgoing path, but the watchdog still cannot see it if any other path produces it.
   Add the check: IDLE with a non-null, non-disposed `currentSipCall` is an invariant
   violation — log it and clear.

3. **Hard deadlines as fail-safes.** Each with a loud error log — reaching one means a
   transition was missed and that is a bug to investigate, not a normal path:
   - Max call duration (suggest 2 h) → terminate.
   - Max mute-lease hold (GW-02, suggest 4 h) → force restore.
   - Max charging-disabled (GW-05, suggest 12 h) → force enable.
   - Max `TERMINATING` dwell (suggest 30 s) → force to `IDLE`.
4. **Expose the invariants.** Add the watchdog's findings to `getStatus()` so the UI and
   the `GET_STATUS` broadcast can surface them, instead of them living only in logcat.
5. `ServiceWatchdog` moves to the control thread's looper (GW-15 §4) — its `running` flag
   then needs no synchronisation, just an assertion.

## Acceptance criteria

- [x] Both orphan directions are detected and terminated, respecting the grace period —
      **and, on the inbound direction, a 45 s dwell, because the grace period does not
      exist there.** `gsmCallPlacedTime` is assigned only by `placeGsmCall()`, so
      `isInGracePeriod()` is permanently false for the whole GSM→SIP direction. The dwell
      outlasts both mechanisms that are supposed to act first (the ~20 s SIP retry chain
      and the 30 s `INCOMING_TIMEOUT_MS`).
- [x] A bridged-but-silent call is detected and logged with a `SipDiagnostics` dump; it does
      not auto-terminate. The dump is **latched to once per episode** — at a 3 s tick, one
      per tick would be ~1200 an hour.
- [x] ~~All four~~ **The two that were missing.** The mute lease (4 h, GW-02) and
      charging-disabled (12 h, GW-05) already existed with error logs and were not rebuilt.
      Max call duration (2 h) is new. **TERMINATING dwell is logged only, and that is the
      honest answer:** `terminateAllCalls()` walks in and out of that state synchronously
      with no suspension point, `transition()` is private, and there is no API to force
      `TERMINATING → IDLE`. There is no machinery to build for a state that cannot stick.
- [x] Watchdog findings appear in the status — as `GatewayStatus.WatchdogFindings`, which
      also flattens into `GET_STATUS`'s bundle. Nothing time-derived is frozen.
- [x] No dependence on `lastPhoneState` (already true after GW-13; still true).
- [x] `WATCHDOG_INTERVAL_MS` (3 s) unchanged.
- [ ] **30 normal calls, zero watchdog terminations.** Needs hardware. Score it from
      `GET_STATUS`'s `watchdog_terminations`, which is in the bundle for exactly this.
- [ ] Silent-bridge detector fires on a deliberately broken bridge. Needs hardware.

## What shipped that the brief did not ask for

**A transient `InCallService` unbind is not an orphan.** `getInstance() == null` reads as
"no GSM leg" by design, so an unbind was indistinguishable from a real orphan and would have
terminated a live call because a *Service* was rebound. The orphan rules now skip that tick;
the max-duration fail-safe still runs, so a permanently unbound service cannot park a call
forever. The instance is resolved **once** per tick — resolving it twice would put an unbind
between the two reads, which is the same false positive by another route. `isGsmLegLive()`
was removed because it folded "unbound" into "no leg", which is precisely the distinction
the tick has to make.

## Verification

1. **Reverse orphan:** place an inbound GSM call, and once the GSM leg is answered, kill
   the SIP call from the PBX side. The GSM leg must be hung up within
   grace period + one watchdog interval (~8 s). Confirm with the carrier call log that the
   call actually ended.
2. **Silent bridge:** break the bridge deliberately (e.g. point the audio profile at a
   wrong PCM device via config) and place a call. The watchdog must log the diagnosis
   within ~13 s, and the log must contain the conference-wiring dump showing
   `local->call(TX to SIP)=false`.
3. **Deadlines:** temporarily shorten each constant to seconds and confirm each fires
   exactly once with its error log, then restore the real values.
4. Confirm no false positives: 30 normal calls of varying length produce zero watchdog
   terminations.

## Risk

Medium. A watchdog that terminates healthy calls is worse than no watchdog. Every new
termination condition must be gated behind the grace period, and the silent-bridge
detector must ship as detection-only first. Run the false-positive check before merge.
