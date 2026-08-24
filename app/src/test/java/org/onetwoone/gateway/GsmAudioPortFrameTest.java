package org.onetwoone.gateway;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Pure frame-arithmetic tests for {@link GsmAudioPort} (GW-23a).
 *
 * <p>A {@code GsmAudioPort} instance cannot exist in a JVM test: its superclass
 * constructor calls into {@code libpjsua2.so}. Both methods under test are therefore
 * {@code static} and free of any pjsua2 state, which is exactly why they were extracted -
 * they encode the two numbers the RT path gets wrong when they are wrong.
 */
public class GsmAudioPortFrameTest {

    // ---- frameSizeBytes ---------------------------------------------------------------

    /**
     * The number the GW-23 brief got wrong. A 20 ms frame of 8 kHz 16-bit mono is 160
     * <em>samples</em> but <b>320 bytes</b>, and {@code frameSize} is declared in bytes -
     * so the per-element JNI loops ran 320 times per frame per direction, and the real
     * transition rate was ~32 500/s rather than the 16 000/s the brief quoted.
     */
    @Test
    public void gsmPortFrameIs320Bytes() {
        assertEquals(320, GsmAudioPort.frameSizeBytes(8000, 16, 1));
    }

    /** The MediaTek playback rate: what a 20 ms frame becomes after upsampling. */
    @Test
    public void frameSizeScalesWithRateChannelsAndDepth() {
        assertEquals(1920, GsmAudioPort.frameSizeBytes(48000, 16, 1));
        assertEquals(640, GsmAudioPort.frameSizeBytes(8000, 16, 2));
        assertEquals(160, GsmAudioPort.frameSizeBytes(8000, 8, 1));
    }

    /**
     * The resampler scratch is sized {@code playbackRate / 50 * playbackChannels} samples
     * in native {@code open()}; that must cover what one frame can produce. 960 samples
     * for 8 k -> 48 k mono, i.e. exactly the 1920-byte playback frame above.
     */
    @Test
    public void upsampledFrameFitsThePreallocatedScratch() {
        int inSamples = GsmAudioPort.frameSizeBytes(8000, 16, 1) / 2;
        int outSamples = inSamples * 48000 / 8000;
        assertEquals(960, outSamples);
        assertEquals(48000 / 50 * 1, outSamples);
    }

    // ---- usableFrameBytes (AUDIT H2e) -------------------------------------------------

    /**
     * The bug this closes: {@code onFrameReceived} accepted any frame up to the buffer
     * size but then handed the <em>whole</em> reusable buffer to {@code writeFrame}. A
     * short frame therefore pushed the tail of the previous frame out to the modem. The
     * length must come from the frame, not from the array.
     */
    @Test
    public void shortFrameReportsItsOwnLengthNotTheBufferSize() {
        assertEquals(96, GsmAudioPort.usableFrameBytes(96, 320));
    }

    @Test
    public void fullFrameIsAccepted() {
        assertEquals(320, GsmAudioPort.usableFrameBytes(320, 320));
    }

    /** Empty and negative sizes are dropped, not written as a zero-length ALSA write. */
    @Test
    public void emptyFrameIsDropped() {
        assertEquals(0, GsmAudioPort.usableFrameBytes(0, 320));
        assertEquals(0, GsmAudioPort.usableFrameBytes(-1, 320));
    }

    /**
     * An oversized frame means the negotiated format changed under us. Dropping it is the
     * pre-existing behaviour and is preserved deliberately - clamping would feed the modem
     * a truncated frame of a format it is not expecting.
     */
    @Test
    public void oversizedFrameIsDropped() {
        assertEquals(0, GsmAudioPort.usableFrameBytes(321, 320));
        assertEquals(0, GsmAudioPort.usableFrameBytes(Integer.MAX_VALUE + 1L, 320));
    }
}
