# Phase 2 — on-device validation, wave by wave

Every wave is tagged (`phase-2-wave-N`) and a debug APK is kept in
`release-output/phase-2-waves/` (gitignored). Validate a wave by checking out its tag —
or by flashing its APK — so a regression is attributable to one wave rather than to the
whole phase.

**Wave 3 is partly validated — see Results below.** The idle, SMS and lifecycle paths
passed on both handsets on 2026-08-24; everything that needs a call placed is still open.

Devices: **merlinx** = Redmi Note 9, MT6768, debug build, assertions armed, `055f14050405`.
**lavender** = Redmi Note 7, SDM660, release build, `c31adecd`.

Standing hazards while testing:
- **Never read `/proc/asound/*/status` during a call on merlinx** — kernel panic. `tinymix`
  and `hw_params` are safe.
- **Do not clear the SMS fixture on merlinx**: 8 unread messages, `_id` 3/4/5/6/8/10/11/12.
  They are the GW-27 reproduction, and they are **reusable** — restore them to `read=0`
  rather than leaving them consumed. Since GW-27 landed they must NOT re-forward on a
  restart; if they do, that is a regression.
- Use the SDK's `adb` (`~/Android/Sdk/platform-tools/adb`). Broadcasts need
  `-p org.onetwoone.gateway`, and `-f 0x00000020` after a `force-stop`.

---

## Results — wave 3, 2026-08-24

Validated **top-down from `phase-2-wave-3`**: wave 3 contains waves 1 and 2, so a green
wave 3 clears all three. The per-wave tags stay useful only for bisecting a *failure*.

Builds under test, both from commit `d94363e`:

| Device | Build | APK |
|---|---|---|
| merlinx (MT6768, assertions armed) | debug | `wave-3-d94363e.apk` |
| lavender (SDM660, Qualcomm) | **release** | `wave-3-d94363e-RELEASE.apk` |

The lavender release APK was built for this run because the debug key does not match what
lavender already carries, and `adb install -r` across signatures would have wiped its config.
A release build from the same commit installs over it cleanly.

### Passed on hardware

- **GW-27 / AUDIT H13 — the headline, tested against the real failing state.** First start
  forwarded all 8 fixture messages exactly once (`sendSipMessage SUCCESS` ×8, `confirmed=8`,
  `inFlight=[]`). The fixture was then **restored to `read=0`** and the app restarted twice:
  both times `Loaded 8 persisted SMS ids` → `Found 8 unread SMS` → `SKIP id=N (already
  forwarded)` ×8 → **zero** `sendSipMessage`. That is the exact shape of the 13:04 field
  report, and the persisted record carried it alone.
- **GW-20 / AUDIT B1e — on the Qualcomm device.** `B1e native-vs-tinymix: 12 agreed,
  0 mismatched, 0 unreadable, of 12 control(s) on card 0`. Trigger it with
  `GET /api/mixer-controls`; no call required.
