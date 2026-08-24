# Refactoring Roadmap — concurrency, crash-safety, lifecycle

Companion to [AUDIT.md](AUDIT.md). Read the audit first: every issue here cites findings
from it.

---

## 1. The decision that is missing

The app has eleven threads and no owner for call state. Every P1 finding in the audit is
a restatement of that. So the roadmap is not "fix 35 bugs" — it is:

> **Give call/audio/SIP lifecycle state exactly one owning thread, make every entry point
> post onto it, and make the two paths that genuinely cannot post (the pjmedia RT thread
> and the native ALSA layer) explicitly safe.**

Everything else is consequence or hygiene.

### Target model

```
┌──────────────────────────────────────────────────────────────────────┐
│ pjsua workers │ Telecom/main │ NanoHTTPD │ broadcast │ UI            │
│      │              │             │           │          │           │
│      └──────────────┴─── post() ──┴───────────┴──────────┘           │
│                              ▼                                        │
│              ┌──────────────────────────────────┐                    │
│              │  GatewayControl (HandlerThread)  │  ← registered with │
│              │  ─ CallManager state machine     │    pjlib ONCE      │
│              │  ─ AudioBridgeManager wiring     │                    │
│              │  ─ GsmAudioPort open/close       │  may block freely  │
│              │  ─ SipAccountManager create/del  │  (root, network,   │
│              │  ─ reconnect / watchdog          │   ALSA retries)    │
│              └──────────────────────────────────┘                    │
│                                                                       │
│  ── separate, never posts, never blocks ───────────────────────────  │
│  pjmedia RT thread → onFrameRequested/Received → JNI → refcounted    │
│                                                     native PCM       │
└──────────────────────────────────────────────────────────────────────┘
```

**Why a dedicated `HandlerThread` and not the main looper.** These operations block for
seconds by nature: root shell-outs (~6 s for the mute preset), ALSA open with retry (up
to 10 s), SIP REGISTER/un-REGISTER, `hangupAllCalls`. Today they are split between main
(→ ANR, findings G1–G3) and ad-hoc bare threads (→ every race in section D–F). One
non-main serialising thread solves both.

**Why this also fixes the pjlib thread-registration mess (F2).** The control thread is
registered with pjlib exactly once at construction. `hasTransport()` stops registering
arbitrary short-lived callers, and the pool stops growing.

**Invariant to hold after Phase 1:** state fields in `CallManager`, `AudioBridgeManager`,
`GsmAudioPort` (lifecycle, not the RT counters), `SipAccountManager` and
`SipEndpointManager` are touched **only** on the control thread. Anything the UI or a
status poll needs is published through an immutable snapshot object.

---

## 2. Phases

Phases are ordered by risk-of-not-doing, and each phase leaves the app shippable.
Phase 0 lands independently of everything else — do not block it behind the refactor.

### Phase 0 — Stop the bleeding (no architectural change) — ✅ CODE COMPLETE, ⏳ UNVERIFIED ON DEVICE

All eight issues implemented and merged onto `refactor/phase-0`.
**103 tests across 10 suites, 0 failures; `assembleDebug` and `lintDebug` green.**
**Nothing has run on the phone** — see [PHASE-0-VERIFICATION.md](PHASE-0-VERIFICATION.md)
for the ordered plan, whose first step is a stop-gate on the charging escape hatch.

Four agents ran the revert-and-confirm-failure check on their own tests (GW-02, GW-04,
GW-06, and GW-05 partially). GW-04's produced the literal brick symptom
(`control not restored: DEC1 Volume expected:<84> but was:<0>`) and GW-02's produced
`ENUM EAR_S left muted`. Those are evidence; the rest is reasoning plus green tests.

Findings added *during* Phase 0, none of them fixed: B1b, B4b (process-kill leaves a mute
or a charging block with nothing to restore it), D1b (bridge can wire to a stale call),
D1c, F6b, H1b, H2b, H2c (raised to P1), H7b, H7c, H8b, H9b.

Targeted fixes for the crash / brick / lost-call findings. Small diffs, each testable
alone, each safe to ship on its own.

