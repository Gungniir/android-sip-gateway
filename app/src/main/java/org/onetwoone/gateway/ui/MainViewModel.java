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
import org.onetwoone.gateway.R;
import org.onetwoone.gateway.config.GatewayConfig;
import org.onetwoone.gateway.core.GatewayStatus;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the main screen knows.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Service lifecycle - bind/unbind, start/stop/restart;
 *   <li>the status surface: the whole immutable {@link GatewayStatus} the control thread
 *       published, handed over verbatim (GW-45), plus whether there is a binding at all;
 *   <li>configuration, through {@code GatewayConfig} and nothing else - this class is the
 *       only owner of it on the UI path, and {@code MainActivity} reads no preferences;
 *   <li><b>view state</b> - which audio card, capture device, playback device, mixer route
 *       and SoC profile are selected but not yet saved, and which sections of the screen are
 *       open (GW-41, plan §4 hazard H-d).
 * </ul>
 *
 * <p>That last item is the one that changed in GW-41 and is worth stating as a rule. Selection
 * state used to live in {@code MainActivity} as plain fields with an {@code isRefreshing}
 * flag; it now lives here, and <b>every setter is idempotent</b>. That is not tidiness: it is
 * the only defence that works against {@code Spinner.setSelection()}, which delivers
 * {@code onItemSelected} asynchronously, so a repaint reaches the listener looking exactly
 * like a choice long after any "I am repainting" flag has been cleared.
 */
public class MainViewModel extends AndroidViewModel {
    private static final String TAG = "MainVM";

    /**
     * The four collapsible groups of the main screen (GW-41 step 4c).
     *
     * <p>Named rather than indexed so a layout change cannot quietly re-map which section a
     * remembered expansion belongs to.
     */
    public enum Section {
        /** SIP account, per-SIM routing, incoming call mode, service controls. */
        SIP,
        /** SoC profile, ALSA card and devices, mixer route, gain, mute preset. */
        AUDIO,
        /** Test call, its report, the logging switches, root permission status. */
        DIAGNOSTICS,
        /** Battery charge limit and the web interface. */
        SYSTEM
    }

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

    /**
     * The mixer routes the audio bridge can be pinned to. Here rather than in the activity
     * because the selection is ViewModel state now (GW-41, plan §4 hazard H-d) and the list
     * and the selection have to agree.
     */
    public static final String[] MIXER_ROUTES = {
            "MultiMedia1", "MultiMedia2", "MultiMedia3", "MultiMedia4"};

    /** Diagnostic call modes, as {@code PjsipSipService.startTestCall} understands them. */
    public static final String[] TEST_MODES = {"tone", "loopback", "bridge"};

    /**
     * SoC audio profiles, exactly as {@code AudioProfileFactory.select} matches them
     * (GW-41 step 4d). Same three values the web page has always offered; this is the half
     * of PHASE-4-PLAN §C8 that was missing from the phone.
     */
    public static final String[] AUDIO_PROFILES = {"auto", "qualcomm", "mediatek"};

    // Configuration (observed from GatewayConfig)
    private final MutableLiveData<SipConfig> sipConfig = new MutableLiveData<>();
    private final MutableLiveData<AudioConfig> audioConfig = new MutableLiveData<>();
    private final MutableLiveData<DiagnosticsConfig> diagnosticsConfig = new MutableLiveData<>();
    private final MutableLiveData<Boolean> webInterfaceEnabled = new MutableLiveData<>(false);

    /**
     * The audio selections the operator has made but not yet saved.
     *
     * <p>GW-41, plan §4 hazard H-d. {@code MainActivity} used to hold these four as plain
     * fields - {@code selectedCard}, {@code selectedCaptureDevice},
     * {@code selectedPlaybackDevice}, {@code selectedMixerRoute} - seeded from the
     * {@code getAudioConfig()} observer and written back by four spinner listeners, with an
     * {@code isRefreshing} flag trying to tell a repaint from a choice. That is view state
     * living in the view, and it did not survive anything: a configuration change threw it
     * away and re-seeded it from config, silently discarding a pending change.
     *
     * <p>They are also why the spinner listeners can be careless about spurious callbacks.
     * {@code Spinner.setSelection()} does not deliver {@code onItemSelected} synchronously,
     * so no "I am currently repainting" flag can be trusted around it; instead every setter
     * below is idempotent, and a set-to-the-same-value does nothing at all.
     */
    private final MutableLiveData<Integer> selectedCard = new MutableLiveData<>(0);
    private final MutableLiveData<Integer> selectedCaptureDevice = new MutableLiveData<>(0);
    private final MutableLiveData<Integer> selectedPlaybackDevice = new MutableLiveData<>(0);
    private final MutableLiveData<String> selectedMixerRoute = new MutableLiveData<>(MIXER_ROUTES[0]);
    private final MutableLiveData<String> selectedAudioProfile =
            new MutableLiveData<>(GatewayConfig.DEFAULT_AUDIO_PROFILE);

