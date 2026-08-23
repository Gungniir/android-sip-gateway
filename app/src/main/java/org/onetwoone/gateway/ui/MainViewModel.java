package org.onetwoone.gateway.ui;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.onetwoone.gateway.BatteryLimitService;
import org.onetwoone.gateway.BatteryWatchdog;
import org.onetwoone.gateway.DeviceMuteManager;
import org.onetwoone.gateway.PjsipSipService;
import org.onetwoone.gateway.config.GatewayConfig;
import org.onetwoone.gateway.core.GatewayStatus;

import java.util.List;
import java.util.Set;

/**
 * ViewModel for MainActivity.
 * Manages service connection, state, and configuration.
 *
 * Responsibilities:
 * - Service lifecycle (bind/unbind, start/stop)
 * - Status monitoring
 * - Configuration via GatewayConfig
 */
public class MainViewModel extends AndroidViewModel {
    private static final String TAG = "MainVM";

    // Service state
    private final MutableLiveData<ServiceState> serviceState = new MutableLiveData<>(new ServiceState());
    private final MutableLiveData<String> statusText = new MutableLiveData<>("Not connected");
    private final MutableLiveData<Boolean> isRegistered = new MutableLiveData<>(false);

    // Configuration (observed from GatewayConfig)
    private final MutableLiveData<SipConfig> sipConfig = new MutableLiveData<>();
    private final MutableLiveData<AudioConfig> audioConfig = new MutableLiveData<>();

    // Toast messages (one-shot events)
    private final MutableLiveData<String> toastMessage = new MutableLiveData<>();

    // Battery and mute state
    private final MutableLiveData<Integer> batteryLimit = new MutableLiveData<>();
    private final MutableLiveData<String> currentMutePreset = new MutableLiveData<>();
    private final MutableLiveData<Boolean> showCustomControls = new MutableLiveData<>(false);
    private final MutableLiveData<String> manualMuteControls = new MutableLiveData<>("");
    private final MutableLiveData<List<TinymixManager.MixerControl>> availableControls = new MutableLiveData<>();

    // SIP diagnostics (test call)
    private final MutableLiveData<String> testReport = new MutableLiveData<>("No test call yet");

    // Managers
    private final TinymixManager tinymixManager;
    private final PermissionManager permissionManager;
    private final AudioDeviceManager audioDeviceManager;

    // Service connection
    private PjsipSipService pjsipService;
    private boolean serviceBound = false;

    // Status polling
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusPoller;
    private boolean polling = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            PjsipSipService.LocalBinder localBinder = (PjsipSipService.LocalBinder) binder;
            pjsipService = localBinder.getService();
            serviceBound = true;
            Log.d(TAG, "Service connected");

            // Apply saved config
            applySavedConfig();

