package org.onetwoone.gateway.sip;

import android.os.Handler;
import android.util.Log;

import org.onetwoone.gateway.config.GatewayConfig;
import org.onetwoone.gateway.core.ControlThread;
import org.onetwoone.gateway.core.GatewayControlThread;

/**
 * Watchdog service that periodically checks for orphaned calls.
 *
 * An orphaned call is a SIP call that exists without a corresponding GSM call,
 * or vice versa. This can happen due to timing issues or crashes.
 *
 * The watchdog runs every WATCHDOG_INTERVAL_MS and calls the check callback.
 *
 * <h3>Threading (GW-15)</h3>
 * Timer and state both live on the {@link GatewayControlThread}. It used to tick on the main
 * looper and hop to the control thread for the check itself; running the tick there directly
 * removes the hop, and - more to the point - puts the check in the same queue as the call and
 * registration events it is inspecting, so it can no longer observe a half-applied teardown.
 *
 * <p>The handler is this object's own, on the control looper, so {@link #stop()} removes only
 * this watchdog's message. The price of owning the handler is that the tick reaches the control
 * thread without passing through {@link GatewayControlThread#post(Runnable)}, and so without
 * its lazy pjlib registration - which the check needs, because it can terminate calls. Both the
 * tick and {@link #checkNow()} therefore go through the control thread rather than calling the
 * callback directly.
 */
@ControlThread
public class ServiceWatchdog {
    private static final String TAG = "Watchdog";

    private final GatewayControlThread control;
    private final Handler handler;
    private final Runnable checkCallback;
    private final Runnable watchdogRunnable;

    /**
     * Confined to the control thread - deliberately NOT volatile, see
     * {@link GatewayControlThread#assertOnControlThread(String)}. {@link #start()} and
     * {@link #stop()} are posted there by {@code PjsipSipService.onStartCommand} /
     * {@code onDestroy}, and {@link #watchdogRunnable} runs on {@link #handler}, which is the
     * control looper. The check-then-set in {@code start()} is only atomic while that holds
     * (AUDIT H5).
     */
    private boolean running = false;

    public ServiceWatchdog(GatewayControlThread control, Runnable checkCallback) {
        this.control = control;
        this.handler = new Handler(control.getLooper());
        this.checkCallback = checkCallback;

        this.watchdogRunnable = new Runnable() {
            @Override
            public void run() {
                if (!running) {
                    return;
                }

                try {
                    if (checkCallback != null) {
                        // runOrPost, not run(): the tick reaches the control thread through our
                        // own Handler, bypassing GatewayControlThread.dispatch() and its lazy
                        // pjlib registration. The check can terminate calls, i.e. call pjsua2,
                        // and pjsua aborts the process for a thread pjlib has never seen. We
                        // are already on the control thread, so this runs inline and the
                        // exception still lands in the catch below.
                        control.runOrPost(checkCallback);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Watchdog check failed: " + e.getMessage());
                }

                // Schedule next check
                if (running) {
                    handler.postDelayed(this, GatewayConfig.WATCHDOG_INTERVAL_MS);
                }
            }
        };
    }

    /**
     * Start the watchdog.
     */
    @ControlThread
    public void start() {
        control.assertOnControlThread("start");
        if (running) {
            Log.d(TAG, "Watchdog already running");
            return;
        }

        running = true;
        handler.postDelayed(watchdogRunnable, GatewayConfig.WATCHDOG_INTERVAL_MS);
        Log.d(TAG, "Watchdog started (interval: " + GatewayConfig.WATCHDOG_INTERVAL_MS + "ms)");
    }

    /**
     * Stop the watchdog.
     */
    @ControlThread
    public void stop() {
        control.assertOnControlThread("stop");
        if (!running) {
            return;
        }

        running = false;
        handler.removeCallbacks(watchdogRunnable);
        Log.d(TAG, "Watchdog stopped");
    }

    /**
     * Check if watchdog is running.
     */
    @ControlThread
    public boolean isRunning() {
        return running;
    }

    /**
     * Trigger an immediate check (in addition to scheduled checks).
     *
     * <p>Callable from any thread - it only posts, and the callback runs on the control
     * thread like every other tick.
     */
    public void checkNow() {
        if (checkCallback != null) {
            // control.post, not handler.post: this is the one entry point callable from a
            // foreign thread, so it must go through GatewayControlThread.dispatch() and pick up
            // the pjlib registration the check may need.
            control.post(checkCallback);
        }
    }
}
