package org.onetwoone.gateway.ui;

import android.view.View;
import android.widget.TextView;

import org.onetwoone.gateway.R;

/**
 * One collapsible group on the main screen: a tappable title row, a chevron, and a body
 * whose visibility follows a {@code boolean} (GW-41 step 4c).
 *
 * <p>The old screen was 14 unlabelled groups stacked in declaration order inside one
 * {@code ScrollView} - which is fine when you know what you are looking for and hostile when
 * you do not. Collapsing them is what lets a status header stay pinned at the top of a phone
 * screen with four sections under it instead of a column you scroll through hunting for the
 * one control you came for.
 *
 * <p><b>This class owns no state.</b> Whether a section is open is view state and lives in
 * {@code MainViewModel} (plan §4 hazard H-d, generalised): the activity observes it and calls
 * {@link #setExpanded}, and a tap on the header asks the ViewModel to toggle rather than
 * flipping a field here. That is what makes the screen survive a configuration change - a
 * night-mode switch recreates the activity, and a section that closed itself on rotation
 * would be the same class of bug as the toast that fires twice.
 */
public final class CollapsibleSection {

    private final TextView chevron;
    private final View body;

    private CollapsibleSection(View header, TextView chevron, View body, Runnable onToggle) {
        this.chevron = chevron;
        this.body = body;
        header.setOnClickListener(v -> onToggle.run());
    }

    /**
     * Wire a section up.
     *
     * @param header   the tappable title row
     * @param chevron  the affordance at its right-hand end
     * @param body     everything the row hides and shows
     * @param onToggle what a tap means - in practice {@code viewModel::toggleSection}
     */
    public static CollapsibleSection attach(View header, TextView chevron, View body,
                                            Runnable onToggle) {
        return new CollapsibleSection(header, chevron, body, onToggle);
    }

    /** Show or hide the body and turn the chevron to match. */
    public void setExpanded(boolean expanded) {
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        chevron.setText(expanded
                ? R.string.label_section_expanded
                : R.string.label_section_collapsed);
    }
}
