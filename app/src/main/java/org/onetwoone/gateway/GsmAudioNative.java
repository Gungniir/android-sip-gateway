package org.onetwoone.gateway;

import android.util.Log;

/**
 * Native tinyalsa integration for GSM audio.
 * Replaces tinycap/tinyplay processes with direct ALSA access.
 *
 * All parameters are configurable - no hardcoded device paths.
 */
public class GsmAudioNative {
    private static final String TAG = "GsmAudioNative";

    static {
        try {
            System.loadLibrary("gsm_audio");
            Log.i(TAG, "Loaded libgsm_audio.so");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load libgsm_audio.so", e);
        }
    }

    /**
     * Open audio devices for capture and playback.
     *
     * @param card          Sound card number (usually 0)
     * @param captureDevice PCM device for capture (VOC_REC, e.g. 0)
     * @param playbackDevice PCM device for playback (Incall_Music, e.g. 1)
     * @param sampleRate    Sample rate in Hz (8000 for GSM)
     * @param channels      Number of channels (1 for mono)
     * @param bits          Bits per sample (16)
     * @param periodSize    Period size in frames (160 for 20ms @ 8kHz)
     * @param periodCount   Number of periods (4)
     * @return true on success
     */
    public static native boolean open(int card, int captureDevice, int playbackDevice,
                                       int captureRate, int captureChannels,
                                       int playbackRate, int playbackChannels,
                                       int bits, int capturePeriod, int playbackPeriod,
                                       int periodCount);

    /**
     * Close audio devices.
     *
     * <p>Safe to call while the pjmedia RT thread is inside {@link #readFrame} /
     * {@link #writeFrame}: the native side stops both PCMs and then waits (bounded at
     * 250&nbsp;ms) for the in-flight I/O to return before it frees anything. Because of
     * that drain, this call <b>blocks for up to ~250&nbsp;ms</b> - never call it from the
     * RT thread, and prefer not to call it on the main thread.
     */
    public static native void close();

    /**
     * Read audio frame from capture device (GSM -> SIP direction).
     *
     * <p>Blocks for up to one ALSA period. Safe (rather than crashing) if {@link #close}
     * has run or runs concurrently: the open check happens under the native lock, inside
     * the same critical section that takes the in-flight I/O reference. Callers must NOT
     * pre-check {@link #isOpen()} - that is a third acquisition of the same mutex per
     * frame and it decides nothing this call does not decide (AUDIT H2b).
     *
     * @param buffer Byte array to fill with PCM data
     * @return Number of bytes read; {@code 0} if the device is closed, which is the
     *         ordinary race at end of call and <b>not</b> an error; {@code -1} if the
     *         ALSA read itself failed
     */
    public static native int readFrame(byte[] buffer);

    /**
     * Write audio frame to playback device (SIP -> GSM direction).
     *
     * <p>Same contract as {@link #readFrame}: no {@link #isOpen()} pre-check, and a
     * closed device is reported as {@code 0}, not as a failure.
     *
     * <p>{@code length} is explicit and mandatory. The caller's buffer is sized for the
     * largest frame the port can carry, and pjmedia is allowed to deliver a shorter one;
     * this method used to take the whole array, so a short frame pushed the untouched
     * tail of the <em>previous</em> frame back out to the modem (AUDIT H2e).
     *
     * @param buffer Byte array with PCM data
     * @param length How many bytes of {@code buffer} belong to this frame
     * @return Number of bytes accepted; {@code 0} if the device is closed; {@code -1} if
     *         the ALSA write failed or {@code length} is out of range
     */
    public static native int writeFrame(byte[] buffer, int length);

    // ============ pjsua2 ByteVector bulk access (AUDIT H2) ============
    //
    // These three move a whole audio frame between a byte[] and a pjsua2 ByteVector with
    // one memcpy instead of ~320 SWIG per-element calls and ~160 Short allocations. They
    // are the reason GsmAudioPort's RT callbacks no longer loop.
    //
    // The `handle` is a raw C++ address obtained from
    // org.pjsip.pjsua2.PjByteVectorAccess — read that class and the matching block in
    // gsm_audio_jni.c before touching any of this. It is an ABI dependency on libc++'s
    // std::vector layout, deliberately taken, verified against the vendored
    // libpjsua2.so, and re-checked at runtime by GsmAudioPort's constructor.
    //
    // Passing a handle that is not a live pj::ByteVector is undefined behaviour. Never
    // cache one across callbacks: pjmedia's MediaFrame is stack-allocated per frame.

    /**
     * Elements currently held by the vector at {@code handle}, or -1 if it does not look
     * like a live {@code std::vector<unsigned char>}.
     */
    static native int pjBufSize(long handle);

    /**
     * Bulk-copy {@code length} bytes out of the vector at {@code handle} into {@code dst}.
     *
     * @return {@code length} on success, -1 if the vector is shorter than {@code length},
     *         the array is too small, or the handle is not usable
     */
    static native int pjBufRead(long handle, byte[] dst, int length);

    /**
     * Bulk-copy {@code length} bytes from {@code src} into the vector at {@code handle},
     * in place.
     *
     * <p>The vector must already be exactly {@code length} elements long: this fills
     * existing storage and never grows it, which is what keeps the vector's control block
     * (and therefore its allocator) out of native hands.
     *
     * @return {@code length} on success, -1 on any size mismatch or unusable handle
     */
    static native int pjBufWrite(long handle, byte[] src, int length);

