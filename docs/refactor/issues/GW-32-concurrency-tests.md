# GW-32 — Regression harness: keep the threading model from eroding

**Phase** 3 · **Severity** P2 (prevents recurrence) · **Closes** nothing directly — protects everything
**Files** `app/src/test/java/org/onetwoone/gateway/**`, `app/build.gradle`, `scripts/`
**Depends on** GW-10, GW-11 · **Conflicts with** nothing

## Problem

The existing suite (`CallManagerTest`, `GsmDtmfSenderTest`, `ReconnectionStrategyTest`,
`ServiceWatchdogTest`, `GatewayConfigTest`, `SipHeaderReaderTest`, `SipUriBuilderTest`) is
useful but tests only single-threaded logic. Every P0 and P1 finding in AUDIT.md is a
*threading* defect, and none of them would be caught by a single re-run of that suite.

Without a harness, the model installed by Phase 1 will erode: the next contributor adds a
`new Thread(...)` because it is the idiom the file already showed them, and the races come
back.

## Required change

Four layers, cheapest first.

### 1. Thread-invariant assertions (the primary defence)

Already introduced by GW-10 (`assertOnControlThread()`). This issue makes them
**enforced**:
- Armed in debug builds, throwing.
- A JVM unit test per manager asserting that calling a lifecycle method off-thread throws.
- `MainThreadAssert` equivalents where the main thread is the invariant
  (`GatewayInCallService`, UI).

### 2. Deterministic interleaving tests (JVM, no device)

For each P0 race, a test that forces the bad ordering with latches rather than hoping for
it. Minimum set:

| Test | Forces | Guards |
|---|---|---|
| `DeviceMuteLeaseTest` | release before a slow acquire completes | GW-02 |
| `AudioProfileStateTest` | teardown concurrent with setup, 1000 iterations | GW-04 |
| `CaptureSessionTest` | stopCapture during a slow open, worker publishes late | GW-08 |
| `CallManagerTransitionTest` | illegal transitions, double-terminate | GW-11, GW-06 |
| `ChargingReconcileTest` | superseded decision applied late | GW-05 |

These need the mixer/ALSA/Telecom boundaries behind interfaces so they can be faked.
Extracting those seams is part of this issue — `GsmDtmfSender` already shows the pattern
(`Target` interface + injectable `Handler`, `call/GsmDtmfSender.java:35-71`). Copy it.

### 3. Native-layer test

`gsm_audio_jni.c` has no test coverage at all, and it hosts the worst finding (GW-01).
Add a small C test binary (built via the existing CMake setup, run on-device via `adb
shell`) that links the JNI file against a stub tinyalsa and hammers
`open`/`readFrame`/`writeFrame`/`close` from multiple pthreads under ASan. This is the
only way to prove the use-after-free is gone rather than merely unobserved.

### 4. On-device soak script

`scripts/soak.sh` driving the broadcast API, scripting the call matrix the issue files
each reference:
- SIP→GSM and GSM→SIP, both SIMs
- hangup from each side, at randomised offsets (0–5 s) to hit the mid-frame window
- back-to-back calls with <1 s gaps
- config reload during a call
- service stop/start with a call bridged

After each run it must assert:
- zero tombstones (`/data/tombstones`)
- thread count back to baseline
- mixer controls at their idle values
- native heap flat (GW-22)
- `callsCreated == callsDeleted`

Wire it so a single command produces a pass/fail summary. Every issue's "Verification"
section references some subset of this — the script is where they converge.

## Acceptance criteria

- [ ] Off-thread lifecycle calls throw in debug builds, covered by tests.
- [ ] All five deterministic interleaving tests exist and pass; each fails if its fix is
      reverted (verify this — a test that passes against the buggy code is worthless).
- [ ] Native concurrency test exists and passes under ASan.
- [ ] `scripts/soak.sh` runs the full matrix and emits a pass/fail summary.
- [ ] `./gradlew test` runs the JVM layer; the native and soak layers are documented in
      `CLAUDE.md` under Commands.

## Verification

The meta-test: for each of GW-01, GW-02, GW-04, GW-05, GW-08, revert the fix on a scratch
branch and confirm the corresponding test **fails**. A concurrency test that has never
been seen to fail is not evidence.

## Risk

Low. The main cost is the interface extraction in §2 — keep those seams minimal and
modelled on `GsmDtmfSender.Target`, not a general-purpose abstraction layer.
