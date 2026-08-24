# GW-20 — `RootHelper`: unsynchronized statics, unsafe output capture, thread churn

**Phase** 2 · **Severity** P2 · **Closes** AUDIT H1
**Files** `RootHelper.java`, callers in `DeviceMuteManager.java`, `audio/QualcommAudioProfile.java`, `ui/TinymixManager.java`
**Depends on** nothing · **Conflicts with** GW-02 (`DeviceMuteManager.java`)

## Problem

1. **Unsafe output capture.** `execRoot` (`:61`) collects stdout into a `StringBuilder`
   appended by a reader thread (`:70-79`) and read by the caller at `:111` after
   `outputThread.join(1000)` (`:97`). If the join **times out** — a slow `su`, a large
   `tinymix` dump — the caller reads a `StringBuilder` that is still being mutated.
   `StringBuilder` is not thread-safe: this can throw
   `StringIndexOutOfBoundsException` from `toString()`, or return torn output that the
   mixer-value parsers then misread as a control value.
2. **Unsynchronized statics.** `hasRoot`, `suProcess`, `suOutputStream` (`:21-23`) are
   plain statics touched from any thread. `startRootShell` (`:137`) can spawn two `su`
   processes; `execInShell` (`:156`) can NPE on a stream another thread just nulled
   (`:168-169`).
3. **Thread churn.** Every `execRoot` spawns a process plus two reader threads.
   `setupAlsaPermissions` (`:242`) — two `execRoot` calls — runs on *every* capture open
   (`GsmAudioPort.java:252`), and `DeviceMuteManager` shells out twice per control
   (~20 processes per mute). `QualcommAudioProfile.readMixerControlValue` (`:152`) and
   `readMixerControlEnum` (`:173`) each spawn their own process with no timeout at all —
   an unbounded `waitFor()` on the calling thread.
4. **Inconsistent mechanisms.** Three different ways to read a mixer control exist:
   `RootHelper.execRoot` + `tinymix`, `Runtime.exec("su -c tinymix")` directly
   (`DeviceMuteManager.java:427`, `:456`), and the extracted `tinymix` binary
   (`QualcommAudioProfile.java:175`). Plus the native `GsmAudioNative.getMixerControl`
   (`GsmAudioNative.java:80`), which is what MediaTek already uses and is the cheapest.

## Required change

1. **Fix the output capture first** — it is the actual correctness bug. Use a
   thread-safe handoff: have each reader thread build a private `StringBuilder` and
   publish the finished `String` through a `BlockingQueue`/`Future`, or simply
   `synchronized` on the builder for both append and read. Never read a builder another
   thread may still be appending to. If the join times out, return `null` (treat it as a
   failed command) rather than returning partial output.
2. **Serialise root access.** One `RootHelper` executor (a single-thread
   `ExecutorService`) through which every `execRoot`/`execRootCode` runs. Guard
   `hasRoot` / `suProcess` / `suOutputStream` with it or make them `volatile` and the
   shell lifecycle `synchronized`.
3. **Prefer the native mixer API for reads.** `GsmAudioNative.getMixerControl` already
   exists and needs no process spawn. Migrate `QualcommAudioProfile.readMixerControlValue`
   and `DeviceMuteManager.readIntControl` to it. ENUM reads have no native getter today —
   either add one (`mixer_ctl_get_enum_string`, mirroring `setMixerControlEnum` at
   `gsm_audio_jni.c:433`) or keep exactly **one** shell-based path and delete the other
   two. Adding the native ENUM getter is the better trade: it removes ~20 process spawns
   per call setup.
4. **Bound every `waitFor()`.** `QualcommAudioProfile:159`, `:181` and
   `DeviceMuteManager:432`, `:461` call `p.waitFor()` with no timeout — a hung `su`
   blocks the caller forever. Use the timeout overload and destroy on expiry.
