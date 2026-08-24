package org.onetwoone.gateway.ui.setup;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * The commissioning wizard's cursor and its scoreboard (GW-42).
 *
 * <h2>Why this is a class of its own</h2>
 *
 * <p>It is the part of the wizard with real logic and the part most likely to strand someone.
 * Plain Java, no Android, no view, no {@code Context}: it can be driven exhaustively in a unit
 * test, which is the only place the "skip everything and still reach the end" property can
 * actually be proven. Everything else in {@code ui/setup} is either a view or an I/O call.
 *
 * <h2>The invariants it exists to hold</h2>
 *
 * <ol>
 *   <li><b>Every step is skippable.</b> {@link #skip()} is defined on every step including the
 *       last, and nothing anywhere refuses it. There is no "required" flag and no place to add
 *       one without changing this class.
 *   <li><b>Skipping through reaches the end.</b> {@link #skip()} always either advances or
 *       reports that there is nowhere left to go, so a loop of skips terminates at the last
 *       step. The wizard finishes on the {@code false}.
 *   <li><b>Going back loses nothing.</b> {@link #back()} moves the cursor and touches no
 *       outcome. What a step recorded stays recorded; what the operator typed lives in the
 *       views, which are never detached.
 *   <li><b>An outcome is never inferred.</b> A step is {@link StepOutcome#PENDING} until
 *       something says otherwise. {@link #next()} does not mark a step passed just because the
 *       operator moved on - only a check that ran, or an explicit skip, resolves it.
 * </ol>
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>There is no {@code finished} flag. "The wizard is over" is the activity's business, and
 * modelling it here would give the machine a terminal state that {@link #back()} would then
 * have to un-terminate. {@link #next()} and {@link #skip()} return {@code false} at the last
 * step, which is the whole of the signal the activity needs.
 *
 * <p>Not thread-safe, and not meant to be: it is driven from the main thread by a ViewModel,
 * like the views it steers.
 */
public final class SetupStepMachine {

    private static final SetupStep[] ORDER = SetupStep.values();

    private final Map<SetupStep, StepOutcome> outcomes = new EnumMap<>(SetupStep.class);

    private int index;

    public SetupStepMachine() {
        for (SetupStep step : ORDER) {
            outcomes.put(step, StepOutcome.PENDING);
        }
    }

    /** The step being shown. */
    public SetupStep current() {
        return ORDER[index];
    }

    /** Its 1-based position, for "Step 3 of 5". */
    public int currentNumber() {
        return index + 1;
    }

    public int stepCount() {
        return ORDER.length;
    }

    public boolean isFirst() {
        return index == 0;
    }

    public boolean isLast() {
        return index == ORDER.length - 1;
    }

    public StepOutcome outcomeOf(SetupStep step) {
        StepOutcome outcome = outcomes.get(step);
        return outcome == null ? StepOutcome.PENDING : outcome;
    }

    /**
     * Record what a step's check said.
     *
     * <p>Overwriting is allowed and intended: a step re-run after fixing something must be
     * able to go from {@link StepOutcome#FAILED} to {@link StepOutcome#PASSED}, and a step
     * whose check runs after the operator skipped past it and came back must be able to
     * replace {@link StepOutcome#SKIPPED}.
     */
    public void record(SetupStep step, StepOutcome outcome) {
        if (step == null || outcome == null) {
            return;
        }
        outcomes.put(step, outcome);
    }

    /** {@link #record} for the step on screen. */
    public void recordCurrent(StepOutcome outcome) {
        record(current(), outcome);
    }

    /**
     * Move to the next step, leaving outcomes alone.
     *
     * @return false if there is no next step - the caller's cue to finish the wizard
     */
    public boolean next() {
        if (isLast()) {
            return false;
        }
        index++;
        return true;
    }

    /**
     * Move back one step, leaving outcomes alone.
     *
     * @return false if already on the first step
     */
    public boolean back() {
        if (isFirst()) {
            return false;
        }
        index--;
        return true;
    }

    /**
     * Mark the current step skipped and move on.
     *
     * <p>The skip is recorded <em>before</em> the move, so it lands on the step being left and
     * not on the one being entered - and it is recorded even at the last step, where there is
     * nowhere to move to. That is what makes "skip everything" leave five skips behind rather
     * than four skips and a pending.
     *
     * @return false if there is no next step - the caller's cue to finish the wizard
     */
    public boolean skip() {
        recordCurrent(StepOutcome.SKIPPED);
        return next();
    }

    /** Jump straight to a step, e.g. to re-open the one that failed. Outcomes untouched. */
    public void goTo(SetupStep step) {
        for (int i = 0; i < ORDER.length; i++) {
            if (ORDER[i] == step) {
                index = i;
                return;
            }
        }
    }

    /** Whether every step has been dealt with - passed, failed or skipped, none pending. */
    public boolean allStepsResolved() {
        for (SetupStep step : ORDER) {
            if (!outcomeOf(step).isResolved()) {
                return false;
            }
        }
        return true;
    }

    /** How many steps ended in {@code outcome}. For the closing summary. */
    public int countOf(StepOutcome outcome) {
        int count = 0;
        for (SetupStep step : ORDER) {
            if (outcomeOf(step) == outcome) {
                count++;
            }
        }
        return count;
    }

    /**
     * An immutable view of everything the wizard's header renders.
     *
     * <p>Published through {@code LiveData} instead of the machine itself, for the reason
     * GW-45 gave for {@code GatewayStatus}: a mutable object handed out through a state holder
     * is a value that changes behind its observers' backs. A snapshot cannot.
     */
    public Snapshot snapshot() {
        return new Snapshot(current(), currentNumber(), stepCount(), isFirst(), isLast(),
                new EnumMap<>(outcomes));
    }

    /** @see #snapshot() */
    public static final class Snapshot {

        private final SetupStep step;
        private final int number;
        private final int total;
        private final boolean first;
        private final boolean last;
        private final Map<SetupStep, StepOutcome> outcomes;

        Snapshot(SetupStep step, int number, int total, boolean first, boolean last,
                 Map<SetupStep, StepOutcome> outcomes) {
            this.step = step;
            this.number = number;
            this.total = total;
            this.first = first;
            this.last = last;
            this.outcomes = Collections.unmodifiableMap(outcomes);
        }

        public SetupStep step() {
            return step;
        }

        public int number() {
            return number;
        }

        public int total() {
            return total;
        }

        public boolean isFirst() {
            return first;
        }

        public boolean isLast() {
            return last;
        }

        public StepOutcome outcomeOf(SetupStep of) {
            StepOutcome outcome = outcomes.get(of);
            return outcome == null ? StepOutcome.PENDING : outcome;
        }

        /** The outcome of the step on screen. */
        public StepOutcome outcome() {
            return outcomeOf(step);
        }
    }
}
