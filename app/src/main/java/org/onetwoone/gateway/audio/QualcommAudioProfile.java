package org.onetwoone.gateway.audio;

import android.content.Context;
import android.util.Log;

import org.onetwoone.gateway.config.GatewayConfig;

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

    /**
     * Passed as {@code getValue}'s fallback so that "unreadable" comes back as a value no
     * control can legitimately hold, instead of a plausible-looking one.
     *
     * <p>It used to be {@code VOLUME_READ_FALLBACK = 84} — a hardcoded guess at the resting
     * value of {@code DEC* Volume} — and because the read path was dead (AUDIT <b>B1e</b>:
     * it shelled out to a {@code tinymix} that is not installed), <em>every</em> saved
     * "original" was that guess, with nothing ever read. It is also the wrong guess: the
     * measured resting value on lavender is <b>0</b>, so teardown wrote a value that was
     * wrong rather than merely unverified. Only B1d's kernel refusal kept it from doing
     * visible damage.
     */
    private static final int VALUE_UNREADABLE = -1;

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
        // context unused since the tinymix reader went away (B1e); kept for signature
        // symmetry with MediaTekAudioProfile and AudioProfileFactory.
        this.captureDevice = config.getCaptureDevice();
        this.playbackDevice = config.getPlaybackDevice();
        this.multimediaRoute = config.getMultimediaRoute();
        this.micMuteControls =
                Collections.unmodifiableList(new ArrayList<>(config.getAllMuteControls()));
        // Reads and writes both go through the tinyalsa JNI bridge. They used to split:
        // writes native, reads through a private TinymixControls that shelled out to a
        // `tinymix` binary present on neither test device (AUDIT B1e).
        this.mixer = mixer != null ? mixer : MixerControls.NATIVE;
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
            //
            // A control whose original cannot be read is left alone. Muting something that
            // cannot be restored is exactly how AUDIT B1c bricked the microphone, and it is
            // the policy DeviceMuteManager already applies (its muteInt/muteEnum are called
            // with requireRead=true everywhere). Skipping it costs at worst some local mic
            // bleed onto the GSM leg for one call; the alternative costs the mic until
            // reboot.
            //
            // enforceMixer() still re-asserts the whole static list every 2 s and does not
            // know about this skip - by contract it reads only the static control lists.
            // That is sound here because the two failure modes coincide: the native getter
            // fails when the mixer cannot be opened or the control does not exist, and in
            // both of those cases the corresponding native *write* fails too, so enforce's
            // re-assert is a logged no-op rather than an unrestorable mute.
            Map<String, Integer> originalValues = new LinkedHashMap<>();
            Map<String, String> originalEnumValues = new LinkedHashMap<>();
            for (String decControl : micMuteControls) {
                if (decControl.contains(" Volume")) {
                    int originalValue = mixer.getValue(card, decControl, VALUE_UNREADABLE);
                    if (originalValue < 0) {
                        Log.w(TAG, "Not muting '" + decControl + "': its current value could "
                                + "not be read, so it could not be restored");
                        continue;
                    }
                    originalValues.put(decControl, originalValue);
                    mixer.setValue(card, decControl, 0);
                    Log.d(TAG, "Muted: " + decControl + " = 0 (original=" + originalValue + ")");
                } else if (decControl.contains(" MUX")) {
                    String originalValue = mixer.getEnum(card, decControl);
                    if (originalValue == null || originalValue.isEmpty()) {
                        Log.w(TAG, "Not muting '" + decControl + "': its current value could "
                                + "not be read, so it could not be restored");
                        continue;
                    }
                    originalEnumValues.put(decControl, originalValue);
                    mixer.setEnum(card, decControl, "ZERO");
                    Log.d(TAG, "Muted: " + decControl + " = ZERO (original=" + originalValue + ")");
                } else if (decControl.equals("EAR_S") || decControl.equals("SPK")) {
                    String originalValue = mixer.getEnum(card, decControl);
                    if (originalValue == null || originalValue.isEmpty()) {
                        Log.w(TAG, "Not muting speaker '" + decControl + "': its current value "
                                + "could not be read, so it could not be restored");
                        continue;
                    }
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

    // The private TinymixControls backend that used to live here is gone (AUDIT B1e).
    // It is not "dead code GW-31 will sweep" - it is the defect: its getValue shelled out
    // to `su -c 'tinymix -D 0 get "DEC1 Volume"'` on devices with no tinymix at all
    // (exit 127, stdout empty, readLine() null, fallback returned), and its getEnum exec'd
    // filesDir/tinymix, which QualcommAudioProfile never extracted - only the UI-path
    // ui/TinymixManager does. Both readers were dead, neither drained stderr, and both
    // p.waitFor() calls were unbounded. Everything they did is now one JNI ioctl through
    // MixerControls.NATIVE.
}
