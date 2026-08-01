package org.onetwoone.gateway.audio;

import android.content.Context;
import android.util.Log;

import org.onetwoone.gateway.GsmAudioNative;
import org.onetwoone.gateway.config.GatewayConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Qualcomm (SDM/MSM) audio profile.
 *
 * Taps the modem voice path via the "&lt;route&gt; Mixer VOC_REC_DL" capture and
 * "Incall_Music Audio Mixer &lt;route&gt;" injection controls, and mutes the local
 * mic/speaker by zeroing the configured DEC/Volume/MUX/EAR_S/SPK controls.
 *
 * This is the original routing logic previously inlined in GsmAudioPort, moved
 * here unchanged so existing Qualcomm devices behave identically.
 */
public class QualcommAudioProfile implements AudioProfile {
    private static final String TAG = "QualcommAudioProfile";

    private static final int SAMPLE_RATE = 8000;
    private static final int CHANNELS = 1;

    private final Context context;
    private final int captureDevice;
    private final int playbackDevice;
    private final String multimediaRoute;
    private final List<String> micMuteControls = new ArrayList<>();

    // Saved originals for restore in teardownMixer()
    private final Map<String, Integer> micOriginalValues = new HashMap<>();
    private final Map<String, String> micOriginalEnumValues = new HashMap<>();

    public QualcommAudioProfile(Context context, GatewayConfig config) {
        this.context = context.getApplicationContext();
        this.captureDevice = config.getCaptureDevice();
        this.playbackDevice = config.getPlaybackDevice();
        this.multimediaRoute = config.getMultimediaRoute();
        this.micMuteControls.addAll(config.getAllMuteControls());
    }

    @Override public String name() { return "Qualcomm"; }
    @Override public int captureDevice() { return captureDevice; }
    @Override public int playbackDevice() { return playbackDevice; }
    @Override public int captureSampleRate() { return SAMPLE_RATE; }
    @Override public int captureChannels() { return CHANNELS; }
    @Override public int playbackSampleRate() { return SAMPLE_RATE; }
    @Override public int playbackChannels() { return CHANNELS; }
    @Override public boolean handlesMicMute() { return false; }

    @Override
    public void setupMixer(int card) {
        Log.d(TAG, "Setting up mixer for " + multimediaRoute + "...");

        boolean ok = true;

        // Enable VOC_REC capture (DL only - UL would capture Incall_Music and cause echo!)
        ok &= GsmAudioNative.setMixerControl(card, multimediaRoute + " Mixer VOC_REC_DL", 1);
        // VOC_REC_UL disabled - it captures uplink including Incall_Music, causing echo

        // Enable Incall_Music playback for BOTH SIM slots
        // SIM1 uses Incall_Music, SIM2 uses Incall_Music_2
        ok &= GsmAudioNative.setMixerControl(card, "Incall_Music Audio Mixer " + multimediaRoute, 1);
        GsmAudioNative.setMixerControl(card, "Incall_Music_2 Audio Mixer " + multimediaRoute, 1);

        // Mute ALL configured controls (microphone DECs + speaker EAR_S/SPK)
        // Different devices use different DECs, so we mute ALL of them
        // Types: Volume controls (INT), MUX controls (ENUM), Speaker controls (ENUM)
        if (!micMuteControls.isEmpty()) {
            micOriginalValues.clear();
            micOriginalEnumValues.clear();
            for (String decControl : micMuteControls) {
                if (decControl.contains(" Volume")) {
                    int originalValue = readMixerControlValue(card, decControl);
                    micOriginalValues.put(decControl, originalValue);
                    GsmAudioNative.setMixerControl(card, decControl, 0);
                    Log.d(TAG, "Muted: " + decControl + " = 0 (original=" + originalValue + ")");
                } else if (decControl.contains(" MUX")) {
                    String originalValue = readMixerControlEnum(card, decControl);
                    micOriginalEnumValues.put(decControl, originalValue);
                    GsmAudioNative.setMixerControlEnum(card, decControl, "ZERO");
                    Log.d(TAG, "Muted: " + decControl + " = ZERO (original=" + originalValue + ")");
                } else if (decControl.equals("EAR_S") || decControl.equals("SPK")) {
                    String originalValue = readMixerControlEnum(card, decControl);
                    micOriginalEnumValues.put(decControl, originalValue);
                    GsmAudioNative.setMixerControlEnum(card, decControl, "ZERO");
                    Log.d(TAG, "Speaker muted: " + decControl + " = ZERO (original=" + originalValue + ")");
                }
            }
        }

        if (ok) {
            Log.d(TAG, "Mixer setup OK");
        } else {
            Log.w(TAG, "Mixer setup incomplete - some controls may not exist on this device");
        }
    }

