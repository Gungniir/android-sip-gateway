package org.onetwoone.gateway.sip;

import android.os.Handler;
import android.util.Log;

import org.onetwoone.gateway.config.GatewayConfig;
import org.onetwoone.gateway.core.ControlThread;
import org.onetwoone.gateway.core.GatewayControlThread;

/**
 * Implements exponential backoff reconnection strategy.
 *
 * When connection fails, waits progressively longer before retrying:
 * 5s -> 10s -> 20s -> 40s -> 60s (max)
 *
 * Success resets the delay back to initial value.
 *
 * <h3>Threading (GW-15)</h3>
 * The timer used to run on the main looper while its state was poked from the control thread,
 * from main and from the broadcast receiver; {@code volatile} made those reads defined but
 * left the {@code pending} check-then-set racy - two callers could both observe
 * {@code pending == false} and queue two reconnects (AUDIT F6).
 *
 * <p>Now the whole object lives on the {@link GatewayControlThread}: the {@link Handler} is
 * built on that looper, {@link #reconnectAction} therefore runs there (which is what
 * {@code attemptReconnect} needs anyway), and every mutator asserts it. The race disappears
 * without a lock and without {@code volatile} - single-threaded confinement, same remedy the
 * rest of Phase 1 uses.
 *
 * <p>The handler is this object's own, not the control thread's, so {@link #cancel()} can use
 * {@code removeCallbacksAndMessages(null)} without touching anything else queued on the
 * control looper. The price of owning the handler is that the timer reaches the control thread
 * without passing through {@link GatewayControlThread#post(Runnable)}, and therefore without
 * its lazy pjlib registration - so the action is run through
 * {@link GatewayControlThread#runOrPost(Runnable)}, which dispatches it inline <em>and</em>
 * registers. See {@link #scheduleReconnect()} for the failure this prevents.
 */
@ControlThread
public class ReconnectionStrategy {
    private static final String TAG = "Reconnect";

    private final GatewayControlThread control;
    private final Handler handler;
    private final Runnable reconnectAction;

    // Confined to the control thread - deliberately NOT volatile. Every reader and writer
    // below asserts that thread, and the timer fires on it too, so the check-then-set in
    // scheduleReconnect() is atomic by confinement.
    private int currentDelay;
    private boolean enabled = true;
    private boolean pending = false;

    public ReconnectionStrategy(GatewayControlThread control, Runnable reconnectAction) {
        this.control = control;
        this.handler = new Handler(control.getLooper());
        this.reconnectAction = reconnectAction;
        this.currentDelay = GatewayConfig.RECONNECT_INITIAL_DELAY_MS;
    }

    /**
     * Schedule a reconnection attempt with current delay.
     * Uses exponential backoff - each call increases the delay.
     */
    @ControlThread
    public void scheduleReconnect() {
        control.assertOnControlThread("scheduleReconnect");
        if (!enabled) {
            Log.d(TAG, "Reconnection disabled, skipping");
            return;
        }

        if (pending) {
            Log.d(TAG, "Reconnection already pending, skipping");
            return;
        }

        Log.d(TAG, "Scheduling reconnection in " + currentDelay + "ms");
        pending = true;

        handler.postDelayed(() -> {
            pending = false;
            if (enabled && reconnectAction != null) {
                Log.d(TAG, "Executing reconnection");
                // runOrPost, not run(): this timer reaches the control thread through our own
                // Handler, which bypasses GatewayControlThread.dispatch() and therefore its
                // lazy pjlib registration. That matters on exactly the path this timer exists
                // for - a SIP init that created the Endpoint and then failed before
                // registerWithPjlib(), leaving a non-null endpoint and an unregistered control
                // thread. attemptReconnect() calls hasTransport() on it, and pjsua aborts the
                // process for an unknown thread. We are already on the control thread here, so
                // this runs inline; the only thing it adds is the registration attempt.
                control.runOrPost(reconnectAction);
            }
        }, currentDelay);

        // Increase delay for next attempt (exponential backoff)
        currentDelay = Math.min(
            currentDelay * GatewayConfig.RECONNECT_MULTIPLIER,
            GatewayConfig.RECONNECT_MAX_DELAY_MS
        );
    }

    /**
     * Called when connection succeeds.
     * Resets the delay back to initial value.
     *
     * <p>AUDIT F6c: this used to clear {@code pending} without disarming the timer, so an
     * already-scheduled runnable still fired and sent a redundant re-REGISTER - and
     * {@link #isPending()} disagreed with what was actually armed. Clearing the queue is what
     * makes the flag mean what it says.
     */
    @ControlThread
    public void onSuccess() {
        control.assertOnControlThread("onSuccess");
        Log.d(TAG, "Connection successful, resetting delay");
        currentDelay = GatewayConfig.RECONNECT_INITIAL_DELAY_MS;
        handler.removeCallbacksAndMessages(null);
        pending = false;
    }

    /**
     * Cancel any pending reconnection.
     */
    @ControlThread
    public void cancel() {
        control.assertOnControlThread("cancel");
        Log.d(TAG, "Cancelling pending reconnection");
        handler.removeCallbacksAndMessages(null);
        pending = false;
    }

    /**
     * Enable or disable reconnection.
     */
    @ControlThread
    public void setEnabled(boolean enabled) {
        control.assertOnControlThread("setEnabled");
        this.enabled = enabled;
        if (!enabled) {
            cancel();
        }
    }

    /**
     * Check if reconnection is enabled.
     */
    @ControlThread
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Check if reconnection is pending.
     */
    @ControlThread
    public boolean isPending() {
        return pending;
    }

    /**
     * Get current delay for debugging.
     */
    @ControlThread
    public int getCurrentDelay() {
        return currentDelay;
    }

    /**
     * Reset delay to initial value without waiting for success.
     */
    @ControlThread
    public void resetDelay() {
        control.assertOnControlThread("resetDelay");
        currentDelay = GatewayConfig.RECONNECT_INITIAL_DELAY_MS;
    }
}
