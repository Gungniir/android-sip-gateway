# Phase 2 execution plan — correctness & resource hygiene

**This document overrides the issue briefs in `issues/` wherever they disagree.** The eight
Phase 2 briefs were written before Phase 0 and Phase 1 landed. Four recon passes over the
current tree found that roughly a third of their required-change items are **already done**,
several are **factually false**, and three carry **hazards that would cause on-device damage
if implemented as written**. Read this file, then the brief, in that order.

Baseline at the time of writing: branch `refactor/phase-1`, HEAD after `5b08d89`,
**204 tests / variant, 0 failures, 0 errors**, `lintDebug` clean, both test devices deployed
and registered (merlinx = debug, assertions armed; lavender = release).

---

## 1. Why Phase 2 is not shaped like Phase 1

Phase 1 was a star: land GW-10, then five leaves that each touched a different subsystem.
Phase 2 is a **dense mesh around one file**. `PjsipSipService.java` is contested by four
issues (GW-21, GW-22, GW-25, GW-26) and it is the file Phase 1 rewrote most heavily. The
ROADMAP's "GW-20, GW-21, GW-22, GW-23, GW-24 — independent, parallel" is wrong; it was
written before that contention existed.

There are also five **hard ordering constraints** that the ROADMAP does not have, three of
them discovered during this recon:

```
GW-20 ──→ GW-24     B1e: GW-24's key fix is what ARMS GW-20's broken read path
GW-20 ──→ GW-27     H13: GW-27 consumes GW-20's fixed execRoot contract
GW-22 ──→ GW-25     GW-25 calls SipDiagnostics.dump on a tick; GW-22 stops it leaking
GW-26 ──→ GW-21     GW-26 rewrites onDestroy end-to-end; GW-21 must sequence against it
GW-01 ──→ GW-23b    the drain has NEVER executed on hardware; GW-23b makes it run per call
```

So the phase is three waves plus a gated tail, not one fan-out.

---

## 2. Corrections to the briefs, issue by issue

### 2.1 GW-20 — root helper

**Already fixed, do not redo:**
- `hasRoot` / `suProcess` / `suOutputStream` are already `private static volatile`
  (Phase 0, `9d03f7c`), with a comment stating that what remains is *mutual exclusion, not
  visibility*. The brief's "plain statics" wording is stale; its substance is not.
- `DeviceMuteManager` no longer contains a single `Runtime.exec` — it is JNI-only since
  `bf22992`. The brief's §3/§4 items about `DeviceMuteManager:427/:456/:432/:461` describe
  code that **no longer exists**. Do not "fix" it.
- **A native ENUM getter already exists.** `GsmAudioNative.getMixerControlEnum`
  (`gsm_audio_jni.c`, via `mixer_ctl_get_enum_string`) is in production use. The brief's
  "ENUM reads have no native getter today — either add one" is **false**; the work is
  *migration*, not authoring.

**The brief's headline number is wrong.** "~20 process spawns per mute" is false today.
With default config the real count for one call setup is **2**, both from
`setupAlsaPermissions`. The verification step `ps -A | grep -c tinymix` will show no
meaningful reduction and must not be used as the acceptance signal.

**The real prize is B1e, which the brief does not mention at all.**
`QualcommAudioProfile.TinymixControls` still uses the pattern B1c proved broken, and it is
the component that saves the originals `teardownMixer` restores. Verified on lavender
2026-08-24:
- `getValue` shells out to `tinymix`, **which is not on the device at all** (exit 127).
  stdout is empty, `readLine()` returns null, and it returns its `fallback` — so every DEC
  control's "original" is recorded as the hardcoded **84** with nothing ever read.
- `getEnum` execs `filesDir/tinymix`, **which does not exist**: the `tinymix-arm64` asset is
  unpacked only by `ui/TinymixManager`, a UI-path component. `IOException`, swallowed,
  returns `""`.

84 is not a harmless sentinel — the true resting value of `DEC* Volume` on lavender is 0
(memory: `qualcomm-dec-volume-zero-at-rest`). B1d's kernel refusal is what has been masking
this. **Migrating `TinymixControls` to the native getters is GW-20's most important item.**

**Scope change — GW-20 does NOT touch `GsmAudioPort.java`.** The brief's §5 asks for the
`setenforce`/`chmod` split at `GsmAudioPort`'s two `setupAlsaPermissions` call sites. Do it
**inside `RootHelper.setupAlsaPermissions` instead** — make `setenforce` idempotent with a
process-scoped flag in `RootHelper`, and leave `chmod` per-call. This is the better design
anyway (RootHelper owns "once per process") and it keeps GW-20 out of the file GW-23a owns.

**Dead code — confirm before deleting.** `execInShell` / `startRootShell` / `stopRootShell`
have zero callers, as the brief says. Recon found **five more** with zero callers:
`checkRoot`, `execRootCode`, `copyFileAsRoot`, `extractAsset`, `grantAllPermissions`
(`ui/PermissionManager` has its own duplicate). The live surface of `RootHelper` is exactly
`execRoot(String)`, `execRoot(String,int)` and `setupAlsaPermissions()`. Deletion is
**GW-31's** job, not GW-20's — per ROADMAP rule 8, list them in AUDIT and move on.
Exception: `execRootCode` has no stream drain and can deadlock on a full pipe; if you keep
it, fix it; if GW-31 will delete it, say so in a comment.