- **GW-20's root contract, incidentally.** merlinx now logs `Marked SMS id=N as read (root
  content update, verified)`. The old `sqlite3` path (absent, exit 127) used to report
  success because `execRoot` returned trimmed output rather than null. Both halves fixed.
- **GW-26 — service lifecycle.** `Service destroyed in 38 ms on main`, `user_stopped=true`
  latched, **zero** restarts in 15 s, latch cleared to `false` on the next START. No
  `unregisterReceiver` / `ContentObserver` leak warnings.
- **GW-21 — the parts hardware can settle without a burst.** Every `SmsHandler` line ran on
  the `GatewayControl` tid. Teardown split exactly as specified: `Stopping SMS handler` on
  main, `SMS handler stopped` on `GatewayControl`. No `Skipped … frames`. Debounce visible:
  the 8 marked-read writes produced 8 observer notifications that coalesced into **one** scan
  (7 × `coalesced into the pending scan`).
- **GW-25 — no idle false positives.** `grep -c INVARIANT` = 0 on both devices. The guard
  line `No InCallService bound - skipping the orphan rules this tick` was observed doing its
  job (InCallService binds only while a call exists; both devices do hold `ROLE_DIALER`).
- Both devices register over TLS with SRTP mandatory; zero crashes, zero tombstones, zero
  app-level `E`/`W` beyond the guard line above.

### Call run — 6 real calls, merlinx, ANSWER_FIRST, 2026-08-24

4 inbound GSM + 2 outbound, all bridged, all torn down cleanly
(`6 × TERMINATING -> IDLE`). 30 calls was over-specified: the watchdog rules are
deterministic per 3 s tick, so a wrong dwell fires on call 1, not call 17. Shape coverage,
not count, is what settles the acceptance question.

- **GW-25 acceptance: PASSED — zero terminations.** The one `INVARIANT` line in the run is
  the silent-bridge detector, which is detection-only by design (see H18 below).
- **The 45 s dwell is now proven on hardware, not just argued.** On call 2 the GSM leg was
  ACTIVE and adopted with no answered SIP leg for **29.7 s**, and the watchdog left it alone.
  The original brief's guess of ~8 s would have killed that call — a healthy, ordinary
  inbound call where the PBX extension simply rang for a while.
- **E5 re-measured (GW-23b baseline).** SIP-initiated hangups: **52.12 s** and **3.19 s**
  (BYE → `UDP media transport detached`). GSM-initiated: **0.00 s** ×4 — detach and TX BYE
  within 3 ms. The asymmetry in GW-23b §1 reproduces exactly, and 52.12 s is a **new worst
  case**, above the previously recorded 50.69 s.
- `Conference links lost (media stream re-created), rewiring` was observed and handled — the
  known PJSIP codec-lock UPDATE behaviour, working as designed.
- Zero crashes, zero tombstones, zero main-thread stalls, `errors=0` on every frame counter.

**GW-23a: CLOSED.** `Profile=MediaTek card=0 capture=5@8000/1ch playback=2@48000/1ch
frame=320B bulkCopy=true` — the fast path is active, and the operator confirmed audio was
good on every call in the run, both directions. Frame counters ended `captureErr=0,
playbackErr=0` throughout.

> Getting that line is fiddlier than it looks: it is logged **once per process**, when
> `AudioBridgeManager` constructs the port. A STOP/START of the *service* does not re-emit it
> because the process survives, so `am force-stop` is required. Clearing the logcat buffer
> after the gateway is already running loses it until the next process death.

**E5 across 8 calls: 52.12 s, 3.19 s, 0.00 s, 0.011 s (SIP-initiated) against 0.00 s ×4
(GSM-initiated).** The spread is the point. Nothing in the code produces "52 seconds" as a
value — `conf->mutex` is a plain non-FIFO `pthread_mutex`, so the BYE handler waiting in
`pjmedia_conf_remove_port` is not queued; it re-contends against a callback that re-enters
every 20 ms and can lose arbitrarily many times. Sometimes it wins immediately, which is why
half the SIP-initiated hangups were instant. This is a lock-fairness lottery, not a timeout,
and it is **expected on wave 3** — GW-23b is the fix and is deliberately not landed.

**New finding: H18** — the silent-bridge detector fires on any inbound call whose SIP leg
rings longer than 12 s, because `BRIDGED` is reached when the INVITE goes out, not when the
SIP side answers. Detection-only, so no call is harmed, but it pollutes the very counter
GW-25 §2 designates as the evidence base for promoting the rule to auto-terminating.

The threshold behaviour is now confirmed across 8 calls, and it tracks the ring gap exactly:
fired on rings of **29.7 s** and **20.6 s**; stayed silent on **9.6 s, 4.3 s, 2.5 s, 2.2 s**.
Nothing else correlates.

### NOT covered — still needs a human placing calls

After the 6-call run above, what is still open:

- **GW-22** — the graveyard under a longer soak.
- **GW-21** — the 20-SMS burst, SMS during a bridged call, PBX-down backoff.
- **A second SIP-initiated hangup on lavender.** Only one was captured (8 ms), and one
  sample of a lock-fairness lottery establishes nothing — see the lavender run below.
- Deliberately not chased: `MAX_CALL_DURATION_MS` and `TERMINATING_DWELL_MAX_MS` need
  temporarily shortened constants to reach (validation step 8), not longer real calls.

### lavender run — 4 calls, Qualcomm, release build, 2026-08-24

3 inbound + 1 outbound, all bridged, 4 clean teardowns. The SIM was moved over from merlinx
for this (lavender's own slots read `ABSENT,ABSENT` — worth checking before blaming the app
when a device will not place calls).

- **Two-way audio confirmed on Qualcomm by the operator**, with `bulkCopy=true` and
  `captureErr=0, playbackErr=0`. Together with merlinx this closes GW-23a **on both SoCs** —
  and the two profiles genuinely differ: `playback=0@8000` here against `playback=2@48000/1ch`
  on MediaTek, so the resampler path and the non-resampler path are both exercised.
- **E5, release build, n=1: 8 ms.** This is the clean CheckJNI-free number GW-23b §6 asked
  for, and it is *not* evidence that E5 is absent on Qualcomm. Only one SIP-initiated hangup
  occurred. merlinx spread 0.00–52.12 s over four samples; a single fast sample is exactly
  what winning the lock race once looks like. **Do not quote 8 ms as the Qualcomm baseline
  until there are several samples.**
- GSM-initiated hangups: 0.002 s, 0.002 s, 0.001 s — the fast path, consistent with merlinx.
- **New finding H19** — D6 fired 103 ms before the DISCONNECTED it claimed was missed,
  because the leg was in the `DISCONNECTING` window. Harmless here (the call was already
  ending and the port stop is idempotent) but it logs at ERROR alleging a Telecom defect that
  did not happen, and it inflates `watchdog_terminations`. It reproduced on lavender and not
  merlinx purely because that window is 486 ms there against 46 ms on merlinx.

### Notes for re-running

- **The SMS fixture is reusable, not single-use.** Restore it with
  `content update --uri content://sms/inbox --bind read:i:0 --where "_id=N"` for
  `3 4 5 6 8 10 11 12`. It was left restored (`read=0`) after this run. Because the read flag
  now genuinely works, a plain restart passes *trivially* — the fixture must be restored to
  unread for the test to mean anything.
