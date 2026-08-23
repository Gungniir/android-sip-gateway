package org.onetwoone.gateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Handler;
import android.os.Looper;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mute-lease tests for {@link DeviceMuteManager} — AUDIT B1 / G3, GW-02.
 *
 * <p>The bug these guard against: the mute ran on a throwaway thread and the unmute ran
 * synchronously on main. A call that ended inside the mute thread's scheduling latency
 * unmuted first (saw nothing muted, returned) and then muted — leaving the phone with no
 * microphone and no earpiece until it was rebooted.
 *
 * <p>{@link #releaseMidAcquireRestoresEveryControl()} is the regression test: it pins the
 * release to land after exactly {@code K} control writes and requires every control to be
 * back at its original value afterwards. {@link #releaseBeforeAcquireStartsMutesNothing()}
 * covers the original interleaving — the release wins the race outright, so not one control
 * may be written.
 *
 * <p>The {@code MuteControls} {@link android.os.HandlerThread} runs for real under
 * Robolectric, so the ordering here is the production ordering, pinned with explicit gates
 * rather than by idling a paused looper.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class DeviceMuteManagerTest {

    private static final long TIMEOUT_S = 10L;
    private static final int CARD = 0;

    /**
     * The redmi_note_7 write sequence, extracted mechanically from the pre-GW-02 source
     * (the {@code PRESETS} table plus the speaker → mic-volume → mic-routing loop order).
     * Any change to a control name, a written value or the order breaks this test.
     */
    private static final String[] EXPECTED_REDMI_NOTE_7_WRITES = {
        "setEnum EAR_S=ZERO",
        "setEnum SPK=ZERO",
        "setValue DEC1 Volume=0",
        "setValue DEC2 Volume=0",
        "setValue DEC3 Volume=0",
        "setValue DEC4 Volume=0",
        "setValue DEC5 Volume=0",
        "setEnum DEC1 MUX=ZERO",
        "setEnum DEC2 MUX=ZERO",
        "setEnum DEC3 MUX=ZERO",
        "setEnum DEC4 MUX=ZERO",
        "setEnum DEC5 MUX=ZERO",
    };

    private static final String[] EXPECTED_GENERIC_WRITES = {
        "setEnum EAR_S=ZERO",
        "setEnum SPK=ZERO",
        "setValue DEC1 Volume=0",
        "setValue DEC2 Volume=0",
        "setValue DEC3 Volume=0",
        "setValue DEC4 Volume=0",
        "setEnum DEC1 MUX=ZERO",
        "setEnum DEC2 MUX=ZERO",
        "setEnum DEC3 MUX=ZERO",
        "setEnum DEC4 MUX=ZERO",
    };

    /** The values the mixer starts at — anything else at the end of a test is a failure. */
    private static final Map<String, String> ORIGINAL_ENUMS = new LinkedHashMap<>();
    private static final Map<String, Integer> ORIGINAL_VALUES = new LinkedHashMap<>();

    static {
        ORIGINAL_ENUMS.put("EAR_S", "SWITCH");
        ORIGINAL_ENUMS.put("SPK", "SWITCH");
        ORIGINAL_ENUMS.put("DEC1 MUX", "ADC1");
        ORIGINAL_ENUMS.put("DEC2 MUX", "ADC2");
        ORIGINAL_ENUMS.put("DEC3 MUX", "ADC3");
        ORIGINAL_ENUMS.put("DEC4 MUX", "DMIC1");
        ORIGINAL_ENUMS.put("DEC5 MUX", "DMIC2");
        ORIGINAL_VALUES.put("DEC1 Volume", 84);
        ORIGINAL_VALUES.put("DEC2 Volume", 80);
        ORIGINAL_VALUES.put("DEC3 Volume", 76);
        ORIGINAL_VALUES.put("DEC4 Volume", 72);
        ORIGINAL_VALUES.put("DEC5 Volume", 68);
    }

    /**
     * In-memory ALSA mixer. Thread-safe, so any surviving mute is the manager's fault.
     *
     * Optionally gates the Nth write, which is how a release is forced to land exactly
     * part-way through an acquire.
     */
    private static final class FakeMixer implements DeviceMuteManager.MixerBackend {
        final Map<String, String> enums = new ConcurrentHashMap<>();
        final Map<String, Integer> values = new ConcurrentHashMap<>();
        final List<String> writes = Collections.synchronizedList(new ArrayList<String>());
        final AtomicInteger writeCount = new AtomicInteger();
        /** Controls whose writes the 'kernel' refuses (AUDIT B1d). */
        final java.util.Set<String> refuse =
                Collections.synchronizedSet(new java.util.HashSet<String>());

        /** Writes this many controls, then blocks on {@link #gateReached}/{@link #gate}. */
        volatile int gateAfterWrites = -1;
        final CountDownLatch gateReached = new CountDownLatch(1);
        final CountDownLatch gate = new CountDownLatch(1);

        FakeMixer() {
            enums.putAll(ORIGINAL_ENUMS);
            values.putAll(ORIGINAL_VALUES);
        }

        private void onWrite() {
            int n = writeCount.incrementAndGet();
            if (gateAfterWrites >= 0 && n == gateAfterWrites) {
                gateReached.countDown();
                try {
                    gate.await(TIMEOUT_S, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public boolean setEnum(int card, String control, String value) {
            writes.add("setEnum " + control + "=" + value);
            enums.put(control, value);
            onWrite();
            return !refuse.contains(control);
        }

        @Override
        public boolean setValue(int card, String control, int value) {
            writes.add("setValue " + control + "=" + value);
            // A refused write leaves the control where it was - the kernel rejected it.
            if (refuse.contains(control)) {
                return false;
            }
            values.put(control, value);
            onWrite();
            return true;
        }

        @Override
        public String getEnum(int card, String control) {
            String v = enums.get(control);
            return v == null ? "" : v;
        }

        @Override
        public int getValue(int card, String control) {
            Integer v = values.get(control);
            return v == null ? -1 : v;
        }

        List<String> writeLog() {
            synchronized (writes) {
                return new ArrayList<>(writes);
            }
        }
    }

    private DeviceMuteManager manager;
    private FakeMixer mixer;

    private void start(String preset) {
        if (manager != null) {
            manager.quitForTest();
        }
        mixer = new FakeMixer();
        manager = DeviceMuteManager.forTesting(preset, CARD, mixer);
    }

    @After
    public void tearDown() {
        if (manager != null) {
            manager.quitForTest();
            manager = null;
        }
    }

    /** Blocks until the MuteControls thread has drained everything queued so far. */
    private void awaitMuteIdle() throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        Handler h = new Handler(manager.muteLooperForTest());
        assertTrue("mute looper rejected the barrier", h.post(new Runnable() {
            @Override
            public void run() {
                done.countDown();
            }
        }));
        assertTrue("MuteControls thread stalled", done.await(TIMEOUT_S, TimeUnit.SECONDS));
    }

    private void assertEverythingRestored() {
        for (Map.Entry<String, String> e : ORIGINAL_ENUMS.entrySet()) {
            assertEquals("ENUM " + e.getKey() + " left muted", e.getValue(), mixer.enums.get(e.getKey()));
        }
        for (Map.Entry<String, Integer> e : ORIGINAL_VALUES.entrySet()) {
            assertEquals("INT " + e.getKey() + " left muted", e.getValue(), mixer.values.get(e.getKey()));
        }
        assertFalse("manager still reports muted", manager.isMuted());
        assertEquals(DeviceMuteManager.NO_LEASE, manager.heldLease());
    }

    // ================================================================
    // The write sequence must not have drifted (GW-02 changes ordering
    // of nothing; only who runs it and when it can be cancelled).
    // ================================================================

    @Test
    public void presetWriteSequenceIsUnchanged() throws Exception {
        start(DeviceMuteManager.PRESET_REDMI_NOTE_7);
        long lease = manager.newLease();
        manager.acquire(lease);
        awaitMuteIdle();

        assertEquals(Arrays.asList(EXPECTED_REDMI_NOTE_7_WRITES), mixer.writeLog());
        assertTrue(manager.isMuted());

        start(DeviceMuteManager.PRESET_GENERIC);
        long lease2 = manager.newLease();
        manager.acquire(lease2);
        awaitMuteIdle();
        assertEquals(Arrays.asList(EXPECTED_GENERIC_WRITES), mixer.writeLog());
    }

    // ================================================================
    // Cancellation case 1: released before the acquire worker starts.
    // This is the exact interleaving of AUDIT B1 — the one that used to
    // brick the phone.
    // ================================================================

    @Test
    public void releaseBeforeAcquireStartsMutesNothing() throws Exception {
        start(DeviceMuteManager.PRESET_REDMI_NOTE_7);

        // Wedge the mute thread so the acquire worker cannot start, exactly as if the
        // MuteControls thread had not been scheduled yet.
        final CountDownLatch blocked = new CountDownLatch(1);
        final CountDownLatch letGo = new CountDownLatch(1);
        new Handler(manager.muteLooperForTest()).post(new Runnable() {
            @Override
            public void run() {
                blocked.countDown();
                try {
                    letGo.await(TIMEOUT_S, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        assertTrue(blocked.await(TIMEOUT_S, TimeUnit.SECONDS));

        final long lease = manager.newLease();
        manager.acquire(lease);

        // The hangup, from a second thread, while the acquire is still queued.
        Thread hangup = new Thread(new Runnable() {
            @Override
            public void run() {
                manager.release(lease);
            }
        }, "Hangup");
        hangup.start();
        hangup.join(TimeUnit.SECONDS.toMillis(TIMEOUT_S));

        letGo.countDown();
        awaitMuteIdle();

        assertEquals("a released lease must not write a single control",
                Collections.emptyList(), mixer.writeLog());
        assertEverythingRestored();
    }

    // ================================================================
    // Cancellation case 2: released mid-acquire, after K writes. The
    // delicate one — those K controls must come back from the snapshot
    // taken before the first write.
    // ================================================================

    @Test
    public void releaseMidAcquireRestoresEveryControl() throws Exception {
        for (int k = 1; k <= EXPECTED_REDMI_NOTE_7_WRITES.length; k++) {
            assertRestoredAfterReleaseAt(k);
        }
    }

    private void assertRestoredAfterReleaseAt(final int k) throws Exception {
        start(DeviceMuteManager.PRESET_REDMI_NOTE_7);
        mixer.gateAfterWrites = k;

        final long lease = manager.newLease();
        manager.acquire(lease);

        // Wait until exactly k controls have been muted and the worker is parked inside
        // the k-th write.
        assertTrue("acquire never reached write " + k,
                mixer.gateReached.await(TIMEOUT_S, TimeUnit.SECONDS));

        // ...then hang up, from a different thread, exactly as Telecom would.
        final CountDownLatch released = new CountDownLatch(1);
        Thread hangup = new Thread(new Runnable() {
            @Override
            public void run() {
                manager.release(lease);
                released.countDown();
            }
        }, "Hangup");
        hangup.start();
        assertTrue("release never returned", released.await(TIMEOUT_S, TimeUnit.SECONDS));

        mixer.gate.countDown();
        awaitMuteIdle();

        // The acquire had written exactly k controls when the release landed, so it may
        // write at most k more putting them back. Anything beyond that means the loop
        // carried on muting past the hangup.
        assertTrue("cancelled acquire kept going past write " + k + ": " + mixer.writeLog(),
                mixer.writeCount.get() <= 2 * k);
        assertEverythingRestored();
    }

    // ================================================================
    // Cancellation case 3: released after the acquire completed. The
    // ordinary path — a full mute, then a full restore.
    // ================================================================

    @Test
    public void releaseAfterAcquireCompletedRestoresEveryControl() throws Exception {
        start(DeviceMuteManager.PRESET_REDMI_NOTE_7);

        final long lease = manager.newLease();
        manager.acquire(lease);
        awaitMuteIdle();

        // Everything is muted at this point.
        assertTrue(manager.isMuted());
        assertEquals("ZERO", mixer.enums.get("EAR_S"));
        assertEquals(Integer.valueOf(0), mixer.values.get("DEC1 Volume"));

        manager.release(lease);
        awaitMuteIdle();

        assertEverythingRestored();
    }

    // ================================================================
    // Lease identity
    // ================================================================

    @Test
    public void staleReleaseIsANoOp() throws Exception {
        start(DeviceMuteManager.PRESET_REDMI_NOTE_7);

        long first = manager.newLease();
        manager.acquire(first);
        awaitMuteIdle();
        manager.release(first);
        awaitMuteIdle();
        assertEverythingRestored();

        long second = manager.newLease();
        manager.acquire(second);
        awaitMuteIdle();

        // A duplicate release for the call that already ended must not tear down the
        // mute the *current* call is holding.
        manager.release(first);
        awaitMuteIdle();

        assertTrue("stale release dropped the live lease", manager.isMuted());
        assertEquals(second, manager.heldLease());
        assertEquals("ZERO", mixer.enums.get("EAR_S"));

        manager.release(second);
        awaitMuteIdle();
        assertEverythingRestored();
    }

    @Test
    public void supersedingLeaseRestoresTheOldOneFirst() throws Exception {
        start(DeviceMuteManager.PRESET_REDMI_NOTE_7);

        long first = manager.newLease();
        manager.acquire(first);
        awaitMuteIdle();

        // A second call goes active without the first ever being released.
        long second = manager.newLease();
        manager.acquire(second);
        awaitMuteIdle();

        manager.release(second);
        awaitMuteIdle();

        // If the second lease had snapshotted the muted values, this would fail with
        // everything still at ZERO/0 — the brick.
        assertEverythingRestored();
    }

    // ================================================================
    // Fail-safe
    // ================================================================

    @Test
    public void leaseHeldPastTheDeadlineIsForceRestored() throws Exception {
        start(DeviceMuteManager.PRESET_REDMI_NOTE_7);
        manager.setMuteMaxHoldMsForTest(50L);

        long lease = manager.newLease();
        manager.acquire(lease);
        awaitMuteIdle();
        assertTrue(manager.isMuted());

        // Nobody ever releases this lease — the call is gone but its mute is still on.
        // Robolectric's clock is virtual, so push it past the deadline by hand; the barrier
        // post inside awaitMuteIdle() then wakes the worker, which finds the fail-safe due.
        ShadowSystemClock.advanceBy(Duration.ofMillis(500));
        awaitMuteIdle();

        assertEverythingRestored();
    }

    // ================================================================
    // No mixer I/O may ever run on main
    // ================================================================

    @Test
    public void everyWriteHappensOffTheMainThread() throws Exception {
        start(DeviceMuteManager.PRESET_REDMI_NOTE_7);

        final List<String> offenders = Collections.synchronizedList(new ArrayList<String>());
        DeviceMuteManager.MixerBackend watching = new DeviceMuteManager.MixerBackend() {
            private void check() {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    offenders.add(Thread.currentThread().getName());
                }
            }

            @Override public boolean setEnum(int c, String k, String v) { check(); return mixer.setEnum(c, k, v); }
            @Override public boolean setValue(int c, String k, int v) { check(); return mixer.setValue(c, k, v); }
            @Override public String getEnum(int c, String k) { check(); return mixer.getEnum(c, k); }
            @Override public int getValue(int c, String k) { check(); return mixer.getValue(c, k); }
        };
        manager = DeviceMuteManager.forTesting(DeviceMuteManager.PRESET_REDMI_NOTE_7, CARD, watching);

        long lease = manager.newLease();
        manager.acquire(lease);
        awaitMuteIdle();
        manager.release(lease);
        awaitMuteIdle();

        assertEquals("mixer touched from the main thread", Collections.emptyList(), offenders);
        assertEverythingRestored();
    }

    // ================================================================
    // Randomised soak: whatever the timing, nothing may be left muted.
    // ================================================================

    @Test
    public void hangupAtAnyMomentAlwaysEndsUnmuted() throws Exception {
        for (int i = 0; i < 200; i++) {
            start(DeviceMuteManager.PRESET_REDMI_NOTE_7);

            final long lease = manager.newLease();
            final CountDownLatch go = new CountDownLatch(1);

            Thread acquirer = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        go.await(TIMEOUT_S, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    manager.acquire(lease);
                }
            }, "Active");

            Thread releaser = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        go.await(TIMEOUT_S, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    manager.release(lease);
                }
            }, "Hangup");

            acquirer.start();
            releaser.start();
            go.countDown();
            acquirer.join(TimeUnit.SECONDS.toMillis(TIMEOUT_S));
            releaser.join(TimeUnit.SECONDS.toMillis(TIMEOUT_S));

            awaitMuteIdle();

            // Whichever thread won, the lease is retired and no control is left muted.
            // If the releaser overtakes the acquirer entirely, acquire must refuse the
            // lease outright rather than mute behind the hangup.
            assertEverythingRestored();
        }
    }

    /**
     * AUDIT B1c regression. On a device where the mixer reads fail — which is what the old
     * {@code tinymix ... get} shell-out did on every real device, because that subcommand
     * does not exist — the mute must write NOTHING.
     *
     * <p>Before the fix this wrote all twelve preset controls to their muted values while
     * recording zero originals, so {@code release()} restored nothing and the microphone
     * stayed dead until the phone was rebooted. Reproduced on lavender 2026-08-23:
     * {@code DEC1-5 Volume} left at {@code 0}, log line {@code Lease 8 muted 0 controls}.
     */
    @Test
    public void unreadableControlsAreNeverMuted() throws Exception {
        if (manager != null) {
            manager.quitForTest();
        }
        final List<String> writes = Collections.synchronizedList(new ArrayList<String>());
        DeviceMuteManager.MixerBackend blind = new DeviceMuteManager.MixerBackend() {
            @Override public boolean setEnum(int card, String control, String value) {
                writes.add("setEnum " + control + "=" + value);
                return true;
            }
            @Override public boolean setValue(int card, String control, int value) {
                writes.add("setValue " + control + "=" + value);
                return true;
            }
            // Both readers report failure, exactly as the broken tinymix path did.
            @Override public String getEnum(int card, String control) { return ""; }
            @Override public int getValue(int card, String control) { return -1; }
        };
        manager = DeviceMuteManager.forTesting(
                DeviceMuteManager.PRESET_REDMI_NOTE_7, CARD, blind);

        long lease = manager.newLease();
        manager.acquire(lease);
        awaitMuteIdle();

        assertEquals("a control that cannot be read must not be muted",
                Collections.<String>emptyList(), writes);

        // And releasing a lease that touched nothing must also touch nothing.
        manager.release(lease);
        awaitMuteIdle();
        assertEquals(Collections.<String>emptyList(), writes);
    }

    /**
     * The readable case still mutes and still restores — the B1c guard must not have
     * turned the whole feature off.
     */
    @Test
    public void readableControlsAreStillMutedAndRestored() throws Exception {
        start(DeviceMuteManager.PRESET_REDMI_NOTE_7);
        long lease = manager.newLease();
        manager.acquire(lease);
        awaitMuteIdle();

        assertEquals(Integer.valueOf(0), mixer.values.get("DEC1 Volume"));
        assertTrue(manager.isMuted());

        manager.release(lease);
        awaitMuteIdle();

        assertEquals("original must come back",
                ORIGINAL_VALUES.get("DEC1 Volume"), mixer.values.get("DEC1 Volume"));
        assertEquals(ORIGINAL_ENUMS.get("EAR_S"), mixer.enums.get("EAR_S"));
    }

    /**
     * AUDIT B1d. The kernel can refuse a restore write: on Qualcomm, setting
     * {@code DEC1 Volume} back to 84 returns -1 once the call has torn down, while setting
     * it to 0 during the call succeeds. Before the setters returned boolean this was
     * invisible — {@code restoreHeld} logged "Restored: DEC1 Volume" either way, which is
     * what made it look for two debugging rounds like the restore had never run.
     *
     * <p>The manager cannot force the write through; what it must not do is claim success.
     * This pins the observable contract: the control really is left muted, and
     * {@link DeviceMuteManager#isMuted()} still clears so the lease is not leaked.
     */
    @Test
    public void aRefusedRestoreLeavesTheControlMutedAndDoesNotClaimSuccess() throws Exception {
        start(DeviceMuteManager.PRESET_REDMI_NOTE_7);
        long lease = manager.newLease();
        manager.acquire(lease);
        awaitMuteIdle();
        assertEquals(Integer.valueOf(0), mixer.values.get("DEC1 Volume"));

        // The kernel starts refusing this control, exactly as it does after teardown.
        mixer.refuse.add("DEC1 Volume");

        manager.release(lease);
        awaitMuteIdle();

        // Refused: still muted, and the fake proves the write was attempted.
        assertEquals("a refused write must not change the control",
                Integer.valueOf(0), mixer.values.get("DEC1 Volume"));
        assertTrue("the restore must still have been attempted",
                mixer.writeLog().contains("setValue DEC1 Volume=84"));

        // Everything the kernel did accept must still be back.
        assertEquals(ORIGINAL_VALUES.get("DEC2 Volume"), mixer.values.get("DEC2 Volume"));
        assertEquals(ORIGINAL_ENUMS.get("EAR_S"), mixer.enums.get("EAR_S"));

        // And the lease must be released regardless, or the next call cannot mute at all.
        assertFalse("lease must clear even when a control refused the restore",
                manager.isMuted());
    }
}
