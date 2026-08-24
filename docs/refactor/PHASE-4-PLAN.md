# Phase 4 — UI execution plan

**This document overrides [PHASE-4-UI-PLAN.md](PHASE-4-UI-PLAN.md) wherever they disagree.**
That plan was written 2026-08-24 while Phase 1 wave 2 was in flight, measured at `c0255dd`.
Phase 2 has landed since. Its diagnosis is still right; several of its load-bearing facts
are not, and one of them invalidates the sequencing.

Written 2026-08-24 against `refactor/phase-2` @ `b3b2c0e`.
Base for all Phase 4 work: **`b3b2c0e`** (Phase 2 tip — 361 tests, lint clean, native build
green, nothing validated on hardware).

---

## 1. What changed under the old plan

`MainActivity.java` and everything under `res/` were **not touched** by Phase 2, so the layout
diagnosis stands. These did change, and they are the ones the plan reasons about:

| File | Phase 2 delta | Consequence for Phase 4 |
|---|---|---|
| `core/GatewayStatus.java` | +210 lines (GW-22 call counters, GW-25 `WatchdogFindings`) | Far more is now *available* to show than the UI shows |
| `ui/MainViewModel.java` | +54 (GW-14 generation poll, GW-24 config routing) | 616 → **666** lines |
| `ui/TinymixManager.java` | +136 (GW-20 bounded root) | No longer a main-thread hazard |
| `ui/PermissionManager.java` | −47 (GW-20 bounded root) | No longer a main-thread hazard |
| `WebConfigServer.java` | +129/−116 (GW-24 config ownership) | Still unauthenticated; still returns the password |

---

## 2. Corrections to the old plan

### C1 — "MainViewModel already exposes everything through LiveData" is false. *(load-bearing)*

The old plan's §1 closes with this as the reason Phase 4 is tractable rather than a rewrite:

> `MainViewModel` already exposes everything through LiveData, and Phase 1 gave it an
> immutable `GatewayStatus` snapshot. The presentation layer is genuinely separable.

Half of that is true. `updateServiceState()` does read the immutable snapshot — and then
**throws almost all of it away**:

```java
GatewayStatus snapshot = pjsipService.getStatusSnapshot();
state.isRunning     = snapshot.isRunning();
state.isRegistered  = snapshot.isSipRegistered();
state.statusMessage = snapshot.getStatusText();   // "SIP: x\nCall: y\nAudio: z"
```

Three fields, one of them a pre-formatted three-line String. What the snapshot carries and
the UI cannot reach today:

`getCallState()` · `getCallStatus()` · `getAudioStatus()` · `getSipStatus()` (separately,
not glued into one String) · `getCallDurationMs()` · `isInGracePeriod()` · `getCallsCreated()`
· `getCallsDeleted()` · `getCallsAlive()` · `getConfigGeneration()` · and the whole of
`WatchdogFindings` — `getTerminations()`, `getSilentBridgeEpisodes()`, `getLastFinding()`,
`getLastFindingAtWallMs()`.

`getStatusText()`'s own javadoc calls it *"the three-line composite the UI has always shown"*.
It is a compatibility shim for the old screen, not a status API.

**A "status-first main screen" cannot be built on this.** GW-41 as written is a layout task
sitting on top of a data source that does not exist. That is not a layout problem and must
not be solved inside a 565-line layout rewrite.

→ **New issue GW-45**, wave 1, prerequisite for GW-41. See §4.

**The same defect has a second half, and it is not ours.** AUDIT **H16**, filed after this
plan's first draft: `GatewayStatus.toBundle()` — the flattened form built specifically for the
`GET_STATUS` broadcast — has no consumer either. `GatewayControlReceiver`'s handler is
`Log.i(TAG, "GET_STATUS not yet implemented")`. So the snapshot is computed in full and
discarded at *both* exits: the UI keeps three fields, and the broadcast keeps none.

That half belongs to **GW-30** (Phase 3), with an ordering constraint worth repeating here
because it also protects Phase 4's gate: **permission-gate the receiver before wiring the
broadcast.** `GatewayControlReceiver` is exported with no permission (S1), so implementing
`GET_STATUS` first would hand the gateway's full runtime state — registration, call state,
call counters, watchdog findings — to any app on the device.

GW-45 and H16 do not overlap in code. GW-45 is the in-process LiveData surface; H16 is the
`Bundle` on the exported receiver. Fixing either does not fix the other.

### C2 — String-extraction scope is 68 literals, not 43

Measured at `b3b2c0e`:

