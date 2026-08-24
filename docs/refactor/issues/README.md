# Issue index

Agent-ready work items for the [refactoring roadmap](../ROADMAP.md).
Evidence and failure scenarios: [AUDIT.md](../AUDIT.md).

Every issue file has the same shape: **Problem → Failure scenario → Required change →
Acceptance criteria → Verification → Risk**. Read the roadmap's §4 rules before starting
any of them.

## Phase 0 — Stop the bleeding

Independent, small, individually shippable. `GW-07` lands last (wide diff).

| ID | Title | Sev | Files |
|---|---|---|---|
| [GW-01](GW-01-native-pcm-lifetime.md) | Native PCM lifetime: `pcm_close()` frees memory the RT thread is reading | P0 | `cpp/gsm_audio_jni.c` |
| [GW-02](GW-02-mute-lease.md) | Mute applied after hangup leaves the phone permanently muted | P0 | `PjsipSipService`, `DeviceMuteManager` |
| [GW-03](GW-03-incall-current-call.md) | `currentCall` TOCTOU: NPE on hangup, second call orphaned | P0 | `GatewayInCallService` |
| [GW-04](GW-04-audio-profile-state.md) | `AudioProfile` original-value maps race → mic left muted | P0 | `audio/*Profile` |
| [GW-05](GW-05-charging-state-machine.md) | Charging can be left disabled → unattended gateway dies | P0 | `BatteryLimitService` |
| [GW-06](GW-06-outgoing-call-registration.md) | Outgoing call registered after it is placed | P1 | `PjsipSipService`, `CallManager` |
| [GW-07](GW-07-unsafe-publication.md) | Unsafe publication / cross-thread visibility | P1 | many |
| [GW-08](GW-08-capture-open-cancellation.md) | Cancelled ALSA open re-arms capture, leaks `MixerEnforce` | P0 | `GsmAudioPort` |

## Phase 1 — Install the threading model

`GW-10` first, alone. The rest are mechanical once it exists.

| ID | Title | Sev | Depends |
|---|---|---|---|
| [GW-10](GW-10-control-thread.md) | Introduce `GatewayControlThread` | P1 | Phase 0 |
| [GW-11](GW-11-callmanager-single-threaded.md) | `CallManager` single-threaded + transition table | P1 | GW-10 |
| [GW-12](GW-12-audio-bridge-generations.md) | Audio bridge: control-thread ownership, generation-tagged wiring | P1 | GW-10, GW-08 |
| [GW-13](GW-13-single-gsm-state-source.md) | One source of truth for GSM call state | P1 | GW-10 |
| [GW-14](GW-14-reload-pipeline.md) | Sequenced reload pipeline, no `Thread.sleep` | P1 | GW-10 |
| [GW-15](GW-15-endpoint-lifecycle.md) | Endpoint lifecycle, thread-registration leak, main-thread init | P1 | GW-10 |

## Phase 2 — Correctness & resource hygiene

| ID | Title | Sev | Depends |
|---|---|---|---|
| [GW-20](GW-20-root-helper.md) | `RootHelper`: unsafe output capture, thread churn | P2 | — |
| [GW-21](GW-21-sms-off-main.md) | SMS pipeline blocks main; retry can double-send | P1 | GW-10 |
| [GW-22](GW-22-pjsip-object-lifetime.md) | pjsua2 object lifetime: unbounded leak per call | P2 | GW-11, GW-12 |
| [GW-23](GW-23-rt-audio-path.md) | RT audio path: 16k JNI round-trips/s, `malloc` per frame | P2 | GW-01 |
| [GW-24](GW-24-config-consistency.md) | Config key mismatch; audio config never reloadable | P1 | — |
| [GW-25](GW-25-watchdog-invariants.md) | Watchdog misses the reverse orphan; no fail-safe deadlines | P1 | GW-10/11/13 |
| [GW-26](GW-26-service-lifecycle.md) | Blocking shutdown, unguarded teardown, restart interplay | P1 | GW-10 |
| [GW-27](GW-27-sms-duplicate-suppression.md) | The whole SMS inbox is re-forwarded on every restart | P1 | GW-20 |

