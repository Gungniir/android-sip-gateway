# GW-30 — Unauthenticated control surfaces: exported receiver and open web config

**Phase** 3 · **Severity** P1 (local privilege / credential exposure) · **Closes** AUDIT S1, S2
**Files** `AndroidManifest.xml`, `GatewayControlReceiver.java`, `WebConfigServer.java`
**Depends on** nothing · **Conflicts with** GW-24 (`WebConfigServer.java`)

## Problem

**S1 — exported control receiver, no permission.**
`AndroidManifest.xml:115-124` declares `GatewayControlReceiver` with
`android:exported="true"` and no `android:permission`. Any app on the device — no
permissions of its own required — can:

- rewrite the SIP server, user and **password** (`GatewayControlReceiver.java:180-220`),
  pointing the gateway at an attacker's PBX;
- change SIM→extension routing (`:222-234`), redirecting inbound calls and SMS;
- start/stop the gateway (`:81-87`);
- place calls via `TEST_CALL` (`:98-101` → `PjsipSipService.startTestCall`).

The redirect case is the serious one: an attacker who repoints `sim1_destination` receives
the victim's inbound GSM calls and SMS — including one-time passcodes.

**S2 — web config server: no auth, cleartext password.**
`WebConfigServer` binds `0.0.0.0:8080` (`super(port)`, `:41`; NanoHTTPD binds all
interfaces by default) with no authentication.
`GET /api/config` returns `sip_password` in cleartext (`:128`), and `POST /api/config`
(`:219`) accepts unauthenticated writes with the same consequences as S1. Anyone on the
same Wi-Fi can read the SIP credentials.

Mitigating context, worth stating honestly: this is a single-purpose device on the
operator's own network, the web interface is **off by default**
(`GatewayConfig.isWebInterfaceEnabled()` defaults to `false`, `:260`), and root is
required for the app to work at all. This is hardening, not an active incident.

## Required change

**Receiver:**
1. Declare a signature-level permission and require it:
   ```xml
   <permission android:name="org.onetwoone.gateway.permission.CONTROL"
               android:protectionLevel="signature" />
   ...
   <receiver android:name=".GatewayControlReceiver"
             android:exported="true"
             android:permission="org.onetwoone.gateway.permission.CONTROL">
   ```
   `signature` means only apps signed with the same key can send. `adb shell am broadcast`
   from a root shell still works (shell has the necessary privilege), so the documented
   debugging workflow in `CLAUDE.md` and `GatewayControlReceiver`'s header comment is
   preserved — **verify this explicitly**, it is the main compatibility risk.
2. If a non-signature caller is genuinely needed, use `signatureOrPrivileged` (the repo
   already ships `privapp-permissions-gateway.xml`) rather than dropping the requirement.
3. Never log the password. `:204` already masks it (`"***"`) — keep that, and audit
   `showConfig` (`:287-306`), which correctly masks at `:296`.

**Web server:**
4. **Bind to loopback or the local subnet only**, not `0.0.0.0`. NanoHTTPD's
   `(hostname, port)` constructor takes a bind address — use it.
5. **Require authentication.** A generated token, shown once in the app UI and required as
   a header or query parameter on every `/api/*` request, is sufficient here and does not
   need a login flow. Fail closed: no token configured → API disabled.
6. **Stop returning the password.** `getConfigJson` (`:128`) should return a
   boolean `sip_password_set` instead of the value. The UI can offer "change password"
   without ever reading the old one.
7. **Remove the hardcoded credentials** at `:125-131` (`192.168.5.95`, `gateway123`,
   `101`) — they leak a real-looking configuration to an unauthenticated reader. GW-24
   removes them anyway by routing through `GatewayConfig`.
8. Consider HTTPS out of scope: on a LAN with a token and a bound address, the marginal
   benefit does not justify certificate management on this device.

## Acceptance criteria

- [ ] `GatewayControlReceiver` requires a signature-level permission.
- [ ] `adb shell am broadcast -p org.onetwoone.gateway -a …` still works for every
      documented action (START, STOP, CONFIGURE, GET_STATUS, TEST_CALL).
- [ ] Web server binds to a specific address, not all interfaces.
- [ ] Every `/api/*` endpoint requires a token; requests without one get 401.
- [ ] `sip_password` is never returned by any endpoint or written to any log.
- [ ] No hardcoded example credentials remain in served content.

## Verification

1. From a second app (or `adb shell am broadcast` **without** `-p`, unsigned context),
   attempt `CONFIGURE`. It must be rejected; `logcat` shows the permission denial.
2. From the shell with `-p`, run each documented broadcast from
   `GatewayControlReceiver`'s header comment and confirm all still work.
3. `curl http://<device-ip>:8080/api/config` from another machine → 401 (or connection
   refused if bound to loopback). With the token → 200, and the response contains
   `sip_password_set` but no password value.
4. `grep -ri "gateway123\|192.168.5.95" app/src/main` → no hits.

## Risk

Medium. §1 can break the operator's own tooling if anything drives the gateway by
broadcast from an unsigned app. Confirm what actually sends these broadcasts today before
merging — if a third-party automation app is in use, `signatureOrPrivileged` plus the
existing priv-app permission file is the path, not removing the check.

---

## Added after Phase 2 — H16: `GET_STATUS` is a stub, and wiring it widens this surface

`ACTION_GET_STATUS` is documented in `CLAUDE.md` and in `GatewayControlReceiver`'s header as
part of the remote-control API. Its handler is `Log.i(TAG, "GET_STATUS not yet implemented")`.

Phase 2 filled `GatewayStatus.toBundle()` with real content — call state, grace-period
instant, config generation, `calls_created`/`calls_deleted`/`calls_alive` (GW-22),
`watchdog_terminations`/`silent_bridge_episodes` (GW-25) — and **none of it has a consumer.**
It also made a validation step unrunnable; see AUDIT H16.

**Do the two together, in this order.** Implementing the broadcast first would hand the
gateway's full runtime state to any app on the device, because the receiver is
`exported="true"` with no permission (S1). So: permission-gate the receiver, *then* wire
`GET_STATUS` to `getStatusSnapshot().toBundle()` via `setResultExtras`.

`ServiceWatchdog.checkNow()` is the natural way to force a fresh tick before answering, and
it already exists as an any-thread entry point (`control.post`, not the private handler).

Note the UI half of the same defect is **GW-45** (Phase 4), not this issue: `MainViewModel`
flattens the snapshot to three fields before it reaches the screen.

