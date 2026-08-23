package org.onetwoone.gateway.audio;

import org.onetwoone.gateway.GsmAudioNative;

/**
 * The handful of ALSA mixer operations an {@link AudioProfile} needs.
 *
 * This exists as a test seam, nothing more: the profiles' saved-state handling is
 * what has to be provably correct under concurrency (AUDIT B2), and that can only
 * be exercised on the JVM, where there is no sound card, no JNI library and no
 * root shell. Production code uses {@link #NATIVE} (or, on Qualcomm, a
 * tinymix-backed reader the profile builds for itself).
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
     * Production backend: the tinyalsa JNI bridge.
     *
     * {@link #getEnum} always returns "" — the native bridge exposes no ENUM
     * getter. The only profile that reads ENUM controls (Qualcomm) supplies its
     * own tinymix-based reader.
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
            return "";
        }
    };
}
