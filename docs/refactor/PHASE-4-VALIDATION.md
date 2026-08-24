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
- **H20** (the locale-sensitive SoC detection found during this wave) cannot be reproduced on
  merlinx, which prints `mt6768` — a marker that survives the bug. Do not attempt it.

## Wave 2 — `phase-4-wave-2` (GW-41, GW-44)

410 tests, 0 failures, lint green (`Autofill`, `ButtonStyle`, `SmallSp`, `UseSwitchCompat`,
`DisableBaselineAlignment`, `SetTextI18n` all now vanished from the baseline),
`assembleDebug` green. Nothing on hardware.

**This is the wave that can break the gateway invisibly.** Wave 1 changed how things looked;
this one rewrote the screen those 25 controls are wired to. Budget real time for §2 below.

### 1. The two wave-1 artefacts must now be gone

Both were expected in wave 1 and are regressions if they survive:

- Light-grey blocks on a dark surface in night mode — the layout's hardcoded `#f0f0f0` /
  `#666666` / `#999999` should be gone.
- All seven buttons rendering as identical filled primary — Save / Connect / Disconnect /
  Restart should now differ.

### 2. The 26-control round-trip — the one that matters

§0 lists 25 controls; GW-41 adds the SoC audio profile, making **26**. A unit test enumerates
them by id, which proves they *exist*. It cannot prove they still reach the right preference.

For each: **set a non-default value → Save → `force-stop` → relaunch → confirm it survived.**

```
adb -s <dev> shell am force-stop org.onetwoone.gateway
adb -s <dev> shell am start -n org.onetwoone.gateway/.MainActivity
```

Reading the value back on the same screen without the force-stop proves nothing — it may be
reading the in-memory field it just wrote.

Two save buttons cover different sets (Save Settings vs Save Audio) and six controls write
through on change with no Save at all — incoming call mode, battery limit, verbose PJSIP log,
DTMF relay, web interface, mute preset. Exercise all three paths.

### 3. H-b — the guard, with a real web save

The one hazard whose fix cannot be proven in a JVM. GW-41 chose dirty-tracking over a plain
`hasFocus()` check, and this is the scenario that justified it:

1. On the phone, type new values into **SIP server, port and username**. Do not save.
2. Tap into the **password** field, so focus is elsewhere.
3. From a browser, open `http://<device-ip>:8080`, change something, and Apply.
4. Within a second or two, look at the phone.

**The three fields you typed must still hold your values.** Under the old code they were
silently replaced and would then have been persisted by your own Save. A plain focus check
would have protected only the password.

Then press Save and confirm the guard *releases*: change the same field from the browser
again and it should now repaint, since what is on screen is what is persisted.

### 4. The status header against a real call

All header logic was tested with synthetic snapshots. **The watchdog block has never been
rendered from a real finding.**

1. Place a call. The duration must **advance once a second** — if it freezes at a value, a
   derived clock read has been cached, which is the exact failure GW-45's design forbids.
2. Watch the grace marker appear and clear on an outbound GSM leg.
3. Confirm SIP / Call / Audio show as three separate lines with their own state colours.
4. Let the watchdog find something — the easiest is a SIP-side hangup that leaves the GSM leg
   up. The watchdog block should appear with a termination count and the finding text.
   Cross-check against `adb logcat | grep INVARIANT`; the screen and the log must agree.
5. Stop the gateway: the header must show the disconnected state, not stale values.

### 5. The SoC profile spinner — both devices, explicitly

This is the only new control with a path into the audio bridge, and picking wrong drives the
wrong ALSA mixer controls entirely.

On **merlinx** set it to MediaTek, on **lavender** to Qualcomm, restart the gateway, and
confirm from logcat that the expected profile loaded:

```
adb -s <dev> logcat -d | grep -E 'Audio profile (forced|auto-detected)'
```

Then set both back to `auto` and confirm auto-detection still picks correctly. Place one call
on each afterwards to confirm the audio bridge still works — a wrong profile is silent
breakage, not a crash.

### 6. Icons (GW-44)

- **Launcher icon** on both devices: home screen, app drawer, recents, and Settings → Apps.
- **Notification icons** — the failure mode is specific: a **solid white blob** in the status
  bar means the alpha-mask rule was got wrong. Check *both* the collapsed status-bar icon and
  the expanded shade, for both the gateway notification and the battery-limit one. They should
  be distinguishable from each other.
- The battery notification icon deliberately no longer says "low battery" — that service caps
  charging, it does not report a low cell.

### 7. Interaction checks that only a hand can make

- **Collapsible sections**: are four collapsed rows readable as navigable, or do they look
  dead? The chevrons are text glyphs (`▾`/`▸`), not drawables — legible?
- **Header height** on the smaller screen, and at maximum font scale
  (Settings → Display → Font size). The header is ~8 rows and is pinned; if it eats the
  screen on a 5-inch device, say so.
- **Keyboard**: expand a section, tap its last field, and check the keyboard does not cover
  the Save button.
- **Spinner popups in dark mode** — GW-41 wrote new item layouts naming `gw_on_surface` on
  `gw_surface_variant`. This is the "popup blindness" class from §0 and it is now in our own
  code. Open every spinner in night mode.

### 8. Still not verifiable here

- **The whole API 23–25 launcher path.** Ten PNGs exist and are valid files; no device on the
  bench will ever load one, because both are API 26+. Needs an Android 6/7 device or emulator.
- **The adaptive icon under a real launcher mask** — circle, squircle, teardrop. The safe-zone
  maths says nothing clips; that is arithmetic, not observation.
