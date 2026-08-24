package org.onetwoone.gateway.ui.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;

/**
 * GW-42 - the step machine, exhaustively.
 *
 * <h2>Why this file is the one that matters</h2>
 *
 * <p>Everything else in {@code ui/setup} is a view or an I/O call. This is the part with real
 * logic, and it is the part that can strand someone: the whole issue exists because a wizard
 * that will not let go of a half-provisioned phone is worse than no wizard at all. The
 * properties below are the promises the screen makes, tested as properties rather than as
 * example paths - "skip everything" is driven as a loop that must terminate, not as five
 * hand-written calls that happen to work.
 *
 * <p>Plain JUnit, no Robolectric: the machine touches no Android class, which is exactly why
 * it was extracted from the activity.
 */
public class SetupStepMachineTest {

    private SetupStepMachine machine;

    @Before
    public void setUp() {
        machine = new SetupStepMachine();
    }

    // ========== Starting state ==========

    @Test
    public void startsOnTheFirstStepWithNothingDecided() {
        assertSame(SetupStep.ROOT, machine.current());
        assertEquals(1, machine.currentNumber());
        assertEquals(SetupStep.values().length, machine.stepCount());
        assertTrue(machine.isFirst());
        assertFalse(machine.isLast());

        for (SetupStep step : SetupStep.values()) {
            assertSame("no step may start with an opinion about itself",
                    StepOutcome.PENDING, machine.outcomeOf(step));
        }
        assertFalse(machine.allStepsResolved());
    }

    @Test
    public void everyStepHasABodyAndAHeader() {
        for (SetupStep step : SetupStep.values()) {
            assertTrue("step " + step + " has no body view", step.bodyViewId() != 0);
            assertTrue("step " + step + " has no title", step.titleRes() != 0);
            assertTrue("step " + step + " has no summary", step.summaryRes() != 0);
        }
    }

    // ========== Skip: the property the whole issue turns on ==========

    /**
     * <b>Skip works on every step, and skipping through reaches the end.</b>
     *
     * <p>Driven as a bounded loop rather than five calls: if a step were ever made
     * unskippable, this would either loop forever (caught by the bound) or stop early.
     */
    @Test
    public void skippingEveryStepReachesTheEnd() {
        int guard = 0;
        while (machine.skip()) {
            if (++guard > 100) {
                fail("skip() never reported the end - a step is refusing to be skipped");
            }
        }

        assertSame("skipping through must land on the last step",
                SetupStep.VERIFY, machine.current());
        assertTrue(machine.isLast());
        assertEquals(SetupStep.values().length, machine.countOf(StepOutcome.SKIPPED));
        assertTrue(machine.allStepsResolved());
    }

    /** The skip lands on the step being left, not on the one being entered. */
    @Test
    public void skipMarksTheStepItLeftAndNotTheNextOne() {
        assertTrue(machine.skip());

        assertSame(StepOutcome.SKIPPED, machine.outcomeOf(SetupStep.ROOT));
        assertSame(StepOutcome.PENDING, machine.outcomeOf(SetupStep.PERMISSIONS));
        assertSame(SetupStep.PERMISSIONS, machine.current());
    }

    /**
     * The last step is skippable too, and its skip is recorded even though there is nowhere to
     * move to. Otherwise "skip everything" would leave four skips and one pending.
     */
    @Test
    public void theLastStepIsSkippableAndTheSkipIsRecorded() {
        machine.goTo(SetupStep.VERIFY);

        assertFalse("skip on the last step reports the end of the wizard", machine.skip());
        assertSame(StepOutcome.SKIPPED, machine.outcomeOf(SetupStep.VERIFY));
        assertSame("the cursor stays put; the activity finishes",
                SetupStep.VERIFY, machine.current());
    }

    // ========== Next and back ==========

    @Test
    public void nextWalksToTheEndAndThenReportsIt() {
        for (int i = 1; i < SetupStep.values().length; i++) {
            assertTrue("next() should still have somewhere to go at step " + i, machine.next());
        }
        assertTrue(machine.isLast());
        assertFalse("next() on the last step is the finish signal", machine.next());
        assertSame(SetupStep.VERIFY, machine.current());
    }

    /**
     * <b>Next never invents an outcome.</b> Walking past a step the operator did not run
     * leaves it PENDING - it is not passed, and it is not skipped either, because they did not
     * ask to skip it.
     */
    @Test
    public void nextDoesNotDecideAnythingAboutTheStepItLeaves() {
        machine.next();
        machine.next();

        assertSame(StepOutcome.PENDING, machine.outcomeOf(SetupStep.ROOT));
        assertSame(StepOutcome.PENDING, machine.outcomeOf(SetupStep.PERMISSIONS));
        assertFalse(machine.allStepsResolved());
    }

    @Test
    public void backFromTheFirstStepIsANoOp() {
        assertFalse(machine.back());
        assertSame(SetupStep.ROOT, machine.current());
    }

