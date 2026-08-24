package org.onetwoone.gateway.sip;

import android.app.Application;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.onetwoone.gateway.BuildConfig;
import org.onetwoone.gateway.config.GatewayConfig;
import org.onetwoone.gateway.core.GatewayControlThread;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * Unit tests for ReconnectionStrategy.
 * Tests exponential backoff behavior and state management.
 *
 * <p>GW-15 moved the strategy onto the control thread's looper. The suite follows the pattern
 * established by {@code GatewayControlThreadTest}: build the control thread in its injected-
 * looper form on Robolectric's paused main looper, so the existing
 * {@code ShadowLooper.runUiThreadTasksIncludingDelayedTasks()} calls still drive the timer and
 * the test thread satisfies the new control-thread assertions.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class ReconnectionStrategyTest {

    private static final long TIMEOUT_S = 10L;

    private AtomicInteger reconnectCount;
    private GatewayControlThread control;
    private ReconnectionStrategy strategy;

    @Before
    public void setUp() {
        // Initialize GatewayConfig with Robolectric context
        Application app = RuntimeEnvironment.getApplication();
        GatewayConfig.init(app);

        reconnectCount = new AtomicInteger(0);
        control = new GatewayControlThread(Looper.getMainLooper(), null);
        strategy = new ReconnectionStrategy(control, reconnectCount::incrementAndGet);
    }

    @Test
    public void testInitialState() {
        assertTrue("Should start enabled", strategy.isEnabled());
        assertFalse("Should have no pending reconnect", strategy.isPending());
        assertEquals("Initial delay should be 5000ms", 5000, strategy.getCurrentDelay());
    }

    @Test
    public void testEnableDisable() {
        strategy.setEnabled(false);
        assertFalse("Should be disabled", strategy.isEnabled());

        strategy.setEnabled(true);
        assertTrue("Should be enabled", strategy.isEnabled());
    }

    @Test
    public void testScheduleReconnect() {
        strategy.scheduleReconnect();
        assertTrue("Should have pending reconnect", strategy.isPending());

        // Fast-forward time
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        assertEquals("Reconnect callback should be called once", 1, reconnectCount.get());
        assertFalse("Should no longer be pending", strategy.isPending());
    }

    @Test
    public void testExponentialBackoff() {
        assertEquals("Initial delay should be 5000", 5000, strategy.getCurrentDelay());

        strategy.scheduleReconnect();
        assertEquals("Delay should double after schedule", 10000, strategy.getCurrentDelay());

        strategy.scheduleReconnect(); // Won't schedule (pending)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        strategy.scheduleReconnect();
        assertEquals("Delay should be 20000", 20000, strategy.getCurrentDelay());
    }

    @Test
    public void testMaxDelay() {
        // Schedule multiple times to reach max
        for (int i = 0; i < 10; i++) {
            strategy.scheduleReconnect();
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        }

        assertTrue("Delay should be capped at max", strategy.getCurrentDelay() <= 60000);
    }

    @Test
    public void testSuccessResetsDelay() {
        // Increase delay
        strategy.scheduleReconnect();
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        strategy.scheduleReconnect();
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        assertTrue("Delay should be increased", strategy.getCurrentDelay() > 5000);

        // Reset via success
        strategy.onSuccess();
        assertEquals("Success should reset delay to initial", 5000, strategy.getCurrentDelay());
        assertFalse("Should clear pending flag", strategy.isPending());
    }

    /**
     * AUDIT F6c. {@code onSuccess()} cleared {@code pending} but left the armed runnable on the
     * queue, so it still fired and sent a redundant re-REGISTER — and {@code isPending()}
     * disagreed with what was actually armed. The reload path reaches this every time:
     * {@code deleteAccount()}'s un-REGISTER produces {@code onRegState(false)} → a scheduled
     * reconnect, and the subsequent {@code onRegState(true)} only cleared the flag.
     */
    @Test
    public void successDisarmsTheTimerItClearsTheFlagFor() {
        strategy.scheduleReconnect();
        assertTrue("Should be pending", strategy.isPending());

        strategy.onSuccess();

        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        assertEquals("a reconnect must not fire after the connection already came up",
                0, reconnectCount.get());
        assertFalse(strategy.isPending());
    }

    @Test
    public void testCancel() {
        strategy.scheduleReconnect();
        assertTrue("Should be pending", strategy.isPending());

        strategy.cancel();
        assertFalse("Cancel should clear pending", strategy.isPending());

        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        assertEquals("Callback should not be called after cancel", 0, reconnectCount.get());
    }

    @Test
    public void testDisabledDoesNotSchedule() {
        strategy.setEnabled(false);
        strategy.scheduleReconnect();

        assertFalse("Should not be pending when disabled", strategy.isPending());

        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        assertEquals("Callback should not be called when disabled", 0, reconnectCount.get());
    }

    @Test
    public void testResetDelay() {
        strategy.scheduleReconnect();
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        assertTrue("Delay should be increased", strategy.getCurrentDelay() > 5000);

        strategy.resetDelay();
        assertEquals("resetDelay should set to initial", 5000, strategy.getCurrentDelay());
    }

    @Test
    public void testDuplicateScheduleIgnored() {
        strategy.scheduleReconnect();
        int delayAfterFirst = strategy.getCurrentDelay();

        strategy.scheduleReconnect(); // Should be ignored
        assertEquals("Second schedule should not change delay", delayAfterFirst, strategy.getCurrentDelay());
    }

    // ========== GW-15: the timer lives on the control looper ==========

    /**
     * The point of GW-15 §4: the {@link android.os.Handler} is built on the control looper, not
     * on {@code Looper.getMainLooper()}. So the backoff timer must be invisible to main and
     * must fire on the control thread.
     *
     * <p>Uses the production form of {@link GatewayControlThread}, which owns a real
     * {@code GatewayControl} thread, because the rest of this suite injects the main looper and
     * therefore cannot tell the two apart. Reverting the constructor to
     * {@code new Handler(Looper.getMainLooper())} fails the first assertion.
     */
    @Test
    public void theTimerLivesOnTheControlLooperNotMain() throws Exception {
        GatewayControlThread owned = new GatewayControlThread(null);
        try {
            AtomicReference<String> ranOn = new AtomicReference<>();
            CountDownLatch fired = new CountDownLatch(1);
            ReconnectionStrategy offMain = new ReconnectionStrategy(owned, () -> {
                ranOn.set(Thread.currentThread().getName());
                fired.countDown();
            });

            CountDownLatch scheduled = new CountDownLatch(1);
            owned.post(() -> {
                offMain.scheduleReconnect();
                scheduled.countDown();
            });
            assertTrue("scheduling never ran on the control thread",
                    scheduled.await(TIMEOUT_S, TimeUnit.SECONDS));

            ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
            assertNull("the reconnect timer must not be queued on the main looper", ranOn.get());

            shadowOf(owned.getLooper())
                    .idleFor(Duration.ofMillis(GatewayConfig.RECONNECT_INITIAL_DELAY_MS));
            assertTrue("the timer never came due on the control looper",
                    fired.await(TIMEOUT_S, TimeUnit.SECONDS));
            assertEquals("the reconnect action must run on the control thread",
                    GatewayControlThread.THREAD_NAME, ranOn.get());
        } finally {
            owned.quitSafely(TIMEOUT_S * 1000);
        }
    }

    /**
     * The trap in owning our own {@link android.os.Handler}: it puts the timer on the control
     * looper without going through {@code GatewayControlThread.post}, so
     * {@code GatewayControlThread.dispatch} - and with it the lazy pjlib registration - is
     * skipped. That is not cosmetic. A SIP init that constructs the {@code Endpoint} and then
     * fails before {@code registerWithPjlib()} leaves a non-null endpoint and an unregistered
     * control thread; the reconnect this schedules calls {@code hasTransport()}, and pjsua
     * aborts the process for a thread pjlib has never seen. Before GW-15 the timer ran on main
     * and hopped via {@code control.post}, which registered on the way in.
     *
     * <p>Reverting {@code control.runOrPost(reconnectAction)} to {@code reconnectAction.run()}
     * fails this test with zero registration attempts.
     */
    @Test
    public void theTimerActionStillPicksUpPjlibRegistration() {
        AtomicInteger attempts = new AtomicInteger();
        GatewayControlThread registering =
                new GatewayControlThread(Looper.getMainLooper(), name -> {
                    attempts.incrementAndGet();
                    return true;
                });
        AtomicInteger ran = new AtomicInteger();
        ReconnectionStrategy s = new ReconnectionStrategy(registering, ran::incrementAndGet);

        s.scheduleReconnect();
        assertEquals("nothing may register before the timer fires", 0, attempts.get());

        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        assertEquals("the reconnect action must still run", 1, ran.get());
        assertEquals("...and must reach pjlib registration on its way in", 1, attempts.get());
    }

    /**
     * What replaces {@code volatile} on {@code pending}/{@code enabled}: the mutators are
     * confined to one thread, and say so. Debug builds throw, release builds log - both
     * variants of this suite run, so the expectation follows the build type.
     */
    @Test
    public void mutatorsAssertTheControlThread() throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        new Thread(() -> {
            try {
                strategy.scheduleReconnect();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        }, "not-control").start();

        assertTrue(done.await(TIMEOUT_S, TimeUnit.SECONDS));

        if (BuildConfig.DEBUG) {
            assertNotNull("a wrong-thread scheduleReconnect must fail loudly in debug",
                    thrown.get());
            assertTrue(thrown.get() instanceof IllegalStateException);
            assertTrue("the message must name the offending thread",
                    thrown.get().getMessage().contains("not-control"));
        } else {
            assertNull("release builds must not kill a live gateway over this", thrown.get());
        }
    }
}
