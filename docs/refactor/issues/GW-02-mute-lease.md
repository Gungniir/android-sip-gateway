# GW-02 — Mute applied after hangup leaves the phone permanently muted

**Phase** 0 · **Severity** P0 (device unusable) · **Closes** AUDIT B1, G3
**Files** `PjsipSipService.java`, `DeviceMuteManager.java`
**Depends on** nothing · **Conflicts with** GW-06, GW-07, GW-10 (`PjsipSipService.java`)

## Problem

`PjsipSipService.onGsmCallStateChanged` (`:473`) handles mute/unmute asymmetrically:

```java
} else if (state == android.telecom.Call.STATE_ACTIVE) {
    ...
    new Thread(() -> DeviceMuteManager.getInstance(this).muteAll(), "MuteControls").start();  // :489
} else if (state == android.telecom.Call.STATE_DISCONNECTED) {
    ...
    DeviceMuteManager.getInstance(this).unmuteAll();   // :499  — on the MAIN thread
}
```

`muteAll()` (`DeviceMuteManager.java:250`) shells out `su -c 'tinymix …'` once per
control to read the original value, then once more to write it — roughly 6 s for the
`redmi_note_7` preset (10 controls). `muteAll` and `unmuteAll` are both `synchronized`
on the manager.

## Failure scenarios

**Permanent mute (the bad one).** Call ends within the scheduling latency of the
`MuteControls` thread:
1. `STATE_ACTIVE` → thread created, not yet scheduled.
2. `STATE_DISCONNECTED` on main → `unmuteAll()` → `isMuted == false` → returns immediately.
3. `MuteControls` runs → `muteAll()` mutes speaker + mic and sets `isMuted = true`.
4. Nothing will ever unmute it. **The phone has no working microphone or earpiece until
   another full call cycle happens to complete in the right order, or the device reboots.**

**Main-thread ANR.** Call ends mid-`muteAll()`: `unmuteAll()` blocks on the monitor on
the main thread for up to 6 s.

## Required change

Model the mute as a **lease held by a call**, not as a fire-and-forget action.

1. Give `DeviceMuteManager` a monotonically increasing `long` lease id.
   `acquire(long leaseId)` and `release(long leaseId)` replace `muteAll` / `unmuteAll`.
   `acquire` records the id; `release` is a no-op if the id doesn't match the held lease.
2. `acquire` must re-check, immediately before it starts writing controls, that the lease
   is still the current one. If `release` for that id already arrived, it must **not
   mute** — and if it already muted some controls, it must restore them before returning.
   The cheapest correct form: check a `cancelled` flag between each control write and
   unwind on cancel.
3. Never call `release`/`unmuteAll` on the main thread. Both directions run on the same
   single-threaded executor (a `HandlerThread` local to `DeviceMuteManager` for Phase 0;
   GW-10 will migrate it to the shared control thread).
4. Add a **fail-safe deadline**: if a lease has been held longer than
   `MUTE_MAX_HOLD_MS` (suggest 4 hours — well beyond any real call, short enough that an
   unattended device recovers), force-restore and log an error. This is the backstop for
   any interleaving not anticipated here.
5. Restore on service destroy: `PjsipSipService.onDestroy` must release any held lease.

Do **not** change which controls are muted, their order, or the preset definitions —
those are device-specific and validated.

## Acceptance criteria

- [ ] `muteAll` / `unmuteAll` are replaced by `acquire(leaseId)` / `release(leaseId)`;
      an out-of-order or late `acquire` for a released lease mutes nothing.
- [ ] A cancelled `acquire` that had already written some controls restores exactly those
      controls before returning.
- [ ] No mute/unmute call runs on the main thread — assert this in a debug build.
- [ ] A held lease older than `MUTE_MAX_HOLD_MS` is force-released with an error log.
- [ ] `onDestroy` releases any held lease.
- [ ] `isMuted()` and the lease id are `volatile` or guarded (see GW-07).

## Verification

1. Unit test (JVM, no Telecom): inject a fake mixer backend into `DeviceMuteManager`,
   drive `acquire(1)` and `release(1)` from two threads with a latch forcing the
   release-before-acquire ordering, assert the final state of every control equals its
   original value.
2. On-device: 20 cycles of "answer then hang up within 1 s". After each,
   `adb shell su -c 'tinymix -D 0 get "DEC1 Volume"'` (and the MUX/EAR_S/SPK controls)
   must equal the pre-call value. Then place a normal voice call from the phone's own
   dialler and confirm the earpiece and mic work.
3. Confirm no ANR: `logcat` shows no `Skipped … frames` bursts around hangup.

## Risk

Low-medium. The unwind path is the delicate part — if it restores the wrong values the
symptom is identical to the bug being fixed. Snapshot originals *before* the first write
and unwind from that snapshot only.

## Note

The `handlesMicMute()` short-circuit at `PjsipSipService.java:488` (MediaTek profile
mutes as part of its routing) must be preserved — this issue only changes the branch that
actually uses `DeviceMuteManager`.
