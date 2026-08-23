package org.onetwoone.gateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import org.onetwoone.gateway.audio.AudioBridgeManager;
import org.onetwoone.gateway.call.CallManager;
import org.onetwoone.gateway.config.GatewayConfig;
import org.onetwoone.gateway.core.ControlThread;
import org.onetwoone.gateway.core.GatewayControlThread;
import org.onetwoone.gateway.core.GatewayStatus;
import org.onetwoone.gateway.diag.SipTestCallManager;
import org.onetwoone.gateway.diag.SipUriBuilder;
import org.onetwoone.gateway.power.PowerController;
import org.onetwoone.gateway.sip.ReconnectionStrategy;
import org.onetwoone.gateway.sip.ServiceWatchdog;
import org.onetwoone.gateway.sip.SipAccountManager;
import org.onetwoone.gateway.sip.SipEndpointManager;
import org.pjsip.pjsua2.*;

/**
 * GSM-SIP Gateway Service (Refactored v2).
 *
 * This is a facade that coordinates between specialized managers:
 * - SipEndpointManager: PJSIP endpoint lifecycle
 * - SipAccountManager: SIP registration
 * - CallManager: Call coordination
 * - AudioBridgeManager: Audio bridging
 * - PowerController: WakeLock management
 * - ReconnectionStrategy: Auto-reconnect
 * - ServiceWatchdog: Orphaned call detection
 *
 * <h3>Threading (GW-10)</h3>
 * Call, audio-bridge and SIP-account lifecycle state has exactly one owner: the
 * {@link GatewayControlThread}. Every entry point that touches it - the six pjsua callbacks,
 * the Telecom and phone-state hops, the watchdog tick, the reconnect action and the public
 * commands - posts onto it. Handlers that run there are marked {@link ControlThread} and
 * assert it as their first statement.
 *
 * <p>Two things deliberately do <b>not</b> move onto it:
 * <ul>
 *   <li>the pjmedia RT callbacks ({@code GsmAudioPort.onFrameRequested} /
 *       {@code onFrameReceived}), which must never post or block;
 *   <li>flags that gate later pjsua2 calls - {@code GatewayCall.disposed},
 *       {@code SipTestCallManager.mediaValid}, {@code SipAccountManager.registered}. Those
 *       are set synchronously on the callback thread; only the work that follows is posted.
 * </ul>
 *
 * <p>Reads for the UI go through an immutable {@link GatewayStatus} snapshot published from
 * the control thread, never through the live managers.
 */
public class PjsipSipService extends Service implements SipCallService {
    private static final String TAG = "GatewaySvc";
    private static final String CHANNEL_ID = "gateway_channel";
    private static final int NOTIFICATION_ID = 1;

    /**
     * Written on main ({@link #onCreate()} / {@link #onDestroy()}), read from pjsua workers,
     * NanoHTTPD workers ({@code WebConfigServer}), the Telecom callbacks in
     * {@code GatewayInCallService}, {@code GsmDtmfSender} and {@code GatewayControlReceiver}
     * - hence {@code volatile} (AUDIT H5). Every consumer already snapshots it into a local.
     */
    private static volatile PjsipSipService instance;

    // Managers
    private GatewayConfig config;
    private SipEndpointManager endpointManager;
    private SipAccountManager accountManager;
    private CallManager callManager;
    private AudioBridgeManager audioBridge;
    private PowerController powerController;
    private ReconnectionStrategy reconnection;
    private ServiceWatchdog watchdog;
    private SmsHandler smsHandler;
    private WebConfigServer webServer;
    private SipTestCallManager testCall;

    /**
     * The one thread that owns call/audio/SIP lifecycle state. Created in {@link #onCreate()}
     * and quit in {@link #onDestroy()}.
     */
    private GatewayControlThread control;

    // Telephony
    private TelephonyManager telephonyManager;
    private PhoneStateListener phoneStateListener;
    private int lastPhoneState = TelephonyManager.CALL_STATE_IDLE;

    /**
     * The {@link DeviceMuteManager} lease held by the GSM call that is currently up, or
     * {@link DeviceMuteManager#NO_LEASE}. Written from the Telecom callback (main) and from
     * onDestroy; atomic so the read-and-clear on the DISCONNECTED path cannot hand the same
     * lease to two releases (AUDIT B1).
     */
    private final java.util.concurrent.atomic.AtomicLong muteLease =
            new java.util.concurrent.atomic.AtomicLong(DeviceMuteManager.NO_LEASE);

    /**
     * How long onDestroy waits for the mute restore to land. Service teardown only — the
     * per-call teardown path never blocks (AUDIT H2c). The restore itself is only mixer
     * writes, no {@code tinymix} reads, so it is milliseconds unless a mute is still in
     * flight ahead of it — and that one is already cancelled and unwinding.
     */
    private static final long MUTE_RESTORE_TIMEOUT_MS = 2000L;

    /**
     * How long {@link #onDestroy()} waits for the control thread's queue to drain. Bounded
     * on purpose - see {@link GatewayControlThread#quitSafely(long)}, which explains why this
     * is the only place main may wait on the control thread.
     */
    private static final long CONTROL_QUIT_TIMEOUT_MS = 1500L;

    // State
    /**
     * Written by {@link #onStartCommand} and {@link #onDestroy}, both on main; the
     * check-then-set in {@code onStartCommand} is still main-only and still asserted
     * ({@link #assertMainThread(String)}), because that is what makes it atomic (AUDIT H5).
     *
     * <p>{@code volatile} since GW-10: the reconnect action now runs on the control thread
     * ({@link #attemptReconnect()} used to be a main-looper callback), and
     * {@link #publishStatus()} reads it there too. One main writer, cross-thread readers -
     * exactly what volatile is for. It does not make the check-then-set atomic and is not
     * meant to.
     */
    private volatile boolean isRunning = false;
    private volatile boolean stopRequested = false;
    private Handler mainHandler;

