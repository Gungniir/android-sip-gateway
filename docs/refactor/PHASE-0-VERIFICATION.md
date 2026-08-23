# Phase 0 — on-device verification plan

**Status: PARTIALLY RUN — 2026-08-23, merlinx, APK versionCode 4 from `refactor/phase-0`.**

| Step | Covers | Result |
|---|---|---|
| 0 Baseline | — | done; pristine baseline = all six switches `Off` |
| 1 Charging stop-gate | GW-05 | **PASS** — force-enable in ~217 ms vs a 7 s budget |
| 2 Smoke | bridge | **PASS** — two-way audio both directions, re-wire line present |
| 3 Native UAF | GW-01 | **NO CRASH — but proves nothing.** 13 cycles, zero drains. See A1 correction |
| 4 Mic brick | GW-04 | **PASS** (GW-04 only — GW-02 is untestable here, see below) |
| 5 Cancelled open | GW-08 | **NOT RUN** |
| 6 Telecom | GW-03 | **NOT RUN** |
| 7 Test call | GW-06 | **NOT RUN** |
| 8 Thread invariants | GW-07 | **PASS so far** — zero `called off the main thread` across the whole session |
| 9 Soak | — | **NOT RUN** |

Across everything run so far: **zero tombstones** (still 32, the baseline), zero
`Fatal signal`, zero `FATAL EXCEPTION`, zero `ConcurrentModificationException`, zero
`setupMixer() over a live snapshot`, zero mixer-write errors, zero
`called off the main thread`.

## Three things the run itself taught us

**1. GW-02 cannot be verified on this device.** `MediaTekAudioProfile.handlesMicMute()`
returns `true`, so `DeviceMuteManager` is never used — the log says
`Mic mute handled by audio profile - skipping DeviceMuteManager` (18×), and there are
**zero** `Lease` lines. The mute-lease rewrite is Qualcomm-only in practice. Step 4 was
written as "GW-02 / GW-04"; on merlinx it exercises **GW-04 only**. GW-02 needs Qualcomm
hardware, and until it gets it, it is code-reviewed and unit-tested but unproven.

**2. GW-01's drain never fired, so Step 3 still matters.** `drain_io_locked()` returns
silently when `active_io == 0`, and across 20 teardowns there were **zero**
`close: draining N in-flight PCM I/O` lines. In the normal teardown order the conference
port is removed and `isCapturing` cleared before `close()` runs, so no I/O is in flight by
then. The refcount is correct but untested by these cycles — exactly the "if N is always 0
this step proves nothing" case Step 3 warns about. Note the tension with **E5**: the
backtrace proves `pcm_read` *is* in flight during the media teardown, just earlier in the
sequence than `close()`.

**3. Placing a normal dialler call can strip ROLE_DIALER and kill the gateway.**
Observed at 22:14:20: `Killing 4190:org.onetwoone.gateway (adj 50): permissions revoked`,
after which `cmd telecom get-default-dialer` returned `com.android.dialer`. With the role
gone `GatewayInCallService` never binds and the gateway is silently dead. **Step 4.4 must
end by restoring it:**
```bash
adb shell su -c 'cmd telecom set-default-dialer org.onetwoone.gateway'
```
This is also a second live instance of **B4b/B4c** — the kill ran no `onDestroy`.

**4. The only crash this app has ever had here is F2, and it was already fixed.** All 8
gateway tombstones on merlinx (2026-08-01 → 2026-08-23, three on 08-23 alone) carry the
same abort — `pj_thread_this` assertion via `pjsua_enum_transports` ←
`Endpoint::transportEnum()`, i.e. `hasTransport()` on an unregistered thread. **None** is a
SIGSEGV in `pcm_read`. `2626f5d` fixed it before Phase 0 started, and there have been
**zero new tombstones in 33 cycles** since. See the A1 correction in AUDIT.md.

## Second device — lavender (Redmi Note 7, SDM660, Qualcomm)

Added 2026-08-23 to cover what merlinx structurally cannot. **Release-signed build**
(`assembleRelease`) — merlinx's debug APK could not be installed over it
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), and uninstalling would have wiped its config, so
the release path was used instead. Bonus: no `CheckJNI`, so this device is the right place
to re-measure **E5** without the debug-build inflation.

- `tinymix` is **not present** on lavender. A copy from merlinx works —
  `adb push` it to `/data/local/tmp/tinymix`, `chmod 755`; `libtinyalsa.so` is present.