            // Update state
            updateServiceState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            pjsipService = null;
            serviceBound = false;
            Log.d(TAG, "Service disconnected");
            updateServiceState();
        }
    };

    public MainViewModel(Application application) {
        super(application);

        // Initialize GatewayConfig
        GatewayConfig.init(application);

        // Initialize managers
        tinymixManager = new TinymixManager(application);
        permissionManager = new PermissionManager(application);
        audioDeviceManager = new AudioDeviceManager();

        // Load initial config
        loadConfig();

        // Status polling runnable
        statusPoller = new Runnable() {
            @Override
            public void run() {
                updateServiceState();
                if (polling) {
                    statusHandler.postDelayed(this, 1000);
                }
            }
        };
    }

    // ========== LiveData Getters ==========

    public LiveData<ServiceState> getServiceState() {
        return serviceState;
    }

    public LiveData<String> getStatusText() {
        return statusText;
    }

    public LiveData<Boolean> getIsRegistered() {
        return isRegistered;
    }

    public LiveData<SipConfig> getSipConfig() {
        return sipConfig;
    }

    public LiveData<AudioConfig> getAudioConfig() {
        return audioConfig;
    }

    public LiveData<String> getToastMessage() {
        return toastMessage;
    }

    public LiveData<Integer> getBatteryLimit() {
        return batteryLimit;
    }

    public LiveData<String> getCurrentMutePreset() {
        return currentMutePreset;
    }

    public LiveData<Boolean> getShowCustomControls() {
        return showCustomControls;
    }

    public LiveData<String> getManualMuteControls() {
        return manualMuteControls;
    }

    public LiveData<List<TinymixManager.MixerControl>> getAvailableControls() {
        return availableControls;
    }

    public LiveData<String> getTestReport() {
        return testReport;
    }

    public LiveData<PermissionManager.PermissionState> getPermissionState() {
        return permissionManager.getPermissionState();
    }

    public LiveData<AudioDeviceManager.AudioDevices> getAudioDevices() {
        return audioDeviceManager.getDevices();
    }

    // ========== Manager Accessors ==========

    public TinymixManager getTinymixManager() {
        return tinymixManager;
    }

    public PermissionManager getPermissionManager() {
        return permissionManager;
    }

    public AudioDeviceManager getAudioDeviceManager() {
        return audioDeviceManager;
    }

    // ========== Service Control ==========

    public void startService() {
        if (pjsipService != null && pjsipService.isRunning()) {
            Log.d(TAG, "Service already running");
            return;
        }

        Log.d(TAG, "Starting service");

        Context context = getApplication();
        Intent intent = new Intent(context, PjsipSipService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }

        bindToService();
        toastMessage.setValue("Connecting to SIP server...");
    }

    public void stopService() {
        Log.d(TAG, "Stopping service");

        if (pjsipService != null) {
            pjsipService.stop();
            pjsipService = null;
        }

        unbindFromService();
        toastMessage.setValue("Disconnected");
        statusText.setValue("Service: Stopped");
    }

    public void restartService() {
        Log.d(TAG, "Restarting service");
        toastMessage.setValue("Restarting...");

        stopService();

        // Wait for PJSIP cleanup
        statusHandler.postDelayed(() -> {
            startService();
            toastMessage.setValue("Restarted");
        }, 2000);
    }

    public void bindToService() {
        if (serviceBound) return;

        Context context = getApplication();
        Intent intent = new Intent(context, PjsipSipService.class);
        context.bindService(intent, serviceConnection, 0);
    }

    public void unbindFromService() {
        if (!serviceBound) return;

        try {
            getApplication().unbindService(serviceConnection);
        } catch (Exception e) {
            Log.w(TAG, "Error unbinding: " + e.getMessage());
        }
        serviceBound = false;
    }

    // ========== Status Polling ==========

    public void startPolling() {
        if (!polling) {
            polling = true;
            statusHandler.post(statusPoller);
        }
    }

    public void stopPolling() {
        polling = false;
        statusHandler.removeCallbacks(statusPoller);
    }

    /**
     * The 1 Hz poll. Reads the service's immutable {@link GatewayStatus} snapshot, never the
     * live managers - those are owned by the control thread now (GW-10).
     *
     * <p>The test-call report is deliberately fetched separately and is not part of the
     * snapshot: it is a {@code StringBuilder} capped at 20 000 chars, and copying it into
     * every publish would make publishing cost proportional to report length (plan §2.7).
     */
    private void updateServiceState() {
        ServiceState state = new ServiceState();

        if (pjsipService != null) {
            GatewayStatus snapshot = pjsipService.getStatusSnapshot();
            state.isRunning = snapshot.isRunning();
            state.isRegistered = snapshot.isSipRegistered();
            state.statusMessage = snapshot.getStatusText();
        } else {
            state.isRunning = false;
            state.isRegistered = false;
            state.statusMessage = "Service not connected";
        }

        serviceState.setValue(state);
        statusText.setValue(state.statusMessage);
        isRegistered.setValue(state.isRegistered);

        if (pjsipService != null) {
            String report = pjsipService.getTestCallReport();
            if (report != null && !report.isEmpty()) {
                testReport.setValue(report);
            }
        }
    }

    // ========== SIP Diagnostics ==========

    /**
     * Place a diagnostic SIP call (no GSM leg). Destination and mode are persisted so
     * the same settings come back on the next launch.
     */
    public void startTestCall(String destination, String mode) {
        if (pjsipService == null) {
            toastMessage.setValue("Service not connected");
            return;
        }

        GatewayConfig config = GatewayConfig.getInstance();
        config.setTestDestination(destination);
        config.setTestMode(mode);

        pjsipService.startTestCall(destination, mode, 0);
        toastMessage.setValue("Test call to " + destination + " (" + mode + ")");
    }

    public void stopTestCall() {
        if (pjsipService != null) {
            pjsipService.stopTestCall();
        }
    }

    public void setVerboseSipLog(boolean enabled) {
        GatewayConfig.getInstance().setVerboseSipLog(enabled);
        toastMessage.setValue(enabled
                ? "Verbose PJSIP logging on (restart service to apply)"
                : "Verbose PJSIP logging off (restart service to apply)");
    }

    public void setDtmfRelay(boolean enabled) {
        GatewayConfig.getInstance().setDtmfRelayEnabled(enabled);
        toastMessage.setValue(enabled ? "DTMF relay on" : "DTMF relay off");
    }

    // ========== Configuration ==========

    private void loadConfig() {
        GatewayConfig config = GatewayConfig.getInstance();

        // SIP config
        SipConfig sip = new SipConfig();
        sip.server = config.getSipServer();
        sip.port = config.getSipPort();
        sip.user = config.getSipUser();
        sip.password = config.getSipPassword();
        sip.realm = config.getSipRealm();
        sip.useTls = config.isUseTls();
        sip.sim1Destination = config.getSim1Destination();
        sip.sim2Destination = config.getSim2Destination();
        sip.incomingCallMode = config.getIncomingCallMode();
        sipConfig.setValue(sip);

        // Audio config
        AudioConfig audio = new AudioConfig();
        audio.card = config.getAudioCard();
        audio.captureDevice = config.getCaptureDevice();
        audio.playbackDevice = config.getPlaybackDevice();
        audio.multimediaRoute = config.getMultimediaRoute();
        audio.txGain = config.getTxGain();
        audio.rxGain = config.getRxGain();
        audio.micMuteControls = config.getMicMuteControls();
        audioConfig.setValue(audio);

        // Battery limit
        batteryLimit.setValue(config.getBatteryLimit());

        // Mute preset
        String preset = config.getMutePreset();
        currentMutePreset.setValue(preset);
        boolean isCustom = DeviceMuteManager.PRESET_CUSTOM.equals(preset);
        showCustomControls.setValue(isCustom);

        // Manual mute controls (for custom preset)
        manualMuteControls.setValue(config.getManualMuteControls());
    }

    public void saveSipConfig(String server, int port, String user, String password,
                              String realm, boolean useTls, String sim1, String sim2) {
        GatewayConfig config = GatewayConfig.getInstance();
        config.updateSipConfig(server, port, user, password, realm, useTls);
        config.updateSimDestinations(sim1, sim2);

        // Refresh LiveData
        loadConfig();

        toastMessage.setValue("SIP settings saved");
        Log.d(TAG, "SIP config saved: " + user + "@" + server);
    }

    public void saveAudioConfig(int card, int capture, int playback, String route,
                                float txGain, float rxGain, Set<String> muteControls,
                                String manualControls) {
        GatewayConfig config = GatewayConfig.getInstance();
        config.updateAudioConfig(card, capture, playback, route);
        config.setTxGain(txGain);
        config.setRxGain(rxGain);
        config.setMicMuteControls(muteControls);
        config.setManualMuteControls(manualControls);

        loadConfig();
        toastMessage.setValue("Audio settings saved. Restart to apply.");
        Log.d(TAG, "Audio config saved: card=" + card + ", capture=" + capture +
              ", playback=" + playback + ", route=" + route +
              ", txGain=" + txGain + ", rxGain=" + rxGain +
              ", manualControls=" + manualControls);
    }

    /**
     * Save full audio configuration (convenience method).
     */
    public void saveFullAudioConfig(int card, int capture, int playback, String route) {
        GatewayConfig config = GatewayConfig.getInstance();
        config.updateAudioConfig(card, capture, playback, route);
        loadConfig();
        toastMessage.setValue("Audio settings saved. Restart to apply.");
    }

    private void applySavedConfig() {
        if (pjsipService == null) return;

        GatewayConfig config = GatewayConfig.getInstance();
        pjsipService.setSipConfig(
            config.getSipServer(),
            config.getSipPort(),
            config.getSipUser(),
            config.getSipPassword()
        );
        pjsipService.setSimDestinations(
            config.getSim1Destination(),
            config.getSim2Destination()
        );

        Log.d(TAG, "Applied saved config to service");
    }

    // ========== Battery Service ==========

    public void startBatteryService(int limit) {
        Context context = getApplication();
        Intent intent = new Intent(context, BatteryLimitService.class);
        intent.putExtra("limit", limit);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }

        BatteryWatchdog.schedule(context);
    }

    public void setBatteryLimit(int limit) {
        GatewayConfig.getInstance().setBatteryLimit(limit);
        startBatteryService(limit);
        Log.d(TAG, "Battery limit set to " + limit + "%");
    }

    // ========== Web Interface ==========

    public void setWebInterfaceEnabled(boolean enabled) {
        GatewayConfig.getInstance().setWebInterfaceEnabled(enabled);

        if (pjsipService != null) {
            if (enabled) {
                pjsipService.startWebServer();
                toastMessage.setValue("Web interface enabled on port 8080");
            } else {
                pjsipService.stopWebServer();
                toastMessage.setValue("Web interface disabled");
            }
        }

        Log.d(TAG, "Web interface " + (enabled ? "enabled" : "disabled"));
    }

    // ========== Mute Preset Management ==========

    /**
     * Select a mute preset and save it.
     *
     * @param preset The preset name (e.g., "redmi_note_7", "custom")
     */
    public void selectMutePreset(String preset) {
        GatewayConfig.getInstance().setMutePreset(preset);
        currentMutePreset.setValue(preset);

        boolean isCustom = DeviceMuteManager.PRESET_CUSTOM.equals(preset);
        showCustomControls.setValue(isCustom);

        if (isCustom) {
            detectMixerControls();
        }

        Log.d(TAG, "Mute preset changed to: " + preset);
    }

    /**
     * Toggle a specific mute control on/off.
     *
     * @param controlName The control name (e.g., "DEC1 Volume")
     * @param enabled     Whether the control should be enabled for muting
     */
    public void toggleMuteControl(String controlName, boolean enabled) {
        Set<String> controls = GatewayConfig.getInstance().getMicMuteControls();
        if (enabled) {
            controls.add(controlName);
        } else {
            controls.remove(controlName);
        }
        GatewayConfig.getInstance().setMicMuteControls(controls);

        // Update audio config LiveData
        AudioConfig audio = audioConfig.getValue();
        if (audio != null) {
            audio.micMuteControls = controls;
            audioConfig.setValue(audio);
        }

        Log.d(TAG, "Mute control " + controlName + " " + (enabled ? "enabled" : "disabled"));
    }

    /**
     * Detect available mixer controls for the current sound card.
     * Runs asynchronously and updates availableControls LiveData.
     */
    public void detectMixerControls() {
        AudioConfig audio = audioConfig.getValue();
        int card = audio != null ? audio.card : 0;

        new Thread(() -> {
            List<TinymixManager.MixerControl> controls = tinymixManager.detectControls(card);
            statusHandler.post(() -> availableControls.setValue(controls));
        }).start();
    }

    /**
     * Refresh audio device lists for the current card.
     */
    public void refreshAudioDevices() {
        AudioConfig audio = audioConfig.getValue();
        int card = audio != null ? audio.card : 0;
        audioDeviceManager.refreshDevices(card);
    }

    /**
     * Initialize permissions via root.
     */
    public void initPermissions() {
        permissionManager.grantAllPermissionsAsync();
    }

    /**
     * Refresh permission status.
     */
    public void refreshPermissions() {
        permissionManager.refreshPermissionStatus();
    }

    // ========== Cleanup ==========

    @Override
    protected void onCleared() {
        super.onCleared();
        stopPolling();
        unbindFromService();
        permissionManager.shutdown();
        audioDeviceManager.shutdown();
    }

    // ========== Data Classes ==========

    public static class ServiceState {
        public boolean isRunning = false;
        public boolean isRegistered = false;
        public String statusMessage = "";
    }

    public static class SipConfig {
        public String server = "";
        public int port = 5060;
        public String user = "";
        public String password = "";
        public String realm = "*";
        public boolean useTls = false;
        public String sim1Destination = "";
        public String sim2Destination = "";
        public int incomingCallMode = 0;
    }

    public static class AudioConfig {
        public int card = 0;
        public int captureDevice = 0;
        public int playbackDevice = 0;
        public String multimediaRoute = "MultiMedia1";
        public float txGain = 0.0f;  // GSM→SIP
        public float rxGain = 0.0f;  // SIP→GSM
        public Set<String> micMuteControls = new java.util.HashSet<>();
    }
}