| Issue | Fixes | Why now |
|---|---|---|
| [GW-01](issues/GW-01-native-pcm-lifetime.md) | A1, A2 | Native use-after-free on every mid-frame hangup |
| [GW-02](issues/GW-02-mute-lease.md) | B1, G3 | Device left permanently muted; 6 s main-thread block |
| [GW-03](issues/GW-03-incall-current-call.md) | C1, C2, C3 | NPE on hangup; second call silently orphaned |
| [GW-04](issues/GW-04-audio-profile-state.md) | B2 | Mic left muted after the call ends |
| [GW-05](issues/GW-05-charging-state-machine.md) | B4 | Charging left off → unattended device dies |
| [GW-06](issues/GW-06-outgoing-call-registration.md) | D2 | State machine wedged on a dead call |
| [GW-07](issues/GW-07-unsafe-publication.md) | H5, H6, C1 | Cross-thread visibility; cheap and enabling |
| [GW-08](issues/GW-08-capture-open-cancellation.md) | B3, E4 | Orphan MixerEnforce thread re-mutes forever |

**Exit criterion:** 30 consecutive scripted call cycles (in/out, both SIMs, hangup from
each side, hangup during ring, hangup during the 6 s mute window) with no native crash,
no leaked `MixerEnforce` thread, mic verifiably restored after each cycle.

### Phase 1 — Install the threading model

The structural work. Land GW-10 first; the rest are mechanical once it exists.

| Issue | Fixes |
|---|---|
| [GW-10](issues/GW-10-control-thread.md) | Introduces `GatewayControlThread`; posts all pjsua callbacks onto it |
| [GW-11](issues/GW-11-callmanager-single-threaded.md) | D1, D4 — state machine becomes single-threaded, explicit transition table |
| [GW-12](issues/GW-12-audio-bridge-generations.md) | E1, E2, E3 — bridge wiring owned by the control thread, generation-tagged |
| [GW-13](issues/GW-13-single-gsm-state-source.md) | D3 — one source of truth for GSM call state |
| [GW-14](issues/GW-14-reload-pipeline.md) | F5, F4 — sequenced reload, no `Thread.sleep` |
| [GW-15](issues/GW-15-endpoint-lifecycle.md) | F1, F2, F3, F6 — endpoint/account lifecycle and thread registration |

**Exit criterion:** a `@ControlThread` annotation + a debug-build assertion on every
state-mutating method, and the full Phase 0 call-cycle suite still green with the
assertion armed.

### Phase 2 — Correctness & resource hygiene — ✅ CLOSED (accepted 2026-08-24), 2 items carried forward

**361 tests, 0 failures; `assembleDebug` + `lintDebug` green.** Eight issues merged across
three tags (`phase-2-wave-1/2/3`) and validated on both handsets over 12 real calls —
results in [PHASE-2-VALIDATION.md](PHASE-2-VALIDATION.md).

**Closed by the owner's decision on 2026-08-24** — the gateway works in daily use and the
phase is not worth holding open. Closed is **not** the same as fully validated, so what was
accepted rather than verified is listed here instead of being quietly marked green.

**Carried forward, still open:**

1. **GW-23b — the phase's only P0, and a live cost, not a latent bug.** E5 was re-measured
   during validation at up to **52.12 s** between the SIP party hanging up and the GSM leg
   being released — that is billed airtime on every SIP-side hangup, today. Its two hardware
   gates were never attempted. Accepting the phase does not make this stop.
2. **Four exit criteria were never met** (§5 of the plan) — recorded as unmeasured, not as
   passed:
   - **GW-22's 500-cycle soak was not run at all.** 12 calls is not a soak, and no
     native-heap slope was taken. Nothing is known about the graveyard under load.
   - **GW-24's mute-selection path is untested** — the web-UI selection, the call, and the
     migration's idempotency.
   - **GW-25's false-positive run was cut from 30 calls to 12**, deliberately, to avoid
     tripping carrier abuse heuristics. The rules are deterministic per tick so the
     reduction is defensible, but it is a deviation, not a pass.
   - **B1e passed its native-read cross-check** (12 agreed, 0 mismatched) but **not** with a
     populated custom mute list, and the "mic verifiably live after the call" half was never
     checked at all.

If any of these is ever needed as evidence, it must be run — not cited from here.