| Where | Count |
|---|---|
| `activity_main.xml` `android:text=` | 43 |
| `activity_main.xml` `android:hint=` | 11 |
| `activity_main.xml` `android:(text\|hint)="@string/…"` | **0** |
| `MainActivity.java` `setText("…")` / `Toast.makeText(…, "…")` | 4 |
| `MainViewModel.java` `toastMessage.setValue("…")` | 10 |
| **Total user-facing literals** | **68** |

~~`strings.xml` has 4 entries and three of them are unreferenced.~~ **Wrong — corrected
during GW-40.** `select_capture`, `select_playback` and `select_mixer` are all referenced,
from `android:prompt` at `activity_main.xml:365`, `:379` and `:393`. The "0 `@string` refs"
figure came from grepping only `android:text` and `android:hint`, which is the same narrow
measurement that produced the 43-vs-68 undercount above — `android:prompt` was never in the
pattern. All three are live and stay. The baseline's two `UnusedResources` entries are the
launcher colours, not these.

The general lesson, since this is twice now: **counting user-facing strings by attribute name
misses attributes you did not think of.** `android:prompt`, `android:contentDescription`,
`android:title` and `android:summary` all take user-visible text.

### C3 — The root-on-main-thread constraint is already satisfied

Old plan §3.4:

> `PermissionManager`, `AudioDeviceManager` and `TinymixManager` all spawn `su` with
> unbounded `waitFor`. That is AUDIT G1–G3 territory and the new UI must not widen it.

GW-20 fixed this. All three now hold a `newSingleThreadExecutor()` and go through
`RootHelper.run(…)`, which bounds every `waitFor` and drains both pipes.
`PermissionManager:123` even carries the note: *"All three root paths below went through a
bare Runtime.exec + unbounded waitFor()"* — past tense.

Restated as a rule: **keep it that way.** No new root call may run on the main thread, and
none may use a bare `Runtime.exec`. Go through `RootHelper`.

### C4 — The icon is worse than described, and adaptive icons have a minSdk cost

`ic_launcher.xml` is not a vector. It is a `<shape android:shape="oval">` with a solid
`#3F51B5` fill — a plain indigo circle, sized 48dp, in `drawable/`. There is no `mipmap/`
directory at all, no density buckets, and no `android:roundIcon` in the manifest.

`minSdkVersion` is 23, so an adaptive icon needs **both**:
- `mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml` (API 26+), and
- rasterised PNG buckets `mipmap-{m,h,xh,xxh,xxxh}dpi` for API 23–25, which get the *legacy*
  icon and will not be masked.

Shipping only the `-v26` variant leaves Android 6–7 with no launcher icon.

### C5 — Notification icons are stock framework drawables

Three call sites, all using `android.R.drawable`:

- `PjsipSipService.java:2418` — `ic_menu_call`
- `BatteryLimitService.java:876` and `:892` — `ic_lock_idle_low_battery`

The old plan mentioned only the first. All three belong to GW-44. Notification small icons
must be white-on-transparent silhouettes; a coloured drawable renders as a white blob.

### C6 — Agents do **not** inherit this branch. Every brief must pin its base.

`.claude/worktrees/` has no `worktree.baseRef` setting, so it defaults to `fresh`:
**a new worktree starts at `origin/main` (`7cbd7fd`)**, which predates Phases 0–2 entirely.
This was confirmed directly — the Phase 4 worktree was created at `7cbd7fd` and had to be
moved onto `b3b2c0e` by hand.

Every agent brief must therefore open with an explicit
`git checkout -B <branch> <base-sha>` and must declare its branch name back, so the merge
targets the branch the agent actually committed to. (Two Phase 2 agents silently worked on
their own branch; merging the worktree default reported "already up to date" and would have
landed nothing.)

### C7 — Lint: the baseline is a floor, and much of it is ours to clear

`app/lint-baseline.xml`, issues Phase 4 owns or must not worsen:

| Issue | Count | Phase 4 relevance |
|---|---|---|
| `HardcodedText` | 46 | GW-40 clears these |
| `Autofill` | 10 | 11 `EditText` with no `autofillHints` — GW-41 |
| `SetTextI18n` | 5 | `MainActivity` string concatenation |
| `ButtonStyle` | 5 | borderless-button grouping — GW-41 |
| `UnusedResources` | 2 | the three dead `strings.xml` entries |
| `SmallSp` | 2 | text below 12sp |
| `UseSwitchCompatOrMaterialXml` / `…Code` | 1 each | the raw `<Switch>` at layout:552 |
| `DisableBaselineAlignment` | 1 | a nested-weight `LinearLayout` |
| `ExpiredTargetSdkVersion` | 1 | **deliberate — targetSdk 27 stays. Leave baselined.** |

