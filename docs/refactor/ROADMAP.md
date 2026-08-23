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

### Phase 0 — Stop the bleeding (no architectural change)

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

### Phase 2 — Correctness & resource hygiene

| Issue | Fixes |
|---|---|
| [GW-20](issues/GW-20-root-helper.md) | H1 — serialized root shell, safe output capture |
| [GW-21](issues/GW-21-sms-off-main.md) | G1 — SMS pipeline off the main thread |
| [GW-22](issues/GW-22-pjsip-object-lifetime.md) | H7 — deletion policy for pjsua2 objects |
| [GW-23](issues/GW-23-rt-audio-path.md) | H2, H3 — bulk JNI copy, no per-frame allocation |
| [GW-24](issues/GW-24-config-consistency.md) | H4 — key mismatch, atomic prefs writes |
| [GW-25](issues/GW-25-watchdog-invariants.md) | H9 — both orphan directions + fail-safe deadlines |
| [GW-26](issues/GW-26-service-lifecycle.md) | G2, H8 — non-blocking shutdown, guarded teardown |

### Phase 3 — Hardening

| Issue | Fixes |
|---|---|
| [GW-30](issues/GW-30-exported-surface.md) | S1, S2 — permission-gate the control receiver, auth the web server |
| [GW-31](issues/GW-31-remove-footguns.md) | E3, H10 — delete dead code that violates project rules |
| [GW-32](issues/GW-32-concurrency-tests.md) | Regression harness for the state machine and the native layer |

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

Phase 3:  GW-30, GW-31 — anytime
          GW-32 — after Phase 1
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
