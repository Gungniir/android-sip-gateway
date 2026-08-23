# GW-07 — Unsafe publication and cross-thread visibility of shared state

**Phase** 0 (land **last**) · **Severity** P1 · **Closes** AUDIT H5, H6, and the visibility half of C1, D1
**Files** many — see table · **Depends on** GW-01…GW-06 merged first
**Conflicts with** everything in Phase 0 — this is why it lands last

## Problem

Fields that cross a thread boundary are declared as plain fields, so a writer's value may
never become visible to a reader, and objects can be observed partially constructed.

| Field | File:line | Written on | Read on |
|---|---|---|---|
| `GatewayConfig.instance` | `config/GatewayConfig.java:97` | first caller (`init` is `synchronized`, `getInstance` is **not**, `:119`) | every thread |
| `PjsipSipService.instance` | `PjsipSipService.java:50` | main (`onCreate`/`onDestroy`) | pjsua workers, NanoHTTPD, broadcast receiver, `GsmDtmfSender` |
| `PjsipSipService.isRunning` | `:71` | main | main, reconnect |
| `GatewayInCallService.instance` | `GatewayInCallService.java:30` | main | pjsua workers |
| `GatewayCall.service` | `GatewayCall.java:25` | any (`dispose`) | pjsua workers |
| `SipAccountManager.account`, `registered`, `lastError` | `sip/SipAccountManager.java:24-26` | `SipInit`, `ConfigReload`, pjsua worker | main, pjsua workers |
| `SipEndpointManager.endpoint`, `endpointUseTls` | `sip/SipEndpointManager.java:31-32` | `SipInit`, main | every thread |
| `CallManager.state`, `currentSipCall`, `pendingGsmDestination`, `pendingGsmSimSlot`, `gsmCallPlacedTime` | `call/CallManager.java:47-50,62` | main, pjsua workers | main, pjsua workers, watchdog |
| `AudioBridgeManager.bridgeActive`, `wiredCallMedia`, `wiredConfSlot` | `audio/AudioBridgeManager.java:29-35` | main, pjsua workers, `ConfigReload` | same |
| `GsmAudioPort.openThread`, `enforceThread` | `GsmAudioPort.java:56,58` | main | main, pjsua workers, `ConfigReload` |
| `SipTestCallManager.call`, `mode`, `mediaWired` | `diag/SipTestCallManager.java:90-93` | main | pjsua workers (`owns()`, `isActive()`) |
| `ReconnectionStrategy.enabled`, `pending`, `currentDelay` | `sip/ReconnectionStrategy.java:23-25` | main, `SipInit`, broadcast receiver | same |
| `ServiceWatchdog.running` | `sip/ServiceWatchdog.java:24` | main | main |
| `RootHelper.hasRoot`, `suProcess`, `suOutputStream` | `RootHelper.java:21-23` | any | any |

`GatewayCall.service` deserves calling out: `disposed` **is** volatile (`:26`) but
`service` is not, and `onCallState` (`:79`) / `onCallMediaState` (`:97`) do
`if (service != null) service.…` — a TOCTOU on a field that is nulled by `dispose()`.
`relayDtmf` (`:158`) already snapshots to a local; copy that pattern.

## Scope of *this* issue

This is deliberately the narrow, mechanical half. It makes the existing design's
cross-thread reads **defined** — it does not make them **correct**. Correctness comes
from GW-10/GW-11/GW-12, which remove most of these reads entirely.

Land it last in Phase 0 so it doesn't conflict with GW-01…GW-06.

## Required change

1. Add `volatile` to every field in the table that is read on a thread other than the one
   that writes it. For `GsmAudioPort.openThread` / `enforceThread`, also snapshot to a
   local before use.
2. Fix `GatewayConfig.getInstance()`: either make it `synchronized`, or make `instance`
   `volatile` — the latter is enough here because construction only reads
   `SharedPreferences` handles.
3. Every consumer of a `volatile` reference **snapshots to a local first**. Add a short
   comment at each snapshot saying why, so it survives future edits. Repeat-read sites to
   fix (beyond the ones GW-03 covers): `PjsipSipService.java:357-362`,
   `:396`, `:406`, `:425`, `:709`; `GatewayCall.java:79`, `:97`;
   `SipTestCallManager.java:141`, `:460`.
4. Where a field is *only* ever touched on the main thread today, do **not** add
   `volatile` — instead add a debug-build assertion (`Looper.myLooper() == mainLooper`)
   so the assumption is enforced rather than assumed. Applies to
   `ServiceWatchdog.running` and `GatewayInCallService.timeoutRunnable`.
5. Do not introduce locks in this issue. If a site needs mutual exclusion rather than
   visibility, note it in AUDIT.md and leave it for Phase 1.

## Acceptance criteria

- [ ] Every field in the table is `volatile`, guarded, or covered by an asserted
      single-thread invariant.
- [ ] No method reads a `volatile` reference field more than once.
- [ ] `GatewayConfig.getInstance()` cannot return a partially constructed instance.
- [ ] No new locks; no behaviour change intended.
- [ ] `./gradlew test` green, `./gradlew lintDebug` introduces no new issues.

## Verification

1. `./gradlew test` — the existing suites (`CallManagerTest`, `GsmDtmfSenderTest`,
   `ReconnectionStrategyTest`, `ServiceWatchdogTest`) must stay green.
2. Enable `StrictMode` thread policy in the debug build and run a full call cycle;
   record any violation in AUDIT.md rather than suppressing it.
3. Diff review: the change should be almost entirely `volatile` keywords and local
   snapshots. Anything else in the diff is out of scope.

## Risk

Low, but the diff is wide. Keep it mechanical; resist the temptation to fix logic here.