Only *new* issues fail the build. Fixing a baselined issue is safe; lint reports vanished
baseline entries as informational. **Do not regenerate the baseline** to paper over a new
issue — if a new issue appears, fix it.

### C8 — The app and the web interface expose different features

Neither surface is a subset of the other, which matters for GW-43 and for anyone reasoning
about "the config UI" as one thing:

| | App | Web |
|---|---|---|
| SoC profile (`auto`/`qualcomm`/`mediatek`) | ✗ | ✓ |
| Capture / playback device numbers | ✓ | ✗ |
| Incoming call mode (SIP-first / answer-first) | ✓ | ✗ |
| Battery charge limit | ✓ | ✗ |
| Test call + report | ✓ | ✗ |
| Verbose PJSIP log, DTMF relay | ✓ | ✗ |
| Mixer-control detection | ✓ | ✓ |
| **Any status at all** | ✓ | ✗ |

`audio_profile` is settable **only** from the web page. There is no way to pick the SoC
profile from the phone in your hand — on a device whose whole purpose is a SoC-specific
audio tap.

→ **GW-41 closes this half**: the SoC profile selector joins the audio section of the new
main screen. It is one spinner over a `GatewayConfig` key that already exists and is already
read on both surfaces, so it costs nothing beyond the control itself. The other direction
(status, call mode, battery limit, test call on the web page) waits for GW-43.

### C9 — Nothing in either UI mentions SMS

SMS is half the gateway's job, `SmsHandler` is the second-largest class in the app at 1262
lines, and GW-27 just fixed a user-visible SMS bug (the whole inbox re-forwarded on every
restart). Neither UI shows an SMS count, a last-forwarded timestamp, or a failure.

`GatewayStatus` carries no SMS fields either, so this is not a rendering gap — the data is
not published. See §4 for how far GW-45 goes and where the line is.

---

## 3. Scope decisions

### GW-43 (web redesign) stays **gated** behind GW-30

`/api/config` still returns `sip_password` in cleartext (`WebConfigServer.java:134`) from an
unauthenticated server bound to `0.0.0.0:8080`. That is AUDIT **S2**, and it is live at
`b3b2c0e` — Phase 2 corrected the *fiction* in that response (it used to publish hardcoded
fake credentials) but not the exposure.

The old plan's judgement holds and I am not overriding it: a better-looking unauthenticated
config endpoint invites more use of it. GW-43 lands after GW-30, in the same sitting.

This is the Phase 2 GW-23b pattern — the work is specified and not landed. **What unblocks
it:** GW-30's auth, at which point GW-43 is a pure asset change (`index.html`, `style.css`,
`config.js`) consuming GW-40's tokens, plus the §C8 asymmetry decisions.

*Not* folded into GW-43: masking the password in the API response. That is S2's fix and
belongs to GW-30, not to a presentation issue.

### Wizard scope is bounded by "skippable at every step"

GW-42's failure mode is stranding a half-provisioned phone. It must be skippable at every
step, re-runnable from settings, and must never gate the main screen. A wizard that blocks
is worse than no wizard on an appliance you may be recovering in the field.

---

## 4. The issues

| Issue | Owns (exclusively) | Depends on |
|---|---|---|
| **GW-40** Design system foundation | `app/build.gradle`, `res/values/**`, `res/values-night/**`, `AndroidManifest` *theme attr only*, string extraction inside `activity_main.xml` | — |
| **GW-45** Status surface | `ui/MainViewModel.java`, `core/GatewayStatus.java`, `ui/MainViewModelTest.java` | — |
| **GW-41** Status-first main screen | `res/layout/**`, `MainActivity.java`, `ui/MainViewModel.java` *(decomposition)* | GW-40, GW-45 |
| **GW-44** Icon and branding | `res/mipmap-**`, `res/drawable/ic_*`, `AndroidManifest` *icon attrs only*, the 3 `setSmallIcon` call sites | GW-40 (palette) |
| **GW-42** Commissioning wizard | new `ui/setup/**`, its layouts, `AndroidManifest` *new activity only* | GW-40, GW-41, GW-45 |
| **GW-43** Web redesign | `assets/index.html`, `style.css`, `config.js` | **GATED on GW-30** |

### GW-45 — the new one

**Problem:** §C1. The UI cannot render status it cannot reach.

**Required change:** `MainViewModel` publishes the snapshot itself —
`LiveData<GatewayStatus>` — and derives from it, rather than flattening to a String on the
way through.