    /**
     * Set mixer control value.
     *
     * @param card        Sound card number
     * @param controlName Mixer control name (e.g. "MultiMedia1 Mixer VOC_REC_DL")
     * @param value       Value to set (0 or 1 for switches)
     * @return true on success
     */
    public static native boolean setMixerControl(int card, String controlName, int value);

    /**
     * Get a mixer control's current integer value (value index 0).
     *
     * @param card        Sound card number
     * @param controlName Mixer control name
     * @return the control value, or -1 if the control is missing / on error
     */
    public static native int getMixerControl(int card, String controlName);

    /**
     * Get a mixer control's current ENUM value as its item name.
     *
     * The counterpart of {@link #setMixerControlEnum}. Added for
     * {@link DeviceMuteManager}, which previously read originals by shelling out to
     * {@code tinymix} — see AUDIT B1c.
     *
     * @param card        Sound card number
     * @param controlName Mixer control name (e.g. "DEC1 MUX")
     * @return the current item name, or {@code null} if the control is missing, is not an
     *         ENUM, or cannot be read. {@code null} rather than {@code ""} so callers can
     *         distinguish "unreadable" from an empty item name.
     */
    public static native String getMixerControlEnum(int card, String controlName);

    /**
     * Set mixer control ENUM value by string.
     *
     * @param card        Sound card number
     * @param controlName Mixer control name (e.g. "DEC1 MUX")
     * @param value       String value to set (e.g. "ZERO", "ADC1", "ADC2")
     * @return true on success
     */
    public static native boolean setMixerControlEnum(int card, String controlName, String value);

    /**
     * Get list of mixer controls for device discovery.
     *
     * @param card Sound card number
     * @return Array of control names, or null on error
     */
    public static native String[] getMixerControls(int card);

    /**
     * Check if audio is open.
     *
     * <p>Advisory only - the device may be closed the instant after this returns, so it
     * can never make anything safe; {@link #readFrame} / {@link #writeFrame} re-check
     * under the native lock and refuse instead of touching a freed PCM.
     *
     * <p><b>Do not call this on the per-frame path.</b> It takes the same native mutex
     * {@code readFrame}/{@code writeFrame} already take, so at 50 frames/s in two
     * directions it was 100 extra acquisitions per second buying nothing (AUDIT H2b).
     */
    public static native boolean isOpen();

    /**
     * Get frame size in bytes.
     */
    public static native int getFrameSize();

    /**
     * Get list of PCM devices for a card.
     *
     * @param card Sound card number
     * @param isCapture true for capture devices, false for playback
     * @return Array of device descriptions ("device_num: name")
     */
    public static native String[] getPcmDevices(int card, boolean isCapture);

    /**
     * Get number of sound cards in the system.
     */
    public static native int getCardCount();

    // ============ Helper methods ============

    /**
     * Configure mixer for Qualcomm VOC_REC capture.
     * Call this before starting capture.
     * Note: Only DL (downlink) is enabled. UL captures Incall_Music and causes echo!
     */
    public static boolean setupQualcommCapture(int card, String multimediaRoute) {
        // Enable VOC_REC downlink only (UL would capture Incall_Music causing echo)
        return setMixerControl(card, multimediaRoute + " Mixer VOC_REC_DL", 1);
    }

    /**
     * Configure mixer for Qualcomm Incall_Music playback.
     * Call this before starting playback.
     * Enables both Incall_Music (SIM1) and Incall_Music_2 (SIM2).
     */
    public static boolean setupQualcommPlayback(int card, String multimediaRoute) {
        // Enable Incall_Music injection for both SIM slots
        boolean ok = setMixerControl(card, "Incall_Music Audio Mixer " + multimediaRoute, 1);
        setMixerControl(card, "Incall_Music_2 Audio Mixer " + multimediaRoute, 1);
        return ok;
    }

    /**
     * Disable mixer routes.
     */
    public static boolean teardownQualcommMixer(int card, String multimediaRoute) {
        boolean ok = true;
        ok &= setMixerControl(card, multimediaRoute + " Mixer VOC_REC_DL", 0);
        // VOC_REC_UL not used (causes echo)
        ok &= setMixerControl(card, "Incall_Music Audio Mixer " + multimediaRoute, 0);
        setMixerControl(card, "Incall_Music_2 Audio Mixer " + multimediaRoute, 0);
        return ok;
    }

    /**
     * Log all available mixer controls (useful for debugging on new devices).
     */
    public static void logMixerControls(int card) {
        String[] controls = getMixerControls(card);
        if (controls == null) {
            Log.e(TAG, "Failed to get mixer controls for card " + card);
            return;
        }
        Log.i(TAG, "=== Mixer controls for card " + card + " (" + controls.length + " total) ===");
        for (int i = 0; i < controls.length; i++) {
            // Only log interesting controls
            String c = controls[i];
            if (c.contains("VOC") || c.contains("Incall") || c.contains("MultiMedia") ||
                c.contains("Voice") || c.contains("PCM")) {
                Log.i(TAG, "  [" + i + "] " + c);
            }
        }
    }
}