What **is** settled on hardware: GW-27 against the real 8-message fixture (zero re-forwards
across two restarts), GW-23a on **both** SoCs (`bulkCopy=true`, two-way audio confirmed by
the operator, `captureErr=0/playbackErr=0`), GW-26's teardown and user-stop latch, GW-21's
thread ownership and debounce, and zero `ILLEGAL TRANSITION` / assertion failures /
tombstones across every run.

Two new findings came out of the validation, both false positives that inflate
`watchdog_terminations` — **H18** (silent-bridge fires on any SIP leg ringing >12 s) and
**H19** (D6 fires inside the `DISCONNECTING` window). Neither harms a call today; together
they corrupt the counter that is supposed to decide whether the watchdog may ever act
automatically.

**Execution plan: [PHASE-2-PLAN.md](PHASE-2-PLAN.md)** — it overrides the briefs below
wherever they disagree. Roughly a third of their required-change items are already done,
several are factually false, and three carry hazards that would cause on-device damage if
implemented as written.

| Issue | Fixes |
|---|---|
| [GW-20](issues/GW-20-root-helper.md) | ✅ H1 — serialized root shell, safe output capture |
| [GW-21](issues/GW-21-sms-off-main.md) | ✅ G1, H12 — SMS pipeline off the main thread, one owner for the suppression state |
| [GW-22](issues/GW-22-pjsip-object-lifetime.md) | ✅ H7 — deletion policy for pjsua2 objects |
| [GW-23a](issues/GW-23-rt-audio-path.md) | ✅ H2, H3 — bulk JNI copy, no per-frame allocation |
| [GW-23b](issues/GW-23b-e5-io-thread.md) | ⏳ **E5 (P0) — CARRIED FORWARD, still open.** Dedicated I/O thread + ring buffer. **Gated on G1/G2**, see that issue §3. Costs up to 52 s of billed GSM per SIP-side hangup until it lands |
| [GW-24](issues/GW-24-config-consistency.md) | ✅ H4 — key mismatch, atomic prefs writes |
| [GW-25](issues/GW-25-watchdog-invariants.md) | ✅ H9 — both orphan directions + fail-safe deadlines |
| [GW-26](issues/GW-26-service-lifecycle.md) | ✅ G2, H8, H8c, H11, F6c, H15 — cancellable shutdown, guarded teardown, deliberate restart |
| [GW-27](issues/GW-27-sms-duplicate-suppression.md) | ✅ H13 — the whole inbox is re-forwarded on every restart |

### Phase 3 — Hardening

| Issue | Fixes |
|---|---|
| [GW-30](issues/GW-30-exported-surface.md) | S1, S2 — permission-gate the control receiver, auth the web server |
| [GW-28](issues/GW-28-reload-gives-up-permanently.md) | H14 — a reload with no endpoint stops the gateway for good |
| [GW-31](issues/GW-31-remove-footguns.md) | E3, H10 — delete dead code that violates project rules |
| [GW-32](issues/GW-32-concurrency-tests.md) | Regression harness for the state machine and the native layer |
| *(unfiled)* | **H18, H19** — two watchdog false positives found during Phase 2 validation. Both cosmetic today; both inflate `watchdog_terminations`, so fix them **before** any decision to let a watchdog rule auto-terminate |

---

### Phase 4 — UI: from debug harness to appliance console

### ✅ CODE COMPLETE, ⏳ UNVERIFIED ON DEVICE

All five wave issues implemented and merged onto `refactor/phase-4-ui`, in three tagged waves
(`phase-4-wave-1/2/3`), based on Phase 2's tip `b3b2c0e`.
**468 tests, 0 failures/errors/skipped, both variants; `lintDebug` green with the baseline
never regenerated; `assembleDebug` green including the native CMake build.**
**Nothing has run on a phone**, and unlike Phases 0–2 there is no automated layer beneath the
view code at all — see [PHASE-4-VALIDATION.md](PHASE-4-VALIDATION.md), where *that document is
the only test*.

| Wave | Issues | Tests | APK |
|---|---|---|---|
| `phase-4-wave-1` | GW-40, GW-45 | 361 → 385 | `wave-1-ebf8eee.apk` |
| `phase-4-wave-2` | GW-41, GW-44 | 385 → 410 | `wave-2-91a8573.apk` |
| `phase-4-wave-3` | GW-42 | 410 → **468** | `wave-3-597c252.apk` |
| `phase-4-wave-3b` | *(post-wave)* the status header scrolls instead of being pinned | 468 → **469** | `wave-3b-b40e111.apk` |

