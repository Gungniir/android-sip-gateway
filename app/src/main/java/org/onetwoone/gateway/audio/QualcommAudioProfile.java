package org.onetwoone.gateway.audio;

import android.content.Context;
import android.util.Log;

import org.onetwoone.gateway.config.GatewayConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
 * here unchanged so existing Qualcomm devices behave identically. Control names,
 * written values and write order are reverse-engineered and validated on real
 * hardware — do not change them.
 *
 * Thread-safety: see {@link AudioProfile} for the setup/teardown/enforce contract.
 */
public class QualcommAudioProfile implements AudioProfile {
    private static final String TAG = "QualcommAudioProfile";

    private static final int SAMPLE_RATE = 8000;
    private static final int CHANNELS = 1;

    /** Value saved for a " Volume" control that could not be read. */
    private static final int VOLUME_READ_FALLBACK = 84;

    private final MixerControls mixer;
    private final int captureDevice;
    private final int playbackDevice;
    private final String multimediaRoute;
    /** Immutable after construction, so enforce/teardown may iterate it freely. */
    private final List<String> micMuteControls;

    /**
     * Pre-call values of the mic/speaker controls, or null when no setup is in
     * flight. Immutable and swapped with a single write so that a teardown on one
     * thread can never observe a half-built or half-cleared map published by a
     * setup on another (AUDIT B2). Null means exactly "nothing to restore".
     */
    private volatile MixerSnapshot saved;

    /**
     * Serialises setupMixer/teardownMixer against each other. The snapshot alone
     * stops the corruption, but without mutual exclusion a teardown that lands
     * mid-setup still sees null and no-ops, and the mic stays muted. enforceMixer
     * never takes this lock — it must not block the MixerEnforce thread and it
     * touches no saved state.
     */
    private final Object mixerLock = new Object();

    public QualcommAudioProfile(Context context, GatewayConfig config) {
        this(context, config, null);
    }

    /** Visible for testing: inject a fake mixer backend. */
    QualcommAudioProfile(Context context, GatewayConfig config, MixerControls mixer) {
        Context appContext = context.getApplicationContext();
        this.captureDevice = config.getCaptureDevice();
        this.playbackDevice = config.getPlaybackDevice();
        this.multimediaRoute = config.getMultimediaRoute();
        this.micMuteControls =
                Collections.unmodifiableList(new ArrayList<>(config.getAllMuteControls()));
        this.mixer = mixer != null ? mixer : new TinymixControls(appContext);
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
        synchronized (mixerLock) {
            Log.d(TAG, "Setting up mixer for " + multimediaRoute + "...");

            // A snapshot still parked here means the previous session never tore
            // down. Put its values back before reading new ones: otherwise the
            // originals are overwritten by the mute values we ourselves wrote and
            // the local mic stays dead for good (AUDIT B2).
            MixerSnapshot stale = saved;
            saved = null;
            if (stale != null) {
                Log.e(TAG, "setupMixer() over a live snapshot - the previous session never tore "
                        + "down; restoring its " + stale.size() + " saved control(s) first");
                restoreSaved(card, stale);
            }

            boolean ok = true;

            // Enable VOC_REC capture (DL only - UL would capture Incall_Music and cause echo!)
            ok &= mixer.setValue(card, multimediaRoute + " Mixer VOC_REC_DL", 1);
            // VOC_REC_UL disabled - it captures uplink including Incall_Music, causing echo

            // Enable Incall_Music playback for BOTH SIM slots
            // SIM1 uses Incall_Music, SIM2 uses Incall_Music_2
            ok &= mixer.setValue(card, "Incall_Music Audio Mixer " + multimediaRoute, 1);
            mixer.setValue(card, "Incall_Music_2 Audio Mixer " + multimediaRoute, 1);

            // Mute ALL configured controls (microphone DECs + speaker EAR_S/SPK)
            // Different devices use different DECs, so we mute ALL of them
            // Types: Volume controls (INT), MUX controls (ENUM), Speaker controls (ENUM)
            Map<String, Integer> originalValues = new LinkedHashMap<>();
            Map<String, String> originalEnumValues = new LinkedHashMap<>();
            for (String decControl : micMuteControls) {
                if (decControl.contains(" Volume")) {
                    int originalValue = mixer.getValue(card, decControl, VOLUME_READ_FALLBACK);
                    originalValues.put(decControl, originalValue);
                    mixer.setValue(card, decControl, 0);
                    Log.d(TAG, "Muted: " + decControl + " = 0 (original=" + originalValue + ")");
                } else if (decControl.contains(" MUX")) {
                    String originalValue = mixer.getEnum(card, decControl);
                    originalEnumValues.put(decControl, originalValue);
                    mixer.setEnum(card, decControl, "ZERO");
                    Log.d(TAG, "Muted: " + decControl + " = ZERO (original=" + originalValue + ")");
                } else if (decControl.equals("EAR_S") || decControl.equals("SPK")) {
                    String originalValue = mixer.getEnum(card, decControl);
                    originalEnumValues.put(decControl, originalValue);
                    mixer.setEnum(card, decControl, "ZERO");
                    Log.d(TAG, "Speaker muted: " + decControl + " = ZERO (original="
                            + originalValue + ")");
                }
            }

            // Publish once, fully built. Always publish - even with nothing to
            // restore - so "saved == null" means exactly "no setup in flight" and
            // teardownMixer can safely treat it as "already torn down".
            saved = new MixerSnapshot(originalValues, originalEnumValues);

            if (ok) {
                Log.d(TAG, "Mixer setup OK");
            } else {
                Log.w(TAG, "Mixer setup incomplete - some controls may not exist on this device");
            }
        }
    }