**Constraints that make this safe:**

1. `GatewayStatus` is immutable and already the Phase-1/2 publication boundary. Publishing it
   whole is *more* correct than the current `ServiceState`, which is a mutable POJO with
   public fields handed out through LiveData.
2. **Keep `getServiceState()`, `getStatusText()` and `getIsRegistered()` working.**
   `MainActivity` has 11 observers and Phase 4 wave 2 rewrites it; wave 1 must not break it.
   Add the new surface, deprecate the old, delete it in GW-41 — not before.
3. `getCallDurationMs()` and `isInGracePeriod()` **re-read the clock on every call** by
   design (their javadoc says so — a frozen duration is a stopwatch that never advances).
   Bind them per-tick; never cache the derived value in the ViewModel.
4. `GatewayStatus.UNAVAILABLE` is the "service not connected" value. Use it instead of
   inventing a null-state; the "Service not connected" string is presentation.
5. `getTestCallReport()` stays **outside** the snapshot — deliberately, per PHASE-2-PLAN §2.7:
   it is a `StringBuilder` capped at 20 000 chars and copying it into every 1 Hz publish would
   make publish cost proportional to report length.

**SMS (§C9) — the line:** GW-45 may add SMS *counters* to `GatewayStatus` only if they can be
read from state `SmsHandler` already maintains, on the thread that already owns it, with no
new locking. If that is not true, **do not add them** — file it as GW-46 and leave the UI
without an SMS panel. An SMS counter is not worth a new cross-thread read in a codebase that
spent three phases removing them.

**Tests:** extend `MainViewModelTest` (4 tests today, all about the config-generation poll —
do not regress them). New coverage: snapshot published verbatim; `UNAVAILABLE` on unbind;
duration advances between two reads of the same snapshot; the generation poll still fires.

### GW-41 — hazards found in the existing wiring

`MainActivity` has 40 `findViewById` calls and 11 observers. Three of them are traps, and all
three are invisible unless you read the observer bodies:

**H-a. Hardcoded colours in Java, where the design system cannot reach them.**

```java
statusText.setTextColor(state.isRegistered ? 0xFF228B22 : 0xFFCC0000);
```

Forest green / brick red as literal ints in `setupObservers()`. GW-40 owns `res/values/**`
and cannot fix this; GW-41 must replace both with palette tokens. These are also the app's
*only* existing state colours — whatever GW-40 defines for registered/fault has to be at
least as legible as these were, in both themes.

**H-b. Only one text field is guarded against clobbering the user's typing.**

```java
viewModel.getManualMuteControls().observe(this, controls -> {
    if (controls != null && !manualMuteControlsEdit.hasFocus()) {   // <-- the guard
        manualMuteControlsEdit.setText(controls);
    }
});
```

The `getSipConfig()` observer sets **eight** fields — server, port, user, password, realm,
both SIM destinations — with no such guard. That observer fires whenever the config
generation changes, which now includes *a save from the web interface while someone is typing
on the phone*. GW-14 removed the activity relaunch precisely so the phone-holder's work
survives a remote save; this observer throws it away anyway, one field at a time.

GW-41 must apply the `hasFocus()` discipline (or a better one — a proper two-way binding that
does not write over a dirty field) to every rebindable input, not just this one.

**H-c. `getToastMessage()` is an event stream published through `LiveData`.**

```java
private final MutableLiveData<String> toastMessage = new MutableLiveData<>();
```

`LiveData` replays its last value to every new observer. On a configuration change — rotation,
theme switch, night-mode toggle, which GW-40 is about to make reachable — the last toast fires
again. It is latent today because nothing re-observes much; a restructured screen with
fragments or a night-mode toggle will surface it immediately. GW-41 needs a consume-once
event holder, not a `MutableLiveData<String>`.

**H-d. Mirrored selection state.** `MainActivity` keeps `selectedCard`,
`selectedCaptureDevice`, `selectedPlaybackDevice`, `selectedMixerRoute` as fields, seeded from
the `getAudioConfig()` observer and written back by four spinner listeners. Any decomposition
has to decide who owns that — it is view state today and belongs in the ViewModel.

---

## 5. Standing rules for every Phase 4 agent

1. **Base your branch explicitly.** First command:
   `git checkout -B <your-branch> <base-sha>`. Verify with
   `git merge-base --is-ancestor <base-sha> HEAD`. Report your branch name in your final
   message. Do not assume the worktree starts where you expect (§C6).