`phase-4-wave-3b` is not a wave. It is one owner-requested change made after wave 3 was
tagged, kept as its own tag so the wave-3 record stays what was actually reviewed and merged.
It is the build on both handsets.

Findings raised *during* Phase 4, none of them fixed: **H17** (the SMS send outcome has no
single thread — `SmsHandler:1127` is on the control thread while `:1210`/`:1224`/`:1229` are on
main), **H20** (SoC auto-detection is locale-sensitive; a Turkish or Azerbaijani device loads
the Qualcomm profile on a MediaTek phone). GW-46 is filed and deferred; GW-43 is specified and
gated.

**Execution plan: [PHASE-4-PLAN.md](PHASE-4-PLAN.md)** — it overrides
[PHASE-4-UI-PLAN.md](PHASE-4-UI-PLAN.md) below wherever they disagree. That plan was measured
at `c0255dd`, before Phase 2 landed; its diagnosis holds but nine of its facts do not, and one
of them changes the sequencing.

Almost presentation-only. GW-45 is the exception and it is not optional.

| Issue | Fixes |
|---|---|
| GW-40 | Design system foundation — Material Components, palette, `values-night`, typography, extract **68** hardcoded strings (not 43) |
| [GW-45](issues/GW-45-status-surface.md) | **The UI cannot reach the status it shows.** `MainViewModel` publishes `LiveData<GatewayStatus>` instead of flattening the snapshot to a three-line String |
| GW-41 | Status-first main screen; decompose `MainActivity` (580 lines) and `MainViewModel` (**666** lines) |
| GW-44 | Adaptive icon, density buckets, notification icons (**three** stock ones, not one), app label |
| GW-42 | First-run commissioning wizard: root → permissions → dialer role → SIP account → verification call |
| GW-43 | Web interface redesign — **GATED on GW-30**, see below |

**Wave graph** (base `b3b2c0e`): wave 1 = GW-40 + GW-45 · wave 2 = GW-41 + GW-44 ·
wave 3 = GW-42 · GW-43 gated.

**Why GW-45 exists.** The old plan justified Phase 4 being presentation-only on the claim that
`MainViewModel` "already exposes everything through LiveData". It does not: it reads the
immutable snapshot and keeps three fields, one of them `getStatusText()` — a pre-formatted
composite its own javadoc describes as *"the three-line composite the UI has always shown"*.
Call state, duration, grace period, the GW-22 call counters and the whole GW-25
`WatchdogFindings` block are unreachable. A status-first screen had no data source.

**Why GW-43 is gated.** `/api/config` still returns `sip_password` in cleartext from an
unauthenticated `0.0.0.0:8080` server — AUDIT **S2**, live at `b3b2c0e`. Phase 2 removed the
*fiction* from that response (it used to publish hardcoded fake credentials) but not the
exposure. A better-looking unauthenticated config endpoint invites more use of it. Same
pattern as GW-23b in Phase 2: specified, not landed.

**The theme is not merely unstyled — it is empty.** `Theme.AppCompat.Light.DarkActionBar`
with no body, a `colors.xml` holding only launcher-icon colours, one 565-line layout, and
no Material Components dependency at all. See the plan's §1 table.

**Hard constraint carried from §4.1:** `targetSdkVersion` stays 27. `compileSdkVersion` is
36, so Material Components compiles and AppCompat handles dark mode regardless — no UI
feature justifies raising it. Material 3 *dynamic colour* needs API 31 at runtime and is
therefore out of scope.

---

## 3. Sequencing and parallelism

