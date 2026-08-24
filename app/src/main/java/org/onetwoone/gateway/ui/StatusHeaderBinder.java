package org.onetwoone.gateway.ui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.core.content.ContextCompat;

import org.onetwoone.gateway.R;
import org.onetwoone.gateway.core.GatewayStatus;

import java.util.Locale;

/**
 * Renders a {@link GatewayStatus} into the persistent status header (GW-41 step 4a).
 *
 * <h2>What this is for</h2>
 *
 * <p>GW-45 published the whole snapshot and nothing consumed it. The pre-GW-41 screen took
 * three fields - {@code isRunning}, {@code isSipRegistered} and the pre-formatted three-line
 * {@code getStatusText()} composite - and dropped the rest. Everything below was reachable
 * and shown nowhere: the three status lines separately, the call state, the call duration,
 * the grace period, the pjsua2 call counters, and all of {@link GatewayStatus.WatchdogFindings}.
 *
 * <p>The watchdog block is the reason this class is worth its weight. GW-25 built
 * silent-bridge detection and orphan termination <em>because those failures are invisible
 * from outside the process</em> - and then the only place a human looks showed none of it. A
 * header that says "the watchdog killed a leg" and "the bridge went silent" is the single
 * highest-value thing on this screen.
 *
 * <h2>The rule about the clock</h2>
 *
 * <p>{@link GatewayStatus#getCallDurationMs()} and {@link GatewayStatus#isInGracePeriod()}
 * re-read the wall clock on <em>every</em> call, by design: the service publishes a new
 * snapshot only on events, so during a quiet call the same object has to be asked again each
 * tick or the screen shows a stopwatch that never advances. This class therefore calls them
 * inside {@link #bind}, at draw time, and <b>caches nothing they return</b> - no field here
 * holds a duration, an age, or a formatted version of either.
 *
 * <p>It holds no reference to a snapshot at all, for the same reason. What it holds is views.
 */
public final class StatusHeaderBinder {

    /** Under this, "12s ago" is noise; say "just now". */
    private static final long AGE_NOW_MS = 10_000L;

    private static final long SECOND_MS = 1_000L;
    private static final long MINUTE_MS = 60_000L;
    private static final long HOUR_MS = 3_600_000L;

    private final Context context;
    private final Resources resources;

    private final TextView sipChip;
    private final TextView callChip;
    private final TextView graceChip;
    private final TextView callDurationText;
    /** The SIP status line. Keeps the id the old three-line composite used. */
    private final TextView statusText;
    private final TextView callStatusText;
    private final TextView audioStatusText;
    private final TextView callCountersText;
    private final TextView watchdogSummaryText;
    private final TextView watchdogFindingText;

    private final float chipCornerPx;

    public StatusHeaderBinder(View root) {
        this.context = root.getContext();
        this.resources = context.getResources();

        sipChip = root.findViewById(R.id.sipChip);
        callChip = root.findViewById(R.id.callChip);
        graceChip = root.findViewById(R.id.graceChip);
        callDurationText = root.findViewById(R.id.callDurationText);
        statusText = root.findViewById(R.id.statusText);
        callStatusText = root.findViewById(R.id.callStatusText);
        audioStatusText = root.findViewById(R.id.audioStatusText);
        callCountersText = root.findViewById(R.id.callCountersText);
        watchdogSummaryText = root.findViewById(R.id.watchdogSummaryText);
        watchdogFindingText = root.findViewById(R.id.watchdogFindingText);

        chipCornerPx = resources.getDimension(R.dimen.gw_corner_chip);
    }

    /**
     * Draw the whole header from one snapshot.
     *
     * @param status           the snapshot as published; never null - {@link GatewayStatus#UNAVAILABLE}
     *                         stands in when there is nothing bound
     * @param serviceConnected whether the ViewModel holds a live binding. This is the one
     *                         thing the snapshot cannot say: a freshly created service
     *                         publishes {@code UNAVAILABLE} too, so "not bound" and "bound but
     *                         idle" are indistinguishable without it.
     */
    public void bind(GatewayStatus status, boolean serviceConnected) {
        final boolean running = status.isRunning();
        final boolean registered = status.isSipRegistered();

        // ---- SIP: the chip and the headline line ----------------------------------------
        if (!serviceConnected) {
            chip(sipChip, R.string.chip_no_service, R.color.gw_state_idle,
                    R.color.gw_state_idle_container);
            statusText.setText(R.string.status_not_connected);
            statusText.setTextColor(color(R.color.gw_state_idle));
        } else if (!running) {
            chip(sipChip, R.string.chip_stopped, R.color.gw_state_idle,
                    R.color.gw_state_idle_container);
            statusText.setText(R.string.status_service_stopped);
            statusText.setTextColor(color(R.color.gw_state_idle));
        } else if (registered) {
            chip(sipChip, R.string.chip_registered, R.color.gw_state_ok,
                    R.color.gw_state_ok_container);
            statusText.setText(status.getSipStatus());
            // Plan §4 hazard H-a: this line and the one below used to be the literal ints
            // 0xFF228B22 and 0xFFCC0000, the app's only state colours, in a place the design
            // system could not reach and values-night could never override.
            statusText.setTextColor(color(R.color.gw_state_ok));
        } else {
            chip(sipChip, R.string.chip_not_registered, R.color.gw_state_error,
                    R.color.gw_state_error_container);
            statusText.setText(status.getSipStatus());
            statusText.setTextColor(color(R.color.gw_state_error));
        }

        // ---- Call: state chip, status line, grace marker, running duration ---------------
        final String callState = status.getCallState();
        callChip.setText(callState);
        if (!serviceConnected || !running || isIdle(callState)) {
            paintChip(callChip, R.color.gw_state_idle, R.color.gw_state_idle_container);
        } else if (isBridged(callState)) {
            paintChip(callChip, R.color.gw_state_ok, R.color.gw_state_ok_container);
        } else {
            // Every other state is a call in transit: worth seeing, not yet worth alarm.
            paintChip(callChip, R.color.gw_state_warn, R.color.gw_state_warn_container);
        }

        callStatusText.setText(status.getCallStatus());
        audioStatusText.setText(status.getAudioStatus());

        // Read at draw time, never cached. See the class javadoc.
        graceChip.setVisibility(status.isInGracePeriod() ? View.VISIBLE : View.GONE);

        long durationMs = status.getCallDurationMs();
        if (durationMs > 0L) {
            callDurationText.setText(formatDuration(durationMs));
            callDurationText.setVisibility(View.VISIBLE);
        } else {
            callDurationText.setVisibility(View.GONE);
        }

        // ---- pjsua2 call objects (GW-22) -------------------------------------------------
        callCountersText.setText(resources.getString(R.string.status_call_objects,
                status.getCallsAlive(), status.getCallsCreated(), status.getCallsDeleted()));
        // Alive must settle at 0 or 1. More than that is either a call in teardown or a
        // CallGraveyard that has stopped deleting, and the screen should not look calm about
        // the second one.
        callCountersText.setTextColor(color(status.getCallsAlive() > 1L
                ? R.color.gw_state_warn
                : R.color.gw_on_surface_faint));

        // ---- Watchdog (GW-25) ------------------------------------------------------------
        bindWatchdog(status.getWatchdog());
    }

