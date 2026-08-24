package org.onetwoone.gateway.audio;

import org.onetwoone.gateway.GsmAudioNative;

/**
 * The handful of ALSA mixer operations an {@link AudioProfile} needs.
 *
 * This exists as a test seam, nothing more: the profiles' saved-state handling is
 * what has to be provably correct under concurrency (AUDIT B2), and that can only
 * be exercised on the JVM, where there is no sound card, no JNI library and no
 * root shell. Production code — both profiles — uses {@link #NATIVE}.
 *
 * Deliberately not a general-purpose mixer abstraction — do not grow it. Anything
 * that is not needed by both call sites belongs in the profile that needs it.
 */
public interface MixerControls {

    /**
     * Set an INT/BOOL control.
     *
     * @return true if the write succeeded
     */
    boolean setValue(int card, String control, int value);

    /**
     * Set an ENUM control to one of its item names.
     *
     * @return true if the write succeeded
     */
    boolean setEnum(int card, String control, String value);

    /**
     * Read an INT/BOOL control.
     *
     * @return the current value, or {@code fallback} if the control is missing or
     *         unreadable (e.g. before the ALSA permissions are applied)
     */
    int getValue(int card, String control, int fallback);

    /**
     * Read an ENUM control.
     *
     * @return the current item name, or "" if the control is missing or unreadable
     */
    String getEnum(int card, String control);

    /**
     * Production backend: every read and every write goes through the tinyalsa
     * JNI bridge. Both profiles use it.
     *
     * <p>{@link #getEnum} used to return "" unconditionally, on the stated grounds
     * that "the native bridge exposes no ENUM getter" and that Qualcomm supplied
     * its own tinymix-based reader. Both were false by the time it mattered:
     * {@link GsmAudioNative#getMixerControlEnum} was added for
     * {@code DeviceMuteManager} (AUDIT B1c) and has been in production use since,
     * and Qualcomm's "own reader" exec'd a {@code tinymix} binary that does not
     * exist on the device — see AUDIT <b>B1e</b>, which this closes.
     */
    MixerControls NATIVE = new MixerControls() {
        @Override
        public boolean setValue(int card, String control, int value) {
            return GsmAudioNative.setMixerControl(card, control, value);
        }

        @Override
        public boolean setEnum(int card, String control, String value) {
            return GsmAudioNative.setMixerControlEnum(card, control, value);
        }

        @Override
        public int getValue(int card, String control, int fallback) {
            int v = GsmAudioNative.getMixerControl(card, control);
            return v < 0 ? fallback : v;
        }

        @Override
        public String getEnum(int card, String control) {
            // Native returns null for missing / non-ENUM / unreadable; this interface's
            // contract is "" for the same, so callers keep using isEmpty() as the
            // readable test. Same mapping as DeviceMuteManager.NATIVE.
            String value = GsmAudioNative.getMixerControlEnum(card, control);
            return value == null ? "" : value;
        }
    };
}
