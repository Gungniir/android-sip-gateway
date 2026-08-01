package org.onetwoone.gateway.audio;

import android.content.Context;
import android.util.Log;

import org.onetwoone.gateway.GsmAudioNative;


import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MediaTek (MT6768 / Helio G85, sound card mt6768-mt6358) audio profile.
 *
 * The modem voice PCM is exposed on the AFE crossbar as PCM_2. Reverse-engineered
 * and verified live on a Redmi Note 9 (2026-08-01): 8 kHz mono opens directly
 * (hardware SRC), so the existing 8 kHz/mono bridge pipeline is reused unchanged —
 * no resampling, no channel conversion.
 *
 * Routing (BOOL switches, card 0):
 *   capture (GSM far-end → SIP): UL2_CH1/2 ← PCM_2_CAP_CH1  = 1, read from Capture_2 (dev 5)
 *   inject  (SIP → GSM far-end): PCM_2_PB_CH1/2 ← DL2_CH1/2 = 1, write to Playback_2 (dev 2)
 *   mute local mic into uplink : PCM_2_PB_CH1/2 ← ADDA_UL_CH1/2 = 0
 *
 * teardownMixer restores every switch to the value it held when setupMixer ran.
 */
public class MediaTekAudioProfile implements AudioProfile {
    private static final String TAG = "MediaTekAudioProfile";

    private static final int CAPTURE_DEVICE = 5;   // Capture_2 (pcmC0D5c)
    private static final int PLAYBACK_DEVICE = 2;   // Playback_2 (pcmC0D2p)
    // Capture memif has an SRC -> 8 kHz mono. Playback memif locks to the modem
    // backend rate (48 kHz) once the mute/inject routing is applied.
    private static final int CAPTURE_RATE = 8000;
    private static final int CAPTURE_CHANNELS = 1;
    private static final int PLAYBACK_RATE = 48000;
    private static final int PLAYBACK_CHANNELS = 1;

    // Switches to turn ON, with fallback original (idle) value 0.
    private static final String[] ENABLE_SWITCHES = {
        "UL2_CH1 PCM_2_CAP_CH1",
        "UL2_CH2 PCM_2_CAP_CH1",
        "PCM_2_PB_CH1 DL2_CH1",
        "PCM_2_PB_CH2 DL2_CH2",
    };

    // Switches to turn OFF (mute local mic), with fallback original (in-call) value 1.
    private static final String[] DISABLE_SWITCHES = {
        "PCM_2_PB_CH1 ADDA_UL_CH1",
        "PCM_2_PB_CH2 ADDA_UL_CH2",
    };

    private final Map<String, Integer> originalValues = new LinkedHashMap<>();

    public MediaTekAudioProfile(Context context) {
        // context currently unused; kept for signature symmetry with other profiles
    }

    @Override public String name() { return "MediaTek"; }
    @Override public int captureDevice() { return CAPTURE_DEVICE; }
    @Override public int playbackDevice() { return PLAYBACK_DEVICE; }
    @Override public int captureSampleRate() { return CAPTURE_RATE; }
    @Override public int captureChannels() { return CAPTURE_CHANNELS; }
    @Override public int playbackSampleRate() { return PLAYBACK_RATE; }
    @Override public int playbackChannels() { return PLAYBACK_CHANNELS; }
    @Override public boolean handlesMicMute() { return true; }

    @Override
    public void setupMixer(int card) {
        Log.d(TAG, "Setting up PCM_2 modem-voice routing...");
        originalValues.clear();

        for (String sw : ENABLE_SWITCHES) {
            originalValues.put(sw, readSwitch(card, sw, 0));
            boolean ok = GsmAudioNative.setMixerControl(card, sw, 1);
            Log.d(TAG, (ok ? "Enabled: " : "FAILED enabling: ") + sw);
        }
        for (String sw : DISABLE_SWITCHES) {
            originalValues.put(sw, readSwitch(card, sw, 1));
            boolean ok = GsmAudioNative.setMixerControl(card, sw, 0);
            Log.d(TAG, (ok ? "Disabled (mic mute): " : "FAILED disabling: ") + sw);
        }
        Log.d(TAG, "MediaTek mixer setup complete");
    }

    @Override
    public void enforceMixer(int card) {
        // Re-assert desired targets only; do NOT read/save originals here.
        for (String sw : ENABLE_SWITCHES) {
            GsmAudioNative.setMixerControl(card, sw, 1);
        }
        for (String sw : DISABLE_SWITCHES) {
            GsmAudioNative.setMixerControl(card, sw, 0);
        }
    }

    @Override
    public void teardownMixer(int card) {
        Log.d(TAG, "Restoring PCM_2 routing...");
        for (Map.Entry<String, Integer> e : originalValues.entrySet()) {
            GsmAudioNative.setMixerControl(card, e.getKey(), e.getValue());
            Log.d(TAG, "Restored: " + e.getKey() + " = " + e.getValue());
        }
        originalValues.clear();
    }

    /**
     * Read a BOOL switch value via the native tinyalsa mixer API.
     * Returns the given fallback if the control is missing / unreadable
     * (e.g. before ALSA permissions are applied).
     */
    private int readSwitch(int card, String controlName, int fallback) {
        int v = GsmAudioNative.getMixerControl(card, controlName);
        return v < 0 ? fallback : v;
    }
}