**The `execRoot` return contract is the systemic bug.** It logs a non-zero exit and then
returns `output.toString().trim()` anyway — an empty string, never null. Every caller in the
tree testing `execRoot(...) != null` for success is blind to a failed command. This is the
shared root cause of **H13** (SMS re-forwarding) and half of **B1e**. Fix it here, once:
return a small result object carrying exit code + stdout + stderr, and migrate callers.
GW-27 depends on this API.

**Do NOT build a single-thread root executor without reading this.** The brief's §2 asks for
one. Seven distinct threads reach `RootHelper` today, and serialising them naively creates
two new problems:
- `PowerController.disableBatteryOptimizations` is 6 × `execRoot` at 5 s each (~30 s) on the
  `BatteryOptDisable` thread, deliberately kept off the control thread. Putting the per-call
  `setupAlsaPermissions` behind that burst stalls call setup at exactly service-start time.
- `SmsHandler.markAsReadWithRoot` still runs on **main** — serialising root means main can
  now block behind another thread's `su`, a main-thread stall the current design does *not*
  have.

Serialise only if you also give the ALSA-permission path a bounded wait or a separate lane.
A correct `execRoot` with a real timeout and a safe output handoff is worth more than the
executor; if you can only do one, do that.

**Still true and worth doing:** the `StringBuilder` handoff race (a join timeout still
yields a builder another thread may be appending to), and the unbounded `waitFor()` in
`QualcommAudioProfile` (×2), `TinymixManager`, `PermissionManager` (×3), `BootReceiver`.

### 2.2 GW-21 — SMS off main

**Half the brief is already done.** After GW-10/GW-14 the SIP send *and* the root
`markAsRead` already run on the control thread. What remains on main is exactly two things:
the `ContentObserver` (constructed with `mainHandler`) and the `ContentResolver.query`
inside `processInbox`. The brief's problem items 3 and 4, and its acceptance criterion
"Outbound `sendSms` unaffected", are **void** — outbound moved to the control thread in
GW-10.

**Already fixed:** §6's account re-check. `sendSipMessage` already calls
`accountManager.isCurrentAccount(account)` immediately before `buddy.create()`, and GW-14
removed the reload as a competing writer.

**The retry claim is overstated.** Failure paths do not mutate the provider, so they do not
self-retrigger `onChange`. Re-delivery is event-driven (another SMS's `markAsRead`, or a
successful REGISTER), not a tight spin. Bound it anyway, but do not describe it as a hot
loop in the commit message.

**Two things the brief misses.** `deleteSms` is dead code (no callers anywhere). And on the
success path an id is **never removed** from `processedSmsIds`, so the set grows
monotonically for the service's lifetime — which GW-27 must bound.

**A trap the brief cannot know about.** On the re-registration retry path, `processInbox`
already runs on the control thread, and `onIncomingSms` uses `control.runOrPost(...)`, which
dispatches **inline** when already on that thread. So the blocking SIP send currently
executes **inside `processInbox`'s open `Cursor` loop**. If you add a debounce or a
per-message queue, do not silently convert that to "posted" — `processedSmsIds`'s
add-then-send invariant depends on the current ordering. Decide deliberately and document it.

**Coordination with GW-26 (which lands first):** `smsHandler.stop()` must run before
`control.quitSafely()`, and `SmsHandler` needs the control looper injected (its constructor
takes only `Context` + callback today). Unregistering the observer from main while an
`onChange` runs on the control thread is a **new** race the current code does not have.

### 2.3 GW-22 — pjsua2 object lifetime

**The brief's ownership model is wrong for half its list, and acting on it would be wasted
work.** Every SWIG proxy carries a `swigCMemOwn` flag; only `true` means the Java side owns
native memory. Verified directly in the vendored bindings:

| Object | Factory | Owned? |
|---|---|---|
| `CallInfo` | `Call.getInfo()` → `new CallInfo(ptr, **true**)` | **yes** |
| `AudioMediaVector2` | `Endpoint.mediaEnumPorts2()` → `(ptr, **true**)` | **yes** |
| `ConfPortInfo`, `StreamInfo`, `StreamStat`, `AccountInfo`, `CodecInfoVector2`, `IntVector` (from `transportEnum`) | — | **yes** |
| `CallMediaInfoVector` | `CallInfo.getMedia()` → `(cPtr, **false**)` | **no** |
| `CallMediaInfo`, `Media`, `AudioMedia` (from `typecastFromMedia`), `ByteVector` (from `MediaFrame.getBuf()`) | — | **no** |

So the brief's "delete `CallMediaInfoVector` / `CallMediaInfo` / the `AudioMedia` from
`typecastFromMedia`" is a **no-op at best**. Delete only the owned set.

