package org.onetwoone.gateway.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GW-26 — {@link LifecycleCancellation}, the mechanism that lets service destroy <em>cancel</em>
 * in-flight SIP init instead of abandoning a thread that then registers a SIP account for a
 * service that no longer exists (AUDIT H8c).
 *
 * <p>Plain JUnit: the class has no Android dependency, which is deliberate — what must be
 * provably right here is a happens-before argument and one ordering rule, not a looper
 * interaction.
 */
public class LifecycleCancellationTest {

    private LifecycleCancellation cancellation;

    @Before
    public void setUp() {
        cancellation = new LifecycleCancellation();
    }

    @Test
    public void aFreshTokenIsNotCancelled() {
        assertFalse(cancellation.begin().isCancelled());
        assertFalse(cancellation.isCancelled());
    }

    @Test
    public void cancelInvalidatesAnOutstandingToken() {
        LifecycleCancellation.Token token = cancellation.begin();

        cancellation.cancel();

        assertTrue(token.isCancelled());
    }

    @Test
    public void everyOutstandingTokenIsInvalidatedAtOnce() {
        LifecycleCancellation.Token a = cancellation.begin();
        LifecycleCancellation.Token b = cancellation.begin();

        cancellation.cancel();

        assertTrue(a.isCancelled());
        assertTrue(b.isCancelled());
    }

    /**
     * <b>The rule that makes this terminal rather than a reusable generation.</b> The doomed
     * SIP init is usually still <em>queued</em> when destroy runs, so it reaches {@code begin()}
     * afterwards, on the control thread. A generation counter would hand it a live token — it
     * would then create exactly the account cancellation exists to prevent, on a service that
     * is already gone.
     */
    @Test
    public void workThatOnlyBeginsAfterCancelIsBornCancelled() {
        cancellation.cancel();

        LifecycleCancellation.Token queuedTaskFinallyRunning = cancellation.begin();

        assertTrue("a task that had not started when destroy ran must not become live",
                queuedTaskFinallyRunning.isCancelled());
    }

    @Test
    public void cancellingTwiceIsIdempotent() {
        LifecycleCancellation.Token token = cancellation.begin();

        cancellation.cancel();
        cancellation.cancel();

        assertTrue(token.isCancelled());
        assertTrue(cancellation.isCancelled());
    }

    @Test
    public void throwIfCancelledIsSilentWhileLive() throws Exception {
        cancellation.begin().throwIfCancelled("SIP init");
    }

    @Test
    public void throwIfCancelledNamesTheAbandonedStep() {
        LifecycleCancellation.Token token = cancellation.begin();
        cancellation.cancel();

        try {
            token.throwIfCancelled("SIP init");
            fail("a cancelled token must abort the step");
        } catch (LifecycleCancellation.CancelledException e) {
            assertTrue("the log line has to say what was abandoned",
                    e.getMessage().contains("SIP init"));
        }
    }

    /**
     * {@link LifecycleCancellation#NEVER} is what the no-argument {@code createEndpoint()}
     * overload and the unit tests pass. It must never abort, whatever anyone cancels.
     */
    @Test
    public void theNeverTokenIsNeverCancelled() throws Exception {
        cancellation.cancel();

        assertFalse(LifecycleCancellation.NEVER.isCancelled());
        LifecycleCancellation.NEVER.throwIfCancelled("SIP init");
    }

    /**
     * The production interleaving: {@code cancel()} is called from main while the control thread
     * is checking. Either the check sees it or the next one does — and there is always a next
     * one before anything irreversible happens.
     */
    @Test
    public void aCancelFromAnotherThreadIsVisibleToTheHolder() throws Exception {
        LifecycleCancellation.Token token = cancellation.begin();
        CountDownLatch observed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread holder = new Thread(() -> {
            try {
                while (!token.isCancelled()) {
                    Thread.sleep(1);
                }
                observed.countDown();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "holder");
        holder.start();

        cancellation.cancel();

        assertTrue("the holder must see a cancel published from another thread",
                observed.await(10, TimeUnit.SECONDS));
        holder.join(1000);
        assertNull(failure.get());
    }
}
