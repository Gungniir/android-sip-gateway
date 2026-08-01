package org.onetwoone.gateway.call;

import android.os.Handler;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * Unit tests for GsmDtmfSender.
 * Covers digit filtering and the play/stop pacing of the tone queue.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class GsmDtmfSenderTest {

    /** Records what the Telecom layer would have been asked to do. */
    private static class FakeTarget implements GsmDtmfSender.Target {
        final List<String> events = new ArrayList<>();
        boolean accept = true;

        @Override
        public boolean playTone(char digit) {
            events.add("play:" + digit);
            return accept;
        }

        @Override
        public void stopTone() {
            events.add("stop");
        }
    }

    private FakeTarget target;
    private GsmDtmfSender sender;
    private ShadowLooper looper;

    @Before
    public void setUp() {
        target = new FakeTarget();
        sender = new GsmDtmfSender(new Handler(Looper.getMainLooper()), target);
        looper = shadowOf(Looper.getMainLooper());
    }

    private static List<String> listOf(String... items) {
        return Arrays.asList(items);
    }

    /** Run everything that is due now plus anything scheduled in the next `ms`. */
    private void advance(long ms) {
        looper.idleFor(Duration.ofMillis(ms));
    }

    @Test
    public void testDigitsArePlayedInOrderWithAGapBetweenThem() {
        sender.enqueue("1");
        sender.enqueue("2");
        looper.idle();

        // First digit starts immediately, and stays on until the tone duration elapses.
        assertEquals(listOf("play:1"), target.events);

        advance(199);
        assertEquals("tone must be held, not pulsed", listOf("play:1"), target.events);

        advance(1);
        assertEquals(listOf("play:1", "stop"), target.events);

        // Gap before the next digit, otherwise the far end hears one long tone.
        advance(99);
        assertEquals(listOf("play:1", "stop"), target.events);

        advance(1);
        assertEquals(listOf("play:1", "stop", "play:2"), target.events);

        advance(200);
        assertEquals(listOf("play:1", "stop", "play:2", "stop"), target.events);
    }

    @Test
    public void testMultiDigitStringIsQueued() {
        sender.enqueue("*21#");
        advance(2000);

        assertEquals(listOf(
                "play:*", "stop",
                "play:2", "stop",
                "play:1", "stop",
                "play:#", "stop"), target.events);
    }

    @Test
    public void testUnsupportedCharactersAreDropped() {
        sender.enqueue("1x 2");
        advance(2000);

        assertEquals(listOf("play:1", "stop", "play:2", "stop"), target.events);
    }

    @Test
    public void testLetterDigitsAreUppercased() {
        sender.enqueue("a");
        advance(1000);

        assertEquals(listOf("play:A", "stop"), target.events);
    }

    @Test
    public void testQueueIsDroppedWhenThereIsNoGsmCall() {
        target.accept = false;
        sender.enqueue("123");
        advance(2000);

        // One attempt, then the rest is discarded rather than replayed later.
        assertEquals(listOf("play:1"), target.events);
    }

    @Test
    public void testClearStopsActiveToneAndDropsPending() {
        sender.enqueue("123");
        looper.idle();
        assertEquals(listOf("play:1"), target.events);

        sender.clear();
        looper.idle();
        assertEquals(listOf("play:1", "stop"), target.events);

        advance(2000);
        assertEquals("nothing may play after clear", listOf("play:1", "stop"), target.events);
    }

    @Test
    public void testDigitsEnqueuedMidPlaybackAreAppended() {
        sender.enqueue("1");
        looper.idle();

        // Arrives while digit 1 is still sounding.
        advance(100);
        sender.enqueue("2");
        advance(1000);

        assertEquals(listOf("play:1", "stop", "play:2", "stop"), target.events);
    }

    @Test
    public void testSenderIsIdleAfterQueueDrains() {
        sender.enqueue("1");
        advance(1000);
        assertEquals(listOf("play:1", "stop"), target.events);

        sender.enqueue("2");
        looper.idle();
        assertEquals(listOf("play:1", "stop", "play:2"), target.events);
    }
}
