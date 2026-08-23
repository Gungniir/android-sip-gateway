package org.onetwoone.gateway;

import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Helper class for executing commands with root (su) privileges.
 * Requires Magisk or similar root solution installed on the device.
 */
public class RootHelper {
    private static final String TAG = "RootHelper";

    // Every one of these is written and read from arbitrary threads: GsmAudioOpen and SipInit
    // (setupAlsaPermissions), SetCharging, BatteryOptDisable, ProcessRestart, SmsHandler and
    // main. volatile makes those reads defined; each consumer snapshots before use.
    //
    // NOTE for Phase 1 (GW-20): the check-then-act in startRootShell() needs mutual
    // exclusion, not visibility - two callers can both see suProcess == null and each spawn
    // an `su`, orphaning one. Deliberately not fixed here.
    private static volatile Boolean hasRoot = null;
    private static volatile Process suProcess = null;
    private static volatile DataOutputStream suOutputStream = null;

    /**
     * Check if root access is available
     */
    public static boolean checkRoot() {
        // Snapshot: another thread can publish the cached answer between the null check and
        // the unboxing return - which would be an NPE if it published null (it never does,
        // but the read must not depend on that).
        Boolean cached = hasRoot;
        if (cached != null) {
            return cached;
        }

        try {
            Process process = Runtime.getRuntime().exec("su -c id");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            process.waitFor();

            boolean rooted = (line != null && line.contains("uid=0"));
            hasRoot = rooted;
            Log.d(TAG, "Root check: " + (rooted ? "AVAILABLE" : "NOT AVAILABLE"));
            return rooted;

        } catch (Exception e) {
            Log.e(TAG, "Root check failed: " + e.getMessage());
            hasRoot = false;
            return false;
        }
    }

    /**
     * Execute a command with root privileges
     * @return command output or null on error
     */
    public static String execRoot(String command) {
        return execRoot(command, 5000);
    }

