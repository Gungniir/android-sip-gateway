package org.onetwoone.gateway.config;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Centralized configuration management for the GSM-SIP Gateway.
 *
 * <p>Handles all SharedPreferences access and provides type-safe getters/setters.
 * All magic numbers and default values are defined here.
 *
 * <h2>This is the only place that may name a preference file or key (AUDIT H4)</h2>
 *
 * The web interface used to open {@code gsm_audio_config} itself and write the selected
 * mute controls as a {@code StringSet} under {@code "mic_mute_controls"}, while this class
 * read a comma-separated {@code String} under {@code "mic_mute_decs"}. Nothing read the web
 * server's key, so every mute control ticked in the web UI was silently discarded — and the
 * custom preset muted nothing, leaving the local microphone live on the GSM leg. That drift
 * was possible only because five components outside this package opened SharedPreferences
 * by raw name. They no longer do: every read and write goes through a typed accessor here,
 * so a key can no longer be misspelled in one component and not another.
 *
 * <p>Writes that touch more than one value go through {@link #edit()}, which batches into
 * one {@link SharedPreferences.Editor} per preference file and applies each exactly once —
 * a reader can no longer observe a half-applied configuration.
 *
 * <p>{@link #init(Context)} runs the one-shot migration for the legacy key and must
 * therefore happen before anything reads config. It is called from
 * {@code GatewayApplication.onCreate}, and {@link #from(Context)} forces it for any entry
 * point that might still reach config first.
 */
public class GatewayConfig {

    private static final String TAG = "GatewayConfig";

    // ========== Preference file names ==========
    private static final String PREFS_GATEWAY = "gateway_prefs";
    private static final String PREFS_AUDIO = "gsm_audio_config";
    private static final String PREFS_MUTE = "device_mute_prefs";

    // ========== Timing constants ==========
    public static final int RECONNECT_INITIAL_DELAY_MS = 5000;
    public static final int RECONNECT_MAX_DELAY_MS = 60000;
    public static final int RECONNECT_MULTIPLIER = 2;
    public static final int WATCHDOG_INTERVAL_MS = 3000;
    public static final long GSM_CALL_GRACE_PERIOD_MS = 5000;
    public static final int PJSIP_WORKER_SLEEP_MS = 10;

    // ========== Network constants ==========
    public static final int DEFAULT_SIP_PORT = 5060;
    public static final int DEFAULT_SIP_TLS_PORT = 5061;
    public static final int WEB_SERVER_PORT = 8080;

    // ========== Battery constants ==========
    public static final int DEFAULT_BATTERY_LIMIT = 60;
    public static final int CRITICAL_BATTERY_LEVEL = 20;
    public static final int BATTERY_HYSTERESIS = 5;

    // ========== Default SIP values ==========
    private static final String DEFAULT_SIP_SERVER = "";
    private static final String DEFAULT_SIP_USER = "";
    private static final String DEFAULT_SIP_PASSWORD = "";
    private static final String DEFAULT_SIP_REALM = "*";
    private static final boolean DEFAULT_USE_TLS = false;

    // ========== Default SIM destinations ==========
    private static final String DEFAULT_SIM1_DESTINATION = "";
    private static final String DEFAULT_SIM2_DESTINATION = "";

    // ========== Default audio values ==========
    private static final int DEFAULT_AUDIO_CARD = 0;
    private static final String DEFAULT_MULTIMEDIA_ROUTE = "MultiMedia1";
    public static final String DEFAULT_MUTE_PRESET = "redmi_note_7";
    public static final String DEFAULT_AUDIO_PROFILE = "auto";

    // ========== Preference keys ==========
    private static final String KEY_SIP_SERVER = "sip_server";
    private static final String KEY_SIP_PORT = "sip_port";
    private static final String KEY_SIP_USER = "sip_user";
    private static final String KEY_SIP_PASSWORD = "sip_password";
    private static final String KEY_SIP_REALM = "sip_realm";
    private static final String KEY_USE_TLS = "use_tls";
    private static final String KEY_SIM1_DESTINATION = "sim1_destination";
    private static final String KEY_SIM2_DESTINATION = "sim2_destination";
    private static final String KEY_INCOMING_CALL_MODE = "incoming_call_mode";
    private static final String KEY_BATTERY_LIMIT = "battery_limit";
    private static final String KEY_WEB_INTERFACE_ENABLED = "web_interface_enabled";
    private static final String KEY_AUDIO_CARD = "card";
    private static final String KEY_CAPTURE_DEVICE = "capture_device";
    private static final String KEY_PLAYBACK_DEVICE = "playback_device";
    private static final String KEY_MULTIMEDIA_ROUTE = "multimedia_route";
    private static final String KEY_MUTE_PRESET = "mute_preset";
    private static final String KEY_AUDIO_PROFILE = "audio_profile";  // auto | qualcomm | mediatek
    private static final String KEY_TX_GAIN = "tx_gain";  // GSM → SIP
    private static final String KEY_RX_GAIN = "rx_gain";  // SIP → GSM
    private static final String KEY_TEST_DESTINATION = "test_destination";
    private static final String KEY_TEST_MODE = "test_mode";  // tone | loopback | bridge
    private static final String KEY_VERBOSE_SIP_LOG = "verbose_sip_log";
    private static final String KEY_DTMF_RELAY = "dtmf_relay";
    private static final String KEY_MANUAL_MUTE_CONTROLS = "manual_mute_controls";

    /**
     * Whether the commissioning wizard has been dismissed at least once (GW-42).
     *
     * <p>The key lives here because this class is the only one allowed to name a preference
     * key at all, not because the flag is gateway configuration - it is first-run state for
     * {@code ui/setup}. It is deliberately in {@code gateway_prefs} rather than a fourth
     * preference file: a new file would be a fourth thing to migrate and a fourth thing for
     * a future audit to find.
     *
     * <p><b>Skipping counts as done.</b> The wizard writes it on any dismissal the operator
     * chose - finishing, skipping through, or closing it - because a wizard that reappears
     * on every launch until it is *completed* is the same trap as one that blocks: on a
     * half-provisioned phone you can never get past it. Re-running is an explicit action
     * from the System section, not something the app decides for you.
     */
    private static final String KEY_SETUP_COMPLETED = "setup_completed";

    /**
     * The canonical mic-mute control list: a comma-separated {@code String} in
     * {@code gsm_audio_config}. Package-private so the migration test can assert on the
     * stored representation rather than only on what the getters return.
     */
    static final String KEY_MIC_MUTE_CONTROLS = "mic_mute_decs";

    /**
     * The web interface's old key for the same list, written as a {@code StringSet} and read
     * by nothing (AUDIT H4). {@link #migrateMicMuteControls()} folds it into
     * {@link #KEY_MIC_MUTE_CONTROLS} and then removes it.
     */
    static final String KEY_MIC_MUTE_CONTROLS_LEGACY = "mic_mute_controls";

    // ========== Default SIP diagnostics values ==========
    // *43 is the FreePBX echo test: Asterisk answers and echoes audio back, so a single
    // call exercises both media directions without a second party.
    private static final String DEFAULT_TEST_DESTINATION = "*43";
    private static final String DEFAULT_TEST_MODE = "tone";

    // ========== Default audio device values ==========
    private static final int DEFAULT_CAPTURE_DEVICE = 0;
    private static final int DEFAULT_PLAYBACK_DEVICE = 0;

    // ========== Default gain values (in dB, 0 = unity, negative = quieter) ==========
    private static final float DEFAULT_TX_GAIN = 0.0f;   // GSM→SIP default 0dB (unity)
    private static final float DEFAULT_RX_GAIN = 0.0f;   // SIP→GSM default 0dB (unity)

    private final SharedPreferences gatewayPrefs;
    private final SharedPreferences audioPrefs;
    private final SharedPreferences mutePrefs;

    /**
     * The singleton. {@code volatile} because {@link #init(Context)} is {@code synchronized}
     * but {@link #getInstance()} is not: without it a reader on another thread may observe
     * the reference before the constructor's writes to {@code gatewayPrefs} /
     * {@code audioPrefs} / {@code mutePrefs} are visible, i.e. a partially constructed
     * instance. Readers span main, the mute thread, NanoHTTPD workers and pjsua workers
     * (AUDIT H5).
     */
    private static volatile GatewayConfig instance;

    private GatewayConfig(Context context) {
        Context appContext = context.getApplicationContext();
        this.gatewayPrefs = appContext.getSharedPreferences(PREFS_GATEWAY, Context.MODE_PRIVATE);
        this.audioPrefs = appContext.getSharedPreferences(PREFS_AUDIO, Context.MODE_PRIVATE);
        this.mutePrefs = appContext.getSharedPreferences(PREFS_MUTE, Context.MODE_PRIVATE);
    }

    /**
     * Initialize the singleton instance. Called from {@code GatewayApplication.onCreate} so
     * that it precedes every component in the process, and idempotent so the components that
     * already call it (services, the ViewModel) keep working unchanged.
     *
     * <p>The migration runs here, on the instance, <em>before</em> it is published: no reader
     * can obtain a {@code GatewayConfig} whose preferences have not been migrated.
     */
    public static synchronized void init(Context context) {
        if (instance == null) {
            GatewayConfig created = new GatewayConfig(context);
            created.migrate();
            instance = created;
        }
    }

    /**
     * Get the singleton instance.
     * @throws IllegalStateException if init() was not called
     */
    public static GatewayConfig getInstance() {
        // Snapshot: another thread's init() can publish between the null check and the
        // return, so the checked value and the returned value must be the same read.
        GatewayConfig current = instance;
        if (current == null) {
            throw new IllegalStateException("GatewayConfig not initialized. Call init(context) first.");
        }
        return current;
    }

    /**
     * The instance, initializing it from {@code context} first if nothing has yet.
     *
     * <p>For entry points that can be the first thing to touch config in a process:
     * broadcast receivers ({@code BootReceiver}, {@code GatewayControlReceiver}), a service
     * started directly by the framework, and JVM tests that have no {@code Application}. The
     * point is not convenience — it is that reaching config through this method cannot
     * bypass {@link #init(Context)}'s migration, which reading SharedPreferences by raw name
     * could (AUDIT H4).
     */
    public static GatewayConfig from(Context context) {
        GatewayConfig current = instance;
        if (current != null) {
            return current;
        }
        init(context);
        return getInstance();
    }

    // ========== Migration ==========

    /**
     * One-shot, idempotent repair of persisted state, run once per process before the
     * instance is published.
     */
    private void migrate() {
        try {
            migrateMicMuteControls();
        } catch (Exception e) {
            // A broken migration must never stop the gateway from starting: the worst case
            // without it is the pre-GW-24 behaviour, an ignored mute-control list.
            Log.e(TAG, "Preference migration failed: " + e.getMessage(), e);
        }
    }

    /**
     * Fold the web interface's legacy {@code mic_mute_controls} StringSet into the canonical
     * comma-separated {@code mic_mute_decs} String (AUDIT H4).
     *
     * <p>Values are read through {@link SharedPreferences#getAll()} and type-checked rather
     * than through {@code getString}, because a real device can hold <em>both</em> keys with
     * mismatched types — the web UI wrote a {@code StringSet}, the in-app UI a {@code String}
     * — and {@code getString} on a key holding a set throws {@link ClassCastException} at
     * config-load time, i.e. in {@code PjsipSipService.onCreate}.
     *
     * <p>Conflict rule: if both keys hold controls, the canonical one wins. It is the one
     * that has actually been in effect; the legacy one never reached the mixer. Adopting the
     * legacy list in that case would enable mute controls the operator has not seen take
     * effect on this device.
     *
     * <p>Non-destructive by construction: the new value is committed and read back before
     * the legacy key is removed, and every failure path leaves the legacy key in place for
     * the next launch to retry.
     */
    private void migrateMicMuteControls() {
        final Map<String, ?> all;
        try {
            all = audioPrefs.getAll();
        } catch (Exception e) {
            Log.e(TAG, "Cannot read " + PREFS_AUDIO + " for migration: " + e.getMessage());
            return;
        }

        Object legacyValue = all.get(KEY_MIC_MUTE_CONTROLS_LEGACY);
        Object canonicalValue = all.get(KEY_MIC_MUTE_CONTROLS);

        // The canonical key holding anything but a String is the case that would throw on
        // every read, so it has to be rewritten even with no legacy key present.
        boolean canonicalNeedsRewrite = canonicalValue != null && !(canonicalValue instanceof String);
        if (legacyValue == null && !canonicalNeedsRewrite) {
            return;   // already migrated - the path every launch after the first takes
        }

        Set<String> canonical = asControlSet(canonicalValue, KEY_MIC_MUTE_CONTROLS);
        Set<String> legacy = asControlSet(legacyValue, KEY_MIC_MUTE_CONTROLS_LEGACY);

        Set<String> winner = canonical.isEmpty() ? legacy : canonical;
        if (!canonical.isEmpty() && !legacy.isEmpty() && !canonical.equals(legacy)) {
            Log.w(TAG, "Both mute-control keys hold values; keeping the in-app list "
                    + canonical + " and discarding the web UI's " + legacy
                    + " (only the former has ever reached the mixer)");
        }

        String joined = joinControls(winner);

        // commit(), not apply(): the legacy key is removed only after a successful read
        // back, and reading back a write that has not happened yet proves nothing.
        if (!audioPrefs.edit().putString(KEY_MIC_MUTE_CONTROLS, joined).commit()) {
            Log.e(TAG, "Mute-control migration write failed; legacy key kept for next launch");
            return;
        }

        Object readBack;
        try {
            readBack = audioPrefs.getAll().get(KEY_MIC_MUTE_CONTROLS);
        } catch (Exception e) {
            Log.e(TAG, "Mute-control migration read-back failed: " + e.getMessage());
            return;
        }
        if (!joined.equals(readBack)) {
            Log.e(TAG, "Mute-control migration read-back mismatch (wrote '" + joined
                    + "', read '" + readBack + "'); legacy key kept for next launch");
            return;
        }

        if (legacyValue != null) {
            audioPrefs.edit().remove(KEY_MIC_MUTE_CONTROLS_LEGACY).apply();
        }
        Log.i(TAG, "Migrated mute controls to " + KEY_MIC_MUTE_CONTROLS + ": '" + joined + "'");
    }

    /**
     * Interpret a raw preference value as a set of control names, whatever type it was
     * stored as. Unknown types yield an empty set rather than an exception — the caller is
     * either the migration or a per-call read on the audio path, and neither may throw.
     */
    private static Set<String> asControlSet(Object raw, String key) {
        Set<String> controls = new LinkedHashSet<>();
        if (raw == null) {
            return controls;
        }
        if (raw instanceof String) {
            addSplit(controls, (String) raw);
        } else if (raw instanceof Collection) {
            for (Object o : (Collection<?>) raw) {
                if (o != null) {
                    addSplit(controls, o.toString());
                }
            }
        } else {
            Log.e(TAG, "Preference '" + key + "' holds a " + raw.getClass().getSimpleName()
                    + ", which is not a control list - ignoring it");
        }
        return controls;
    }

    private static void addSplit(Set<String> into, String csv) {
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                into.add(trimmed);
            }
        }
    }

    private static String joinControls(Collection<String> controls) {
        StringBuilder sb = new StringBuilder();
        for (String control : controls) {
            if (control == null) continue;
            String trimmed = control.trim();
            if (trimmed.isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(trimmed);
        }
        return sb.toString();
    }

    // ========== SIP Configuration ==========

    public String getSipServer() {
        return gatewayPrefs.getString(KEY_SIP_SERVER, DEFAULT_SIP_SERVER);
    }

    public void setSipServer(String server) {
        edit().setSipServer(server).apply();
    }

    public int getSipPort() {
        return gatewayPrefs.getInt(KEY_SIP_PORT, DEFAULT_SIP_PORT);
    }

    public void setSipPort(int port) {
        edit().setSipPort(port).apply();
    }

    public String getSipUser() {
        return gatewayPrefs.getString(KEY_SIP_USER, DEFAULT_SIP_USER);
    }

    public void setSipUser(String user) {
        edit().setSipUser(user).apply();
    }

    public String getSipPassword() {
        return gatewayPrefs.getString(KEY_SIP_PASSWORD, DEFAULT_SIP_PASSWORD);
    }

    public void setSipPassword(String password) {
        edit().setSipPassword(password).apply();
    }

    /** Whether a password has ever been stored - for logging it as set/not set. */
    public boolean hasSipPassword() {
        return gatewayPrefs.contains(KEY_SIP_PASSWORD);
    }

    public String getSipRealm() {
        return gatewayPrefs.getString(KEY_SIP_REALM, DEFAULT_SIP_REALM);
    }

    public void setSipRealm(String realm) {
        edit().setSipRealm(realm).apply();
    }

    public boolean isUseTls() {
        return gatewayPrefs.getBoolean(KEY_USE_TLS, DEFAULT_USE_TLS);
    }

    public void setUseTls(boolean useTls) {
        edit().setUseTls(useTls).apply();
    }

    /**
     * Get effective SIP port (considering TLS setting).
     */
    public int getEffectiveSipPort() {
        int configuredPort = getSipPort();
        if (isUseTls() && configuredPort == DEFAULT_SIP_PORT) {
            return DEFAULT_SIP_TLS_PORT;
        }
        return configuredPort;
    }

    // ========== SIM Destinations ==========

    public String getSim1Destination() {
        return gatewayPrefs.getString(KEY_SIM1_DESTINATION, DEFAULT_SIM1_DESTINATION);
    }

    public void setSim1Destination(String destination) {
        edit().setSim1Destination(destination).apply();
    }

    public String getSim2Destination() {
        return gatewayPrefs.getString(KEY_SIM2_DESTINATION, DEFAULT_SIM2_DESTINATION);
    }

    public void setSim2Destination(String destination) {
        edit().setSim2Destination(destination).apply();
    }

    /**
     * Get destination extension for a given SIM slot.
     * @param simSlot 1 or 2
     * @return destination extension or empty string if not configured
     */
    public String getDestinationForSim(int simSlot) {
        return simSlot == 2 ? getSim2Destination() : getSim1Destination();
    }

    /**
     * Get SIM slot for a given caller extension.
     * @param callerExt extension number (e.g., "101")
     * @return SIM slot (1 or 2), or 1 as default
     */
    public int getSimSlotForCaller(String callerExt) {
        if (callerExt == null || callerExt.isEmpty()) {
            return 1;
        }

        String sim1 = getSim1Destination();
        String sim2 = getSim2Destination();

        if (!sim2.isEmpty() && callerExt.equals(sim2)) {
            return 2;
        }
        if (!sim1.isEmpty() && callerExt.equals(sim1)) {
            return 1;
        }

        return 1; // Default to SIM1
    }

    // ========== Call Settings ==========

    public int getIncomingCallMode() {
        return gatewayPrefs.getInt(KEY_INCOMING_CALL_MODE, 0);
    }

    public void setIncomingCallMode(int mode) {
        edit().setIncomingCallMode(mode).apply();
    }

    // ========== Battery Settings ==========

    public int getBatteryLimit() {
        return gatewayPrefs.getInt(KEY_BATTERY_LIMIT, DEFAULT_BATTERY_LIMIT);
    }

    public void setBatteryLimit(int limit) {
        edit().setBatteryLimit(limit).apply();
    }

    // ========== Web Interface ==========

    public boolean isWebInterfaceEnabled() {
        return gatewayPrefs.getBoolean(KEY_WEB_INTERFACE_ENABLED, false);
    }

    public void setWebInterfaceEnabled(boolean enabled) {
        edit().setWebInterfaceEnabled(enabled).apply();
    }

    // ========== SIP Diagnostics (test call) ==========

    /** Destination dialled by the diagnostic test call. Defaults to the FreePBX echo test. */
    public String getTestDestination() {
        return gatewayPrefs.getString(KEY_TEST_DESTINATION, DEFAULT_TEST_DESTINATION);
    }

    public void setTestDestination(String destination) {
        edit().setTestDestination(destination).apply();
    }

    /** Audio source for the diagnostic test call: "tone", "loopback" or "bridge". */
    public String getTestMode() {
        return gatewayPrefs.getString(KEY_TEST_MODE, DEFAULT_TEST_MODE);
    }

    public void setTestMode(String mode) {
        edit().setTestMode(mode).apply();
    }

    /** Raise the PJSIP log level to 5 so full SIP messages (incl. SDP) are logged. */
    public boolean isVerboseSipLog() {
        return gatewayPrefs.getBoolean(KEY_VERBOSE_SIP_LOG, false);
    }

    public void setVerboseSipLog(boolean enabled) {
        edit().setVerboseSipLog(enabled).apply();
    }

    /** Replay DTMF digits pressed on the SIP leg onto the GSM leg (far-end voice menus). */
    public boolean isDtmfRelayEnabled() {
        return gatewayPrefs.getBoolean(KEY_DTMF_RELAY, true);
    }

    public void setDtmfRelayEnabled(boolean enabled) {
        edit().setDtmfRelayEnabled(enabled).apply();
    }

    // ========== Audio Configuration ==========

    public int getAudioCard() {
        return audioPrefs.getInt(KEY_AUDIO_CARD, DEFAULT_AUDIO_CARD);
    }

    public void setAudioCard(int card) {
        edit().setAudioCard(card).apply();
    }

    public String getMultimediaRoute() {
        return audioPrefs.getString(KEY_MULTIMEDIA_ROUTE, DEFAULT_MULTIMEDIA_ROUTE);
    }

    public void setMultimediaRoute(String route) {
        edit().setMultimediaRoute(route).apply();
    }

    public int getCaptureDevice() {
        return audioPrefs.getInt(KEY_CAPTURE_DEVICE, DEFAULT_CAPTURE_DEVICE);
    }

    public void setCaptureDevice(int device) {
        edit().setCaptureDevice(device).apply();
    }

    public int getPlaybackDevice() {
        return audioPrefs.getInt(KEY_PLAYBACK_DEVICE, DEFAULT_PLAYBACK_DEVICE);
    }

    public void setPlaybackDevice(int device) {
        edit().setPlaybackDevice(device).apply();
    }

    // ========== Audio Profile (SoC) ==========

    /**
     * SoC audio profile selector: "auto" (default), "qualcomm", or "mediatek".
     * Controls which mixer topology GsmAudioPort uses for the call-audio bridge.
     */
    public String getAudioProfile() {
        return audioPrefs.getString(KEY_AUDIO_PROFILE, DEFAULT_AUDIO_PROFILE);
    }

    public void setAudioProfile(String profile) {
        edit().setAudioProfile(profile).apply();
    }

    // ========== Mute Preset ==========

    public String getMutePreset() {
        return mutePrefs.getString(KEY_MUTE_PRESET, DEFAULT_MUTE_PRESET);
    }

    public void setMutePreset(String preset) {
        edit().setMutePreset(preset).apply();
    }

    // ========== Mic Mute Controls ==========

    /**
     * The checkbox-selected mic-mute controls, as a mutable set the caller may edit and hand
     * back to {@link #setMicMuteControls(Set)}.
     *
     * <p>Read through {@code getAll()} and type-checked rather than with {@code getString}:
     * this key held a {@code StringSet} on any device that used the web interface before
     * GW-24, and {@code getString} on it throws {@link ClassCastException}. The migration in
     * {@link #init(Context)} normally repairs that before anything gets here, but this
     * getter sits on the call-audio path ({@code QualcommAudioProfile.setupMixer} via
     * {@link #getAllMuteControls()}) and must not be able to throw even if the migration
     * could not write.
     */
    public Set<String> getMicMuteControls() {
        try {
            return asControlSet(audioPrefs.getAll().get(KEY_MIC_MUTE_CONTROLS),
                    KEY_MIC_MUTE_CONTROLS);
        } catch (Exception e) {
            Log.e(TAG, "Cannot read mute controls: " + e.getMessage());
            return new LinkedHashSet<>();
        }
    }

    public void setMicMuteControls(Set<String> controls) {
        edit().setMicMuteControls(controls).apply();
    }

    // ========== Manual Mute Controls ==========

    /**
     * Get manually entered mute control names (comma-separated string).
     */
    public String getManualMuteControls() {
        return audioPrefs.getString(KEY_MANUAL_MUTE_CONTROLS, "");
    }

    /**
     * Set manually entered mute control names.
     */
    public void setManualMuteControls(String controls) {
        edit().setManualMuteControls(controls).apply();
    }

    /**
     * Get all mute controls (checkbox-selected + manual).
     * Returns combined set for DeviceMuteManager and QualcommAudioProfile.
     */
    public Set<String> getAllMuteControls() {
        Set<String> all = getMicMuteControls();
        addSplit(all, getManualMuteControls());
        return all;
    }

    // ========== Audio Gain (dB) ==========

    /**
     * Get TX gain (GSM → SIP) in dB.
     * Negative values = quieter, 0 = unity, positive = louder.
     */
    public float getTxGain() {
        return audioPrefs.getFloat(KEY_TX_GAIN, DEFAULT_TX_GAIN);
    }

    public void setTxGain(float gainDb) {
        edit().setTxGain(gainDb).apply();
    }

    /**
     * Get RX gain (SIP → GSM) in dB.
     * Negative values = quieter, 0 = unity, positive = louder.
     */
    public float getRxGain() {
        return audioPrefs.getFloat(KEY_RX_GAIN, DEFAULT_RX_GAIN);
    }

    public void setRxGain(float gainDb) {
        edit().setRxGain(gainDb).apply();
    }

    /**
     * Convert dB to linear scale for PJSIP.
     * PJSIP uses linear scale: 1.0 = 0dB, 0.5 = -6dB, 2.0 = +6dB
     */
    public static float dbToLinear(float db) {
        return (float) Math.pow(10.0, db / 20.0);
    }

    // ========== SMS duplicate suppression (AUDIT H13 / GW-27) ==========

    private static final String KEY_PROCESSED_SMS = "processed_sms";

    /**
     * The persisted SMS duplicate-suppression record, or {@code ""} when nothing has been
     * forwarded yet.
     *
     * <p>Opaque to this class on purpose: {@code SmsHandler} owns the encoding, the pruning
     * and the age policy, and this is only the durable slot they live in. It exists because
     * the inbox {@code read} flag is provider state the app <b>cannot</b> guarantee it can
     * write (it is not the default SMS app, and the root fallback is device-dependent), so
     * the flag must not be the only defence against re-forwarding the whole inbox on every
     * process start — AUDIT H13.
     *
     * <p>Written with {@code commit()} rather than {@code apply()}: the failure this guards
     * against is precisely the process going away, and {@code apply()}'s disk write is not
     * ordered against that. The record is a few KB and the write happens once per forwarded
     * message, never on a hot path.
     */
    public String getProcessedSmsRecord() {
        return gatewayPrefs.getString(KEY_PROCESSED_SMS, "");
    }

    /** @see #getProcessedSmsRecord() */
    @SuppressLint("ApplySharedPref")
    public void setProcessedSmsRecord(String record) {
        gatewayPrefs.edit().putString(KEY_PROCESSED_SMS, record == null ? "" : record).commit();
    }

    // ========== Commissioning wizard (GW-42) ==========

    /**
     * Whether the first-run commissioning wizard has already been dismissed.
     *
     * <p>False means "this handset has never been through the wizard", which is the only
     * thing that makes {@code ui/setup/SetupLauncher} open it unasked. It says nothing about
     * whether the gateway is actually configured - {@link #isSipConfigured()} answers that,
     * and the two are deliberately independent: a phone can be fully provisioned over the
     * web interface and never see the wizard, and a phone can skip every step of the wizard
     * and be configured by nothing.
     */
    public boolean isSetupCompleted() {
        return gatewayPrefs.getBoolean(KEY_SETUP_COMPLETED, false);
    }

    /** Record that the wizard has been dismissed (or, with false, ask for it again). */
    public void setSetupCompleted(boolean completed) {
        edit().setSetupCompleted(completed).apply();
    }

    // ========== Bulk Operations ==========

    /**
     * Start a batched write. Every setter is typed, so no caller has to know a key or a
     * preference file name; {@link Editor#apply()} then does exactly one {@code apply()} per
     * preference file the batch actually touched (AUDIT H4).
     *
     * <pre>{@code
     * config.edit().setSipServer(server).setAudioCard(card).apply();
     * }</pre>
     */
    public Editor edit() {
        return new Editor();
    }

    /**
     * Update all SIP settings at once.
     */
    public void updateSipConfig(String server, int port, String user, String password,
                                String realm, boolean useTls) {
        edit().setSipServer(server)
              .setSipPort(port)
              .setSipUser(user)
              .setSipPassword(password)
              .setSipRealm(realm)
              .setUseTls(useTls)
              .apply();
    }

    /**
     * Update SIM destinations at once.
     */
    public void updateSimDestinations(String sim1, String sim2) {
        edit().setSim1Destination(sim1).setSim2Destination(sim2).apply();
    }

    /**
     * Update all audio settings at once.
     */
    public void updateAudioConfig(int card, int capture, int playback, String route) {
        edit().setAudioCard(card)
              .setCaptureDevice(capture)
              .setPlaybackDevice(playback)
              .setMultimediaRoute(route)
              .apply();
    }

    /**
     * Check if SIP is configured (server and user are set).
     */
    public boolean isSipConfigured() {
        String server = getSipServer();
        String user = getSipUser();
        return server != null && !server.isEmpty() && user != null && !user.isEmpty();
    }

    /**
     * Get a summary string for logging.
     */
    public String getConfigSummary() {
        return String.format("%s@%s:%d TLS=%b, realm=%s, SIM1→%s, SIM2→%s",
            getSipUser(),
            getSipServer(),
            getEffectiveSipPort(),
            isUseTls(),
            getSipRealm(),
            getSim1Destination(),
            getSim2Destination()
        );
    }

    /**
     * A typed batch write across the three preference files.
     *
     * <p>One {@link SharedPreferences.Editor} per file, created on first use, and exactly one
     * {@code apply()} per touched file in {@link #apply()}. Before GW-24 a single web-config
     * POST could call {@code apply()} five times across three editors, so a concurrent reader
     * — the SIP service reloading, the mute thread starting a call — could observe half of it
     * (AUDIT H4).
     *
     * <p>Not thread-safe, and not meant to be: build and apply one on the thread handling the
     * request.
     */
    public final class Editor {
        private SharedPreferences.Editor gateway;
        private SharedPreferences.Editor audio;
        private SharedPreferences.Editor mute;

        private Editor() {
        }

        private SharedPreferences.Editor gateway() {
            if (gateway == null) gateway = gatewayPrefs.edit();
            return gateway;
        }

        private SharedPreferences.Editor audio() {
            if (audio == null) audio = audioPrefs.edit();
            return audio;
        }

        private SharedPreferences.Editor mute() {
            if (mute == null) mute = mutePrefs.edit();
            return mute;
        }

        // --- gateway_prefs ---

        public Editor setSipServer(String server) {
            gateway().putString(KEY_SIP_SERVER, server);
            return this;
        }

        public Editor setSipPort(int port) {
            gateway().putInt(KEY_SIP_PORT, port);
            return this;
        }

        public Editor setSipUser(String user) {
            gateway().putString(KEY_SIP_USER, user);
            return this;
        }

        public Editor setSipPassword(String password) {
            gateway().putString(KEY_SIP_PASSWORD, password);
            return this;
        }

        public Editor setSipRealm(String realm) {
            gateway().putString(KEY_SIP_REALM, realm);
            return this;
        }

        public Editor setUseTls(boolean useTls) {
            gateway().putBoolean(KEY_USE_TLS, useTls);
            return this;
        }

        public Editor setSim1Destination(String destination) {
            gateway().putString(KEY_SIM1_DESTINATION, destination);
            return this;
        }

        public Editor setSim2Destination(String destination) {
            gateway().putString(KEY_SIM2_DESTINATION, destination);
            return this;
        }

        public Editor setIncomingCallMode(int mode) {
            gateway().putInt(KEY_INCOMING_CALL_MODE, mode);
            return this;
        }

        public Editor setBatteryLimit(int limit) {
            gateway().putInt(KEY_BATTERY_LIMIT, limit);
            return this;
        }

        public Editor setWebInterfaceEnabled(boolean enabled) {
            gateway().putBoolean(KEY_WEB_INTERFACE_ENABLED, enabled);
            return this;
        }

        public Editor setTestDestination(String destination) {
            gateway().putString(KEY_TEST_DESTINATION, destination);
            return this;
        }

        public Editor setTestMode(String mode) {
            gateway().putString(KEY_TEST_MODE, mode);
            return this;
        }

        public Editor setVerboseSipLog(boolean enabled) {
            gateway().putBoolean(KEY_VERBOSE_SIP_LOG, enabled);
            return this;
        }

        public Editor setDtmfRelayEnabled(boolean enabled) {
            gateway().putBoolean(KEY_DTMF_RELAY, enabled);
            return this;
        }

        /** GW-42: the wizard has been dismissed. See {@link GatewayConfig#isSetupCompleted()}. */
        public Editor setSetupCompleted(boolean completed) {
            gateway().putBoolean(KEY_SETUP_COMPLETED, completed);
            return this;
        }

        // --- gsm_audio_config ---

        public Editor setAudioProfile(String profile) {
            audio().putString(KEY_AUDIO_PROFILE, profile);
            return this;
        }

        public Editor setAudioCard(int card) {
            audio().putInt(KEY_AUDIO_CARD, card);
            return this;
        }

        public Editor setCaptureDevice(int device) {
            audio().putInt(KEY_CAPTURE_DEVICE, device);
            return this;
        }

        public Editor setPlaybackDevice(int device) {
            audio().putInt(KEY_PLAYBACK_DEVICE, device);
            return this;
        }

        public Editor setMultimediaRoute(String route) {
            audio().putString(KEY_MULTIMEDIA_ROUTE, route);
            return this;
        }

        public Editor setTxGain(float gainDb) {
            audio().putFloat(KEY_TX_GAIN, gainDb);
            return this;
        }

        public Editor setRxGain(float gainDb) {
            audio().putFloat(KEY_RX_GAIN, gainDb);
            return this;
        }

        /**
         * The checkbox-selected mic-mute controls, stored as one comma-separated String.
         * This is the write that used to go to {@code mic_mute_controls} as a StringSet from
         * the web interface and be read by nothing (AUDIT H4).
         */
        public Editor setMicMuteControls(Set<String> controls) {
            audio().putString(KEY_MIC_MUTE_CONTROLS, joinControls(controls));
            return this;
        }

        public Editor setManualMuteControls(String controls) {
            audio().putString(KEY_MANUAL_MUTE_CONTROLS, controls);
            return this;
        }

        // --- device_mute_prefs ---

        public Editor setMutePreset(String preset) {
            mute().putString(KEY_MUTE_PRESET, preset);
            return this;
        }

        /** One {@code apply()} per preference file this batch actually wrote to. */
        public void apply() {
            if (gateway != null) gateway.apply();
            if (audio != null) audio.apply();
            if (mute != null) mute.apply();
        }
    }
}
