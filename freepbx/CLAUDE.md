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
| `extensions_custom.conf` | `/etc/asterisk/extensions_custom.conf` | yes — ours, plus a pre-existing `[myMessages]` |
| `globals_custom.conf` | `/etc/asterisk/globals_custom.conf` | yes — the gateway→endpoint map |
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

## Adding a gateway

One line here:

```
GSMGW_102 = gw2name          # globals_custom.conf
```

Everything else is GUI — a PJSIP trunk, two Custom Trunks, Inbound Routes, Outbound
Routes. `extensions_custom.conf` does not change.

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
cp extensions_custom.conf globals_custom.conf /etc/asterisk/
fwconsole reload
```

A reload is always required — editing the files alone changes nothing.

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
