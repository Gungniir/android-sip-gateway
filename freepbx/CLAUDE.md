# CLAUDE.md — PBX side of the gateway

Snapshot of the FreePBX configuration that makes the Android gateway usable. Nothing here
is built or deployed by Gradle — it is the other half of the feature, kept in version
control so it is not one bad edit away from being lost. The live copies are in
`/etc/asterisk/` on the FreePBX host.

This is **not** the same deployment as `asterisk-config/` at the repo root. That one is a
plain Asterisk config with a hand-written dialplan; this one runs on FreePBX, where most
routing is GUI-managed and only the files below are hand-written.

## What's here

| File | Live path | Hand-written? |
|---|---|---|
| `extensions_custom.conf` | `/etc/asterisk/extensions_custom.conf` | yes — calls and SMS |
| `globals_custom.conf` | `/etc/asterisk/globals_custom.conf` | yes — the gateway→endpoint map and the SMS domain, nothing else |
| `pjsip.endpoint_custom_post.conf` | `/etc/asterisk/pjsip.endpoint_custom_post.conf` | yes — `message_context` only |
| `func_odbc_custom.conf` | `/etc/asterisk/func_odbc_custom.conf` | yes — reads Inbound/Outbound Routes for SMS |
| `freepbx-generated.reference.conf` | several | **no** — GUI output, snapshot only |

`freepbx-generated.reference.conf` is a read-only record of what the GUI produced, so a
future session can see the settings that matter without SSH. Never copy it back: FreePBX
regenerates those files from its database and would overwrite it. The auth password is
redacted.

## Numbering

DIDs follow `10<gw><sim>`, and the dialplan decodes the gateway and SIM out of the number
itself — that is what keeps it generic:

| DID | Label | Prefix | Meaning |
|---|---|---|---|
| 1011 / 1012 | GW1SIM1 / GW1SIM2 | `*011` / `*012` | gateway 1 (`gw1rn9`), SIM 1 / SIM 2 |
| 1021 / 1022 | GW2SIM1 / GW2SIM2 | `*021` / `*022` | gateway 2 (`gw2rn7`), SIM 1 / SIM 2 |
| 2001, 2002 | | | softphones |

The same number means the same thing in both directions: outbound it selects a SIM,
inbound it says which SIM rang.

A SIM has exactly two other spellings, and both are the DID again: **`GW<gw>SIM<sim>`** is
what a human sees, **`*0<gw><sim>`** is what you dial — literally `*0` plus the DID's last
two digits. The prefix has to name the gateway too, not just the slot: `*1` used to mean
"SIM 1" and became ambiguous the moment a second gateway existed, so a redial off a GW2
call went out GW1. The `*0` namespace was chosen because it is the only one on this box
with no feature code in it — `*9…` collides with `_*91.` (call-forward-busy deactivate) and
a bare `*21` with `_*21X!` (Find Me/Follow Me).

The slot the app is told about (`X-GSM-SIM`) is still just 1 or 2 — the gateway is the
endpoint the INVITE went to, so the app never needs to know its own number.

## Outbound: SIP → GSM

```
softphone → outbound route → custom trunk → Local/<did>*<number>@gsm-out
          → [gsm-out] decodes DID → Dial(PJSIP/<number>@<endpoint>, b(gsm-sim-header))
          → INVITE carries X-GSM-SIM: <slot>
```

The app reads that header in `SipHeaderReader.readSimSlot()` and `CallManager` uses it to
pick the SIM. The `b()` predial handler is essential — it runs on the *outbound* channel,
the only place `PJSIP_HEADER(add,...)` reaches the INVITE.

`[gsm-out]` also rewrites `CONNECTEDLINE` so the caller sees `GW2SIM1 +7…` as the name and
a redial-able `*021+7…` as the number.

Prefix `*0<gw><sim>` forces a SIM; without a prefix the outbound route's **CallerId column**
gives each softphone its default SIM. No dialplan change needed for either.

## Inbound: GSM → SIP