- `sub_id=2` (t2) maps to `sim_id=-1`: that SIM is physically absent, so its historical
  messages route to SIM1's destination via the documented `default to SIM1` fallback. Not a
  bug, but it is a silent fallback.
- A fresh agent worktree has **no `local.properties`**, so a Gradle build there fails with
  "SDK location not found". Copy it (and `keystore.properties` for a release build) from the
  main checkout. This is the second worktree trap after the `origin/main` base — the worktree
  for this run did start at `7cbd7fd`, as GW-23b §8 warned.

---

## Wave 1 — `phase-2-wave-1` (GW-20, GW-23a, GW-26)

263 tests, 0 failures, lint clean, native build green. Nothing on hardware.

### First, before anything else — is the fast path even active?

```
adb -s <dev> logcat -d | grep -E 'bulkCopy=|Profile='
```
Must read **`bulkCopy=true`**. If it says `false`, GW-23a's ABI check failed and every audio
observation below is measuring the *fallback* path, not the new one. That is a safe
degradation, not a crash — but report it, because it means a PJSIP rebuild changed the
`std::vector` layout.

### GW-23a — audio. This is the only thing here that can be judged by ear.

**What a regression sounds like matters, because the two failure modes are distinct:**
- **Resampler (H3) regression → pitch/speed shift** (chipmunk or slow-motion), or a periodic
  20 ms buzz. **merlinx only** — Qualcomm has `capture_rate == playback_rate` and never
  enters the resampler, so lavender cannot validate this at all.
