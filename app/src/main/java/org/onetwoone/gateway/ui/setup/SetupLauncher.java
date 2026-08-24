package org.onetwoone.gateway.ui.setup;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import org.onetwoone.gateway.config.GatewayConfig;

/**
 * The only two ways the commissioning wizard is opened (GW-42).
 *
 * <h2>Why the first-run decision lives here and not in {@code MainActivity}</h2>
 *
 * <p>GW-41 left {@code MainActivity} with a property worth keeping: it reads no
 * {@code SharedPreferences} at all, and every configuration value it shows arrives through
 * {@code MainViewModel}. A first-run check written into {@code onCreate} would have been the
 * one exception, and exceptions to that rule are how the web interface came to write a key
 * nothing read (AUDIT H4). So the main screen calls {@link #launchIfFirstRun(Activity)} and
 * knows nothing about the flag behind it; the wizard's own package owns the question of
 * whether the wizard should open.
 *
 * <h2>What "first run" means</h2>
 *
 * <p>{@code GatewayConfig.isSetupCompleted()}, and nothing else. Specifically <b>not</b>
 * "is the gateway configured": a handset provisioned entirely from the web interface has a
 * working SIP account and has never seen the wizard, and opening it unasked on such a device
 * would be a wizard appearing on a working appliance. The two facts are independent and are
 * kept independent.
 *
 * <p>The flag is set on <em>any</em> dismissal the operator chose - finishing, skipping
 * through, or closing - because "skipping counts as done". A wizard that keeps reappearing
 * until every step is completed is the same trap as one that blocks, reached by a different
 * road: on the half-provisioned phone that cannot complete a step, it never goes away.
 *
 * <p>What does <em>not</em> set it is the process dying mid-wizard. That is not a dismissal,
 * and offering the wizard once more after a crash costs one tap.
 *
 * <h2>Re-running</h2>
 *
 * <p>{@link #launch(Activity)} is the System-section button, and it is unconditional: any
 * number of times, on a configured gateway or an empty one. Nothing about re-running discards
 * anything - see {@code SetupViewModel.saveSipAccount}, which will not replace a stored value
 * with a blank one.
 */
public final class SetupLauncher {

    private SetupLauncher() {
    }

    /** An intent for the wizard, for a caller that wants to start it its own way. */
    public static Intent intent(Context context) {
        return new Intent(context, SetupActivity.class);
    }

    /**
     * Open the wizard because this handset has never been through it.
     *
     * <p>Called from {@code MainActivity.onCreate} on a fresh launch only. The wizard opens
     * <em>over</em> the main screen rather than instead of it, so closing it - by the Close
     * button, by system back, or by anything else - lands on a usable console. That is the
     * whole of "never gates the main screen": there is no state in which the wizard is the
     * only thing on the stack.
     *
     * @return true if the wizard was started
     */
    public static boolean launchIfFirstRun(Activity host) {
        if (host == null || GatewayConfig.from(host).isSetupCompleted()) {
            return false;
        }
        launch(host);
        return true;
    }

    /** Open the wizard because someone asked for it. Always allowed. */
    public static void launch(Activity host) {
        host.startActivity(intent(host));
    }
}
