package org.onetwoone.gateway.ui.setup;

import org.onetwoone.gateway.R;

/**
 * The five things that have to be true before a handset is a gateway (GW-42).
 *
 * <p>The order is a dependency order, not a preference: without root nothing else on this list
 * can be done from the phone at all; without the runtime permissions the service cannot read
 * the modem; without the dialer role {@code GatewayInCallService} is never bound and no GSM
 * call is ever seen; without a SIP account there is nothing to register; and verification can
 * only be attempted once the four before it have been.
 *
 * <p><b>Every one of them is still skippable.</b> The order says what depends on what, not what
 * the wizard will insist on - see {@link SetupStepMachine}.
 *
 * <p>Each constant carries the string resources its header renders and the id of the body view
 * that belongs to it. Holding the ids here rather than in a {@code switch} in the activity is
 * what makes "a step with no body" a compile error instead of a blank screen.
 */
public enum SetupStep {

    /** {@code su} works at all. Everything else on this list is downstream of it. */
    ROOT(R.string.setup_step_root_title, R.string.setup_step_root_summary,
            R.id.setupStepRootBody),

    /** The six runtime permissions the gateway cannot work without, granted via root. */
    PERMISSIONS(R.string.setup_step_permissions_title, R.string.setup_step_permissions_summary,
            R.id.setupStepPermissionsBody),

    /** The dialer role. Without it the InCallService is never bound and GSM calls vanish. */
    DIALER(R.string.setup_step_dialer_title, R.string.setup_step_dialer_summary,
            R.id.setupStepDialerBody),

    /** Server, port, credentials, realm, TLS, and where each SIM's calls are sent. */
    SIP_ACCOUNT(R.string.setup_step_sip_title, R.string.setup_step_sip_summary,
            R.id.setupStepSipBody),

    /**
     * What can actually be proven from the handset.
     *
     * <p>Read {@link SetupViewModel}'s class javadoc before changing this step: the obvious
     * design - dial {@code *43}, the FreePBX echo test - is field-known not to work on this
     * deployment, and a verification step that fails on a correctly configured gateway is the
     * worst outcome a commissioning wizard has.
     */
    VERIFY(R.string.setup_step_verify_title, R.string.setup_step_verify_summary,
            R.id.setupStepVerifyBody);

    private final int titleRes;
    private final int summaryRes;
    private final int bodyViewId;

    SetupStep(int titleRes, int summaryRes, int bodyViewId) {
        this.titleRes = titleRes;
        this.summaryRes = summaryRes;
        this.bodyViewId = bodyViewId;
    }

    public int titleRes() {
        return titleRes;
    }

    public int summaryRes() {
        return summaryRes;
    }

    /** The id of this step's body in {@code activity_setup.xml}. */
    public int bodyViewId() {
        return bodyViewId;
    }
}
