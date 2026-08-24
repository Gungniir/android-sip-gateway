package org.onetwoone.gateway.call;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.onetwoone.gateway.core.GatewayControlThread;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * GW-22 / AUDIT H7. The deferred deletion of pjsua2 {@code Call} objects.
 *
 * <p>A real {@code Call} cannot be constructed on the JVM, which is why
 * {@link CallGraveyard.Doomed} exists: a fake stands in for the two facts the graveyard acts
 * on - the pjsua call id, and whether {@code delete()} was called. The clock is injected too,
 * so the 2 s / 60 s deadlines are exercised without waiting for them.
 *
 * <p>What these tests are really pinning down is the <b>refusal</b> half. The graveyard's
 * failure mode is not a leak, it is a tombstone from deleting a call pjsua still tracks, and
 * that failure cannot be caught (pjmedia assertions are {@code abort()}). So the cases that
 * matter most here are the ones where it declines to delete.
 *
 * <p>The control thread runs on Robolectric's main looper, i.e. the test thread, so
 * {@code assertOnControlThread} is satisfied. Sweeps are driven directly rather than through
 * {@code postDelayed}, so the tests do not depend on looper scheduling mode.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class CallGraveyardTest {

    /** Stands in for a {@code GatewayCall}: an id we control and a delete we can observe. */
    private static class FakeCall implements CallGraveyard.Doomed {
        int id;
        int deleteCount;

        FakeCall(int id) {
            this.id = id;
        }

        @Override
        public int getId() {
            return id;
        }

        @Override
        public void delete() {
            deleteCount++;
        }

        /** What pjsua does once it has released the call slot. */
        void releaseSlot() {
            id = CallGraveyard.PJSUA_INVALID_ID;
        }
    }

    private static class FakeClock implements CallGraveyard.Clock {
        long now;

        @Override
        public long nowMs() {
            return now;
        }

        void advance(long ms) {
            now += ms;
        }
    }

    private GatewayControlThread control;
    private FakeClock clock;
    private CallGraveyard graveyard;

    @Before
    public void setUp() {
        control = new GatewayControlThread(Looper.getMainLooper(), null);
        clock = new FakeClock();
        graveyard = new CallGraveyard(control, clock);
    }

    /** The ordinary path: pjsua lets go of the slot, so the graveyard deletes deterministically. */
    @Test
    public void deletesOnceThePjsuaSlotIsReleased() {
        FakeCall call = new FakeCall(3);
        graveyard.bury(call, "test");

        call.releaseSlot();
        clock.advance(CallGraveyard.MIN_GRAVE_MS);
        graveyard.sweep();

        assertEquals(1, call.deleteCount);
        assertEquals(1L, graveyard.getDeletedCount());
        assertEquals(0, graveyard.getPendingCount());
        assertEquals(0L, graveyard.getAbandonedCount());
    }

    /**
     * The safety property. A call whose id is still valid is a call pjsua may still be inside;
     * deleting it presents as a native crash, not an exception. The graveyard must wait.
     */
    @Test
    public void refusesToDeleteWhileThePjsuaSlotIsStillHeld() {
        FakeCall call = new FakeCall(7);
        graveyard.bury(call, "test");

        clock.advance(CallGraveyard.MIN_GRAVE_MS * 4);
        graveyard.sweep();

        assertEquals("must not delete a call pjsua still tracks", 0, call.deleteCount);
        assertEquals(1, graveyard.getPendingCount());
    }

    /**
     * The other half of the same property: no grave is opened immediately, even if the id
     * already reads invalid, so a burial cannot be followed by a delete on the same turn.
     */
    @Test
    public void doesNotOpenAGraveBeforeTheMinimumDelay() {
        FakeCall call = new FakeCall(CallGraveyard.PJSUA_INVALID_ID);
        graveyard.bury(call, "test");

        clock.advance(CallGraveyard.MIN_GRAVE_MS - 1);
        graveyard.sweep();

        assertEquals(0, call.deleteCount);
        assertEquals(1, graveyard.getPendingCount());

        clock.advance(1);
        graveyard.sweep();

        assertEquals(1, call.deleteCount);
    }

    /**
     * AUDIT E5 measured a pjsua worker sitting inside {@code pjsua_media_channel_deinit} for
     * up to 50 s. If a call never comes free the graveyard gives up rather than forcing it -
     * abandoning it to the finalizer is exactly the pre-GW-22 behaviour, and refusing to
     * delete is always an option that deleting a live call is not.
     */
    @Test
    public void abandonsRatherThanForcesACallThatNeverComesFree() {
        FakeCall call = new FakeCall(11);
        graveyard.bury(call, "test");

        clock.advance(CallGraveyard.MAX_WAIT_MS);
        graveyard.sweep();

        assertEquals("abandoned, never forced", 0, call.deleteCount);
        assertEquals(1L, graveyard.getAbandonedCount());
        assertEquals(0L, graveyard.getDeletedCount());
        assertEquals("the reference must be dropped so the finalizer can have it",
                0, graveyard.getPendingCount());
    }

    /** One second before the deadline it is still waiting, not yet abandoning. */
    @Test
    public void keepsWaitingUntilTheDeadline() {
        FakeCall call = new FakeCall(11);
        graveyard.bury(call, "test");

        clock.advance(CallGraveyard.MAX_WAIT_MS - 1);
        graveyard.sweep();

        assertEquals(1, graveyard.getPendingCount());
        assertEquals(0L, graveyard.getAbandonedCount());
    }

    /**
     * A call reaches two burial sites in the normal flow - {@code hangupSipCall} sends the BYE
     * and buries it, then its own {@code DISCONNECTED} arrives and buries it again. It must be
     * deleted once, not twice: a double {@code delete()} would be a double count even though
     * SWIG's own delete is idempotent.
     */
    @Test
    public void buryingTheSameCallTwiceDeletesItOnce() {
        FakeCall call = new FakeCall(5);
        graveyard.bury(call, "hangupSipCall");
        graveyard.bury(call, "DISCONNECTED");

        assertEquals(1, graveyard.getPendingCount());

        call.releaseSlot();
        clock.advance(CallGraveyard.MIN_GRAVE_MS);
        graveyard.sweep();

        assertEquals(1, call.deleteCount);
        assertEquals(1L, graveyard.getDeletedCount());
    }

    /** Graves are independent: one call coming free must not hold up or drag out another. */
    @Test
    public void sweepsEachGraveOnItsOwnTerms() {
        FakeCall freed = new FakeCall(1);
        FakeCall stuck = new FakeCall(2);
        graveyard.bury(freed, "freed");
        graveyard.bury(stuck, "stuck");

        freed.releaseSlot();
        clock.advance(CallGraveyard.MIN_GRAVE_MS);
        graveyard.sweep();

        assertEquals(1, freed.deleteCount);
        assertEquals(0, stuck.deleteCount);
        assertEquals(1, graveyard.getPendingCount());
    }

    /**
     * A call buried before its predecessors have been swept must still get its own full
     * {@link CallGraveyard#MIN_GRAVE_MS}, rather than inheriting an older grave's sweep.
     */
    @Test
    public void aLateBurialGetsItsOwnDelay() {
        FakeCall early = new FakeCall(CallGraveyard.PJSUA_INVALID_ID);
        graveyard.bury(early, "early");

        clock.advance(CallGraveyard.MIN_GRAVE_MS);
        FakeCall late = new FakeCall(CallGraveyard.PJSUA_INVALID_ID);
        graveyard.bury(late, "late");

        graveyard.sweep();

        assertEquals(1, early.deleteCount);
        assertEquals("buried this instant - not yet", 0, late.deleteCount);

        clock.advance(CallGraveyard.MIN_GRAVE_MS);
        graveyard.sweep();

        assertEquals(1, late.deleteCount);
    }

    /**
     * An outgoing call whose {@code makeCall} threw never took a pjsua slot, so its id is
     * still invalid from construction. Nothing ever freed those before GW-22.
     */
    @Test
    public void deletesACallThatNeverTookASlot() {
        FakeCall neverDialled = new FakeCall(CallGraveyard.PJSUA_INVALID_ID);
        graveyard.bury(neverDialled, "outgoing call failed");

        clock.advance(CallGraveyard.MIN_GRAVE_MS);
        graveyard.sweep();

        assertEquals(1, neverDialled.deleteCount);
    }

    /** Nothing to do, and nothing that throws, is the expected state most of the time. */
    @Test
    public void sweepingAnEmptyGraveyardDoesNothing() {
        graveyard.sweep();

        assertEquals(0, graveyard.getPendingCount());
        assertEquals(0L, graveyard.getDeletedCount());
    }

    @Test
    public void buryingNullIsIgnored() {
        graveyard.bury(null, "test");

        assertEquals(0, graveyard.getPendingCount());
    }

    /**
     * Insurance for a 24/7 device whose control looper has quit with graves outstanding: the
     * sweep would never run again, so the list must not grow without bound. The oldest is
     * abandoned to the finalizer, never force-deleted.
     */
    @Test
    public void boundsTheGraveyardWhenTheSweepStopsRunning() {
        FakeCall oldest = new FakeCall(1);
        graveyard.bury(oldest, "oldest");
        for (int i = 1; i < CallGraveyard.MAX_GRAVES; i++) {
            graveyard.bury(new FakeCall(i + 1), "filler");
        }
        assertEquals(CallGraveyard.MAX_GRAVES, graveyard.getPendingCount());

        graveyard.bury(new FakeCall(9999), "one too many");

        assertEquals(CallGraveyard.MAX_GRAVES, graveyard.getPendingCount());
        assertEquals(1L, graveyard.getAbandonedCount());
        assertEquals("dropped, not deleted", 0, oldest.deleteCount);
    }

    /** The graveyard cannot work off the control thread; refusing to construct says so early. */
    @Test
    public void refusesToBeBuiltWithoutTheControlThread() {
        boolean threw = false;
        try {
            new CallGraveyard(null);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        assertTrue("a graveyard with no control thread is a silent leak", threw);
    }

    /** A grave that is neither ready nor expired simply stays, and stays exactly once. */
    @Test
    public void aPendingGraveIsNotDuplicatedBySuccessiveSweeps() {
        FakeCall call = new FakeCall(4);
        graveyard.bury(call, "test");

        clock.advance(CallGraveyard.MIN_GRAVE_MS);
        graveyard.sweep();
        graveyard.sweep();
        graveyard.sweep();

        assertEquals(1, graveyard.getPendingCount());
        assertFalse("still not deleted", call.deleteCount > 0);
    }
}
