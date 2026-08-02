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
| `globals_custom.conf` | `/etc/asterisk/globals_custom.conf` | yes — the gateway→endpoint and SMS maps |
| `pjsip.endpoint_custom_post.conf` | `/etc/asterisk/pjsip.endpoint_custom_post.conf` | yes — `message_context` only |
| `func_odbc_custom.conf` | `/etc/asterisk/func_odbc_custom.conf` | yes — Inbound Route lookup for SMS |
| `freepbx-generated.reference.conf` | several | **no** — GUI output, snapshot only |

`freepbx-generated.reference.conf` is a read-only record of what the GUI produced, so a
future session can see the settings that matter without SSH. Never copy it back: FreePBX
regenerates those files from its database and would overwrite it. The auth password is
redacted.

## Numbering

DIDs follow `10<gw><sim>`, and the dialplan decodes the gateway and SIM out of the number
itself — that is what keeps it generic:

| DID | Meaning |
|---|---|
| 1011 / 1012 | gateway 1 (`gw1rn9`), SIM 1 / SIM 2 |
| 1021 / 1022 | gateway 2, SIM 1 / SIM 2 |
| 2001, 2002 | softphones |

The same number means the same thing in both directions: outbound it selects a SIM,
inbound it says which SIM rang.

## Outbound: SIP → GSM

```
softphone → outbound route → custom trunk → Local/<did>*<number>@gsm-out
          → [gsm-out] decodes DID → Dial(PJSIP/<number>@<endpoint>, b(gsm-sim-header))
          → INVITE carries X-GSM-SIM: <slot>
```

The app reads that header in `SipHeaderReader.readSimSlot()` and `CallManager` uses it to
pick the SIM. The `b()` predial handler is essential — it runs on the *outbound* channel,
the only place `PJSIP_HEADER(add,...)` reaches the INVITE.

`[gsm-out]` also rewrites `CONNECTEDLINE` so the caller sees `SIM1 +7…` as the name and a
redial-able `*1+7…` as the number.

Prefix `*1`/`*2` forces a SIM; without a prefix the outbound route's **CallerId column**
gives each softphone its default SIM. No dialplan change needed for either.

## Inbound: GSM → SIP

The app puts the GSM number in `X-GSM-CallerID` (it cannot use `From` — that carries the
endpoint's own identity and authenticates it). Each gateway trunk sets
**Context = `from-gsm-gateway`**, which turns that header into a real CallerID and then
hands the call straight back to `from-pstn` so normal Inbound Routes still apply.

## SMS: SIP MESSAGE both ways

SMS reuses the DID plan, but none of it goes through Inbound/Outbound Routes — routes only
exist for calls. A MESSAGE is routed by the endpoint's **`message_context`**, which FreePBX
has no GUI field for, so it lives in `pjsip.endpoint_custom_post.conf`.

```
GSM  -> SIP   app MESSAGEs sip:1011@pbx, X-GSM-CallerID: +7…
              → [from-gsm-sms] → Inbound Route for 1011 → MessageSend to 2001
SIP  -> GSM   2001 MESSAGEs +7… (or *1+7… to force a SIM)
              → [gsm-sms-out] → [gsm-sms-send] → MESSAGE to gw1rn9, X-GSM-SIM: 1
```

**Inbound Routes decide where an SMS goes**, but not by running — the route is call
dialplan (`sub-record-check`, `AGI(sangomacrm.agi)`, then `Goto(from-did-direct,2001,1)`
into a `Dial()`), and none of that survives a message channel. Instead `ODBC_GSMSMSDEST()`
reads the route's destination out of `asterisk.incoming` and takes the extension from the
Goto triple, so the GUI stays the single place a DID is pointed somewhere. `GSMSMSTO_<did>`
in `globals_custom.conf` is only the fallback — no route, a route to something that cannot
receive an SMS, or a lost `func_odbc.conf` include.

The app must have its SIM destinations set to the SIM DIDs (`1011` / `1012`) — that is what
tells the dialplan which SIM took the SMS, exactly like a call's DID.

Details worth knowing:

- **The inbound From is `"SIM1 +7…" <sip:*1+7…@domain>`** — number in the display name,
  `*<sim>` prefixed in the URI, so a reply goes back out the SIM it arrived on. Same trick
  as `CONNECTEDLINE` in `[gsm-out]`.
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
  offline softphone, unreachable gateway, missing global. It replaces the old `[myMessages]`
  bounce, and `[gsm-sms-out]`'s `_X.` catch-all replaces its extension-to-extension relay.
- **The app's destination regex takes 10–15 digits**, so short codes (900, 3333) are rejected
  on the SIP→GSM leg.

## Adding a gateway

One line here:

```
GSMGW_102 = gw2name          # globals_custom.conf
```

Plus, for SMS, which extension receives each SIM's messages:

```
GSMSMSTO_1021 = 2001         # globals_custom.conf
GSMSMSTO_1022 = 2001
```

Everything else is GUI — a PJSIP trunk, two Custom Trunks, Inbound Routes, Outbound
Routes. `extensions_custom.conf` does not change.

## Adding an extension

Calls need nothing. SMS needs two lines — a default SIM, and permission to send at all:

```
GSMSMSDID_2002 = 1012        # globals_custom.conf
[2002](+)                    # pjsip.endpoint_custom_post.conf
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

Custom Trunks (one per SIM): `Local/1011*$OUTNUM$@gsm-out/n`.

Outbound Routes: forced-SIM routes above the defaults; **Route CID blank** — the header
selects the SIM now, and a Route CID would overwrite the softphone's ID for nothing.

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
  so FreePBX will allow one call on SIM1 and one on SIM2 at once; the app rejects the
  second with `486 Busy Here`.
- **Codecs.** The trunk still offers `opus,alaw`. Prefer `alaw` alone: the app pins no
  codec list, and PJSIP fires a codec-locking `UPDATE` right after the 200 OK that once
  caused one-way audio. A single-codec offer leaves nothing to renegotiate.
- **Connected-line updates are advisory.** Most SIP clients repaint only when the
  connected *number* changes, so on a `*1`-prefixed call — where our value equals what was
  dialled — the name is silently dropped. Not a dialplan bug.
- **`asterisk -rx` needs more than the `asterisk` group.** The CLI socket is `0755`, so
  only root or the `asterisk` user can use it. Group membership grants log reads and
  `/etc/asterisk` writes, but not CLI or `fwconsole`.