    @Override
    public void enforceMixer(int card) {
        // Re-assert routing + mutes only; do NOT read/save originals here, and do
        // NOT take mixerLock - this runs every 2s on the MixerEnforce thread and
        // must never block setup or teardown.
        mixer.setValue(card, multimediaRoute + " Mixer VOC_REC_DL", 1);
        mixer.setValue(card, "Incall_Music Audio Mixer " + multimediaRoute, 1);
        mixer.setValue(card, "Incall_Music_2 Audio Mixer " + multimediaRoute, 1);
        for (String decControl : micMuteControls) {
            if (decControl.contains(" Volume")) {
                mixer.setValue(card, decControl, 0);
            } else if (decControl.contains(" MUX") || decControl.equals("EAR_S")
                    || decControl.equals("SPK")) {
                mixer.setEnum(card, decControl, "ZERO");
            }
        }
    }

    @Override
    public void teardownMixer(int card) {
        synchronized (mixerLock) {
            // Read-then-null, then restore from the detached snapshot: a concurrent
            // setup can no longer empty the originals out from under this restore.
            MixerSnapshot snapshot = saved;
            saved = null;
            if (snapshot == null) {
                Log.d(TAG, "teardownMixer(): nothing saved - already torn down, ignoring");
                return;
            }

            Log.d(TAG, "Tearing down mixer...");

            mixer.setValue(card, multimediaRoute + " Mixer VOC_REC_DL", 0);
            mixer.setValue(card, "Incall_Music Audio Mixer " + multimediaRoute, 0);
            mixer.setValue(card, "Incall_Music_2 Audio Mixer " + multimediaRoute, 0);

            // Restore ALL muted controls (Volume, MUX, and Speaker)
            restoreSaved(card, snapshot);
        }
    }

    /**
     * Write every saved original back, walking the controls in the same order
     * {@link #setupMixer(int)} muted them. Caller holds {@link #mixerLock}.
     */
    private void restoreSaved(int card, MixerSnapshot snapshot) {
        for (String decControl : micMuteControls) {
            if (decControl.contains(" Volume")) {
                Integer originalValue = snapshot.value(decControl);
                if (originalValue != null) {
                    mixer.setValue(card, decControl, originalValue);
                    Log.d(TAG, "Restored: " + decControl + " = " + originalValue);
                }
            } else if (decControl.contains(" MUX") || decControl.equals("EAR_S")
                    || decControl.equals("SPK")) {
                String originalValue = snapshot.enumValue(decControl);
                if (originalValue != null && !originalValue.isEmpty()) {
                    mixer.setEnum(card, decControl, originalValue);
                    Log.d(TAG, "Restored: " + decControl + " = " + originalValue);
                }
            }
        }
    }

    /**
     * Production backend for this profile: writes go through the tinyalsa JNI
     * bridge, reads go through tinymix — INT via {@code su -c tinymix}, ENUM via
     * the tinymix binary extracted into the app's files dir. Lifted verbatim out
     * of the profile so the state handling above can be unit-tested.
     */
    private static final class TinymixControls implements MixerControls {
        private final Context context;

        TinymixControls(Context context) {
            this.context = context;
        }

        @Override
        public boolean setValue(int card, String control, int value) {
            return MixerControls.NATIVE.setValue(card, control, value);
        }

        @Override
        public boolean setEnum(int card, String control, String value) {
            return MixerControls.NATIVE.setEnum(card, control, value);
        }

        /** Read current INT mixer control value via tinymix. */
        @Override
        public int getValue(int card, String control, int fallback) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", "tinymix -D " + card + " get \"" + control + "\""
                });
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = reader.readLine();
                p.waitFor();
                if (line != null) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length > 0) {
                        return Integer.parseInt(parts[0]);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to read mixer control " + control + ": " + e.getMessage());
            }
            return fallback;
        }

        /** Read current ENUM mixer control value via the extracted tinymix binary. */
        @Override
        public String getEnum(int card, String control) {
            try {
                File tinymixFile = new File(context.getFilesDir(), "tinymix");
                Process p = Runtime.getRuntime().exec(new String[]{
                    tinymixFile.getAbsolutePath(), "-D", String.valueOf(card), "get", control
                });
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = reader.readLine();
                p.waitFor();
                if (line != null) {
                    return line.trim();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to read mixer control ENUM " + control + ": " + e.getMessage());
            }
            return "";
        }
    }
}
