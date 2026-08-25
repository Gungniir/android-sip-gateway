package org.onetwoone.gateway;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure frame-arithmetic and level-meter tests for {@link GsmAudioPort} (GW-23a).
 *
 * <p>A {@code GsmAudioPort} instance cannot exist in a JVM test: its superclass
 * constructor calls into {@code libpjsua2.so}. Every method under test is therefore
 * {@code static} and free of any pjsua2 state, which is exactly why they were extracted -
 * they encode the numbers the RT path gets wrong when they are wrong.
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

    // ---- recordPeak / closeWindow -----------------------------------------------------
    //
    // The level meter exists because a two-way-silent gateway call and a working one were
    // captured back to back on lavender and were identical in every other signal: same
    // conference links, same mixer state sampled live during both, same PCM open, both
    // frame counters ticking at 50 fps for the whole bridge lifetime. Whether the bytes
    // were non-zero was the one quantity nothing measured.

    /** Little-endian signed 16-bit, so the high byte is the second one. */
    private static byte[] pcm(int... samples) {
        byte[] out = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            out[i * 2] = (byte) (samples[i] & 0xFF);
            out[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
        }
        return out;
    }

    /** Digital silence must read as exactly zero - that is the whole diagnostic. */
    @Test
    public void silenceMeasuresZero() {
        AtomicInteger window = new AtomicInteger();
        GsmAudioPort.recordPeak(window, new byte[320], 320);
        assertEquals(0, window.get());
    }

    /** Positive and negative full scale, and the sign handling between them. */
    @Test
    public void peakIsTheLargestMagnitude() {
        AtomicInteger window = new AtomicInteger();
        GsmAudioPort.recordPeak(window, pcm(0, 1000, -32768, 5), 8);
        assertEquals(32768, window.get());

        window.set(0);
        GsmAudioPort.recordPeak(window, pcm(32767, -3), 4);
        assertEquals(32767, window.get());

        window.set(0);
        GsmAudioPort.recordPeak(window, pcm(-2000, 1500), 4);
        assertEquals(2000, window.get());
    }

    /**
     * Byte order is not incidental: reading these big-endian would turn a quiet frame into
     * a loud one and hide the very failure the meter is for.
     */
    @Test
    public void samplesAreDecodedLittleEndian() {
        AtomicInteger window = new AtomicInteger();
        GsmAudioPort.recordPeak(window, new byte[] {(byte) 0xFF, (byte) 0x7F}, 2);
        assertEquals(32767, window.get());

        window.set(0);
        GsmAudioPort.recordPeak(window, new byte[] {(byte) 0x00, (byte) 0x80}, 2);
        assertEquals(32768, window.get());
    }

    /**
     * The AUDIT H2e shape again: both buffers are reused and sized for a full frame, so a
     * short frame must not be measured against the loud tail the previous frame left
     * behind - that would report audio on a leg that had gone silent.
     */
    @Test
    public void onlyTheFramesOwnBytesAreMeasured() {
        byte[] reused = pcm(10, 20, 30000, 30000);
        AtomicInteger window = new AtomicInteger();
        GsmAudioPort.recordPeak(window, reused, 4);
        assertEquals(20, window.get());
    }

    /** A trailing odd byte cannot form a sample and must not run off the end. */
    @Test
    public void oddTrailingByteIsIgnored() {
        AtomicInteger window = new AtomicInteger();
        GsmAudioPort.recordPeak(window, pcm(700, 900), 3);
        assertEquals(700, window.get());
    }

    /** The window is a running maximum across the frames of one logging interval. */
    @Test
    public void windowAccumulatesAcrossFrames() {
        AtomicInteger window = new AtomicInteger();
        GsmAudioPort.recordPeak(window, pcm(400), 2);
        GsmAudioPort.recordPeak(window, pcm(90), 2);
        assertEquals(400, window.get());
    }

    /** Closing hands back the window, clears it, and raises the session high-water mark. */
    @Test
    public void closeWindowReportsAndResets() {
        AtomicInteger window = new AtomicInteger(1234);
        AtomicInteger session = new AtomicInteger();

        assertEquals(1234, GsmAudioPort.closeWindow(window, session));
        assertEquals(0, window.get());
        assertEquals(1234, session.get());
    }

    /**
     * A quiet window after a loud one must not lower the session peak - otherwise a call
     * that carried audio and then went silent would report zero at teardown and read as
     * "never worked".
     */
    @Test
    public void sessionPeakIsAHighWaterMark() {
        AtomicInteger window = new AtomicInteger();
        AtomicInteger session = new AtomicInteger();

        window.set(9000);
        GsmAudioPort.closeWindow(window, session);
        window.set(12);
        assertEquals(12, GsmAudioPort.closeWindow(window, session));
        assertEquals(9000, session.get());
    }

    /** Closing an untouched window is a no-op, not a spurious zero written over history. */
    @Test
    public void closingAnEmptyWindowLeavesTheSessionAlone() {
        AtomicInteger window = new AtomicInteger();
        AtomicInteger session = new AtomicInteger(555);

        assertEquals(0, GsmAudioPort.closeWindow(window, session));
        assertEquals(555, session.get());
    }
}