    /**
     * Execute a command with root privileges with custom timeout
     */
    public static String execRoot(String command, int timeoutMs) {
        Log.d(TAG, "execRoot: " + command);

        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});

            StringBuilder output = new StringBuilder();
            StringBuilder error = new StringBuilder();

            Thread outputThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error reading output: " + e.getMessage());
                }
            });

            Thread errorThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        error.append(line).append("\n");
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error reading stderr: " + e.getMessage());
                }
            });

            outputThread.start();
            errorThread.start();

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);

            outputThread.join(1000);
            errorThread.join(1000);

            if (!finished) {
                process.destroyForcibly();
                Log.e(TAG, "Command timed out: " + command);
                return null;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                Log.w(TAG, "Command exit code " + exitCode + ": " + error.toString().trim());
            }

            String result = output.toString().trim();
            Log.d(TAG, "execRoot result: " + (result.length() > 100 ? result.substring(0, 100) + "..." : result));
            return result;

        } catch (Exception e) {
            Log.e(TAG, "execRoot failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Execute command and return exit code
     */
    public static int execRootCode(String command) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            return process.waitFor();
        } catch (Exception e) {
            Log.e(TAG, "execRootCode failed: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Start a persistent root shell for faster command execution
     */
    public static boolean startRootShell() {
        if (suProcess != null) {
            return true;
        }

        try {
            suProcess = Runtime.getRuntime().exec("su");
            suOutputStream = new DataOutputStream(suProcess.getOutputStream());
            Log.d(TAG, "Started persistent root shell");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start root shell: " + e.getMessage());
            return false;
        }
    }

    /**
     * Execute command in persistent root shell
     */
    public static void execInShell(String command) {
        // Snapshot: stopRootShell() and the catch below null this from other threads. A null
        // slipping through still behaves exactly as before - the NPE lands in the catch.
        DataOutputStream out = suOutputStream;
        if (out == null) {
            if (!startRootShell()) {
                return;
            }
            out = suOutputStream;   // published by startRootShell()
        }

        try {
            out.writeBytes(command + "\n");
            out.flush();
        } catch (Exception e) {
            Log.e(TAG, "Failed to exec in shell: " + e.getMessage());
            suProcess = null;
            suOutputStream = null;
        }
    }

    /**
     * Stop persistent root shell
     */
    public static void stopRootShell() {
        // Snapshot both: the objects checked must be the ones closed/destroyed.
        DataOutputStream out = suOutputStream;
        if (out != null) {
            try {
                out.writeBytes("exit\n");
                out.flush();
                out.close();
            } catch (Exception e) {
                // ignore
            }
            suOutputStream = null;
        }

        Process proc = suProcess;
        if (proc != null) {
            proc.destroy();
            suProcess = null;
        }

        Log.d(TAG, "Stopped persistent root shell");
    }

    /**
     * Copy file to a location requiring root
     */
    public static boolean copyFileAsRoot(String src, String dst) {
        String result = execRoot("cp " + src + " " + dst + " && chmod 755 " + dst);
        return result != null;
    }

    /**
     * Extract asset to a file and make executable
     */
    public static boolean extractAsset(android.content.Context context, String assetName, String destPath) {
        try {
            File destFile = new File(destPath);

            // Extract to app's files directory first
            File tempFile = new File(context.getFilesDir(), assetName);
            try (InputStream is = context.getAssets().open(assetName);
                 FileOutputStream fos = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
            }

            // Make executable
            tempFile.setExecutable(true, false);

            // If destination requires root, copy with su
            if (!destFile.getParentFile().canWrite()) {
                return copyFileAsRoot(tempFile.getAbsolutePath(), destPath);
            } else {
                return tempFile.renameTo(destFile);
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to extract asset: " + e.getMessage());
            return false;
        }
    }

    /**
     * Make ALSA sound devices accessible to the app.
     * Required for native tinyalsa access.
     */
    public static boolean setupAlsaPermissions() {
        Log.d(TAG, "Setting up ALSA permissions...");

        // Disable SELinux (required for direct ALSA access from app)
        execRoot("setenforce 0");

        // Make sound devices accessible
        String result = execRoot("chmod 666 /dev/snd/*");
        if (result == null) {
            Log.e(TAG, "Failed to chmod /dev/snd/*");
            return false;
        }

        Log.d(TAG, "ALSA permissions set");
        return true;
    }

    /**
     * Grant all runtime permissions to a package using root
     */
    public static void grantAllPermissions(android.content.Context context) {
        String packageName = context.getPackageName();
        Log.d(TAG, "Granting all permissions to " + packageName);

        String[] permissions = {
            // Phone permissions
            "android.permission.CALL_PHONE",
            "android.permission.READ_PHONE_STATE",
            "android.permission.READ_CALL_LOG",
            "android.permission.WRITE_CALL_LOG",
            "android.permission.ANSWER_PHONE_CALLS",
            "android.permission.PROCESS_OUTGOING_CALLS",
            // SMS permissions
            "android.permission.RECEIVE_SMS",
            "android.permission.READ_SMS",
            "android.permission.SEND_SMS",
            // Audio permissions
            "android.permission.RECORD_AUDIO",
            "android.permission.MODIFY_AUDIO_SETTINGS",
            // Contacts (for caller ID)
            "android.permission.READ_CONTACTS",
            // Storage (for config/logs)
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            // Network
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
            // Boot
            "android.permission.RECEIVE_BOOT_COMPLETED",
            // Foreground service
            "android.permission.FOREGROUND_SERVICE",
            // Wake lock
            "android.permission.WAKE_LOCK",
        };

        for (String permission : permissions) {
            String cmd = "pm grant " + packageName + " " + permission;
            String result = execRoot(cmd);
            if (result != null) {
                Log.d(TAG, "Granted: " + permission);
            } else {
                Log.w(TAG, "Failed to grant: " + permission);
            }
        }

        // Also set as default dialer/phone app
        execRoot("cmd telecom set-default-dialer " + packageName);
        Log.d(TAG, "Set as default dialer");

        // Disable battery optimization
        execRoot("dumpsys deviceidle whitelist +" + packageName);
        Log.d(TAG, "Added to battery whitelist");

        Log.d(TAG, "Permission grant complete");
    }
}
