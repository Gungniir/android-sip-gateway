# GW-21 — SMS pipeline blocks the main thread; retry bookkeeping can double-send

**Phase** 2 · **Severity** P1 (ANR) · **Closes** AUDIT G1
**Files** `SmsHandler.java`, `PjsipSipService.java`
**Depends on** GW-10 · **Conflicts with** GW-10, GW-14 (`PjsipSipService.java`)

## Problem

The whole inbound-SMS path runs on the main thread:

1. `ContentObserver` is constructed with `mainHandler` (`SmsHandler.java:97`), so
   `onChange` (`:99`) fires on main.
2. `processInbox()` (`:149`) does a `ContentResolver.query` over `content://sms/inbox`
   inline (`:159`) — disk I/O through a cross-process content provider.
3. For each row it calls back synchronously into
   `PjsipSipService.handleIncomingGsmSms` (`:521`) → `sendSipMessage` (`:543`), which
   creates a `Buddy` and calls `buddy.sendInstantMessage(prm)` (`:581`) — **network I/O,
   still on main**.
4. Then `markAsRead(smsId)` (`:254`), which may fall back to
   `markAsReadWithRoot` → `sqlite3` via `su` (`:293`) — another process spawn on main.

A burst of SMS therefore blocks the UI thread for seconds. It also blocks the very thread
that services Telecom callbacks, so an SMS burst during a call delays call teardown.

Secondary issues:
- `processedSmsIds` (`:56`, plain `HashSet`) is touched from `processInbox` (main) and
  from `unprocessSms`/`deleteSms` — safe today only because everything is on main, which
  this issue changes. It must move with the work.
- `unprocessSms` (`:282`) removes the id so the SMS can be retried, and even logs a
  stack trace warning about re-send. Combined with the observer firing again on the
  provider's own `read` update, a failed send can be retried immediately and repeatedly
  with no backoff.
- `processInboxCounter` (`:143`) is a plain `static int`.

## Required change

1. **Move the pipeline to the control thread.** Construct the `ContentObserver` with the
   control thread's `Handler`, so `onChange` → `processInbox` → send → `markAsRead` all
   run there. Nothing in this path needs the main thread.
2. **Keep `processedSmsIds` with the work.** Once single-threaded on the control thread,
   the plain `HashSet` is correct — assert the thread rather than adding a lock. Same for
   `processInboxCounter` (make it an instance field while you are there).
3. **Debounce the observer.** The comment at `:102` says "no debounce, race with
   MessagingApp" — but `markAsRead` itself mutates the provider and re-triggers
   `onChange`, so every send causes a redundant full inbox scan. Coalesce with a short
   `postDelayed` (e.g. 250 ms) that collapses bursts, keeping the `processedSmsIds` guard
   as the correctness mechanism.
4. **Bound the retry.** Give each failed SMS an attempt count and a backoff before
   `unprocessSms` makes it eligible again. Cap attempts (suggest 5) and then
   `markAsRead` with an error log — an SMS that can never be forwarded must not spin
   forever.
5. **Preserve the registration-gated behaviour.** `handleIncomingGsmSms` (`:524-529`)
   deliberately un-processes when not registered so the message is retried after
   registration, driven from `onRegistrationState` (`PjsipSipService.java:309-312`).
   Keep that; just make it go through the new backoff so it cannot hot-loop.
6. **`Buddy` lifetime.** `sendSipMessage` creates a `Buddy` per message and deletes it in
   `finally` (`:591-596`) — correct, keep it. But it captures the account at `:547`; with
   GW-14 landed, re-check the account is still current immediately before
   `buddy.create()`.

## Acceptance criteria

- [ ] No SMS work runs on the main thread; the control-thread assertion covers
      `processInbox`, `sendSipMessage`, `markAsRead`.
- [ ] `processedSmsIds` and the trace counter are single-threaded and asserted.
- [ ] Observer bursts are coalesced; `markAsRead` no longer causes a redundant full scan.
- [ ] A permanently failing SMS is retried a bounded number of times with backoff, then
      marked read with an error.
- [ ] Registration-gated retry still works.
- [ ] Outbound (SIP→GSM) path in `sendSms` (`:328`) unaffected.

## Verification

1. On-device: send 20 SMS to the gateway SIM in a burst. Expect all 20 forwarded exactly
   once (check the PBX / receiving extension), and no `Skipped … frames` in logcat.
2. With the PBX unreachable, send one SMS. Expect bounded retries with visible backoff in
   `logcat -s SmsHandler`, then a give-up line — not an infinite loop.
3. Restore the PBX before the cap and confirm the SMS is delivered on a retry.
4. Send an SMS while a call is bridged and confirm the call audio is unaffected and
   teardown is not delayed.

## Risk

Low-medium. The duplicate-send guard is the delicate part — `processedSmsIds` is the only
thing preventing double delivery, and the retry changes touch it directly. Test the
"PBX down, then up" path explicitly.

---

## Known residual after merge — the send-status receivers still land on main

Found by the Phase 4 UI session and confirmed here at `d94363e`. **GW-21 moved the inbound
pipeline onto the control thread but not the outbound verdict.**

`SmsHandler.registerSendReceivers()` registers both receivers with the two-argument
`registerReceiver(receiver, filter)` (and the three-argument `RECEIVER_NOT_EXPORTED` form on
API 33+), neither of which takes a `Handler` — so `onReceive` runs on **main**, and
`callback.onSmsSendStatus(...)` is invoked from there.

**Severity, stated precisely:** this is *not* a live race today. The only implementation of
`onSmsSendStatus` (`PjsipSipService.initSmsHandler`) is a single `Log.d` and touches no
shared state, so nothing is currently read or written off-thread. It is a **latent**
constraint: it becomes a real threading problem the moment anything publishes the send
outcome — a status field, a snapshot, a UI update — which is precisely what the Phase 4
status-surface work wants to do.

Same family as **F4b** (a residual main-thread touch in a path the refactor otherwise owns).

**It is not simply "the verdict arrives on main" — the callback has no single thread.**
Verified: `onSmsSendStatus` has four invocation sites, on two different threads depending on
whether the failure was synchronous or asynchronous.

| Site | Path | Thread |
|---|---|---|
| `SmsHandler:1127` | the `catch` in `sendSms`, reached from `handleIncomingSipMessage` | **control** |
| `SmsHandler:1210`, `:1224`, `:1229` | the sent / delivered receivers | **main** |

**Ordering of the fix: `Handler` first, assertion second.** Passing a control-thread
`Handler` to both `registerReceiver` calls collapses all four sites onto one thread, which is
what makes an assertion meaningful. Adding `assertOnControlThread` *before* the `Handler`
would not give a false green — `assertOnControlThread` returns silently only when already on
the control thread, so it would pass at `:1127` and **throw in debug / `Log.e` in release** at
the three receiver sites. That is a true positive, and a loud one: it would crash debug builds
on every SMS send verdict. So the assertion is the right detector, but installing it first
converts a latent constraint into an immediate debug crash rather than exposing a hidden bug.

Filed by the UI session as AUDIT **H17**, with issue **GW-46** (deferred). Not renumbered
here and not duplicated: when `refactor/phase-4-ui` merges, H17 arrives with it.

