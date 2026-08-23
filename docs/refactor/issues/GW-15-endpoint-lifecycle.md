# GW-15 — PJSIP endpoint lifecycle: creation race, thread-registration leak, main-thread init

**Phase** 1 · **Severity** P1 · **Closes** AUDIT F1, F2, F3, F6
**Files** `sip/SipEndpointManager.java`, `PjsipSipService.java`, `sip/ReconnectionStrategy.java`
**Depends on** GW-10 · **Conflicts with** GW-10, GW-14 (`PjsipSipService.java`)

## Problem

**F1 — check-then-act on a static.** `endpoint` / `endpointUseTls`
(`SipEndpointManager.java:31-32`) are static and non-volatile. `createEndpoint` (`:132`)
tests `endpoint != null` (`:136`) then constructs at `:215`. `SipInit`, a reconnect, and
`ConfigReload` can all reach it. Two threads observing `null` both call
`new Endpoint().libCreate()` — the second `libCreate` on an already-created pjsua library
aborts natively.

**F2 — thread-registration leak.** `hasTransport()` (`:78`) calls
`registerThread(Thread.currentThread().getName())` at `:85` — from inside a *query*.
Callers include NanoHTTPD workers, `ConfigReload`, and the reconnect runnable, all
short-lived. pjlib allocates a thread descriptor from the pjsua pool and never frees it;
when the thread dies, the descriptor dangles. The pool grows monotonically for the life of
the process.

The comment at `:82-84` is honest about why the call is there: unregistered callers abort
the process. That was the right emergency fix (commit `2626f5d`), but the query is the
wrong place for it.

**F3 — init on the main thread.** `attemptReconnect` (`PjsipSipService.java:275`) runs on
the main handler and calls `initializeSip()` (`:286`) when the endpoint isn't ready. That
executes `audioBridge.initialize()` (root shell-out + full mixer enumeration, `:238`) and
`accountManager.createAccount()` (network, `:241`) on main — a multi-second freeze. It
also registers the main thread with pjlib under the misleading name `"SipInit"` (`:226`).

**F4/F6 — reconnect flags.** `ReconnectionStrategy.enabled` / `pending` (`:24-25`) are
non-volatile and set from main, `SipInit` (`PjsipSipService.java:253`) and the broadcast
receiver. `pending` races → duplicate or dropped reconnects.

## Required change

1. **Serialise endpoint creation on the control thread.** `createEndpoint` asserts the
   control thread. The check-then-act becomes safe by construction. Make `endpoint` and
   `endpointUseTls` `volatile` anyway, because `getEndpoint()` is read from the RT-adjacent
   diagnostics path (`SipDiagnostics` uses `Endpoint.instance()`, which is pjsua's own
   static — leave that alone).
   - **Keep** the main-thread requirement for `new Endpoint()` itself (`:166-176`): pjsua
     auto-registers the thread that loaded the native library, which is main. So
     `createEndpointInternal` still hops to main via `createEndpointOnMainThread` (`:181`).
     That hop is now *from the control thread*, so the `latch.await(30s)` at `:197` can
     never be a self-deadlock — document that explicitly, because on the current code the
     safety of that await is accidental.
2. **Stop registering threads from a query.** Remove the `registerThread` call at `:85`.
   Instead:
   - The control thread registers once, at construction (GW-10).
   - `hasTransport()` asserts the control thread, so an unregistered caller becomes a
     detectable programming error rather than a process abort.
   - Keep `registerThread` public for the one legitimate case (registering the control
     thread), and document that it must be called at most once per thread and never for a
     short-lived one. `libIsThreadRegistered()` short-circuiting (`:368`) stays — it is
     correct and prevents descriptor churn.
3. **Move `initializeSip` off main.** It becomes a control-thread post. Delete the
   `SipInit` bare thread (`PjsipSipService.java:162`) and the
   `registerThread("SipInit")` / `registerThread("MainThread")` calls (`:226`, `:232`) —
   both are subsumed by the control thread's single registration.
4. **`ReconnectionStrategy` moves onto the control thread.** Its `Handler` is constructed
   with `Looper.getMainLooper()` (`:28`); construct it with the control thread's looper
   instead, and assert the thread in `scheduleReconnect` / `onSuccess` / `cancel` /
   `setEnabled`. The `pending` race then disappears without `volatile`.
   Same for `ServiceWatchdog` (`sip/ServiceWatchdog.java:27`).
5. **Keep the transport fallback logic** (`:289-311`): port 5060/5061 may be taken by the
   phone's IMS/VoLTE stack, and falling back to an ephemeral port is correct because the
   PBX learns the contact from REGISTER. Do not "simplify" it.
6. **Keep `TlsChangedException` and the process-restart path** — PJSIP genuinely cannot
   destroy and recreate an endpoint at runtime.

## Acceptance criteria

- [ ] `createEndpoint` runs only on the control thread; concurrent creation is impossible.
- [ ] `hasTransport()` no longer registers threads; it asserts instead.
- [ ] Exactly one `libRegisterThread` call happens per process for the control thread; no
      short-lived thread is ever registered.
- [ ] `initializeSip` never runs on the main thread.
- [ ] `SipInit` bare thread is gone.
- [ ] `ReconnectionStrategy` and `ServiceWatchdog` run on the control thread's looper.
- [ ] The main-thread hop for `new Endpoint()` is preserved and its non-deadlock property
      is documented.
- [ ] Transport port-fallback and TLS-restart behaviour unchanged.

## Verification

1. On-device: force repeated reconnects (block the PBX at the firewall for 5 minutes, then
   restore). Expect exponential backoff, one reconnect at a time, successful
   re-registration, and **no** main-thread freeze (`logcat` shows no `Skipped … frames`).
2. Thread-descriptor leak: run 200 reconnect cycles, then dump pjsua's pool usage
   (raise the PJSIP log level via `verbose_sip_log` and look for pool capacity lines).
   Capacity must be flat.
3. TLS toggle still triggers the process restart and re-registers.
4. Kill the network entirely and confirm the app stays responsive.

## Risk

Medium-high. Thread registration is the thing that most recently caused process aborts
(commit `2626f5d`, "register threads with pjlib before they can reach pjsua"). Removing
the defensive registration from `hasTransport` is only safe **after** the control thread
genuinely owns every caller — so land GW-10 and verify it before touching `:85`. If any
caller is missed, the symptom is an immediate hard abort, not an exception.
