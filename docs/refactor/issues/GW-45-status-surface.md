# GW-45 — The UI cannot reach the status it is supposed to show

**Phase:** 4, wave 1
**Blocks:** GW-41 (status-first main screen)
**Filed:** 2026-08-24, during Phase 4 planning, against `refactor/phase-2` @ `b3b2c0e`
**Origin:** [PHASE-4-PLAN.md](../PHASE-4-PLAN.md) §2 C1

---

## 1. Why this issue exists

It was not in the original Phase 4 plan. That plan justified the whole phase being
presentation-only on this claim (§1):

> `MainViewModel` already exposes everything through LiveData, and Phase 1 gave it an
> immutable `GatewayStatus` snapshot. The presentation layer is genuinely separable from the
> logic today.

The second half is true. The first half is not, and GW-41 was specified on top of it.

## 2. What is actually there

`MainViewModel.updateServiceState()`, on a 1 Hz poll:

```java
GatewayStatus snapshot = pjsipService.getStatusSnapshot();
state.isRunning     = snapshot.isRunning();
state.isRegistered  = snapshot.isSipRegistered();
state.statusMessage = snapshot.getStatusText();
```

Then it publishes a `ServiceState` — a mutable POJO with three public fields — through
`LiveData`, plus `statusText` and `isRegistered` as separate `LiveData`s carrying the same
two values again.

`getStatusText()` returns:

```java
return "SIP: " + sipStatus + "\n"
     + "Call: " + callStatus + "\n"
     + "Audio: " + audioStatus;
```

Its javadoc calls it *"The three-line composite the UI has always shown."* It is a
compatibility shim for the existing screen, not a status API.

## 3. What is unreachable

`GatewayStatus` grew by 210 lines in Phase 2 — GW-22 added call counters, GW-25 added
`WatchdogFindings`. None of it reaches the UI:

| Accessor | Added by | Shows |
|---|---|---|
| `getSipStatus()` / `getCallStatus()` / `getAudioStatus()` | Phase 1 | the three legs **separately**, not glued into one String |
| `getCallState()` | Phase 1 | the state-machine state |
| `getCallDurationMs()` | Phase 1 | live call duration |
| `isInGracePeriod()` | GW-25 | GSM leg still inside its post-dial grace |
| `getCallsCreated()` / `getCallsDeleted()` / `getCallsAlive()` | GW-22 | pjsua2 `Call` object leak detection |
| `getConfigGeneration()` | GW-14 | consumed internally; never displayed |
| `WatchdogFindings.getTerminations()` | GW-25 | how many legs the watchdog killed |
| `WatchdogFindings.getSilentBridgeEpisodes()` | GW-25 | audio bridge up but carrying nothing |
| `WatchdogFindings.getLastFinding()` / `getLastFindingAtWallMs()` | GW-25 | the last thing that went wrong, and when |

The last four matter most. GW-25 built silent-bridge detection and orphan termination
specifically because those failures are invisible from the outside — and then the only place
a human looks shows none of it.

## 4. Required change

`MainViewModel` publishes `LiveData<GatewayStatus>` and derives from it, rather than
flattening on the way through.

### Constraints

1. **Keep `getServiceState()`, `getStatusText()`, `getIsRegistered()` working.**
   `MainActivity` has 11 observers and GW-41 (wave 2) is what rewrites it. Add the new
   surface, deprecate the old, let GW-41 delete it. Wave 1 must leave a working app.
2. **`getCallDurationMs()` and `isInGracePeriod()` re-read the clock on every call**, by
   design — their javadoc says a frozen duration is *"a stopwatch that never advances"*.
   Bind per-tick; never cache the derived value.
3. **`GatewayStatus.UNAVAILABLE`** is the service-not-connected value. Do not invent a
   null-state; "Service not connected" is presentation.
4. **`getTestCallReport()` stays out of the snapshot** — `PHASE-2-PLAN.md` §2.7: a
   `StringBuilder` capped at 20 000 chars, and copying it into every 1 Hz publish would make
   publish cost proportional to report length.
5. **Do not regress the config-generation poll.** `seenConfigGeneration` is what replaced
   GW-14's deleted activity relaunch, which used to discard whatever the person holding the
   phone was doing. Its four tests must keep passing.
6. **No live read of `CallManager` / `AudioBridgeManager` / `SipAccountManager`.** Need a
   field the snapshot lacks? Add it to the snapshot.

### SMS — the boundary

Neither UI mentions SMS, though `SmsHandler` is 1262 lines and GW-27 just fixed a
user-visible SMS bug. `GatewayStatus` carries no SMS fields, so this is not a rendering gap.

SMS counters may be added **only if** readable from state `SmsHandler` already maintains, on
the thread that already owns it, with no new locking and no new cross-thread read. Otherwise
file GW-46 and ship without an SMS panel. A counter is not worth a new cross-thread read in a
codebase that spent three phases removing them.

## 5. Verification

- `MainViewModelTest`'s 4 existing tests still pass (all four are about the generation poll).
- New: snapshot published verbatim; `UNAVAILABLE` on unbind; **call duration advances between
  two reads of the same snapshot object** — this is the test that proves it was not cached;
  generation poll still fires.
- `./gradlew test` and `./gradlew lintDebug` green; 361 tests at base, none regressed.

**Not verifiable without hardware:** that the published fields read correctly during a real
call on either SoC. That belongs in `PHASE-4-VALIDATION.md`.

## 6. Why this is not folded into GW-41

It is the only Phase 4 change that touches logic. Landing it alone keeps a
publication-boundary change reviewable, instead of burying it inside a 565-line layout
rewrite — the discipline that made Phase 2's diffs reviewable.