- **Bulk-copy regression → white noise, a constant tone, or silence.** Wholesale wrong
  buffer, not a subtle artefact. Either device.
- Intermittent clicks would instead point at the frame-length change (H2e).

1. Real GSM↔SIP call on **merlinx**, both directions, listening for pitch/rate change.
   Compare against a pre-change recording if you have one.
2. Same on **lavender**, listening for noise/tone/silence.
3. `adb logcat | grep 'Upsample scratch'` — expect `960 samples (1920 bytes)` once per open
   on merlinx, and **never** `writeFrame: N out samples exceed the 960-sample scratch`.
4. At teardown, `captureErr`/`playbackErr` in the stats line should be **lower or equal** to
   baseline, never higher. (A `0` return now means "closed under us" and no longer counts as
   an error, so a rise is meaningful.)
5. Full call matrix both SoCs; zero tombstones (`adb shell ls /data/tombstones`).
6. CPU during a call, before/after: `adb shell top -p $(pidof org.onetwoone.gateway)`.

### GW-20 — the B1e check is an argument, not a measurement yet

The native getters agree with `tinymix` *by construction* (same tinyalsa primitives, index 0),
but that has never been measured. A cross-check was built in:

1. Tap **"Detect mixer controls"** in the app (or `GET /api/mixer-controls`).
2. `adb logcat | grep 'B1e native-vs-tinymix'`
3. **Must read `0 mismatched, 0 unreadable`** on each SoC. Anything else means the saved
   mixer originals are still wrong and `teardownMixer` will restore garbage.

Then the behavioural half — **this is what B1c/B1e are ultimately about**:
4. Place a gateway call on **lavender**, end it, then make a **normal non-gateway call** and
   confirm the microphone works. Do not infer this from the mixer:
   `DEC* Volume == 0` is the normal resting value on that device and proves nothing.

Note: the Qualcomm mute path only executes once the custom mute list is non-empty, which is
**GW-24's** fix (wave 2). Until then this path is still latent — so a clean result here is
necessary but not sufficient, and the real test lands in wave 2.

### GW-26 — service lifecycle

1. **The direction that matters most: a crash must still restart the gateway.**
   ```
   adb shell su -c 'kill -9 $(pidof org.onetwoone.gateway)'
   adb shell dumpsys activity services org.onetwoone.gateway
   ```
   It must come back. Only an *explicit* STOP is allowed to keep it down.
2. Explicit stop survives, including while the app is the default dialler:
   ```
   adb shell am broadcast -p org.onetwoone.gateway -a org.onetwoone.gateway.STOP
   adb shell dumpsys activity services org.onetwoone.gateway
   ```
   Stays stopped. Then `START` must bring it back.
3. Destroy duration: stop the service ~20× and check the logged main-thread time.
   Expect it bounded and well under the ANR budget.
4. No leaked wake lock after stop:
   `adb shell dumpsys power | grep -A5 'Wake Locks'` — `Gateway::CpuWakeLock` must be absent.
5. **Destroy with a live bridged call** — the only path where `stopAudioBridge` and
   `shutdownSip` actually do work. Zero tombstones, mic restored afterwards.
6. Reboot: gateway comes back (BootReceiver), and its `am start` fallback behaviour is worth
   a glance since it now also fires on a non-zero exit.

---

## Wave 2 — `phase-2-wave-2` (GW-24, GW-27, GW-22)

329 tests, 0 failures, both variants; lint clean; native build green. Nothing on hardware.

Will be filled in when the wave lands. Expected headline checks:
- **GW-27's fixture test** — restart with the 8 unread SMS present; **zero** re-forwards at
  the PBX. Then fault-inject the read-flag write and repeat: still zero. That second run is
  the one that proves the persisted set rather than the flag is carrying correctness.
