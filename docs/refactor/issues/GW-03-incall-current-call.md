# GW-03 — `GatewayInCallService.currentCall`: NPE on hangup, and a second call is silently orphaned

**Phase** 0 · **Severity** P0 (NPE + lost call) · **Closes** AUDIT C1, C2, C3
**Files** `GatewayInCallService.java`
**Depends on** nothing · **Conflicts with** nothing in Phase 0

## Problem

Three separate defects in one field.

**C1 — TOCTOU / visibility.** `currentCall` (`:31`) is a plain field written on the main
thread (`onCallAdded:88`, `onCallRemoved:298`) and read from pjsua worker threads via
`CallManager.hangupGsmCall() → disconnectCall()` and
`PjsipSipService.onSipCallConnected → getCurrentCall()` (`PjsipSipService.java:357-359`).

Every consumer re-reads the field instead of snapshotting it:

```java
public void disconnectCall() {
    if (currentCall != null) {                    // :326
        int state = currentCall.getState();        // :327  ← may already be null
        ...
        currentCall.disconnect();                  // :333  ← may already be null
```

Same pattern in `answerCall()` (`:307`,`:311`), `rejectCall()` (`:319`,`:321`) and the
timeout runnable (`:234`,`:236`). `playDtmfTone`/`stopDtmfTone` (`:356`,`:379`) get it
right — they snapshot to a local. Copy that.

Without `volatile`, a pjsua worker may also simply never observe the write.

**C2 — second call orphaned.** `onCallAdded` overwrites `currentCall` unconditionally.
`onCallRemoved` only clears it when the identity matches. A call-waiting leg, or a second
inbound call arriving while one is bridged, replaces the tracked call — the original is
then invisible to `disconnectCall()` and is never hung up.

**C3 — unbounded retry chain.** `makeSipCallWithRetry` (`:267`) re-posts itself every
500 ms with no attempt cap and no cancellation other than `currentCall == null`. Two
overlapping calls start two independent chains; if SIP never registers, a chain runs for
the life of the process, and each iteration allocates a fresh `Handler` (`:281`).

## Required change

1. Make `currentCall` and `instance` `volatile`. **Every** consumer snapshots to a local
   first and operates on the local — no exceptions.
2. Track calls in a `Map<Call, CallRecord>` (or at minimum refuse to overwrite):
   - `onCallAdded` on a non-null `currentCall` → log an error and **reject the new call**
     (`call.reject(false, null)`), because the gateway can bridge exactly one leg. Do not
     silently replace.
   - `onCallRemoved` clears `currentCall` when it matches, and unregisters the callback in
     all cases (it already does, `:290`).
3. Bound the retry chain: cap at `MAX_SIP_RETRIES` (suggest 40 ≈ 20 s, safely inside the
   30 s incoming timeout), reuse the single `timeoutHandler` instead of allocating a new
   `Handler` per attempt, and tag the chain with the call it belongs to so a chain for a
   removed call stops immediately.
4. `timeoutRunnable` (`:34`) is written and cancelled from main only today; keep it that
   way and assert it, or make the pair atomic.

## Acceptance criteria

- [ ] `currentCall` and `instance` are `volatile`; no method reads either field twice.
- [ ] A second `onCallAdded` while a call is tracked is rejected and logged, never
      silently swallowed.
- [ ] The SIP retry chain has a hard attempt cap, reuses one `Handler`, and stops when
      the call it was started for is gone.
- [ ] `stateToString` coverage unchanged (it is used in logs the on-device debugging
      relies on).

## Verification

1. On-device, C1: 30 cycles of hanging up from the SIP side at random offsets. Zero
   `NullPointerException` from `GatewayInCall` in logcat.
2. On-device, C2: with a call bridged, place a second call to the same SIM from another
   phone. Expect: second call rejected, first call unaffected and still bridged, log line
   naming the rejection.
3. C3: disable the SIP account on the PBX, then send an inbound GSM call. Expect exactly
   `MAX_SIP_RETRIES` retry lines then a stop, and the 30 s timeout still hangs the GSM leg
   up. Confirm no runaway `Handler` allocation (`adb shell dumpsys meminfo`).

## Risk

Low. The rejection behaviour in C2 is a deliberate behaviour change — it makes an already
broken case explicit. If the operator actually wants call-waiting support, that is a
separate feature, not this fix.