5. **Cache `setupAlsaPermissions`.** It currently runs `setenforce 0` + `chmod 666
   /dev/snd/*` on every open. The comment at `GsmAudioPort.java:249-251` explains why it
   is re-applied (the HAL recreates the nodes), which is legitimate — but the `setenforce`
   half is idempotent and process-wide. Split them: `setenforce` once per process, `chmod`
   per open.
6. **`execInShell`/`startRootShell`/`stopRootShell` have no callers.** Delete them
   (see GW-31) rather than fixing them.

## Acceptance criteria

> **Status after the GW-20 change.** Read PHASE-2-PLAN §2.1 first — it overrides this brief,
> and it is where the two headline items below come from. AUDIT **H1** and **B1e** carry the
> full record.

- [x] No `StringBuilder` is read while another thread may append to it. — each reader owns
      its builder and publishes a finished `String` through a `FutureTask`.
- [x] A join timeout yields a failed result, never partial output. — `EXIT_NO_OUTPUT` /
      `EXIT_TIMED_OUT`, both negative, both with empty output.
- [x] **`execRoot`'s return contract** (not in this brief; PHASE-2-PLAN §2.1 added it, and it
      is the systemic bug). `RootResult` carries exit code + stdout + stderr + `success()`;
      a non-zero exit can no longer be reported as success. Closes the `execRoot` half of
      **H13**. **GW-27 consumes this API.**
- [x] **B1e** (not in this brief either, and the highest-value item):
      `QualcommAudioProfile.TinymixControls` migrated to `GsmAudioNative.getMixerControl` /
      `getMixerControlEnum`, and a control whose original cannot be read is no longer muted
      with a fabricated one.
- [x] Every `Process.waitFor()` has a timeout and is killed on expiry, in every file GW-20
      owns. `destroy()` not `destroyForcibly()` — the latter is API 26 against minSdk 23.
- [x] Exactly one mechanism per mixer read type: `MixerControls.NATIVE` for both.
- [x] `setenforce` runs once per process; `chmod` still runs per open — implemented **inside
      `RootHelper.setupAlsaPermissions`**, not at `GsmAudioPort`'s call sites (GW-23a owns
      that file).
- [ ] ~~All root command execution is serialised through one executor.~~ **Deliberately not
      done** — PHASE-2-PLAN §2.1. `PowerController`'s ~30 s burst would stall per-call
      `setupAlsaPermissions` at service-start time, and `SmsHandler.markAsReadWithRoot` still
      runs on main, so serialising would let main block behind another thread's `su`. The
      reasoning is in `RootHelper`'s class javadoc so it is not silently re-litigated.
- [ ] ~~Dead persistent-shell API deleted.~~ **GW-31's** sweep, per ROADMAP rule 8. Eight
      dead methods are listed in AUDIT **H1c** and marked `@Deprecated` in the meantime.
      `execRootCode`'s pipe deadlock was fixed rather than left, by delegating to `run()`.
- [ ] **On-device: the B1e value-by-value check** (this brief's own Risk section).
      `TinymixManager.verifyNativeReads()` runs it at the end of `detectControls()`; tap
      "Detect mixer controls" and grep logcat for `B1e native-vs-tinymix`. Must read
      **0 mismatched, 0 unreadable** on each SoC. Not yet run on hardware.

## Verification

1. Count process spawns during one call setup, before and after:
   ```
   adb shell su -c 'ps -A | grep -c tinymix'   # sampled during setup
   ```
   Expect a large reduction (target: zero for the read path if the native ENUM getter
   lands).
2. Stress the timeout path: point `execRoot` at `sleep 30` with a 1 s timeout, 100×, and
   confirm no exception and no leaked process (`adb shell ps -A | grep su`).
3. Mixer values read via the native path must match `tinymix` output exactly for every
   control in the active preset — verify once per SoC profile.
4. Full call cycle still mutes and restores correctly (GW-02/GW-04 tests must stay green).

## Risk

Low-medium. The mixer-read migration is the risky part: a native getter that returns a
different representation than `tinymix` parsing would silently corrupt the saved originals
and break restore. Verify value-by-value against `tinymix` before switching the write path
over.
