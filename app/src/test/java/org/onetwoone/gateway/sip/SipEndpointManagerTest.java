package org.onetwoone.gateway.sip;

import android.app.Application;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.onetwoone.gateway.BuildConfig;
import org.onetwoone.gateway.config.GatewayConfig;
import org.onetwoone.gateway.core.LifecycleCancellation;
import org.onetwoone.gateway.core.GatewayControlThread;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * GW-15 — {@link SipEndpointManager}'s thread ownership.
 *
 * <p>Only the assertion surface is reachable from the JVM: everything past it calls into
 * pjsua2, which needs {@code libpjsua2.so}. That is enough to cover what GW-15 changed, because
 * the assertion <em>is</em> the change. {@code hasTransport()} used to call
 * {@code registerThread(Thread.currentThread().getName())} to keep an unregistered caller from
 * aborting the process; every descriptor pjlib handed out that way leaked (AUDIT F2). The
 * replacement is not a smaller registration - it is proving the caller is the one thread this
 * process registers, and failing loudly when it is not.
 *
 * <p>These tests never let the endpoint be non-null, so no pjsua2 call is ever reached.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class SipEndpointManagerTest {

    private static final long TIMEOUT_S = 10L;

    private GatewayControlThread control;
    private SipEndpointManager manager;

    @Before
    public void setUp() {
        Application app = RuntimeEnvironment.getApplication();
        GatewayConfig.init(app);

        control = new GatewayControlThread(Looper.getMainLooper(), null);
        manager = new SipEndpointManager(GatewayConfig.getInstance());
        manager.setControlThread(control);
    }

    /**
     * The F2 remedy. A caller off the control thread is now a visible programming error rather
     * than a silently leaked thread descriptor - or, if it had been left unguarded, a hard
     * process abort inside pjsua. Debug throws, release logs; both variants of this suite run.
     */
    @Test
    public void hasTransportAssertsTheControlThread() throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        new Thread(() -> {
            try {
                manager.hasTransport();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        }, "nanohttpd-worker").start();

        assertTrue(done.await(TIMEOUT_S, TimeUnit.SECONDS));

        if (BuildConfig.DEBUG) {
            assertNotNull("a query that reaches pjsua from a foreign thread must fail loudly",
                    thrown.get());
            assertTrue(thrown.get() instanceof IllegalStateException);
            assertTrue("the message must name the offending thread",
                    thrown.get().getMessage().contains("nanohttpd-worker"));
            assertTrue("...and the thread it should have been on",
                    thrown.get().getMessage().contains(GatewayControlThread.THREAD_NAME));
        } else {
            assertNull("release builds must not kill a live gateway over this", thrown.get());
        }
    }

    /** On the owning thread it is an ordinary query: no endpoint yet, so no transport. */
    @Test
    public void hasTransportOnTheControlThreadReportsNoTransportBeforeAnyEndpointExists() {
        assertFalse(manager.hasTransport());
    }

    /**
     * A manager built standalone has no thread model to assert against, so it must not blow up
     * on one. This is what keeps the assertion from being a landmine for future unit tests -
     * the production wiring in {@code PjsipSipService.initializeManagers} is the only place
     * that arms it.
     */
    @Test
    public void assertionsAreSkippedWhenNoControlThreadIsWired() throws Exception {
        SipEndpointManager unwired = new SipEndpointManager(GatewayConfig.getInstance());

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        new Thread(() -> {
            try {
                unwired.hasTransport();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        }, "unwired").start();

        assertTrue(done.await(TIMEOUT_S, TimeUnit.SECONDS));
        assertNull(thrown.get());
    }

    // ========== GW-26: the endpoint hop must be cancellable ==========

    /**
     * The 30 s latch in {@code createEndpointOnMainThread} is the whole reason destroy needed
     * cancellation rather than a longer join, and this is that wait in isolation.
     * {@code awaitCancellably} is package-visible for exactly this: everything around it is
     * pjsua2 and therefore unreachable from the JVM.
     */
    @Test
    public void aCancellableWaitReturnsWhenTheLatchFires() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        latch.countDown();

        SipEndpointManager.awaitCancellably(latch, LifecycleCancellation.NEVER, 5000,
                "endpoint creation");
    }

    @Test
    public void aCancellableWaitGivesUpAtItsDeadline() throws Exception {
        CountDownLatch never = new CountDownLatch(1);

        try {
            SipEndpointManager.awaitCancellably(never, LifecycleCancellation.NEVER, 120,
                    "endpoint creation");
            fail("the 30 s backstop must still exist");
        } catch (LifecycleCancellation.CancelledException e) {
            fail("nothing cancelled this; it timed out");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("Timeout waiting for endpoint creation"));
        }
    }

    /**
     * The one that matters. On device the runnable this latch waits for is queued <em>behind
     * {@code onDestroy}</em> on main, so it cannot fire while the service is being destroyed;
     * before GW-26 the control thread therefore sat here for the full 30 s and then walked on
     * to create a SIP account for a service that was already gone (AUDIT H8c). It must now give
     * up within a poll interval of the cancel.
     */
    @Test
    public void aCancellableWaitAbortsPromptlyWhenTeardownCancelsIt() throws Exception {
        LifecycleCancellation cancellation = new LifecycleCancellation();
        LifecycleCancellation.Token token = cancellation.begin();
        CountDownLatch never = new CountDownLatch(1);
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();

        Thread waiter = new Thread(() -> {
            waiting.countDown();
            try {
                SipEndpointManager.awaitCancellably(never, token,
                        SipEndpointManager.ENDPOINT_CREATE_TIMEOUT_MS, "endpoint creation");
                outcome.set(new AssertionError("the wait must not have completed"));
            } catch (Throwable t) {
                outcome.set(t);
            }
        }, "endpoint-waiter");
        waiter.start();

        assertTrue(waiting.await(TIMEOUT_S, TimeUnit.SECONDS));
        cancellation.cancel();

        // Generous relative to CANCEL_POLL_MS (50 ms) and tiny relative to the 30 s bound the
        // wait would otherwise run to: this asserts "cancelled", not "timed out".
        waiter.join(TIMEOUT_S * 1000);
        assertFalse("the wait must abandon the latch, not run to its deadline", waiter.isAlive());
        assertTrue("a cancelled hop is not a failed one: " + outcome.get(),
                outcome.get() instanceof LifecycleCancellation.CancelledException);
    }

    /** Destroy can land before the task ever reaches the wait. That must abort too. */
    @Test
    public void aCancellableWaitDoesNotStartIfAlreadyCancelled() {
        LifecycleCancellation cancellation = new LifecycleCancellation();
        LifecycleCancellation.Token token = cancellation.begin();
        cancellation.cancel();

        try {
            SipEndpointManager.awaitCancellably(new CountDownLatch(1), token,
                    SipEndpointManager.ENDPOINT_CREATE_TIMEOUT_MS, "endpoint creation");
            fail("an already-cancelled token must not enter the wait");
        } catch (LifecycleCancellation.CancelledException expected) {
            // exactly this
        } catch (Exception e) {
            fail("expected cancellation, got " + e);
        }
    }
}
