# GW-46 — SMS is invisible, and it cannot be published without moving a thread first

**Phase** 4 (deferred; not in any wave yet) · **Severity** P2 (observability) · **Closes** AUDIT H17
**Files** `SmsHandler.java`, `PjsipSipService.java`, `core/GatewayStatus.java`
**Depends on** GW-45 (the surface it would publish through — already landed)
**Conflicts with** nothing currently assigned; `SmsHandler.java` was last touched by GW-21 and GW-27

> **Severity note, added after review with the Phase 2 session.** The main-thread callback is
> **latent, not a live race.** The sole production implementation of `onSmsSendStatus` is
> `PjsipSipService:1290` — one `Log.d`, no shared state. Nothing is read or written
> off-thread today; it becomes a real threading problem the moment anything *publishes* the
> outcome, which is exactly what this issue does. So the `Handler` change below is a
> **prerequisite of the publishing work and lands with it**, not a precondition that blocks
> anything else in the meantime.
>
> **And the callback is invoked from two different threads**, which neither of us had noticed
> at first: `SmsHandler:1127` (the synchronous `catch` in the send path) runs on the **control
> thread**, while `:1210`, `:1224` and `:1229` (the sent/delivered receivers) run on **main**.
> An implementer therefore has no single thread to reason about, and which one they get
> depends on whether the failure was synchronous. That is worse than "always main": testing
> the route you can most easily trigger — the synchronous `catch` — tells you the callback is
> control-thread-confined, and it is not.
>
> **`assertOnControlThread` is the correct detector.** An earlier revision of this note said
> it would fire on the working path; that was inverted. `GatewayControlThread:192` returns
> silently only when `isCurrent()`, and otherwise throws in debug / `Log.e`s in release — so
> it passes at `:1127` and fires at the three receiver sites, which is a true positive on the
> broken path.
>
> **Fix in the order Handler → assertion → counters.** Not because the assertion would hide
> anything, but because installing it first would crash debug builds on *every* SMS send
> verdict, converting a latent constraint into an immediate hard failure on merlinx.
>
> Until the `Handler` lands, **no implementation of this interface may touch shared state** —
> the current `Log.d` is conformant by accident, not by design. That constraint is unenforced
> by choice; the detector is available whenever it is wanted.

## Problem

Phase 4 plan §C9: nothing in either UI mentions SMS. It is half the gateway's job,
`SmsHandler` is 1262 lines, and GW-27 just fixed a user-visible SMS bug — yet neither the app
nor the web page shows a count, a last-forwarded time, or a failure.

That is not a rendering gap. `GatewayStatus` carries no SMS fields, so **the data is not
published**, and since GW-45 the snapshot is the UI's only status surface.

GW-45's brief set the line explicitly: add SMS counters to the snapshot *only if* they can be
read from state `SmsHandler` already maintains, on the thread that already owns it, with no
new locking and no new cross-thread read. **That was checked, and it is not true.** Below is
what was found, so nobody has to check it twice.

### 1. There are no message-level tallies to read

Everything `SmsHandler` keeps is `@ControlThread` — the annotation is real and
`control.assertOnControlThread(...)` backs it, which is GW-21's doing. The problem is *what*
it keeps:

| Field | Line | What it actually is |
|---|---|---|
| `confirmedIds` | `:221` | The **pruned** duplicate-suppression record. TTL 30 days (`:147`), hard cap 1000 (`:150`), `pruneProcessedIds()` at `:987` drops by age then by size. |
| `inFlightIds` | `:230` | Gauge: messages mid-forward right now. |
| `forwardAttempts` | `:234` | Gauge: ids with at least one failed attempt outstanding. |
| `retryNotBefore` | `:238` | Backoff deadlines. |
| `rootFlagFailures` / `rootFlagWriteGivenUp` / `readFlagWriteEnabled` | `:242` `:246` `:255` | Health of the `read=1` flag write. |
| `processInboxCounter` | `:456` | Counts **scans**, not messages — a log trace id. |

`confirmedIds.size()` is the one that looks like "SMS forwarded" and is not. It is a retention
window: it is correct as a count only until the TTL or the 1000-cap bites, and then it silently
starts shrinking while the real number keeps climbing. Publishing it under an SMS-count label
would be a number that lies on exactly the long-running 24/7 deployment this gateway is for.

**No lifetime counter of forwarded, failed, or sent messages exists anywhere in the class.**

### 2. The outbound half arrives on the main thread

The *submission* side is fine: `PjsipSipService.handleIncomingSipMessage` is `@ControlThread`
and calls `smsHandler.sendSms(...)` from there (`PjsipSipService.java:1419`).

The **outcome** is not. `registerSendReceivers()` registers both status receivers with no
handler argument:

