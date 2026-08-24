# GW-23b — Decouple ALSA from the conference callback (closes E5)

**Phase** 2 (tail) · **Severity** P0 — burns real GSM minutes · **Closes** AUDIT E5
**Files** `GsmAudioPort.java`, `cpp/gsm_audio_jni.c`, `cpp/tinyalsa/**`
**Depends on** GW-01 (landed), GW-23a (landed) · **GATED — see §3. Do not start until G1 and G2 pass.**

## 1. The problem

**Measured on device, five SIP-initiated hangups: 23.74 s, 4.47 s, 50.69 s, 1.79 s, 4.86 s**
between the `BYE` and the media being detached. The GSM call stays connected — and billed —
for that whole window after the SIP party hung up. This is the most expensive open defect in
the audit.

**The mechanism is proven, not inferred.** Native backtraces captured 2026-08-23, both threads
at the same instant (full dumps in `evidence/E5-conf-mutex-starvation.md`):

- *Holder* — the pjmedia conference clock thread, inside our SWIG director callback, blocked
  in `__ioctl` ← `pcm_read` ← `Java_..._readFrame` ← `GsmAudioPort.onFrameRequested`, with
  `conf.c get_frame` above it holding `conf->mutex`.
- *Waiter* — the pjsua worker handling the received BYE, in `pj_mutex_lock` ←
  `pjmedia_conf_remove_port` ← `pjsua_aud_stop_stream` ← `pjsua_media_channel_deinit`.

The conference bridge holds its mutex across the director callback; the callback blocks in an
ALSA `ioctl`; the callback re-enters every 20 ms, so a plain non-FIFO `pthread_mutex` acquire
starves for an unbounded time. That matches the measured 1.8–50.7 s spread, which no timeout
constant would produce.

The asymmetry confirms it: **GSM-initiated** hangups take 15 ms / 107 ms / 1.77 s, because
`terminateAllCalls` stops the audio port *before* pjsua touches the media.

## 2. The fix

Only one thing addresses the mechanism: **take ALSA out of the conference callback.**

A dedicated I/O thread owns `pcm_read` / `pcm_write`. `onFrameRequested` / `onFrameReceived`
do nothing but copy from/to a lock-free ring buffer and return immediately. The conference
mutex is then never held across a blocking syscall.

GW-23a deliberately did **not** do this. Its items (bulk JNI copy, preallocated scratch,
hoisted silence, counters, dropping `isOpen()`) all keep `pcm_read`/`pcm_write` inside the
callback. They shorten the hold; they do not remove it.

## 3. Why this is gated — read before writing any code

> **GW-23a's scope was safe precisely because it preserved something this ticket destroys.**

The blocking read inside the conference callback is, accidentally, what protects against
**A1** (native use-after-free on a mid-frame hangup). Not because it blocks — because of the
**`conf->mutex` happens-before edge**:

1. `conf->mutex` is held across `get_frame`, which is where our callback runs.
2. `pjmedia_conf_remove_port`'s first act is to take that same mutex.
3. So when `remove_port` returns, our callback is provably not in flight, and once the port is
   removed the conference never calls it again.
4. Both teardown directions inherit this: SIP-side because `pjsua_media_channel_deinit` runs
   before Java sees `DISCONNECTED`; GSM-side because `stopBridge` unwires before
   `stopAudioStreams`.

That is why **33 on-device teardowns produced zero `close: draining N in-flight PCM I/O`
lines. GW-01's drain has never executed.** "Reviewed" is not "exercised".

GW-23b is exactly the change that makes it execute on *every* call.

### Gate G1 — force the drain to run, on hardware, before starting

A debug-only path that starts a background `readFrame` loop and calls `close()` underneath it.

- Assert `active_io > 0` at `close()` entry — otherwise the test is not testing anything.
- `close: draining N in-flight PCM I/O` must appear.
- **Zero tombstones**, on **both** SoCs.
- Run it enough times to catch a rare interleaving, not once.

### Gate G2 — bound the `EPIPE` restart, or replace draining with joining

`tinyalsa`'s `pcm_read` (`pcm.c:558-588`) and `pcm_write` (`:519-555`) both `continue`
**unboundedly** on `EPIPE`, and `PCM_NORESTART` is never set — `open_pcm_adaptive` passes only
`PCM_IN` / `PCM_OUT`. So:

```c
if (ioctl(pcm->fd, SNDRV_PCM_IOCTL_READI_FRAMES, &x)) {
    pcm->prepared = 0; pcm->running = 0;
    if (errno == EPIPE) { pcm->underruns++; continue; }   /* <-- unbounded */
    ...
}
```