    /**
     * The last snapshot published by {@link #publishStatus()}. Written on the control thread,
     * read from main (the 1 Hz UI poll), from Telecom and from NanoHTTPD - hence volatile,
     * and immutable so a reader can never see a half-built one.
     */
    private volatile GatewayStatus status = GatewayStatus.UNAVAILABLE;

    // Binder
    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public PjsipSipService getService() {
            return PjsipSipService.this;
        }
    }

    static {
        try {
            System.loadLibrary("pjsua2");
            Log.d(TAG, "PJSIP library loaded");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load PJSIP: " + e.getMessage());
        }
    }

    public static PjsipSipService getInstance() {
        return instance;
    }

    // ========== Service Lifecycle ==========

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        stopRequested = false;  // Reset flag on new service instance
        mainHandler = new Handler(Looper.getMainLooper());

        // Initialize config
        GatewayConfig.init(this);
        config = GatewayConfig.getInstance();

        // Initialize managers
        initializeManagers();

        // Setup telephony listener
        setupPhoneStateListener();

        Log.d(TAG, "Service created");
    }

    private void initializeManagers() {
        // Power controller (acquire wake lock immediately).
        //
        // disableBatteryOptimizationsAsync stays on its own thread and is NOT folded onto the
        // control thread (contrary to GW-10 §4, corrected by plan §2.1): it is six
        // RootHelper.execRoot calls at a 5 s timeout each, a ~30 s worst case, and it touches
        // no call/audio/SIP state at all. Folding it in would make the control thread
        // unavailable for half a minute at every service start - precisely when inbound calls
        // arrive.
        powerController = new PowerController(this);
        powerController.acquireCpuWakeLock();
        powerController.disableBatteryOptimizationsAsync();

        // SIP components. The endpoint manager is built first so the control thread can be
        // handed its registerThread method - see GatewayControlThread's "pjlib registration"
        // note for why the thread cannot simply register at construction.
        endpointManager = new SipEndpointManager(config);
        control = new GatewayControlThread(endpointManager::registerThread);

        accountManager = new SipAccountManager(config, endpointManager);

        // Call management
        callManager = new CallManager(this, config);
        callManager.setListener(callListener);

        // Audio bridge
        audioBridge = new AudioBridgeManager(this, config);

        // Diagnostic SIP test call (no GSM leg) - see SipTestCallManager. Still main-bound:
        // its internals call pjsua2 from the main looper, which SIP init registers with
        // pjlib. GW-10 changes only who demuxes its callbacks, not where they are handled.
        testCall = new SipTestCallManager(this, config, accountManager, audioBridge,
                this, mainHandler);

        // Reconnection strategy. Its timer still fires on main; the action hops to the
        // control thread, because attemptReconnect() runs initializeSip().
        reconnection = new ReconnectionStrategy(() -> control.post(this::attemptReconnect));

        // Watchdog. Same shape: main-looper timer, control-thread check.
        watchdog = new ServiceWatchdog(() -> control.post(this::checkOrphanedCalls));

        // Account listener
        accountManager.setListener(accountListener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service starting");

        startForegroundNotification();

        if (!isRunning) {
            isRunning = true;
            reconnection.setEnabled(true);
            watchdog.start();

            // Was the "SipInit" bare thread. Same body, same blocking, one owner.
            control.post(this::initializeSip);

            // Initialize SMS handler
            initSmsHandler();

            // Start web server if enabled
            if (config.isWebInterfaceEnabled()) {
                startWebServer();
            }
        }

        control.post(this::publishStatus);

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service destroying");

        isRunning = false;
        instance = null;
        status = GatewayStatus.UNAVAILABLE;

        // Hand the device's mic and earpiece back FIRST (AUDIT B1). Queued here rather than
        // waited on here, so the restore runs while the teardown below does its own work.
        DeviceMuteManager mute = null;
        long lease = muteLease.getAndSet(DeviceMuteManager.NO_LEASE);
        if (lease != DeviceMuteManager.NO_LEASE) {
            mute = DeviceMuteManager.getInstance(this);
            mute.release(lease);
        }

        // Stop components
        watchdog.stop();
        reconnection.setEnabled(false);
        reconnection.cancel();

        if (smsHandler != null) {
            smsHandler.stop();
        }

        stopWebServer();

        // Retire the control thread BEFORE tearing SIP down, so nothing still queued there
        // runs against an endpoint this thread is about to shut down. quitSafely() drains
        // what is already queued; the join is bounded, which is what keeps this - the only
        // main-blocks-on-control wait in the app - from being a deadlock. See
        // GatewayControlThread.quitSafely(long).
        control.quitSafely(CONTROL_QUIT_TIMEOUT_MS);

        // Shutdown SIP. Still on main, as before: it calls pjsua2 and main is registered with
        // pjlib. Moving it off main is AUDIT G2, owned by GW-26.
        shutdownSip();

        // Release power
        powerController.release();

        // Cleanup telephony
        if (telephonyManager != null && phoneStateListener != null) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
        }

        // Only now wait on the restore queued at the top: by this point it has almost always
        // finished behind shutdownSip(), so the wait costs nothing. Bounded either way — a
        // phone left without a microphone is worse than a slow teardown, but not unboundedly.
        if (mute != null && !mute.awaitRestore(MUTE_RESTORE_TIMEOUT_MS)) {
            Log.w(TAG, "Mute restore still running after " + MUTE_RESTORE_TIMEOUT_MS + " ms");
        }

        stopForeground(true);
        Log.d(TAG, "Service destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // ========== SIP Initialization ==========

    /**
     * Bring PJSIP up. Was the {@code SipInit} bare thread; now a control-thread task.
     *
     * <p><b>Why the main hop inside {@code createEndpoint()} cannot self-deadlock.</b>
     * {@code createEndpoint()} constructs the {@code Endpoint} on the <em>main</em> looper -
     * pjsua auto-registers only the thread that loaded the native library, and that is main -
     * and blocks the caller on a 30 s latch. That caller used to be {@code SipInit}; it is
     * now the control thread. That is allowed, and it is safe in exactly one direction:
     * <ul>
     *   <li>main is not waiting on the control thread while this runs. Every main→control
     *       hand-off in the app is a fire-and-forget {@code post}; nothing on main takes a
     *       latch, a {@code Future.get} or a {@code join} on control's result. The single
     *       exception is the bounded {@code quitSafely} join at service destroy, which
     *       resolves after {@code CONTROL_QUIT_TIMEOUT_MS} rather than waiting forever.
     *   <li>the runnable being awaited is posted onto main's queue and needs nothing from the
     *       control thread to complete, so it cannot be blocked by the very thread waiting
     *       for it.
     * </ul>
     * Keep it this way: <b>control may block on main, main must never block on control</b>
     * (plan §2.4). If a future change makes main wait on a control-thread result, this await
     * becomes a real deadlock.
     */
    @ControlThread
    private void initializeSip() {
        control.assertOnControlThread("initializeSip");
        try {
            Log.d(TAG, "Initializing SIP...");

            // Create endpoint (hops to main and waits - see the javadoc above)
            endpointManager.createEndpoint();

            // Hand THIS thread to pjlib now that an endpoint exists to register with. This
            // MUST happen before any other PJSIP call from here. Idempotent and one-shot:
            // GatewayControlThread also tries this at the head of every task, so whichever
            // gets there first is the only registration that ever happens.
            if (!control.registerWithPjlib()) {
                throw new Exception("Failed to register the control thread with pjlib");
            }

            // Register main thread for callbacks. Still needed: SipTestCallManager's
            // internals and the SMS path call pjsua2 from the main looper.
            mainHandler.post(() -> {
                if (!endpointManager.registerThread("MainThread")) {
                    Log.e(TAG, "Failed to register MainThread");
                }
            });

            // Initialize audio bridge
            audioBridge.initialize();

            // Create and register account
            accountManager.createAccount(this);

            Log.d(TAG, "SIP initialized");

        } catch (SipEndpointManager.TlsChangedException e) {
            // TLS setting changed - PJSIP cannot safely recreate endpoint
            // Must kill the entire process and restart
            Log.e(TAG, "TLS changed, restarting process: " + e.getMessage());
            restartProcess();
        } catch (Exception e) {
            Log.e(TAG, "SIP init failed: " + e.getMessage(), e);
            updateNotification("Error: " + e.getMessage());
            reconnection.scheduleReconnect();
        }
        publishStatus();
    }

    private void shutdownSip() {
        Log.d(TAG, "Shutting down SIP...");

        // Stop audio bridge and streams, but DON'T release (keep port alive)
        // Releasing while PJSIP still running causes NullPointerException in onFrameReceived
        audioBridge.stopBridge();
        audioBridge.stopAudioStreams();

        // Delete account
        accountManager.deleteAccount();

        // Keep endpoint alive for reuse (don't destroy it)
        // PJSIP native library crashes if we destroy and recreate endpoint in same process
        endpointManager.shutdown();

        Log.d(TAG, "SIP shutdown complete");
    }

    /**
     * Retry SIP bring-up after a failure.
     *
     * <p>Moved off main by GW-10, and not optional: it calls {@link #initializeSip()}, which
     * is now a control-thread task. {@code ReconnectionStrategy} still counts its backoff on
     * the main looper and hops here.
     */
    @ControlThread
    private void attemptReconnect() {
        control.assertOnControlThread("attemptReconnect");
        if (!isRunning) return;

        Log.d(TAG, "Attempting reconnect...");

        try {
            // Check if endpoint is properly initialized (has transport)
            // CRITICAL: Must check hasTransport() - creating account without transport causes PJSIP crash
            if (!endpointManager.isInitialized() || !endpointManager.hasTransport() || accountManager.getAccount() == null) {
                // Endpoint not ready, transport missing, or account missing - need full init
                Log.d(TAG, "Endpoint/transport/account not ready, performing full initialization");
                initializeSip();
            } else {
                // Endpoint and transport ready, just re-register
                accountManager.getAccount().setRegistration(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Reconnect failed: " + e.getMessage());
            reconnection.scheduleReconnect();
        }
    }

    // ========== Account Callbacks ==========

    private final SipAccountManager.AccountListener accountListener = new SipAccountManager.AccountListener() {
        /**
         * Runs on a pjsua worker. {@code SipAccountManager.registered} has already been set,
         * synchronously, before this listener is invoked - that flag is never posted, only the
         * handling below is (plan §2.6). Retargeted from mainHandler to the control thread.
         */
        @Override
        public void onRegistrationState(boolean registered, String reason) {
            control.post(() -> handleRegistrationState(registered, reason));
        }

        /**
         * Runs on a pjsua worker. The {@link GatewayCall} must still be <em>constructed</em>
         * here: the callId is only valid inside the callback. Only the handling is posted.
         */
        @Override
        public void onIncomingCall(GatewayAccount account, int callId, int simSlotHint) {
            try {
                GatewayCall call = new GatewayCall(PjsipSipService.this, account, callId);
                control.post(() -> handleIncomingSipCall(call, simSlotHint));
            } catch (Exception e) {
                Log.e(TAG, "Error creating call: " + e.getMessage());
            }
        }

        @Override
        public void onInstantMessage(String from, String to, String body, int simSlot) {
            control.post(() -> handleIncomingSipMessage(from, to, body, simSlot));
        }
    };

    @ControlThread
    private void handleRegistrationState(boolean registered, String reason) {
        control.assertOnControlThread("handleRegistrationState");
        if (registered) {
            Log.i(TAG, "SIP registered");
            updateNotification("Registered");
            reconnection.onSuccess();

            // Process any pending SMS (may have been queued before registration)
            if (smsHandler != null) {
                Log.d(TAG, "Triggering SMS inbox check after registration");
                smsHandler.processInbox();
            }
        } else {
            Log.w(TAG, "SIP registration failed: " + reason);
            updateNotification("Error: " + reason);
            reconnection.scheduleReconnect();
        }
        publishStatus();
    }

    // ========== Call Handling ==========

    @ControlThread
    private void handleIncomingSipCall(GatewayCall call, int simSlotHint) {
        control.assertOnControlThread("handleIncomingSipCall");
        Log.d(TAG, "Incoming SIP call");
        // Re-check the dispose guard here as well as in GatewayCall: the callback thread
        // saw a live call, but the hop onto this thread is a window in which a teardown
        // (a user stop(), the watchdog, a GSM-side hangup) can dispose it.
        if (call.isDisposed()) {
            Log.d(TAG, "Incoming SIP call was disposed before it could be handled");
            return;
        }
        powerController.wakeScreen();
        callManager.onIncomingSipCall(call, simSlotHint);
        publishStatus();
    }

    /**
     * Every one of these is invoked synchronously by {@code CallManager}, which since GW-10
     * only ever runs on the control thread - so they are control-thread code too.
     */
    private final CallManager.CallListener callListener = new CallManager.CallListener() {
        @Override
        public void onCallStateChanged(CallManager.CallState state) {
            updateNotification("Call: " + state.name());
            publishStatus();
        }

        @Override
        public void onSipCallConnected(GatewayCall call) {
            // Start audio bridge when SIP call media is ready
            audioBridge.startBridge(call);

            // In SIP_FIRST mode, answer GSM call now that SIP is connected
            GatewayInCallService inCallService = GatewayInCallService.getInstance();
            if (inCallService != null) {
                // ONE snapshot. This used to read getCurrentCall() twice and dereference the
                // second read - exactly what GatewayInCallService's class doc forbids,
                // because onCallRemoved nulls the field from main. Posting this callback
                // widens that window, so it is fixed here rather than left for GW-11.
                android.telecom.Call gsmCall = inCallService.getCurrentCall();
                if (gsmCall != null && gsmCall.getState() == android.telecom.Call.STATE_RINGING) {
                    Log.d(TAG, "SIP connected, answering GSM call (SIP_FIRST mode)");
                    inCallService.answerCall();
                }
            }
        }

        @Override
        public void onGsmCallNeeded(String destination, int simSlot) {
            callManager.placeGsmCall(destination, simSlot);
        }

        @Override
        public void onSipCallNeeded(String destination, String callerId, int simSlot) {
            makeSipCallWithCallerId(destination, callerId, simSlot);
        }

        @Override
        public void onCallsTerminated() {
            audioBridge.stopBridge();
            audioBridge.stopAudioStreams();
            updateNotification(accountManager.isRegistered() ? "Registered" : "Not registered");
            publishStatus();
        }

        @Override
        public void onError(String error) {
            Log.e(TAG, "Call error: " + error);
            updateNotification("Error: " + error);
        }
    };

    /**
     * True for the diagnostic SIP call, false for a gateway leg.
     *
     * <p>Reads the call's {@code final} {@link GatewayCall.Owner}, never
     * {@code SipTestCallManager.owns(call)}. The old check compared against a mutable field
     * that a failed diagnostic dial nulls in its catch block, so evaluating it after a post -
     * which is what GW-10 does - would have mis-routed the diagnostic call's DISCONNECTED
     * into {@code CallManager} and run {@code terminateAllCalls()} on a live gateway call.
     * See plan §2.6.
     */
    // Visible for testing.
    static boolean isDiagnostic(GatewayCall call) {
        return call != null && call.getOwner() == GatewayCall.Owner.DIAGNOSTIC;
    }

    // Callback from GatewayCall (SipCallService interface).
    //
    // Runs on a pjsua worker. Two things must NOT be deferred here:
    //  - the gateway/diagnostic demux, which is why it reads the immutable Owner;
    //  - SipTestCallManager.mediaValid, which the call below drops synchronously. It guards
    //    stopTransmit against a conference port PJSIP has already destroyed, and that
    //    failure is a pjmedia assertion, i.e. abort() rather than a catchable exception.
    // SipTestCallManager.onCallState is itself already a "flag inline, handling posted"
    // split - it posts its own teardown onto the main looper, where its internals live.
    @Override
    public void onCallState(GatewayCall call, int state) {
        if (isDiagnostic(call)) {
            SipTestCallManager tc = testCall;
            if (tc != null) {
                tc.onCallState(state);
            }
            return;
        }
        control.post(() -> handleGatewayCallState(call, state));
    }

    @ControlThread
    private void handleGatewayCallState(GatewayCall call, int state) {
        control.assertOnControlThread("handleGatewayCallState");
        // Re-check the dispose guard GatewayCall applied on the callback thread: dispose()
        // can run from a teardown in between. DISCONNECTED is exempt on purpose - GatewayCall
        // sets `disposed` itself on the way in for exactly that state, and dropping it here
        // would leave the state machine holding a dead call forever.
        if (state != pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED && call.isDisposed()) {
            Log.d(TAG, "Dropping queued call state " + state + " for a disposed call");
            return;
        }
        callManager.onSipCallState(call, state);
        publishStatus();
    }

    // Callback from GatewayCall (SipCallService interface). Same split as onCallState.
    @Override
    public void onCallMediaState(GatewayCall call) {
        if (isDiagnostic(call)) {
            SipTestCallManager tc = testCall;
            if (tc != null) {
                tc.onMediaState();
            }
            return;
        }
        control.post(() -> handleGatewayCallMediaState(call));
    }

    @ControlThread
    private void handleGatewayCallMediaState(GatewayCall call) {
        control.assertOnControlThread("handleGatewayCallMediaState");
        // Re-check: wiring a conference port to a call PJSIP has torn down in the meantime is
        // the pjmedia-assertion class of failure, not a catchable one.
        if (call.isDisposed()) {
            Log.d(TAG, "Dropping queued media state for a disposed call");
            return;
        }

        try {
            CallInfo info = call.getInfo();
            if (info.getState() == pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED) {
                audioBridge.startBridge(call);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling media state: " + e.getMessage());
        }
        publishStatus();
    }

    // Callback from GatewayCall (SipCallService interface).
    @Override
    public void onDtmfDigit(GatewayCall call, String digit) {
        // The diagnostic test call has no GSM leg to relay onto.
        if (isDiagnostic(call)) {
            Log.d(TAG, "DTMF on test call, ignored: " + digit);
            return;
        }
        control.post(() -> handleGatewayDtmf(call, digit));
    }

    @ControlThread
    private void handleGatewayDtmf(GatewayCall call, String digit) {
        control.assertOnControlThread("handleGatewayDtmf");
        if (call.isDisposed()) {
            Log.d(TAG, "Dropping queued DTMF '" + digit + "' for a disposed call");
            return;
        }
        callManager.onSipDtmf(digit);
    }

    // ========== GSM Call Handling ==========

    @SuppressWarnings("deprecation")
    private void setupPhoneStateListener() {
        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);

        phoneStateListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String phoneNumber) {
                // Delivered on main; the handling touches the bridge and the state machine.
                control.post(() -> handlePhoneState(state, phoneNumber));
            }
        };

        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
    }

    @ControlThread
    private void handlePhoneState(int state, String phoneNumber) {
        control.assertOnControlThread("handlePhoneState");
        Log.d(TAG, "Phone state: " + state);

        if (state == TelephonyManager.CALL_STATE_OFFHOOK && lastPhoneState != TelephonyManager.CALL_STATE_OFFHOOK) {
            // GSM call active
            audioBridge.startAudioStreams();
            callManager.onGsmCallConnected();
        }

        if (state == TelephonyManager.CALL_STATE_IDLE && lastPhoneState != TelephonyManager.CALL_STATE_IDLE) {
            // GSM call ended. Always stop the audio streams so the mixer routing
            // is torn down even for calls that never reached the BRIDGED state
            // (otherwise the enforce thread would keep the local mic muted).
            audioBridge.stopAudioStreams();
            callManager.onGsmCallEnded();
        }

        lastPhoneState = state;
        publishStatus();
    }

    /** Called from {@code GatewayInCallService} on main. */
    public void onIncomingGsmCall(String callerNumber, int simSlot) {
        control.runOrPost(() -> handleIncomingGsmCall(callerNumber, simSlot));
    }

    @ControlThread
    private void handleIncomingGsmCall(String callerNumber, int simSlot) {
        control.assertOnControlThread("handleIncomingGsmCall");
        powerController.wakeScreen();
        // Ends up in makeSipCallWithCallerId via the listener, which asserts it is on this
        // thread - the dial and the DISCONNECTED it can provoke must share one queue.
        callManager.onIncomingGsmCall(callerNumber, simSlot);
        publishStatus();
    }

    /**
     * Called from {@code GatewayInCallService}'s Telecom callback, on main.
     *
     * <p>The incoming-timeout cancel stays on main deliberately:
     * {@code GatewayInCallService}'s timeout state is main-owned and asserts it. Everything
     * that touches the bridge, the state machine or the mute lease is posted.
     */
    public void onGsmCallStateChanged(android.telecom.Call call, int state) {
        if (state == android.telecom.Call.STATE_ACTIVE) {
            // Cancel incoming timeout - call is now bridged
            GatewayInCallService inCallService = GatewayInCallService.getInstance();
            if (inCallService != null) {
                inCallService.cancelIncomingTimeout();
            }
        }
        control.post(() -> handleGsmCallState(state));
    }

    @ControlThread
    private void handleGsmCallState(int state) {
        control.assertOnControlThread("handleGsmCallState");
        if (state == android.telecom.Call.STATE_ACTIVE) {
            // Start audio immediately (don't wait for mute)
            audioBridge.startAudioStreams();
            callManager.onGsmCallConnected();

            // Mute device speaker/mic in background (takes ~6 seconds).
            // Skipped when the SoC audio profile mutes the mic as part of its
            // routing (e.g. MediaTek disables PCM_2_PB <- ADDA_UL in setupMixer).
            if (!audioBridge.handlesMicMute()) {
                // This call takes out a mute lease. acquire() returns immediately; the
                // ~6 s of tinymix runs on DeviceMuteManager's own thread. If the call ends
                // first, release() cancels it before or during the writes, so the mute can
                // never land after the hangup and strand the mic (AUDIT B1).
                DeviceMuteManager mute = DeviceMuteManager.getInstance(this);
                long lease = mute.newLease();
                long stale = muteLease.getAndSet(lease);
                if (stale != DeviceMuteManager.NO_LEASE) {
                    // No DISCONNECTED arrived for the previous call. Hand its controls back
                    // before this lease reads them, or its originals are lost for good.
                    Log.w(TAG, "GSM call became active while lease " + stale + " was still held");
                    mute.release(stale);
                }
                mute.acquire(lease);
            } else {
                Log.d(TAG, "Mic mute handled by audio profile - skipping DeviceMuteManager");
            }
        } else if (state == android.telecom.Call.STATE_DISCONNECTED) {
            callManager.onGsmCallEnded();
            // Restore device speaker/mic. Driven by the lease rather than by
            // handlesMicMute(), so a profile that changed mid-call cannot strand a mute we
            // took out earlier. Non-blocking: AUDIT H2c, this path must not grow.
            long lease = muteLease.getAndSet(DeviceMuteManager.NO_LEASE);
            if (lease != DeviceMuteManager.NO_LEASE) {
                DeviceMuteManager.getInstance(this).release(lease);
            }
        }
        publishStatus();
    }

    // ========== SMS Handling ==========

    private void initSmsHandler() {
        smsHandler = new SmsHandler(this, new SmsHandler.SmsCallback() {
            @Override
            public void onIncomingSms(String from, String body, long smsId, int simSlot) {
                handleIncomingGsmSms(from, body, smsId, simSlot);
            }

            @Override
            public void onSmsSendStatus(String destination, String status, String errorMessage) {
                Log.d(TAG, "SMS to " + destination + ": " + status);
            }
        });
        smsHandler.start();
    }

    private void handleIncomingGsmSms(String from, String body, long smsId, int simSlot) {
        Log.d(TAG, "handleIncomingGsmSms: smsId=" + smsId + " from=" + from + " SIM" + simSlot + " registered=" + accountManager.isRegistered());

        if (!accountManager.isRegistered()) {
            Log.w(TAG, "Not registered, cannot forward SMS smsId=" + smsId + " - will retry after registration");
            // Remove from processed list so it can be retried after registration
            smsHandler.unprocessSms(smsId);
            return;
        }

        String destination = config.getDestinationForSim(simSlot);
        if (destination.isEmpty()) {
            Log.w(TAG, "No destination for SIM" + simSlot + ", marking smsId=" + smsId + " as read");
            smsHandler.markAsRead(smsId);
            return;
        }

        Log.d(TAG, "handleIncomingGsmSms: Forwarding smsId=" + smsId + " to SIP destination=" + destination);
        // Send as SIP MESSAGE
        sendSipMessage(destination, from, body, smsId, simSlot);
    }

    private void sendSipMessage(String toExt, String gsmSender, String body, long smsId, int simSlot) {
        Log.d(TAG, "sendSipMessage START: smsId=" + smsId + " to=" + toExt + " from=" + gsmSender);
        Buddy buddy = null;
        try {
            GatewayAccount account = accountManager.getAccount();
            if (account == null) {
                Log.e(TAG, "sendSipMessage: No account, cannot send");
                return;
            }

            String server = config.getSipServer();
            int port = config.getSipPort();
            boolean useTls = config.isUseTls();

            // Build URI with correct transport (use sip: with transport=tls, not sips:)
            String toUri = "sip:" + toExt + "@" + server + (useTls ? ";transport=tls" : "");

            // Create temporary Buddy to send MESSAGE
            BuddyConfig buddyConfig = new BuddyConfig();
            buddyConfig.setUri(toUri);

            buddy = new Buddy();
            buddy.create(account, buddyConfig);

            SendInstantMessageParam prm = new SendInstantMessageParam();
            prm.setContent(body);
            prm.setContentType("text/plain");

            // Add X-GSM-CallerID header (like calls) - don't override From URI
            SipTxOption txOpt = prm.getTxOption();
            SipHeaderVector headers = txOpt.getHeaders();

            SipHeader callerHeader = new SipHeader();
            callerHeader.setHName("X-GSM-CallerID");
            callerHeader.setHValue(gsmSender);
            headers.add(callerHeader);

            Log.d(TAG, "sendSipMessage: Calling buddy.sendInstantMessage for smsId=" + smsId);
            buddy.sendInstantMessage(prm);

            Log.i(TAG, "SIP MESSAGE sent to " + toUri + " from " + gsmSender + " (SMS id=" + smsId + ") - now marking as read");
            smsHandler.markAsRead(smsId);
            Log.d(TAG, "sendSipMessage SUCCESS: smsId=" + smsId + " marked as read");

        } catch (Exception e) {
            Log.e(TAG, "sendSipMessage FAILED for smsId=" + smsId + ": " + e.getMessage(), e);
            smsHandler.unprocessSms(smsId);
        } finally {
            // Clean up buddy
            if (buddy != null) {
                try {
                    buddy.delete();
                } catch (Exception ignored) {}
            }
        }
    }

    @ControlThread
    private void handleIncomingSipMessage(String from, String to, String body, int simSlot) {
        control.assertOnControlThread("handleIncomingSipMessage");
        Log.d(TAG, "handleIncomingSipMessage: from=" + from + " to=" + to + " body=\"" + body + "\" SIM" + simSlot);

        // Extract phone number from 'to' URI
        String phoneNumber = extractPhoneNumber(to);
        if (phoneNumber == null || phoneNumber.isEmpty()) {
            Log.w(TAG, "Invalid destination in SIP MESSAGE - to=\"" + to + "\" not a phone number, IGNORING");
            return;
        }

        Log.d(TAG, "handleIncomingSipMessage: Sending GSM SMS to " + phoneNumber);
        // Send via GSM
        if (smsHandler != null) {
            smsHandler.sendSms(phoneNumber, body, simSlot);
        }
    }

    private String extractPhoneNumber(String uri) {
        if (uri == null) return null;
        String cleaned = uri.replaceAll("[<>]", "");
        if (cleaned.startsWith("sips:")) cleaned = cleaned.substring(5);
        else if (cleaned.startsWith("sip:")) cleaned = cleaned.substring(4);
        int at = cleaned.indexOf('@');
        if (at > 0) cleaned = cleaned.substring(0, at);
        if (cleaned.matches("^\\+?[0-9]{10,15}$")) return cleaned;
        return null;
    }

    // ========== Watchdog ==========

    /**
     * The watchdog tick. Its timer still fires on the main looper; the check itself runs
     * here, because it reads {@link #lastPhoneState} and can terminate calls.
     *
     * <p>Doubles as the backstop that keeps {@link #status} from going stale between call
     * events.
     */
    @ControlThread
    private void checkOrphanedCalls() {
        control.assertOnControlThread("checkOrphanedCalls");
        publishStatus();

        if (!callManager.hasActiveCall()) return;
        if (callManager.isInGracePeriod()) return;

        // Check if GSM call exists
        if (lastPhoneState == TelephonyManager.CALL_STATE_IDLE) {
            GatewayCall sipCall = callManager.getCurrentSipCall();
            if (sipCall != null) {
                Log.w(TAG, "Orphaned SIP call detected, terminating");
                callManager.terminateAllCalls();
            }
        }
    }

    // ========== Public API ==========

    /**
     * Dial the SIP leg for an inbound GSM call.
     *
     * <p>Must run on the control thread. The register-before-dial contract in
     * {@code CallManager.placeOutgoingSipCall} now depends on it: PJSIP can still deliver
     * {@code DISCONNECTED} synchronously from inside {@code makeCall}, and
     * {@code onCallState} turns that into {@code control.post(...)}. Dialling from the
     * control thread puts the queued handler strictly behind this dial in one queue.
     * Dialling from anywhere else would let the handler run concurrently with the rest of
     * {@code placeOutgoingSipCall} - two threads racing on {@code currentSipCall} (plan §2.6).
     */
    @ControlThread
    public void makeSipCallWithCallerId(String destination, String callerId, int simSlot) {
        control.assertOnControlThread("makeSipCallWithCallerId");
        try {
            GatewayAccount account = accountManager.getAccount();
            if (account == null) {
                Log.e(TAG, "No SIP account");
                return;
            }

            String server = config.getSipServer();
            boolean useTls = config.isUseTls();

            // Build SIP URI (with TLS transport if enabled)
            String uri = SipUriBuilder.build(destination, server, useTls);

            GatewayCall call = new GatewayCall(this, account);

            CallOpParam prm = new CallOpParam(true);  // true = use default values

            // Add custom SIP headers (Asterisk reads via PJSIP_HEADER())
            SipTxOption txOpt = prm.getTxOption();
            SipHeaderVector headers = new SipHeaderVector();

            // Add CallerID header
            if (callerId != null && !callerId.isEmpty()) {
                SipHeader callerIdHeader = new SipHeader();
                callerIdHeader.setHName("X-GSM-CallerID");
                callerIdHeader.setHValue(callerId);
                headers.add(callerIdHeader);
                Log.d(TAG, "Added X-GSM-CallerID: " + callerId);
            }

            txOpt.setHeaders(headers);

            // The call MUST be registered with CallManager before makeCall() runs: PJSIP can
            // still deliver onCallState(DISCONNECTED) synchronously on this thread (immediate
            // transport failure, or a 403/404 from the PBX), and a handler that cannot find
            // its own call leaves a dead one registered forever. Since GW-10 that handler is
            // queued on THIS thread rather than run inline, which is why the assert at the
            // top of this method is load-bearing: it is what puts the handler behind the dial
            // in a single queue instead of on a second thread. placeOutgoingSipCall owns the
            // ordering and the compare-and-clear on failure - see AUDIT D2 / GW-06.
            if (!callManager.placeOutgoingSipCall(call, c -> c.makeCall(uri, prm))) {
                Log.e(TAG, "SIP call to " + uri + " was not placed");
                return;
            }

            Log.d(TAG, "SIP call to " + uri + " (CallerID: " + callerId + ", SIM: " + simSlot + ")");

        } catch (Exception e) {
            Log.e(TAG, "Failed to make SIP call: " + e.getMessage());
        }
    }

    /**
     * Tear down whatever is up. Called from the Telecom timeout on main and from NanoHTTPD.
     *
     * <p>The {@code synchronized} is now redundant - every writer of the state it protects is
     * the control thread - but GW-10 does not remove synchronisation; plan §3c hands both this
     * monitor and {@code CallManager.hangupSipCall}'s to GW-11 §1, to be deleted together.
     */
    public synchronized void hangupCall() {
        control.runOrPost(() -> {
            control.assertOnControlThread("hangupCall");
            callManager.terminateAllCalls();
            publishStatus();
        });
    }

    // ========== SIP diagnostics ==========

    /**
     * Place a diagnostic SIP call that needs no GSM leg.
     *
     * @param destination extension to dial, empty for the configured default (*43)
     * @param mode        "tone", "loopback" or "bridge"
     * @param durationSec auto-hangup after this many seconds, 0 for the default
     */
    public void startTestCall(String destination, String mode, int durationSec) {
        // The gate below reads CallManager state, so it is taken on the control thread;
        // SipTestCallManager.start() then hops onto main, where its own internals live.
        control.runOrPost(() -> {
            control.assertOnControlThread("startTestCall");
            if (testCall == null) {
                Log.w(TAG, "Test call manager not ready");
                return;
            }
            // Ask for a *live* call, not just a non-null reference: a disposed leftover is
            // not a call in progress, and refusing on one is what made the audio bridge
            // undiagnosable after a failed outgoing call (AUDIT D2).
            if (callManager.hasLiveSipCall()) {
                Log.w(TAG, "Refusing test call: a gateway SIP call is in progress");
                return;
            }
            testCall.start(destination, SipTestCallManager.Mode.parse(mode), durationSec);
        });
    }

    public void stopTestCall() {
        if (testCall != null) {
            testCall.stop();
        }
    }

    public boolean isTestCallActive() {
        return testCall != null && testCall.isActive();
    }

    public String getTestCallReport() {
        return testCall == null ? "" : testCall.getReport();
    }

    public void stop() {
        if (stopRequested) {
            Log.w(TAG, "Stop already requested, ignoring duplicate");
            return;
        }
        stopRequested = true;
        Log.d(TAG, "Stop requested");
        reconnection.setEnabled(false);
        stopSelf();
    }

    /**
     * Reload configuration and re-register SIP account.
     * Use this instead of full service restart when only config changed.
     * Thread-safe, can be called from any thread.
     */
    public void reloadConfig() {
        control.runOrPost(this::doReloadConfig);
    }

    private volatile boolean reloadInProgress = false;

    /**
     * Was a main-thread hop that spawned a {@code ConfigReload} bare thread. Both are gone:
     * the whole sequence is one control-thread task, so it is serialised against every call
     * and registration event instead of racing them.
     *
     * <p>The {@code Thread.sleep}s below are left exactly as they were - they are AUDIT F5,
     * owned by GW-14, which replaces this ad-hoc sequencing with a real pipeline. Blocking
     * the control thread for ~600 ms during a reload is what the control thread is for.
     */
    @ControlThread
    private void doReloadConfig() {
        control.assertOnControlThread("doReloadConfig");
        if (reloadInProgress) {
            Log.w(TAG, "Reload already in progress");
            return;
        }
        reloadInProgress = true;

        Log.i(TAG, "Reloading configuration...");
        updateNotification("Reloading...");

        try {
            // 0. Check if endpoint exists
            if (!endpointManager.isInitialized()) {
                Log.w(TAG, "Endpoint not initialized, cannot reload - restarting service");
                mainHandler.post(() -> {
                    stop();
                    // Service will be restarted by system due to START_STICKY
                });
                return;
            }

            // 1. Make sure this thread is known to PJSIP (idempotent, one-shot)
            if (!control.registerWithPjlib()) {
                Log.e(TAG, "Failed to register thread, aborting reload");
                mainHandler.post(() -> updateNotification("Reload failed: thread registration"));
                return;
            }

            // 2. Stop any active calls. Was a mainHandler.post + sleep because this ran on a
            //    foreign thread; it is now simply in order, on the owning thread.
            callManager.terminateAllCalls();
            Thread.sleep(100);

            // 3. Stop audio streams (but keep port alive)
            audioBridge.stopBridge();
            audioBridge.stopAudioStreams();

            // 4. Delete old account
            accountManager.deleteAccount();

            // 5. Small delay for cleanup
            Thread.sleep(500);

            // 6. Check if endpoint needs recreation (TLS changed)
            if (endpointManager.needsRecreation()) {
                // TLS change requires killing the entire process because:
                // 1. PJSIP endpoint cannot be safely destroyed/recreated at runtime
                // 2. Thread registration is tied to specific Endpoint instance
                // 3. Static endpoint survives service restart but threads don't
                Log.i(TAG, "TLS setting changed, restarting process");
                restartProcess();
                return;
            }

            // 7. Create new account with new settings
            accountManager.createAccount(PjsipSipService.this);

            Log.i(TAG, "Configuration reloaded successfully");
            mainHandler.post(() -> updateNotification("SIP Registered"));

            // 8. Restart MainActivity to refresh UI
            mainHandler.post(() -> {
                try {
                    android.content.Intent intent = new android.content.Intent(PjsipSipService.this, MainActivity.class);
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK |
                                   android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to restart activity: " + e.getMessage());
                }
            });

        } catch (InterruptedException e) {
            Log.w(TAG, "Reload interrupted");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.e(TAG, "Reload failed: " + e.getMessage(), e);
            mainHandler.post(() -> updateNotification("Reload error: " + e.getMessage()));
        } finally {
            reloadInProgress = false;
            publishStatus();
        }
    }

    public void setSipConfig(String server, int port, String user, String password) {
        config.updateSipConfig(server, port, user, password, config.getSipRealm(), config.isUseTls());
    }

    public void setSimDestinations(String sim1, String sim2) {
        config.updateSimDestinations(sim1, sim2);
    }

    // ========== Process Restart ==========

    /**
     * Restart the entire process by killing it and launching MainActivity via root.
     * This is needed when TLS setting changes because PJSIP endpoint cannot be safely
     * destroyed/recreated at runtime.
     */
    private void restartProcess() {
        new Thread(() -> {
            try {
                Log.i(TAG, "Restarting process via root...");

                // Launch MainActivity via root (bypasses background activity restrictions)
                // Flags: -S = force stop before start, -W = wait for launch to complete
                RootHelper.execRoot("am start -S -W -n org.onetwoone.gateway/.MainActivity");

                // Small delay to let activity start
                Thread.sleep(500);

                // Kill this process
                Log.i(TAG, "Killing process for restart");
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(0);

            } catch (Exception e) {
                Log.e(TAG, "Failed to restart: " + e.getMessage());
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(0);
            }
        }, "ProcessRestart").start();
    }

    // ========== Web Server ==========

    public void startWebServer() {
        if (webServer != null) return;
        try {
            webServer = new WebConfigServer(this, GatewayConfig.WEB_SERVER_PORT);
            webServer.start();
            Log.i(TAG, "Web server started on port " + GatewayConfig.WEB_SERVER_PORT);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start web server: " + e.getMessage());
        }
    }

    public void stopWebServer() {
        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }
    }

    public boolean isWebServerRunning() {
        return webServer != null;
    }

    // ========== Status ==========

    /**
     * The last snapshot the control thread published. Safe from any thread, immutable, and
     * the only supported way to read gateway state from outside the control thread.
     *
     * <p>Commands are a different matter: {@link #stop()}, {@link #reloadConfig()},
     * {@link #hangupCall()}, {@link #startTestCall} and friends still need the live service
     * instance. The snapshot replaces <em>reads</em>, not calls.
     */
    public GatewayStatus getStatusSnapshot() {
        return status;
    }

    /**
     * Rebuild {@link #status} from the live managers. The one place they are read for display,
     * and it runs where they are owned.
     */
    @ControlThread
    private void publishStatus() {
        control.assertOnControlThread("publishStatus");
        status = GatewayStatus.capture(isRunning, accountManager, callManager, audioBridge);
    }

    /** The composite the UI shows. Reads the snapshot, never the live managers. */
    public String getStatus() {
        return status.getStatusText();
    }

    public boolean isRunning() {
        assertMainThread("isRunning");
        return isRunning;
    }

    /**
     * The check-then-set on {@link #isRunning} in {@code onStartCommand} is main-only and
     * this is what says so. Same shape as {@code GatewayInCallService.assertMainThread}: log
     * loudly rather than throw, because a violation here is a wrong-thread bug to fix, not a
     * reason to kill a live gateway. Cross-thread <em>reads</em> of the flag are fine and
     * defined - it is volatile - and the UI takes it from the snapshot anyway.
     */
    private static void assertMainThread(String what) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.e(TAG, what + " called off the main thread ("
                    + Thread.currentThread().getName() + ") - isRunning is main-owned");
        }
    }

    /**
     * Snapshot read (plan §2.7). Its consumer is {@code GatewayInCallService}'s SIP retry
     * chain, which polls at 500 ms; registration changes are published within one control-
     * queue hop of the pjsua callback, so the snapshot is not a meaningful lag there.
     */
    public boolean isSipRegistered() {
        return status.isSipRegistered();
    }

    // ========== Notifications ==========

    private void startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Gateway Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        startForeground(NOTIFICATION_ID, buildNotification("Starting..."));
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);

        return builder
            .setContentTitle("GSM-SIP Gateway")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }
}