```
Phase 0:  GW-01 ─┐
          GW-02 ─┤
          GW-03 ─┤  all independent — run in parallel
          GW-04 ─┤
          GW-05 ─┤
          GW-06 ─┤
          GW-07 ─┤  (GW-07 touches many files; land it LAST in Phase 0
          GW-08 ─┘   to avoid conflicts with the others)

Phase 1:  GW-10 ──┬─→ GW-11 ─┐
                  ├─→ GW-12 ─┤
                  ├─→ GW-13 ─┼─→ (all merge before Phase 1 exit criterion)
                  ├─→ GW-14 ─┤
                  └─→ GW-15 ─┘

Phase 2:  GW-20, GW-21, GW-22, GW-23, GW-24 — independent, parallel
          GW-25, GW-26 — after GW-10
          GW-27 — after GW-20 (consumes its execRoot result contract)

  ** SUPERSEDED. Phase 2 is a dense mesh around PjsipSipService.java, not a flat
     fan-out. See PHASE-2-PLAN.md section 4 for the real wave graph and the five
     hard ordering constraints (GW-20->GW-24, GW-20->GW-27, GW-22->GW-25,
     GW-26->GW-21, GW-01->GW-23b). **

Phase 3:  GW-30, GW-31 — anytime
          GW-32 — after Phase 1

Phase 4:  wave 1  GW-40 (design system)  +  GW-45 (status surface)   — disjoint
          wave 2  GW-41 (main screen)    +  GW-44 (icon/branding)    — disjoint
          wave 3  GW-42 (commissioning wizard)                       — alone
          GATED   GW-43 (web redesign)   — blocked on GW-30 / S2

  GW-40 and GW-44 are split across waves only because both would edit adjacent
  attributes of the manifest's <application> tag (android:theme vs android:icon).
  GW-45 is a prerequisite for GW-41, not a nicety — see PHASE-4-PLAN.md §2 C1.
```

**Conflict hot-spots** — do not assign two agents to these simultaneously:
- `PjsipSipService.java` — touched by GW-02, GW-06, GW-07, GW-10, GW-14, GW-15, GW-26
- `GsmAudioPort.java` — GW-01, GW-08, GW-12, GW-23
- `CallManager.java` — GW-06, GW-07, GW-11

Recommended: run each agent in its own worktree (`isolation: "worktree"`) and merge in
the order listed.

---

## 4. Rules for every agent working this plan

1. **Do not raise `targetSdkVersion`** (currently 27, deliberate — privileged telephony
   behaviour depends on it). Do not touch `app/src/main/java/org/pjsip/pjsua2/**` —
   those are SWIG-generated and vendored.
2. **Never mute the mic via `AudioManager`.** Mic mute goes through the ALSA mixer only.
   See `CLAUDE.md`.
3. **The pjmedia RT callbacks must never block, allocate unboundedly, or take a lock the
   control thread holds across I/O.** If a change would make `onFrameRequested` wait on
   anything the control thread can hold for >1 ms, it is wrong.
4. **`pjmedia` assertion failures are `abort()`, not exceptions.** A `try/catch` around a
   conference-port operation proves nothing. Prove liveness before the call, on the same
   thread, with no window in between.
5. Verify with `./gradlew test` and `./gradlew lintDebug` (only *new* lint issues fail —
   pre-existing ones are baselined in `app/lint-baseline.xml`).
6. On-device verification: `./gradlew assembleDebug` then `/deploy`. Use the SDK's `adb`,
   not the host copy. App broadcasts need an explicit `-p org.onetwoone.gateway`.
7. **Do not read `/proc/asound/*/status` (PCM status) during an active call** on the
   Redmi Note 9 test device — it kernel-panics. `tinymix` and `hw_params` are safe.
8. Keep each issue's diff scoped to that issue. If you find a new defect, add it to
   AUDIT.md and open a new issue file — do not fold it into an unrelated change.
9. Every fix that closes an audit finding must state, in the commit body, which finding
   ID it closes and how it was verified.

---

## 5. What this plan deliberately does not do

- **No rewrite.** The manager decomposition (`SipEndpointManager` / `SipAccountManager` /
  `CallManager` / `AudioBridgeManager`) is sound; only its thread discipline is missing.
- **No coroutines / RxJava / DI framework.** A `HandlerThread` plus posts is sufficient,
  matches the existing Android idiom in this codebase, and adds no dependency.
- **No change to the PJSIP build or the vendored bindings.**
- **No change to the ALSA routing topology** — the Qualcomm and MediaTek profiles are
  hard-won reverse-engineering (see the MT6768 recipe). Only their *state handling* is in
  scope, never their control names or ordering.
