# GW-31 — Delete dead code that violates project rules or invites crashes

**Phase** 3 · **Severity** P2 · **Closes** AUDIT E3, H10 (partial)
**Files** `GatewayInCallService.java`, `audio/AudioBridgeManager.java`, `RootHelper.java`, `GsmAudioNative.java`
**Depends on** GW-12 (for `release()`) · **Conflicts with** GW-12

## Problem

Four pieces of unreferenced code that a future contributor could reasonably wire up, each
of which would break something specific:

1. **`GatewayInCallService.setMicrophoneMute` (`:410-420`)** calls
   `AudioManager.setMicrophoneMute()`. `CLAUDE.md` states this is forbidden: *"Never mute
   the mic via `AudioManager` — it breaks the `Incall_Music` ALSA playback path. Mic mute
   must go through the ALSA mixer (`DeviceMuteManager`)."* The class even carries a
   comment at `:40-41` explaining why the callback must not mute — and then keeps the
   method that would do it. No callers.

2. **`AudioBridgeManager.release()` (`:274-288`)** nulls the static `gsmAudioPort` while
   the pjmedia RT thread may be inside `onFrameReceived`. No callers — the codebase
   instead never releases, and `PjsipSipService.shutdownSip` (`:260-262`) carries a comment
   explaining that calling it "causes NullPointerException in onFrameReceived". A leak
   documented as a fix, with the loaded gun still on the table.

3. **`RootHelper.startRootShell` / `execInShell` / `stopRootShell` (`:137-194`)** — a
   persistent root shell with unsynchronized static state (AUDIT H1). No callers.

4. **`GsmAudioNative.setupQualcommCapture` / `setupQualcommPlayback` /
   `teardownQualcommMixer` (`:131-158`)** — superseded by `QualcommAudioProfile`
   (`setupMixer` / `teardownMixer`), which is the code actually in use. Keeping both means
   two definitions of the Qualcomm routing that can drift apart. No callers.

Also worth checking while here: `GsmAudioPort.stop()` (`:373-375`) is a one-line alias for
`stopCapture()` — confirm whether anything calls it and collapse if not.

5. **`DeviceMuteManager.setSoundCard`** — added by GW-24's sweep (Phase 2 plan §2.5). No
   callers, and its persistence was a fiction: it wrote `"sound_card"` into
   `device_mute_prefs`, a key nothing has ever read. The card actually used comes from
   `gsm_audio_config`'s `"card"` via `GatewayConfig.getAudioCard()`, re-read per mute by
   `refreshFromConfig()`. GW-24 removed the dead write (it could not stay: nothing outside
   `config/` may name a preference key any more) and left the method, which is now a plain
   field setter with no callers. Delete it, and check for a stale `sound_card` entry in
   `device_mute_prefs` on the test devices while you are there — GW-24 does not remove it,
   because it is not a value anything has ever consumed.

## Required change

Delete 1, 3 and 4 outright.

For 2, the choice depends on GW-12: either delete `release()` along with the
"never release" workaround comment in `shutdownSip`, or replace it with the RT-safe
`shutdown()` GW-12 specifies. **Do not leave it as-is.**

For 1, before deleting, confirm nothing reaches `AudioManager.setMicrophoneMute` anywhere:

```
grep -rn "setMicrophoneMute\|isMicrophoneMute" app/src/main
```

If anything else does, that is a bug, not a deletion — record it in AUDIT.md.

Add a short note to `CLAUDE.md` under Gotchas recording that the Qualcomm mixer routing
lives in `QualcommAudioProfile` only, so the `GsmAudioNative` helpers do not get
resurrected.

## Acceptance criteria

- [ ] `setMicrophoneMute` is gone; `grep` confirms no `AudioManager` mic-mute call remains
      anywhere in `app/src/main`.
- [ ] `AudioBridgeManager.release()` is deleted or RT-safe, and the workaround comment in
      `shutdownSip` is resolved either way.
- [ ] The persistent-root-shell API is gone.
- [ ] The superseded Qualcomm mixer helpers in `GsmAudioNative` are gone.
- [ ] `DeviceMuteManager.setSoundCard` is gone.
- [ ] `CLAUDE.md` records where the Qualcomm routing lives.
- [ ] `./gradlew assembleDebug` and `./gradlew test` green; `./gradlew lintDebug`
      introduces no new issues.

## Verification

1. Build and run a full call cycle — a deletion that breaks something shows up
   immediately.
2. `grep -rn "setupQualcommCapture\|setupQualcommPlayback\|teardownQualcommMixer\|execInShell\|startRootShell\|setMicrophoneMute" app/src` → no hits.
3. Confirm the Qualcomm device still mutes and restores correctly (the GW-04 mixer check).

## Risk

Low. These have no callers. The only real risk is deleting something a *future* intended
feature needed — in which case it belongs in git history, not in the tree.
