package org.onetwoone.gateway.sip;

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
 * Unit tests for ServiceWatchdog.
 * Tests periodic callback execution and state management.
 *
 * <p>GW-15 moved the watchdog onto the control thread's looper. Same pattern as
 * {@code GatewayControlThreadTest} and {@link ReconnectionStrategyTest}: the control thread is
 * built in its injected-looper form on Robolectric's paused main looper, so the existing
 * {@code ShadowLooper.runUiThreadTasksIncludingDelayedTasks()} calls still drive the timer and
 * the test thread satisfies the new control-thread assertions.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class ServiceWatchdogTest {

    private static final long TIMEOUT_S = 10L;

    private AtomicInteger checkCount;
    private GatewayControlThread control;
    private ServiceWatchdog watchdog;

    @Before
    public void setUp() {
        // Initialize GatewayConfig for WATCHDOG_INTERVAL_MS constant
        GatewayConfig.init(RuntimeEnvironment.getApplication());

        checkCount = new AtomicInteger(0);
        control = new GatewayControlThread(Looper.getMainLooper(), null);
        watchdog = new ServiceWatchdog(control, checkCount::incrementAndGet);
    }

    @Test
    public void testInitialState() {
        assertFalse("Watchdog should not be running initially", watchdog.isRunning());
    }

    @Test
    public void testStartStop() {
        watchdog.start();
        assertTrue("Watchdog should be running after start", watchdog.isRunning());

        watchdog.stop();
        assertFalse("Watchdog should not be running after stop", watchdog.isRunning());
    }

    @Test
    public void testDoubleStartIgnored() {
        watchdog.start();
        watchdog.start(); // Should be ignored

        assertTrue("Watchdog should still be running", watchdog.isRunning());
    }

    @Test
    public void testDoubleStopSafe() {
        watchdog.stop();
        watchdog.stop(); // Should not throw

        assertFalse("Watchdog should not be running", watchdog.isRunning());
    }

    @Test
    public void testCallbackExecution() {
        watchdog.start();

        // Fast-forward time to trigger watchdog
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        assertTrue("Callback should be called at least once", checkCount.get() >= 1);
    }

    @Test
    public void testStopCancelsCallback() {
        watchdog.start();
        watchdog.stop();

        int countAfterStop = checkCount.get();
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        assertEquals("No callbacks should occur after stop", countAfterStop, checkCount.get());
    }

    @Test
    public void testCheckNow() {
        // checkNow should work even when not running
        watchdog.checkNow();
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        assertEquals("checkNow should execute callback once", 1, checkCount.get());
    }

    @Test
    public void testCallbackException() {
        // Test that exceptions in callback don't crash watchdog
        ServiceWatchdog badWatchdog = new ServiceWatchdog(control, () -> {
            throw new RuntimeException("Test exception");
        });

        badWatchdog.start();
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        // Should not throw, watchdog continues running
        assertTrue("Watchdog should continue after callback exception", badWatchdog.isRunning());
    }

    @Test
    public void testNullCallback() {
        // Null callback should be handled gracefully
        ServiceWatchdog nullWatchdog = new ServiceWatchdog(control, null);

        nullWatchdog.start();
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        // Should not throw
        assertTrue("Watchdog with null callback should still run", nullWatchdog.isRunning());
    }

    // ========== GW-15: the tick lives on the control looper ==========

    /**
     * GW-15 §4 for the watchdog: the {@link android.os.Handler} is built on the control looper,
     * not on {@code Looper.getMainLooper()}, so the tick must be invisible to main and must run
     * on the control thread. Uses the production form of {@link GatewayControlThread}, which
     * owns a real {@code GatewayControl} thread - the rest of this suite injects the main
     * looper and so cannot tell the two apart. Reverting the constructor to
     * {@code new Handler(Looper.getMainLooper())} fails the first assertion.
     */
    @Test
    public void theTickLivesOnTheControlLooperNotMain() throws Exception {
        GatewayControlThread owned = new GatewayControlThread(null);
        try {
            AtomicReference<String> ranOn = new AtomicReference<>();
            CountDownLatch fired = new CountDownLatch(1);
            ServiceWatchdog offMain = new ServiceWatchdog(owned, () -> {
                ranOn.set(Thread.currentThread().getName());
                fired.countDown();
            });

            CountDownLatch started = new CountDownLatch(1);
            owned.post(() -> {
                offMain.start();
                started.countDown();
            });
            assertTrue("start never ran on the control thread",
                    started.await(TIMEOUT_S, TimeUnit.SECONDS));

            ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
            assertNull("the watchdog tick must not be queued on the main looper", ranOn.get());

            shadowOf(owned.getLooper())
                    .idleFor(Duration.ofMillis(GatewayConfig.WATCHDOG_INTERVAL_MS));
            assertTrue("the tick never came due on the control looper",
                    fired.await(TIMEOUT_S, TimeUnit.SECONDS));
            assertEquals("the watchdog check must run on the control thread",
                    GatewayControlThread.THREAD_NAME, ranOn.get());
        } finally {
            owned.quitSafely(TIMEOUT_S * 1000);
        }
    }

    /**
     * Same trap as {@code ReconnectionStrategyTest.theTimerActionStillPicksUpPjlibRegistration}:
     * owning our own {@link android.os.Handler} puts the tick on the control looper without
     * going through {@code GatewayControlThread.post}, skipping the lazy pjlib registration -
     * and the check can terminate calls, which is a pjsua2 call, which aborts the process from
     * an unregistered thread. Reverting {@code control.runOrPost(checkCallback)} to
     * {@code checkCallback.run()} fails this with zero registration attempts.
     */
    @Test
    public void theTickStillPicksUpPjlibRegistration() {
        AtomicInteger attempts = new AtomicInteger();
        GatewayControlThread registering =
                new GatewayControlThread(Looper.getMainLooper(), name -> {
                    attempts.incrementAndGet();
                    return true;
                });
        AtomicInteger ran = new AtomicInteger();
        ServiceWatchdog w = new ServiceWatchdog(registering, ran::incrementAndGet);

        w.start();
        assertEquals("nothing may register before the first tick", 0, attempts.get());

        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();

        assertTrue("the check must still run", ran.get() >= 1);
        assertEquals("...and must reach pjlib registration on its way in", 1, attempts.get());
    }

    /**
     * {@code running} is confined to the control thread instead of being made volatile, so the
     * check-then-set in {@code start()} stays atomic. The old helper asserted <em>main</em>;
     * this is the assertion that replaced it. Debug throws, release logs.
     */
    @Test
    public void startAssertsTheControlThread() throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        new Thread(() -> {
            try {
                watchdog.start();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        }, "not-control").start();

        assertTrue(done.await(TIMEOUT_S, TimeUnit.SECONDS));

        if (BuildConfig.DEBUG) {
            assertNotNull("a wrong-thread start must fail loudly in debug", thrown.get());
            assertTrue(thrown.get() instanceof IllegalStateException);
            assertTrue("the message must name the offending thread",
                    thrown.get().getMessage().contains("not-control"));
        } else {
            assertNull("release builds must not kill a live gateway over this", thrown.get());
        }
    }
}
