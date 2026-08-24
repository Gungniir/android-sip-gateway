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

    /**
     * The gateway's whole status, as the control thread published it. GW-45.
     *
     * <p>Never null: {@link GatewayStatus#UNAVAILABLE} stands in whenever there is no service
     * binding, which is what plan §4 GW-45 constraint 4 asks for instead of a null-state or
     * a locally invented sentinel.
     */
    private final MutableLiveData<GatewayStatus> gatewayStatus =
            new MutableLiveData<>(GatewayStatus.UNAVAILABLE);

    /**
     * Whether this ViewModel currently holds a live {@code PjsipSipService} binding. GW-45.
     *
     * <p>Separate from the snapshot on purpose. {@link GatewayStatus} describes the
     * <em>gateway</em>; whether the UI is bound to the process hosting it is a fact about this
     * ViewModel, and folding it into the snapshot would put a UI concern inside the
     * publication boundary Phases 1 and 2 exist to keep clean. It is also the one thing
     * {@link GatewayStatus#UNAVAILABLE} cannot tell you apart from: a freshly created service
     * publishes {@code UNAVAILABLE} too.
     */
    private final MutableLiveData<Boolean> serviceConnected = new MutableLiveData<>(false);

    // Service state - the pre-GW-45 surface. Deprecated, still fed on every tick.
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

    /**
     * Last {@link GatewayStatus#getConfigGeneration()} this ViewModel has re-read config for.
     * {@code -1} so the first poll after binding never counts as a change.
     */
    private long seenConfigGeneration = -1L;

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

    /**
     * <b>The status surface (GW-45).</b> The whole immutable {@link GatewayStatus} the control
     * thread published, handed over as it is rather than flattened into a String on the way
     * through. This is what a status-first screen renders from.
     *
     * <p><b>Contract</b>
     * <ul>
     *   <li><b>Never null.</b> Before the first poll, and whenever there is no service
     *       binding, the value is {@link GatewayStatus#UNAVAILABLE}.
     *   <li><b>Verbatim.</b> The object is the one {@code PjsipSipService.getStatusSnapshot()}
     *       returned - not copied, not re-wrapped, nothing dropped. Everything the control
     *       thread published is reachable: {@link GatewayStatus#getSipStatus()},
     *       {@link GatewayStatus#getCallStatus()}, {@link GatewayStatus#getAudioStatus()} as
     *       three separate values, {@link GatewayStatus#getCallState()},
     *       {@link GatewayStatus#getCallsAlive()}, {@link GatewayStatus#getConfigGeneration()}
     *       and the whole of {@link GatewayStatus.WatchdogFindings}.
     *   <li><b>Republished every tick.</b> {@code setValue} dispatches unconditionally, so
     *       observers fire once a second even when the control thread has published nothing
     *       new and the value is the same instance as last tick.
     * </ul>
     *
     * <p>That last point is load-bearing, not waste. {@link GatewayStatus#getCallDurationMs()}
     * and {@link GatewayStatus#isInGracePeriod()} re-read the clock on <em>every</em> call by
     * design, and the service publishes a new snapshot only on events - so during a call that
     * is generating none, the same object has to be asked again each second.
     * <b>Read those two inside the observer and never cache what they return</b>; a field
     * holding the derived value is the stopwatch that never advances their javadoc warns
     * about.
     *
     * <p>Not in here: the test-call report. It is a {@code StringBuilder} capped at 20 000
     * chars and copying it into every 1 Hz publish would make publishing cost proportional to
     * report length (PHASE-2-PLAN §2.7). It stays {@link #getTestReport()}.
     *
     * <p>Not in here either: what "no service" should read as on screen. That is presentation
     * - observe {@link #getServiceConnected()} and pick a string resource.
     */
    public LiveData<GatewayStatus> getGatewayStatus() {
        return gatewayStatus;
    }

    /**
     * Whether the ViewModel is bound to the gateway service right now (GW-45).
     *
     * <p>The companion to {@link #getGatewayStatus()}: {@code false} means the snapshot beside
     * it is {@link GatewayStatus#UNAVAILABLE} because there is nothing to read, rather than
     * because the gateway is idle. Both cases render as "nothing is running"; only this one
     * should say so in the words of a disconnected UI, and choosing those words is the view's
     * job.
     */
    public LiveData<Boolean> getServiceConnected() {
        return serviceConnected;
    }

    /**
     * @deprecated GW-45. A mutable POJO with public fields, handed out through LiveData, that
     *     carries three of the snapshot's fields and drops the rest - one of the three being
     *     {@link GatewayStatus#getStatusText()}, a pre-formatted composite rather than status.
     *     Use {@link #getGatewayStatus()} and {@link #getServiceConnected()}. Kept working
     *     because {@code MainActivity} still observes it; GW-41 rewrites that screen and
     *     deletes this with it.
     */
    @Deprecated
    public LiveData<ServiceState> getServiceState() {
        return serviceState;
    }

    /**
     * @deprecated GW-45. The three-line {@code "SIP: x\nCall: y\nAudio: z"} composite, which
     *     a caller then has to take apart to style any part of it. Observe
     *     {@link #getGatewayStatus()} and read {@link GatewayStatus#getSipStatus()},
     *     {@link GatewayStatus#getCallStatus()} and {@link GatewayStatus#getAudioStatus()}
     *     separately. Removed by GW-41.
     */
    @Deprecated
    public LiveData<String> getStatusText() {
        return statusText;
    }

    /**
     * @deprecated GW-45. One boolean lifted out of the snapshot. Observe
     *     {@link #getGatewayStatus()} and read {@link GatewayStatus#isSipRegistered()}, which
     *     comes with {@link GatewayStatus#getSipStatus()} beside it from the same capture
     *     instead of from a second LiveData that could be a tick behind. Removed by GW-41.
     */
    @Deprecated
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
     * What the pre-GW-45 status line said when nothing was bound.
     *
     * <p>Hoisted out of {@link #updateServiceState()} so the deprecated composite stays
     * byte-identical while it lives, and so the one literal left in it has a name to grep for.
     * It is presentation and belongs in {@code strings.xml}, but {@code res/} is GW-40's and
     * the whole composite is GW-41's to delete - so it is a seam, not a home.
     *
     * @deprecated GW-45, with {@link #getStatusText()}. A view observing
     *     {@link #getServiceConnected()} picks its own string resource.
     */
    @Deprecated
    static final String DISCONNECTED_STATUS_TEXT = "Service not connected";

    /**
     * The 1 Hz poll. Reads the service's immutable {@link GatewayStatus} snapshot, never the
     * live managers - those are owned by the control thread now (GW-10).
     *
     * <p><b>GW-45: the snapshot is republished whole.</b> This method used to flatten it into
     * three fields of a mutable {@link ServiceState} - {@code isRunning}, {@code isRegistered}
     * and the pre-formatted {@link GatewayStatus#getStatusText()} composite - and drop
     * everything else the control thread had published: the three status lines separately, the
     * call state, the call duration, the call-object counters, the config generation and the
     * whole of {@link GatewayStatus.WatchdogFindings}. That is plan §2 C1: the UI could not
     * render status it could not reach. {@link #getGatewayStatus()} hands the object over
     * instead and leaves the deriving to the view.
     *
     * <p>{@code setValue} dispatches on every call, so observers fire once a second even when
     * the service has published nothing new and the value is the same instance as last tick.
     * That is deliberate: {@code publishStatus()} on the service side is event-driven, while
     * {@link GatewayStatus#getCallDurationMs()} and {@link GatewayStatus#isInGracePeriod()}
     * re-read the clock on every call - so a call that is generating no events still has to be
     * asked again each second or the screen shows a stopwatch that never advances.
     *
     * <p>The test-call report is deliberately fetched separately and is not part of the
     * snapshot: it is a {@code StringBuilder} capped at 20 000 chars, and copying it into
     * every publish would make publishing cost proportional to report length (plan §2.7).
     *
     * <p>The config-generation check is what replaces GW-14's deleted {@code MainActivity}
     * relaunch. A config save from the web interface writes SharedPreferences on a NanoHTTPD
     * worker and never touches this ViewModel, so the SIP/audio form fields went stale; the
     * old reload path "fixed" that by restarting the activity with {@code CLEAR_TASK}, which
     * threw away whatever the person holding the phone was doing. Now the reload bumps a
     * counter in the snapshot and this poll re-reads config in place, at most a second later.
     * The status half needs nothing: {@code getStatusText()} is rebuilt from the live managers
     * on every publish.
     */
    private void updateServiceState() {
        // One read of the binding per tick. It is only ever written on this thread, but a
        // local keeps the branches below and the report fetch talking about the same object.
        final PjsipSipService service = pjsipService;
        final boolean connected = service != null;

        // UNAVAILABLE is the "service not connected" value (plan §4 GW-45 constraint 4), so
        // there is no null-state and no locally invented sentinel for the view to handle.
        // What it should read as on screen is presentation, and is not decided here.
        final GatewayStatus snapshot =
                connected ? service.getStatusSnapshot() : GatewayStatus.UNAVAILABLE;

        if (connected) {
            long generation = snapshot.getConfigGeneration();
            if (seenConfigGeneration < 0) {
                // First snapshot after binding - nothing has changed underneath us yet, and
                // loadConfig() has already run in the constructor.
                seenConfigGeneration = generation;
            } else if (generation != seenConfigGeneration) {
                seenConfigGeneration = generation;
                Log.d(TAG, "Config reloaded elsewhere (generation " + generation + "), re-reading");
                loadConfig();
            }
        } else {
            // A restarted process starts counting from zero again, so forget what we saw.
            seenConfigGeneration = -1L;
        }

        // GW-45. Verbatim: the object the control thread published, nothing derived from it
        // cached here - getCallDurationMs() and isInGracePeriod() are the view's to call, on
        // this object, at the moment it draws.
        serviceConnected.setValue(connected);
        gatewayStatus.setValue(snapshot);

        publishLegacyServiceState(connected, snapshot);

        if (connected) {
            String report = service.getTestCallReport();
            if (report != null && !report.isEmpty()) {
                testReport.setValue(report);
            }
        }
    }

    /**
     * Feed the pre-GW-45 LiveData from the same snapshot, unchanged in what it shows.
     *
     * <p>{@code MainActivity} still observes {@link #getServiceState()} and wave 1 has to leave
     * a working app, so this keeps the old three fields exactly as they were - including
     * {@link #DISCONNECTED_STATUS_TEXT}, which is what the unbound case said before and is not
     * {@link GatewayStatus#getStatusText()} of {@link GatewayStatus#UNAVAILABLE}. GW-41 rewrites
     * that screen and deletes this method with the getters it feeds.
     *
     * @deprecated with the surface it publishes.
     */
    @Deprecated
    @SuppressWarnings("deprecation")
    private void publishLegacyServiceState(boolean connected, GatewayStatus snapshot) {
        ServiceState state = new ServiceState();
        state.isRunning = snapshot.isRunning();
        state.isRegistered = snapshot.isSipRegistered();
        state.statusMessage =
                connected ? snapshot.getStatusText() : DISCONNECTED_STATUS_TEXT;

        serviceState.setValue(state);
        statusText.setValue(state.statusMessage);
        isRegistered.setValue(state.isRegistered);
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

    /**
     * What an audio save actually promises, stated precisely (AUDIT H4b).
     *
     * <p>It used to be a flat "Restart to apply", which was honest when the profile
     * snapshotted its whole configuration in its constructor. Now the route, the capture and
     * playback devices and the mute-control list are re-read by
     * {@code QualcommAudioProfile.setupMixer} on every call, so they take effect on the next
     * one. Two things still do not, because {@code GsmAudioPort} reads them once and its port
     * is never replaced ({@code AudioBridgeManager.Wiring}): the sound card, and which SoC
     * profile is selected. Saying "restart" for everything would now under-claim; saying
     * "applied" for everything would over-claim, which is the failure H4b is about.
     */
    static final String AUDIO_SAVED_TOAST =
            "Audio settings saved. Route, devices and mute controls apply on the next call; "
            + "sound card and SoC profile need a restart.";

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
        toastMessage.setValue(AUDIO_SAVED_TOAST);
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
        toastMessage.setValue(AUDIO_SAVED_TOAST);
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
     * <p>Writing it through {@code GatewayConfig} is now enough: {@code DeviceMuteManager}
     * re-reads the preset from config before every mute (its {@code refreshFromConfig}), so
     * the change reaches the live singleton on the next call. It did not before — the
     * manager read the preset once at construction and {@code savePreset} had no callers, so
     * selecting {@code custom} here did nothing until the process restarted.
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

    /**
     * @deprecated GW-45. A mutable POJO with public fields, published through LiveData, that
     *     carried three of {@link GatewayStatus}'s fields and dropped the rest. Observe
     *     {@link #getGatewayStatus()} - immutable, and complete - with
     *     {@link #getServiceConnected()} beside it. Removed by GW-41 together with the
     *     screen that reads it.
     */
    @Deprecated
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
