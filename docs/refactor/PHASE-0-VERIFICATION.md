# Phase 0 — on-device verification plan

**Status: NOT RUN.** Every Phase 0 fix has been verified only on the JVM
(103 tests, 0 failures, `assembleDebug` + `lintDebug` green). Nothing has touched the
phone. All eight issues are static reasoning plus unit tests until this plan passes.

Consolidated from the eight agents' individual checklists, ordered so that the steps which
can **strand the device** are proven before anything riskier runs.

## Ground rules

- **Never read `/proc/asound/*/status` during a call** on the Redmi Note 9 — it kernel
  panics. `tinymix` and `hw_params` are safe.
- Use the SDK's `adb`, not the host copy. App broadcasts need `-p org.onetwoone.gateway`.
- Keep a second terminal on `adb logcat` for the whole run; save it.
- If a **STOP** gate fails, stop and revert rather than continuing.

---

## Step 0 — Baseline capture (before installing)

Record these; several later steps are "back to baseline" assertions.

```bash
PKG=org.onetwoone.gateway
adb shell su -c 'tinymix -D 0 get "DEC1 Volume"'            # Qualcomm mute controls
adb shell su -c 'tinymix -D 0 get "EAR_S"'                  # ...and each in your preset
adb shell su -c 'tinymix -D 0 get "PCM_2_PB_CH1 ADDA_UL_CH1"'   # MediaTek
adb shell cat /sys/class/power_supply/battery/input_suspend     # expect 0
adb shell ls /data/tombstones                                    # note what is already there
adb shell su -c "ls /proc/\$(pidof $PKG)/task | wc -l"           # thread baseline
```

---

## Step 1 — STOP GATE: the charging escape hatch (GW-05)

**This must pass before any other step.** It is the only thing standing between a bug and
a phone that will not charge. Run it plugged in, at ≥60%.

1. Start the service; set the limit just below the current level.
2. Wait for `input_suspend` → `1`.
3. Stop the service: `adb shell am broadcast -p $PKG -a org.onetwoone.gateway.STOP`
4. **`input_suspend` must return to `0` within ~7 s.**
5. Force-stop, restart, and stop again before init completes — `input_suspend` must be `0`.
   Expect `SAFETY: Force enabling charging on all paths` in logcat.

**If step 4 fails: stop the whole plan and revert.**

Known gap, do not treat as a failure: `adb shell am force-stop` leaves charging disabled,
because `onDestroy` never runs. That is AUDIT **B4b**, unfixed and filed.

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
3. After **each** cycle, every mute control back at its Step 0 value:
   - Qualcomm: `DEC1-5 Volume`, `DEC1-5 MUX`, `EAR_S`, `SPK`; and `VOC_REC_DL`,
     `Incall_Music Audio Mixer MultiMedia1`, `Incall_Music_2 …` all `0`.
   - MediaTek: `PCM_2_PB_CH1/2 ADDA_UL_CH1/2` = `1`; the four enable switches = `0`.
4. **Then place a normal call from the phone's own dialler.** Earpiece and mic must both
   work. This is the real test — the mixer values can look right and the path still be dead.
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
