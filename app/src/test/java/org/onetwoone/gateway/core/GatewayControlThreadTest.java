package org.onetwoone.gateway.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.onetwoone.gateway.BuildConfig;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GW-10 — {@link GatewayControlThread}.
 *
 * <p>Most tests run the control thread on Robolectric's paused main looper, which is the
 * established pattern in this suite ({@code GsmDtmfSenderTest},
 * {@code ReconnectionStrategyTest}): {@code shadowOf(looper).idle()} drives the queue with no
 * wall-clock waiting, so "was it deferred or run inline?" is a decidable question. The two
 * tests that need a genuinely foreign thread build the production form, which owns a real
 * {@code HandlerThread} (these run for real under Robolectric — see
 * {@code DeviceMuteManagerTest}).
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class GatewayControlThreadTest {

    private static final long TIMEOUT_S = 10L;

    /**
     * Stands in for {@code SipEndpointManager::registerThread}. {@code endpointReady} models
     * the ordering problem that is the whole point: at construction there is no endpoint, so
     * registration cannot succeed yet.
     */
    private static class FakeRegistrar implements GatewayControlThread.PjlibRegistrar {
        volatile boolean endpointReady = false;
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger successes = new AtomicInteger();
        final List<String> names = new ArrayList<>();

        @Override
        public boolean register(String threadName) {
            attempts.incrementAndGet();
            if (!endpointReady) {
                return false;
            }
            synchronized (names) {
                names.add(threadName);
            }
            successes.incrementAndGet();
            return true;
        }
    }

    private FakeRegistrar registrar;
    private GatewayControlThread control;
    private ShadowLooper looper;

    @Before
    public void setUp() {
        registrar = new FakeRegistrar();
        control = new GatewayControlThread(Looper.getMainLooper(), registrar);
        looper = shadowOf(Looper.getMainLooper());
    }

    @After
    public void tearDown() {
        control.quitSafely(TIMEOUT_S * 1000);
    }

    // ========== Posting ==========

    @Test
    public void postDefersWorkOntoTheControlQueue() {
        AtomicInteger runs = new AtomicInteger();

        control.post(runs::incrementAndGet);
        assertEquals("post() must never run inline, not even from the control thread itself",
                0, runs.get());

        looper.idle();
        assertEquals(1, runs.get());
    }

    @Test
    public void postedTasksRunInOrder() {
        List<String> order = new ArrayList<>();
        control.post(() -> order.add("a"));
        control.post(() -> order.add("b"));
        control.post(() -> order.add("c"));

        looper.idle();

        assertEquals("the point of a single owning thread is a single order",
                java.util.Arrays.asList("a", "b", "c"), order);
    }

    @Test
    public void postDelayedWaitsForItsDeadline() {
        AtomicInteger runs = new AtomicInteger();
        control.postDelayed(runs::incrementAndGet, 500);

        looper.idleFor(Duration.ofMillis(499));
        assertEquals(0, runs.get());

        looper.idleFor(Duration.ofMillis(1));
        assertEquals(1, runs.get());
    }

    /**
     * The wrapper that carries pjlib registration must not break cancellation: what reaches
     * the looper is not the caller's runnable, so {@code removeCallbacks} only works because
     * the task is posted as its own message token.
     */
    @Test
    public void removeCallbacksCancelsAQueuedTask() {
        AtomicInteger runs = new AtomicInteger();
        Runnable task = runs::incrementAndGet;

        control.postDelayed(task, 500);
        control.removeCallbacks(task);

        looper.idleFor(Duration.ofMillis(2000));
        assertEquals("a cancelled task must not run", 0, runs.get());
    }

    @Test
    public void removeCallbacksLeavesOtherTasksAlone() {
        AtomicInteger cancelled = new AtomicInteger();
        AtomicInteger kept = new AtomicInteger();
        Runnable doomed = cancelled::incrementAndGet;

        control.postDelayed(doomed, 500);
        control.postDelayed(kept::incrementAndGet, 500);
        control.removeCallbacks(doomed);

        looper.idleFor(Duration.ofMillis(2000));
        assertEquals(0, cancelled.get());
        assertEquals(1, kept.get());
    }

    // ========== isCurrent / re-entrancy ==========

    @Test
    public void isCurrentIsTrueOnTheControlThreadAndFalseElsewhere() throws Exception {
        assertTrue("the test runs on the injected control looper", control.isCurrent());

        AtomicReference<Boolean> offThread = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        new Thread(() -> {
            offThread.set(control.isCurrent());
            done.countDown();
        }, "not-control").start();

        assertTrue(done.await(TIMEOUT_S, TimeUnit.SECONDS));
        assertFalse(offThread.get());
    }

    /**
     * A lifecycle command reached from a control-thread handler must run <em>now</em>, not
     * queue behind the handler that called it. Queuing is not a deadlock with a Handler, but
     * it silently reorders the sequence a caller believes it wrote.
     */
    @Test
    public void runOrPostRunsInlineWhenAlreadyOnTheControlThread() {
        List<String> order = new ArrayList<>();

        control.runOrPost(() -> order.add("inline"));
        order.add("after");

        assertEquals("re-entrant work must not be deferred",
                java.util.Arrays.asList("inline", "after"), order);
    }

    @Test
    public void runOrPostQueuesFromAForeignThread() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch posted = new CountDownLatch(1);

        new Thread(() -> {
            control.runOrPost(runs::incrementAndGet);
            posted.countDown();
        }, "not-control").start();

        assertTrue(posted.await(TIMEOUT_S, TimeUnit.SECONDS));
        assertEquals("must not run on the caller's thread", 0, runs.get());

        looper.idle();
        assertEquals(1, runs.get());
    }

    // ========== assertOnControlThread ==========

    @Test
    public void assertOnControlThreadPassesOnTheControlThread() {
        control.assertOnControlThread("onTheRightThread");
    }

    /**
     * Debug builds throw (same shape as {@code DeviceMuteManager.assertOffMain}); release
     * builds log (same shape as {@code PjsipSipService.assertMainThread}). Both variants of
     * this suite run, so the expectation has to follow the build type rather than pick one.
     */
    @Test
    public void assertOnControlThreadThrowsInDebugAndLogsInRelease() throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        new Thread(() -> {
            try {
                control.assertOnControlThread("wrongThread");
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        }, "not-control").start();

        assertTrue(done.await(TIMEOUT_S, TimeUnit.SECONDS));

        if (BuildConfig.DEBUG) {
            assertNotNull("debug builds must fail loudly on a wrong-thread mutator",
                    thrown.get());
            assertTrue(thrown.get() instanceof IllegalStateException);
            assertTrue("the message must name the offending thread",
                    thrown.get().getMessage().contains("not-control"));
            assertTrue("...and the thread it should have been on",
                    thrown.get().getMessage().contains(GatewayControlThread.THREAD_NAME));
        } else {
            assertNull("release builds must not kill a live gateway over this", thrown.get());
        }
    }

    // ========== pjlib registration ordering ==========

    /**
     * The ordering problem GW-10 §1 got wrong: the thread starts in {@code onCreate}, but the
     * endpoint it would register with is created <em>by a task posted onto this thread</em>.
     * So registration is retried at the head of each task until it takes, and then never
     * again — the pool must not grow one descriptor per task (AUDIT F2).
     */
    @Test
    public void registersWithPjlibExactlyOnceAndOnlyAfterAnEndpointExists() {
        AtomicInteger runs = new AtomicInteger();

        // No endpoint yet: the task still runs, unregistered. Safe, because with no endpoint
        // there is no pjsua to call.
        control.post(runs::incrementAndGet);
        looper.idle();
        assertEquals(1, runs.get());
        assertEquals("must have tried", 1, registrar.attempts.get());
        assertEquals("...and failed", 0, registrar.successes.get());

        registrar.endpointReady = true;

        control.post(runs::incrementAndGet);
        looper.idle();
        assertEquals(2, registrar.attempts.get());
        assertEquals(1, registrar.successes.get());

        // From here on it must never ask again, however many tasks run.
        control.post(runs::incrementAndGet);
        control.post(runs::incrementAndGet);
        looper.idle();
        assertEquals(4, runs.get());
        assertEquals("registered exactly once", 1, registrar.successes.get());
        assertEquals("and never asked again", 2, registrar.attempts.get());

        synchronized (registrar.names) {
            assertEquals(java.util.Collections.singletonList(GatewayControlThread.THREAD_NAME),
                    registrar.names);
        }
    }

    /**
     * SIP init creates the endpoint and then keeps calling pjsua2 <em>in the same task</em>,
     * so it must be able to register mid-task. That explicit call and the per-task one share
     * a single flag.
     */
    @Test
    public void explicitRegistrationMidTaskIsHonouredAndStillOnlyHappensOnce() {
        AtomicReference<Boolean> firstResult = new AtomicReference<>();

        control.post(() -> {
            // Stand-in for endpointManager.createEndpoint() completing.
            registrar.endpointReady = true;
            firstResult.set(control.registerWithPjlib());
            // A second ask inside the same task must be a no-op.
            control.registerWithPjlib();
        });
        looper.idle();

        assertTrue(firstResult.get());
        assertEquals("one failed pre-endpoint attempt plus one success",
                2, registrar.attempts.get());
        assertEquals(1, registrar.successes.get());

        control.post(() -> assertTrue(control.isRegisteredWithPjlib()));
        looper.idle();
        assertEquals("still exactly one registration", 1, registrar.successes.get());
    }

    @Test
    public void aNullRegistrarIsTolerated() {
        GatewayControlThread bare = new GatewayControlThread(Looper.getMainLooper(), null);
        AtomicInteger runs = new AtomicInteger();
        bare.post(runs::incrementAndGet);
        looper.idle();
        assertEquals(1, runs.get());
    }

    // ========== Snapshot publication ==========

    /**
     * The contract the {@code GatewayStatus} snapshot rests on: it is built on the control
     * thread and read from anywhere, so the publish must both happen there and be visible
     * off it.
     */
    @Test
    public void snapshotIsBuiltOnTheControlThreadAndVisibleOffIt() throws Exception {
        AtomicReference<GatewayStatus> published = new AtomicReference<>(GatewayStatus.UNAVAILABLE);
        AtomicReference<String> publishedOn = new AtomicReference<>();

        control.post(() -> {
            control.assertOnControlThread("publishStatus");
            publishedOn.set(Thread.currentThread().getName());
            published.set(GatewayStatus.capture(true, null, null, null, 0L, 0L, 0L, null));
        });

        assertSame("nothing may be published before the task runs",
                GatewayStatus.UNAVAILABLE, published.get());

        looper.idle();
        assertEquals(Thread.currentThread().getName(), publishedOn.get());

        AtomicReference<Boolean> seenByReader = new AtomicReference<>();
        CountDownLatch read = new CountDownLatch(1);
        new Thread(() -> {
            seenByReader.set(published.get().isRunning());
            read.countDown();
        }, "ui-poll").start();

        assertTrue(read.await(TIMEOUT_S, TimeUnit.SECONDS));
        assertTrue("a reader off the control thread must see the published snapshot",
                seenByReader.get());
    }

    // ========== Owned thread: identity, registration and shutdown ==========

    @Test
    public void ownedThreadRunsWorkOffTheCallerAndRegistersOnce() throws Exception {
        FakeRegistrar own = new FakeRegistrar();
        own.endpointReady = true;
        GatewayControlThread owned = new GatewayControlThread(own);
        try {
            AtomicReference<String> ranOn = new AtomicReference<>();
            AtomicReference<Boolean> wasCurrent = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            owned.post(() -> {
                ranOn.set(Thread.currentThread().getName());
                wasCurrent.set(owned.isCurrent());
                done.countDown();
            });

            assertTrue(done.await(TIMEOUT_S, TimeUnit.SECONDS));
            assertEquals(GatewayControlThread.THREAD_NAME, ranOn.get());
            assertTrue(wasCurrent.get());
            assertFalse("the caller is not the control thread", owned.isCurrent());
            assertEquals(1, own.successes.get());
        } finally {
            owned.quitSafely(TIMEOUT_S * 1000);
        }
    }

    /** Service destroy must actually retire the thread, and must not hang doing it. */
    @Test
    public void quitSafelyStopsTheOwnedThreadWithinItsBound() throws Exception {
        GatewayControlThread owned = new GatewayControlThread(new FakeRegistrar());
        CountDownLatch ran = new CountDownLatch(1);
        owned.post(ran::countDown);
        assertTrue(ran.await(TIMEOUT_S, TimeUnit.SECONDS));

        assertTrue(owned.isAlive());
        long start = System.nanoTime();
        owned.quitSafely(TIMEOUT_S * 1000);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertFalse("the control thread must not outlive the service", owned.isAlive());
        assertTrue("the join is bounded, not a wait-forever: " + elapsedMs + " ms",
                elapsedMs < TIMEOUT_S * 1000);
    }

    /** An injected looper is not ours; quitting must leave the caller's looper alive. */
    @Test
    public void quitSafelyLeavesAnInjectedLooperAlone() {
        control.quitSafely(50);
        AtomicInteger runs = new AtomicInteger();
        control.post(runs::incrementAndGet);
        looper.idle();
        assertEquals(1, runs.get());
    }

    @Test
    public void nullTasksAreIgnoredRatherThanCrashingTheOwningThread() {
        control.post(null);
        control.postDelayed(null, 10);
        control.runOrPost(null);
        control.removeCallbacks(null);
        looper.idleFor(Duration.ofMillis(100));
    }

    @Test
    public void getLooperExposesTheQueueForTests() {
        assertSame(Looper.getMainLooper(), control.getLooper());
    }
}
