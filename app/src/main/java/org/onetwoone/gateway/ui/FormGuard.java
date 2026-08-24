package org.onetwoone.gateway.ui;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Keeps a rebindable input from being overwritten while someone is editing it (GW-41, plan
 * §4 hazard H-b).
 *
 * <h2>What went wrong without it</h2>
 *
 * <p>The pre-GW-41 screen guarded exactly one field:
 *
 * <pre>{@code
 * viewModel.getManualMuteControls().observe(this, controls -> {
 *     if (controls != null && !manualMuteControlsEdit.hasFocus()) {   // the only guard
 *         manualMuteControlsEdit.setText(controls);
 *     }
 * });
 * }</pre>
 *
 * <p>The {@code getSipConfig()} observer rewrote <b>eight</b> fields with no guard at all,
 * and it fires whenever the config generation moves - which since GW-14 includes <em>a save
 * from the web interface while someone is typing on the phone</em>. GW-14 deleted the
 * activity relaunch precisely so the phone-holder's work would survive a remote save; that
 * observer threw it away anyway, one field at a time.
 *
 * <h2>Why dirty tracking and not just hasFocus()</h2>
 *
 * <p>A {@code hasFocus()} check protects the field the cursor is in <em>at that instant</em>,
 * and nothing else. Take the real case: someone types a server, a port and a username, then
 * taps into the password field. A web save lands. Focus is on the password, so the password
 * survives - and the three fields they just filled in are silently replaced by whatever the
 * remote config says. They then press Save and persist a config they did not write.
 *
 * <p>So this guard is per-field and lasts for the whole editing session: a field the user has
 * typed into is <b>dirty</b> and is not rebound at all until the edit is resolved. Both
 * conditions are checked - {@code hasFocus()} still counts, because a field can be focused
 * before its first keystroke.
 *
 * <p>The counterweight to a sticky guard is that it must be released, or a remote change
 * would stop reaching the screen forever. Two things release it, both of them moments where
 * the user's value has stopped being unsaved work:
 *
 * <ul>
 *   <li>{@link #clean(View...)} after a successful save - what is on screen is now what is
 *       persisted, so there is nothing left to protect;
 *   <li>{@link #reset()} when the screen goes away.
 * </ul>
 *
 * <h2>Programmatic writes</h2>
 *
 * <p>Every write this class performs is wrapped in a {@link #isBinding()} window, so the
 * listeners it drives can tell "the ViewModel is repainting me" from "a human touched me".
 * That matters for the compound buttons: the incoming-call-mode radio group and the battery
 * limit write straight through to {@code GatewayConfig} on change, and without the window a
 * repaint would write the value back to config as if the user had chosen it.
 *
 * <p><b>Spinners are deliberately not handled here.</b> {@code Spinner.setSelection()} does
 * not deliver its callback synchronously, so a binding window around it would already have
 * closed by the time {@code onItemSelected} fires - the exact shape of the old screen's
 * {@code isRefreshing} flag. Spinner selections are made idempotent in the ViewModel instead:
 * a set-to-the-same-value is a no-op, so a spurious callback cannot do anything.
 *
 * <p>Main-thread only, like the views it guards.
 */
public final class FormGuard {

    /**
     * Fields the user has typed into since the last {@link #clean}. Weak keys so a guard that
     * outlives a view - it does not, but the ownership is worth stating - cannot pin it.
     */
    private final Set<View> dirty =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    private boolean binding;

    /**
     * Start watching an {@link EditText}: any change the user makes marks it dirty.
     *
     * <p>A {@code TextWatcher} is additive, so this does not compete with anything else the
     * screen wants to observe on the field.
     */
    public void watch(final EditText field) {
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!binding) {
                    dirty.add(field);
                }
            }
        });
    }

    /**
     * Start watching a {@link CompoundButton}, forwarding real user changes to
     * {@code delegate} and swallowing the ones this guard caused itself.
     *
     * <p>A compound button has a single listener slot, so the guard has to own it and pass
     * the change on rather than sitting beside it.
     *
     * @param delegate what to do when a <em>person</em> changes the control; may be null for
     *                 a control that is only read at save time (the TLS checkbox)
     */
    public void watch(final CompoundButton button, final CompoundButton.OnCheckedChangeListener delegate) {
        button.setOnCheckedChangeListener((view, checked) -> {
            if (binding) {
                return;
            }
            dirty.add(button);
            if (delegate != null) {
                delegate.onCheckedChanged(view, checked);
            }
        });
    }

    /** Whether this field currently holds unsaved user input. */
    public boolean isDirty(View field) {
        return dirty.contains(field);
    }

    /**
     * Write {@code value} into {@code field} unless the user is editing it.
     *
     * @return true if the field was actually written
     */
    public boolean bind(EditText field, String value) {
        if (isBlocked(field)) {
            return false;
        }
        binding = true;
        try {
            field.setText(value == null ? "" : value);
        } finally {
            binding = false;
        }
        return true;
    }

    /**
     * Set {@code button} unless the user has changed it since the last save.
     *
     * @return true if the button was actually written
     */
    public boolean bind(CompoundButton button, boolean checked) {
        if (isBlocked(button)) {
            return false;
        }
        binding = true;
        try {
            button.setChecked(checked);
        } finally {
            binding = false;
        }
        return true;
    }

    /**
     * Run {@code write} inside a binding window, so any listener it trips can recognise the
     * change as programmatic. For the radio groups, whose listener is on the group rather
     * than on the buttons this guard watches.
     */
    public void bindQuietly(Runnable write) {
        binding = true;
        try {
            write.run();
        } finally {
            binding = false;
        }
    }

    /**
     * Whether a programmatic write is in progress. Listeners that write through to
     * {@code GatewayConfig} must consult this before doing anything.
     */
    public boolean isBinding() {
        return binding;
    }

    /**
     * Forget the unsaved-input marker on these fields - call it after a save, when what is on
     * screen and what is persisted are the same thing again.
     */
    public void clean(View... fields) {
        for (View field : fields) {
            dirty.remove(field);
        }
    }

    /** Forget every marker. */
    public void reset() {
        dirty.clear();
    }

    private boolean isBlocked(View field) {
        return field.hasFocus() || dirty.contains(field);
    }
}
