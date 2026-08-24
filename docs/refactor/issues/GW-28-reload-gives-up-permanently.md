# GW-28 — A reload with no endpoint stops the gateway permanently

**Phase** 3 · **Severity** P2 · **Closes** AUDIT H14
**Files** `PjsipSipService.java`
**Found by** GW-26 (Phase 2 wave 1) · **Conflicts with** GW-21, GW-25 (same file)

## Problem

`PjsipSipService.doReloadConfig` step 0:

```java
if (!endpointManager.isInitialized()) {
    Log.w(TAG, "Endpoint not initialized, cannot reload - stopping service");
    mainHandler.post(() -> stop(false));
    return;
}
```

Until GW-26 the comment on that `stop()` read *"Service will be restarted by system due to
START_STICKY"*. That is false. `START_STICKY` governs what the system does after **it** kills
a service; a service that ends through `stopSelf()` is not restarted. So this branch takes the
gateway down and leaves it down.

It is reachable in the case that matters most: a config reload arriving while SIP init has
failed and left `endpoint == null` — which is exactly the state a reload might be trying to
repair. The operator sees "saved" in the web UI or the app, and the gateway silently ends.

Whether anything brings it back is incidental: `GatewayInCallService.onCreate` will, but only
if the app is the default dialler *and* Telecom happens to bind afterwards; `BootReceiver`
will, at the next reboot. Neither is a design.

## What GW-26 already did (do not redo)

- The branch now calls `stop(false)`, the internal form, so it does **not** latch the
  persisted user-stop flag introduced in GW-26 §5. Without that, this path would additionally
  suppress `GatewayInCallService`'s restart and the sticky restart — strictly worse.
- The false comment is gone and points at AUDIT H14.

The remaining defect — the gateway stops and nothing is guaranteed to start it — is untouched.

## Required change

Pick one and say why in the commit body:

1. **Re-initialise instead of stopping** (preferred). The endpoint is `static` and outlives
   the service, so there is nothing to restart the *process* for: call `initializeSip()` on the
   control thread, which is already where `doReloadConfig` runs, and let its own failure path
   (`reconnection.scheduleReconnect()`) handle a second failure. Note `initializeSip` takes a
   `LifecycleCancellation.Token` — reuse the same mechanism, do not bypass it.
2. **Restart the process honestly.** `restartProcess()` exists, is used for the TLS-changed
   case two steps below, and does what the old comment claimed. Heavier, but truthful.

Do **not** simply return `START_STICKY` harder; the service is not being killed.

## Acceptance criteria

- [ ] A reload issued while `endpoint == null` leaves the gateway either running or
      deterministically restarted — never stopped with nothing to bring it back.
- [ ] The persisted user-stop latch (GW-26 §5) is still untouched by this path: an internal
      give-up must not look like a human pressing stop.
- [ ] `reloadInProgress` is still cleared on the new path. (It is today: the early `return`
      sits inside the `try`, so the `finally` runs. Do not move the branch above it.)

## Verification

1. Force the state: block SIP init (wrong server, no network), confirm `endpoint == null` in
   the log, then POST a config change from the web UI.
2. `adb shell dumpsys activity services org.onetwoone.gateway` — the service must still be
   listed.
3. Confirm `adb shell am broadcast -p org.onetwoone.gateway -a org.onetwoone.gateway.START`
   still works afterwards, and that the user-stop latch was never written.

## Risk

Low. The path is already a failure path; the change is about which failure it produces.
Option 1 can loop if init keeps failing — rely on `ReconnectionStrategy`'s backoff rather than
adding a second retry of your own.
