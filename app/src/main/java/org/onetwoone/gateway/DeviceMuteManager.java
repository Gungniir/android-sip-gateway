package org.onetwoone.gateway;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import org.onetwoone.gateway.config.GatewayConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Manages device-specific mute controls for speaker and microphone.
 *
 * Different Qualcomm devices have different mixer controls.
 * This class provides presets for known devices and allows custom configuration.
 *
 * To add support for a new device:
 * 1. Run: adb shell "su -c 'tinymix'" during an active call
 * 2. Find controls for speaker (EAR_S, SPK, RCV, etc.) and mic (DEC Volume/MUX)
 * 3. Add a new preset below
 *
 * <h2>Threading — the mute is a lease, not a fire-and-forget action (AUDIT B1, G3)</h2>
 *
 * Muting costs roughly six seconds: every control is read back with
 * {@code su -c 'tinymix get'} before it is overwritten. The old API ran that from a
 * throwaway {@code MuteControls} thread while the matching {@code unmuteAll()} ran
 * <em>synchronously on the main thread</em>, which gave two failures:
 *
 * <ul>
 *   <li>a call that ended before the mute thread was scheduled unmuted <em>first</em>
 *       (saw nothing muted, returned), then muted — and the phone had no microphone and
 *       no earpiece until it was rebooted;</li>
 *   <li>a call that ended mid-mute blocked main on the monitor for up to six seconds.</li>
 * </ul>
 *
 * Now a call holds a <b>lease</b>: {@link #newLease()} issues a monotonic id,
 * {@link #acquire(long)} mutes for it and {@link #release(long)} restores it. Both are
 * non-blocking and both do their mixer I/O on one private {@code MuteControls}
 * {@link HandlerThread}; nothing touches the mixer on main (see {@link #assertOffMain}).
 *
 * {@link #release(long)} clears {@link #currentLease} <em>synchronously</em>, on the
 * caller's thread, before it queues anything. That volatile write is the cancel signal:
 * an {@link #acquire(long)} already running on the mute thread re-checks it before every
 * single control write, and on a mismatch unwinds the controls it has written so far from
 * the originals it snapshotted before writing them. So a release can never be overtaken by
 * the mute it was meant to cancel, no matter how the two interleave.
 *
 * A lease held longer than {@link #MUTE_MAX_HOLD_MS} is force-restored as a backstop for
 * any interleaving not anticipated here.
 */
public class DeviceMuteManager {
    private static final String TAG = "DeviceMute";
    private static final String PREFS_NAME = "device_mute_prefs";
    private static final String PREF_PRESET = "mute_preset";

    /** "No lease is held." Lease ids issued by {@link #newLease()} start at 1. */
    public static final long NO_LEASE = 0L;

    /**
     * A lease held longer than this is force-restored with an error log. Four hours is
     * well beyond any real GSM call, and short enough that an unattended gateway phone
     * recovers its microphone on its own if some interleaving still slips through.
     */
    public static final long MUTE_MAX_HOLD_MS = 4L * 60L * 60L * 1000L;

    // Preset names
    public static final String PRESET_CUSTOM = "custom";
    public static final String PRESET_REDMI_NOTE_7 = "redmi_note_7";      // SDM660
    public static final String PRESET_GENERIC = "generic";                // Generic SDM4xx
    public static final String PRESET_REDMI_4X = "redmi_4x";              // MSM8940 / SD435

    // ============================================================
    // DEVICE PRESETS - Edit these for your device!
    // ============================================================

    private static final Map<String, DevicePreset> PRESETS = new HashMap<>();

    static {
        // Redmi Note 7 (SDM660) - tested on LineageOS 17.1
        PRESETS.put(PRESET_REDMI_NOTE_7, new DevicePreset(
            "Redmi Note 7 (SDM660)",
            new String[] {
                // Speaker/Earpiece mute (ENUM -> ZERO)
                "EAR_S",
                "SPK"
            },
            new String[] {
                // Microphone mute (INT -> 0)
                "DEC1 Volume",
                "DEC2 Volume",
                "DEC3 Volume",
                "DEC4 Volume",
                "DEC5 Volume"
            },
            new String[] {
                // Microphone routing mute (ENUM -> ZERO)
                "DEC1 MUX",
                "DEC2 MUX",
                "DEC3 MUX",
                "DEC4 MUX",
                "DEC5 MUX"
            }
        ));

        // Generic preset for SDM4xx devices (SD425, SD435, etc.)
        PRESETS.put(PRESET_GENERIC, new DevicePreset(
            "Generic (SDM4xx)",
            new String[] {
                // Speaker mute - check with tinymix on your device!
                "EAR_S",
                "SPK"
            },
            new String[] {
                // Microphone mute
                "DEC1 Volume",
                "DEC2 Volume",
                "DEC3 Volume",
                "DEC4 Volume"
            },
            new String[] {
                // Microphone routing
                "DEC1 MUX",
                "DEC2 MUX",
                "DEC3 MUX",
                "DEC4 MUX"
            }
        ));

        // Redmi 4X (MSM8940 / Snapdragon 435)
        PRESETS.put(PRESET_REDMI_4X, new DevicePreset(
            "Redmi 4X (SD435)",
            new String[] {
                "EAR_S",
                "SPK"
            },
            new String[] {
                "DEC1 Volume",
                "DEC2 Volume",
                "DEC3 Volume",
                "DEC4 Volume"
            },
            new String[] {
                "DEC1 MUX",
                "DEC2 MUX",
                "DEC3 MUX",
                "DEC4 MUX"
            }
        ));
    }

    // ============================================================
    // Mixer backend (test seam)
    // ============================================================

    /**
     * The mixer operations the mute path needs. A test seam and nothing more, modelled on
     * {@link org.onetwoone.gateway.audio.MixerControls}: the lease/unwind logic is what has
     * to be provably correct under concurrency, and that can only be exercised on the JVM,
     * where there is no sound card, no JNI library and no root shell.
     *
     * Do not grow it into a general-purpose mixer abstraction.
     */
    public interface MixerBackend {
        /** Set an ENUM control to one of its item names. */
        void setEnum(int card, String control, String value);

        /** Set an INT control. */
        void setValue(int card, String control, int value);

        /** @return the current item name, or "" if the control is missing or unreadable. */
        String getEnum(int card, String control);

        /** @return the current value, or -1 if the control is missing or unreadable. */
        int getValue(int card, String control);
    }

    /**
     * Production backend: writes go through the tinyalsa JNI bridge, reads shell out to
     * {@code tinymix} (the native bridge has no ENUM getter, and the INT getter needs the
     * ALSA permissions that {@code tinymix} obtains for itself via {@code su}).
     *
     * This is where the ~6 s of {@code muteAll} lives — every read is a process spawn.
     */
    static final MixerBackend TINYMIX = new MixerBackend() {
        @Override
        public void setEnum(int card, String control, String value) {
            GsmAudioNative.setMixerControlEnum(card, control, value);
        }

        @Override
        public void setValue(int card, String control, int value) {
            GsmAudioNative.setMixerControl(card, control, value);
        }

        @Override
        public String getEnum(int card, String control) {
            String line = tinymixGet(card, control);
            if (line != null && !line.isEmpty()) {
                // Parse output like "EAR_S: ZERO >Switch" -> return current value.
                // The current value has a > prefix.
                String[] parts = line.split("\\s+");
                for (String part : parts) {
                    if (part.startsWith(">")) {
                        return part.substring(1);
                    }
                }
                // Fallback: return last part
                if (parts.length > 1) {
                    return parts[parts.length - 1];
                }
            }
            return "";
        }

        @Override
        public int getValue(int card, String control) {
            String line = tinymixGet(card, control);
            if (line != null && !line.isEmpty()) {
                // Parse output like "DEC1 Volume: 84" -> return 84
                String[] parts = line.split("\\s+");
                for (String part : parts) {
                    try {
                        return Integer.parseInt(part);
                    } catch (NumberFormatException ignored) {
                        // keep scanning
                    }
                }
            }
            return -1;
        }

        private String tinymixGet(int card, String name) {
            try {
                String cmd = "su -c 'tinymix -D " + card + " get \"" + name + "\"'";
                Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
                String line = reader.readLine();
                p.waitFor();
                return line;
            } catch (Exception e) {
                Log.e(TAG, "Failed to read control " + name + ": " + e.getMessage());
                return null;
            }
        }
    };

    // ============================================================
    // Instance fields
    // ============================================================

    private final Context context;
    private final MixerBackend mixer;

    private volatile int soundCard = 0;
    private volatile String currentPreset = PRESET_CUSTOM;

    /**
     * The lease {@link #acquire(long)} is currently allowed to mute for, or {@link #NO_LEASE}.
     *
     * This is the cancel flag. {@link #release(long)} clears it on the caller's thread before
     * queueing anything, and the mute worker re-reads it before every control write.
     */
    private volatile long currentLease = NO_LEASE;

    /** Guards the lease bookkeeping below and every {@link #currentLease} transition. */
    private final Object leaseLock = new Object();
    private long lastIssuedLease = NO_LEASE;      // guarded by leaseLock
    private long lastAcquiredLease = NO_LEASE;    // guarded by leaseLock

    /**
     * Highest lease ever released, whether or not it had been acquired yet.
     *
     * This is what makes a release that overtakes its own acquire safe: the release records
     * the id here, and {@link #acquire(long)} refuses any id at or below it. Without this,
     * a release landing in the window between "the caller decided to release lease N" and
     * "acquire(N) registered N" would be a no-op and the mute would go on to land anyway —
     * which is AUDIT B1 exactly.
     */
    private long lastReleasedLease = NO_LEASE;    // guarded by leaseLock

    /**
     * Controls written by the lease that completed, newest last — an immutable snapshot,
     * published by one volatile write from the mute thread once the mute has fully landed.
     * Empty whenever nothing is muted.
     */
    private volatile List<Applied> held = Collections.emptyList();

    private volatile boolean isMuted = false;

    /** Overridable so a test can watch the fail-safe fire without waiting four hours. */
    private volatile long muteMaxHoldMs = MUTE_MAX_HOLD_MS;

    /** Single-threaded mixer worker. All mute/unmute I/O runs here and only here. */
    private final HandlerThread muteThread;
    private final Handler muteHandler;

    /** Force-restore backstop for a lease nobody released. */
    private final Runnable failSafeRunnable = new Runnable() {
        @Override
        public void run() {
            long stuck = currentLease;
            if (stuck == NO_LEASE && held.isEmpty()) {
                return;
            }
            Log.e(TAG, "Mute lease " + stuck + " held for more than " + muteMaxHoldMs
                + " ms - force restoring. This should never happen; a call's release was lost.");
            synchronized (leaseLock) {
                currentLease = NO_LEASE;
            }
            restoreHeld("fail-safe");
        }
    };

    /** Restores whatever the last completed acquire published. */
    private final Runnable restoreRunnable = new Runnable() {
        @Override
        public void run() {
            restoreHeld("release");
        }
    };

    // Singleton
    private static DeviceMuteManager instance;

    public static synchronized DeviceMuteManager getInstance(Context context) {
        if (instance == null) {
            instance = new DeviceMuteManager(context.getApplicationContext(), TINYMIX);
        }
        return instance;
    }

    private DeviceMuteManager(Context context, MixerBackend mixer) {
        this.context = context;
        this.mixer = mixer;
        this.muteThread = new HandlerThread("MuteControls");
        this.muteThread.start();
        this.muteHandler = new Handler(this.muteThread.getLooper());
        if (context != null) {
            loadPreset();
        }
    }

    /**
     * Context-free instance for JVM tests: no SharedPreferences, no {@code su}, no JNI.
     * The {@code MuteControls} thread is real, so tests exercise the production ordering.
     */
    static DeviceMuteManager forTesting(String preset, int card, MixerBackend mixer) {
        DeviceMuteManager m = new DeviceMuteManager(null, mixer);
        m.currentPreset = preset;
        m.soundCard = card;
        return m;
    }

    /** Test-only: shrink the fail-safe deadline. */
    void setMuteMaxHoldMsForTest(long ms) {
        this.muteMaxHoldMs = ms;
    }

    /** Test-only: the mute worker's looper, for barrier posts. */
    Looper muteLooperForTest() {
        return muteThread.getLooper();
    }

    /** Test-only: retire the worker thread so a test can build hundreds of managers. */
    void quitForTest() {
        muteThread.quitSafely();
    }

    /**
     * Load saved preset from SharedPreferences
     */
    private void loadPreset() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        currentPreset = prefs.getString(PREF_PRESET, PRESET_REDMI_NOTE_7);  // Default

        // Read sound card from gsm_audio_config (same as GsmAudioPort uses)
        SharedPreferences audioPrefs = context.getSharedPreferences("gsm_audio_config", Context.MODE_PRIVATE);
        soundCard = audioPrefs.getInt("card", 0);

        Log.d(TAG, "Loaded preset: " + currentPreset + ", card: " + soundCard);
    }

    /**
     * Save current preset to SharedPreferences
     */
    public void savePreset(String presetName) {
        currentPreset = presetName;
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putString(PREF_PRESET, presetName);
        editor.apply();
        Log.d(TAG, "Saved preset: " + presetName);
    }

    /**
     * Set sound card number
     */
    public void setSoundCard(int card) {
        this.soundCard = card;
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putInt("sound_card", card);
        editor.apply();
    }

    // Ordered list of preset keys to ensure consistent iteration
    private static final String[] PRESET_ORDER = {
        PRESET_REDMI_NOTE_7,
        PRESET_GENERIC,
        PRESET_REDMI_4X,
        PRESET_CUSTOM
    };

    /**
     * Get list of available preset names (in consistent order)
     */
    public static String[] getPresetNames() {
        return PRESET_ORDER.clone();
    }

    /**
     * Get human-readable preset descriptions (matching order of getPresetNames)
     */
    public static String[] getPresetDescriptions() {
        String[] descriptions = new String[PRESET_ORDER.length];
        for (int i = 0; i < PRESET_ORDER.length; i++) {
            if (PRESET_ORDER[i].equals(PRESET_CUSTOM)) {
                descriptions[i] = "Custom (select controls manually)";
            } else {
                DevicePreset preset = PRESETS.get(PRESET_ORDER[i]);
                descriptions[i] = (preset != null) ? preset.description : PRESET_ORDER[i];
            }
        }
        return descriptions;
    }

    /**
     * Check if current preset is custom
     */
    public boolean isCustomPreset() {
        return PRESET_CUSTOM.equals(currentPreset);
    }

    /**
     * Get current preset name
     */
    public String getCurrentPreset() {
        return currentPreset;
    }

    /**
     * Check if currently muted
     */
    public boolean isMuted() {
        return isMuted;
    }

    /** @return the lease currently held, or {@link #NO_LEASE}. */
    public long heldLease() {
        return currentLease;
    }

    // ============================================================
    // MUTE LEASE
    // ============================================================

    /**
     * Issue the next lease id. Monotonic and never repeated, so a stale
     * {@link #release(long)} can always be told apart from a live one.
     */
    public long newLease() {
        synchronized (leaseLock) {
            return ++lastIssuedLease;
        }
    }

    /**
     * Mute speaker + microphone for {@code leaseId}. Returns immediately; the mixer I/O
     * runs on the {@code MuteControls} thread.
     *
     * If {@link #release(long)} for this lease arrives before the worker starts, nothing is
     * muted at all. If it arrives part-way through, the controls already written are put
     * back before the worker returns.
     */
    public void acquire(long leaseId) {
        synchronized (leaseLock) {
            if (leaseId <= NO_LEASE || leaseId > lastIssuedLease || leaseId <= lastAcquiredLease) {
                Log.e(TAG, "Refusing acquire for bogus lease " + leaseId
                    + " (issued=" + lastIssuedLease + ", acquired=" + lastAcquiredLease + ")");
                return;
            }
            if (leaseId <= lastReleasedLease) {
                // The call already ended. Mute nothing at all — this is the interleaving
                // that used to leave the phone without a microphone (AUDIT B1).
                Log.w(TAG, "Lease " + leaseId + " was released before it was acquired - muting nothing");
                lastAcquiredLease = leaseId;
                return;
            }
            lastAcquiredLease = leaseId;
            currentLease = leaseId;
        }

        muteHandler.removeCallbacks(failSafeRunnable);
        muteHandler.post(new Runnable() {
            @Override
            public void run() {
                runAcquire(leaseId);
            }
        });
        muteHandler.postDelayed(failSafeRunnable, muteMaxHoldMs);
    }

    /**
     * Give up {@code leaseId} and restore every control it muted.
     *
     * Cheap and non-blocking — safe to call from the call-teardown path (AUDIT H2c: that
     * path already has a ~1.75 s main-thread worst case and must not grow). A release for a
     * lease that was already released or superseded is a no-op.
     */
    public void release(long leaseId) {
        boolean owned;
        synchronized (leaseLock) {
            if (leaseId <= NO_LEASE) {
                return;
            }
            if (leaseId > lastReleasedLease) {
                // Poison it even if acquire(leaseId) has not run yet — see lastReleasedLease.
                lastReleasedLease = leaseId;
            }
            owned = (currentLease == leaseId);
            if (owned) {
                // The cancel signal. Published before anything is queued, so an acquire
                // already running on the mute thread sees it at its next per-control re-check.
                currentLease = NO_LEASE;
            }
        }

        if (!owned) {
            // Already released, superseded by a newer lease, or cancelled before it started.
            // In every one of those cases this lease owns no controls, so there is nothing
            // to put back and the live lease (if any) must be left alone.
            return;
        }

        muteHandler.removeCallbacks(failSafeRunnable);
        muteHandler.post(restoreRunnable);
    }

    /**
     * Block until every queued mute/unmute has drained, up to {@code timeoutMs}.
     *
     * <b>Service teardown only.</b> Not for the per-call path — {@link #release(long)} is
     * already asynchronous there. This exists so {@code onDestroy} can give the restore a
     * chance to land before the process goes away.
     *
     * @return true if the worker drained within the timeout
     */
    public boolean awaitRestore(long timeoutMs) {
        final CountDownLatch drained = new CountDownLatch(1);
        if (!muteHandler.post(new Runnable() {
            @Override
            public void run() {
                drained.countDown();
            }
        })) {
            return false;
        }
        try {
            return drained.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ============================================================
    // Mute worker — everything below runs on the MuteControls thread
    // ============================================================

    private void runAcquire(long leaseId) {
        assertOffMain("acquire");

        if (currentLease != leaseId) {
            Log.w(TAG, "Lease " + leaseId + " was released before the mute started - muting nothing");
            return;
        }

        // Defensive: a previous lease that was never released would otherwise have its
        // originals overwritten by this one's reads, and could never be restored.
        if (!held.isEmpty()) {
            Log.w(TAG, "Lease " + leaseId + " starting on top of an unreleased mute - restoring first");
            restoreHeld("superseded");
        }

        refreshSoundCard();

        int card = soundCard;
        String preset = currentPreset;
        Log.d(TAG, "Muting all controls (lease: " + leaseId + ", preset: " + preset + ")");

        // Originals are snapshotted into `applied` immediately BEFORE each write, so a
        // cancel can only ever over-restore (write a control back to the value it already
        // has), never under-restore. Under-restoring is the brick.
        List<Applied> applied = new ArrayList<>();
        boolean completed;

        if (PRESET_CUSTOM.equals(preset)) {
            completed = muteCustomControls(leaseId, card, applied);
        } else {
            DevicePreset def = PRESETS.get(preset);
            if (def == null) {
                Log.w(TAG, "Unknown preset: " + preset);
                return;
            }
            completed = mutePresetControls(leaseId, card, def, applied);
        }

        if (!completed) {
            Log.w(TAG, "Lease " + leaseId + " cancelled after " + applied.size()
                + " control writes - unwinding");
            unwind(applied);
            // Publish nothing: the release that cancelled us has already queued
            // restoreRunnable, and it must find no work left to do.
            return;
        }

        held = Collections.unmodifiableList(applied);
        isMuted = true;
        Log.d(TAG, "Lease " + leaseId + " muted " + applied.size() + " controls");
    }

    /**
     * Mute controls for a device preset.
     *
     * @return false if the lease was cancelled part-way through
     */
    private boolean mutePresetControls(long leaseId, int card, DevicePreset preset, List<Applied> applied) {
        // Mute speaker controls (ENUM -> ZERO)
        // Always try to set, even if we can't read current value
        for (String control : preset.speakerControls) {
            if (!muteEnum(leaseId, card, control, applied, false, "speaker")) {
                return false;
            }
        }

        // Mute mic volume controls (INT -> 0)
        for (String control : preset.micVolumeControls) {
            if (!muteInt(leaseId, card, control, applied, false, "mic volume")) {
                return false;
            }
        }

        // Mute mic routing controls (ENUM -> ZERO)
        for (String control : preset.micRoutingControls) {
            if (!muteEnum(leaseId, card, control, applied, false, "mic routing")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Mute controls from custom configuration (checkbox + manual).
     *
     * Unlike the device presets, a custom control is only written when its current value
     * could be read back — an unreadable control is left alone.
     *
     * @return false if the lease was cancelled part-way through
     */
    private boolean muteCustomControls(long leaseId, int card, List<Applied> applied) {
        GatewayConfig config = GatewayConfig.getInstance();
        java.util.Set<String> controls = config.getAllMuteControls();

        if (controls.isEmpty()) {
            Log.w(TAG, "Custom preset but no controls configured");
            return true;
        }

        for (String raw : controls) {
            String control = raw.trim();
            if (control.isEmpty()) continue;

            boolean ok;
            if (control.contains(" Volume")) {
                // INT control - set to 0
                ok = muteInt(leaseId, card, control, applied, true, "custom");
            } else {
                // ENUM control (MUX, EAR_S, SPK) - set to ZERO
                ok = muteEnum(leaseId, card, control, applied, true, "custom");
            }
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * Snapshot one ENUM control's original value, then mute it to ZERO.
     *
     * @param requireRead when true the control is only written if its original could be read
     * @return false if the lease was cancelled before this control was touched
     */
    private boolean muteEnum(long leaseId, int card, String control, List<Applied> applied,
                             boolean requireRead, String what) {
        if (currentLease != leaseId) {
            return false;
        }

        String original = mixer.getEnum(card, control);
        boolean readable = original != null && !original.isEmpty();

        if (readable) {
            // Recorded BEFORE the write, so an unwind can always put it back.
            applied.add(Applied.forEnum(card, control, original));
        } else if (requireRead) {
            return true;   // custom preset: unreadable controls are left alone
        }

        // Re-check: release may have landed while tinymix was running.
        if (currentLease != leaseId) {
            if (readable) {
                applied.remove(applied.size() - 1);   // never written, nothing to unwind
            }
            return false;
        }

        mixer.setEnum(card, control, "ZERO");
        Log.d(TAG, "Muted " + what + ": " + control + " (was: " + original + ")");
        return true;
    }

    /**
     * Snapshot one INT control's original value, then mute it to 0.
     *
     * @param requireRead when true the control is only written if its original could be read
     * @return false if the lease was cancelled before this control was touched
     */
    private boolean muteInt(long leaseId, int card, String control, List<Applied> applied,
                            boolean requireRead, String what) {
        if (currentLease != leaseId) {
            return false;
        }

        int original = mixer.getValue(card, control);
        boolean readable = original >= 0;

        if (readable) {
            applied.add(Applied.forValue(card, control, original));
        } else if (requireRead) {
            return true;
        }

        if (currentLease != leaseId) {
            if (readable) {
                applied.remove(applied.size() - 1);
            }
            return false;
        }

        mixer.setValue(card, control, 0);
        Log.d(TAG, "Muted " + what + ": " + control + " (was: " + original + ")");
        return true;
    }

    /** Put back exactly the controls a cancelled acquire wrote, newest first. */
    private void unwind(List<Applied> applied) {
        assertOffMain("unwind");
        for (int i = applied.size() - 1; i >= 0; i--) {
            applied.get(i).restore(mixer);
        }
    }

    /** Put back everything the last completed acquire published. */
    private void restoreHeld(String reason) {
        assertOffMain("restore");
        List<Applied> snapshot = held;
        if (snapshot.isEmpty()) {
            isMuted = false;
            return;
        }

        Log.d(TAG, "Restoring " + snapshot.size() + " controls (" + reason + ")");
        // Published before the writes: if this thread dies mid-restore, the next acquire
        // must not think there is still something to unwind.
        held = Collections.emptyList();
        isMuted = false;

        for (int i = snapshot.size() - 1; i >= 0; i--) {
            Applied a = snapshot.get(i);
            a.restore(mixer);
            Log.d(TAG, "Restored: " + a.control);
        }
    }

    /**
     * Force mute all controls (called periodically by watchdog to combat Android re-routing)
     */
    public void enforceMute() {
        muteHandler.post(new Runnable() {
            @Override
            public void run() {
                assertOffMain("enforce");
                if (currentLease == NO_LEASE) {
                    return;   // nothing is leased; do not re-mute behind a finished call
                }

                int card = soundCard;
                String preset = currentPreset;

                if (PRESET_CUSTOM.equals(preset)) {
                    // Custom preset: re-enforce all stored controls
                    for (Applied a : held) {
                        a.mute(mixer);
                    }
                } else {
                    // Device preset: enforce ALL controls (speaker + mic)
                    DevicePreset def = PRESETS.get(preset);
                    if (def == null) return;

                    // Speaker controls
                    for (String control : def.speakerControls) {
                        mixer.setEnum(card, control, "ZERO");
                    }
                    // Mic volume controls
                    for (String control : def.micVolumeControls) {
                        mixer.setValue(card, control, 0);
                    }
                    // Mic routing controls
                    for (String control : def.micRoutingControls) {
                        mixer.setEnum(card, control, "ZERO");
                    }
                }
            }
        });
    }

    /**
     * Refresh sound card setting from SharedPreferences
     */
    private void refreshSoundCard() {
        if (context == null) {
            return;
        }
        SharedPreferences audioPrefs = context.getSharedPreferences("gsm_audio_config", Context.MODE_PRIVATE);
        int newCard = audioPrefs.getInt("card", 0);
        if (newCard != soundCard) {
            Log.d(TAG, "Sound card changed: " + soundCard + " -> " + newCard);
            soundCard = newCard;
        }
    }

    /**
     * AUDIT G3: the restore used to run on main and could block it for six seconds. Nothing
     * that touches the mixer may go back there — fail loudly in debug if it ever does.
     */
    private static void assertOffMain(String what) {
        if (BuildConfig.DEBUG && Looper.myLooper() != null && Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("DeviceMuteManager." + what + " on the main thread");
        }
    }

    // ============================================================
    // Applied control — one entry per control this lease wrote
    // ============================================================

    /**
     * A control that was (or is about to be) muted, together with the card and the exact
     * value it had beforehand. Immutable, so an unwind and a restore can never disagree
     * about what "original" meant.
     */
    private static final class Applied {
        final int card;
        final String control;
        final boolean isEnum;
        final String enumValue;
        final int intValue;

        private Applied(int card, String control, boolean isEnum, String enumValue, int intValue) {
            this.card = card;
            this.control = control;
            this.isEnum = isEnum;
            this.enumValue = enumValue;
            this.intValue = intValue;
        }

        static Applied forEnum(int card, String control, String original) {
            return new Applied(card, control, true, original, -1);
        }

        static Applied forValue(int card, String control, int original) {
            return new Applied(card, control, false, null, original);
        }

        void restore(MixerBackend mixer) {
            if (isEnum) {
                mixer.setEnum(card, control, enumValue);
            } else {
                mixer.setValue(card, control, intValue);
            }
        }

        void mute(MixerBackend mixer) {
            if (isEnum) {
                mixer.setEnum(card, control, "ZERO");
            } else {
                mixer.setValue(card, control, 0);
            }
        }
    }

    // ============================================================
    // Device Preset class
    // ============================================================

    private static class DevicePreset {
        String description;
        String[] speakerControls;      // ENUM controls for speaker/earpiece
        String[] micVolumeControls;    // INT controls for mic volume
        String[] micRoutingControls;   // ENUM controls for mic routing

        DevicePreset(String description, String[] speaker, String[] micVol, String[] micRoute) {
            this.description = description;
            this.speakerControls = speaker;
            this.micVolumeControls = micVol;
            this.micRoutingControls = micRoute;
        }
    }
}
