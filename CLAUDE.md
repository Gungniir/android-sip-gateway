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
- The PBX is Asterisk (FreePBX). FreeSWITCH is not supported.
- Lint: `./gradlew lintDebug` — pre-existing issues are baselined in `app/lint-baseline.xml`; only new issues fail. Release builds are not lint-gated. No code formatter is configured.
- The app can be controlled via exported broadcasts: `org.onetwoone.gateway.{START,STOP,CONFIGURE,GET_STATUS}` (`GatewayControlReceiver`).