- **GW-24 + GW-20 together** — select mute controls in the web UI, place a call, confirm
  those controls are actually muted, then confirm the mic is live afterwards via a normal
  call. This is the first time the Qualcomm mute path executes at all.

  GW-24 has landed; the procedure, in order, on a device that has used the web UI before:
  1. **Back up first** —
     `adb shell su -c 'cp -r /data/data/org.onetwoone.gateway/shared_prefs /sdcard/prefs-backup'`.
  2. **Migration.** Confirm the legacy key is present *before* the upgrade:
     `adb shell su -c 'grep -c mic_mute_controls /data/data/org.onetwoone.gateway/shared_prefs/gsm_audio_config.xml'`.
     Install, launch, then confirm `mic_mute_decs` holds the same names as a `<string>` and
     `mic_mute_controls` is gone. `logcat -s GatewayConfig` must show
     `Migrated mute controls to mic_mute_decs: '…'` and **no** `ClassCastException` anywhere.
     Relaunch twice more and confirm the value does not drift (the migration is idempotent,
     but only the device proves the read-back path).
  3. **The mute itself** (the loop that has never executed). Custom preset, one known DEC
     selected, place a GSM call: `tinymix -D 0 get "DEC1 Volume"` must read **0** during the
     call and its pre-call value after it. Any `Not muting '…'` line in logcat must name a
     control that genuinely does not exist on that device — if it names one that does, the
     native read is the problem, not the config (that is B1e's outstanding
     `verifyNativeReads()` check, which should be run first).
  4. **Reloadability (H4b).** With the gateway running, change the multimedia route or the
     mute list from the web UI and place a call **without restarting**: the new values must
     appear in the `Setting up mixer for … (N mute control(s))` line. Then change the sound
     *card* and confirm it does **not** take effect until a restart — that residual is
     deliberate and the toast promises it.
  5. **Web UI defaults.** On a device with SIP unconfigured, the page must show empty
     server/user/password and realm `*` — never `192.168.5.95` / `gateway123` / `101`.
- **GW-22** — 500-cycle soak, `callsCreated - callsDeleted` equal to active calls at the end,
  zero tombstones. A premature `Call` delete presents as a native crash, so the soak is also
  the safety test.

### GW-27 — the SMS fixture. Run this before anything that restarts the app a lot.

The fixture on merlinx is 8 unread SMS, `_id` 3/4/5/6/8/10/11/12. Confirm it is intact
first — if it is not, this test cannot be run again without recreating it:
```
adb -s 055f14050405 shell su -c 'content query --uri content://sms/inbox --projection _id:read'
```
Expect 8 rows `read=0`, plus `_id=1` `read=1` (spent during triage).

1. **The reported bug.** With the fixture present, restart the app and watch the PBX /
   receiving extension. **Zero re-forwards.** Before this wave, all 8 were re-sent.
2. **Confirm the flag now actually gets written** — this is the half that never worked:
   ```
   adb shell su -c 'content query --uri content://sms/inbox --projection _id:read'
   ```
   Forwarded ids must show `read=1`. Any `could not be marked read` error naming an id
   means `content update` is refused under the app's own uid/SELinux context, which was
   only ever verified from a root shell, not from inside the app. **That is the single
   most likely thing to differ on hardware.**
3. **The test that proves the design.** If step 2 fails, step 1 must *still* pass — the
   persisted record, not the flag, is what carries correctness. If both fail together, the
   persistence is not surviving `force-stop`; check with
   `adb shell su -c 'cat /data/data/org.onetwoone.gateway/shared_prefs/*.xml | grep processed_sms'`.
4. Flapping registration: force ~5 re-registrations with unread SMS present. Each must
   forward nothing new (`processInbox` runs on every successful REGISTER).

### GW-22 — the soak, which is the safety test as much as the leak test

