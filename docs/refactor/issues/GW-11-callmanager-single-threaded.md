# GW-11 — Make `CallManager` a single-threaded state machine with an explicit transition table

**Phase** 1 · **Severity** P1 · **Closes** AUDIT D1, D4
**Files** `call/CallManager.java`, `diag/SipTestCallManager.java`, `PjsipSipService.java`
**Depends on** GW-10 · **Conflicts with** GW-13 (`PjsipSipService.java`)

## Problem

**D1.** `CallManager`'s state (`:47-50`, `:62`) is unguarded and written from main, pjsua
workers and (indirectly) `ConfigReload`. Only `hangupSipCall()` (`:439`) is
`synchronized`; `terminateAllCalls()` (`:468`) is not — so two concurrent terminations
each set `TERMINATING`, each call `hangupSipCall`, each fire `onCallsTerminated()` (→ two
concurrent `stopBridge()`, see GW-12), and each reset to `IDLE`.

The transitions are also implicit: `state` is assigned at eight sites with no rule about
which transitions are legal. `onIncomingGsmCall` (`:425`) even reuses `SIP_INCOMING` for
an *outgoing* SIP call, with a comment apologising for it.

**D4.** `SipTestCallManager.owns()` (`:141`) is consulted from pjsua workers to decide
whether a call belongs to the diagnostic path or the gateway path
(`PjsipSipService.java:396`, `:406`, `:425`). A stale read routes a real call into the
test handler — which deliberately skips the GSM state machine entirely — or the reverse.
`PjsipSipService.startTestCall` (`:704`) checks `callManager.getCurrentSipCall()` on the
caller's thread then posts, so an incoming gateway call can land in the gap.

## Required change

1. **Single thread.** Every public method of `CallManager` begins with
   `control.assertOnControlThread()`. Remove `synchronized` from `hangupSipCall` — it
   becomes meaningless and misleading once the thread invariant holds.
2. **Explicit transition table.** Replace scattered `state = X` with one
   `transition(CallState from, CallState to, String reason)` that rejects (logs loudly,
   does not throw) an illegal transition. Define the legal set:
   ```
   IDLE          → SIP_INCOMING | GSM_INCOMING
   SIP_INCOMING  → SIP_ANSWERED | TERMINATING
   GSM_INCOMING  → SIP_DIALING  | TERMINATING
   SIP_ANSWERED  → GSM_DIALING  | TERMINATING
   SIP_DIALING   → BRIDGED      | TERMINATING
   GSM_DIALING   → BRIDGED      | TERMINATING
   BRIDGED       → TERMINATING
   TERMINATING   → IDLE
   ```
   Split the overloaded `SIP_INCOMING` into `SIP_INCOMING` (SIP→GSM) and `GSM_INCOMING`
   (GSM→SIP) plus `SIP_DIALING`, so `getStatusString()` (`:577`) stops lying to the UI.
3. **Idempotent termination.** `terminateAllCalls()` returns immediately if already
   `TERMINATING` or `IDLE`. Since it now runs on one thread, this is a plain check — no
   lock needed.
4. **Ownership of the diagnostic call.** Remove `owns()` from the hot path. Instead, tag
   the call at creation: give `GatewayCall` a final `Owner` enum (`GATEWAY` |
   `DIAGNOSTIC`), set in the constructor, read without synchronisation because it never
   changes. `PjsipSipService.onCallState` dispatches on `call.getOwner()`.
5. **Admission control for the test call.** `startTestCall` posts to the control thread
   and performs the "is a gateway call in progress?" check **there**, so check and start
   are on the same thread with no gap.
6. Keep `GSM_CALL_GRACE_PERIOD_MS` (`:40`) and the `isInGracePeriod()` semantics — the
   watchdog depends on them (GW-25).

## Acceptance criteria

- [ ] Every `CallManager` public method asserts the control thread; no `synchronized`
      remains in the class.
- [ ] All state changes go through `transition()`; illegal transitions are logged with
      `from`, `to` and reason, and are no-ops.
- [ ] `terminateAllCalls()` is idempotent and cannot fire `onCallsTerminated()` twice.
- [ ] `GatewayCall` carries an immutable owner tag; `SipTestCallManager.owns()` is gone
      from the dispatch path.
- [ ] Test-call admission check and start happen on the same thread with no window.
- [ ] `getStatusString()` distinguishes SIP→GSM from GSM→SIP.
- [ ] `CallManagerTest` extended to cover the transition table.

## Verification

1. `./gradlew test` — extend `CallManagerTest` with: illegal-transition rejection, double
   `terminateAllCalls()` firing one `onCallsTerminated()`, and the D2 regression from
   GW-06.
2. On-device: place a diagnostic test call, and while it is up, have the PBX send an
   inbound gateway call. Expect the gateway call to be rejected cleanly with the test call
   unaffected — and the log to name which owner each callback belonged to.
3. Debug assertions armed through the full call matrix — zero failures.

## Risk

Medium. The transition table will reject transitions the code currently performs — that
is the point, but each rejection is a behaviour change. Run the full matrix and treat
every logged rejection as a finding to resolve before merge (either the transition is
legal and the table is wrong, or the caller is wrong).