    /**
     * Which sections of the main screen are open.
     *
     * <p>Also view state, and here for the same reason: a night-mode switch recreates the
     * activity, and sections that closed themselves on recreation would be the same class of
     * defect as the toast that fires twice. SIP starts open because commissioning a handset
     * that has never registered is the one job you cannot do from anywhere else.
     */
    private final Map<Section, MutableLiveData<Boolean>> sectionExpanded = new EnumMap<>(Section.class);

    /**
     * Toast messages, as consume-once events (GW-41, plan §4 hazard H-c).
     *
     * <p>This was a {@code MutableLiveData<String>}, which is a state holder: it replays its
     * last value to every new observer, so on any configuration change the last toast fired
     * again. Latent while nothing re-observed much; this wave adds a night-mode switch and a
     * restructured screen, both of which surface it. {@link Event} makes the replay a no-op.
     */
    private final MutableLiveData<Event<String>> toastMessage = new MutableLiveData<>();

    // Battery and mute state
    private final MutableLiveData<Integer> batteryLimit = new MutableLiveData<>();
    private final MutableLiveData<String> currentMutePreset = new MutableLiveData<>();
    private final MutableLiveData<Boolean> showCustomControls = new MutableLiveData<>(false);
    private final MutableLiveData<String> manualMuteControls = new MutableLiveData<>("");
    private final MutableLiveData<List<TinymixManager.MixerControl>> availableControls = new MutableLiveData<>();

    // SIP diagnostics (test call)
    private final MutableLiveData<String> testReport = new MutableLiveData<>(
            getApplication().getString(R.string.status_no_test_call));

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
            // An unchecked cast on a callback argument, on the UI thread, in an app whose
            // whole job is to keep running unattended. instanceof covers null as well, and
            // "the binding did not produce our service" is a state this can survive - the
            // poll publishes UNAVAILABLE and the header says so.
            if (!(binder instanceof PjsipSipService.LocalBinder)) {
                Log.w(TAG, "Ignoring a binding that is not PjsipSipService.LocalBinder: " + binder);
                return;
            }
            pjsipService = ((PjsipSipService.LocalBinder) binder).getService();
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

        // Section expansion. SIP open, the rest closed: health is the pinned header, and a
        // screen that opens with four expanded sections is the flat column this wave removed.
        for (Section section : Section.values()) {
            sectionExpanded.put(section, new MutableLiveData<>(section == Section.SIP));
        }

        // Load initial config
        loadConfig();

        // Under the custom preset the mixer-control checkboxes are populated from a root
        // scan, and the only thing that used to trigger it at launch was a Spinner delivering
        // its restored selection as if it were a choice. GW-41 made the selection setters
        // idempotent - correctly - so the scan is asked for here instead, where it is a
        // consequence of the config rather than of a callback ordering.
        if (DeviceMuteManager.PRESET_CUSTOM.equals(GatewayConfig.getInstance().getMutePreset())) {
            detectMixerControls();
        }

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

    public LiveData<SipConfig> getSipConfig() {
        return sipConfig;
    }

    public LiveData<AudioConfig> getAudioConfig() {
        return audioConfig;
    }

    /**
     * Transient messages, each delivered once (GW-41, plan §4 hazard H-c). Observers must go
     * through {@link Event#getContentIfNotHandled()}; a value redelivered after a
     * configuration change comes back already handled and is a no-op.
     */
    public LiveData<Event<String>> getToastMessage() {
        return toastMessage;
    }

    /** Diagnostic settings that persist: test destination, mode, and the two log switches. */
    public LiveData<DiagnosticsConfig> getDiagnosticsConfig() {
        return diagnosticsConfig;
    }