    /**
     * <b>Going back loses nothing.</b> The outcomes recorded on the way forward are still
     * there on the way back - which is what makes "re-run the wizard to fix step 3" a thing
     * you can do without re-doing steps 1 and 2.
     */
    @Test
    public void backNavigationKeepsEveryOutcome() {
        machine.recordCurrent(StepOutcome.PASSED);          // ROOT
        machine.next();
        machine.recordCurrent(StepOutcome.FAILED);          // PERMISSIONS
        machine.next();
        machine.skip();                                     // DIALER
        machine.next();                                     // SIP_ACCOUNT -> VERIFY

        assertSame(SetupStep.VERIFY, machine.current());

        while (machine.back()) {
            // walk all the way home
        }

        assertSame(SetupStep.ROOT, machine.current());
        assertSame(StepOutcome.PASSED, machine.outcomeOf(SetupStep.ROOT));
        assertSame(StepOutcome.FAILED, machine.outcomeOf(SetupStep.PERMISSIONS));
        assertSame(StepOutcome.SKIPPED, machine.outcomeOf(SetupStep.DIALER));
        assertSame(StepOutcome.PENDING, machine.outcomeOf(SetupStep.SIP_ACCOUNT));
    }

    @Test
    public void backAndForwardIsTheIdentity() {
        machine.next();
        machine.next();
        SetupStep before = machine.current();

        machine.back();
        machine.next();

        assertSame(before, machine.current());
    }

    // ========== Recording ==========

    /** A failed step that is fixed and re-checked must be able to say so. */
    @Test
    public void anOutcomeCanBeOverwritten() {
        machine.recordCurrent(StepOutcome.FAILED);
        assertSame(StepOutcome.FAILED, machine.outcomeOf(SetupStep.ROOT));

        machine.recordCurrent(StepOutcome.PASSED);
        assertSame(StepOutcome.PASSED, machine.outcomeOf(SetupStep.ROOT));
    }

    /** A skipped step that is come back to and run must be able to replace the skip. */
    @Test
    public void aSkippedStepCanBeRunAfterwards() {
        machine.skip();
        machine.back();

        assertSame(SetupStep.ROOT, machine.current());
        machine.recordCurrent(StepOutcome.PASSED);
        assertSame(StepOutcome.PASSED, machine.outcomeOf(SetupStep.ROOT));
    }

    @Test
    public void nullsAreIgnoredRatherThanStored() {
        machine.record(null, StepOutcome.PASSED);
        machine.record(SetupStep.ROOT, null);

        assertSame(StepOutcome.PENDING, machine.outcomeOf(SetupStep.ROOT));
    }

    @Test
    public void goToMovesTheCursorAndNothingElse() {
        machine.recordCurrent(StepOutcome.PASSED);
        machine.goTo(SetupStep.SIP_ACCOUNT);

        assertSame(SetupStep.SIP_ACCOUNT, machine.current());
        assertEquals(4, machine.currentNumber());
        assertSame(StepOutcome.PASSED, machine.outcomeOf(SetupStep.ROOT));
    }

    @Test
    public void countingOutcomes() {
        machine.recordCurrent(StepOutcome.PASSED);
        machine.next();
        machine.skip();
        machine.skip();

        assertEquals(1, machine.countOf(StepOutcome.PASSED));
        assertEquals(2, machine.countOf(StepOutcome.SKIPPED));
        assertEquals(2, machine.countOf(StepOutcome.PENDING));
    }

    @Test
    public void resolvedMeansDealtWithHoweverItWasDealtWith() {
        assertFalse(StepOutcome.PENDING.isResolved());
        assertTrue(StepOutcome.PASSED.isResolved());
        assertTrue(StepOutcome.FAILED.isResolved());
        assertTrue("a skip is an answer, not an absence", StepOutcome.SKIPPED.isResolved());
    }

    // ========== The snapshot ==========

    /**
     * The published value is a copy. A snapshot handed to an observer must not change under it
     * when the machine moves - the GW-45 rule, for the same reason.
     */
    @Test
    public void theSnapshotIsImmutableAndDetached() {
        SetupStepMachine.Snapshot taken = machine.snapshot();
        assertSame(SetupStep.ROOT, taken.step());
        assertEquals(1, taken.number());
        assertTrue(taken.isFirst());
        assertFalse(taken.isLast());
        assertSame(StepOutcome.PENDING, taken.outcome());

        machine.recordCurrent(StepOutcome.PASSED);
        machine.next();

        assertSame("the snapshot moved with the machine", SetupStep.ROOT, taken.step());
        assertSame("the snapshot's outcomes changed underneath it",
                StepOutcome.PENDING, taken.outcomeOf(SetupStep.ROOT));

        SetupStepMachine.Snapshot after = machine.snapshot();
        assertSame(SetupStep.PERMISSIONS, after.step());
        assertSame(StepOutcome.PASSED, after.outcomeOf(SetupStep.ROOT));
    }

    @Test
    public void theSnapshotKnowsWhenItIsOnTheLastStep() {
        machine.goTo(SetupStep.VERIFY);
        SetupStepMachine.Snapshot taken = machine.snapshot();

        assertTrue(taken.isLast());
        assertFalse(taken.isFirst());
        assertEquals(taken.total(), taken.number());
    }
}
