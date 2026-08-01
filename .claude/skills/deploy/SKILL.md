---
name: deploy
description: Build the debug APK, install it on the connected rooted test device via adb, restart the gateway service, and verify it comes up via logcat.
disable-model-invocation: true
---

Deploy the current code to the connected test device and verify the gateway starts.

1. **Check a device is connected**: `adb devices` — if no device (or `unauthorized`), stop and tell the user.
2. **Build**: `./gradlew assembleDebug`. On failure, report the error and stop.
3. **Install**: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
4. **Restart the gateway** using its broadcast control API:
   - `adb shell am broadcast -a org.onetwoone.gateway.STOP`
   - `adb shell am broadcast -a org.onetwoone.gateway.START`
5. **Verify via logcat**: clear the log (`adb logcat -c`), then watch ~15 seconds of output filtered to the app (e.g. `adb logcat --pid=$(adb shell pidof -s org.onetwoone.gateway)` or grep for `Pjsip|Gateway|SipService`). Confirm the SIP service starts and account registration succeeds; report any crash, `FATAL EXCEPTION`, or registration failure with the relevant log lines.
6. Summarize: APK installed (version from `app/build.gradle`), service state, registration state.