`close()`'s `pcm_stop()` issues `DROP` and clears `pcm->running`. The blocked `READI` returns
an error; **if that errno is `EPIPE`, the reader does not exit — it `continue`s, sees
`!pcm->running`, calls `pcm_start()`, and re-arms the hardware `close()` just stopped.**

End of call is exactly when the modem voice path is being torn down by the HAL, so persistent
`EPIPE` there is plausible, not theoretical. The reader then spins holding `active_io` until
the 250 ms deadline expires, `close()` logs `!!! PCM drain gave up`, and calls `pcm_close()`
**while the reader is still inside the loop dereferencing `pcm->fd`.** That is A1, live and
deterministic.

**Prefer joining a real I/O thread over draining a refcount.** Joining is strictly stronger and
removes this whole class: there is no "gave up" branch left to reason about.

## 4. Hard prohibitions

1. **Never hoist `io_acquire` out of per-frame scope.** The obvious I/O-thread optimisation —
   acquire once, read many — turns the refcount into a session-long hold; `close()` then blocks
   the full 250 ms and frees under an active reader. Guaranteed UAF.
2. **`GsmAudioNative.close()` is reachable from two threads** — the GatewayControl thread
   (`stopCapture` → `releaseLocked`) and the `GsmAudioOpen-N` worker (superseded/failed open).
   Any I/O thread must be stopped from **both**, or a superseded open leaves a thread running
   against a closed device.
3. **The RT callbacks must never block, allocate, or take a lock the control thread can hold
   across I/O.** After this change they should do strictly less than they do now.
4. **pjmedia assertion failures are `abort()`, not exceptions.** A `try/catch` proves nothing.
5. Do not shorten the 20 × 500 ms open-retry policy — it is tuned for the modem voice path
   coming up late on SIP-first calls.
6. `stopCapture()` already blocks up to ~1.75 s (1000 ms open-join + 500 ms enforce-join +
   250 ms drain, AUDIT H2c). Do not add to that budget without a plan.

## 5. Acceptance criteria

- [ ] G1 and G2 both passed on both SoCs **before** implementation started.
- [ ] `pcm_read` / `pcm_write` no longer execute inside the pjmedia conference callback.
- [ ] SIP-initiated hangup: media detached within a bound comparable to the GSM-initiated
      path (tens of ms, not seconds). **Re-measure the five-hangup spread.**
- [ ] The I/O thread is stopped from both `close()` paths.
- [ ] Zero tombstones across a full call matrix on both SoCs.
- [ ] Audio quality unchanged — no dropouts, no pitch/rate change, error counters no worse.

## 6. Verification

1. **The headline number.** Five SIP-initiated hangups, timing `BYE` → `UDP media transport
   detached` from logcat. Baseline is 23.74 / 4.47 / 50.69 / 1.79 / 4.86 s. Anything still in
   the seconds is a failure.
2. Re-measure on a **release** build. The measured baseline was a debuggable build with
   `CheckJNI` active, which inflates every JNI crossing — it worsens the hold but is not the
   cause. Do not quote the debug figures as the release baseline.
3. Recorded audio both directions, both SoCs, against a pre-change recording. On merlinx a
   resampler regression sounds like pitch/speed shift; on either device a ring-buffer
   regression sounds like periodic dropouts or a stutter at the buffer period.
4. Zero tombstones. A premature free presents as a native crash, so the call matrix is the
   safety test.
5. **Do not read `/proc/asound/*/status` during a call on merlinx** — kernel panic.

## 7. Risk

**Highest in the phase.** This ticket moves the audio path off the thread that has been
serialising it since the feature was written, in a subsystem where "it works on this exact
device" is load-bearing. It also converts GW-01's never-exercised drain into a per-call
operation.

The gates exist because the failure mode is not a wrong number or a bad sound — it is a
use-after-free in native code on a phone meant to run unattended. If G1 or G2 cannot be made
to pass, **that is a result**: it means A1 is reachable today and should be fixed on its own
before anything is decoupled.

## 8. Note for whoever dispatches this

Fresh agent worktrees start at `origin/main`, **not** at local HEAD (`worktree.baseRef`
defaults to `fresh` and there is no override in `.claude/settings.json`). Pin the base
explicitly as step 0 of the brief — `git checkout -B <branch> <sha>` — and require the agent
to report its branch name back. Two Phase 2 agents silently created their own branches, and
one `git merge` consequently reported "Already up to date" and would have landed nothing.
