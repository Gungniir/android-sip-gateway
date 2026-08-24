package org.onetwoone.gateway;

import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs commands with root (su) privileges. Requires Magisk or an equivalent.
 *
 * <h2>The result contract (AUDIT H1 / H13 / B1e — GW-20)</h2>
 *
 * <p>Use {@link #run(String)} and check {@link RootResult#success()}. That is the only
 * honest success test, and it is <b>impossible</b> for a non-zero exit to report success:
 * {@code success()} is defined as {@code exitCode == 0} and every failure path
 * ({@link #EXIT_NOT_STARTED}, {@link #EXIT_TIMED_OUT}, {@link #EXIT_NO_OUTPUT},
 * {@link #EXIT_INTERRUPTED}) uses a negative code.
 *
 * <p>This exists because the old API could not express failure. {@code execRoot} logged a
 * non-zero exit and then returned {@code output.toString().trim()} anyway — an empty
 * string, never {@code null} — so every caller testing {@code execRoot(...) != null} was
 * blind to a failed command. Two live bugs shared that root cause:
 * <ul>
 *   <li><b>H13</b> — {@code SmsHandler.markAsReadWithRoot} shells out to {@code sqlite3},
 *       which is on neither test device (exit 127). It logged
 *       {@code "Marked SMS id=N as read (root sqlite3)"} for a command that did nothing, so
 *       the whole inbox was re-forwarded to the PBX on every process start.</li>
 *   <li><b>B1e</b> — the same shape in {@code QualcommAudioProfile}: a {@code tinymix} that
 *       does not exist, whose failure was indistinguishable from a reading of {@code 84}.</li>
 * </ul>
 *
 * <p>{@link #execRoot(String)} is kept as a source-compatible convenience for callers that
 * only want stdout, but it now returns {@code null} for <em>any</em> failure — including a
 * non-zero exit, which it previously reported as {@code ""}. Prefer {@link #run(String)} in
 * new code: it carries the exit code and stderr, which is what makes a failure diagnosable.
 *
 * <h2>Output capture</h2>
 *
 * <p>Each stream is drained by its own daemon thread into a thread-confined
 * {@code StringBuilder} and handed over as a finished {@code String} through a
 * {@link FutureTask}. Nothing ever reads a builder another thread may still be appending
 * to, and a reader that does not finish in time yields a <em>failed</em> result rather than
 * partial output. Both streams are always drained, so a command that fills a pipe cannot
 * deadlock.
 *
 * <h2>Threading</h2>
 *
 * <p>Calls are <b>not</b> serialised, deliberately. GW-20's brief asked for a single-thread
 * root executor; it was not built because serialising is a net loss here:
 * <ul>
 *   <li>{@code PowerController.disableBatteryOptimizations} is six commands at 5 s each
 *       (~30 s worst case) on its own thread, kept off the control thread on purpose.
 *       Serialising would put the per-call {@link #setupAlsaPermissions()} behind that
 *       burst at exactly service-start time, stalling call setup.</li>
 *   <li>{@code SmsHandler.markAsReadWithRoot} still runs on <b>main</b>. Serialising would
 *       let main block behind another thread's {@code su} — a main-thread stall the current
 *       design does not have.</li>
 * </ul>
 * A real timeout plus a safe output handoff (both above) is worth more than the executor.
 * If serialisation is ever revisited, the ALSA-permission path needs its own lane or a
 * bounded wait first.
 *
 * <p>{@link #startRootShell()} / {@link #execInShell(String)} / {@link #stopRootShell()}
 * and the persistent-shell statics have no callers; neither do {@link #checkRoot()},
 * {@link #execRootCode(String)}, {@link #copyFileAsRoot(String, String)},
 * {@link #extractAsset(android.content.Context, String, String)} and
 * {@link #grantAllPermissions(android.content.Context)}. Deleting them is <b>GW-31</b>'s
 * sweep, not GW-20's — see AUDIT §"dead code". They are left correct in the meantime.
 */
public class RootHelper {
    private static final String TAG = "RootHelper";

    /** Default wall-clock budget for one privileged command. */
    public static final int DEFAULT_TIMEOUT_MS = 5000;

    /**
     * How long to wait for a stream reader to hand its finished output over, once the
     * process has already exited. Normally instant — the readers see EOF the moment the
     * process dies.
     */
    private static final int OUTPUT_HANDOFF_MS = 1000;

    /** The process could not be started at all (no {@code su}, SELinux, missing binary). */
    public static final int EXIT_NOT_STARTED = -1;

    /** The process outlived its timeout and was killed. Output is discarded. */
    public static final int EXIT_TIMED_OUT = -2;

    /** The process exited but its stdout reader did not hand output over in time. */
    public static final int EXIT_NO_OUTPUT = -3;

    /** The calling thread was interrupted while waiting. */
    public static final int EXIT_INTERRUPTED = -4;

    // Every one of these is written and read from arbitrary threads: GsmAudioOpen and SipInit
    // (setupAlsaPermissions), SetCharging, BatteryOptDisable, ProcessRestart, SmsHandler and
    // main. volatile makes those reads defined; each consumer snapshots before use.
    //
    // NOTE: the check-then-act in startRootShell() needs mutual exclusion, not visibility -
    // two callers can both see suProcess == null and each spawn an `su`, orphaning one.
    // Deliberately not fixed: the whole persistent-shell API is dead and GW-31 deletes it.
    private static volatile Boolean hasRoot = null;
    private static volatile Process suProcess = null;
    private static volatile DataOutputStream suOutputStream = null;

    /**
     * Whether {@code setenforce 0} has already been applied in this process.
     *
     * <p>SELinux mode is a global, idempotent, process-independent setting: once permissive
     * it stays permissive until something outside this app changes it. The {@code chmod} in
     * {@link #setupAlsaPermissions()} is <em>not</em> idempotent in the same way — the audio
     * HAL recreates the {@code /dev/snd/*} nodes with their default ownership whenever it
     * reopens the card, which is why that half legitimately re-runs on every capture open
     * (see {@code GsmAudioPort}'s comment at its call site).
     *
     * <p>Reset to false if the {@code setenforce} itself fails, so a transient failure at
     * boot does not disable it for the life of the process.
     */
    private static final AtomicBoolean selinuxPermissive = new AtomicBoolean(false);

    // ------------------------------------------------------------------
    // Result type
    // ------------------------------------------------------------------

    /**
     * The outcome of one command: exit code, stdout, stderr.
     *
     * <p>Immutable. {@link #stdout()} and {@link #stderr()} are never {@code null} — they
     * are {@code ""} when there was no output or the output could not be collected. They
     * are never <em>partial</em>: a stream is published only once its reader has run to
     * completion, and a reader that fails or times out yields a failed result with empty
     * output instead.
     */
    public static final class RootResult {
        private final String command;
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        RootResult(String command, int exitCode, String stdout, String stderr) {
            this.command = command;
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
        }

        /** The command as executed, for logging. */
        public String command() {
            return command;
        }

        /**
         * The process exit code, or one of the negative {@code EXIT_*} constants when the
         * command never produced one.
         */
        public int exitCode() {
            return exitCode;
        }

        /** Complete stdout, trimmed. Never null, never partial. */
        public String stdout() {
            return stdout;
        }

        /** Complete stderr, trimmed. Never null, never partial. */
        public String stderr() {
            return stderr;
        }

        /**
         * The only success test. True <b>only</b> for a process that ran to completion and
         * exited 0 — a missing binary (127), a refused write, a timeout and a failed spawn
         * are all false.
         */
        public boolean success() {
            return exitCode == 0;
        }

        /**
         * @return {@link #stdout()} when {@link #success()}, otherwise {@code null}. The
         *         shape {@link RootHelper#execRoot(String)} keeps for its callers.
         */
        public String outputOrNull() {
            return exitCode == 0 ? stdout : null;
        }

        @Override
        public String toString() {
            return "RootResult{exit=" + exitCode + ", out=" + abbreviate(stdout)
                    + ", err=" + abbreviate(stderr) + ", cmd=" + command + "}";
        }
    }

    // ------------------------------------------------------------------
    // Execution
    // ------------------------------------------------------------------

    /** Run {@code command} through {@code su -c} with the default timeout. */
    public static RootResult run(String command) {
        return run(command, DEFAULT_TIMEOUT_MS);
    }

    /** Run {@code command} through {@code su -c}, killing it after {@code timeoutMs}. */
    public static RootResult run(String command, int timeoutMs) {
        return exec(new String[]{"su", "-c", command}, timeoutMs);
    }

    /**
     * Run an argv directly, with no {@code su} wrapper — for callers that already know
     * whether they need root (e.g. an extracted binary that runs unprivileged on older
     * Android). Same bounded, drained, thread-safe capture as {@link #run(String, int)}.
     *
     * @param argv      argv[0] is the executable
     * @param timeoutMs wall-clock budget; the process is force-killed when it expires
     */
    public static RootResult exec(String[] argv, int timeoutMs) {
        String display = join(argv);
        Log.d(TAG, "exec: " + display);

        Process process;
        try {
            process = Runtime.getRuntime().exec(argv);
        } catch (IOException | RuntimeException e) {
            // No `su`, no such binary, SELinux denial: the command never ran. This used to
            // be indistinguishable from "ran and printed nothing".
            Log.e(TAG, "Could not start: " + display + ": " + e);
            return new RootResult(display, EXIT_NOT_STARTED, "", String.valueOf(e.getMessage()));
        }

        // Both streams are drained unconditionally, so a chatty command cannot fill a pipe
        // and deadlock. Each reader owns its StringBuilder and publishes the finished
        // String through the FutureTask - the caller never touches a live builder (H1).
        FutureTask<String> stdoutTask = startReader(process.getInputStream(), "RootHelper-out");
        FutureTask<String> stderrTask = startReader(process.getErrorStream(), "RootHelper-err");

        try {
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                // Kill first, THEN give up. The old code joined both readers (1 s each)
                // before it even looked at `finished`, so a hung `su` cost the caller
                // timeoutMs + 2000 ms.
                abandon(process, stdoutTask, stderrTask);
                Log.e(TAG, "Timed out after " + timeoutMs + " ms: " + display);
                return new RootResult(display, EXIT_TIMED_OUT, "", "");
            }

            String stdout = await(stdoutTask);
            if (stdout == null) {
                // A reader that did not finish means the output on hand is incomplete.
                // Report a failure; never hand back a partial read.
                abandon(process, stdoutTask, stderrTask);
                Log.e(TAG, "stdout reader did not complete within " + OUTPUT_HANDOFF_MS
                        + " ms: " + display);
                return new RootResult(display, EXIT_NO_OUTPUT, "", "");
            }

            // stderr is diagnostic only, so a slow reader downgrades to "" rather than
            // failing a command that otherwise succeeded. Still never partial.
            String stderr = await(stderrTask);
            if (stderr == null) {
                stderr = "";
            }

            int exitCode = process.exitValue();
            RootResult result = new RootResult(display, exitCode, stdout, stderr);
            if (result.success()) {
                Log.d(TAG, "exec ok: " + display + " -> " + abbreviate(stdout));
            } else {
                Log.w(TAG, "exec exit " + exitCode + ": " + display
                        + (stderr.isEmpty() ? "" : " : " + abbreviate(stderr)));
            }
            return result;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abandon(process, stdoutTask, stderrTask);
            Log.w(TAG, "Interrupted waiting for: " + display);
            return new RootResult(display, EXIT_INTERRUPTED, "", "");
        }
    }

    /**
     * Give up on a process: kill it and stop caring about its readers.
     *
     * <p>{@code destroy()} rather than {@code destroyForcibly()} on purpose — the latter is
     * API 26 and {@code minSdkVersion} is 23, while on Android {@code Process.destroy()}
     * already sends SIGKILL. Killing the process closes both pipes, which is what actually
     * unblocks the reader threads; {@code cancel(true)} only interrupts them, and a thread
     * blocked in a native {@code read()} does not notice an interrupt. They are daemons, so
     * a reader that somehow survives cannot hold anything open.
     */
    private static void abandon(Process process, FutureTask<String> stdoutTask,
                                FutureTask<String> stderrTask) {
        process.destroy();
        stdoutTask.cancel(true);
        stderrTask.cancel(true);
    }

    /**
     * Drain {@code stream} on a daemon thread. The builder is thread-confined; the finished
     * String is published exactly once through the returned task.
     */
    private static FutureTask<String> startReader(InputStream stream, String name) {
        FutureTask<String> task = new FutureTask<>(() -> {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString().trim();
        });
        Thread thread = new Thread(task, name);
        // Daemon: a reader blocked on a stream that never closes must not hold the process
        // open, and it is never joined.
        thread.setDaemon(true);
        thread.start();
        return task;
    }

    /**
     * @return the reader's complete output, or {@code null} if it did not finish in time or
     *         failed. Null is the caller's cue to report a failed command.
     */
    private static String await(FutureTask<String> task) throws InterruptedException {
        try {
            return task.get(OUTPUT_HANDOFF_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | CancellationException e) {
            task.cancel(true);
            return null;
        } catch (ExecutionException e) {
            // The stream broke mid-read. Whatever was buffered is incomplete by definition.
            Log.w(TAG, "Stream reader failed: " + e.getCause());
            return null;
        }
    }

    private static String join(String[] argv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < argv.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(argv[i]);
        }
        return sb.toString();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 100 ? text.substring(0, 100) + "..." : text;
    }

    // ------------------------------------------------------------------
    // Convenience wrappers
    // ------------------------------------------------------------------

    /**
     * Execute a command with root privileges.
     *
     * @return the command's stdout, or {@code null} if it could not be started, timed out,
     *         or <b>exited non-zero</b>. It no longer returns {@code ""} for a failed
     *         command, so {@code execRoot(...) != null} is finally a valid success test.
     *         New code should prefer {@link #run(String)}, which also carries the exit code
     *         and stderr.
     */
    public static String execRoot(String command) {
        return execRoot(command, DEFAULT_TIMEOUT_MS);
    }

    /** {@link #execRoot(String)} with a custom timeout. */
    public static String execRoot(String command, int timeoutMs) {
        return run(command, timeoutMs).outputOrNull();
    }

    /**
     * Execute a command and return only its exit code.
     *
     * <p>Delegates to {@link #run(String)} so it drains both streams — the old
     * implementation did a bare {@code waitFor()} on an undrained process and would
     * deadlock on any command that filled a pipe.
     */
    public static int execRootCode(String command) {
        return run(command).exitCode();
    }

    /**
     * Check if root access is available. Cached for the life of the process.
     */
    public static boolean checkRoot() {
        // Snapshot: another thread can publish the cached answer between the null check and
        // the unboxing return - which would be an NPE if it published null (it never does,
        // but the read must not depend on that).
        Boolean cached = hasRoot;
        if (cached != null) {
            return cached;
        }

        RootResult result = run("id");
        boolean rooted = result.success() && result.stdout().contains("uid=0");
        hasRoot = rooted;
        Log.d(TAG, "Root check: " + (rooted ? "AVAILABLE" : "NOT AVAILABLE"));
        return rooted;
    }

    // ------------------------------------------------------------------
    // ALSA permissions
    // ------------------------------------------------------------------

    /**
     * Make the ALSA sound devices accessible to the app, so the native tinyalsa bridge can
     * open them.
     *
     * <p>{@code setenforce 0} runs <b>once per process</b> (it is a global, idempotent
     * setting); {@code chmod 666 /dev/snd/*} runs on <b>every</b> call, because the audio
     * HAL recreates those nodes whenever it reopens the card and the permission has to be
     * re-applied. That split is GW-20's; it lives here rather than at the call sites so
     * that "once per process" is the helper's own property.
     *
     * @return true if the {@code chmod} succeeded
     */
    public static boolean setupAlsaPermissions() {
        // Disable SELinux (required for direct ALSA access from the app).
        if (selinuxPermissive.compareAndSet(false, true)) {
            RootResult result = run("setenforce 0");
            if (result.success()) {
                Log.d(TAG, "SELinux set permissive (once per process)");
            } else {
                // Let the next open try again rather than skipping it forever.
                selinuxPermissive.set(false);
                Log.w(TAG, "setenforce 0 failed (exit " + result.exitCode() + "): "
                        + result.stderr());
            }
        }

        // Re-applied every call: the HAL recreates /dev/snd/* on each card open.
        RootResult chmod = run("chmod 666 /dev/snd/*");
        if (!chmod.success()) {
            Log.e(TAG, "Failed to chmod /dev/snd/* (exit " + chmod.exitCode() + "): "
                    + chmod.stderr());
            return false;
        }

        Log.d(TAG, "ALSA permissions set");
        return true;
    }

    /** Test-only: forget that {@code setenforce} has run, so the next call re-applies it. */
    static void resetSelinuxStateForTest() {
        selinuxPermissive.set(false);
    }

    // ------------------------------------------------------------------
    // Persistent shell - no callers, GW-31 deletes this block
    // ------------------------------------------------------------------

    /**
     * Start a persistent root shell for faster command execution.
     *
     * @deprecated No callers. GW-31 deletes it. The check-then-act below can spawn two
     *             {@code su} processes and orphan one.
     */
    @Deprecated
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
     * Execute command in the persistent root shell.
     *
     * @deprecated No callers. GW-31 deletes it.
     */
    @Deprecated
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
     * Stop the persistent root shell.
     *
     * @deprecated No callers. GW-31 deletes it.
     */
    @Deprecated
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

    // ------------------------------------------------------------------
    // File / permission helpers - no callers, GW-31 deletes this block
    // ------------------------------------------------------------------

    /**
     * Copy a file to a location requiring root.
     *
     * @deprecated No callers. GW-31 deletes it.
     */
    @Deprecated
    public static boolean copyFileAsRoot(String src, String dst) {
        return run("cp " + src + " " + dst + " && chmod 755 " + dst).success();
    }

    /**
     * Extract an asset to a file and make it executable.
     *
     * @deprecated No callers. GW-31 deletes it.
     */
    @Deprecated
    public static boolean extractAsset(android.content.Context context, String assetName,
                                       String destPath) {
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
     * Grant all runtime permissions to this package using root.
     *
     * @deprecated No callers — {@code ui/PermissionManager} has its own. GW-31 deletes it.
     */
    @Deprecated
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
            RootResult result = run("pm grant " + packageName + " " + permission);
            if (result.success()) {
                Log.d(TAG, "Granted: " + permission);
            } else {
                Log.w(TAG, "Failed to grant " + permission + " (exit " + result.exitCode()
                        + "): " + result.stderr());
            }
        }

        // Also set as default dialer/phone app
        run("cmd telecom set-default-dialer " + packageName);
        Log.d(TAG, "Set as default dialer");

        // Disable battery optimization
        run("dumpsys deviceidle whitelist +" + packageName);
        Log.d(TAG, "Added to battery whitelist");

        Log.d(TAG, "Permission grant complete");
    }
}
