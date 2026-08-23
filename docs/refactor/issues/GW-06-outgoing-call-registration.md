# GW-06 — Outgoing SIP call is registered after it is placed; a synchronous failure wedges the state machine

**Phase** 0 · **Severity** P1 · **Closes** AUDIT D2
**Files** `PjsipSipService.java`, `call/CallManager.java`
**Depends on** nothing · **Conflicts with** GW-02, GW-07 (`PjsipSipService.java`), GW-07 (`CallManager.java`)

## Problem

`PjsipSipService.makeSipCallWithCallerId` (`:646`):

```java
GatewayCall call = new GatewayCall(this, account);
...
call.makeCall(uri, prm);                 // :679
callManager.setOutgoingSipCall(call);    // :682  ← after
```

`Call.makeCall` can deliver `onCallState(PJSIP_INV_STATE_DISCONNECTED)` **synchronously
on the calling thread** — an immediate transport failure, a 403/404 from the PBX, or a
local pjsua rejection. That callback reaches
`CallManager.onSipCallState` (`CallManager.java:241`), which does:

```java
if (currentSipCall == call) {   // :254  — still null, so the clear is skipped
    currentSipCall = null;
}
...
if (state != CallState.IDLE) { terminateAllCalls(); }   // :262
```

`terminateAllCalls()` runs, resets state to `IDLE`, and *then* `setOutgoingSipCall(call)`
stores the **already-dead call** as `currentSipCall`.

## Failure scenario

Inbound GSM call → `onIncomingGsmCall` → `onSipCallNeeded` →
`makeSipCallWithCallerId`. The PBX rejects the INVITE immediately (wrong extension, or
the gateway trunk can't reach it — see the known "gateway trunks cannot dial feature
codes" case).

Result: `state == IDLE` but `currentSipCall != null` and disposed. The next inbound call
takes the `state != CallState.IDLE`… no — it takes the IDLE path and overwrites, so it
mostly self-heals. But `checkOrphanedCalls` (`PjsipSipService.java:630`) short-circuits on
`hasActiveCall()`, so the dead reference survives, and `startTestCall` (`:709`) refuses
every diagnostic call afterwards with "a gateway SIP call is in progress" — which is
exactly the state that makes the audio bridge undiagnosable in the field.

## Required change

1. Register the call **before** it can generate callbacks:
   ```java
   GatewayCall call = new GatewayCall(this, account);
   callManager.setOutgoingSipCall(call);
   try {
       call.makeCall(uri, prm);
   } catch (Exception e) {
       callManager.onOutgoingCallFailed(call);   // clears if still current
       throw/log;
   }
   ```
2. Add `CallManager.onOutgoingCallFailed(GatewayCall)` that clears `currentSipCall`
   **only if it is still that call** (compare-and-clear), disposes it, and drives the
   state machine to `IDLE` — so a `makeCall` that throws doesn't leave a registered
   phantom either.
3. Make `setOutgoingSipCall` refuse to overwrite a live call: if `currentSipCall != null`
   and not disposed, log an error and reject the new one rather than dropping the old
   reference on the floor (the old one would otherwise never be hung up).
4. Apply the same ordering to `SipTestCallManager.startInternal`
   (`diag/SipTestCallManager.java:206-213`) — it assigns `call` before `makeCall`, which
   is already correct; add a comment saying why so it is not "tidied" later.

## Acceptance criteria

- [ ] The call is stored before `makeCall` in every outgoing path.
- [ ] A synchronous DISCONNECTED during `makeCall` leaves `currentSipCall == null` and
      `state == IDLE`.
- [ ] A throwing `makeCall` leaves the same clean state.
- [ ] `setOutgoingSipCall` cannot silently replace a live call.
- [ ] `startTestCall` is not blocked after a failed outgoing call.

## Verification

1. JVM unit test in `CallManagerTest`: a fake `GatewayCall` whose `makeCall` invokes the
   DISCONNECTED callback inline. Assert `getCurrentSipCall() == null` and
   `getState() == IDLE` afterwards.
2. On-device: configure `sim1_destination` to a non-existent extension, place an inbound
   GSM call, then immediately try a diagnostic call:
   ```
   adb shell am broadcast -p org.onetwoone.gateway -a org.onetwoone.gateway.TEST_CALL --es mode tone
   ```
   It must be accepted (today it is refused).

## Risk

Low. Pure ordering plus a compare-and-clear.