**"Unbounded leak" is over-stated — reframe the ticket.** Every proxy has
`finalize() { delete(); }`, so these are **finalizer-deferred releases, not permanent
leaks**. The soak test will show a far flatter native-heap slope than the brief predicts,
and the ticket will look wrong if it is sold on unboundedness. The real cost is
non-determinism: native memory invisible to the GC heuristic, a growing finalizer queue,
and — for `Call` — a native destructor on an unregistered thread.

**The "GC'd eventually" comment cuts the opposite way, and this is the headline.**
`Call(Account)` is constructed with `swigCMemOwn = true` and `director_connect(..., true,
true)` — a **weak** global ref. So the Java object *is* collectible, and when it is,
`finalize()` calls `delete_Call` on the **FinalizerDaemon thread, which is not registered
with pjlib**. That is the same thread class behind all eight historical tombstones. The
current policy does not prevent deletion; it defers it to an unpredictable time on the worst
possible thread. Lead the ticket with that.

**§3's safety argument is false — do not build on it.** The brief says "callbacks are
posted, so the disconnect handler runs on the control thread *after* the pjsua worker has
returned". `Handler.post` orders **queue entry, not stack unwind**. The two run
concurrently, and E5 proves the pjsua worker can sit inside `pjsua_media_channel_deinit` for
tens of seconds around exactly this point. The only *checkable* signal in the bindings is
`Call.getId() == PJSUA_INVALID_ID (-1)`, evaluated on the pjlib-registered control thread.
Even that is not a proof — a delay-based graveyard is a heuristic and **must be labelled one
in the code**.

**Two sites the brief misses, both hotter than anything on its list:**
1. `GatewayCall.onCallState` calls `getInfo()` on **every** SIP state change — ~5–6 owned
   `CallInfo` per call, the highest-frequency site in the app, and the easiest fix.
2. `AudioBridgeManager.startBridge` calls `SipDiagnostics.dumpAndLog(...)` **unconditionally
   on every successful wiring**. That is a **production** path, not diagnostics, and one
   invocation creates ~8 owned objects. It is the single heaviest owned-object site on the
   normal call path.

Budget: **~30 owned SWIG objects per completed gateway call.**

**Fix the template before copying it 15 times.** `SipEndpointManager.hasTransport` — the
"correct pattern already in the codebase" — is a bare `delete()` inside `try/catch`, not
`try/finally`. If `size()` throws, the delete is skipped.

**Split the ticket as its own Risk section says:** value objects first, soak, then `Call`.
If `Call` deletion produces any tombstone, revert that half and keep the counters.

### 2.4 GW-23 — RT audio path — **SPLIT INTO GW-23a AND GW-23b**

**There is a documented scope conflict and this plan resolves it.** `GW-23-rt-audio-path.md`
says "Closes AUDIT H2, H3" and contains no ring buffer. But `AUDIT.md`'s E5 fix — a
dedicated I/O thread owning `pcm_read`/`pcm_write` with the callbacks doing only a
ring-buffer copy — says "**This is GW-23's territory**", and `PHASE-1-PLAN.md` says E5 is
"Fixed by GW-23 (Phase 2)". Those are two tickets with very different risk profiles sharing
one number.

> **GW-23a** — H2/H3 only: bulk JNI copy, preallocated resample scratch, hoisted silence
> frame, safely-published counters, drop the redundant `isOpen()`. **No ordering
> constraint.** Ships in wave 1.
>
> **GW-23b** — the E5 fix: dedicated I/O thread + lock-free ring buffer, removing ALSA from
> the conference callback. **Gated** — see below. Does not ship until the gate passes.

**The Phase 1 note's conclusion is right; its stated mechanism is wrong.** It says E5's
blocking read protects against A1 *by blocking*. It does not. What protects is the
**pjmedia conference mutex**: `conf->mutex` is held across `get_frame`, which is where our
director callback runs, and `pjmedia_conf_remove_port`'s first act is to take that same
mutex. That acquisition is the happens-before edge proving our callback is not in flight
when the port is removed. Both teardown directions inherit it (SIP-side via
`media_channel_deinit` running before Java sees DISCONNECTED; GSM-side via `stopBridge`
unwiring before `stopAudioStreams`).

**Consequence: GW-23a does not remove the protection.** Items §1–§5 all keep
`pcm_read`/`pcm_write` inside the conference callback. The mutex edge survives, `active_io`
stays 0 at `close()`, and the sequencing constraint is **vacuous for GW-23a**. Only GW-23b
breaks the edge.

**The gate for GW-23b.** GW-01's drain has executed **zero times in 33 on-device
teardowns** — no `close: draining N in-flight PCM I/O` line has ever been logged.
"Confirmed sound" today means *reviewed*, not *exercised*. GW-23b is precisely the change
that makes it run on every call. Before GW-23b merges:

1. **Force the drain to execute on hardware.** A debug-only path that starts a background
   `readFrame` loop and calls `close()` underneath it, asserting `active_io > 0` at `close()`
   entry, on both SoCs, with zero tombstones.
