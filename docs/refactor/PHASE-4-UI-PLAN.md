# Phase 4 — UI: from debug harness to appliance console

Companion to [ROADMAP.md](ROADMAP.md). Independent of Phases 1–3: it touches presentation
only and shares no files with the concurrency work, so it can run in parallel or after.

Written 2026-08-24, while Phase 1 wave 2 was in flight.

---

## 1. The diagnosis

The UI does not look unfinished because it was styled badly. **It was never styled at all.**

| | Measured state at `c0255dd` |
|---|---|
| Layout | **one** `activity_main.xml`, 565 lines — 29 `TextView`, 11 `EditText`, 7 `Button`, 6 `Spinner`, 4 `RadioButton`, 3 `CheckBox`, 1 `Switch`, in 12 nested `LinearLayout`s inside one `ScrollView` |
| Theme | `Theme.AppCompat.Light.DarkActionBar` with an **empty body** — no palette, typography, or component styles |
| `colors.xml` | 2 entries, **both launcher-icon colours**. There is no app palette |
| Strings | **43 hardcoded** in the layout; `strings.xml` has 4 |
| Material Components | **not a dependency** — appcompat only |
| Dark theme | no `values-night` |
| Icon | one `ic_launcher.xml`; no mipmap density buckets, no adaptive icon |
| Wiring | `MainActivity` 580 lines, `MainViewModel` 616 lines |
| Web UI | `index.html` 131, `style.css` 246, `config.js` 248 lines |

Every control is a raw stock widget stacked in declaration order: no grouping, no hierarchy,
no visual state. That is the whole of why it reads as a nightly build.

**What makes this tractable rather than a rewrite:** `MainViewModel` already exposes
everything through LiveData, and Phase 1 gave it an immutable `GatewayStatus` snapshot. The
presentation layer is genuinely separable from the logic today.

---

## 2. What this device actually is

A rooted phone in a drawer. It is an **appliance**, not something anyone uses daily. The
on-device UI has exactly three jobs:

1. **Commissioning** — provisioning a new handset: root, permissions, dialer role, SIP
   account, and proving the audio bridge works.
2. **At-a-glance health** — is it registered, is a call up, is anything wrong.
3. **Diagnostics** — when something breaks, and you are holding the phone.

Day-to-day administration belongs on the web interface (`WebConfigServer`, port 8080).

So the target aesthetic is a **confident status-first console**, not a consumer app. Density
is a virtue here; whitespace-heavy consumer styling would make it worse. Design for someone
standing over a bench with the phone in one hand.

---

## 3. Hard constraints

1. **`targetSdkVersion` stays 27.** Privileged telephony and `InCallService` behaviour depend
   on it. `compileSdkVersion` is 36 and `minSdkVersion` 23, so Material Components compiles
   and AppCompat handles dark mode independently of targetSdk — **no UI feature is worth
   raising targetSdk, ever.** Material 3 *dynamic colour* needs API 31 at runtime and is
   therefore out of scope; ship a fixed palette.
2. **`minSdkVersion 23`** — the design must work on Android 6.
3. **The UI reads `GatewayStatus`, never the managers.** Phase 1 exists to make lifecycle
   state single-threaded; a UI change that reaches back into `CallManager` /
   `AudioBridgeManager` / `SipAccountManager` directly would undo it. If the snapshot lacks a
   field the UI needs, **add the field** — do not add a live read.
4. **Nothing that shells out to root may run on the main thread.** `PermissionManager`,
   `AudioDeviceManager` and `TinymixManager` all spawn `su` with unbounded `waitFor`. That is
   AUDIT G1–G3 territory and the new UI must not widen it.
5. **Do not touch** `org/pjsip/pjsua2/**`, ALSA control names or ordering, or any file under
   active Phase 1/2/3 work.
6. The `assets/` directory also holds the `tinymix` / `tinycap` / `tinyplay` **binaries** —
   web assets live alongside them. Do not disturb them.

---

## 4. The issues

| Issue | Scope | Depends on |
|---|---|---|
| **GW-40** Design system foundation | Material Components dependency, colour palette (light + `values-night`), typography scale, spacing scale, component styles, extract all 43 hardcoded strings. **No layout restructuring.** | — |
| **GW-41** Status-first main screen | Restructure `activity_main.xml` into a persistent status header + grouped sections/tabs. Decompose `MainActivity` (580 lines) and `MainViewModel` (616 lines) — the layout and its wiring cannot be separated, so they are one issue. | GW-40 |
| **GW-42** First-run commissioning wizard | 5-step flow: root → permissions → dialer role → SIP account → verification test call. Reuses `PermissionManager`, `RootHelper`, `SipTestCallManager`. Must be skippable and re-runnable. | GW-40, GW-41 |
| **GW-43** Web interface redesign | `assets/index.html`, `style.css`, `config.js` to match the app's language. **Coordinate with GW-30** (Phase 3): the server is currently unauthenticated on `0.0.0.0:8080` and returns `sip_password` in cleartext. Redesigning the login-less UI without landing auth ships a prettier hole. | GW-40 (tokens) |
| **GW-44** Icon and branding | Adaptive icon, mipmap density buckets, notification icon (currently the stock `ic_menu_call`), app label. | — |

### Sequencing

```
GW-40  (alone — everything downstream consumes its tokens)
  │
  ├── GW-41  main screen + wiring decomposition ──┐
  ├── GW-43  web interface        (parallel)      │
  └── GW-44  icon and branding    (parallel)      │
                                                  │
                    GW-42  commissioning wizard ◄──┘
```

GW-41 is the only one that rewrites `MainActivity`; GW-43 and GW-44 touch disjoint files and
can run alongside it. GW-42 lands last because it reuses GW-41's components.

---

## 5. Risks

- **GW-41 is where regressions hide.** Every one of those 43 controls is wired to something.
  The `MainViewModel` LiveData surface is the contract — enumerate every observer before
  restructuring, and keep the ViewModel's public API stable so the diff stays in the view
  layer.
- **The wizard can strand a half-provisioned phone.** GW-42 must be skippable at every step
  and re-runnable from settings; a wizard that blocks the main screen until complete would
  make a partially-configured gateway unusable.
- **Dark theme is not free at `targetSdk 27`.** Use `AppCompatDelegate.setDefaultNightMode`
  rather than relying on system-follows behaviour that a targetSdk-27 app does not get.
- **GW-43 without GW-30 is a net negative** for security posture — a better-looking
  unauthenticated config endpoint invites more use of it.
- No instrumented tests exist (JVM/Robolectric only), so **UI verification is manual on
  hardware**, on both merlinx and lavender, at both themes.