    private void bindWatchdog(GatewayStatus.WatchdogFindings watchdog) {
        long terminations = watchdog.getTerminations();
        long silent = watchdog.getSilentBridgeEpisodes();

        if (terminations == 0L && silent == 0L) {
            watchdogSummaryText.setText(R.string.status_watchdog_clean);
            watchdogSummaryText.setTextColor(color(R.color.gw_on_surface_variant));
        } else {
            watchdogSummaryText.setText(
                    resources.getString(R.string.status_watchdog_counts, terminations, silent));
            // A termination means the watchdog tore a leg down. A silent-bridge episode is
            // detection only - GW-25 never terminates on that signal - so it is a warning,
            // not a fault, and the two must not read the same.
            watchdogSummaryText.setTextColor(color(terminations > 0L
                    ? R.color.gw_state_error
                    : R.color.gw_state_warn));
        }

        String lastFinding = watchdog.getLastFinding();
        if (lastFinding == null || lastFinding.isEmpty()) {
            watchdogFindingText.setVisibility(View.GONE);
            return;
        }
        watchdogFindingText.setText(resources.getString(R.string.status_watchdog_last,
                lastFinding, formatAge(watchdog.getLastFindingAtWallMs())));
        watchdogFindingText.setTextColor(color(terminations > 0L
                ? R.color.gw_state_error
                : R.color.gw_state_warn));
        watchdogFindingText.setVisibility(View.VISIBLE);
    }

    // ========== Formatting ==========

    /**
     * {@code MM:SS} up to an hour, {@code H:MM:SS} beyond it.
     *
     * <p>{@link Locale#US} rather than the default: these are digits and colons, and lint's
     * {@code DefaultLocale} is right that a locale-sensitive format for a machine-readable
     * value is a latent bug.
     */
    static String formatDuration(long durationMs) {
        long totalSeconds = durationMs / SECOND_MS;
        long seconds = totalSeconds % 60L;
        long minutes = (totalSeconds / 60L) % 60L;
        long hours = totalSeconds / 3600L;
        if (hours > 0L) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    /** How long ago a wall-clock instant was, in the coarsest unit that still says something. */
    private String formatAge(long wallMs) {
        if (wallMs <= 0L) {
            return resources.getString(R.string.status_age_now);
        }
        long age = System.currentTimeMillis() - wallMs;
        if (age < AGE_NOW_MS) {
            return resources.getString(R.string.status_age_now);
        }
        if (age < MINUTE_MS) {
            return resources.getString(R.string.status_age_seconds, age / SECOND_MS);
        }
        if (age < HOUR_MS) {
            return resources.getString(R.string.status_age_minutes, age / MINUTE_MS);
        }
        return resources.getString(R.string.status_age_hours, age / HOUR_MS);
    }

    // ========== Chips ==========

    private void chip(TextView chip, int labelRes, @ColorRes int fgRes, @ColorRes int bgRes) {
        chip.setText(labelRes);
        paintChip(chip, fgRes, bgRes);
    }

    /**
     * Paint a chip from the palette's state tokens.
     *
     * <p>A fresh {@link GradientDrawable} per call rather than a cached one per state: the
     * chips are repainted once a second on a screen with three of them, the allocation is
     * trivial beside the {@code String.format} on the same path, and sharing a mutable
     * drawable between views is the kind of aliasing bug that shows up as "one chip changed
     * colour when the other did".
     */
    private void paintChip(TextView chip, @ColorRes int fgRes, @ColorRes int bgRes) {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(chipCornerPx);
        background.setColor(color(bgRes));
        chip.setBackground(background);
        chip.setTextColor(color(fgRes));
    }

    private int color(@ColorRes int colorRes) {
        return ContextCompat.getColor(context, colorRes);
    }

    private static boolean isIdle(String callState) {
        return callState == null || callState.isEmpty() || "IDLE".equals(callState);
    }

    private static boolean isBridged(String callState) {
        return "BRIDGED".equals(callState);
    }
}