    @Override
    public void enforceMixer(int card) {
        // Re-assert routing + mutes only; do NOT read/save originals here.
        GsmAudioNative.setMixerControl(card, multimediaRoute + " Mixer VOC_REC_DL", 1);
        GsmAudioNative.setMixerControl(card, "Incall_Music Audio Mixer " + multimediaRoute, 1);
        GsmAudioNative.setMixerControl(card, "Incall_Music_2 Audio Mixer " + multimediaRoute, 1);
        for (String decControl : micMuteControls) {
            if (decControl.contains(" Volume")) {
                GsmAudioNative.setMixerControl(card, decControl, 0);
            } else if (decControl.contains(" MUX") || decControl.equals("EAR_S") || decControl.equals("SPK")) {
                GsmAudioNative.setMixerControlEnum(card, decControl, "ZERO");
            }
        }
    }

    @Override
    public void teardownMixer(int card) {
        Log.d(TAG, "Tearing down mixer...");

        GsmAudioNative.setMixerControl(card, multimediaRoute + " Mixer VOC_REC_DL", 0);
        GsmAudioNative.setMixerControl(card, "Incall_Music Audio Mixer " + multimediaRoute, 0);
        GsmAudioNative.setMixerControl(card, "Incall_Music_2 Audio Mixer " + multimediaRoute, 0);

        // Restore ALL muted controls (Volume, MUX, and Speaker)
        for (String decControl : micMuteControls) {
            if (decControl.contains(" Volume")) {
                Integer originalValue = micOriginalValues.get(decControl);
                if (originalValue != null) {
                    GsmAudioNative.setMixerControl(card, decControl, originalValue);
                    Log.d(TAG, "Restored: " + decControl + " = " + originalValue);
                }
            } else if (decControl.contains(" MUX") || decControl.equals("EAR_S") || decControl.equals("SPK")) {
                String originalValue = micOriginalEnumValues.get(decControl);
                if (originalValue != null && !originalValue.isEmpty()) {
                    GsmAudioNative.setMixerControlEnum(card, decControl, originalValue);
                    Log.d(TAG, "Restored: " + decControl + " = " + originalValue);
                }
            }
        }
        micOriginalValues.clear();
        micOriginalEnumValues.clear();
    }

    /** Read current INT mixer control value via tinymix (fallback 84). */
    private int readMixerControlValue(int card, String controlName) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                "su", "-c", "tinymix -D " + card + " get \"" + controlName + "\""
            });
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor();
            if (line != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length > 0) {
                    return Integer.parseInt(parts[0]);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read mixer control " + controlName + ": " + e.getMessage());
        }
        return 84;  // Default fallback value
    }

    /** Read current ENUM mixer control value via the extracted tinymix binary. */
    private String readMixerControlEnum(int card, String controlName) {
        try {
            File tinymixFile = new File(context.getFilesDir(), "tinymix");
            Process p = Runtime.getRuntime().exec(new String[]{
                tinymixFile.getAbsolutePath(), "-D", String.valueOf(card), "get", controlName
            });
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor();
            if (line != null) {
                return line.trim();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read mixer control ENUM " + controlName + ": " + e.getMessage());
        }
        return "";
    }
}