- Config preserved: `gw2rn7`, `sim1=1021`, `sim2=1022`, `mute_preset=redmi_note_7`.
- Baseline (idle): `DEC1-5 Volume = 84`, `EAR_S`/`SPK`/`DEC1-5 MUX = ZERO`,
  `Incall_Music* = Off`, tombstones 4.
- **`DEC* Volume` is the only diagnostic control.** The mute writes `ZERO` to `EAR_S`,
  `SPK` and the `DEC* MUX` enums — which is *also* their idle value, so a leaked mute is
  indistinguishable from a clean idle on those. Only `DEC* Volume` (84 = live, 0 = muted)
  can tell you the device is bricked.

| Step | Result on lavender |
|---|---|
| Startup | **PASS** — `Audio profile auto-detected: Qualcomm`, registered `gw2rn7` over TLS, zero fatals, zero `called off the main thread` |
| 1 Charging stop-gate | **PASS** — force-enable in ~210 ms, both hatches in order. GW-05 now verified on both SoCs |
| GW-02 mute lease | **STILL UNRUN** — needs GSM calls on this device; `handlesMicMute()` is `false` here so `DeviceMuteManager` *is* used |
| GW-04 on Qualcomm | **STILL UNRUN** — the rewritten `QualcommAudioProfile` loads, but its mixer path needs a call |

**Note:** the earlier "NOT RUN" status below is superseded by the table above; the rest of
the plan is unchanged and still applies to the steps not yet run.

Consolidated from the eight agents' individual checklists, ordered so that the steps which
can **strand the device** are proven before anything riskier runs.

## Ground rules

- **Never read `/proc/asound/*/status` during a call** on the Redmi Note 9 — it kernel
  panics. `tinymix` and `hw_params` are safe.
- Use the SDK's `adb`, not the host copy. App broadcasts need `-p org.onetwoone.gateway`.
- Keep a second terminal on `adb logcat` for the whole run; save it.
- If a **STOP** gate fails, stop and revert rather than continuing.

---

## Step 0 — Baseline capture

### The test device is MediaTek, not Qualcomm

`merlinx` / Redmi Note 9 / MT6768 (Helio G85), sound card `mt6768-mt6358`, card 0.
**Every Qualcomm control named below — `DEC*`, `EAR_S`, `SPK`, `VOC_REC_DL`,
`Incall_Music` — does not exist on this device.** Only the six MediaTek crossbar
switches apply. `handlesMicMute()` is `true` on this profile, so the mic mute is the
`ADDA_UL` pair, not a `DeviceMuteManager` preset. (The stored `mute_preset` pref is
`redmi_note_7`, a Qualcomm preset name — inert here, but see AUDIT H4.)

### tinymix syntax on this build

This device's `tinymix` has **no `get` subcommand**: `tinymix -D 0 get "NAME"` fails with
`Invalid mixer control: get`. The working forms are:

```bash
tinymix -D 0 "NAME"          # name + value
tinymix -D 0 -v "NAME"       # value only  ← use this in scripts
tinymix -D 0 "NAME" 1        # set
```

### Pristine baseline — captured 2026-08-23 after a reboot

A reboot is the only way to get a trustworthy baseline: mixer state does not survive it,
and nothing else clears a leaked crossbar. Immediately after boot, before the gateway has
ever bridged a call, **all six switches read `Off`**:

| Switch | Pristine (idle) | During a bridged call |
|---|---|---|
| `UL2_CH1 PCM_2_CAP_CH1` | Off | On |
| `UL2_CH2 PCM_2_CAP_CH1` | Off | On |
| `PCM_2_PB_CH1 DL2_CH1` | Off | On |
| `PCM_2_PB_CH2 DL2_CH2` | Off | On |
| `PCM_2_PB_CH1 ADDA_UL_CH1` | Off | Off (mic muted into uplink) |
| `PCM_2_PB_CH2 ADDA_UL_CH2` | Off | Off |

So **"all six Off" is the between-calls assertion** for every step below. Any `On` at idle
means a teardown was missed.

> **Why this matters more than it looks.** On the pre-Phase-0 build the first four were
> found `On` at idle with no call in progress — a leaked crossbar from an earlier session.
> `setupMixer` reads the *current* value as the "original" to restore, so a session that
> starts from an already-leaked state saves the leak and faithfully restores it forever.
> GW-04 recovers from a snapshot **it** left behind (`setupMixer() over a live snapshot`),
> but not from one left by a **previous process** — that state lives in the kernel, not in
> the heap. This is AUDIT **B1b/B4b** applied to the mixer, and it is unfixed.
> **Always reboot before a verification run**, or you are measuring against a corrupt zero.

