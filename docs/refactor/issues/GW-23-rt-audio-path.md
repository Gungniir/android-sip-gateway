# GW-23 — Real-time audio path: 16 000 JNI round-trips/s and a `malloc` per frame

**Phase** 2 · **Severity** P2 (audio quality, battery) · **Closes** AUDIT H2, H3
**Files** `GsmAudioPort.java`, `cpp/gsm_audio_jni.c`
**Depends on** GW-01 · **Conflicts with** GW-01, GW-08, GW-12

---

## Status — split per PHASE-2-PLAN §2.4

**GW-23a — code complete, on-device verification outstanding.** Closes AUDIT **H2**,
**H2b**, **H3** and the newly filed **H2e**. Items §1–§5 of this brief are done; §5's
prohibition was honoured — ALSA still runs inside the pjmedia conference callback.

**GW-23b — not started, gated.** The E5 fix (dedicated I/O thread + ring buffer) is a
separate ticket and does not begin until gates G1/G2 in PHASE-2-PLAN §4 pass.

### What GW-23a actually changed

| Brief item | Outcome |
|---|---|
| §1 bulk JNI copy | Option 1 taken — the copy is one `memcpy` in C. Needs a new hand-written class, `org.pjsip.pjsua2.PjByteVectorAccess`, to reach `protected static getCPtr`. **Creates an ABI dependency** — see below. |
| §2 preallocated resample scratch | Allocated in `open()` (960 samples), borrowed through GW-01's `io_ref`, freed in `close()` after the drain. Bounds-checked. |
| §3 hoist the silence frame | Fill removed entirely; `size = 0`, `type = NONE`. Confirmed against pjproject 2.14.1 `pjsua2/media.cpp:get_frame` and `pjmedia/conference.c`, not by reasoning. |
| §4 cheap counters | `AtomicLong`, single writer, no lock. Read accessors added for GW-25. |
| §4b drop `isOpen()` | Done. Required making `readFrame`/`writeFrame` three-valued (*n* / `0` closed / `-1` error) so the error counters stay comparable. |
| §5 no new locks | Honoured. `io_acquire` remains the only critical section, per frame, never hoisted. |
| — | **H2e**, new: `onFrameReceived` passed the whole reusable buffer to `writeFrame`, so a short frame replayed the previous frame's tail. `writeFrame` now takes an explicit length. |

**The brief's numbers were wrong and are corrected in AUDIT H2.** `frameSize` is
**320 bytes**, so the loops ran 320 times, not 160 — **≈32 500 JNI transitions/s**. And
the transition count was never the main cost: ~4 000 `Short` boxes/s per direction plus
100 finalizable `ByteVector` wrappers/s from `MediaFrame.getBuf()` explain GC-pause
dropouts far better.

### The ABI dependency, stated plainly

`gsm_audio_jni.c` reads the first two pointer words of a `std::vector<unsigned char>` as
`__begin_`/`__end_`. That binds this code to `pj::ByteVector` being
`std::vector<unsigned char>` and to libc++'s vector layout. Verified by disassembling the
vendored `libpjsua2.so` (`ByteVector_doSize` → `ldp x8, x9, [x2]; sub x8, x9, x8`) and by
confirming it links `libc++_shared.so`, the same STL as `ANDROID_STL=c++_shared`.

Nothing writes a vector's control block or resizes one — sizing stays with pjsua2 — and
`GsmAudioPort`'s constructor cross-checks both copy directions against pjsua2's own
generated accessors at startup, falling back to the old loops on mismatch. **After any
PJSIP rebuild, check logcat for `bulk frame copy unavailable`.**

### On-device verification still owed

Nothing below has been run; all of it needs hardware.

**merlinx (MediaTek Helio G85, debug, assertions armed) — the only device that can
validate H3.** It is the only SoC where `capture_rate != playback_rate`, so it is the only
one that enters the resampler at all.
1. `GsmAudioPort` startup line must read `bulkCopy=true`. If it reads `false`, the ABI
   self-check failed and everything below is measuring the fallback path.
2. `Upsample scratch: 960 samples (1920 bytes)` must appear once per `open()`, and
   **never** `writeFrame: N out samples exceed the 960-sample scratch`.
3. Record both directions of a real GSM↔SIP call and compare against a pre-change
   recording. A wrong scratch size or a wrong `out_n` sounds like **pitch/speed shift**
   (chipmunk or slow-motion) or a **periodic 20 ms buzz** — not like dropouts.
4. `captureErr` / `playbackErr` in the `Native audio stopped` line must not be worse than
   baseline. They should now be *lower* at teardown: an end-of-call close is reported as 0
   and no longer counted as an error.

**lavender (Qualcomm, release).** Takes the no-resample fast path, so it validates
everything except H3.
1. `bulkCopy=true` at startup.
2. Two-way audio on a real call, both directions, compared against a pre-change recording.
   A wrong bulk copy sounds like **white noise, a constant tone, or silence** — the failure
   mode is a wholesale wrong buffer, not a subtle artefact. Anything in between (occasional
   clicks) points at the frame-length change instead.
3. Confirm the mic is live after the call via a normal non-gateway call (the mute-lease
   path is untouched but shares the teardown).

**Both SoCs:** full call matrix, zero tombstones, zero `ILLEGAL TRANSITION`, and CPU during
a call (`adb shell top -p $(pidof org.onetwoone.gateway)`) compared before/after — a
measurable drop is expected. Do **not** read `/proc/asound/*/status` on merlinx during a
call while profiling; it kernel-panics.

## Problem

