package org.onetwoone.gateway.ui.setup;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.TextView;

import org.onetwoone.gateway.R;

/**
 * A one-word verdict beside a step, in GW-40's chip vocabulary (GW-42).
 *
 * <h2>Why it inflates instead of recolouring</h2>
 *
 * <p>GW-40 defined the four chip states as <em>styles</em> -
 * {@code Widget.Gateway.StatusChip.Ok} / {@code .Warn} / {@code .Error} / {@code .Idle} - and
 * a style cannot be swapped on a view that has already been inflated. GW-41's status header
 * therefore builds its chips' backgrounds programmatically from the palette tokens, which
 * works but leaves the two styles it does not use ({@code .Ok}, {@code .Error}) defined and
 * unconsumed.
 *
 * <p>This holder inflates the styled chip for the state instead, so every one of the four
 * states is a resource the design system owns rather than a colour this class knows. The
 * background, the corner radius, the text colour and the type step all come from the style,
 * and {@code values-night} moves them without this file changing.
 *
 * <p>Re-inflation happens only when the state actually changes, which matters because the
 * verification step re-renders once a second: a chip that is still {@code Ok} keeps its view
 * and only has its text replaced.
 */
final class SetupChip {

    private final ViewGroup host;

    private StepOutcome shown;

    SetupChip(ViewGroup host) {
        this.host = host;
    }

    /** Draw {@code outcome} with {@code text} on it. */
    void set(StepOutcome outcome, CharSequence text) {
        if (shown != outcome || host.getChildCount() == 0) {
            host.removeAllViews();
            LayoutInflater.from(host.getContext()).inflate(layoutFor(outcome), host, true);
            shown = outcome;
        }
        ((TextView) host.getChildAt(0)).setText(text);
    }

    /** The state currently on screen, for a test that wants to know which style was used. */
    StepOutcome shown() {
        return shown;
    }

    private static int layoutFor(StepOutcome outcome) {
        switch (outcome) {
            case PASSED:
                return R.layout.gw_chip_ok;
            case FAILED:
                return R.layout.gw_chip_error;
            case SKIPPED:
                return R.layout.gw_chip_warn;
            case PENDING:
            default:
                return R.layout.gw_chip_idle;
        }
    }
}
