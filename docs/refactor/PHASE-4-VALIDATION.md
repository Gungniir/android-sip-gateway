# Phase 4 — on-device validation, wave by wave

Every wave is tagged (`phase-4-wave-N`) and a debug APK is kept in
`release-output/phase-4-waves/` (gitignored). Validate a wave by checking out its tag — or by
flashing its APK — so a regression is attributable to one wave rather than to the whole phase.

**Nothing below has been run.** All of it needs a human on the handsets. Phase 4 has no
instrumented tests at all (JVM/Robolectric only), so unlike Phases 0–2 there is no automated
layer beneath this document — for the view layer, *this document is the only test*.

Devices: **merlinx** = Redmi Note 9, MT6768, debug build, assertions armed, `055f14050405`.
**lavender** = Redmi Note 7, SDM660, release build, `c31adecd`.

Standing hazards while testing:
- **Never read `/proc/asound/*/status` during a call on merlinx** — kernel panic. `tinymix`
  and `hw_params` are safe.
- **Do not clear the SMS fixture on merlinx**: 8 unread messages, `_id` 3/4/5/6/8/10/11/12.
  They are the GW-27 reproduction and are deliberately preserved. GW-27 has landed, so they
  should now be forwarded **once** and not again on the next restart — but do not mark them
  read or clear the inbox to "tidy up" during UI testing.
- Use the SDK's `adb` (`~/Android/Sdk/platform-tools/adb`). Broadcasts need
  `-p org.onetwoone.gateway`, and `-f 0x00000020` after a `force-stop`.
- **Phase 4 changes what the default dialer sees.** Anything that touches the dialer role
  stops `GatewayInCallService` binding. If a device stops answering GSM calls during UI
  testing, check the role before assuming a UI bug.

---

## 0. How UI regressions present — read this before wave 1

Phases 0–2 failed loudly: crashes, tombstones, audible artefacts. **Phase 4 mostly fails
silently**, and the failure modes do not resemble each other. Knowing which one you are
looking at is most of the work.

| Failure class | What it looks like | Where it hides |
|---|---|---|
| **Theme incompatibility** | Immediate crash on launch — `IllegalArgumentException: The style on this component requires your app theme to be Theme.MaterialComponents (or a descendant)`, or the AppCompat variant | Unmissable. The good news |
| **Unwired control** | You change a setting, press Save, nothing happens — or it appears to work and is gone after a restart | **The dangerous one.** Invisible until the gateway misbehaves days later |
| **Mislabelled control** | A hint or label attached to the wrong field — "SIP Port" over the username box | String extraction moved 68 literals. Only a side-by-side against the previous build catches it |
| **Contrast collapse** | Text invisible or barely legible in exactly one theme | A token defined in `values/` but not `values-night/`, or vice versa |
| **Popup blindness** | A spinner opens and looks empty | Material popup styles inheriting the wrong background — white on white |
| **Stale state** | A value on screen no longer tracks reality | GW-45: a derived clock value cached instead of re-read |

**The unwired-control class is what this phase must actually be tested for**, because GW-41
rewrites a 565-line layout wired to 40 `findViewById` calls and 11 observers. Every control
that persists something must be exercised as *set → save → kill the app → relaunch → confirm
it survived*. Reading the value back on the same screen proves nothing: it may be reading the
in-memory field it just wrote.

### The full control inventory that must survive wave 2

Set each to a non-default value, save, `force-stop`, relaunch, confirm:

SIP server · port · username · password · realm · TLS checkbox · SIM1 destination ·
SIM2 destination · incoming call mode (both radio options) · battery limit (60 / 100) ·
sound card · capture device · playback device · mixer route · TX gain · RX gain ·
device mute preset · custom mute controls (checkboxes) · manual mute controls (free text) ·
web interface switch · test destination · test mode · verbose PJSIP log · DTMF relay

That is 25 persisted controls. A layout rewrite that drops one will not announce it.

### Cross-cutting checks for every wave

```
# 1. Does it launch at all, on both SoCs?
adb -s <dev> shell am start -n org.onetwoone.gateway/.MainActivity
adb -s <dev> logcat -d | grep -iE 'AndroidRuntime|FATAL'

# 2. Did the gateway still come up? A UI change must not touch the service.
adb -s <dev> logcat -d | grep -E 'INVARIANT|Registration|registered'

# 3. Both themes, both devices.
adb -s <dev> shell cmd uimode night yes
adb -s <dev> shell cmd uimode night no
```

Check 2 matters more than it looks. Phase 4 is presentation work, but GW-45 touches the
publication boundary and GW-40 touches the application class. **If registration or call
handling changes behaviour in any wave, that is a Phase 4 bug**, not an unrelated flake —
the whole phase is supposed to be incapable of affecting them.

### What cannot be validated on the available hardware

State these as unverified rather than passing them:

- **The legacy launcher-icon path (API 23–25).** `minSdkVersion` is 23, so GW-44 must ship
  rasterised `mipmap-*dpi` PNGs alongside `mipmap-anydpi-v26`. Both test devices are API 26+
  and will only ever load the adaptive icon. Confirm the API level with
  `adb -s <dev> shell getprop ro.build.version.sdk`; if both are ≥26, the legacy path needs an
  emulator or it stays unverified. An API-23 device with no launcher icon is the failure.