**A premature `Call` delete presents as a native crash, not an exception.** So:

1. **Zero tombstones** — `adb shell ls /data/tombstones` before and after. This is the
   pass/fail criterion, not the heap graph.
2. 500 call cycles. Then `callsAlive` (in the status / `GET_STATUS` bundle) must equal the
   number of genuinely active calls, i.e. 0 or 1.
3. `adb shell dumpsys meminfo org.onetwoone.gateway | grep -E 'Native Heap|TOTAL'` before
   and after. **Expect it flat-ish — that is the predicted result, not a failure.** These
   were finalizer-deferred releases, never unbounded leaks. The finding is about
   determinism.
4. **Watch for `Call deleted on FinalizerDaemon`** in logcat. Every occurrence means the
   deterministic path lost the race and the old, dangerous behaviour happened instead. A
   few are tolerable; a widening `callsCreated - callsDeleted` gap is not.
5. Also expect occasional `Call still holds pjsua slot N after 60000 ms - abandoning it to
   the finalizer`. That is the graveyard **refusing** to delete, which is the safe
   direction. Frequent occurrences mean `getId()` is not clearing as assumed and the Call
   half is not buying anything — worth reporting.

**If a tombstone appears:** revert only the `bury(...)` call sites, keep `CallGraveyard`
and the counters. The recipe is in the class javadoc and the commit body.

## Wave 3 — `phase-2-wave-3` (GW-21, GW-25)

### GW-21 — what only hardware can settle

Everything below is **unmeasured** until it is run on a device. The JVM suite covers thread
identity, cursor lifetime, the coalescing window and stop-during-a-scan; it cannot cover the
real content provider, real `su`, or a real PBX.

1. **The burst.** 20 SMS to the gateway SIM in a burst. Expect all 20 at the PBX **exactly
   once**, and in `logcat -s SmsHandler` roughly one `processInbox START` per burst rather
   than one per message — that is the debounce doing its job. Also expect **no**
   `Skipped … frames` from the UI thread, which is the G1 acceptance signal.
2. **The observer's thread.** Every `SmsHandler` line during a burst must be on
   `GatewayControl`. `adb logcat -v thread -s SmsHandler` and check the tid against
   `GwControlThread: Control thread started`. A line on main is the regression.
3. **SMS during a bridged call.** Send an SMS while a call is bridged: call audio unaffected,
   teardown not delayed. The blocking SIP send now shares a thread with call setup, so this is
   the one behavioural risk the move creates — the send is bounded by the PBX's response, and
   the batch is at most one inbox's worth per 250 ms window.
4. **PBX down, then up.** With the PBX unreachable, send one SMS: bounded retries with visible
   backoff in `logcat -s SmsHandler`, then a give-up line. Restore the PBX before the cap and
   confirm delivery on a retry. This is GW-27's bound, re-checked single-threaded.
5. **Destroy during a scan.** `am force-stop` / STOP broadcast while a burst is in flight:
   expect `Stopping SMS handler` on main, `SMS handler stopped` on `GatewayControl`, no
   `unregisterContentObserver` leak warning, and the undelivered messages forwarded (not
   dropped, not duplicated) on the next start.

**Do not clear the merlinx fixture** (8 unread SMS, `_id` 3/4/5/6/8/10/11/12) as part of any
of this.

### GW-25 — the false-positive run is the acceptance test, not the orphan test

A watchdog that kills healthy calls is worse than no watchdog, so run this **before** trying
to provoke any of the rules.

> **CORRECTION — do not use `GET_STATUS`.** An earlier draft of this section said to score
> the run from the `GET_STATUS` broadcast. **That broadcast is an unimplemented stub**
> (`GatewayControlReceiver`: `Log.i(TAG, "GET_STATUS not yet implemented")`). GW-25 did put
> `watchdog_terminations` and `silent_bridge_episodes` into `GatewayStatus.toBundle()`, but
> nothing delivers that bundle, so the counters are computed and never surfaced. Filed as
> **H16** / GW-30. Score from logcat instead.

