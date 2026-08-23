# GW-13 — One source of truth for GSM call state (drop the duplicate `PhoneStateListener` path)

**Phase** 1 · **Severity** P1 · **Closes** AUDIT D3
**Files** `PjsipSipService.java`, `GatewayInCallService.java`
**Depends on** GW-10 · **Conflicts with** GW-11 (`PjsipSipService.java`)

## Problem

Two independent listeners drive the same transitions with no defined ordering:

| Event | Path A — `PhoneStateListener` | Path B — `Call.Callback` |
|---|---|---|
| connected | `handlePhoneState(OFFHOOK)` `:451-455` → `startAudioStreams()` + `onGsmCallConnected()` | `onGsmCallStateChanged(STATE_ACTIVE)` `:474-494` → `cancelIncomingTimeout()` + `startAudioStreams()` + `onGsmCallConnected()` + spawn `MuteControls` |
| ended | `handlePhoneState(IDLE)` `:457-463` → `stopAudioStreams()` + `onGsmCallEnded()` | `onGsmCallStateChanged(STATE_DISCONNECTED)` `:495-501` → `onGsmCallEnded()` + `unmuteAll()` |

Neither path is idempotent, and Android gives no ordering guarantee between
`PhoneStateListener` and `InCallService` callbacks.

## Failure scenarios

- **Double start.** Both connect paths call `startAudioStreams()`. Guarded by
  `isCapturing` today, but the guard is exactly the racy one GW-08 had to fix.
- **Stale stop kills a live call's audio.** Back-to-back calls: path A's IDLE for call 1
  can arrive *after* path B's ACTIVE for call 2 → `stopAudioStreams()` tears down the
  mixer and closes the PCM of the call that just started. Symptom: silent call, no error.
- **Double mute.** Path B's connect spawns a `MuteControls` thread every time it fires.
- `lastPhoneState` (`:68`) exists only to de-duplicate path A against itself; it does
  nothing about A-vs-B.

## Required change

1. **`Call.Callback` (via `GatewayInCallService`) is the single source of truth.** It is
   the only one that carries call identity, so it is the only one that can be made
   ordering-safe. `PhoneStateListener` gives a process-wide state with no identity.
2. **Demote `PhoneStateListener`.** Either remove it entirely, or keep it strictly as a
   *cross-check*: it may log a discrepancy and (optionally) feed the watchdog (GW-25), but
   it must not call `startAudioStreams`, `stopAudioStreams`, `onGsmCallConnected` or
   `onGsmCallEnded`. Removing `handlePhoneState`'s side effects is the whole issue.
   - If it is removed entirely, also drop `telephonyManager`/`phoneStateListener`
     (`:66-67`) and the `LISTEN_NONE` teardown at `:202-204`.
   - `checkOrphanedCalls` (`:635`) currently reads `lastPhoneState`; it must switch to
     asking `GatewayInCallService` for the tracked call's state instead.
3. **Carry call identity through.** `onGsmCallStateChanged(Call, int)` already receives
   the `Call`. Pass its identity down into `CallManager` so `onGsmCallEnded` can be
   ignored when it refers to a call that is no longer current — this is what actually
   prevents the stale-stop scenario, independent of ordering.
4. **Make the transitions idempotent** regardless: a second connect for the same call is a
   no-op; an end for a non-current call is a logged no-op.

## Acceptance criteria

- [ ] Exactly one code path drives GSM connect/disconnect into `CallManager` and
      `AudioBridgeManager`.
- [ ] `PhoneStateListener` performs no state mutation (or is deleted).
- [ ] GSM lifecycle events carry call identity; events for a non-current call are ignored
      and logged.
- [ ] Connect and end handlers are idempotent.
- [ ] `checkOrphanedCalls` no longer depends on `lastPhoneState`.
- [ ] Exactly one `DeviceMuteManager` lease acquisition per call (with GW-02 landed).

## Verification

1. On-device, back-to-back calls with <1 s between hangup and redial, 20 cycles. Every
   call must have two-way audio. Today some fraction are silent — that is the regression
   this closes, so run the baseline first and record the failure rate.
2. `logcat -s GatewaySvc GsmAudioPort` — exactly one `Audio streams started` and one
   `Audio streams stopped` per call, in order, with no interleaving across calls.
3. Confirm exactly one `Muting all controls` per call.

## Risk

Medium. `PhoneStateListener` may be catching a case `InCallService` misses on this
hardware (e.g. a call the dialler role transition drops). Before deleting it, run a
logging-only build for a day that reports every discrepancy between the two sources; if
there are none, delete with confidence. If there are, keep it as a cross-check that feeds
the watchdog rather than the state machine.
