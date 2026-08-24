package org.onetwoone.gateway;

import android.content.Context;
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
 *    (note: use {@code tinymix -D 0 -v "NAME"} to read one control — there is no
 *    {@code get} subcommand, see AUDIT B1c)
 * 2. Find controls for speaker (EAR_S, SPK, RCV, etc.) and mic (DEC Volume/MUX)
 * 3. Add a new preset below
 *
 * <h2>Threading — the mute is a lease, not a fire-and-forget action (AUDIT B1, G3)</h2>
 *
 * Muting used to cost roughly six seconds — measured at <b>~13 s</b> on the
 * {@code redmi_note_7} preset — because every control was read back with a
 * {@code su -c 'tinymix ...'} process spawn before being overwritten. Reads now go through
 * the JNI mixer bridge ({@link #NATIVE}), so the whole sequence is a dozen in-process
 * ioctls. The lease machinery below is unchanged: it was built for the slow path and is
 * simply cheaper to run now.
 *
 * The old API ran the mute from a throwaway {@code MuteControls} thread while the matching
 * {@code unmuteAll()} ran <em>synchronously on the main thread</em>, which gave two
 * failures:
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
    // The "device_mute_prefs" / "mute_preset" names that used to live here are gone: this
    // class reads and writes the preset through GatewayConfig, which is the only place a
    // preference file or key may be named (AUDIT H4).

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
        /**
         * Set an ENUM control to one of its item names.
         *
         * @return false if the write was refused. Callers must not assume success: see
         *         AUDIT B1d, where the kernel rejected a restore and the old {@code void}
         *         signature made it look like the restore had never run at all.
         */
        boolean setEnum(int card, String control, String value);

        /** Set an INT control. @return false if the write was refused (see AUDIT B1d). */
        boolean setValue(int card, String control, int value);

        /** @return the current item name, or "" if the control is missing or unreadable. */
        String getEnum(int card, String control);

        /** @return the current value, or -1 if the control is missing or unreadable. */
        int getValue(int card, String control);
    }

    /**
     * Production backend: every read and every write goes through the tinyalsa JNI bridge.
     *
     * <p>It used to read by shelling out to {@code su -c 'tinymix -D N get "name"'}, on the
     * stated grounds that the native bridge had no ENUM getter and that its INT getter
     * lacked the ALSA permissions {@code tinymix} obtains via {@code su}. Both were wrong,
     * and the consequence was <b>AUDIT B1c</b> — on Qualcomm, every gateway call left the
     * microphone dead:
     *
     * <ul>
     *   <li>That {@code tinymix} build has <b>no {@code get} subcommand</b>. Its usage is
     *       {@code tinymix [options] [control] [value]}, so {@code get} was parsed as the
     *       control <em>name</em> and the call failed with
     *       {@code Invalid mixer control: get}. On some devices {@code tinymix} is not
     *       installed at all.</li>
     *   <li>So every read returned the failure sentinel, nothing was ever recorded as an
     *       original, and {@code release()} faithfully restored an empty set. The mute
     *       writes — which already went through JNI — succeeded, so the controls went to
     *       {@code 0} and stayed there.</li>
     *   <li>{@link GsmAudioNative#getMixerControl} existed the whole time, and the writes
     *       succeeding is itself proof the permissions were there.</li>
     * </ul>
     *
     * <p>{@link GsmAudioNative#getMixerControlEnum} was added to close the one genuine gap.
     * This also removes ~1 s of process spawn per control: {@code muteAll} over the
     * {@code redmi_note_7} preset measured <b>~13 s</b> on device and is now a dozen
     * in-process ioctls.
     */
    static final MixerBackend NATIVE = new MixerBackend() {
        @Override
        public boolean setEnum(int card, String control, String value) {
            return GsmAudioNative.setMixerControlEnum(card, control, value);
        }

        @Override
        public boolean setValue(int card, String control, int value) {
            return GsmAudioNative.setMixerControl(card, control, value);
        }

        @Override
        public String getEnum(int card, String control) {
            // Native returns null for missing/non-ENUM/unreadable; the interface contract
            // here is "" for the same, so callers keep using isEmpty() as the readable test.
            String value = GsmAudioNative.getMixerControlEnum(card, control);
            return value == null ? "" : value;
        }

        @Override
        public int getValue(int card, String control) {
            return GsmAudioNative.getMixerControl(card, control);
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
            instance = new DeviceMuteManager(context.getApplicationContext(), NATIVE);
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

    /**
     * Context-bearing twin of {@link #forTesting(String, int, MixerBackend)}: real
     * preferences, still no {@code su} and no JNI. Needed to exercise
     * {@link #refreshFromConfig()}, which the context-free instance skips — the preset and
     * card come from {@link GatewayConfig} rather than being set directly.
     */
    static DeviceMuteManager forTesting(Context context, MixerBackend mixer) {
        return new DeviceMuteManager(context, mixer);
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
     * Load the saved preset and sound card, through {@link GatewayConfig} rather than by
     * opening {@code device_mute_prefs} / {@code gsm_audio_config} by name (AUDIT H4).
     *
     * <p>This is the initial load only. What matters at mute time is
     * {@link #refreshFromConfig()}, which re-reads both before every acquire.
     */
    private void loadPreset() {
        refreshFromConfig();
        Log.d(TAG, "Loaded preset: " + currentPreset + ", card: " + soundCard);
    }

    /**
     * Persist a preset and adopt it immediately.
     *
     * <p>Kept for callers that hold the manager; the UI and the web interface write through
     * {@link GatewayConfig#setMutePreset(String)} instead, and {@link #refreshFromConfig()}
     * is what makes those writes reach this object.
     */
    public void savePreset(String presetName) {
        currentPreset = presetName;
        if (context != null) {
            GatewayConfig.from(context).setMutePreset(presetName);
        }
        Log.d(TAG, "Saved preset: " + presetName);
    }

    /**
     * Set sound card number.
     *
     * <p>NOTE: the persisted half of this is dead. It writes {@code "sound_card"} into
     * {@code device_mute_prefs}, a key nothing has ever read — the card actually used comes
     * from {@code gsm_audio_config}'s {@code "card"} via {@link GatewayConfig#getAudioCard()}
     * and is re-read by {@link #refreshFromConfig()}. The method itself has no callers.
     * Left in place for <b>GW-31</b>'s dead-code sweep rather than deleted here; the write is
     * removed so it can no longer look like configuration.
     */
    public void setSoundCard(int card) {
        this.soundCard = card;
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

        refreshFromConfig();

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
     * <p><b>A control whose original cannot be read is left alone</b> (AUDIT B1c). The old
     * rule was "always try to set, even if we can't read current value", which is how the
     * microphone got bricked: reads were failing for every control, so the mute applied
     * twelve writes and recorded nothing to undo them. Muting something you cannot restore
     * trades a temporary problem for a permanent one.
     *
     * <p>The cost of the safer rule is that a genuinely unreadable control stays unmuted,
     * so the local mic may bleed into the GSM uplink on that device. That is an audio
     * defect the user can hear and report, not a phone that silently stops working.
     *
     * @return false if the lease was cancelled part-way through
     */
    private boolean mutePresetControls(long leaseId, int card, DevicePreset preset, List<Applied> applied) {
        // Mute speaker controls (ENUM -> ZERO)
        for (String control : preset.speakerControls) {
            if (!muteEnum(leaseId, card, control, applied, true, "speaker")) {
                return false;
            }
        }

        // Mute mic volume controls (INT -> 0)
        for (String control : preset.micVolumeControls) {
            if (!muteInt(leaseId, card, control, applied, true, "mic volume")) {
                return false;
            }
        }

        // Mute mic routing controls (ENUM -> ZERO)
        for (String control : preset.micRoutingControls) {
            if (!muteEnum(leaseId, card, control, applied, true, "mic routing")) {
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
            // Left alone on purpose: muting what we cannot restore is AUDIT B1c.
            Log.w(TAG, "Not muting " + what + " '" + control
                    + "': its current value could not be read, so it could not be restored");
            return true;
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
            // Left alone on purpose: muting what we cannot restore is AUDIT B1c.
            Log.w(TAG, "Not muting " + what + " '" + control
                    + "': its current value could not be read, so it could not be restored");
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
        int refused = 0;
        for (int i = applied.size() - 1; i >= 0; i--) {
            Applied a = applied.get(i);
            if (!a.restore(mixer)) {
                // AUDIT B1d — same failure mode as restoreHeld, and just as invisible
                // before the setters started reporting it.
                refused++;
                Log.e(TAG, "UNWIND REFUSED: " + a.control + " could not be set back to "
                        + a.originalForLog() + " - it is still muted");
            }
        }
        if (refused > 0) {
            Log.e(TAG, "Mute unwind incomplete: " + refused + " of " + applied.size()
                    + " control(s) remain muted (AUDIT B1d)");
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

        int refused = 0;
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            Applied a = snapshot.get(i);
            if (a.restore(mixer)) {
                Log.d(TAG, "Restored: " + a.control + " = " + a.originalForLog());
            } else {
                // AUDIT B1d. Do NOT log this as restored: the control is still muted and
                // the device is left in the broken state this whole class exists to avoid.
                refused++;
                Log.e(TAG, "RESTORE REFUSED: " + a.control + " could not be set back to "
                        + a.originalForLog() + " - it is still muted");
            }
        }
        if (refused > 0) {
            Log.e(TAG, "Mute restore incomplete: " + refused + " of " + snapshot.size()
                    + " control(s) refused the write and remain muted. On Qualcomm this is"
                    + " AUDIT B1d - the mic volume write is rejected once the call has torn"
                    + " down. The device will not have a working microphone until the audio"
                    + " path is re-established or the phone is rebooted.");
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
     * Re-read the sound card <em>and the preset</em> from {@link GatewayConfig}, so that a
     * change made since this singleton was built takes effect on the next call.
     *
     * <p>Only the card used to be refreshed. {@code currentPreset} was read once in the
     * constructor and {@link #savePreset(String)} had no callers, so a preset change from the
     * in-app UI ({@code MainViewModel.selectMutePreset}) or the web interface — both of which
     * write through {@code GatewayConfig} — updated the stored value and nothing else. The
     * live manager kept muting with the preset the process started with, and switching
     * <em>to</em> {@code custom} did nothing at all until the process was restarted (Phase 2
     * plan §2.5).
     */
    private void refreshFromConfig() {
        if (context == null) {
            return;   // forTesting(): no preferences, the fields are set directly
        }
        GatewayConfig config = GatewayConfig.from(context);

        int newCard = config.getAudioCard();
        if (newCard != soundCard) {
            Log.d(TAG, "Sound card changed: " + soundCard + " -> " + newCard);
            soundCard = newCard;
        }

        String newPreset = config.getMutePreset();
        if (!newPreset.equals(currentPreset)) {
            Log.i(TAG, "Mute preset changed: " + currentPreset + " -> " + newPreset);
            currentPreset = newPreset;
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

        /** @return false if the mixer refused to put the original back (AUDIT B1d). */
        boolean restore(MixerBackend mixer) {
            if (isEnum) {
                return mixer.setEnum(card, control, enumValue);
            }
            return mixer.setValue(card, control, intValue);
        }

        /** The original value, for logging. */
        String originalForLog() {
            return isEnum ? enumValue : Integer.toString(intValue);
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
