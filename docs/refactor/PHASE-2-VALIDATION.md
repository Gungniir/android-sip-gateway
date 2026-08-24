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

## Wave 2 — `phase-2-wave-2` (GW-24, GW-27, GW-22) — *pending*

Will be filled in when the wave lands. Expected headline checks:
- **GW-27's fixture test** — restart with the 8 unread SMS present; **zero** re-forwards at
  the PBX. Then fault-inject the read-flag write and repeat: still zero. That second run is
  the one that proves the persisted set rather than the flag is carrying correctness.
- **GW-24 + GW-20 together** — select mute controls in the web UI, place a call, confirm
  those controls are actually muted, then confirm the mic is live afterwards via a normal
  call. This is the first time the Qualcomm mute path executes at all.
- **GW-22** — 500-cycle soak, `callsCreated - callsDeleted` equal to active calls at the end,
  zero tombstones. A premature `Call` delete presents as a native crash, so the soak is also
  the safety test.

## Wave 3 — `phase-2-wave-3` (GW-21, GW-25) — *pending*

- **GW-25 false-positive check, before merge**: 30 normal calls of varying length, both
  directions, **zero** watchdog terminations. A watchdog that kills healthy calls is worse
  than no watchdog.
- Specifically exercise **inbound** calls: the grace period does not exist on that direction
  (`gsmCallPlacedTime` is only set by `placeGsmCall`), and the inbound flow spends up to ~20 s
  ringing with `CallManager` at `IDLE`.
- **GW-21**: 20 SMS in a burst, all forwarded exactly once, no `Skipped … frames`.
