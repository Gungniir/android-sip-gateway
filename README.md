# GSM-SIP Gateway

Android application that turns a Qualcomm-based phone into a GSM-SIP gateway.

## What is this?

This app bridges GSM calls and SMS to VoIP (SIP), allowing:
- **SIP→GSM calls**: Make GSM calls through the phone's modem from SIP PBX
- **GSM→SIP calls**: Receive incoming GSM calls and route them to PBX
- **SMS GSM↔SIP**: Bidirectional SMS forwarding via SIP MESSAGE
- **Dual-SIM support**: Per-SIM routing (SIM1↔ext101, SIM2↔ext102)
- **Video calls**: H264/VP8 video between SIP clients via the PBX
- **TLS/SRTP encryption**: Secure external SIP access
- **Web configuration**: Browser-based settings on port 8080
- **Auto-reconnect**: Exponential backoff (5s → 60s) on connection loss
- **Battery management**: Charge limit (60%) + watchdog for 24/7 operation
- **Boot auto-start**: Automatically starts on device boot

## Requirements

### Hardware
- Android phone with **Qualcomm** chipset
- **Root access** (Magisk recommended)
- Active SIM card (dual-SIM supported)
- **Works on VoLTE and GSM networks**

### Tested Devices

| Device | SoC | ROM | Status |
|--------|-----|-----|--------|
| Xiaomi Redmi Note 7 (lavender) | Snapdragon 660 | [LineageOS 17.1](https://xdaforums.com/t/rom-official-nightlies-lineageos-17-1.4109617/) | ✅ Working |

### Software
- Android 8.1+ (LineageOS recommended)
- SIP PBX: Asterisk (FreePBX)
- See `asterisk-config/` for PBX configuration examples

### Important!
- **Qualcomm only!** Uses Qualcomm-specific mixer controls: `VOC_REC_DL`, `VOC_REC_UL`, `Incall_Music`
- **Root required!** App directly accesses `/dev/snd/*`
- **SELinux must be permissive!** Otherwise ALSA access is blocked

---

## Library Versions

| Component | Version |
|-----------|---------|
| PJSIP | 2.14.1 |
| OpenSSL | 1.1.1k |
| OpenH264 | 2.1.0 |
| Opus | 1.3.1 |
| BCG729 | 1.1.1 |
| SWIG | 4.0.2 |
| Android NDK | r21e |
| Android SDK | API 21-36 |
| tinyalsa | bundled (custom) |

### Build Configuration
- `compileSdkVersion`: 36
- `minSdkVersion`: 23
- `targetSdkVersion`: 27
- Target architecture: `arm64-v8a`

---

## Build Guide (Step by Step)

### Quick Start (Production Release)

For building a signed production release APK:

```bash
# One-time setup: Create signing keystore
./setup-keystore.sh

# Build signed release APK
./build-release.sh
```

See [BUILD.md](BUILD.md) for detailed build instructions, versioning, and signing configuration.

### Manual Build (Development)

### Prerequisites

1. **Linux machine** (Ubuntu 20.04+ recommended)
2. **Android Studio** (or just Gradle + Android SDK)
3. **Android NDK r21e** (for native code compilation)
4. **Java JDK 11+**

### Step 1: Clone the repository

```bash
git clone https://github.com/user/gsm-sip-gateway.git
cd gsm-sip-gateway
```

### Step 2: Install Android SDK & NDK

If you don't have Android Studio:

```bash
# Download command line tools
wget https://dl.google.com/android/repository/commandlinetools-linux-8512546_latest.zip
unzip commandlinetools-linux-8512546_latest.zip -d android-sdk
cd android-sdk/cmdline-tools
mkdir latest && mv bin lib source.properties latest/

# Set environment
export ANDROID_HOME=$PWD/../..
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin

# Install SDK and NDK
sdkmanager "platforms;android-27" "build-tools;30.0.3" "ndk;21.4.7075529"
```

### Step 3: Build PJSIP (if needed)

PJSIP library is pre-built in `app/libs/`. To rebuild from source:

```bash
cd pjsip-build

# Edit config.conf if needed (architecture, versions, etc.)

# Build
./prepare-build-system
./build

# Copy output to app/libs
cp -r /home/output/pjsip-build-output/lib/* ../app/libs/
```

### Step 4: Build the APK

```bash
# From project root
./gradlew assembleDebug

# APK will be at:
# app/build/outputs/apk/debug/app-debug.apk
```

### Step 5: Install on device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Device Setup

### 1. Enable Root Access

Make sure your device is rooted (Magisk recommended).

### 2. Set SELinux to Permissive

**This is REQUIRED!** Without this, the app cannot access ALSA devices.

```bash
adb shell "su -c 'setenforce 0'"

# Verify
adb shell getenforce  # Should output: Permissive
```

To make it persistent across reboots, add to your boot scripts or use a Magisk module.

## App Configuration

### Web Configuration (recommended)
1. Enable "Web Interface" toggle in app
2. Open `http://<phone-ip>:8080` in browser
3. Configure all settings and click "Apply"
4. Settings are saved and service restarts automatically

### SIP Settings (in UI)
- **SIP Server** — PBX IP address (e.g., `192.168.1.100`)
- **SIP Port** — `5060` (UDP) or `5061` (TLS)
- **Use TLS** — enable encrypted SIP signaling (uses `sip:...;transport=tls`)
- **Realm** — SIP realm for digest authentication (empty = "*" for any realm)
- **Username** — gateway account username
- **Password** — gateway account password
- **SIM1 Destination** — SIP extension for SIM1 (e.g., `101`)
- **SIM2 Destination** — SIP extension for SIM2 (e.g., `102`)

### Audio Settings (in UI)
- **Sound Card** — usually Card 0
- **Capture Device** — for GSM→SIP audio (e.g., `0: MultiMedia1`)
- **Playback Device** — for SIP→GSM audio (e.g., `0: MultiMedia1`)
- **Mixer Route** — MultiMedia1, MultiMedia2, etc.
- **TX Gain (GSM→SIP)** — adjust volume from GSM to SIP peer (dB, default 0)
- **RX Gain (SIP→GSM)** — adjust volume from SIP to GSM peer (dB, default 0)

### Device Mute Profiles

The app needs to mute the device's speaker and microphone during bridged calls to prevent echo and audio leakage. Different devices require different ALSA mixer controls. Select the appropriate profile:

| Profile | Speaker Mute | Mic Mute | Devices |
|---------|--------------|----------|---------|
| **Redmi Note 7** | RX1-7 Digital Mute | DEC1-8 Volume = 0 | Redmi Note 7, other SDM6xx |
| **Generic** | RX1-3 Digital Mute | DEC1-4 Volume = 0 | Other SDM4xx/SDM6xx devices |
| **Custom** | User-defined controls | User-defined controls | Other Qualcomm devices |

**Why hardware mute?**
- `AudioManager.setMicrophoneMute()` breaks the `Incall_Music` audio injection path
- `AudioManager.setStreamVolume(0)` doesn't fully mute on all devices
- ALSA mixer controls provide reliable hardware-level mute

The mute is enforced every 3 seconds by the watchdog to handle Android audio rerouting

### Finding Mixer Controls for New Devices

If your device isn't working with the presets, follow these steps to find the correct mixer controls:

#### Step 1: Get root shell access
```bash
adb shell
su
```

#### Step 2: Start a GSM call
Make a regular phone call from the device to any number (or receive a call).

#### Step 3: List all mixer controls during the call
```bash
# List all controls with current values
tinymix -D 0

# Or just list control names
tinymix -D 0 | grep -E "^[0-9]+"
```

#### Step 4: Find speaker/earpiece controls
Look for controls containing these keywords (varies by device):
- **Speaker:** `EAR_S`, `SPK`, `RCV`, `HPHL`, `HPHR`, `RX` + `Digital Mute`
- **Example:** `EAR_S Switch`, `SPK` Switch`, `RX1 Digital Mute`

Test by setting them to 0/Off during a call:
```bash
# Mute earpiece (example)
tinymix -D 0 "EAR_S" "Off"

# If audio from far-end stops - this is the right control!
```

#### Step 5: Find microphone controls
Look for controls containing:
- **Mic volume:** `DEC` + `Volume`, `ADC` + `Volume`
- **Mic routing:** `DEC` + `MUX`, `ADC` + `MUX`
- **Example:** `DEC1 Volume`, `DEC1 MUX`, `ADC1 Volume`

Test by setting volume to 0 or routing to ZERO:
```bash
# Mute mic volume (example)
tinymix -D 0 "DEC1 Volume" 0

# Or disable mic routing
tinymix -D 0 "DEC1 MUX" "ZERO"

# If the other party can't hear you - this is the right control!
```

#### Step 6: Configure in the app
1. Select **Custom** preset in the app
2. **Detected controls** — check the controls you found in the checkboxes
3. **Manual controls** — if your controls aren't in the list, enter them manually:
   ```
   EAR_S, DEC1 Volume, DEC2 MUX, ADC1 Volume
   ```
   - Controls with `Volume` in the name are set to `0` (INT)
   - Other controls are set to `ZERO` (ENUM)

4. Save and restart the service

#### Tips
- Always test during an active GSM call
- Some devices use multiple DEC controls (DEC1-8), try muting all of them
- Card number is usually 0 (`-D 0`), but check `/proc/asound/cards` if unsure
- Use `tinymix -D 0 "control name"` to see possible values for a control

---

## Architecture

```
┌─────────────┐     SIP/RTP      ┌─────────────────┐    ALSA/tinyalsa   ┌─────────┐
│  Asterisk   │ <──────────────> │  Gateway App    │ <────────────────> │   GSM   │
│    PBX      │                  │  (PJSIP + JNI)  │                    │  Modem  │
└─────────────┘                  └─────────────────┘                    └─────────┘
```

### Audio Flow

```
GSM (far-end voice) ──► VOC_REC_DL ──► pcm_read() ──► PJSIP ──► RTP ──► SIP PBX
SIP PBX ──► RTP ──► PJSIP ──► pcm_write() ──► Incall_Music ──► GSM (to far-end)
```

### Components

| File | Purpose |
|------|---------|
| `PjsipSipService.java` | Main SIP service, orchestrates all components |
| `sip/SipEndpointManager.java` | PJSIP endpoint lifecycle, TLS transport |
| `sip/SipAccountManager.java` | SIP account registration, MESSAGE handling |
| `sip/ReconnectionStrategy.java` | Exponential backoff reconnect (5s→60s) |
| `call/CallManager.java` | Call coordination between SIP and GSM |
| `audio/AudioBridgeManager.java` | Audio bridge lifecycle, gain control |
| `GsmAudioPort.java` | PJSIP AudioMediaPort for PCM callbacks |
| `GsmAudioNative.java` | JNI wrapper for tinyalsa |
| `gsm_audio_jni.c` | Native C code: pcm_read/write, mixer control |
| `GatewayInCallService.java` | Android InCallService for GSM call interception |
| `GatewayCall.java` | PJSIP Call wrapper with dispose pattern |
| `GatewayAccount.java` | PJSIP Account wrapper |
| `config/GatewayConfig.java` | Centralized SharedPreferences management |
| `SmsHandler.java` | SMS monitoring and sending via SmsManager |
| `BatteryLimitService.java` | Battery charge limit control (root) |
| `BatteryWatchdog.java` | Independent safety check every 15 min |
| `DeviceMuteManager.java` | Hardware mute via ALSA mixer controls |
| `power/PowerController.java` | WakeLock management, battery optimization |
| `WebConfigServer.java` | HTTP server for browser-based configuration |
| `GatewayControlReceiver.java` | Intent API for remote configuration |
| `BootReceiver.java` | Auto-start on device boot |
| `RootHelper.java` | Root command execution |
| `ui/MainViewModel.java` | MVVM ViewModel for MainActivity |
| `MainActivity.java` | UI with settings |

---

## Hacks and Workarounds

### 1. SELinux Permissive Mode
Without this, the app cannot open `/dev/snd/*`. Done at startup via `RootHelper`:
```java
RootHelper.execute("setenforce 0");
RootHelper.execute("chmod 666 /dev/snd/*");
```

### 2. PJSIP Thread Registration
PJSIP requires thread registration. All PJSIP calls from non-main threads are wrapped:
```java
new Thread(() -> {
    endpoint.libRegisterThread("ThreadName");
    // ... PJSIP calls ...
}).start();
```

### 3. Microphone Mute via ALSA (not AudioManager!)
`AudioManager.setMicrophoneMute(true)` breaks the `Incall_Music` audio path! Instead, mute via mixer:
```java
GsmAudioNative.setMixerControl(card, "DEC1 Volume", 0);  // mute
GsmAudioNative.setMixerControl(card, "DEC1 Volume", 84); // restore
```

### 4. SIP Service Retry on Incoming GSM
If GSM call arrives before SIP service is registered, retry with exponential backoff:
```java
private void makeSipCallWithRetry(String callerNumber, int attempt) {
    if (sipService != null && sipService.isSipRegistered()) {
        sipService.onIncomingGsmCall(callerNumber);
    } else {
        new Handler().postDelayed(() -> makeSipCallWithRetry(...), 500);
    }
}
```

### 5. Frame Accumulation in GsmAudioPort
PJSIP requests 320 bytes (20ms), but ALSA may return less. An accumulation buffer is used.

### 6. CallerID via Custom SIP Header
GSM CallerID is passed to the PBX via `X-GSM-CallerID` SIP header.

### 7. SMS Sender via From Display Name
For SIP MESSAGE, GSM sender is passed in the From header display name:
```
From: "+79123456789" <sip:gateway@server>
```
The PBX extracts the sender number from the display name.

### 8. SMS Retry on SIP Reconnection
When SIP is disconnected, incoming SMS are tracked but not deleted. On reconnection:
```java
void onRegState(boolean registered, String reason) {
    if (registered && smsHandler != null) {
        smsHandler.processInbox();  // Retry pending SMS
    }
}
```

### 9. Battery Charge Control via sysfs
Root access allows controlling charging via kernel sysfs:
```java
RootHelper.execRoot("echo 0 > /sys/class/power_supply/battery/charging_enabled");
```
Paths vary by device — app tries multiple known paths.

---

## Legal Notice

**This software is provided for educational and legitimate business purposes only.**

### Usage Requirements

- ✅ Use only on devices you own or have explicit permission to modify
- ✅ Ensure compliance with your mobile carrier's Terms of Service
- ✅ Check local regulations regarding GSM gateways and VoIP services
- ✅ Obtain proper authorization before routing calls through the gateway

### Intended Use Cases

**Legitimate uses:**
- Personal home office PBX integration
- Small business telephony systems
- Testing and development of VoIP applications
- Educational purposes and research

**NOT intended for:**
- ❌ Bypassing carrier restrictions or charges
- ❌ Commercial SIM box operations
- ❌ Unauthorized call termination services
- ❌ Violating carrier Terms of Service

### Disclaimer

**The authors and contributors of this project:**
- Assume NO liability for any violations of carrier Terms of Service
- Assume NO responsibility for any legal consequences of misuse
- Provide this software "AS IS" without warranties of any kind
- Are NOT responsible for any damages or losses resulting from its use

**Important:** Some mobile carriers explicitly prohibit the use of GSM gateways. Using this software may result in service termination or SIM card blocking. **You are solely responsible for ensuring compliance with all applicable laws and agreements.**

---

## License

MIT