### Other baselines recorded on 2026-08-23

- Tombstones already present: **32** (`ls /data/tombstones | grep -c 'tombstone_[0-9]*$'`)
- Threads at idle, registered, no call: **~30**
- `MixerEnforce` / `GsmAudioOpen` threads at idle: **0**
- Config: `sip_user=gw1rn9`, TLS to `pbx.kurus.me:5061`, `sim1_destination=1011`,
  `sim2_destination=1012`, `test_destination=2001`, `battery_limit=60`

### Helper

`gw-check.sh [mixer|state|crash|threads|all]` (in the session scratchpad) collapses the
between-cycle checks into one command and flags any switch that is not `Off`.

---

## Step 1 — STOP GATE: the charging escape hatch (GW-05)

**This must pass before any other step.** It is the only thing standing between a bug and
a phone that will not charge. Run it plugged in, at ≥60%.

> **The `STOP` broadcast does not stop the battery service.** `GatewayControlReceiver`
> starts `BatteryLimitService` alongside `PjsipSipService`, but `STOP` only tears down the
> SIP service — nothing in the app ever calls `stopService` on the battery one, and no UI
> control stops it. The escape hatch under test is `onDestroy`, so it must be reached
> directly:
> ```bash
> adb shell am stopservice org.onetwoone.gateway/.BatteryLimitService
> ```
> Verified on 2026-08-23: a `STOP` broadcast at 93% with the limit at 60 left
> `input_suspend=1`, which is **correct** (level is above the limit) and therefore proves
> nothing about the escape hatch. Do not use `STOP` for this step.

1. Start the service; confirm the limit is below the current level (here: limit 60,
   level 93), so charging is legitimately suspended.
2. Wait for `input_suspend` → `1`.
3. `adb shell am stopservice org.onetwoone.gateway/.BatteryLimitService`
4. **`input_suspend` must return to `0` within ~7 s**, with
   `SAFETY: Force enabling charging on all paths` then `SAFETY: Charging force-enabled`
   in logcat.
5. Restart, and stop again before init completes — `input_suspend` must still end at `0`.

**If step 4 fails: stop the whole plan and revert.**

Known gaps, neither a failure of this step:
- `adb shell am force-stop` leaves charging disabled, because `onDestroy` never runs.
  That is AUDIT **B4b**, unfixed and filed.
- **An APK reinstall does the same.** Observed 2026-08-23: `adb install -r` killed the
  process with `input_suspend=1` and it stayed `1` until the service was started again.
  Same root cause as B4b — the restore state lives only in the dying process.
- The 5 s `ENFORCE_INTERVAL_MS` re-write is **expected**, not a regression: logcat shows
  `Charging disabled on 1 path(s)` every 5 s on both the old and the new build. GW-05
  coalesces *decisions*, not the periodic re-assert against the HAL.

---

## Step 2 — Smoke

Install, set the app as default dialler, confirm registration. One inbound GSM→SIP call and
one outbound SIP→GSM call, **two-way audio on both**. If audio is one-way here, stop —
something in Waves 1–2 regressed the bridge and nothing below is meaningful.

Confirm in logcat on a normal call:
- `Conference links lost (media stream re-created), rewiring` still appears — this is the
  hard-won re-INVITE/UPDATE re-wire path. **Losing it brings back one-way audio.**
- `opened on attempt K` with K>1 on a SIP-first incoming call — the open-retry policy is
  doing its job.

---

## Step 3 — GW-01: the native use-after-free (the crash fix)

1. 50 call cycles, hangup issued **PBX-side at a random offset inside the first 3 s** —
   that is the window where `pcm_read` is always in flight.
2. Expect 50 clean `Audio closed`. **Zero `Fatal signal`, zero new tombstones.**
3. `logcat -s GsmAudioNative | grep 'draining'` — `close: draining N in-flight PCM I/O`
   with **N ≥ 1 on most hangups**. If N is always 0 the drain is never exercised and this
   step proves nothing.
4. `grep 'PCM drain gave up'` — **must be empty**. If it fires, `pcm_stop()` is not waking
   the reader on this kernel and GW-01 needs a different wake mechanism.
5. Time `Closing audio` → `Audio closed`: single-digit ms typical. Near 250 ms means the
   drain is timing out.

---

## Step 4 — GW-02 / GW-04: the mic-brick fixes

The single most important user-visible property: **after every call, the phone must still
work as a phone.**