- **`android:roundIcon` actually being preferred** by an OEM launcher that reads it.
- **`<monochrome>` / Android 13+ themed icons.** Reasoned safe to ignore below 13; never seen.
- **Contrast and legibility at arm's length**, in both themes. The `gw_state_*` tokens are
  tested for existence, never for readability.

## Wave 3 — `phase-4-wave-3` (GW-42)

468 tests, 0 failures, lint green (baseline not regenerated), `assembleDebug` green.
Nothing on hardware.

**Test this on a phone you are willing to re-provision.** The wizard grants permissions and
changes the default dialer role. Changing that role stops `GatewayInCallService` binding — if
a device stops answering GSM calls during this section, check the role before assuming a UI
bug.

The wizard is `exported="false"`. To launch it directly:
```
adb -s <dev> shell su -c 'am start -n org.onetwoone.gateway/.ui.setup.SetupActivity'
```

### 1. The stranding test — run this before anything else

The single thing this issue exists to prevent is a half-provisioned phone standing behind a
wizard it cannot get past.

1. Fresh install, or clear `setup_completed`. Launch the app. The wizard should appear **over**
   a drawn main screen, not instead of it.
2. Press **Skip** on all five steps. You must reach the end and land on a usable console.
3. Kill and relaunch. **The wizard must not reappear** — skipping counts as dismissal.
4. Repeat, but press **Close** on step 1. Same result: usable console, no reappearance.
5. Repeat, but press **system back** on step 1. Same result.
6. Re-run it from the System section. It must open every time, unconditionally.

If any of these leaves you unable to reach the main screen, stop and report — nothing else in
this section matters.

### 2. Re-running must not wipe a working gateway

On a **configured** device, with a known-good SIP account:

1. Note the current server, port, user and SIM destinations.
2. Re-run the wizard from the System section.
3. On the account step, confirm every field is **pre-filled** with the stored values and the
   callout names the existing `user@server`.
4. **Clear the password box**, press Next, `force-stop`, relaunch.
5. The stored password must have survived — blank means "keep", not "clear".
6. Re-run again, press **Skip** on the account step, and confirm nothing at all changed.
7. Confirm the gateway still registers afterwards.

The JVM tests prove the merge logic. Only the force-stop proves the preference.

### 3. The dialer step — the one that can silently invert

`PermissionState.isDefaultDialer` was dead code until this wave: declared, defaulted `false`,
never written. It is now answered from `TelecomManager`. **If that read misbehaves on an OEM
build, the step tells a correctly-provisioned phone that it is not the dialer.**

Cross-check the step's verdict against the system on both devices:
```
adb -s <dev> shell cmd role get-role-holders android.app.role.DIALER
```
The screen and that command must agree, both before and after using the step's own button.

Also: root is re-checked uncached on each visit, deliberately, so that granting in Magisk
mid-wizard is picked up. Confirm that works — deny root, reach step 1, grant in Magisk,
re-check, and see it flip.

The doze-whitelist button is new and has **never been pressed on either bench phone**.
`disableBatteryOptimizationAsync`'s own javadoc warns it can freeze some devices. Press it
deliberately, with the phone in a state you can recover.

### 4. The verification step, and re-testing its central assumption

**Re-test the `*43` claim on the live PBX first.** The whole step is designed around the trunk
being unable to dial feature codes (`from-gsm-gateway` rejects them). If that context has since
been changed, the callout is now misleading and the fallback is unnecessary.

Then:
1. On a fresh handset, confirm the default destination is **not** `*43` — it should fall
   through to the SIM1 destination. The shipped `GatewayConfig` default *is* `*43`, and a test
   guards that it never leaks through; confirm on glass.
2. Type a `*` or `#` destination deliberately. A callout should explain the limitation, and
   the call button and Next must **stay enabled**. It informs, it does not block.
3. Confirm the SIM1 default is actually dialable *outbound* from the trunk. It should be — it
   is where inbound GSM calls already go — but that direction has never been exercised.
4. Run the call. The transcript is shown beside the verdict; confirm the markers `verdictOf`
   looks for actually appear against this FreePBX.
5. Fail a call on purpose (bad destination), then retry with a good one.
   `SipTestCallManager` refuses a start while a call is live — confirm the second attempt is
   accepted once the first tears down, and that Hang Up releases it.
6. Confirm the registration chip and the call verdict are visibly **separate claims**, and
   that the "this cannot confirm audio" callout is present and legible.

Mode is `tone`, not `bridge` — no GSM leg, no ALSA tap. That is deliberate: it exercises
exactly the part a handset can check while commissioning.

### 5. Presentation

- **Header height** on the 5-inch panel and at maximum font scale. The app bar was removed to
  buy back ~56dp but the summary strings are long, and this has never been laid out on glass.
- **Both themes** for the two new drawables (`gw_panel`, `gw_callout`) and the four chip
  states. `gw_primary_container` behind `gw_on_primary_container` has never been read at night.
- **Rotation mid-wizard**: step, outcomes and destination survive; **unsaved typing in the
  account form does not** — the `FormGuard` dirty set is per-activity, the same as the main
  screen. Confirm that is tolerable rather than surprising. If it is surprising, it is a
  finding.

### 6. Not verifiable here

- That `reloadConfig()` from the wizard both re-registers with the new account **and** bumps
  the config generation so the main screen behind it repaints within a second (GW-14's path).
  Nothing binds the service in Robolectric.
- Whether the wizard opening over `MainActivity` reads as a wizard over a console, or as a
  flash of the wrong screen.