2. **Bound `pcm_read`'s `EPIPE` restart, or replace draining with joining.** This is the
   concrete break: `tinyalsa`'s `pcm_read` and `pcm_write` both `continue` **unboundedly**
   on `EPIPE`, and `PCM_NORESTART` is never set (`open_pcm_adaptive` passes only
   `PCM_IN`/`PCM_OUT`). `close()`'s `pcm_stop()` sets `pcm->running = 0`; the blocked
   `READI` returns, and if errno is `EPIPE` the reader **calls `pcm_start()` and re-arms the
   hardware `close()` just stopped**. End-of-call is exactly when the modem voice path is
   being torn down, so persistent `EPIPE` is plausible — the reader then spins holding
   `active_io` until the 250 ms deadline expires and `close()` calls `pcm_close()` **with
   the reader still inside the loop dereferencing `pcm->fd`**. That is A1, live and
   deterministic. Joining a dedicated I/O thread is strictly stronger than draining and
   removes the whole class; prefer it.

**Prohibition for GW-23b:** never hoist `io_acquire` out of per-frame scope. The obvious
I/O-thread optimisation — acquire once, read many — turns the refcount into a session-long
hold; `close()` then blocks the full 250 ms and frees under an active reader. Guaranteed
UAF. Also: `GsmAudioNative.close()` is reachable from **two** threads (GatewayControl and
`GsmAudioOpen-N`); any I/O thread must be stopped from both.

