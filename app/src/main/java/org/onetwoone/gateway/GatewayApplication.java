package org.onetwoone.gateway;

import android.app.Application;
import android.util.Log;

import androidx.work.Configuration;
import androidx.work.WorkManager;

import org.onetwoone.gateway.config.GatewayConfig;

import java.io.File;

/**
 * Application class for gateway initialization.
 *
 * Handles early initialization tasks before other components start,
 * including fixing the adoptable storage bug where no_backup directory
 * doesn't exist.
 */
public class GatewayApplication extends Application implements Configuration.Provider {
    private static final String TAG = "GatewayApp";
    private static boolean workManagerAvailable = false;

    @Override
    public void onCreate() {
        // Fix adoptable storage bug: ensure no_backup directory exists
        // before WorkManager tries to create its database
        ensureNoBackupDirectory();

        super.onCreate();

        // Config before anything else in the process. GatewayConfig.init() is where the
        // preference migration runs (AUDIT H4), so it has to precede every reader - and
        // before GW-24 it was called from three components only (PjsipSipService,
        // GatewayInCallService, MainViewModel), while BootReceiver, GatewayControlReceiver,
        // WebConfigServer, DeviceMuteManager and BatteryLimitService all read the same
        // preferences by raw name and could run first. Those raw reads are gone; this makes
        // the ordering structural rather than a property of which component happened to
        // start the process.
        GatewayConfig.init(this);

        // Manually initialize WorkManager with error handling
        initWorkManager();
    }

    /**
     * Ensure the no_backup directory exists.
     *
     * On devices with adoptable storage (SD card as internal storage),
     * Android may fail to create this directory, causing WorkManager
     * and other components to crash with SQLiteCantOpenDatabaseException.
     */
    private void ensureNoBackupDirectory() {
        try {
            File noBackupDir = new File(getNoBackupFilesDir().getPath());
            if (!noBackupDir.exists()) {
                boolean created = noBackupDir.mkdirs();
                Log.i(TAG, "Created no_backup directory: " + created);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to ensure no_backup directory: " + e.getMessage());
        }
    }

    /**
     * Initialize WorkManager with error handling.
     * On devices with corrupted storage, WorkManager may fail to initialize.
     * In that case, we disable battery watchdog but allow the app to continue.
     */
    private void initWorkManager() {
        try {
            WorkManager.initialize(this, getWorkManagerConfiguration());
            workManagerAvailable = true;
            Log.i(TAG, "WorkManager initialized successfully");
        } catch (Exception e) {
            workManagerAvailable = false;
            Log.e(TAG, "WorkManager initialization failed (battery watchdog disabled): " + e.getMessage());
        }
    }

    @Override
    public Configuration getWorkManagerConfiguration() {
        return new Configuration.Builder()
                .setMinimumLoggingLevel(Log.INFO)
                .build();
    }

    /**
     * Check if WorkManager is available.
     * Used by BatteryWatchdog to skip scheduling if WorkManager failed to initialize.
     */
    public static boolean isWorkManagerAvailable() {
        return workManagerAvailable;
    }
}
