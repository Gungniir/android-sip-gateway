package org.onetwoone.gateway;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import org.onetwoone.gateway.config.GatewayConfig;

/**
 * Автозапуск GSM-SIP шлюза при загрузке системы
 *
 * Запускается автоматически после BOOT_COMPLETED
 * Гарантирует что шлюз работает всегда после перезагрузки устройства
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "GatewayBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i(TAG, "Boot completed, starting gateway services");

            // Start SIP service
            Intent serviceIntent = new Intent(context, PjsipSipService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            Log.i(TAG, "SIP service started");

            // Start Battery Limit service with saved limit. Through GatewayConfig, not a raw
            // "gateway_prefs"/"battery_limit" read: at boot this receiver is often the first
            // thing in the process, so a raw read here would be the one that outran
            // GatewayConfig.init() and its migration (AUDIT H4).
            int batteryLimit = GatewayConfig.from(context).getBatteryLimit();
            Intent batteryIntent = new Intent(context, BatteryLimitService.class);
            batteryIntent.putExtra("limit", batteryLimit);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(batteryIntent);
            } else {
                context.startService(batteryIntent);
            }
            Log.i(TAG, "Battery limit service started (limit: " + batteryLimit + "%)");

            // Schedule battery watchdog (runs independently, survives service crashes)
            BatteryWatchdog.schedule(context);
            Log.i(TAG, "Battery watchdog scheduled");

            // Start MainActivity via full-screen intent (required for Android 10+)
            launchMainActivity(context);
            Log.i(TAG, "MainActivity launch requested");
        }
    }

    private void launchMainActivity(Context context) {
        // Use root to start activity (bypasses Android 10+ background restrictions)
        new Thread(() -> {
            try {
                Thread.sleep(2000); // Wait for system to settle
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            // Bounded and drained, and the exit code is checked (GW-20 §4 / AUDIT H1):
            // the old code did a bare waitFor() with no timeout - a `su` that never
            // returned at boot would have parked this thread forever - and only fell back
            // on an exception, so an `am start` that failed outright was reported as a
            // success and MainActivity never came up.
            RootHelper.RootResult result =
                    RootHelper.run("am start -n org.onetwoone.gateway/.MainActivity");
            if (result.success()) {
                Log.i(TAG, "MainActivity started via root");
                return;
            }

            Log.e(TAG, "Failed to start MainActivity via root (exit " + result.exitCode()
                    + "): " + result.stderr());
            // Fallback: try normal start
            try {
                Intent activityIntent = new Intent(context, MainActivity.class);
                activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(activityIntent);
            } catch (Exception e2) {
                Log.e(TAG, "Fallback also failed: " + e2.getMessage());
            }
        }).start();
    }
}