**Score it from logcat.** Every termination logs at ERROR with an `INVARIANT` prefix, and
that is the only reachable signal today:

```
adb -s <dev> logcat -c                      # clear before the run
# ... place the 30 calls ...
adb -s <dev> logcat -d | grep -c 'INVARIANT'          # must be 0
adb -s <dev> logcat -d | grep -E 'INVARIANT|Silent bridge'
```
`INVARIANT (AUDIT H9)` is the reverse orphan; a bare `INVARIANT:` is one of the other four
rules. **Any hit at all is a false positive and a fail** — every one of the 30 calls is a
healthy call. Silent-bridge lines are detection-only and are not failures, but a *repeating*
one every 3 s means the once-per-episode latch broke.

1. **30 normal calls of varying length, both directions, zero terminations.** Vary the
   length deliberately — 5 s, 30 s, several minutes — because the max-duration anchor is
   re-armed per call and a leaked anchor would only show on a long one.
2. **Weight it towards inbound, and set `MODE_ANSWER_FIRST` for at least ten of them.** That
   is the dangerous shape: the GSM leg is answered first, so it goes ACTIVE and is adopted
   while the SIP retry chain still has up to 20 s to run, going ACTIVE has already cancelled
   the 30 s incoming timeout, and `isInGracePeriod()` is false because
   `gsmCallPlacedTime` is only ever set by `placeGsmCall()`. Every signal says "orphan"; the
   45 s dwell is the only thing that says otherwise.
3. **Toggle the default dialler off and on during an idle period.** Expect
   `No InCallService bound - skipping the orphan rules this tick` and **no** termination.
   Do it again with a call up if you can bear the risk — that is the transient-unbind false
   positive, and the log line is the evidence the guard ran.
4. **Run a BRIDGE-mode diagnostic test call** (in-app test call, mode `bridge`). It sets
   `Wiring.active` with `CallManager` at `IDLE` and no GSM leg at all. It must survive; it
   satisfies no rule's predicate and never calls `startAudioStreams()`.

Only then provoke the rules:

5. **Reverse orphan (H9):** answer an inbound GSM call, then kill the SIP leg from the PBX.
   The GSM leg must be hung up within **45 s + one tick ≈ 48 s** — not the ~8 s the brief
   originally guessed, because that number predates the dwell. Confirm with the carrier call
   log that the call actually ended, and confirm `watchdog_terminations` incremented by 1.
6. **D6:** the hard case to stage. Look for
   `INVARIANT (AUDIT D6): GSM leg N is tracked but Telecom no longer has it`. If the
   pre-existing `GSM source cross-check: modem is IDLE but GSM leg N is still tracked` line
   appears and is *not* followed by the D6 line within a tick, that is a finding — it means
   Telecom still reports the leg live while the modem says otherwise.
7. **Silent bridge:** point the audio profile at a wrong PCM device via config and place a
   call. Within ~13 s expect one `INVARIANT: bridged and streaming, but pjmedia has requested
   no frame for …` **and exactly one** conference-wiring dump showing
   `local->call(TX to SIP)=false`. **If you see the dump repeating every 3 s the latch is
   broken** — that is the regression this check is really for. `watchdog_terminations` must
   stay 0: this is detection only.
8. **Deadlines:** temporarily shorten `MAX_CALL_DURATION_MS` to seconds, confirm it fires
   exactly once with its error log, then restore it. `TERMINATING_DWELL_MAX_MS` is not
   reachable and should not be chased — see the javadoc.

**H9b** needs `call.answer()` to throw, which is not reproducible on demand. If it ever does,
the leg must stop ringing and the log must carry
`GSM leg disconnected after answer() failed (AUDIT H9b)`.

### GW-21

- 20 SMS in a burst, all forwarded exactly once, no `Skipped … frames`.