**H2 — per-sample JNI in the RT callbacks.**
`onFrameRequested` (`:148`) fills the outgoing frame one element at a time:

```java
for (byte b : captureBuffer) { buf.add((short) (b & 0xFF)); }     // :160-162
```
and the silence paths do the same (`:168`, `:174`). `onFrameReceived` (`:192`) reads one
at a time:
```java
for (int i = 0; i < size; i++) { playbackBuffer[i] = (byte) (buf.get(i) & 0xFF); }  // :205-207
```

`ByteVector` is a SWIG wrapper over `std::vector`. At 8 kHz / 20 ms frames that is 160
elements × 50 frames/s × 2 directions ≈ **16 000 JNI transitions per second**, each with
a `std::vector::push_back` that may reallocate (`buf.clear()` at `:153` keeps capacity,
so mostly not — but the JNI cost dominates anyway).

This runs on the pjmedia RT thread. Every transition is a safepoint opportunity; a GC
pause here is an audible dropout, and the CPU cost is pure overhead on a battery-powered
device meant to run 24/7.

**H3 — `malloc`/`free` per frame in the resampler.**
`writeFrame` (`gsm_audio_jni.c:304`) allocates the upsample buffer on every frame:
```c
short *out = (short *)malloc((size_t)out_n * 2);
...
free(out);                                                          // :319
```
50 allocations/s on the RT thread, in the MediaTek path (8 kHz → 48 kHz), where `out_n` is
a fixed 960 samples.

**Minor, same area:** `onFrameRequested` sets
`PJMEDIA_FRAME_TYPE_NONE` on the error path (`:170`) while still writing a full frame of
silence — pjmedia treats `TYPE_NONE` as "no data", so the explicit silence fill is dead
work. Worth confirming and simplifying.

## Required change

1. **Bulk-copy across JNI.** Replace the per-element loops. Options, in order of
   preference:
   - Add a native method that takes the `byte[]` and the frame's underlying buffer
     directly — i.e. do the copy in C with `GetByteArrayRegion`/`SetByteArrayRegion`
     rather than through `ByteVector`. This removes `ByteVector` from the hot path
     entirely.
   - If the pjsua2 `MediaFrame` API forces `ByteVector`, check whether the vendored SWIG
     bindings expose a bulk `assign`/`reserve` + array overload. **Do not hand-edit the
     generated bindings** (`app/src/main/java/org/pjsip/pjsua2/**`) — if a bulk accessor
     is needed and absent, that is a PJSIP rebuild decision, out of scope here; take
     option 1 instead.
2. **Preallocate the resample scratch buffer.** Size it once at `open()` from
   `playback_rate / 50 * playback_channels` (the maximum a 20 ms frame can produce), store
   it on `g_ctx`, free it in `close()`. Guard it with the same lock discipline GW-01
   introduces — the buffer is per-context, and `writeFrame` already holds an I/O
   reference while using it.
3. **Hoist the silence frame.** Precompute a zero-filled buffer once in the constructor
   instead of filling it per frame.
4. **Keep the counters cheap.** `framesRequested`/`framesReceived` (`:66-67`) are plain
   `long` incremented on the RT thread and read from `stopCapture` — make them `volatile`
   or `AtomicLong`, but do **not** put them on a contended path.
4b. **Drop the redundant `isOpen()` pre-check (AUDIT H2b).** Since GW-01 landed,
   `readFrame`/`writeFrame` test `is_open` under the native lock and return -1 safely, so
   the `GsmAudioNative.isOpen()` calls at `:155` and `:196` are a third lock acquisition
   per frame per direction for no benefit. Remove them and rely on the -1 return.
5. **Do not add a lock the control thread can hold across I/O.** The RT callbacks must
   never wait on anything the control thread holds while shelling out to root or opening
   ALSA. GW-01's `io_acquire` is a short critical section — that is the only acceptable
   shape.

## Acceptance criteria

- [ ] No per-element JNI loop remains in `onFrameRequested` / `onFrameReceived`.
- [ ] No allocation occurs in `readFrame` / `writeFrame` / the RT callbacks after `open()`.
- [ ] The resample scratch buffer is allocated once per open and freed on close.
- [ ] Frame counters are safely published without adding contention.
- [ ] Audio quality is unchanged or better; sample rates, frame timing (20 ms) and the
      MediaTek 8 k→48 k conversion are behaviourally identical.

## Verification

1. **Audio quality is the acceptance test, not the profiler.** Run a diagnostic loopback
   call and a real GSM↔SIP call, record both directions, and compare against a
   pre-change recording. No new dropouts, no pitch/rate change.
   ```
   adb shell am broadcast -p org.onetwoone.gateway -a org.onetwoone.gateway.TEST_CALL --es mode loopback --ei duration 60
   ```
   The WAV lands in the app's external `diag/` dir (`SipTestCallManager.java:342`).
2. Error counters (`captureErrors`, `playbackErrors` in the `stopCapture` log line, `:362`)
   must not increase relative to baseline.
3. CPU: `adb shell top -p $(pidof org.onetwoone.gateway)` during a call, before and after.
   Expect a measurable drop.
4. MediaTek specifically: verify the 48 kHz playback path still works — this is the device
   where the resampler runs. Confirm two-way audio on a real call.

## Risk

Medium. The resampler and the frame plumbing are where "it works on this exact device"
lives. Change one thing at a time (scratch buffer first, then JNI bulk copy), verify audio
after each, and keep the recorded WAVs as evidence.

## Note

Do not read `/proc/asound/*/status` during a call on the Redmi Note 9 test device while
profiling — it kernel-panics. `tinymix` and `hw_params` are safe.
