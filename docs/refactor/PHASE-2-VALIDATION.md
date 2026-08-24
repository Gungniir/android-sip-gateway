# Phase 2 — on-device validation, wave by wave

Every wave is tagged (`phase-2-wave-N`) and a debug APK is kept in
`release-output/phase-2-waves/` (gitignored). Validate a wave by checking out its tag —
or by flashing its APK — so a regression is attributable to one wave rather than to the
whole phase.

**Nothing below has been run.** All of it needs a human on the handsets.

Devices: **merlinx** = Redmi Note 9, MT6768, debug build, assertions armed, `055f14050405`.
**lavender** = Redmi Note 7, SDM660, release build, `c31adecd`.

Standing hazards while testing:
- **Never read `/proc/asound/*/status` during a call on merlinx** — kernel panic. `tinymix`
  and `hw_params` are safe.
- **Do not clear the SMS fixture on merlinx**: 8 unread messages, `_id` 3/4/5/6/8/10/11/12.
  They are the GW-27 reproduction. They re-forward on every restart until GW-27 lands —
  that is expected, not a new bug.
- Use the SDK's `adb` (`~/Android/Sdk/platform-tools/adb`). Broadcasts need
  `-p org.onetwoone.gateway`, and `-f 0x00000020` after a `force-stop`.

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

## Wave 3 — `phase-2-wave-3` (GW-21, GW-25) — *pending*

- **GW-25 false-positive check, before merge**: 30 normal calls of varying length, both
  directions, **zero** watchdog terminations. A watchdog that kills healthy calls is worse
  than no watchdog.
- Specifically exercise **inbound** calls: the grace period does not exist on that direction
  (`gsmCallPlacedTime` is only set by `placeGsmCall`), and the inbound flow spends up to ~20 s
  ringing with `CallManager` at `IDLE`.
- **GW-21**: 20 SMS in a burst, all forwarded exactly once, no `Skipped … frames`.

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
