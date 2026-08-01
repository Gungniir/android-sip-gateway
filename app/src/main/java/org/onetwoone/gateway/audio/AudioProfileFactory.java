package org.onetwoone.gateway.audio;

import android.content.Context;
import android.util.Log;

import org.onetwoone.gateway.config.GatewayConfig;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * Selects the {@link AudioProfile} for this device.
 *
 * Honors the {@code audio_profile} config key ("qualcomm"|"mediatek"|"auto");
 * on "auto" it sniffs /proc/asound/cards for the SoC codec name.
 */
public final class AudioProfileFactory {
    private static final String TAG = "AudioProfileFactory";

    private AudioProfileFactory() {}

    public static AudioProfile select(Context context, GatewayConfig config) {
        String pref = config.getAudioProfile();
        if ("qualcomm".equalsIgnoreCase(pref)) {
            Log.i(TAG, "Audio profile forced: Qualcomm");
            return new QualcommAudioProfile(context, config);
        }
        if ("mediatek".equalsIgnoreCase(pref)) {
            Log.i(TAG, "Audio profile forced: MediaTek");
            return new MediaTekAudioProfile(context);
        }

        // auto
        String cards = readSoundCards().toLowerCase();
        boolean isMediaTek = cards.contains("mt6768") || cards.contains("mt6358")
                || cards.contains("mediatek") || cards.contains("mtk");
        AudioProfile profile = isMediaTek
                ? new MediaTekAudioProfile(context)
                : new QualcommAudioProfile(context, config);
        Log.i(TAG, "Audio profile auto-detected: " + profile.name()
                + " (from /proc/asound/cards)");
        return profile;
    }

    private static String readSoundCards() {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/asound/cards"))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read /proc/asound/cards: " + e.getMessage());
        }
        return sb.toString();
    }
}
