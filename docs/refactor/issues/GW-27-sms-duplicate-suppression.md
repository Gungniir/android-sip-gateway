# GW-27 — The whole inbox is re-forwarded on every restart

**Phase** 2 · **Severity** P1 (user-visible: duplicate SMS delivery) · **Closes** AUDIT H13
**Files** `SmsHandler.java`, `RootHelper.java`, `PjsipSipService.java`
**Depends on** nothing · **Conflicts with** GW-20 (`RootHelper.java`), GW-21 (`SmsHandler.java`, `PjsipSipService.java`)

## Problem

Reported from the field and reproduced from the device log. See AUDIT **H13** for the full
evidence; the short version is three defects that stack:

1. The app is not the default SMS app → `ContentResolver.update(read=1)` returns 0.
2. The root fallback shells out to `sqlite3`, **which does not exist on either test
   device** (exit 127, `inaccessible or not found`).
3. `RootHelper.execRoot` returns the (empty) output instead of `null` on a non-zero exit,
   so `markAsReadWithRoot`'s `if (result != null)` treats the failure as success and logs
   `Marked SMS id=N as read (root sqlite3)`.

The `read` flag is therefore never written. `processInbox`'s `selection = "read = 0"` keeps
matching every message ever received, and the only thing suppressing re-delivery is the
in-memory `processedSmsIds` set — which starts empty on every process start.

Result: **every restart re-forwards the entire inbox to the PBX.** Confirmed on merlinx:
9 unread before, 9 "marked read", 9 unread after, still 9 unread ten minutes later.

`processInbox` is also driven by SIP re-registration, so a flapping registration replays
the burst with no restart involved.

## Required change

1. **Fix the `execRoot` success contract** — this is the root cause of defect 3 and it is
   not SMS-specific. Every caller in the tree that tests `execRoot(...) != null` for success
   is equally blind to a failed command. Either return `null` on a non-zero exit, or (better,
   and what GW-20 should converge on) return a small result object carrying exit code,
   stdout and stderr, and migrate callers. **Coordinate with GW-20** — it owns `RootHelper`
   and its §1 is already about `execRoot`'s output handling. If GW-20 lands first, this
   issue consumes its new API rather than inventing a second one.
2. **Use a mechanism that exists.** Replace the `sqlite3` invocation with the `content`
   tool, verified working on-device:
   ```
   su -c 'content update --uri content://sms/<id> --bind read:i:1'   # exit 0, row flips
   ```
   Check the exit code. Keep the non-root `ContentResolver.update` as the first attempt —
   it is the correct path if the app ever becomes the default SMS app.
3. **Persist the duplicate-suppression set.** This is the part that must not be skipped.
   The read flag is provider state the app cannot guarantee it can write on an arbitrary
   device or Android version; fixing only the flag leaves the same bug one OEM away.
   - Persist `processedSmsIds` (SharedPreferences via `GatewayConfig`, or a small file).
   - Prune it — bound it by age (e.g. drop ids whose SMS `date` is older than 30 days) or by
     size, so it cannot grow without limit on a 24/7 device.
   - Persist **after** a successful forward, not before, so a crash mid-send still retries.
4. **Make the false-success impossible to reintroduce.** `markAsReadWithRoot` must verify,
   not assume: after the write, re-read the row (or check the affected-row count) and log an
   error naming the SMS id if it is still `read = 0`. A silent no-op here is what hid this
   bug for the life of the feature.
5. **Do not let a permanently unmarkable SMS spin.** If the flag cannot be written after N
   attempts, the persisted set is the backstop — log once at error level and stop retrying
   the flag. Ties into GW-21 §4's attempt cap; keep the two consistent.

## Acceptance criteria

- [ ] `execRoot` (or its replacement) cannot report success for a command that exited non-zero.
- [ ] `markAsRead` verifies the row actually reached `read = 1` and logs an error naming the
      id when it did not.
- [ ] Duplicate suppression survives a process restart with the `read` flag write disabled
      (fault-inject it) — no SMS is forwarded twice.
- [ ] The persisted set is bounded and pruned.
- [ ] No `sqlite3` invocation remains in the tree.

## Verification

1. **The reported bug.** With unread SMS in the inbox, forward them, then
   `am force-stop` and restart the app. Expect **zero** re-forwards at the PBX. Today this
   re-sends all of them.
2. Confirm the flag is genuinely written:
   ```
   adb shell su -c 'content query --uri content://sms/inbox --projection _id:read'
   ```
   Every forwarded id must show `read=1`.
3. **Fault-inject the flag write** (make `markAsRead` a no-op) and repeat test 1. Still zero
   duplicates — this proves the persisted set, not the flag, is carrying correctness.
4. Flapping registration: force 5 re-registrations with unread SMS present. Each must
   forward nothing new.
5. Prune: seed the persisted set with 10k synthetic ids and confirm it is bounded after the
   next scan and that startup is not measurably slower.

## Risk

Low-medium. The failure direction that matters is *under*-suppression (duplicates, the
current bug) versus *over*-suppression (a genuine SMS silently dropped). Persisting after a
successful forward keeps the failure direction on the safe side. Test 3 is the one that
proves it; do not skip it.

## Note

`content update` was verified on merlinx during triage, which set SMS `_id=1` to `read=1`.
The remaining 8 unread test messages on that device were deliberately left alone so the
reproduction stays available.