```java
context.registerReceiver(smsSentReceiver, new IntentFilter(ACTION_SMS_SENT));       // :1243
context.registerReceiver(smsDeliveredReceiver, new IntentFilter(ACTION_SMS_DELIVERED));
```

so `onReceive` — and with it every `sent` / `failed` / `delivered` / `delivery_failed`
verdict — runs on **main**. This is residual GW-21: that issue moved the inbound pipeline onto
the control thread by giving the `ContentObserver` `new Handler(control.getLooper())`
(`:355`), and did not do the same for these two.

Counting outbound outcomes therefore requires a new `AtomicLong`/`volatile`, or a new
`control.post` from main — a new cross-thread hand-off, in a codebase that has spent three
phases removing them. That is precisely what GW-45's rule excludes.

**Conclusion:** publishing anything a status screen would actually want means (a) adding new
counter fields and (b) inventing a cross-thread publication for half of them. That is not
"read state that already exists on the thread that already owns it", so GW-45 correctly did
nothing and filed this instead.

## Failure scenario

Not a crash — an operator one. A gateway deployed in a cupboard forwards SMS to the PBX and
sends SMS out of it. Today the only way to know whether that half of the appliance is working
is `adb logcat`. GW-27's bug (the whole inbox re-forwarded on every restart) was **reported
from the field**, by a person who noticed duplicate messages, because the device itself had
nothing to show. A `read`-flag write that has silently given up (`rootFlagWriteGivenUp`, a
real state this class reaches and logs) leaves duplicate suppression resting entirely on the
persisted set, and nothing surfaces that either.

## Required change

Ordered, because step 2 is what makes step 3 legal.

1. **Add lifetime counters where control-thread code already runs.** At minimum:
   messages forwarded to the PBX, forwards abandoned after `MAX_FORWARD_ATTEMPTS`, and the
   wall-clock instant of the last successful forward. Written only from existing
   `@ControlThread` methods (`forward`, `markAsRead`, the give-up branch). Plain `long`s —
   confinement, not atomics.

2. **Move the send-outcome receivers onto the control looper**, finishing GW-21:

   ```java
   context.registerReceiver(smsSentReceiver, filter, null, new Handler(control.getLooper()));
   ```

   Fix the thread; do not add a lock. This makes the outbound counters control-thread state
   like everything else, instead of buying an `AtomicLong` to paper over the split.
   **Verify on-device:** the receivers read `getResultCode()`, which is ordered-broadcast
   state on the receiver, not on the looper — dispatching to a non-main handler is supported
   and should not change it, but this is the outbound SMS path and it is user-visible, so it
   is a device check, not a reasoning check.

3. **Publish an `SmsCounters` value object on `GatewayStatus`**, built in `publishStatus()`
   exactly the way `WatchdogFindings` is: read off the handler on the control thread and
   *passed into* `capture()` rather than pulled out of a manager inside it. `smsHandler` is
   already `volatile` on the service (`PjsipSipService.java:99`) and already read from the
   control thread, so reaching it from `publishStatus()` is not itself new. Flatten it into
   `toBundle()` for `GET_STATUS` with everything else. Nothing time-derived may be frozen —
   carry the raw instant of the last forward, derive "how long ago" on read.

4. **Render it.** No further plumbing: since GW-45 the UI observes
   `LiveData<GatewayStatus>`, so anything added to the snapshot is reachable by the screen the
   moment it lands.

## Acceptance criteria

- No new lock, no new atomic, and no new `volatile` in `SmsHandler` for the counters. If one
  seems necessary, step 2 was skipped.
- `SmsHandler`'s counter fields carry `@ControlThread` and every write is in a method that
  already asserts it.
- A unit test that drives a forward and a give-up through the control thread and reads the
  counters off a captured `GatewayStatus` — the `GatewayStatusTest` pattern.
- A unit test that the last-forward instant is carried raw and its age derived on read
  (PHASE-2-PLAN §2.7 trap 1).
- `confirmedIds.size()` is **not** published as a message count anywhere.
- `./gradlew test` and `./gradlew lintDebug` green, baseline not regenerated.

## Verification

JVM/Robolectric for the counters and the snapshot. The receiver-looper move needs a phone:
send an SMS from the PBX through the gateway and confirm the `sent` and `delivered` callbacks
still fire, with the right result codes, on the control thread — and that a failed send (radio
off) still reports `failed` rather than being lost.

## Risk

Step 2 touches the live outbound SMS path, which is user-visible and has no instrumented test.
Steps 1, 3 and 4 are additive and low risk. If step 2 turns out to misbehave on-device, the
honest fallback is to publish the **inbound** counters only and leave outbound unpublished —
not to reach for an atomic.
