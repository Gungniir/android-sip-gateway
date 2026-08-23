package org.onetwoone.gateway.audio;

import android.content.Context;
import android.util.Log;

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
 * The switch names, values and the order they are written in are reverse-engineered
 * and validated on real hardware — do not change them.
 *
 * Thread-safety: see {@link AudioProfile} for the setup/teardown/enforce contract.
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

    /** Fallback original for a switch we expect to be off outside a bridged call. */
    private static final int ENABLE_SWITCH_FALLBACK = 0;

    /** Fallback original for a switch we expect to be on during a call. */
    private static final int DISABLE_SWITCH_FALLBACK = 1;

    private final MixerControls mixer;

    /**
     * Pre-call values of every switch above, or null when no setup is in flight.
     * Immutable and swapped with a single write, so a teardown on main / a pjsua
     * worker can never iterate a map that the next setup is clearing on the
     * GsmAudioOpen thread (AUDIT B2). On this SoC that map also carries the
     * ADDA_UL un-mute values, so losing it leaves the phone with no microphone.
     */
    private volatile MixerSnapshot saved;

    /**
     * Serialises setupMixer/teardownMixer against each other. enforceMixer never
     * takes it — it must not block the MixerEnforce thread and touches no state.
     */
    private final Object mixerLock = new Object();

    public MediaTekAudioProfile(Context context) {
        this(context, MixerControls.NATIVE);
    }

    /** Visible for testing: inject a fake mixer backend. */
    MediaTekAudioProfile(Context context, MixerControls mixer) {
        // context currently unused; kept for signature symmetry with other profiles
        this.mixer = mixer;
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
        synchronized (mixerLock) {
            Log.d(TAG, "Setting up PCM_2 modem-voice routing...");

            // A snapshot still parked here means the previous session never tore
            // down. Restore it before reading new originals, or the values we read
            // are our own crossbar patch and the mic mute becomes permanent.
            MixerSnapshot stale = saved;
            saved = null;
            if (stale != null) {
                Log.e(TAG, "setupMixer() over a live snapshot - the previous session never tore "
                        + "down; restoring its " + stale.size() + " saved switch(es) first");
                restoreSaved(card, stale);
            }

            Map<String, Integer> originalValues = new LinkedHashMap<>();

            for (String sw : ENABLE_SWITCHES) {
                originalValues.put(sw, mixer.getValue(card, sw, ENABLE_SWITCH_FALLBACK));
                boolean ok = mixer.setValue(card, sw, 1);
                Log.d(TAG, (ok ? "Enabled: " : "FAILED enabling: ") + sw);
            }
            for (String sw : DISABLE_SWITCHES) {
                originalValues.put(sw, mixer.getValue(card, sw, DISABLE_SWITCH_FALLBACK));
                boolean ok = mixer.setValue(card, sw, 0);
                Log.d(TAG, (ok ? "Disabled (mic mute): " : "FAILED disabling: ") + sw);
            }

            // Publish once, fully built.
            saved = new MixerSnapshot(originalValues, null);
            Log.d(TAG, "MediaTek mixer setup complete");
        }
    }

    @Override
    public void enforceMixer(int card) {
        // Re-assert desired targets only; do NOT read/save originals here, and do
        // NOT take mixerLock - this runs every 2s on the MixerEnforce thread.
        for (String sw : ENABLE_SWITCHES) {
            mixer.setValue(card, sw, 1);
        }
        for (String sw : DISABLE_SWITCHES) {
            mixer.setValue(card, sw, 0);
        }
    }

    @Override
    public void teardownMixer(int card) {
        synchronized (mixerLock) {
            // Read-then-null, then restore from the detached snapshot.
            MixerSnapshot snapshot = saved;
            saved = null;
            if (snapshot == null) {
                Log.d(TAG, "teardownMixer(): nothing saved - already torn down, ignoring");
                return;
            }

            Log.d(TAG, "Restoring PCM_2 routing...");
            restoreSaved(card, snapshot);
        }
    }

    /**
     * Write every saved switch back, in the order it was saved.
     * Caller holds {@link #mixerLock}.
     */
    private void restoreSaved(int card, MixerSnapshot snapshot) {
        for (Map.Entry<String, Integer> e : snapshot.values().entrySet()) {
            mixer.setValue(card, e.getKey(), e.getValue());
            Log.d(TAG, "Restored: " + e.getKey() + " = " + e.getValue());
        }
    }
}
