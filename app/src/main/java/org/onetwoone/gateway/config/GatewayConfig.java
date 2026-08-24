package org.onetwoone.gateway.config;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * Centralized configuration management for the GSM-SIP Gateway.
 *
 * Handles all SharedPreferences access and provides type-safe getters/setters.
 * All magic numbers and default values are defined here.
 */
public class GatewayConfig {

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
    private static final String DEFAULT_MUTE_PRESET = "redmi_note_7";

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
    private static final String KEY_MIC_MUTE_CONTROLS = "mic_mute_decs";
    private static final String KEY_AUDIO_PROFILE = "audio_profile";  // auto | qualcomm | mediatek
    private static final String KEY_TX_GAIN = "tx_gain";  // GSM → SIP
    private static final String KEY_RX_GAIN = "rx_gain";  // SIP → GSM
    private static final String KEY_TEST_DESTINATION = "test_destination";
    private static final String KEY_TEST_MODE = "test_mode";  // tone | loopback | bridge
    private static final String KEY_VERBOSE_SIP_LOG = "verbose_sip_log";
    private static final String KEY_DTMF_RELAY = "dtmf_relay";

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
     * Initialize the singleton instance. Must be called once at app startup.
     */
    public static synchronized void init(Context context) {
        if (instance == null) {
            instance = new GatewayConfig(context);
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

    // ========== SIP Configuration ==========

    public String getSipServer() {
        return gatewayPrefs.getString(KEY_SIP_SERVER, DEFAULT_SIP_SERVER);
    }

    public void setSipServer(String server) {
        gatewayPrefs.edit().putString(KEY_SIP_SERVER, server).apply();
    }

    public int getSipPort() {
        return gatewayPrefs.getInt(KEY_SIP_PORT, DEFAULT_SIP_PORT);
    }

    public void setSipPort(int port) {
        gatewayPrefs.edit().putInt(KEY_SIP_PORT, port).apply();
    }

    public String getSipUser() {
        return gatewayPrefs.getString(KEY_SIP_USER, DEFAULT_SIP_USER);
    }

    public void setSipUser(String user) {
        gatewayPrefs.edit().putString(KEY_SIP_USER, user).apply();
    }

    public String getSipPassword() {
        return gatewayPrefs.getString(KEY_SIP_PASSWORD, DEFAULT_SIP_PASSWORD);
    }

    public void setSipPassword(String password) {
        gatewayPrefs.edit().putString(KEY_SIP_PASSWORD, password).apply();
    }

    public String getSipRealm() {
        return gatewayPrefs.getString(KEY_SIP_REALM, DEFAULT_SIP_REALM);
    }

    public void setSipRealm(String realm) {
        gatewayPrefs.edit().putString(KEY_SIP_REALM, realm).apply();
    }

    public boolean isUseTls() {
        return gatewayPrefs.getBoolean(KEY_USE_TLS, DEFAULT_USE_TLS);
    }

    public void setUseTls(boolean useTls) {
        gatewayPrefs.edit().putBoolean(KEY_USE_TLS, useTls).apply();
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
        gatewayPrefs.edit().putString(KEY_SIM1_DESTINATION, destination).apply();
    }

    public String getSim2Destination() {
        return gatewayPrefs.getString(KEY_SIM2_DESTINATION, DEFAULT_SIM2_DESTINATION);
    }

    public void setSim2Destination(String destination) {
        gatewayPrefs.edit().putString(KEY_SIM2_DESTINATION, destination).apply();
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
        gatewayPrefs.edit().putInt(KEY_INCOMING_CALL_MODE, mode).apply();
    }

    // ========== Battery Settings ==========

    public int getBatteryLimit() {
        return gatewayPrefs.getInt(KEY_BATTERY_LIMIT, DEFAULT_BATTERY_LIMIT);
    }

    public void setBatteryLimit(int limit) {
        gatewayPrefs.edit().putInt(KEY_BATTERY_LIMIT, limit).apply();
    }

    // ========== Web Interface ==========

    public boolean isWebInterfaceEnabled() {
        return gatewayPrefs.getBoolean(KEY_WEB_INTERFACE_ENABLED, false);
    }

    public void setWebInterfaceEnabled(boolean enabled) {
        gatewayPrefs.edit().putBoolean(KEY_WEB_INTERFACE_ENABLED, enabled).apply();
    }

    // ========== SIP Diagnostics (test call) ==========

    /** Destination dialled by the diagnostic test call. Defaults to the FreePBX echo test. */
    public String getTestDestination() {
        return gatewayPrefs.getString(KEY_TEST_DESTINATION, DEFAULT_TEST_DESTINATION);
    }

    public void setTestDestination(String destination) {
        gatewayPrefs.edit().putString(KEY_TEST_DESTINATION, destination).apply();
    }

    /** Audio source for the diagnostic test call: "tone", "loopback" or "bridge". */
    public String getTestMode() {
        return gatewayPrefs.getString(KEY_TEST_MODE, DEFAULT_TEST_MODE);
    }

    public void setTestMode(String mode) {
        gatewayPrefs.edit().putString(KEY_TEST_MODE, mode).apply();
    }

    /** Raise the PJSIP log level to 5 so full SIP messages (incl. SDP) are logged. */
    public boolean isVerboseSipLog() {
        return gatewayPrefs.getBoolean(KEY_VERBOSE_SIP_LOG, false);
    }

    public void setVerboseSipLog(boolean enabled) {
        gatewayPrefs.edit().putBoolean(KEY_VERBOSE_SIP_LOG, enabled).apply();
    }

    /** Replay DTMF digits pressed on the SIP leg onto the GSM leg (far-end voice menus). */
    public boolean isDtmfRelayEnabled() {
        return gatewayPrefs.getBoolean(KEY_DTMF_RELAY, true);
    }

    public void setDtmfRelayEnabled(boolean enabled) {
        gatewayPrefs.edit().putBoolean(KEY_DTMF_RELAY, enabled).apply();
    }

    // ========== Audio Configuration ==========

    public int getAudioCard() {
        return audioPrefs.getInt(KEY_AUDIO_CARD, DEFAULT_AUDIO_CARD);
    }

    public void setAudioCard(int card) {
        audioPrefs.edit().putInt(KEY_AUDIO_CARD, card).apply();
    }

    public String getMultimediaRoute() {
        return audioPrefs.getString(KEY_MULTIMEDIA_ROUTE, DEFAULT_MULTIMEDIA_ROUTE);
    }

    public void setMultimediaRoute(String route) {
        audioPrefs.edit().putString(KEY_MULTIMEDIA_ROUTE, route).apply();
    }

    public int getCaptureDevice() {
        return audioPrefs.getInt(KEY_CAPTURE_DEVICE, DEFAULT_CAPTURE_DEVICE);
    }

    public void setCaptureDevice(int device) {
        audioPrefs.edit().putInt(KEY_CAPTURE_DEVICE, device).apply();
    }

    public int getPlaybackDevice() {
        return audioPrefs.getInt(KEY_PLAYBACK_DEVICE, DEFAULT_PLAYBACK_DEVICE);
    }

    public void setPlaybackDevice(int device) {
        audioPrefs.edit().putInt(KEY_PLAYBACK_DEVICE, device).apply();
    }

    // ========== Audio Profile (SoC) ==========

    /**
     * SoC audio profile selector: "auto" (default), "qualcomm", or "mediatek".
     * Controls which mixer topology GsmAudioPort uses for the call-audio bridge.
     */
    public String getAudioProfile() {
        return audioPrefs.getString(KEY_AUDIO_PROFILE, "auto");
    }

    public void setAudioProfile(String profile) {
        audioPrefs.edit().putString(KEY_AUDIO_PROFILE, profile).apply();
    }

    // ========== Mute Preset ==========

    public String getMutePreset() {
        return mutePrefs.getString(KEY_MUTE_PRESET, DEFAULT_MUTE_PRESET);
    }

    public void setMutePreset(String preset) {
        mutePrefs.edit().putString(KEY_MUTE_PRESET, preset).apply();
    }

    // ========== Mic Mute Controls ==========

    public java.util.Set<String> getMicMuteControls() {
        String decList = audioPrefs.getString(KEY_MIC_MUTE_CONTROLS, "");
        java.util.Set<String> controls = new java.util.HashSet<>();
        if (!decList.isEmpty()) {
            for (String dec : decList.split(",")) {
                controls.add(dec.trim());
            }
        }
        return controls;
    }

    public void setMicMuteControls(java.util.Set<String> controls) {
        String decList = String.join(",", controls);
        audioPrefs.edit().putString(KEY_MIC_MUTE_CONTROLS, decList).apply();
    }

    // ========== Manual Mute Controls ==========

    private static final String KEY_MANUAL_MUTE_CONTROLS = "manual_mute_controls";

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
        audioPrefs.edit().putString(KEY_MANUAL_MUTE_CONTROLS, controls).apply();
    }

    /**
     * Get all mute controls (checkbox-selected + manual).
     * Returns combined set for DeviceMuteManager.
     */
    public java.util.Set<String> getAllMuteControls() {
        java.util.Set<String> all = getMicMuteControls();
        String manual = getManualMuteControls();
        if (!manual.isEmpty()) {
            for (String control : manual.split(",")) {
                String trimmed = control.trim();
                if (!trimmed.isEmpty()) {
                    all.add(trimmed);
                }
            }
        }
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
        audioPrefs.edit().putFloat(KEY_TX_GAIN, gainDb).apply();
    }

    /**
     * Get RX gain (SIP → GSM) in dB.
     * Negative values = quieter, 0 = unity, positive = louder.
     */
    public float getRxGain() {
        return audioPrefs.getFloat(KEY_RX_GAIN, DEFAULT_RX_GAIN);
    }

    public void setRxGain(float gainDb) {
        audioPrefs.edit().putFloat(KEY_RX_GAIN, gainDb).apply();
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

    // ========== Bulk Operations ==========

    /**
     * Update all SIP settings at once.
     */
    public void updateSipConfig(String server, int port, String user, String password,
                                String realm, boolean useTls) {
        gatewayPrefs.edit()
            .putString(KEY_SIP_SERVER, server)
            .putInt(KEY_SIP_PORT, port)
            .putString(KEY_SIP_USER, user)
            .putString(KEY_SIP_PASSWORD, password)
            .putString(KEY_SIP_REALM, realm)
            .putBoolean(KEY_USE_TLS, useTls)
            .apply();
    }

    /**
     * Update SIM destinations at once.
     */
    public void updateSimDestinations(String sim1, String sim2) {
        gatewayPrefs.edit()
            .putString(KEY_SIM1_DESTINATION, sim1)
            .putString(KEY_SIM2_DESTINATION, sim2)
            .apply();
    }

    /**
     * Update all audio settings at once.
     */
    public void updateAudioConfig(int card, int capture, int playback, String route) {
        audioPrefs.edit()
            .putInt(KEY_AUDIO_CARD, card)
            .putInt(KEY_CAPTURE_DEVICE, capture)
            .putInt(KEY_PLAYBACK_DEVICE, playback)
            .putString(KEY_MULTIMEDIA_ROUTE, route)
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
}
