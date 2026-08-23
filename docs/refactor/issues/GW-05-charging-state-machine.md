# GW-05 — Charging control can be left disabled → unattended gateway dies

**Phase** 0 · **Severity** P0 (safety / availability) · **Closes** AUDIT B4
**Files** `BatteryLimitService.java`
**Depends on** nothing · **Conflicts with** nothing

## Problem

`BatteryLimitService` decides charging state from three contexts and applies it from a
fresh thread per decision:

```java
private void startCharging(boolean enable) {
    new Thread(() -> setCharging(enable), "SetCharging").start();   // :493
}
private synchronized void setCharging(boolean enable) { ... }        // :496
```

The enforce runnable fires every 5 s (`ENFORCE_INTERVAL_MS`, `:268`) and itself spawns a
thread (`:198`); the battery `BroadcastReceiver` runs on main; the init path runs on its
own background thread (`:112`).

Three defects:

1. **Ordering.** `setCharging` is `synchronized`, so calls serialise — but in *arrival*
   order at the monitor, not decision order. Two threads created 5 s apart can enter in
   either order. A stale `setCharging(false)` applied after a fresh `setCharging(true)`
   leaves charging off while the battery is below the limit.
2. **`activeChargingPaths` (`:54`, plain `ArrayList`)** is populated on the init thread
   (`findChargingPaths()` inside the `:112` thread) and iterated by every `SetCharging`
   thread and by the enforce runnable (`:200`). Unsynchronized `ArrayList` published
   without a barrier — readers may see it empty (the enforce path at `:200` then
   silently does nothing) or mid-growth.
3. **`chargingDisabled` (`:53`)** is written from the receiver (main), the init thread and
   `SetCharging` threads. Non-volatile, so the hysteresis logic at `:244-252` reads stale
   values and can latch into the wrong branch.

Plus: one thread every 5 s, forever, is needless churn on a device meant to run for weeks.

## Failure scenario

Battery is at 61 %, limit 60 %.
1. Enforce tick T0: decides disable → spawns thread A.
2. Battery drops to 59 % → receiver on main decides enable → spawns thread B.
3. B wins the monitor, writes `input_suspend = 0` (charging on), sets
   `chargingDisabled = false`.
4. A then enters, writes `input_suspend = 1` (charging off), sets
   `chargingDisabled = true`.
5. Next tick reads `chargingDisabled == true`, `percent(59) < chargeLimit(60)` →
   `setCharging(true)`… **if** it observes the write. If `chargingDisabled` is stale in
   that thread's cache, the correction never fires.

The device stops charging while plugged in and eventually powers off. There is a
`CRITICAL_BATTERY_LEVEL` guard (`:50`), but it lives in the same racy path.

## Required change

1. **One thread, one queue.** Replace the per-call `new Thread` with a single
   `HandlerThread` (`BatteryControl`) owned by the service. All decisions and all sysfs
   writes post onto it, so decisions apply in the order they were made. Remove
   `synchronized` from `setCharging` — it becomes unnecessary and misleading.
2. **Coalesce.** Post the *desired state*, not an action: keep a
   `volatile boolean desiredChargingEnabled` and a single "reconcile" runnable. Before
   writing sysfs, re-read the desired state — a decision superseded while queued is then
   skipped rather than applied late.
3. **Publish `activeChargingPaths` safely**: build it fully on the init thread, then
   assign once to a `volatile List<String[]>` holding an unmodifiable copy.
4. **Make `chargingDisabled` volatile** (or fold it into the reconcile state).
5. **Fail-safe on teardown**: `onDestroy` must force-enable charging. A service that is
   being killed must never leave the device unable to charge. Verify the existing
   `:333` path does this on every exit route, including `stopSelf()` after a foreground
   start failure (`:95-99`).
6. **Fail-safe deadline**: if charging has been disabled continuously for longer than
   `MAX_DISABLE_MS` (suggest 12 h), force-enable and log an error — the backstop for any
   interleaving not anticipated here.

Do not change `CHARGING_PATHS`, `CRITICAL_BATTERY_LEVEL`, `DEEP_DISCHARGE_LEVEL`, the
hysteresis constant, or the enforce interval — those are device-tuned.

## Acceptance criteria

- [ ] Exactly one thread writes charging sysfs nodes; no `new Thread` per decision.
- [ ] A superseded decision is dropped, not applied late.
- [ ] `activeChargingPaths` is immutable and safely published; `chargingDisabled` is
      `volatile` or removed in favour of the reconcile state.
- [ ] `onDestroy` force-enables charging on **every** exit path.
- [ ] Charging cannot remain disabled longer than `MAX_DISABLE_MS`.

## Verification

1. On-device, plugged in, limit set to a value just below the current level:
   ```
   adb shell cat /sys/class/power_supply/battery/input_suspend
   ```
   Toggle the limit above and below the current level repeatedly (10×, ~2 s apart) and
   confirm the node always settles to the value matching the *last* decision within one
   enforce interval.
2. Kill the service (`am force-stop` / `STOP` broadcast) while charging is disabled and
   confirm `input_suspend` returns to `0`.
3. Leave the device charging overnight with a 60 % limit; confirm the level oscillates
   in the hysteresis band and never falls below `CRITICAL_BATTERY_LEVEL`.

## Risk

Medium — this code can physically strand the device. Test the `onDestroy` force-enable
path first, before touching the decision logic, so there is always an escape hatch.