The app puts the GSM number in `X-GSM-CallerID` (it cannot use `From` — that carries the
endpoint's own identity and authenticates it). Each gateway trunk sets
**Context = `from-gsm-gateway`**, which turns that header into a real CallerID and then
hands the call straight back to `from-pstn` so normal Inbound Routes still apply.

The CallerID it builds is `"GW2SIM1 +7…" <*021+7…>` — readable number in the name, prefixed
number in the *number*, so calling back from the phone's history returns on the SIM that was
called instead of taking the caller's default route. Two consequences of the prefix living
in `CALLERID(num)`: a softphone will not match the caller against its address book, and
`app-blacklist-check` sees the prefixed string, so blacklist entries would have to be
written that way. The display name is what phones show when it is set, which is always here.

## SMS: SIP MESSAGE both ways

SMS reuses the DID plan, but a MESSAGE never *runs* an Inbound or Outbound Route — routes
are call dialplan. It is routed by the endpoint's **`message_context`**, which FreePBX has
no GUI field for, so it lives in `pjsip.endpoint_custom_post.conf`; the routes are then
*read* out of FreePBX's database instead of executed (see below).

```
GSM  -> SIP   app MESSAGEs sip:1011@pbx, X-GSM-CallerID: +7…
              → [from-gsm-sms] → Inbound Route for 1011 → MessageSend to 2001
SIP  -> GSM   2001 MESSAGEs +7… (or *011+7… to force a SIM)
              → [gsm-sms-out] → [gsm-sms-send] → MESSAGE to gw1rn9, X-GSM-SIM: 1
```

**The GUI decides SMS routing too, by being read rather than run.** A route is call dialplan
(`sub-record-check`, `AGI(sangomacrm.agi)`, then `Goto(from-did-direct,2001,1)` into a
`Dial()`), and none of that survives a message channel — so `func_odbc_custom.conf` queries
FreePBX's own tables for the same two answers:

| | reads | answers |
|---|---|---|
| `ODBC_GSMSMSDEST(<did>)` | `asterisk.incoming` | which extension an **Inbound Route** points a SIM DID at — the extension comes out of the destination's Goto triple |
| `ODBC_GSMSMSSIM(<ext>)` | `outbound_route_patterns` → `outbound_route_trunks` → `trunks` | which SIM DID an extension's **Outbound Route** (the one whose CallerID column is that extension) calls from, taken from the trunk's `Local/<did>*$OUTNUM$@gsm-out/n` dial string |

So an extension's SMS always leaves on the same SIM its calls do, and there is no parallel
map to keep in step. There is no fallback layer: if the `#include func_odbc_custom.conf`
line goes missing, both lookups return nothing and SMS stops with a logged reason
(`No SMS destination for <did>` / `No SIM for <ext>`) rather than quietly rerouting.

The app must have its SIM destinations set to the SIM DIDs (`1011` / `1012`) — that is what
tells the dialplan which SIM took the SMS, exactly like a call's DID.

Details worth knowing:

- **The inbound From is `"GW2SIM1 +7…" <sip:*021+7…@domain>`** — number in the display name,
  `*0<gw><sim>` prefixed in the URI, so a reply goes back out the SIM it arrived on. Same
  trick as `CONNECTEDLINE` in `[gsm-out]`.
- **Routing vs. addressing.** `MessageSend(pjsip:<endpoint>)` routes to the registered
  contact; `MESSAGE(to)` set beforehand rewrites only the To header, which is where the app
  reads the destination number (`extractPhoneNumber`). The `pjsip:<endpoint>/<uri>` form
  makes that URI the *Request URI* — right for a contact URI, wrong for anything else (give
  it the PBX domain and the MESSAGE comes back to the PBX).
- **A message reaches one device per extension unless you fan it out.** Asterisk resolves an
  endpoint destination to a single contact; `Dial()` forks to every contact, `MessageSend()`
  does not. `[gsm-sms-deliver]` walks `PJSIP_AOR(<ext>,contact)` and sends one copy per
  contact URI, so a desktop and a mobile sharing extension 2001 both get the SMS. Use
  `SHIFT()` to iterate — `CUT()` defaults to a `-` delimiter, not a comma.
- **Custom headers go through `MESSAGE_DATA()`**, not `PJSIP_HEADER()` — a message runs on a
  `Message/ast_msg_queue` channel, not a PJSIP one. Received headers show up the same way.
- **`SUCCESS` only means the phone accepted the MESSAGE**, not that GSM delivered it. The app
  does not report SMS delivery back to the PBX.
- `[gsm-sms-bounce]` sends the sender a failure notice when a message is not accepted —
  offline softphone, unreachable gateway, an extension with no SIM. It replaces the old
  `[myMessages]` bounce, and `[gsm-sms-out]`'s `_X.` catch-all replaces its
  extension-to-extension relay.
- **The app's destination regex takes 10–15 digits**, so short codes (900, 3333) are rejected
  on the SIP→GSM leg.

## Adding a gateway

One line in these files, and it is the only fact FreePBX cannot be asked for — the Custom
Trunk row that holds the DID and the PJSIP Trunk row that holds the endpoint name are
unrelated in its database:

```
GSMGW_103 = gw3name          # globals_custom.conf
```

Everything else is GUI — a PJSIP trunk, two Custom Trunks, Inbound Routes for 1031/1032,
and two forced-SIM Outbound Routes with prefixes `*031` / `*032`. Neither
`extensions_custom.conf` nor `func_odbc_custom.conf` changes.

## Adding an extension

Calls need nothing. SMS needs one block here, plus the same Outbound Route the extension
already needs to make calls — its **CallerID column** is what gives it a default SIM, for
calls and SMS alike:

```
[2003](+)                    # pjsip.endpoint_custom_post.conf
message_context=gsm-sms-out
```

## GUI settings that are not in these files

On the gateway's PJSIP trunk:

- **Context** = `from-gsm-gateway` (default `from-pstn` breaks CallerID)
- **Registration** = Receive — without it FreePBX builds an outbound trunk whose AOR has
  no `max_contacts`, and every REGISTER is answered `403 Forbidden`
- **CID Options** = Allow Any CID; **Outbound CallerID** blank
- **Media Encryption** = SDES — the app sets `PJMEDIA_SRTP_MANDATORY`, so a mismatch kills
  audio after registration succeeds
- **Max Channels** = 1

Custom Trunks (one per SIM): `Local/1011*$OUTNUM$@gsm-out/n`. `ODBC_GSMSMSSIM` parses the
DID back out of this string, so keep the `<did>*` shape.

Outbound Routes: forced-SIM routes (prefix `*0<gw><sim>`, e.g. `*021` + `+7XXXXXXXXXX`)
above the per-extension defaults; **Route CID blank** — the header selects the SIM now, and
a Route CID would overwrite the softphone's ID for nothing. A default route's **CallerID
column** must be the extension: that is what makes it that extension's default SIM, and it
is the row `ODBC_GSMSMSSIM` looks for.

## Restoring

```
cp extensions_custom.conf globals_custom.conf pjsip.endpoint_custom_post.conf \
   func_odbc_custom.conf /etc/asterisk/
grep -q func_odbc_custom /etc/asterisk/func_odbc.conf \
  || echo '#include func_odbc_custom.conf' >> /etc/asterisk/func_odbc.conf
fwconsole reload
```

A reload is always required — editing the files alone changes nothing. The `#include` line
is the one edit made to a file this repo does not own: `func_odbc.conf` ships with the
asterisk package, and only that line is ours.

**A FreePBX upgrade truncated every `pjsip.*_custom*.conf` to 0 bytes once** (FreePBX 17 /
Asterisk 22), which silently killed SMS: without `message_context` a MESSAGE follows the
endpoint's call context and is dropped. If SMS stops working after an upgrade, check that
file has content before anything else.

## Gotchas

- **The app handles one call at a time.** Max Channels caps each Custom Trunk separately,
  so FreePBX will allow one call on each SIM of a gateway at once; the app rejects the
  second with `486 Busy Here`. Two gateways really are two calls — different phones.
- **Codecs.** The trunk still offers `opus,alaw`. Prefer `alaw` alone: the app pins no
  codec list, and PJSIP fires a codec-locking `UPDATE` right after the 200 OK that once
  caused one-way audio. A single-codec offer leaves nothing to renegotiate.
- **Connected-line updates are advisory.** Most SIP clients repaint only when the
  connected *number* changes, so on a `*0<gw><sim>`-prefixed call — where our value equals
  what was dialled — the name is silently dropped. Not a dialplan bug.
- **`asterisk -rx` needs more than the `asterisk` group.** The CLI socket is `0755`, so
  only root or the `asterisk` user can use it. Group membership grants log reads and
  `/etc/asterisk` writes, but not CLI or `fwconsole`.