**Corrections to GW-23a's own numbers and scope:**
- **The JNI count is 2× low.** `frameSize = 8000 * (16/8) * 1 * 20/1000 = **320 bytes***
  (the field comment says "bytes per 20ms"), and `ByteVector` holds one `short` per *byte*.
  So the loops run 320 iterations, not 160: **≈32 500 JNI transitions/s, not 16 000.**
- **The brief misses the allocations, which probably matter more than the JNI count.**
  `ByteVector.add(Short)` / `get(int)→Short` **autobox**, and values are `b & 0xFF` ∈
  [0,255] while `Short.valueOf` caches only −128…127 — so roughly half of every 320-element
  loop allocates on the RT thread (~4 000/s per direction). And `MediaFrame.getBuf()`
  returns `new ByteVector(cPtr, false)` — a fresh **finalizable** wrapper twice per frame,
  i.e. **100 finalizable objects/s created by the RT thread**. That is a better explanation
  for GC-pause dropouts than raw transition count.
- **§1 option 1 is harder than it reads.** A native bulk copy needs `getCPtr`, which is
  `protected static` in `org.pjsip.pjsua2` — reaching it requires a **new, hand-written**
  helper class *in that package* (permitted: that is not editing a generated file) plus a
  hard dependency on pjmedia's `vector<pj_uint8_t>` ABI. Call that dependency out explicitly
  if you take it.
- **The `TYPE_NONE` simplification has a trap.** `buf.clear()` runs *before* the branch. If
  you drop the silence fill you must also drop `setSize(frameSize)` (or set 0), or pjmedia
  is handed `size = 320` over a zero-length vector. Confirm against pjmedia's `conf.c`
  behaviour, not by reasoning — the error path is rare and a mistake fails silently.
- **`out_n` really is fixed at 960** (`playbackBuffer` is `new byte[320]`, constant for the
  port's life), so a 960-sample scratch buffer is correct. The resample path is
  **MediaTek-only** — Qualcomm has `capture_rate == playback_rate` and takes the no-malloc
  fast path. H3 can only be validated on merlinx.
- **§4b is correct**: `is_open` is checked inside `io_acquire` under the lock, so the
  per-frame `GsmAudioNative.isOpen()` is redundant. During a call it runs 100×/s, each
  taking the native mutex.
- **Pre-existing bug found in this area, not in either brief:** `onFrameReceived` guards on
  `size <= frameSize` but passes the **whole** `playbackBuffer` to `writeFrame`, which uses
  `GetArrayLength` = 320 regardless. When `size < frameSize` the tail is stale audio from
  the previous frame. Pass an explicit length.
- **"Which thread runs `stopCapture`" is stale** — it is the GatewayControl thread (plus the
  `GsmAudioOpen-N` worker on the superseded path), not "main / pjsua worker / ConfigReload".

### 2.5 GW-24 — config consistency

**The brief is unusually accurate — `WebConfigServer` and `GatewayControlReceiver` were
untouched by Phase 0/1, so every line number it cites still matches.** The key mismatch is
real and verbatim: `mic_mute_controls` (StringSet, web) vs `mic_mute_decs` (comma String,
`GatewayConfig`), and nothing reads the web server's key.

**Sequencing is mandatory: GW-20 first.** See §2.1 / B1e. The moment `getAllMuteControls()`
returns a non-empty set, `QualcommAudioProfile.setupMixer` starts reading originals through
the dead `TinymixControls` path and `teardownMixer` writes a fabricated **84** into every
`DEC* Volume`. **GW-24's fix is what arms the bug.** Do not land it first.

**Corrections:**
- The consequence is narrower than stated: the web UI's `manual_mute_controls` field **does**
  work (same key both sides). Only the *checkbox selection* is discarded.
- **An extra unreported bug in the same area:** `DeviceMuteManager.currentPreset` is loaded
  once in the constructor and `savePreset` has **no callers**. A preset change from the web
  UI or `MainViewModel.selectMutePreset` writes prefs but never updates the live singleton —
  so even switching *to* `custom` does nothing until process restart. Fix it here.
- The `getSharedPreferences` list is incomplete: add `BootReceiver`, `BatteryLimitService`
  (×2), and two more in `GatewayControlReceiver`. Also `DeviceMuteManager` writes a
  `sound_card` key nothing ever reads (`setSoundCard` is dead → GW-31).
- **`GsmAudioPort` is no longer a bare `static` field.** It lives in the process-scoped
  `AudioBridgeManager.Wiring` holder, documented as *"Never replaced once published"*. Same
  lifetime, different shape.
- **H4b is narrower than stated.** Gains are **exempt** — `getTxGain`/`getRxGain` are re-read
  per `startBridge`, so they already take effect on the next call. And `MediaTekAudioProfile`
  takes no `GatewayConfig` at all (compile-time constants), so it has no config to stale.
- **`doReloadConfig` does not rebuild the port**, and calling `audioBridge.initialize()`
  would early-return anyway. The "Restart to apply" toast is honest — keep it unless you
  genuinely rebuild.

**Hazard if you do rebuild the port:** `Wiring.port` is never replaced. A rebuild must handle
a live call holding the old port *and* the `MixerEnforce` thread it started, or you recreate
the orphaned-enforce-thread failure GW-08 exists to prevent.

**Migration hook placement.** `GatewayConfig.init` is `synchronized` and would run before
every *`GatewayConfig`* reader — but it is **not** called from `GatewayApplication.onCreate`,
only from three components. Raw-prefs readers (`BootReceiver`, `GatewayControlReceiver`,
`WebConfigServer`, `DeviceMuteManager`, `BatteryLimitService`) can run first and bypass it.
So the migration is only safe **after** §2 of the brief (route everything through
`GatewayConfig`), or move `init` to `GatewayApplication.onCreate`. Decide explicitly.

### 2.6 GW-25 — watchdog invariants

**This is the brief most invalidated by Phase 1.** Three of its items are already done and
one of its central assumptions is false.

**Already fixed:** §5 (`ServiceWatchdog` on the control looper, `running` unsynchronised with
the exact javadoc the brief asks for) landed in GW-15. Gap 3 (`lastPhoneState`) closed in
GW-13 — `checkOrphanedCalls` now calls `isGsmLegLive()`, which asks Telecom. Nothing is
stale or wrong there.

**§3's four hard deadlines are two.** Already built, with error logs:
- mute-lease hold: `DeviceMuteManager.MUTE_MAX_HOLD_MS` = **4 h**, fail-safe armed by
  `acquire()`, disarmed by `release()` (GW-02).
- charging-disabled: `BatteryLimitService.MAX_DISABLE_MS` = **12 h**, checked on the 5 s
  enforce tick (GW-05).

Only **max call duration** and **TERMINATING dwell** are missing — and TERMINATING dwell is
near-unreachable: `terminateAllCalls()` walks in and out of it synchronously with no
suspension point, `transition()` is private, and there is no API to force `TERMINATING →
IDLE`. Do not build machinery for a state that cannot stick; log it and move on.

> ### ⚠️ The landmine: the grace period does not exist for inbound calls
>
> `gsmCallPlacedTime` is assigned in **exactly one place** — `placeGsmCall()`, the SIP→GSM
> dial. `onIncomingGsmCall` never sets it. So **`isInGracePeriod()` is permanently `false`
> for the entire GSM→SIP direction.** The brief says "keep `GSM_CALL_GRACE_PERIOD_MS` as the
> guard against terminating a call that is legitimately mid-setup" — on inbound calls that
> guard **is not there**.
>
> Compounding it: on the inbound flow `GatewayInCallService.makeSipCallWithRetry` retries for
> up to `MAX_SIP_RETRIES(40) × 500 ms ≈ 20 s` **before** `onIncomingGsmCall` is ever called.
> During that whole window the GSM leg is live and RINGING, `CallManager` is `IDLE`, there is
> no SIP call, and the grace period reads false.
>
> **A reverse-orphan rule written as the brief specifies would hang up every inbound call.**
> Gate on Telecom `STATE_ACTIVE`, not `hasLiveGsmCall()`, and leave the pre-answer window to
> the existing `INCOMING_TIMEOUT_MS` (30 s), which is already tagged to the call it was armed
> for.

**`hasLiveGsmCall()` is not `STATE_ACTIVE`** — it is true for RINGING, DIALING, CONNECTING
and HOLDING too. Two ways to get the granularity: read
`PjsipSipService.currentGsmCallId != NO_GSM_CALL` (control-thread-confined, set only in
`handleGsmCallConnected`, i.e. Telecom `STATE_ACTIVE` — free to read), or add
`hasActiveGsmCall()` to `GatewayInCallService`. Prefer the first; it needs no new file.

**Silent-bridge detection: the evidence is only half there.** `isAudioStreaming()` is
readable and safe off-thread. `framesRequested` is **not** — private, non-volatile, no
accessor, written on the RT thread. PHASE-1-PLAN §3b forbids the obvious fix (a lock there
would park the RT thread behind `close()`'s drain). **Keep GW-25 out of `GsmAudioPort.java`
entirely**: ship the detector using `isAudioStreaming()` plus a watchdog-local BRIDGED dwell
clock (control-thread-confined; no new cross-thread field, nothing frozen into a snapshot),
and let GW-23a expose a counter accessor if one is wanted later.

**Do not call `dumpAndLog` on every tick.** It creates ~8 owned SWIG objects and emits
~20 logcat lines. At 3 s that is ~1200 invocations/hour. **Latch it to once per detection
episode**, and land GW-25 after GW-22.

**Use the right remedy.** `terminateAllCalls()` returns early from `IDLE` and will **not**
stop the audio streams when the machine never left `IDLE` (plan §3d). For a stuck leg use
`handleGsmCallEnded(...)`, which stops streams unconditionally and releases the mute lease.

**D6 (act on the cross-check):** the trigger predicate must be **Telecom-based**
(`!isGsmLegLive() && currentGsmCallId != NO_GSM_CALL`), never the modem reading — using the
modem as a trigger re-creates the second source of truth GW-13 deleted.

**Two more hazards:**
- The diagnostic test call sets `Wiring.active` with `CallManager` at `IDLE` and no GSM leg
  at all. Any "bridge active but a leg is missing → terminate" rule kills it.
  `isAudioStreaming()` is the safe discriminator (the test call never starts the streams).
- `GatewayInCallService.getInstance() == null` reads as "no GSM leg" **by design**. If the
  InCallService is transiently unbound, that looks like an orphan.

**Keep `publishStatus()` at the top of `checkOrphanedCalls`, before every early return** —
it is the only thing keeping the 1 Hz UI fresh between call events.

**The brief's file list is incomplete:** add `core/GatewayStatus.java` (findings go in as a
field + a `capture()` parameter, following the `configGeneration` precedent) and
`GatewayInCallService.java` (AUDIT **H9b** is also assigned to GW-25).

### 2.7 GW-26 — service lifecycle

**Already fixed, do not redo:** §3 — `instance = null` already precedes all teardown. §6 —
`stopWebServer()` already calls `stop()` before nulling; the ordering fix is a **no-op**.
The G2 bullet about `stopBridge`/`stopAudioStreams`/`teardownMixer` on main is stale; GW-12
moved those to the control thread.

**Still on main in `onDestroy`:** `smsHandler.stop()`, `stopWebServer()`, the 1.5 s quit
join, `shutdownSip()` (un-REGISTER, unbounded network), `powerController.release()`, and an
up-to-2 s `awaitRestore`.

> ### ⚠️ The brief's fix is not sufficient: shutdown needs cancellation, not a bounded join
>
> Verified directly. `initializeSip` has **no cancellation check anywhere**:
> ```java
> endpointManager.createEndpoint();   // parks in a 30 s latch waiting on main
> ...                                  // no cancellation check
> audioBridge.initialize();
> accountManager.createAccount(this);  // `this` = the destroyed service
> ```
> When `onDestroy`'s bounded join expires and the control thread is abandoned mid-init, the
> latch resolves the instant `onDestroy` returns (its runnable was queued behind `onDestroy`
> on main), and the abandoned thread walks straight on to **`createAccount(this)` —
> registering a fresh SIP account for a service that no longer exists**, against the `static`
> Endpoint that main only ran `hangupAllCalls()` on. Its callbacks post to a quit looper and
> are dropped, so nothing ever tears it down.
>
> Real non-blocking shutdown needs a **cancellation generation** checked inside
> `initializeSip` and honoured by `createEndpointOnMainThread`.

**This corrects AUDIT H8c**, which claims the thread burns the full 30 s and leaks a
*reconnect timer*. It does not: the timer post targets a dead looper and fails harmlessly
(Android logs "sending message to a Handler on a dead thread"). The leaked **account** is the
actual damage. H8c should be amended.

**`quitSafely` still drains already-due messages.** Anything `onDestroy` posts *will* run on
the abandoned thread, after main has finished teardown and released the wake lock. "Post it
and quit" is **not** cancellation. That is also H11's precise collision: main's
`deleteAccount()` destroying the call's conference port while the control thread is inside
`unwireBridge()`'s liveness check → a pjmedia `abort()`.

**§2's premise is weak but the conclusion is right.** "onCreate threw after `instance = this`"
is barely reachable — an exception out of `Service.onCreate` crashes the process before
`onDestroy` runs. The **reachable** partial state is "onCreate ran, `onStartCommand` never
did" (bind-only). But there is a sharper path the brief misses: the static block **catches
`UnsatisfiedLinkError`** and lets the service run without `libpjsua2`. Every pjsua2 call then
throws an `Error`, and `SipAccountManager.deleteAccount()` catches only `Exception` — so the
`Error` escapes `shutdownSip()` and **skips `powerController.release()`, the telephony
unlisten, the mute restore and `stopForeground`** → leaked `Gateway::CpuWakeLock`, which is
exactly the acceptance criterion. **Catch `Throwable`, not `Exception`, in teardown.**

**Do not touch `WebConfigServer.java`** — the only residual is that `webServer` is a plain
non-volatile field in `PjsipSipService`. Fix it there. GW-24 owns `WebConfigServer` in wave 2.

**§7 is a documentation bug, not a code bug.** `isRunning` is already `volatile`, and
`attemptReconnect` reads it from the control thread. But the javadoc claims the
check-then-set in `onStartCommand` "is still asserted (`assertMainThread`)" — it is not; only
the getter asserts. Add the assertion or fix both comments.

**§4/§5 change restart semantics — the brief's own Risk section is right.** A gateway that
does *not* come back after a crash is worse than the current bug. Suppress the restart only
for an **explicit** user stop; every other path keeps restarting. Note `onDestroy` nulls
`instance` first, so an InCallService created during teardown sees `null` and restarts the
service — the persisted flag must be checked there too.

### 2.8 GW-27 — SMS duplicate suppression (new this phase)

Filed during this planning pass from a field report; see AUDIT **H13**. Full brief in
`issues/GW-27-sms-duplicate-suppression.md`. Three defects stack so that **the entire inbox
is re-forwarded on every process start**: the app is not the default SMS app so
`ContentResolver.update` is refused; the root fallback shells out to `sqlite3`, **absent on
both devices** (exit 127); and `execRoot` reports that failure as success.

Depends on **GW-20** for the `execRoot` contract. `/system/bin/content` is the verified
working mechanism. **The read flag must not be the only defence** — persist and prune the
suppression set, and fault-inject the flag write in testing.

**A protected reproduction fixture exists on merlinx: 8 unread SMS, `_id` 3/4/5/6/8/10/11/12.
Do not clear them.** They re-fire on every restart, which is a known cost of Phase 2 device
testing until GW-27 lands — that is why GW-27 is early.

---

## 3. Standing rules for every Phase 2 agent

Carried from ROADMAP §4 and PHASE-1-PLAN §3, plus what this phase adds:

1. **Never mute the mic via `AudioManager`.** ALSA mixer only.
2. **Do not raise `targetSdkVersion`** (27, deliberate). **Never hand-edit
   `app/src/main/java/org/pjsip/pjsua2/**`** — SWIG-generated and vendored. A *new* file in
   that package is permitted but welds you to pjsua2's C++ ABI; say so if you do it.
3. **The pjmedia RT callbacks must never block, allocate unboundedly, or take a lock the
   control thread holds across I/O.**
4. **pjmedia assertion failures are `abort()`, not exceptions.** A `try/catch` around a
   conference-port operation proves nothing. Liveness check + single-thread confinement is
   the only guard.
5. **`GatewayCall.disposed` and `SipTestCallManager.mediaValid` must keep flipping inline on
   the pjsua callback thread.** Post the *handling*, never the flag. Both files carry
   javadoc saying so.
6. **The one-directional blocking rule.** The control thread may block on main; **main must
   never block waiting on the control thread**, with `quitSafely(long)` the single bounded
   exception.
7. **Do not read `/proc/asound/*/status` during a call on merlinx** — kernel panic.
   `tinymix` and `hw_params` are safe.
8. **Keep each diff scoped.** New defects go to AUDIT + a new issue file, never folded in.
   Deletion of dead code is **GW-31's** job.
9. Verify with `./gradlew test` (204 baseline) and `./gradlew lintDebug` (only new issues
   fail). Every fix names the finding it closes and how it was verified, in the commit body.
10. **`AudioBridgeManager.startBridge`'s re-wire branch is the one-way-audio regression
    guard.** `"Conference links lost (media stream re-created), rewiring"` must still fire.
11. **Do not clear the merlinx SMS fixture** (§2.8).

---

## 4. Wave graph

One rule resolves the `PjsipSipService` mesh: **exactly one issue per wave may make
structural changes to `PjsipSipService.java`.** Others make point edits inside named methods
and rebase before merge.

```
WAVE 1  ── independent, no shared files
  GW-20   RootHelper, QualcommAudioProfile(TinymixControls), TinymixManager,
          PermissionManager, BootReceiver          [NOT GsmAudioPort - see §2.1]
  GW-23a  GsmAudioPort, gsm_audio_jni.c, GsmAudioNative
  GW-26   PjsipSipService (STRUCTURAL OWNER), GatewayInCallService   [NOT WebConfigServer]

