# GW-04 — `AudioProfile` original-value maps race across three threads → mic left muted

**Phase** 0 · **Severity** P0 (device unusable) · **Closes** AUDIT B2
**Files** `audio/QualcommAudioProfile.java`, `audio/MediaTekAudioProfile.java`, `audio/AudioProfile.java`
**Depends on** nothing · **Conflicts with** GW-08 (both reason about `GsmAudioPort` call order)

## Problem

Both profiles save the pre-call mixer values so `teardownMixer` can restore them:

- `QualcommAudioProfile.java:40-41` — `micOriginalValues` (`HashMap<String,Integer>`),
  `micOriginalEnumValues` (`HashMap<String,String>`)
- `MediaTekAudioProfile.java:53` — `originalValues` (`LinkedHashMap<String,Integer>`)

Three threads touch them with no synchronisation:

| Method | Thread | Operation |
|---|---|---|
| `setupMixer` | `GsmAudioOpen` (`GsmAudioPort.java:257`) | `clear()` then `put()` per control |
| `enforceMixer` | `MixerEnforce` (`GsmAudioPort.java:315`) | reads the control list (not the map) every 2 s |
| `teardownMixer` | main / pjsua worker / `ConfigReload` (`GsmAudioPort.java:360`) | iterate + `get()`, then `clear()` |

## Failure scenario

Back-to-back calls, or a hangup racing a start:

1. Call 1 ends → `stopCapture()` on a pjsua worker begins `teardownMixer`, iterating
   `originalValues`.
2. Call 2 starts → `startCapture()` on main spawns `GsmAudioOpen` → `setupMixer` →
   `originalValues.clear()` mid-iteration.
3. `ConcurrentModificationException` out of `teardownMixer` — or, if the timing lands
   between `clear()` and the first `put()`, teardown reads an **empty map** and restores
   nothing.

Result: the local mic mute (`DEC* Volume = 0`, `DEC* MUX = ZERO` on Qualcomm;
`PCM_2_PB_CH* ADDA_UL_CH* = 0` on MediaTek) is never lifted. **The phone has no
microphone until an unrelated audio-path cycle happens to reset it.** On MediaTek the
same map holds the routing switches, so the modem voice path stays crossbar-patched too.

A second, quieter failure: `setupMixer`'s `clear()` at
`QualcommAudioProfile.java:79-80` discards originals from a *still-active* previous
session, so even a clean sequential teardown restores stale values.

## Required change

The real fix is ordering, not locking — but do both, because Phase 0 lands before the
control thread exists.

1. **Make the saved state immutable and swapped atomically.** Replace the mutable maps
   with a single `volatile MixerSnapshot` field (an immutable value object holding both
   maps, built fully before publication):
   ```java
   private volatile MixerSnapshot saved;   // null == not set up
   ```
   `setupMixer` builds a local `Map`, populates it, wraps it in an unmodifiable
   `MixerSnapshot`, and assigns it **once** at the end.
   `teardownMixer` does `MixerSnapshot s = saved; saved = null; if (s == null) return;`
   then restores from `s` — it can no longer see a half-built map, and a concurrent
   setup cannot clear it out from under the iteration.
2. **Make `setupMixer`/`teardownMixer` idempotent and self-guarding.** A `teardownMixer`
   with `saved == null` must be a logged no-op, not a partial teardown. A `setupMixer`
   with `saved != null` must log an error (it means the previous session never tore down)
   and restore the old snapshot before taking a new one — never silently discard it.
3. Document the contract on the `AudioProfile` interface
   (`audio/AudioProfile.java`): setup/teardown are paired, teardown is idempotent,
   `enforceMixer` never touches saved state (both implementations already honour the
   last part — `QualcommAudioProfile.java:110`, `MediaTekAudioProfile.java:88` — keep it
   that way and say so).

Do **not** change any control name, value, or the order in which controls are written.
Those are validated per-SoC and reverse-engineered.

## Acceptance criteria

- [ ] Neither profile holds a mutable map field; saved originals live in one immutable
      object behind a single `volatile` reference.
- [ ] `teardownMixer` with nothing saved is a logged no-op.
- [ ] `setupMixer` over a live snapshot restores it first and logs an error.
- [ ] `enforceMixer` still reads only the static control lists — no saved state.
- [ ] Control names, values and write order are byte-identical to today.

## Verification

1. JVM unit test with a fake `GsmAudioNative` shim: run `setupMixer` and `teardownMixer`
   concurrently from two threads across 1000 iterations; assert no exception and that the
   final value of every control equals its original.
2. On-device, back-to-back calls (hang up and redial within 1 s), 20 cycles, both
   profiles if both devices are available. After the last call:
   ```
   adb shell su -c 'tinymix -D 0 get "DEC1 Volume"'          # Qualcomm
   adb shell su -c 'tinymix -D 0 get "PCM_2_PB_CH1 ADDA_UL_CH1"'   # MediaTek
   ```
   must show the idle values, and a normal dialler call must have working mic + earpiece.
3. Grep logcat for `ConcurrentModificationException` — zero.

## Risk

Low. The change is mechanical and the restore values are unchanged. The one behaviour
change (setup over a live snapshot restores first) makes an already-broken case correct.