    /**
     * Whether the embedded web server is meant to be running.
     *
     * <p>Published rather than read from {@code GatewayConfig} at the view, so that a change
     * made on the web page itself reaches the switch on the phone through the same
     * config-generation poll as everything else. It did not before: the old screen read the
     * value once in {@code setupWebInterfaceSwitch()} and never looked again.
     */
    public LiveData<Boolean> getWebInterfaceEnabled() {
        return webInterfaceEnabled;
    }

    public LiveData<Integer> getSelectedCard() {
        return selectedCard;
    }

    public LiveData<Integer> getSelectedCaptureDevice() {
        return selectedCaptureDevice;
    }

    public LiveData<Integer> getSelectedPlaybackDevice() {
        return selectedPlaybackDevice;
    }

    public LiveData<String> getSelectedMixerRoute() {
        return selectedMixerRoute;
    }

    /** The SoC audio profile, one of {@link #AUDIO_PROFILES} (GW-41 step 4d). */
    public LiveData<String> getSelectedAudioProfile() {
        return selectedAudioProfile;
    }

    /** Whether {@code section} is currently expanded. Never null for a real {@link Section}. */
    public LiveData<Boolean> getSectionExpanded(Section section) {
        return sectionExpanded.get(section);
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
        toast(getApplication().getString(R.string.toast_connecting));
    }

    public void stopService() {
        Log.d(TAG, "Stopping service");

        if (pjsipService != null) {
            pjsipService.stop();
            pjsipService = null;
        }

        unbindFromService();
        toast(getApplication().getString(R.string.toast_disconnected));
        // No status line is written here any more. The next poll publishes UNAVAILABLE with
        // serviceConnected false, and the header renders R.string.status_service_stopped from
        // that - one source of truth instead of a String pushed from the stop path.
    }