WAVE 2  ── after wave 1 merges
  GW-24   WebConfigServer, GatewayConfig, GatewayControlReceiver, DeviceMuteManager,
          MainViewModel, QualcommAudioProfile(constructor snapshot)   [needs GW-20]
  GW-27   SmsHandler, RootHelper(consumes GW-20 API)                  [needs GW-20]
  GW-22   PjsipSipService (STRUCTURAL OWNER), GatewayCall, CallManager,
          AudioBridgeManager, SipDiagnostics, SipTestCallManager,
          SipAccountManager, GatewayAccount, GatewayStatus, SipEndpointManager

WAVE 3  ── after wave 2 merges
  GW-21   SmsHandler, PjsipSipService (STRUCTURAL OWNER)   [after GW-26, GW-27]
  GW-25   PjsipSipService(point edits), GatewayStatus, GatewayInCallService,
          CallManager, ServiceWatchdog                     [after GW-22]

GATE    ── GW-23b does not start until both pass, on both SoCs
  G1  force GW-01's drain to execute; assert active_io > 0 at close(); zero tombstones
  G2  bound pcm_read/pcm_write's EPIPE restart, or replace draining with joining

WAVE 4  ── gated
  GW-23b  dedicated I/O thread + ring buffer; closes E5 (P0)
