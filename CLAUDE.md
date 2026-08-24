# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Android app (Java, package `org.onetwoone.gateway`) that turns a rooted Qualcomm phone into a GSM↔SIP gateway: it bridges the phone's GSM modem (voice + SMS) to an Asterisk/FreePBX PBX via PJSIP, tapping call audio at the ALSA layer with Qualcomm-specific mixer controls (`VOC_REC_DL`, `VOC_REC_UL`, `Incall_Music`). Server-side PBX config lives in `asterisk-config/` — it is deployed to a Linux Asterisk box, not bundled in the APK.

## Commands

- Debug build: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- Unit tests (JVM/Robolectric only, no instrumented tests): `./gradlew test`
- Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk` — a rooted test device is usually connected; use `/deploy` for build+install+verify
- Signed release: `./build-release.sh [--clean] [--bump-version]` — needs `keystore.properties` (create once with `./setup-keystore.sh`); output lands in `release-output/`

## Gotchas

- **The SIP stack is PJSIP**, not Linphone: SWIG-generated bindings are vendored at `app/src/main/java/org/pjsip/pjsua2/` (never hand-edit them) with prebuilt `libpjsua2.so` in `app/src/main/jniLibs/arm64-v8a/`. Rebuilding PJSIP is optional and done via `pjsip-build/` in Docker.
- `gradle.properties` pins `org.gradle.java.home` to a local JDK 17 path — AGP 8.2.0 requires JDK 17; adjust the path if your JDK lives elsewhere.
- The CMake native build (`app/src/main/cpp/`: JNI audio bridge + bundled tinyalsa) needs an Android NDK; Gradle auto-installs one on first build. (The prebuilt PJSIP libs were built with NDK r21e — only relevant when rebuilding PJSIP itself.)
- `targetSdkVersion` is deliberately 27 — do not raise it; privileged telephony/InCallService behavior depends on it. ABI is `arm64-v8a` only.
- Runtime requires: Qualcomm chipset, root (Magisk), SELinux permissive, and the app set as default dialer (ROLE_DIALER) so `GatewayInCallService` binds.
- **Never mute the mic via `AudioManager`** — it breaks the `Incall_Music` ALSA playback path. Mic mute must go through the ALSA mixer (`DeviceMuteManager`).
- GSM CallerID crosses SIP in a custom `X-GSM-CallerID` header; the SMS sender number rides in the SIP `From` display name.
- The PBX chooses which SIM an outbound GSM call leaves on via a custom `X-GSM-SIM` header (`1` or `2`) on the incoming INVITE or MESSAGE (`SipHeaderReader`). Absent the header, the app falls back to mapping the caller extension to a slot (`GatewayConfig.getSimSlotForCaller`).
- DTMF pressed by the SIP caller (RFC4733 from the PBX) is replayed on the GSM leg out-of-band through Telecom's `playDtmfTone`/`stopDtmfTone`, paced by `GsmDtmfSender` — it never goes through the ALSA bridge. The other direction needs no code: GSM-side tones are in the tapped call audio already.
- The PBX is Asterisk (FreePBX). FreeSWITCH is not supported. Two separate server-side configs live here: `asterisk-config/` (plain Asterisk, hand-written dialplan) and `freepbx/` (FreePBX, mostly GUI-managed — see its own CLAUDE.md).
- Lint: `./gradlew lintDebug` — pre-existing issues are baselined in `app/lint-baseline.xml`; only new issues fail. Release builds are not lint-gated. No code formatter is configured.
- The app can be controlled via exported broadcasts: `org.onetwoone.gateway.{START,STOP,CONFIGURE,GET_STATUS}` (`GatewayControlReceiver`).
- **All gateway configuration goes through `config/GatewayConfig`** — it is the only place that may name a preference file (`gateway_prefs`, `gsm_audio_config`, `device_mute_prefs`) or a key, and the only place defaults are defined. Do not add a `getSharedPreferences` call elsewhere: that is how the web UI came to write a mute-control key nothing read (AUDIT H4). Multi-value writes use `config.edit()…apply()`, which does one `apply()` per preference file. `GatewayConfig.init` runs in `GatewayApplication.onCreate` because it carries the preference migration; entry points that can start a process use `GatewayConfig.from(context)`.
- An explicit **STOP** (broadcast or UI) now latches a persisted flag (`gateway_lifecycle` prefs, `user_stopped`), so the gateway is not resurrected by the `START_STICKY` redelivery or by `GatewayInCallService` binding as default dialler. Any start carrying an intent clears it — a crash, an OOM kill or a reboot still brings the gateway back. If a device seems not to start, check that flag first.
- **pjsua2 object lifetime.** Every SWIG proxy in `org.pjsip.pjsua2` carries a `swigCMemOwn` flag and only `true` means the Java side owns native memory — read the generated factory, never guess from the type name (two `AudioMedia`s can differ). The rule: **what the Java side `new`s, the Java side deletes; what pjsua2 hands back by value must be deleted after use; what pjsua2 owns and hands back by reference must not.** Owned: `CallInfo`, `AccountInfo`, `AudioMediaVector2`, `ConfPortInfo`, `StreamInfo`, `StreamStat`, `CodecInfoVector2`, `IntVector` *from `transportEnum()`*, and anything you construct (`CallOpParam`, `SipHeader`, …). Not owned: `CallMediaInfoVector`/`CallMediaInfo`, `Media`, `AudioMedia` from `typecastFromMedia`/`AudioMediaVector2.get`, `CodecInfo`, `IntVector` from `getListeners()`, `SipTxOption` from `getTxOption()`, `RtcpStat`, `ByteVector` from `MediaFrame.getBuf()`. Go through `sip/Pjsua2Lifetime` — it has an overload for exactly the owned set, so a compile error means the object is pjsua's. Deletes go in a `finally` that encloses **every** use of views derived from the object (`CallInfo.getMedia()` points into the `CallInfo`). `Call` and `Account` are SWIG *directors*, not value objects — their deletion is an ordering problem rather than a `finally`. A `Call` is deleted by `call/CallGraveyard`, deferred onto the control thread and gated on `getId() == PJSUA_INVALID_ID` (a documented heuristic, not a proof — read its javadoc before changing it); an `Account` by `SipAccountManager`. `diag/PjsipLogWriter`'s singleton is a director held by native code and must stay strongly reachable forever — never delete it.
