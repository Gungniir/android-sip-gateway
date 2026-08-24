package org.onetwoone.gateway.ui;

import android.content.Context;
import android.content.pm.PackageManager;
import android.telecom.TelecomManager;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.onetwoone.gateway.RootHelper;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages Android permissions via root commands.
 *
 * Handles:
 * - Granting runtime permissions via root
 * - Setting default dialer via RoleManager
 * - Tracking permission state via LiveData
 */
public class PermissionManager {
    private static final String TAG = "PermissionManager";

    private static final String[] REQUIRED_PERMISSIONS = {
        "android.permission.READ_PHONE_STATE",
        "android.permission.CALL_PHONE",
        "android.permission.RECORD_AUDIO",
        "android.permission.READ_CALL_LOG",
        "android.permission.ANSWER_PHONE_CALLS",
        "android.permission.PROCESS_OUTGOING_CALLS",
    };

    private static final String[] PERMISSION_DISPLAY_NAMES = {
        "READ_PHONE_STATE",
        "CALL_PHONE",
        "RECORD_AUDIO",
        "READ_CALL_LOG",
        "ANSWER_PHONE_CALLS",
        "PROCESS_OUTGOING_CALLS"
    };

    /**
     * Holds the current state of all permissions.
     */
    public static class PermissionState {
        public Map<String, Boolean> permissions = new HashMap<>();

        /**
         * Whether this package currently holds the dialer role.
         *
         * <p>Until GW-42 this field was declared, defaulted to false and <b>never
         * written</b>: {@link PermissionManager#refreshPermissionStatus()} built a fresh
         * {@code PermissionState} and filled in only the runtime permissions. Nothing read
         * it either, so the dead value was invisible. The commissioning wizard's dialer step
         * is the first consumer, and a step that reports "not default dialer" on a phone
         * that is one would be worse than no step at all - so the refresh now answers it
         * from {@code TelecomManager}.
         *
         * <p>It is not a permission and is deliberately outside {@link #allGranted()}: the
         * role is granted by the framework's role manager, not by {@code pm grant}, and a
         * caller that needs both has to ask for both.
         */
        public boolean isDefaultDialer = false;

        public boolean allGranted() {
            for (Boolean granted : permissions.values()) {
                if (!granted) return false;
            }
            return true;
        }

        public String toDisplayString() {
            StringBuilder sb = new StringBuilder();
            for (String name : PERMISSION_DISPLAY_NAMES) {
                String fullPerm = "android.permission." + name;
                Boolean granted = permissions.get(fullPerm);
                String icon = (granted != null && granted) ? "\u2713" : "\u2717";  // ✓ or ✗
                String shortName = name.replace("_", " ");
                if (shortName.length() > 20) {
                    shortName = shortName.substring(0, 17) + "...";
                }
                sb.append(icon).append(" ").append(shortName).append("\n");
            }
            return sb.toString();
        }
    }

    private final Context context;
    private final String packageName;
    private final MutableLiveData<PermissionState> permissionState = new MutableLiveData<>(new PermissionState());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public PermissionManager(Context context) {
        this.context = context.getApplicationContext();
        this.packageName = context.getPackageName();
    }

    public LiveData<PermissionState> getPermissionState() {
        return permissionState;
    }

    /**
     * Refresh permission status by checking current grants.
     * Runs on background thread and updates LiveData.
     */
    public void refreshPermissionStatus() {
        executor.execute(() -> {
            PermissionState state = new PermissionState();

            for (String perm : REQUIRED_PERMISSIONS) {
                try {
                    int status = context.checkSelfPermission(perm);
                    state.permissions.put(perm, status == PackageManager.PERMISSION_GRANTED);
                } catch (Exception e) {
                    state.permissions.put(perm, false);
                }
            }

            state.isDefaultDialer = isDefaultDialer();

            permissionState.postValue(state);
        });
    }

    /**
     * Grant all required permissions via root.
     * Runs asynchronously and updates LiveData when complete.
     */
    public void grantAllPermissionsAsync() {
        executor.execute(() -> {
            grantPermissionsViaRoot();
            setDefaultDialerViaRoot();
            refreshPermissionStatus();
        });
    }

    // All three root paths below went through a bare Runtime.exec + unbounded waitFor(),
    // with neither pipe drained: a hung `su` blocked this executor for good, and a chatty
    // command could deadlock it (GW-20 §4 / AUDIT H1). They also logged unconditional
    // success. RootHelper bounds, drains and reports the exit code.

    private void grantPermissionsViaRoot() {
        for (String perm : REQUIRED_PERMISSIONS) {
            RootHelper.RootResult result = RootHelper.run("pm grant " + packageName + " " + perm);
            if (result.success()) {
                Log.d(TAG, "Granted via root: " + perm);
            } else {
                Log.e(TAG, "Failed to grant " + perm + " (exit " + result.exitCode() + "): "
                        + result.stderr());
            }
        }
    }

    /**
     * Whether this package holds the dialer role right now (GW-42).
     *
     * <p>Read from {@code TelecomManager}, not inferred from the exit code of the
     * {@code cmd role} call that tried to claim it: on a device where the claim silently
     * does nothing - the failure mode this whole step exists for, since without the role
     * {@code GatewayInCallService} never binds and no GSM call is ever handled - the claim
     * still exits 0. Every failure is caught: this runs on the permission executor and its
     * caller must not die because a framework service was unavailable.
     */
    private boolean isDefaultDialer() {
        try {
            TelecomManager telecom =
                    (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
            if (telecom == null) {
                return false;
            }
            return packageName.equals(telecom.getDefaultDialerPackage());
        } catch (Exception e) {
            Log.w(TAG, "Could not read the default dialer package: " + e.getMessage());
            return false;
        }
    }

    /**
     * Claim the dialer role via root, then re-read what actually happened (GW-42).
     *
     * <p>The same command {@link #grantAllPermissionsAsync()} runs, exposed on its own so the
     * commissioning wizard's dialer step can retry it without re-granting six permissions.
     * Runs on the same single-thread executor as everything else here, so it is never a root
     * call on the main thread.
     */
    public void setDefaultDialerAsync() {
        executor.execute(() -> {
            setDefaultDialerViaRoot();
            refreshPermissionStatus();
        });
    }

    private void setDefaultDialerViaRoot() {
        // Use RoleManager via cmd role - required for InCallService binding
        RootHelper.RootResult result = RootHelper.run(
                "cmd role add-role-holder android.app.role.DIALER " + packageName);
        if (result.success()) {
            Log.d(TAG, "Set as default dialer via cmd role");
        } else {
            Log.e(TAG, "Failed to set default dialer (exit " + result.exitCode() + "): "
                    + result.stderr());
        }
    }

    /**
     * Disable battery optimization via root (doze whitelist).
     * Note: This can cause freezes on some devices.
     */
    public void disableBatteryOptimizationAsync() {
        executor.execute(() -> {
            RootHelper.RootResult result =
                    RootHelper.run("dumpsys deviceidle whitelist +" + packageName);
            if (result.success()) {
                Log.d(TAG, "Disabled battery optimization via root");
            } else {
                Log.e(TAG, "Failed to disable battery optimization (exit " + result.exitCode()
                        + "): " + result.stderr());
            }
        });
    }

    /**
     * Shutdown the executor service.
     */
    public void shutdown() {
        executor.shutdown();
    }
}