1. 20 cycles of *answer then hang up within 1 s* (GW-02's cancellation window).
2. 20 cycles of *back-to-back calls, hang up and redial within 1 s* (GW-04's setup/teardown
   race).
3. After **each** cycle, every switch back at its Step 0 value — on this device that is
   **all six `Off`** (`gw-check.sh mixer`). Note this corrects the earlier draft, which
   said the `ADDA_UL` pair should return to `1`: that is their in-call value, restored by
   the HAL, not their idle value.
4. **Then place a normal call from the phone's own dialler.** Earpiece and mic must both
   work. This is the real test — the mixer values can look right and the path still be
   dead, and on this SoC the `ADDA_UL` pair *is* the microphone path into the modem
   uplink. Do this at least once after Step 3 and once after Step 5.
5. `logcat | grep ConcurrentModificationException` — zero.
6. `grep 'Lease N cancelled after K control writes - unwinding'` — should appear in the
   1 s-hangup cycles. Its absence means the cancellation path is untested.
7. `grep 'setupMixer() over a live snapshot'` — appearing *routinely* means a teardown is
   being missed upstream; report it.

---

## Step 5 — GW-08: cancelled ALSA open

1. Force a slow open (point the profile at a busy PCM, or temporarily raise
   `OPEN_RETRY_MS`), start a call, hang up at t≈2 s. Repeat ×10.
2. `adb shell su -c "cat /proc/<pid>/task/*/comm" | grep -c MixerEnforce` → **0 between
   calls**, and the thread count must not grow across the 10 iterations.
3. Expect `Open aborted (session N superseded)` or
   `Open for session N completed after cancellation - releasing it`.
4. **Must NOT see** `Native audio started (session N` after `Stopping native audio` for
   that same N. That is the exact bug.

---

## Step 6 — GW-03: Telecom paths

1. 30 hangups from the SIP side at random offsets → **zero `NullPointerException` with tag
   `GatewayInCall`**.
2. With a call bridged, call the same SIM from a third phone → expect the
   `SECOND GSM CALL REJECTED` block, and critically **the first call keeps two-way audio
   and hangs up normally**.
3. Disable the SIP account on the PBX, place an inbound GSM call → exactly 40
   `SIP service not ready, retry N in 500ms` lines over ~20 s, then one give-up, then the
   30 s timeout drops the GSM leg. Repeat ×3; confirm chains do not accumulate.

---

## Step 7 — GW-06: the diagnostic call is reachable again

1. Set `sim1_destination` to a non-existent extension.
2. Place an inbound GSM call → PBX rejects the INVITE.
3. Immediately:
   `adb shell am broadcast -p $PKG -a org.onetwoone.gateway.TEST_CALL --es mode tone`
   → **must be accepted.** Before GW-06 it was refused with
   `Refusing test call: a gateway SIP call is in progress`.
4. Restore the destination; confirm the normal bridge still works.

---

## Step 8 — GW-07: thread invariants

Across everything above, grep logcat for **`called off the main thread`**. Any hit is a
real wrong-thread bug, not a false alarm — record it in AUDIT.md.

Exercise specifically: incoming GSM→SIP, outgoing SIP→GSM, config reload from the web UI
during a call, `*43` test call, service stop/start.

Also worth one pass with StrictMode's thread policy enabled in the debug build; record
violations rather than suppressing them.

---

## Step 9 — Soak

- 60% charge limit, plugged in, left running overnight. Level oscillates in the 55–60%
  hysteresis band, never approaches 20%, and no
  `FAIL-SAFE: charging has been disabled for …` line appears.
- Confirm SIP stays registered; force a reconnect cycle (block the PBX for 5 minutes, then
  restore) and confirm exponential backoff and recovery with no main-thread freeze.

---

## What this plan deliberately does not cover

- **AUDIT H2c** — `stopCapture()`'s worst case is now ~1.75 s plus a mixer-lock wait, still
  on the main thread. Watch for `Skipped … frames` bursts around hangup and record them,
  but the fix is GW-10/GW-26, not Phase 0.
- **B1b / B4b** — a mute or a charging block held when the process is *killed* survives it.
  Both are filed and unfixed; they need out-of-process restore state.
- **D1b** — the audio bridge can still be wired to a stale call. Filed for GW-12.
- The 12 h `MAX_DISABLE_MS` and 4 h `MUTE_MAX_HOLD_MS` fail-safes are unit-untested and
  impractical to exercise for real. Optionally smoke them in a scratch build with the
  constants lowered to minutes — **do not ship that build**.
