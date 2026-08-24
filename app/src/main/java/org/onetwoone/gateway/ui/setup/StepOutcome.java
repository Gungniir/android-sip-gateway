package org.onetwoone.gateway.ui.setup;

/**
 * How one commissioning step ended (GW-42).
 *
 * <p>Four values, and the important one is {@link #SKIPPED}. A wizard on an appliance you may
 * be recovering in the field must be skippable at every step, and "skipped" is a real answer -
 * not a failure and not a success. Collapsing it into either would either nag the operator
 * forever or claim a check passed that nobody ran.
 */
public enum StepOutcome {

    /** Not attempted yet. The state every step starts in, and the only one that means nothing. */
    PENDING,

    /** The step's check ran and said yes. */
    PASSED,

    /**
     * The step's check ran and said no.
     *
     * <p><b>Never blocks.</b> A failed step is information: the wizard reports what was tried
     * and what came back, and Next stays enabled. The failure this issue exists to avoid is a
     * half-provisioned phone standing behind a wizard that will not let go of it.
     */
    FAILED,

    /** The operator moved past the step without running it. Counts as resolved, not as passed. */
    SKIPPED;

    /** Whether the operator has dealt with this step at all, however they dealt with it. */
    public boolean isResolved() {
        return this != PENDING;
    }
}
