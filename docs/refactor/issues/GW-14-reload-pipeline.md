# GW-14 — Replace sleep-based `reloadConfig` with a sequenced pipeline

**Phase** 1 · **Severity** P1 · **Closes** AUDIT F5, F4
**Files** `PjsipSipService.java`, `sip/SipAccountManager.java`
**Depends on** GW-10 · **Conflicts with** GW-10, GW-15 (`PjsipSipService.java`)

## Problem

`doReloadConfig` (`PjsipSipService.java:752`) runs on a bare `ConfigReload` thread and
synchronises with the main thread by sleeping:

```java
mainHandler.post(() -> callManager.terminateAllCalls());   // :782  fire and forget
Thread.sleep(100);                                          // :783  hope it finished
audioBridge.stopBridge();                                   // :786
audioBridge.stopAudioStreams();                             // :787
accountManager.deleteAccount();                             // :790
Thread.sleep(500);                                          // :793  "small delay for cleanup"
...
accountManager.createAccount(PjsipSipService.this);          // :807
```

If the main thread is busy for >100 ms — which it routinely is, given G1/G3 — the hangup
has not happened when `deleteAccount()` runs. The account is then deleted with live calls
attached.

**F4** compounds it: `deleteAccount` (`SipAccountManager.java:142`) sets `account = null`
on this thread while `sendSipMessage` (`PjsipSipService.java:543`) has already captured
the reference at `:547` and calls `buddy.create(account, …)` at `:565` on a deleted native
object.

`reloadInProgress` (`:750`) is `volatile` but is set on main and cleared in the worker's
`finally`, so two reloads posted in quick succession can both observe `false`.

There is a second, quieter defect: the reload ends by restarting `MainActivity` with
`CLEAR_TASK` (`:813-822`) as a way to "refresh UI" — a config save from the web interface
therefore yanks the foreground activity out from under whoever is using the phone.

## Required change

Express the reload as an ordered sequence of steps on the control thread, where each step
runs only after the previous one has actually completed.

```java
@ControlThread
private void doReloadConfig() {
    if (reloadInProgress) { log; return; }        // now a plain field — one thread owns it
    reloadInProgress = true;
    try {
        if (!endpointManager.isInitialized()) { requestServiceRestart(); return; }
        callManager.terminateAllCalls();          // same thread — completes before returning
        audioBridge.stopBridge(generation);
        audioBridge.stopAudioStreams();
        accountManager.deleteAccount();
        if (endpointManager.needsRecreation()) { restartProcess(); return; }
        accountManager.createAccount(this);
    } finally {
        reloadInProgress = false;
    }
}
```

Both `Thread.sleep` calls disappear because the operations are now genuinely sequential.

Additionally:

1. **Guard the account reference.** `sendSipMessage` and anything else that uses
   `accountManager.getAccount()` must run on the control thread too, so the account cannot
   be deleted mid-use. Snapshot the reference into a local at the top and re-check it is
   still current before the native call.
2. **`registerThread("ConfigReload")` (`:775`) goes away** — the control thread is
   registered once at construction (GW-10).
3. **Drop the `MainActivity` restart** (`:813-822`). The UI should observe the status
   snapshot (GW-10 §5) and update itself. If a nudge is genuinely needed, send a
   `LocalBroadcast`/`LiveData` update, not `FLAG_ACTIVITY_CLEAR_TASK`.
4. **Keep the TLS-change process restart** (`:796-804`). PJSIP genuinely cannot recreate an
   endpoint at runtime; that is correct and must survive.

## Acceptance criteria

- [ ] No `Thread.sleep` remains in the reload path.
- [ ] Every reload step completes before the next begins, by construction, not by timing.
- [ ] `deleteAccount` cannot run while a `Buddy`/`Call` created from that account is in
      flight.
- [ ] Two reloads posted back-to-back result in one reload, then the other — never
      interleaved.
- [ ] `MainActivity` is not force-restarted on config save.
- [ ] The TLS-change → process-restart path is unchanged.

## Verification

1. On-device: POST a config change to the web interface **while a call is bridged**.
   Expect: call torn down cleanly, account re-registered, no native crash, no orphan
   conference links (check with a diagnostic test call afterwards).
2. Fire two config POSTs 50 ms apart. Expect exactly two sequential reloads in the log,
   no interleaving, and a registered account at the end.
3. Toggle TLS on/off via the web interface and confirm the process restart still works and
   the gateway re-registers.
4. Confirm the foreground app is no longer replaced by `MainActivity` on save.

## Risk

Medium. The reload path is load-bearing for the web interface. The behaviour change in
§3 (no activity restart) is deliberate — verify the UI actually reflects the new config
without it, or the change trades one annoyance for a worse one.
