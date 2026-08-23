# GW-26 — Service lifecycle: blocking shutdown on main, unguarded teardown, restart interplay

**Phase** 2 · **Severity** P1 · **Closes** AUDIT G2, H8
**Files** `PjsipSipService.java`, `GatewayInCallService.java`, `WebConfigServer.java`
**Depends on** GW-10 · **Conflicts with** GW-10, GW-14, GW-15

## Problem

**G2 — `onDestroy` blocks the main thread.** `PjsipSipService.onDestroy` (`:177`) runs
`shutdownSip()` (`:196` → `:257`) inline on main:
- `audioBridge.stopBridge()` + `stopAudioStreams()` — the latter joins the open worker for
  up to 1 s and shells out for `teardownMixer`.
- `accountManager.deleteAccount()` (`:266`) — `setRegistration(false)` sends an
  un-REGISTER and `account.delete()` waits on it: network I/O.
- `endpointManager.shutdown()` (`:270`) → `hangupAllCalls()`.

Android allows ~10 s before an ANR on service destroy; this path can approach it, and it
delays the `START_STICKY` restart.

**H8 — unguarded teardown.** `onDestroy` calls `watchdog.stop()`,
`reconnection.setEnabled(false)`, `reconnection.cancel()`, `audioBridge.stopBridge()` and
`powerController.release()` unconditionally (`:185-199`). If `onCreate` threw after
`instance = this` (`:102`) — e.g. `GatewayConfig.init` failing at `:107`, or
`initializeManagers()` at `:111` failing partway — every one of those is an NPE **inside
`onDestroy`**, which Android escalates.

`onDestroy` also calls `super.onDestroy()` **first** (`:178`) and sets `instance = null`
(`:182`) before teardown completes — so another thread can observe a null instance while
the managers are still live, or (worse) a live instance whose managers are half torn down.

**Restart interplay.** `stop()` (`:730`) sets `stopRequested` and calls `stopSelf()`, but
the service is `START_STICKY` (`:173`) and `onCreate` resets `stopRequested = false`
(`:103`). A user-requested stop is therefore undone by the system restart. Meanwhile
`GatewayInCallService.onCreate` (`:67-75`) *starts* the SIP service whenever it binds — so
stopping the gateway while it is the default dialler immediately restarts it.

**Minor:** `stopWebServer()` (`:886`) nulls `webServer` from main while a NanoHTTPD worker
may be inside `serve()` (`WebConfigServer.java:54`).

## Required change

1. **Move shutdown off main.** `onDestroy` posts the teardown to the control thread and
   waits with a bounded join (suggest 3 s), then quits the thread. If the join expires,
   log an error and proceed — never block past the ANR budget.
2. **Guard every teardown step.** Null-check each manager, or better: track initialisation
   completion with a single flag set at the end of `initializeManagers()` and skip
   teardown of anything not initialised. Wrap each step in its own try/catch so one
   failure cannot skip the rest — in particular `powerController.release()` must run even
   if the SIP shutdown throws, or the wake lock leaks.
3. **Order the visibility change correctly.** Set `instance = null` **first** (so no new
   work arrives), then tear down, then `super.onDestroy()`. Today it is the reverse.
4. **Make `START_STICKY` deliberate.** Return `START_NOT_STICKY` when the last stop was
   user-requested, or persist `stopRequested` so `onCreate` does not clear an intentional
   stop. Decide explicitly — the current behaviour makes the `STOP` broadcast
   (`GatewayControlReceiver.java:131`) unreliable, which matters because that is the
   documented remote-control API.
5. **Don't auto-start from `GatewayInCallService.onCreate`** when the user has explicitly
   stopped the gateway. Check the persisted stop flag before the `startForegroundService`
   at `:69-74`.
6. **Shut the web server down safely.** `stop()` NanoHTTPD before nulling the reference,
   and null it only after `stop()` returns.
7. **Fix `onStartCommand` idempotence.** `isRunning` (`:71`, `:156`) gates initialisation,
   but `startForegroundNotification()` (`:154`) runs on every start command — harmless,
   but the flag should be checked coherently on one thread (it is written on main, read
   from `attemptReconnect` at `:276`).

## Acceptance criteria

- [ ] `onDestroy` never performs network or root I/O on the main thread; total main-thread
      time is bounded and logged.
- [ ] A service whose `onCreate` failed partway can be destroyed without NPE.
- [ ] `powerController.release()` runs on every teardown path, including exceptional ones.
- [ ] `instance` is nulled before teardown begins.
- [ ] A user-requested stop survives `START_STICKY` and the InCallService auto-start.
- [ ] `stopWebServer()` cannot null the reference while a request is in flight.

## Verification

1. Time `onDestroy`: add a duration log, then stop the service 20× and confirm the
   main-thread portion stays under ~200 ms and the total under the bounded join.
2. Fault-inject an `onCreate` failure (temporarily throw from `initializeManagers`) and
   confirm the service destroys cleanly with no NPE and no leaked wake lock:
   ```
   adb shell dumpsys power | grep -A5 'Wake Locks'
   ```
   `Gateway::CpuWakeLock` must not be held after stop.
3. Send the `STOP` broadcast while the app is the default dialler; confirm the service
   stays stopped (today it restarts):
   ```
   adb shell am broadcast -p org.onetwoone.gateway -a org.onetwoone.gateway.STOP
   adb shell dumpsys activity services org.onetwoone.gateway
   ```
4. Confirm `START` after an explicit stop still works.

## Risk

Medium. §4 and §5 change the restart semantics of a service whose whole purpose is to stay
up. Getting it wrong in the other direction — a gateway that does *not* come back after a
crash — is worse than the current bug. Only suppress the restart for an **explicit** user
stop; every other path must keep restarting.
