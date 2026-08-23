# GW-12 — Audio bridge: control-thread ownership and generation-tagged wiring

**Phase** 1 · **Severity** P1 (can `abort()` the process) · **Closes** AUDIT E1, E2, E3
**Files** `audio/AudioBridgeManager.java`, `GsmAudioPort.java`, `diag/SipTestCallManager.java`
**Depends on** GW-10, GW-08 · **Conflicts with** GW-23 (`GsmAudioPort.java`)

## Problem

**E1 — unguarded wiring, and the failure mode is `abort()`.**
`gsmAudioPort` is `static` (`:28`); `bridgeActive`, `wiredCallMedia`, `wiredConfSlot`
(`:29`, `:34-35`) are plain instance fields.

`startBridge` (`:90`) is reached from pjsua workers (`onSipCallConnected`,
`onCallMediaState`) and from main (`SipTestCallManager.wireMedia`).
`stopBridge` (`:177`) from whichever thread ran `terminateAllCalls`, from main
(`shutdownSip`), from `ConfigReload` (`doReloadConfig`), and from main
(`SipTestCallManager.unwireMedia`).

The class's own comment (`:196-203`) is explicit that disconnecting a destroyed
conference port trips a pjmedia assertion — `abort()`, **not** a catchable exception.
`unwireBridge` (`:204`) guards with `SipDiagnostics.isLiveConfPort()`, but check and use
sit on different threads with no lock: `startBridge` can re-wire, or a call can be
destroyed, between the liveness check (`:219`) and `stopTransmit` (`:226`).

**E2 — static port, instance flag.** `gsmAudioPort` survives `onDestroy`; `bridgeActive`
does not. A restarted service gets `bridgeActive == false` while the static port is still
wired to a stale call, so `stopBridge()` early-returns at `:178` and the conference links
leak permanently.

**E3 — `release()` is a live foot-gun.** `release()` (`:274`) nulls the static port while
the pjmedia RT thread may be inside `onFrameReceived`. It has no callers; the codebase
works around it by never releasing (`PjsipSipService.java:260-262`), a leak documented as
a fix.

## Required change

1. **Control-thread ownership.** Every `AudioBridgeManager` method asserts the control
   thread. All callers (`PjsipSipService` callbacks, `SipTestCallManager`) already post
   there after GW-10.
2. **Kill the static.** Move `gsmAudioPort` and its wiring state into a single
   process-scoped holder whose lifetime is explicit — e.g. an `AudioBridgeState` object
   held by a static reference, containing the port *and* `bridgeActive` *and*
   `wiredCallMedia` *and* the generation. Then a restarted service adopts the real state
   instead of a fresh `false`.
3. **Generation-tag the wiring.** Every `startBridge` takes a generation number
   (from `CallManager`'s current call, or a monotonic counter). `stopBridge(generation)`
   unwires only if the generation still matches. This is what makes "unwire exactly what
   we wired" (the intent stated at `:31-33`) actually hold.
4. **Close the check→use window.** With single-thread ownership, `isLiveConfPort()` and
   `stopTransmit()` execute back-to-back on the same thread with nothing able to
   intervene. Add a comment saying that is *why* the assertion holds — it is not
   incidental.
4b. **Close AUDIT D1b — the bridge can be wired to the wrong call.**
   `CallManager.onSipCallState` fires `onSipCallConnected(call)` on CONFIRMED without
   checking `call` is the current one, and `startBridge` bridges whatever it is handed.
   The generation tag in §3 is the fix: `startBridge` must reject a call whose generation
   is not current, rather than trusting the caller. A bare `call == currentSipCall`
   identity check is not sufficient once calls can legitimately be replaced.

5. **Preserve the re-wire logic.** The `bridgeActive && !isTransmitting(...)` rewire path
   (`:115-125`) exists because PJSIP destroys and re-creates the media stream on the
   codec-locking UPDATE it sends after the 200 OK, silently dropping conference links
   while keeping the slot number. That was hard-won (see the `gsm-sip-bridge-next-steps`
   history). Keep it exactly, including the deliberate absence of `stopTransmit` in that
   branch.
6. **Fix or delete `release()`.** Preferred: delete it (GW-31) and give the port a
   `shutdown()` that (a) stops capture, (b) waits for the RT thread to observe
   `isCapturing == false`, (c) unwires, (d) only then deletes the PJSIP port. If it is
   kept, it must never null a port the RT thread can still reach.
7. **Fold `GsmAudioOpen` into the control thread.** `startCapture` currently spawns its
   own worker (`GsmAudioPort.java:242`) precisely because the caller was the main thread
   and could not block. The control thread can block, so the retry loop moves onto it —
   which removes the whole cancellation problem GW-08 works around. Keep GW-08's
   generation check as defence in depth. Decide `MixerEnforce`'s fate explicitly: either
   a `postDelayed` loop on the control thread (preferred — one fewer thread) or keep it
   separate with a documented reason.

## Acceptance criteria

- [ ] Every `AudioBridgeManager` method asserts the control thread.
- [ ] Port and wiring state live in one holder; a service restart sees the true state.
- [ ] `stopBridge` unwires only the generation it was asked to.
- [ ] Liveness check and `stopTransmit` are provably adjacent on one thread.
- [ ] The re-INVITE/UPDATE re-wire path is behaviourally unchanged.
- [ ] `release()` is deleted or made RT-safe; the "never release" workaround comment in
      `PjsipSipService.shutdownSip` is removed along with the reason for it.
- [ ] `GsmAudioOpen` is gone as a separate thread; `MixerEnforce`'s status is a documented
      decision.

## Verification

1. On-device, the E1 case: hang up from the SIP side at random offsets across 50 cycles.
   Zero tombstones, zero `pjmedia` assertion aborts. Check for tombstones with
   `adb shell ls /data/tombstones`.
2. On-device, the E2 case: stop and restart the service with a call bridged
   (`STOP` then `START` broadcast). Then dump the conference bridge via a diagnostic test
   call and confirm no orphan links:
   ```
   adb shell am broadcast -p org.onetwoone.gateway -a org.onetwoone.gateway.TEST_CALL --es mode tone
   ```
   The `SipDiag` dump must show only the expected ports.
3. Confirm the re-wire path still fires: `logcat -s AudioBridge` on a normal call must
   still show `Conference links lost (media stream re-created), rewiring` and audio must
   flow in both directions. **This is the regression that matters most** — losing it
   brings back one-way audio.

## Risk

**High.** This code path is the one that took the longest to get right historically. Do
not refactor its logic and its threading in the same commit: first move it onto the
control thread with logic byte-identical, verify two-way audio, *then* introduce
generations.