- **Any device that is neither MT6768 nor SDM660.** The mute presets name a Redmi Note 7, a
  generic SDM4xx and a Redmi 4X; only one of those is on the bench.

---

## Wave 1 — `phase-4-wave-1` (GW-40, GW-45)

385 tests, 0 failures, lint clean (`HardcodedText` 54 → 0, baseline not regenerated),
`assembleDebug` green including the native build. Nothing on hardware.

### Read this first: wave 1 is *supposed* to look half-finished

Two things will look broken and are not. If you report either as a regression, the fix will
be to explain the wave split again — so they are written down here instead.

1. **In dark mode, several blocks render light-grey on a dark surface.** `activity_main.xml`
   still hardcodes `#f0f0f0`, `#666666`, `#999999` and 9/10sp text. GW-40 deliberately did not
   restyle individual widgets, because GW-41 rewrites that file from scratch in wave 2 and
   per-widget styling now would be thrown away. `Widget.Gateway.Console` already exists as the
   landing spot. **Expected in wave 1; a regression only if still present after wave 2.**
2. **All seven buttons render as filled primary**, with no visual hierarchy between Save,
   Connect, Disconnect and Restart. The layout assigns no styles yet.
   `Widget.Gateway.Button.Secondary` / `.Destructive` / `.Text` exist for GW-41 to assign.

**GW-45 is invisible in this wave.** `MainActivity` still observes the deprecated
`getStatusText()` composite, so the status area looks identical to Phase 2. That is correct:
GW-45 built the surface, GW-41 renders it. The only thing to check here is that status still
works *at all* — see below.

### 1. Does it launch? (the theme-incompatibility gate)

This is the one wave-1 failure that would be loud, and it must be cleared on both devices
before anything else is worth doing.

```
adb -s <dev> install -r app-debug.apk
adb -s <dev> shell am start -n org.onetwoone.gateway/.MainActivity
adb -s <dev> logcat -d | grep -iE 'AndroidRuntime|FATAL|MaterialComponents|AppCompat'
```

A Material theme applied to a stock-widget layout fails at **inflation**, immediately and
with a clear message — `The style on this component requires your app theme to be
Theme.MaterialComponents (or a descendant)`. If it launches, this class of failure is
excluded entirely.

### 2. The string extraction — the silent one

68+ literals moved into `strings.xml`. A wrong mapping puts a correct-looking label on the
wrong control, and nothing about that is detectable at runtime.

**Compare side by side against the pre-Phase-4 build**:
`release-output/phase-2-waves/wave-3-*.apk` is the last UI before any of this. Screenshot
both, top to bottom, and diff by eye. Pay particular attention to the hint text inside the
eleven `EditText` fields — hints are the easiest to transpose and the least likely to be
noticed, because you only see them while a field is empty.

Three strings need explicit checking, because the plan wrongly called them dead and they turn
out to be spinner prompts — they appear **only when the spinner dialog is open**:
tap the Capture Device, Playback Device and Mixer Route spinners and confirm each dialog has
a sensible title.

### 3. Both themes, both devices

```
adb -s <dev> shell cmd uimode night yes
adb -s <dev> shell cmd uimode night no
```

There is **no `setDefaultNightMode()` call** — GW-40 chose to follow the system rather than
force a mode, so the day palette stays reachable and a per-app override can be a real setting
in GW-41/GW-42 rather than a hardcoded constant.

Consequence to check rather than assume: **Android has no system dark theme before API 29,
and `minSdk` is 23.** Confirm each device's level with
`adb -s <dev> shell getprop ro.build.version.sdk`. If a device is below 29 it will *always*
render the day palette and the night half is untestable there — record that rather than
reporting the toggle as broken.

In each theme, on each device, check: status text legible; section headers distinguishable
from body text; spinner dialogs not white-on-white when opened; the app bar and status bar
not the same colour as each other.

### 4. GW-45 — confirm nothing regressed

The surface is not rendered yet, so this is a no-regression check only.

1. Start the gateway. The status block must still show three lines — `SIP:` / `Call:` /
   `Audio:` — exactly as before.
2. Stop the gateway, or kill the service. The status must read **`Service not connected`** —
   *not* `SIP: Not configured / Call: Idle / Audio: Not initialized`. Those are two different
   strings and preserving the distinction is the one thing in GW-45 that could have regressed
   silently. A unit test covers it; this confirms it on the device.
3. Place one real call and watch the status change. Registration and call handling must behave
   exactly as in Phase 2 — **if either changed, that is a Phase 4 bug**, since nothing in this
   wave is supposed to be able to affect them.

### 5. Not verifiable here

- **Material widgets on API 23–25.** The unit tests run at SDK 28 and both bench devices are
  API 26+. The Material upgrade on Android 6/7 is unverified.
- **Whether the palette actually reads well** on the merlinx and lavender panels, at arm's
  length, in a drawer or over a bench. No amount of unit testing reaches this; it is a
  judgement call that needs the physical device in the intended setting.
- **H18** (the locale-sensitive SoC detection found during this wave) cannot be reproduced on
  merlinx, which prints `mt6768` — a marker that survives the bug. Do not attempt it.

## Wave 2 — `phase-4-wave-2` (GW-41, GW-44)

*To be completed when the wave lands.*

## Wave 3 — `phase-4-wave-3` (GW-42)

*To be completed when the wave lands.*
