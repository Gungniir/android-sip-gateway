# GW-24 — Config: web UI writes a key nothing reads; non-atomic preference writes

**Phase** 2 · **Severity** P1 (silent misconfiguration) · **Closes** AUDIT H4, H4b
**Files** `WebConfigServer.java`, `config/GatewayConfig.java`, `GatewayControlReceiver.java`, `ui/MainViewModel.java`
**Depends on** nothing · **Conflicts with** GW-30 (`WebConfigServer.java`)

## Problem

**Key mismatch — the web UI's mute-control selection is silently discarded.**

- `WebConfigServer` reads (`:156`) and writes (`:267`) `"mic_mute_controls"` as a
  **`StringSet`**.
- `GatewayConfig.KEY_MIC_MUTE_CONTROLS` (`:70`) is `"mic_mute_decs"`, read as a
  comma-separated **`String`** (`getMicMuteControls()`, `:365`).

Nothing reads `mic_mute_controls`. A user selecting mute controls in the web interface
sees them saved and reloaded (the web server reads back its own key), but
`DeviceMuteManager.muteCustomControls` (`:317`) → `config.getAllMuteControls()` (`:403`)
never sees them. The custom preset therefore mutes nothing, and the local mic stays live
during calls — which on the Qualcomm path means the far end hears the room.

Worse, if the two keys are ever unified naively, `getString` on a key holding a
`StringSet` throws `ClassCastException` at config-load time.

**Non-atomic writes.** `postConfig` (`:219`) calls `audioEditor.apply()` three times —
`:251`, `:268`, `:274`. A reader between them observes a half-applied config. Same shape
in `GatewayControlReceiver.configure` (`:270-274`), which applies two editors separately.

**Bypassed abstraction.** `WebConfigServer` and `GatewayControlReceiver` both open
`SharedPreferences` by raw name and key (`"gateway_prefs"`, `"gsm_audio_config"`,
`"device_mute_prefs"`) instead of going through `GatewayConfig` — which is exactly how the
key drift happened. `WebConfigServer` also hardcodes different defaults than
`GatewayConfig` (e.g. `"192.168.5.95"` at `:125`, `"gateway123"` at `:128`, `"101"` at
`:131`), so the web UI shows values the app would never use.

## Required change

1. **Unify on one key and one type.** Pick `GatewayConfig`'s representation
   (comma-separated `String` under a single key) or the `StringSet` — either is fine, but
   exactly one. Add a **migration**: on `GatewayConfig.init`, if the legacy key is present
   and the canonical one is not, convert and remove the legacy key. Handle the
   `ClassCastException` case defensively (read with `getAll()` and type-check) since
   existing devices have both keys in different types.
2. **Route every config read/write through `GatewayConfig`.** Delete the raw
   `getSharedPreferences(...)` calls in `WebConfigServer` (`:124`, `:135`, `:145`, `:233`,
   `:245`, `:255`, `:299`) and `GatewayControlReceiver` (`:117`, `:177`, `:244`, `:264`,
   `:288`). Add whatever getters/setters are missing to `GatewayConfig`. This is what
   makes key drift structurally impossible rather than merely fixed once.
3. **One atomic write per request.** A single `SharedPreferences.Editor` per prefs file,
   `apply()` once at the end. Add a `GatewayConfig.updateAll(...)`-style bulk method if
   needed — `updateSipConfig` (`:456`) and `updateAudioConfig` (`:481`) already show the
   pattern.
4. **Delete the duplicate defaults.** `WebConfigServer` must render `GatewayConfig`'s
   defaults, not its own. The hardcoded `192.168.5.95` / `gateway123` are also a small
   information leak in a page served without auth (GW-30).
5. **`DeviceMuteManager` reads its own prefs directly too** (`:147`, `:151`, `:238`) with
   a hardcoded `"gsm_audio_config"` / `"card"`. Route those through `GatewayConfig`
   (`getAudioCard()`, `getMutePreset()`) as well.
6. **Make audio config actually reloadable (H4b).** `QualcommAudioProfile` snapshots
   `getAllMuteControls()`, `getCaptureDevice()`, `getPlaybackDevice()` and
   `getMultimediaRoute()` in its constructor (`:45-48`); the profile is built once by
   `GsmAudioPort`'s constructor and the port is `static`
   (`AudioBridgeManager.java:28`, `:68`). So audio config changes never take effect until
   the process restarts, while the UI and `reloadConfig` both report success.
   Either re-read the config in `setupMixer` (simplest — it runs per call), or have
   `reloadConfig` rebuild the port when audio settings changed. Whichever is chosen, the
   UI must stop claiming a change took effect when it did not — the existing
   "Restart to apply" toast (`ui/MainViewModel.java:408`, `:422`) is honest and should be
   kept if the config genuinely still needs a restart.

## Acceptance criteria

- [x] One key, one type for the mute-control list; legacy values migrated on first run.
      Canonical: `mic_mute_decs`, comma-separated `String`. Migration in `GatewayConfig.init`,
      before the instance is published.
- [x] No component outside `config/` calls `getSharedPreferences` for gateway config.
      `WebConfigServer`, `GatewayControlReceiver`, `DeviceMuteManager`, `BootReceiver` and
      `BatteryLimitService` all route through `GatewayConfig` / `GatewayConfig.from(context)`.
      (`PjsipSipService`'s `gateway_lifecycle` prefs are GW-26's service-lifecycle latch, not
      gateway config, and are left alone.)
- [x] Each config-save request performs exactly one `apply()` per prefs file —
      `GatewayConfig.Editor`.
- [x] Defaults are defined once, in `GatewayConfig`.
- [x] Migration is safe against a device that already has both keys with mismatched types:
      read via `getAll()` + type check, canonical wins, commit-then-read-back before the
      legacy key is removed. `MicMuteControlMigrationTest`.
- [x] **H4b:** `QualcommAudioProfile` re-reads route / devices / mute list per
      `setupMixer`; teardown and enforce use the session that set up. The sound card and the
      SoC profile still need a restart, and the toast says so.

**Migration placement, decided:** `GatewayConfig.init` moved to `GatewayApplication.onCreate`
**and** every raw-prefs reader routed through `GatewayConfig`. Either alone would have been
enough on paper; both together mean the ordering does not depend on which component starts
the process. `GatewayConfig.from(context)` exists for the entry points that can be first
(receivers, a directly-started service, JVM tests with no `Application`) and cannot bypass
`init`.

**Still needs hardware** — see AUDIT H4's warning box. This change is what arms
`QualcommAudioProfile`'s mute loop (B1e); its first real execution is the next GSM call on a
device with the custom preset selected.

## Verification

1. **The actual bug:** set the mute preset to `custom` and select controls in the web
   interface. Then place a call and confirm those controls are muted:
   ```
   adb shell su -c 'tinymix -D 0 get "DEC1 Volume"'
   ```
   Today this shows the un-muted value; after the fix it must be 0 during the call.
2. Migration: on a device with the legacy `mic_mute_controls` StringSet present, launch
   the app and confirm the values appear under the canonical key and the custom preset
   works. Confirm no `ClassCastException` in logcat.
3. Confirm the web UI shows the same server/user/destination values the app actually uses
   (previously it showed its own hardcoded defaults when unset).

## Risk

Low-medium. The migration touches persisted state on a live device — make it idempotent
and non-destructive (write the new key, then remove the old only after a successful read
back). Back up prefs before testing:
`adb shell su -c 'cp -r /data/data/org.onetwoone.gateway/shared_prefs /sdcard/prefs-backup'`.