```

**Wave-2 merge order is fixed:** GW-24 → GW-27 → GW-22. GW-27 adds one getter/setter pair to
`GatewayConfig`; GW-24 must not restructure that region, and GW-27 rebases onto GW-24.

**Known small collisions, all textual:** `CLAUDE.md` (GW-22 §1 requires a documented lifetime
rule) — instruct agents to **append, never restructure**. `docs/refactor/` checkboxes likewise.

---

## 5. Exit criteria

Phase 2 is done when all of the following hold. Anything unmeasured is recorded as
**unmeasured**, not as passed — Phase 1 left three criteria in that state and saying so
plainly is the point.

- [ ] `./gradlew test` green, count ≥ 204, and `lintDebug` clean.
- [ ] **GW-27's fixture test:** restart the app with the 8 unread SMS present; **zero**
      re-forwards at the PBX. Then fault-inject the read-flag write and repeat — still zero.
- [ ] **B1e:** on lavender, with a populated custom mute list, every DEC control's saved
      original matches what the native getter reads (not the hardcoded 84), and the mic is
      verifiably live after the call via a normal non-gateway call.
- [ ] **GW-24's actual bug:** select mute controls in the web UI, place a call, confirm those
      controls are muted, and confirm the migration is idempotent with no `ClassCastException`.
- [ ] **GW-26:** `onDestroy` main-thread time bounded and logged; a destroy during in-flight
      SIP init leaves **no** registered account behind (the new cancellation path); no leaked
      `Gateway::CpuWakeLock`; an explicit STOP survives `START_STICKY` *and* the InCallService.
- [ ] **GW-25 false-positive check:** 30 normal calls of varying length, both directions,
      **zero** watchdog terminations — run this *before* merge, not after.
- [ ] **GW-22:** 500-cycle soak with `callsCreated - callsDeleted` equal to active calls at
      the end, and **zero tombstones**. Native-heap slope compared against a pre-change
      baseline, reported honestly even if flat (see §2.3).
- [ ] **GW-23a:** recorded audio both directions on merlinx compared against a pre-change
      recording — no new dropouts, no pitch/rate change; error counters not worse.
- [ ] Full call matrix on both SoCs with debug assertions armed: zero assertion failures,
      zero `ILLEGAL TRANSITION`, zero skipped frames, zero new tombstones.

**Carried forward from Phase 1, still unmeasured:** thread-count drop (no clean pre-GW-10
baseline), pjsua pool flatness over ~200 reconnects, GW-12's 50-cycle random-offset hangup
soak, and the E2 restart-with-a-bridged-call case.

---

## 6. Findings that are NOT Phase 2 work

Filed, owned elsewhere, do not fold in:

- **D4c, F4b, H7d, H7e, H10** and the dead `RootHelper` surface (§2.1), dead `deleteSms`
  (§2.2), dead `setSoundCard` (§2.5) → **GW-31** (the deletion sweep).
- **D5** (double `Audio streams stopped` on a bridged GSM-side hangup) — benign; the real fix
  is making `stopAudioStreams()` log only when it stopped something, an
  `AudioBridgeManager`/`GsmAudioPort` change.
- **F6c** (`ReconnectionStrategy.onSuccess()` clears `pending` without cancelling the armed
  timer) — one line, `ReconnectionStrategy`'s own bug. Cheap enough to fold into wave 1's
  GW-26 if that agent is already in the area; otherwise its own commit.
- **S1, S2** (exported receiver with no permission; `WebConfigServer` on `0.0.0.0:8080` with
  no auth returning `sip_password` in cleartext) → **GW-30**, Phase 3. Note GW-24 removes the
  hardcoded `192.168.5.95` / `gateway123` defaults, which is a small independent improvement
  to S2's blast radius.
- **AUDIT H8c needs amending** per §2.7 — its stated failure mode is wrong.
