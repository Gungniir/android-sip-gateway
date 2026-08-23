# GW-01 — Native PCM lifetime: `pcm_close()` frees memory the RT thread is reading

**Phase** 0 · **Severity** P0 (process crash) · **Closes** AUDIT A1, A2
**Files** `app/src/main/cpp/gsm_audio_jni.c`, `app/src/main/java/org/onetwoone/gateway/GsmAudioNative.java`
**Depends on** nothing · **Conflicts with** GW-08, GW-23 (same files — coordinate)

## Problem

`close()` (`gsm_audio_jni.c:209`) takes `g_ctx->lock`. `readFrame` (`:244`) and
`writeFrame` (`:277`) **never take it**. Both test the plain `int g_ctx->is_open` and
then dereference `g_ctx->capture_pcm` / `g_ctx->playback_pcm`.

`pcm_read` blocks until a period is available (~20 ms at 8 kHz / 160 frames). The
pjmedia RT thread is inside it roughly 100 % of the time during a call.
`GsmAudioPort.stopCapture()` (`GsmAudioPort.java:357`) calls `close()` from the main,
pjsua-worker or `ConfigReload` thread the moment a call ends. `pcm_close()` frees the
`struct pcm`; the RT thread then reads freed memory.

`open()` (`:139-202`) has the mirror problem: it writes every `g_ctx` field with no lock.

## Failure scenario

1. GSM call is up; pjmedia RT thread is blocked in `pcm_read(capture_pcm, …)`.
2. Remote party hangs up → `onSipCallState(DISCONNECTED)` on a pjsua worker →
   `terminateAllCalls()` → `onCallsTerminated()` → `audioBridge.stopAudioStreams()` →
   `GsmAudioPort.stopCapture()` → `GsmAudioNative.close()`.
3. `close()` acquires the lock (uncontended — the reader never takes it),
   `pcm_close(capture_pcm)` frees the struct and its mmap'd buffer.
4. The RT thread returns from the kernel into freed memory → SIGSEGV.

Presents as a non-deterministic native crash at end-of-call. Probability rises with call
frequency; it is exactly the "stop the call before the phone handles it" class the
project is chasing.

## Required change

Refcount in-flight I/O and make `close()` drain it. Do **not** simply take the mutex in
`readFrame` — that would make `close()` block for a full period behind a reader and, worse,
serialise capture against playback.

Shape:

```c
struct gsm_audio_ctx {
    ...
    int is_open;                 /* guarded by lock */
    int active_io;               /* guarded by lock: readers+writers in flight */
    pthread_mutex_t lock;
    pthread_cond_t  io_drained;
};

/* Enter: returns the pcm to use, or NULL if closed. */
static struct pcm *io_acquire(int capture) {
    pthread_mutex_lock(&g_ctx->lock);
    if (!g_ctx->is_open) { pthread_mutex_unlock(&g_ctx->lock); return NULL; }
    struct pcm *p = capture ? g_ctx->capture_pcm : g_ctx->playback_pcm;
    if (p) g_ctx->active_io++;
    pthread_mutex_unlock(&g_ctx->lock);
    return p;
}

static void io_release(void) {
    pthread_mutex_lock(&g_ctx->lock);
    if (--g_ctx->active_io == 0) pthread_cond_broadcast(&g_ctx->io_drained);
    pthread_mutex_unlock(&g_ctx->lock);
}
```

`close()`:
1. lock, `is_open = 0`;
2. `pcm_stop()` on both PCMs **while still holding the lock** — this is what unblocks a
   reader parked in the kernel; the structs are still valid at this point;
3. `pthread_cond_timedwait(&io_drained, &lock, now + 250 ms)` until `active_io == 0`
   (bounded — log and proceed on timeout rather than hanging the caller forever);
4. `pcm_close()` both, `mixer_close()`, NULL the pointers;
5. unlock.

`open()` must take the same lock for the whole body, and must refuse to run when
`is_open` is already set (it does check, at `:124` — move that check inside the lock).

`isOpen()` (`:522`) must read `is_open` under the lock.

Also fold in the two ordering guarantees the file already documents in comments so a
future edit cannot lose them: playback opens before capture (`:168-173`), and each device
gets its own `pcm_config` copy (`:149-151`).

## Acceptance criteria

- [ ] `readFrame`, `writeFrame`, `isOpen`, `open`, `close` all mediate `g_ctx` access
      through the mutex; no field of `g_ctx` is read or written outside it.
- [ ] `close()` cannot return while any `pcm_read`/`pcm_write` is in flight, and cannot
      block indefinitely (bounded wait, logged on timeout).
- [ ] `pcm_stop()` is issued before the drain wait, so a parked reader actually wakes.
- [ ] Playback-before-capture open order and per-device configs are preserved.
- [ ] No allocation is added to the `readFrame`/`writeFrame` hot path.
- [ ] `pthread_cond_t` is initialised alongside the mutex at `:136` and never destroyed
      while `g_ctx` is reachable.

## Verification

1. `./gradlew assembleDebug` — the CMake build must be warning-clean for this file.
2. On-device stress: script 50 call cycles where the hangup is issued from the PBX side
   at a random offset in the first 3 s (the window where `pcm_read` is always in flight):
   ```
   adb shell am broadcast -p org.onetwoone.gateway -a org.onetwoone.gateway.TEST_CALL ...
   ```
   `logcat` must show 50 clean `Audio closed` lines and zero `Fatal signal` / tombstones.
3. Confirm the drain path is actually exercised: temporarily log `active_io` at the top of
   `close()` and confirm it is non-zero on most hangups.

## Risk

Medium. A bug in the drain logic turns a rare crash into a deterministic hang on hangup.
Keep the wait bounded and log the timeout loudly. Test the timeout path by artificially
holding a reader.

## Out of scope

The per-frame `malloc` in the resampler (`:304`) — that is GW-23.