`GW-23` was split during execution: **23a** (bulk JNI copy, no per-frame `malloc`) shipped;
**23b** (the E5 ring buffer, P0) is **gated on hardware** — see `PHASE-2-PLAN.md` §2.4.

## Phase 3 — Hardening

| ID | Title | Sev | Depends |
|---|---|---|---|
| [GW-30](GW-30-exported-surface.md) | Unauthenticated control receiver and web config; wire `GET_STATUS` (H16) | P1 | — |
| [GW-28](GW-28-reload-gives-up-permanently.md) | A reload with no endpoint stops the gateway for good | P1 | GW-14 |
| [GW-31](GW-31-remove-footguns.md) | Delete dead code that violates project rules | P2 | GW-12 |
| [GW-32](GW-32-concurrency-tests.md) | Regression harness so the model does not erode | P2 | GW-10, GW-11 |

## Phase 4 — UI: from debug harness to appliance console

Execution plan: [PHASE-4-PLAN.md](../PHASE-4-PLAN.md), which overrides the older
[PHASE-4-UI-PLAN.md](../PHASE-4-UI-PLAN.md). Almost presentation-only — **GW-45 is the
exception and it is a prerequisite, not a nicety.**

| ID | Title | Sev | Depends |
|---|---|---|---|
| GW-40 | Design system foundation — Material, palette, `values-night`, 68 strings | — | — |
| [GW-45](GW-45-status-surface.md) | The UI cannot reach the status it shows | P2 | — |
| GW-41 | Status-first main screen; decompose `MainActivity` / `MainViewModel` | — | GW-40, GW-45 |
| GW-44 | Adaptive icon, density buckets, three stock notification icons | — | GW-40 |
| GW-42 | First-run commissioning wizard | — | GW-40, GW-41, GW-45 |
| GW-43 | Web interface redesign | — | **GATED on GW-30** |
| [GW-46](GW-46-sms-status.md) | SMS is unobservable on the device | P2 | GW-21 |

**GW-43 is gated**, not merely sequenced: `/api/config` returns `sip_password` in cleartext
from an unauthenticated `0.0.0.0:8080` server (**S2**). A better-looking unauthenticated
config endpoint invites more use of it.

**GW-46 is deferred** and in no wave. SMS counters need new counters *and* a new cross-thread
hand-off — half the send-outcome path still dispatches on main (**H17**).

## Conflict map

Do not assign two agents to the same file simultaneously:

- `PjsipSipService.java` — GW-02, GW-06, GW-07, GW-10, GW-13, GW-14, GW-15, GW-21, GW-25, GW-26
- `GsmAudioPort.java` — GW-01, GW-08, GW-12, GW-23
- `CallManager.java` — GW-06, GW-07, GW-11, GW-25
- `AudioBridgeManager.java` — GW-12, GW-22, GW-31
- `WebConfigServer.java` — GW-24, GW-30, GW-43
- `cpp/gsm_audio_jni.c` — GW-01, GW-23
- `ui/MainViewModel.java` — GW-14, GW-24, GW-45, GW-41
- `AndroidManifest.xml` — GW-40 (`android:theme`), GW-44 (`android:icon`), GW-42 (new
  activity), GW-30 (receiver permission). Four issues, four different attributes of the same
  `<application>` tag — this is why Phase 4 splits GW-40 and GW-44 across waves.
- `res/values/colors.xml` — GW-40 owns it outright; GW-44 *references* `ic_launcher_*` and
  must not edit the file.

Prefer one worktree per agent, merged in issue-number order within each phase.

**Pin the base in every brief.** A fresh agent worktree starts at `origin/main`, not at the
branch you are working on — there is no `worktree.baseRef` setting. Make
`git checkout -B <branch> <base-sha>` the agent's step 0 and require it to report its branch
name back; merging by an assumed name has already reported "Already up to date" and would
have landed nothing.
