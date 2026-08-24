package org.onetwoone.gateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import org.onetwoone.gateway.config.GatewayConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Service to monitor and limit battery charging level.
 * Requires root access to control charging via sysfs.
 *
 * <h3>Threading model (see docs/refactor/issues/GW-05, AUDIT B4)</h3>
 *
 * Exactly one thread — the {@code BatteryControl} {@link HandlerThread} — ever writes the
 * charging sysfs nodes. Decisions are made from three contexts (the battery
 * {@link BroadcastReceiver} on main, the enforce watchdog on the control thread, and
 * {@link #onStartCommand} on main) but none of them writes sysfs directly: each records the
 * <em>desired state</em> in {@link #desiredChargingEnabled} and asks for a reconcile.
 * {@link #reconcileCharging()} re-reads the desired state immediately before writing, so a
 * decision that was superseded while it sat in the queue is dropped rather than applied late.
 * That is what makes a stale "disable" unable to land on top of a fresh "enable".
 *
 * <p>Safety invariant: <b>the device must never be left unable to charge.</b> Every service exit
 * path force-enables charging ({@link #onDestroy()}), a fail-safe deadline
 * ({@link #MAX_DISABLE_MS}) force-enables if charging was disabled for too long, and
 * {@link BatteryWatchdog} force-enables out-of-process if this service is killed outright.
 */
public class BatteryLimitService extends Service {
    private static final String TAG = "BatteryLimit";
    private static final String CHANNEL_ID = "battery_limit_channel";
    private static final int NOTIFICATION_ID = 4;

    // Charging control paths (varies by device)
    // We try ALL paths to handle both USB and AC charging
    private static final String[][] CHARGING_PATHS = {
        // {path, value_to_disable, value_to_enable}
        {"/sys/class/power_supply/battery/input_suspend", "1", "0"},  // inverted logic
        {"/sys/class/power_supply/battery/charging_enabled", "0", "1"},
        {"/sys/class/power_supply/usb/charging_enabled", "0", "1"},
        {"/sys/class/power_supply/dc/charging_enabled", "0", "1"},
        {"/sys/class/power_supply/ac/charging_enabled", "0", "1"},
        {"/sys/class/power_supply/main/charging_enabled", "0", "1"},
        {"/sys/class/power_supply/pc_port/charging_enabled", "0", "1"},
    };

    // Hysteresis to avoid rapid on/off (5% below limit to resume)
    private static final int HYSTERESIS = 5;

    // CRITICAL: Never disable charging below this level to prevent brick
    private static final int CRITICAL_BATTERY_LEVEL = 20;

    // SAFETY: Force re-enable charging if battery drops below this (deep discharge protection)
    private static final int DEEP_DISCHARGE_LEVEL = 50;

    // Watchdog to re-enforce charging state (Android/kernel resets it frequently)
    // Must be aggressive (5s) because kernel resets charging_enabled constantly
    private static final int ENFORCE_INTERVAL_MS = 5000;  // 5 seconds

    /**
     * FAIL-SAFE: charging may never stay disabled longer than this, whatever the decision logic
     * believes. This is the backstop for any interleaving not anticipated by the state machine —
     * a gateway phone that stops charging while unattended eventually powers off for good.
     */
    private static final long MAX_DISABLE_MS = 12L * 60L * 60L * 1000L;  // 12 hours

    /**
     * After {@link #MAX_DISABLE_MS} trips, refuse to disable charging again for this long so the
     * forced re-enable is an actual recovery window and not a single write the next enforce tick
     * immediately undoes.
     */
    private static final long DEADLINE_RECOVERY_MS = 5L * 60L * 1000L;  // 5 minutes

    /** Written from onStartCommand (main), read from the control thread. */
    private volatile int chargeLimit = 60;  // Default 60%

    /** Mirrors the last value successfully written to sysfs. Read from main and control. */
    private volatile boolean chargingDisabled = false;

    /**
     * The state the control thread should converge sysfs onto. Written by every decision site,
     * re-read by {@link #reconcileCharging()} right before the write.
     */
    private volatile boolean desiredChargingEnabled = true;

    /**
     * Built once on the control thread by {@link #findChargingPaths()} and then published by a
     * single volatile write of an unmodifiable copy. The element arrays are references into the
     * static {@link #CHARGING_PATHS} table and are never mutated.
     * Package-private so the unit test can assert safe publication.
     */
    volatile List<String[]> activeChargingPaths = Collections.emptyList();

    /**
     * {@link SystemClock#elapsedRealtime()} at which charging was most recently disabled, or 0
     * when charging is enabled. Only mutated on the control thread and in the force-enable path.
     */
    private volatile long disabledSinceElapsedMs = 0L;

    /** Deadline recovery window; disable decisions are ignored until this elapsedRealtime. */
    private volatile long suppressDisableUntilElapsedMs = 0L;

    private int lastBatteryLevel = -1;   // main thread only
    private volatile boolean receiverRegistered = false;
    private volatile boolean destroyed = false;

    private Handler mainHandler;

    /** The one and only thread allowed to write charging sysfs nodes. */
    private HandlerThread controlThread;
    private volatile Handler controlHandler;

    private BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int percent = (level * 100) / scale;

                int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                      status == BatteryManager.BATTERY_STATUS_FULL);

                handleBatteryLevel(percent, isCharging);
            } catch (Exception e) {
                Log.e(TAG, "Error in battery receiver: " + e.getMessage());
            }
        }
    };

    /** Single reconcile task. Always re-reads {@link #desiredChargingEnabled}. */
    private final Runnable reconcileRunnable = new Runnable() {
        @Override
        public void run() {
            reconcileCharging();
        }
    };

    /** Periodic enforcement, running on the control thread and re-posting itself. */
    private final Runnable enforceRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed) {
                return;
            }
            try {
                runEnforceTick();
            } catch (Exception e) {
                Log.e(TAG, "Error in enforce watchdog: " + e.getMessage());
            } finally {
                Handler h = controlHandler;
                if (!destroyed && h != null) {
                    h.postDelayed(this, ENFORCE_INTERVAL_MS);
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        mainHandler = new Handler(Looper.getMainLooper());

        // SAFETY: create the control thread BEFORE anything that can fail and bail out.
        // onDestroy's force-enable escape hatch must exist from the very first instruction that
        // could lead to stopSelf() — including the startForeground failure a few lines below.
        controlThread = new HandlerThread("BatteryControl");
        controlThread.start();
        controlHandler = new Handler(controlThread.getLooper());

        // CRITICAL: Start foreground IMMEDIATELY to avoid ANR/crash
        // Android gives only 5 seconds after startForegroundService()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                startForegroundWithNotification();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start foreground: " + e.getMessage());
                showToast("Battery service error: " + e.getMessage());
                // onDestroy() follows and force-enables charging on all known paths, so a device
                // left non-charging by a previous instance still recovers here.
                stopSelf();
                return;
            }
        }

        // Load saved limit (fast, do before slow operations)
        try {
            chargeLimit = GatewayConfig.from(this).getBatteryLimit();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load prefs: " + e.getMessage());
            chargeLimit = GatewayConfig.DEFAULT_BATTERY_LIMIT;
        }

        // Slow initialization (root probing) runs on the control thread, so it is ordered ahead of
        // every decision that could be made while it is still running.
        controlHandler.post(new Runnable() {
            @Override
            public void run() {
                initializeOnControlThread();
            }
        });
    }

    /**
     * Probe the charging control paths and restore a sane charging state after a restart.
     * Runs on the control thread.
     */
    private void initializeOnControlThread() {
        try {
            if (destroyed) {
                return;
            }

            // Find ALL working charging control paths
            findChargingPaths();

            if (destroyed) {
                // Probing is slow (one su per candidate node); the service can be torn down while
                // it runs. onDestroy has already force-enabled — do not decide anything on top.
                return;
            }

            // Get current battery level to determine correct state
            IntentFilter batteryFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = registerReceiver(null, batteryFilter);
            int level = -1;
            if (batteryStatus != null) {
                int rawLevel = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                level = (rawLevel * 100) / scale;
            }

            // Restore correct charging state based on battery level and limit
            if (level >= 0) {
                Log.i(TAG, "Service restart: battery=" + level + "%, limit=" + chargeLimit + "%");

                if (level < CRITICAL_BATTERY_LEVEL) {
                    // SAFETY: Battery critically low - always enable charging
                    Log.w(TAG, "SAFETY on restart: Battery " + level + "% < critical, enabling charging");
                    forceEnableCharging();
                } else if (chargeLimit >= 100) {
                    // No limit - enable charging
                    Log.d(TAG, "No limit on restart, enabling charging");
                    forceEnableCharging();
                } else if (level >= chargeLimit) {
                    // Above limit - disable charging
                    Log.i(TAG, "Battery " + level + "% >= limit " + chargeLimit + "%, disabling charging");
                    requestCharging(false);
                } else if (level <= (chargeLimit - HYSTERESIS)) {
                    // Below threshold - enable charging
                    Log.i(TAG, "Battery " + level + "% below threshold, enabling charging");
                    requestCharging(true);
                } else {
                    // In hysteresis zone - read actual state and sync flag
                    boolean actuallyCharging = isActuallyCharging();
                    desiredChargingEnabled = actuallyCharging;
                    setDisabledFlag(!actuallyCharging);
                    Log.d(TAG, "In hysteresis zone, syncing with actual state: disabled=" + chargingDisabled);
                }
            } else {
                // Can't determine level - be safe and enable
                Log.w(TAG, "Cannot determine battery level on restart, enabling charging for safety");
                forceEnableCharging();
            }

            // Register the battery receiver on main: onReceive must stay short (broadcast timeout),
            // and it only records a decision — the sysfs write happens back on the control thread.
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (destroyed) {
                        return;   // onDestroy already ran on this same looper; do not re-register
                    }
                    try {
                        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                        registerReceiver(batteryReceiver, filter);
                        receiverRegistered = true;
                        Log.d(TAG, "Battery receiver registered");
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to register receiver: " + e.getMessage());
                    }
                }
            });

            // Update notification with actual status
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    updateNotification();
                }
            });

            // Start periodic enforcement watchdog
            startEnforceWatchdog();

            Log.d(TAG, "BatteryLimitService initialized, limit: " + chargeLimit + "%, disabled: " + chargingDisabled);

        } catch (Exception e) {
            Log.e(TAG, "Error during initialization: " + e.getMessage());
            showToast("Battery limit init error: " + e.getMessage());
        }
    }

    /**
     * Start watchdog that periodically checks and corrects charging state.
     * This is critical for recovering from crashes/restarts and kernel resets.
     * Philosophy: Better to charge to 100% on glitch than to have dead battery.
     */
    private void startEnforceWatchdog() {
        Handler h = controlHandler;
        if (h == null || destroyed) {
            return;
        }
        h.removeCallbacks(enforceRunnable);
        h.postDelayed(enforceRunnable, ENFORCE_INTERVAL_MS);
        Log.d(TAG, "Enforce watchdog started (interval: " + ENFORCE_INTERVAL_MS + "ms)");
    }

    /**
     * One enforcement pass. Runs on the control thread, so root I/O here is not bounded by the
     * broadcast-receiver timeout and cannot interleave with another sysfs write.
     */
    private void runEnforceTick() {
        // FAIL-SAFE first: it does not depend on activeChargingPaths and must run even if path
        // probing found nothing.
        if (checkDisableDeadline()) {
            return;
        }

        if (activeChargingPaths.isEmpty()) {
            return;  // No control available
        }

        // Get current battery level
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, filter);
        if (batteryStatus == null) return;

        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int percent = (level * 100) / scale;

        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean systemSaysCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                      status == BatteryManager.BATTERY_STATUS_FULL);

        int plugged = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        boolean isPluggedIn = plugged != 0;

        int limit = chargeLimit;

        // SAFETY: Critical battery - always enable
        if (percent < CRITICAL_BATTERY_LEVEL && isPluggedIn) {
            Log.w(TAG, "WATCHDOG: Battery critical (" + percent + "%), enabling charging!");
            requestCharging(true);
            return;
        }

        // SAFETY: Deep discharge protection
        if (percent < DEEP_DISCHARGE_LEVEL && isPluggedIn && !systemSaysCharging) {
            Log.w(TAG, "WATCHDOG: Battery low (" + percent + "%), enabling charging!");
            requestCharging(true);
            return;
        }

        // Normal operation: enforce limit
        if (limit < 100) {
            if (percent >= limit && systemSaysCharging) {
                // Above limit but charging - disable it
                Log.d(TAG, "WATCHDOG: Battery " + percent + "% >= limit " + limit + "%, disabling");
                requestCharging(false);
            } else if (percent <= (limit - HYSTERESIS) && !systemSaysCharging && isPluggedIn) {
                // Below threshold and not charging - enable it
                Log.d(TAG, "WATCHDOG: Battery " + percent + "% < threshold, enabling");
                requestCharging(true);
            } else if (chargingDisabled && percent >= limit) {
                // Re-enforce disabled state
                requestCharging(false);
            }
        } else {
            // No limit - ensure charging enabled if plugged
            if (isPluggedIn && !systemSaysCharging) {
                Log.d(TAG, "WATCHDOG: No limit, enabling charging");
                requestCharging(true);
            }
        }
    }

    /**
     * FAIL-SAFE: force-enable charging if it has been continuously disabled past
     * {@link #MAX_DISABLE_MS}.
     *
     * @return true if the deadline tripped and charging was force-enabled
     */
    private boolean checkDisableDeadline() {
        long since = disabledSinceElapsedMs;
        if (since == 0L) {
            return false;
        }
        long heldMs = SystemClock.elapsedRealtime() - since;
        if (heldMs < MAX_DISABLE_MS) {
            return false;
        }

        Log.e(TAG, "FAIL-SAFE: charging has been disabled for " + (heldMs / 60000L)
                + " min (max " + (MAX_DISABLE_MS / 60000L) + " min) - force enabling. "
                + "Holding it enabled for " + (DEADLINE_RECOVERY_MS / 60000L) + " min.");

        // Refuse further disable decisions for a while, otherwise the next tick (5 s later) simply
        // turns charging back off and the deadline achieves nothing.
        suppressDisableUntilElapsedMs = SystemClock.elapsedRealtime() + DEADLINE_RECOVERY_MS;
        forceEnableCharging();
        return true;
    }

    private boolean isDisableSuppressed() {
        long until = suppressDisableUntilElapsedMs;
        if (until == 0L) {
            return false;
        }
        if (SystemClock.elapsedRealtime() >= until) {
            suppressDisableUntilElapsedMs = 0L;
            return false;
        }
        return true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Ensure foreground (in case onCreate was skipped somehow)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                startForegroundWithNotification();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start foreground in onStartCommand: " + e.getMessage());
            }
        }

        // Update limit if provided
        if (intent != null && intent.hasExtra("limit")) {
            chargeLimit = intent.getIntExtra("limit", GatewayConfig.DEFAULT_BATTERY_LIMIT);
            try {
                GatewayConfig.from(this).setBatteryLimit(chargeLimit);
            } catch (Exception e) {
                Log.e(TAG, "Failed to save limit: " + e.getMessage());
            }
            Log.d(TAG, "Battery limit set to: " + chargeLimit + "%");
            updateNotification();

            // If limit is 100%, re-enable charging
            if (chargeLimit >= 100 && chargingDisabled) {
                requestCharging(true);
            }
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // Everything below is the safety escape hatch: a service that is going away must never
        // leave the device unable to charge. Order matters.

        // 1. Stop making decisions. Any enforce tick or reconcile that is already running observes
        //    this and any that is queued is dropped in step 2.
        destroyed = true;
        desiredChargingEnabled = true;
        suppressDisableUntilElapsedMs = 0L;

        final Handler h = controlHandler;
        final HandlerThread t = controlThread;

        // 2. Drop every queued decision (reconcile + enforce ticks) so nothing can re-disable
        //    charging after the force-enable below.
        if (h != null) {
            try {
                h.removeCallbacksAndMessages(null);
            } catch (Exception e) {
                Log.w(TAG, "Failed to clear control queue: " + e.getMessage());
            }
        }

        unregisterBatteryReceiver();

        // 3. ESCAPE HATCH (1/2): apply immediately on the caller's thread. No thread hand-off, no
        //    queue, no timeout logic — a single batched su invocation, so the worst case is one
        //    RootHelper timeout rather than one per path.
        forceEnableCharging();

        // 4. ESCAPE HATCH (2/2): re-apply on the control thread. A reconcile could have been
        //    mid-write when step 3 ran; because the control thread is serial, this write is
        //    ordered after it and therefore has the last word on the sysfs nodes.
        if (h != null) {
            boolean posted = h.post(new Runnable() {
                @Override
                public void run() {
                    forceEnableCharging();
                }
            });
            if (!posted) {
                Log.w(TAG, "Control thread already gone; relying on the inline force-enable");
            }
            if (t != null) {
                // quitSafely() runs messages whose time has already come (our post above) and then
                // terminates the looper. It never discards it in favour of quitting early.
                t.quitSafely();
            }
        }

        Log.d(TAG, "BatteryLimitService destroyed");
    }

    /** Idempotent; safe to call when the receiver was never registered. */
    private void unregisterBatteryReceiver() {
        if (!receiverRegistered) {
            return;
        }
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister receiver: " + e.getMessage());
        } finally {
            receiverRegistered = false;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Probe every candidate control node once and publish the result with a single volatile write.
     * Runs on the control thread before any decision can be applied.
     */
    private void findChargingPaths() {
        List<String[]> found = new ArrayList<>();

        for (String[] pathInfo : CHARGING_PATHS) {
            String path = pathInfo[0];
            try {
                String result = shell().exec("cat " + path + " 2>/dev/null");
                if (result != null && !result.trim().isEmpty()) {
                    found.add(pathInfo);
                    Log.d(TAG, "Found charging control: " + path + " (value: " + result.trim() + ")");
                }
            } catch (Exception e) {
                Log.w(TAG, "Error checking path " + path + ": " + e.getMessage());
            }
        }

        // Single publication of a fully-built, unmodifiable list. Readers on other threads either
        // see the old empty list or the complete new one, never a growing ArrayList.
        activeChargingPaths = Collections.unmodifiableList(found);

        if (found.isEmpty()) {
            Log.w(TAG, "No charging control path found - battery limit may not work on this device");
            showToast("No charging control found - battery limit disabled");
        } else {
            Log.i(TAG, "Found " + found.size() + " charging control path(s)");
        }
    }

    /**
     * SAFETY: Force enable charging on ALL known paths, not just the probed ones.
     *
     * <p>Deliberately ignores {@link #activeChargingPaths}: this is the recovery path used on
     * startup and teardown, when that list may be empty or may not describe the state a previous
     * process left behind. Issued as one shell invocation so the whole sweep costs a single
     * {@link RootHelper} timeout at worst — this runs on the main thread during onDestroy.
     */
    private void forceEnableCharging() {
        Log.i(TAG, "SAFETY: Force enabling charging on all paths");

        // Reset internal state first: a reconcile racing with us must converge on "enabled".
        desiredChargingEnabled = true;
        setDisabledFlag(false);

        try {
            StringBuilder cmd = new StringBuilder();
            for (String[] pathInfo : CHARGING_PATHS) {
                cmd.append("echo ").append(pathInfo[2])
                   .append(" > ").append(pathInfo[0])
                   .append(" 2>/dev/null; ");
            }
            shell().exec(cmd.toString());
            Log.i(TAG, "SAFETY: Charging force-enabled");
        } catch (Exception e) {
            Log.e(TAG, "Failed to force enable charging: " + e.getMessage());
        }
    }

    private void showToast(String message) {
        if (mainHandler != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to show toast: " + e.getMessage());
                    }
                }
            });
        }
    }

    /** Runs on the main thread from the battery broadcast. Records decisions only — never writes. */
    private void handleBatteryLevel(int percent, boolean isCharging) {
        try {
            int limit = chargeLimit;

            // Only log when level changes
            if (percent != lastBatteryLevel) {
                lastBatteryLevel = percent;
                Log.d(TAG, "Battery: " + percent + "%, charging: " + isCharging +
                           ", limit: " + limit + "%, disabled: " + chargingDisabled);
            }

            if (limit >= 100) {
                // No limit - ensure charging is enabled
                if (chargingDisabled) {
                    Log.d(TAG, "Limit is 100%, re-enabling charging");
                    requestCharging(true);
                }
                return;
            }

            // SAFETY: Never disable charging if battery is critically low
            if (percent < CRITICAL_BATTERY_LEVEL && chargingDisabled) {
                Log.w(TAG, "SAFETY: Battery critically low (" + percent + "%), force enabling charging!");
                requestCharging(true);
                updateNotification();
                return;
            }

            // SAFETY: Deep discharge protection - force re-enable if battery too low
            if (percent < DEEP_DISCHARGE_LEVEL && chargingDisabled && isCharging) {
                Log.w(TAG, "DEEP DISCHARGE PROTECTION: Battery " + percent + "% < " + DEEP_DISCHARGE_LEVEL + "%, re-enabling charging!");
                requestCharging(true);
                updateNotification();
                return;
            }

            if (percent >= limit && !chargingDisabled && percent >= CRITICAL_BATTERY_LEVEL) {
                // At or above limit - disable charging (only if above critical level)
                Log.i(TAG, "Battery at " + percent + "%, stopping charging (limit: " + limit + "%)");
                requestCharging(false);
                updateNotification();
            } else if (percent <= (limit - HYSTERESIS) && chargingDisabled) {
                // Below threshold with hysteresis - re-enable charging
                Log.i(TAG, "Battery at " + percent + "%, resuming charging (threshold: " + (limit - HYSTERESIS) + "%)");
                requestCharging(true);
                updateNotification();
            } else if (chargingDisabled && percent >= limit) {
                // Battery level changed but still above limit - re-enforce disabled state
                // Kernel often resets charging_enabled, so we need to keep setting it
                requestCharging(false);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling battery level: " + e.getMessage());
        }
    }

    /**
     * Check if charging is actually enabled in sysfs (not just our internal flag).
     * Reads real state from kernel to detect if Android/kernel changed it.
     *
     * @return true if charging appears to be enabled in hardware
     */
    private boolean isActuallyCharging() {
        try {
            // Check input_suspend (inverted logic: 0 = charging allowed, 1 = suspended)
            String suspend = shell().exec("cat /sys/class/power_supply/battery/input_suspend 2>/dev/null");
            if (suspend != null && suspend.trim().equals("0")) {
                return true;  // input_suspend=0 means charging is allowed
            }

            // Also check charging_enabled
            String enabled = shell().exec("cat /sys/class/power_supply/battery/charging_enabled 2>/dev/null");
            if (enabled != null && enabled.trim().equals("1")) {
                // charging_enabled=1 but if input_suspend=1, still not charging
                return suspend != null && suspend.trim().equals("0");
            }

            // Default: assume not charging if can't determine
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Failed to read actual charging state: " + e.getMessage());
            return false;
        }
    }

    /**
     * Record a charging decision and ask the control thread to converge on it.
     *
     * <p>This is the ONLY entry point for changing charging state. It never writes sysfs itself:
     * it publishes the desired state and lets {@link #reconcileCharging()} re-read it at write
     * time. Two decisions made 5 s apart can therefore no longer land out of order — whichever
     * reconcile runs last reads the newest desired state, and a superseded decision is skipped.
     *
     * <p>Package-private so the unit test can drive decisions from an arbitrary thread.
     */
    void requestCharging(boolean enable) {
        desiredChargingEnabled = enable;

        Handler h = controlHandler;
        if (h == null || destroyed) {
            Log.w(TAG, "Charging control unavailable, decision dropped (enable=" + enable + ")");
            return;
        }

        if (Looper.myLooper() == h.getLooper()) {
            // Already on the control thread (enforce tick / init): apply in place so the write
            // stays ordered with respect to the decision that produced it.
            reconcileCharging();
            return;
        }

        // Coalesce: one pending reconcile is enough, it will read the latest desired state.
        h.removeCallbacks(reconcileRunnable);
        h.post(reconcileRunnable);
    }

    /**
     * Converge sysfs onto {@link #desiredChargingEnabled}. Control thread only.
     *
     * <p>Always writes, even when the state has not changed — the kernel resets
     * {@code charging_enabled} on its own, which is why the enforce watchdog exists at all.
     */
    private void reconcileCharging() {
        if (destroyed) {
            return;
        }

        // Re-read at write time. Anything decided while this task sat in the queue wins.
        boolean enable = desiredChargingEnabled;

        if (!enable && isDisableSuppressed()) {
            Log.w(TAG, "FAIL-SAFE recovery window active - ignoring request to disable charging");
            desiredChargingEnabled = true;
            enable = true;
        }

        setCharging(enable);
    }

    /**
     * The single sysfs writer. Control thread only — no locking needed, and adding any would only
     * hide the ordering requirement.
     */
    private void setCharging(boolean enable) {
        List<String[]> paths = activeChargingPaths;
        if (paths.isEmpty()) {
            Log.w(TAG, "No charging paths available");
            return;
        }

        try {
            // Apply to ALL active paths to handle both USB and AC charging
            for (String[] pathInfo : paths) {
                String path = pathInfo[0];
                String disableValue = pathInfo[1];
                String enableValue = pathInfo[2];
                String value = enable ? enableValue : disableValue;

                String cmd = "echo " + value + " > " + path;
                shell().exec(cmd);
                Log.d(TAG, "Set " + path + " = " + value);
            }

            setDisabledFlag(!enable);
            Log.i(TAG, "Charging " + (enable ? "enabled" : "disabled") + " on " + paths.size() + " path(s)");
        } catch (Exception e) {
            Log.e(TAG, "Failed to set charging: " + e.getMessage());
        }
    }

    /** Update the disabled flag and the fail-safe deadline clock together. */
    private void setDisabledFlag(boolean disabled) {
        chargingDisabled = disabled;
        if (disabled) {
            if (disabledSinceElapsedMs == 0L) {
                disabledSinceElapsedMs = SystemClock.elapsedRealtime();
            }
        } else {
            disabledSinceElapsedMs = 0L;
        }
    }

    private void startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Battery Limit Service",
                    NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Monitors and limits battery charging");
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) {
                    manager.createNotificationChannel(channel);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to create notification channel: " + e.getMessage());
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification());
    }

    private void updateNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) {
                    manager.notify(NOTIFICATION_ID, buildNotification());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to update notification: " + e.getMessage());
        }
    }

    private Notification buildNotification() {
        try {
            Intent notificationIntent = new Intent(this, MainActivity.class);
            int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingFlags);

            String text;
            if (chargeLimit >= 100) {
                text = "No limit (charging normally)";
            } else if (activeChargingPaths.isEmpty()) {
                text = "Limit: " + chargeLimit + "% [NO CONTROL]";
            } else {
                text = "Limit: " + chargeLimit + "%" + (chargingDisabled ? " [PAUSED]" : " [active]");
            }

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(this);
            }

            return builder
                .setContentTitle("Battery Charge Limit")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification_battery)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
        } catch (Exception e) {
            Log.e(TAG, "Failed to build notification: " + e.getMessage());
            // Return minimal notification to avoid crash
            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(this);
            }
            return builder
                .setContentTitle("Battery Limit")
                .setContentText("Running...")
                .setSmallIcon(R.drawable.ic_notification_battery)
                .build();
        }
    }

    public int getChargeLimit() {
        return chargeLimit;
    }

    public boolean isChargingDisabled() {
        return chargingDisabled;
    }

    // ------------------------------------------------------------------
    // Root shell seam
    //
    // Production always uses RootHelper. The indirection exists so the charging state machine can
    // be unit-tested without ever exec'ing su.
    // ------------------------------------------------------------------

    /** Minimal view of {@link RootHelper} used by this service. */
    interface RootShell {
        String exec(String command);
    }

    private static final RootShell ROOT_SHELL = new RootShell() {
        @Override
        public String exec(String command) {
            return RootHelper.execRoot(command);
        }
    };

    private static volatile RootShell rootShell = ROOT_SHELL;

    private static RootShell shell() {
        RootShell s = rootShell;
        return (s != null) ? s : ROOT_SHELL;
    }

    /** Test-only: swap the privileged shell. Pass null to restore {@link RootHelper}. */
    static void setRootShellForTest(RootShell shell) {
        rootShell = (shell != null) ? shell : ROOT_SHELL;
    }

    /** Test-only: the looper of the single sysfs-writing thread. */
    Looper controlLooperForTest() {
        HandlerThread t = controlThread;
        return (t != null) ? t.getLooper() : null;
    }

    /** Test-only. */
    boolean desiredChargingEnabledForTest() {
        return desiredChargingEnabled;
    }
}