2. **`targetSdkVersion` stays 27.** `compileSdk` 36 and `minSdk` 23 mean Material Components
   compiles and AppCompat handles dark mode regardless. Material 3 *dynamic colour* needs API
   31 at runtime — out of scope, ship a fixed palette. No UI feature justifies raising
   targetSdk, ever.
3. **Do not touch** `app/src/main/java/org/pjsip/pjsua2/**`, ALSA control names or their
   ordering, `assets/tinymix*`, `assets/tinycap`, `assets/tinyplay` (those are **binaries**
   living next to the web assets), or any file another wave owns per §4.
4. **The UI reads `GatewayStatus`, never the managers.** Three phases exist to make lifecycle
   state single-threaded. If the snapshot lacks a field you need, add it to the snapshot —
   never add a live read of `CallManager` / `AudioBridgeManager` / `SipAccountManager`.
5. **No root call on the main thread, ever, and no bare `Runtime.exec`.** Go through
   `RootHelper` (§C3).
6. **Never mute the mic via `AudioManager`** — it breaks the `Incall_Music` ALSA playback
   path. Mixer only.
7. Verify with `./gradlew test` **and** `./gradlew lintDebug` from *your worktree root*.
   Both must be green. Only new lint issues fail; do not regenerate the baseline (§C7).
8. Keep the diff scoped to your issue. Found a new defect? Add it to `AUDIT.md` and open an
   issue file — do not fold it into an unrelated change.
9. State in the commit body which finding or issue it closes and how you verified it.
10. **You cannot verify this on hardware.** No instrumented tests exist and no phone is
    attached to your worktree. Say plainly what you could not check, and put it in
    `PHASE-4-VALIDATION.md` rather than implying it works.

---

## 6. Wave graph

```
base = b3b2c0e  (refactor/phase-2 tip)

wave 1   GW-40  design system      res/values, build.gradle, manifest[theme]
         GW-45  status surface     ui/MainViewModel, core/GatewayStatus
         └─ disjoint file sets; both are prerequisites for wave 2

wave 2   GW-41  main screen        res/layout, MainActivity, MainViewModel[decompose]
         GW-44  icon & branding    res/mipmap, manifest[icon], 3x setSmallIcon
         └─ disjoint; GW-41 consumes GW-40's tokens and GW-45's snapshot

wave 3   GW-42  commissioning wizard   new ui/setup/**, manifest[new activity]
         └─ alone; reuses GW-41's components

GATED    GW-43  web redesign       blocked on GW-30 (S2 live: unauthenticated
                                    0.0.0.0:8080 returning sip_password cleartext)
```

**Why GW-40 and GW-44 are not in the same wave**, though both are "styling": they would both
edit the `<application>` tag of `AndroidManifest.xml` — `android:theme` and
`android:icon`/`roundIcon` are adjacent attributes. Splitting them across waves makes the
merge trivial instead of a hand-resolved conflict.

**Why GW-45 is not folded into GW-41:** GW-45 is the only Phase 4 change that touches logic.
Landing it separately keeps it reviewable and testable on its own, instead of burying a
publication-boundary change inside a 565-line layout rewrite. This is the discipline that
made Phase 2's diffs reviewable.

---

## 7. Exit criteria

Phase 4 is code-complete when:

1. `./gradlew test` green, no regressions in the 361 Phase 2 tests, and new coverage for
   GW-45 and GW-42's step machine.
2. `./gradlew lintDebug` green with **no baseline regeneration**, and `HardcodedText` down
   from 46 toward 0.
3. `./gradlew assembleDebug` green including the native CMake build.
4. Every wave tagged `phase-4-wave-N` and independently checkout-able, with an APK stashed in
   `release-output/phase-4-waves/`.
5. `PHASE-4-VALIDATION.md` written, per wave, naming **what a regression looks like** — not
   just what to check.

Phase 4 is **done** only after on-device validation on both SoC profiles (merlinx / MT6768
and lavender / SDM660), in both themes, per that document. Nothing here has run on a phone.

---

## 8. Out of scope

- **Raising `targetSdkVersion`.** Not for dark mode, not for adaptive icons, not for
  Material 3 dynamic colour.
- **Any change to call/audio/SIP lifecycle.** GW-45 adds a *publication* of state that is
  already computed; it changes no lifecycle.
- **Rewriting the ALSA routing topology or its control names.**
- **Landing GW-30's auth.** Phase 3 owns it. Phase 4 gates on it.
- **Localisation.** Extract strings into `strings.xml` so it becomes possible; ship English.
- **Instrumented tests.** None exist and adding an instrumentation harness is its own project.