    public void restartService() {
        Log.d(TAG, "Restarting service");
        toast(getApplication().getString(R.string.toast_restarting));

        stopService();

        // Wait for PJSIP cleanup
        statusHandler.postDelayed(() -> {
            startService();
            toast(getApplication().getString(R.string.toast_restarted));
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

    /**
     * Post a transient message for the view to show once.
     *
     * <p>Wrapping every message in a fresh {@link Event} is what makes the replay a
     * {@code LiveData} performs on a new or re-activated observer harmless: the replayed
     * event is already handled, so nothing fires (plan §4 hazard H-c).
     */
    private void toast(String message) {
        toastMessage.setValue(new Event<>(message));
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
     * <p><b>GW-45: the snapshot is republished whole.</b> This method used to flatten it into
     * three fields of a mutable {@code ServiceState} POJO - {@code isRunning},
     * {@code isRegistered} and a pre-formatted three-line composite - and drop everything else
     * the control thread had published: the three status lines separately, the call state, the
     * call duration, the call-object counters, the config generation and the whole of
     * {@link GatewayStatus.WatchdogFindings}. That is plan §2 C1: the UI could not render
     * status it could not reach. {@link #getGatewayStatus()} hands the object over instead and
     * leaves the deriving to the view. <b>GW-41 deleted that POJO and the three deprecated
     * getters that carried it</b>, which is what wave 1 kept them working for.
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

        if (connected) {
            String report = service.getTestCallReport();
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
            toast(getApplication().getString(R.string.toast_service_not_connected));
            return;
        }

        GatewayConfig config = GatewayConfig.getInstance();
        config.setTestDestination(destination);
        config.setTestMode(mode);
        republishDiagnostics(config);

        pjsipService.startTestCall(destination, mode, 0);
        toast(
                getApplication().getString(R.string.toast_test_call, destination, mode));
    }

    public void stopTestCall() {
        if (pjsipService != null) {
            pjsipService.stopTestCall();
        }
    }

    public void setVerboseSipLog(boolean enabled) {
        GatewayConfig config = GatewayConfig.getInstance();
        config.setVerboseSipLog(enabled);
        republishDiagnostics(config);
        toast(getApplication().getString(enabled
                ? R.string.toast_verbose_sip_log_on
                : R.string.toast_verbose_sip_log_off));
    }

    public void setDtmfRelay(boolean enabled) {
        GatewayConfig config = GatewayConfig.getInstance();
        config.setDtmfRelayEnabled(enabled);
        republishDiagnostics(config);
        toast(getApplication().getString(
                enabled ? R.string.toast_dtmf_relay_on : R.string.toast_dtmf_relay_off));
    }

    /**
     * Choose how an incoming GSM call is bridged.
     *
     * <p>Writes through immediately, as it always did - there is no Save button over this
     * radio group. What is new is that the published {@link SipConfig} is updated with it, so
     * the value the view was last told is the value that is persisted. The old screen wrote
     * config from the listener and left its own LiveData stale until the next reload.
     */
    public void setIncomingCallMode(int mode) {
        GatewayConfig config = GatewayConfig.getInstance();
        config.setIncomingCallMode(mode);

        SipConfig sip = sipConfig.getValue();
        if (sip != null) {
            sip.incomingCallMode = mode;
            sipConfig.setValue(sip);
        }
        Log.d(TAG, "Incoming call mode set to " + mode);
    }

    private void republishDiagnostics(GatewayConfig config) {
        DiagnosticsConfig diagnostics = new DiagnosticsConfig();
        diagnostics.testDestination = config.getTestDestination();
        diagnostics.testMode = config.getTestMode();
        diagnostics.verboseSipLog = config.isVerboseSipLog();
        diagnostics.dtmfRelay = config.isDtmfRelayEnabled();
        diagnosticsConfig.setValue(diagnostics);
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
        audio.audioProfile = config.getAudioProfile();
        audioConfig.setValue(audio);

        // The pending audio selections follow the persisted config whenever it is re-read.
        // That is the same rule the form fields follow: a reload from the web interface is
        // the persisted truth, and there is nothing on screen it should not replace.
        selectedCard.setValue(audio.card);
        selectedCaptureDevice.setValue(audio.captureDevice);
        selectedPlaybackDevice.setValue(audio.playbackDevice);
        selectedMixerRoute.setValue(audio.multimediaRoute);
        selectedAudioProfile.setValue(audio.audioProfile);

        // Diagnostics
        republishDiagnostics(config);

        // Web interface
        webInterfaceEnabled.setValue(config.isWebInterfaceEnabled());

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

    // ========== View state the activity used to hold (plan §4 hazard H-d) ==========

    /**
     * Choose the ALSA card. Selecting a different one re-enumerates its devices, which is why
     * the no-op guard matters: {@code Spinner.setSelection()} delivers its callback
     * asynchronously, so a repaint can arrive as a "selection" and would otherwise kick off a
     * root device scan on every rebind.
     */
    public void setSelectedCard(int card) {
        if (equalInt(selectedCard.getValue(), card)) {
            return;
        }
        selectedCard.setValue(card);
        audioDeviceManager.refreshDevices(card);
    }

    public void setSelectedCaptureDevice(int device) {
        if (!equalInt(selectedCaptureDevice.getValue(), device)) {
            selectedCaptureDevice.setValue(device);
        }
    }

    public void setSelectedPlaybackDevice(int device) {
        if (!equalInt(selectedPlaybackDevice.getValue(), device)) {
            selectedPlaybackDevice.setValue(device);
        }
    }

    public void setSelectedMixerRoute(String route) {
        if (route != null && !route.equals(selectedMixerRoute.getValue())) {
            selectedMixerRoute.setValue(route);
        }
    }

    /**
     * Choose the SoC audio profile (GW-41 step 4d).
     *
     * <p>Held pending like the other audio selections and written by
     * {@link #saveAudioConfig}, not here: the profile is read once when the bridge's port is
     * built and never re-read, so it is one of the two settings whose save toast says
     * "needs a restart". Applying it the instant a spinner moved would be a lie in the other
     * direction.
     */
    public void setSelectedAudioProfile(String profile) {
        if (profile != null && !profile.equals(selectedAudioProfile.getValue())) {
            selectedAudioProfile.setValue(profile);
        }
    }

    /** Open a closed section, close an open one. */
    public void toggleSection(Section section) {
        MutableLiveData<Boolean> state = sectionExpanded.get(section);
        if (state == null) {
            return;
        }
        state.setValue(!Boolean.TRUE.equals(state.getValue()));
    }

    private static boolean equalInt(Integer current, int candidate) {
        return current != null && current == candidate;
    }

    public void saveSipConfig(String server, int port, String user, String password,
                              String realm, boolean useTls, String sim1, String sim2) {
        GatewayConfig config = GatewayConfig.getInstance();
        config.updateSipConfig(server, port, user, password, realm, useTls);
        config.updateSimDestinations(sim1, sim2);

        // Refresh LiveData
        loadConfig();

        toast(getApplication().getString(R.string.toast_sip_settings_saved));
        Log.d(TAG, "SIP config saved: " + user + "@" + server);
    }

    /**
     * What an audio save actually promises, stated precisely (AUDIT H4b).
     *
     * <p>The text, and the reasoning behind every clause of it, moved to
     * {@code R.string.toast_audio_settings_saved} in GW-40 - the argument about which
     * settings apply on the next call and which need a restart belongs next to the words
     * that make the claim. Read the comment above that resource before changing the string.
     */
    private String audioSavedToast() {
        return getApplication().getString(R.string.toast_audio_settings_saved);
    }

    /**
     * Persist the audio bridge configuration.
     *
     * <p>The card, capture device, playback device, mixer route and SoC profile are taken
     * from this ViewModel's own selection state rather than passed in (plan §4 hazard H-d):
     * they are already here, and a screen that had to hand them back would be mirroring them
     * again. The gain values, the mute checkboxes and the manual control list are read from
     * the widgets at save time because that is where they live - they are typed, not chosen.
     *
     * <p>The SoC profile is new to this surface (GW-41 step 4d, plan §C8). Note that
     * {@code R.string.toast_audio_settings_saved} already names it as one of the two settings
     * that need a restart; GW-40 wrote that sentence for this control.
     */
    public void saveAudioConfig(float txGain, float rxGain, Set<String> muteControls,
                                String manualControls) {
        int card = intValue(selectedCard.getValue());
        int capture = intValue(selectedCaptureDevice.getValue());
        int playback = intValue(selectedPlaybackDevice.getValue());
        String route = stringValue(selectedMixerRoute.getValue(), MIXER_ROUTES[0]);
        String profile = stringValue(selectedAudioProfile.getValue(),
                GatewayConfig.DEFAULT_AUDIO_PROFILE);

        GatewayConfig config = GatewayConfig.getInstance();
        config.updateAudioConfig(card, capture, playback, route);
        config.setAudioProfile(profile);
        config.setTxGain(txGain);
        config.setRxGain(rxGain);
        config.setMicMuteControls(muteControls);
        config.setManualMuteControls(manualControls);

        loadConfig();
        toast(audioSavedToast());
        Log.d(TAG, "Audio config saved: card=" + card + ", capture=" + capture +
              ", playback=" + playback + ", route=" + route + ", profile=" + profile +
              ", txGain=" + txGain + ", rxGain=" + rxGain +
              ", manualControls=" + manualControls);
    }

    private static int intValue(Integer value) {
        return value == null ? 0 : value;
    }

    private static String stringValue(String value, String fallback) {
        return value == null ? fallback : value;
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
        webInterfaceEnabled.setValue(enabled);

        if (pjsipService != null) {
            if (enabled) {
                pjsipService.startWebServer();
                toast(
                        getApplication().getString(R.string.toast_web_interface_enabled));
            } else {
                pjsipService.stopWebServer();
                toast(
                        getApplication().getString(R.string.toast_web_interface_disabled));
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
        // The card the operator has CHOSEN, not the one last saved: picking a different card
        // and then asking what mixer controls it has should not scan the previous one.
        final int card = intValue(selectedCard.getValue());

        new Thread(() -> {
            List<TinymixManager.MixerControl> controls = tinymixManager.detectControls(card);
            statusHandler.post(() -> availableControls.setValue(controls));
        }).start();
    }

    /**
     * Refresh audio device lists for the current card.
     */
    public void refreshAudioDevices() {
        audioDeviceManager.refreshDevices(intValue(selectedCard.getValue()));
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
        /** One of {@link #AUDIO_PROFILES}. GW-41 step 4d. */
        public String audioProfile = GatewayConfig.DEFAULT_AUDIO_PROFILE;
    }

    /**
     * The diagnostics settings that persist across launches.
     *
     * <p>These four were read straight out of {@code GatewayConfig} by {@code MainActivity}
     * in {@code setupTestCallControls()}, once, at {@code onCreate}. Publishing them means
     * they follow a config reload like everything else - and it removes the last direct
     * {@code GatewayConfig} read from the view layer.
     */
    public static class DiagnosticsConfig {
        public String testDestination = "";
        /** One of {@link #TEST_MODES}. */
        public String testMode = TEST_MODES[0];
        public boolean verboseSipLog = false;
        public boolean dtmfRelay = false;
    }
}
