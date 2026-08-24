package org.onetwoone.gateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import org.onetwoone.gateway.audio.AudioBridgeManager;
import org.onetwoone.gateway.call.CallManager;
import org.onetwoone.gateway.config.GatewayConfig;
import org.onetwoone.gateway.core.LifecycleCancellation;
import org.onetwoone.gateway.core.ControlThread;
import org.onetwoone.gateway.core.GatewayControlThread;
import org.onetwoone.gateway.core.GatewayStatus;
import org.onetwoone.gateway.diag.SipDiagnostics;
import org.onetwoone.gateway.diag.SipTestCallManager;
import org.onetwoone.gateway.diag.SipUriBuilder;
import org.onetwoone.gateway.power.PowerController;
import org.onetwoone.gateway.sip.Pjsua2Lifetime;
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

    /**
     * Constructed on main by {@link #initSmsHandler()} and used almost entirely from the
     * control thread (GW-21). {@code volatile} so the control thread cannot see a half-built
     * handler — {@code handleRegistrationState} reads this field, and the {@code initializeSip}
     * task it follows is queued <em>before</em> {@code initSmsHandler()} runs.
     */
    private volatile SmsHandler smsHandler;

    /**
     * Written on main ({@link #startWebServer()} / {@link #stopWebServer()}, both reachable from
     * the UI and from {@code GatewayControlReceiver}) and read from the control thread's config
     * reload and from {@link #isWebServerRunning()} - hence {@code volatile} (GW-26 §6). The
     * NanoHTTPD shutdown ordering the brief also asks for is already correct: {@code stop()} is
     * called before the reference is dropped. {@code WebConfigServer} itself belongs to GW-24.
     */
    private volatile WebConfigServer webServer;
    private SipTestCallManager testCall;

    /**
     * The one thread that owns call/audio/SIP lifecycle state. Created in {@link #onCreate()}
     * and quit in {@link #onDestroy()}.
     */
    private GatewayControlThread control;

    // Telephony
    private TelephonyManager telephonyManager;
    private PhoneStateListener phoneStateListener;

    /**
     * The modem's process-wide call state, as last reported by {@link #phoneStateListener}.
     *
     * <p>Since GW-13 this is <b>observational only</b>. It drives nothing: it is compared
     * against the Telecom-tracked leg in {@link #handlePhoneState(int, String)} so a
     * discrepancy between the two sources is visible in the log, and that is all it does.
     */
    @ControlThread
    private int lastPhoneState = TelephonyManager.CALL_STATE_IDLE;

    /**
     * The GSM leg this service is bridging, or {@link GatewayInCallService#NO_GSM_CALL}
     * (GW-13). Adopted when Telecom reports the leg ACTIVE and cleared when its end is
     * processed; confined to the control thread.
     */
    @ControlThread
    private long currentGsmCallId = GatewayInCallService.NO_GSM_CALL;

    /**
     * The last GSM leg whose end was processed. Together with {@link #currentGsmCallId} this
     * makes teardown exactly-once per leg: the {@code DISCONNECTED} callback and the
     * {@code onCallRemoved} backstop both name the same id, so the second one to arrive is a
     * logged no-op rather than a second {@code stopAudioStreams()}.
     */
    @ControlThread
    private long lastEndedGsmCallId = GatewayInCallService.NO_GSM_CALL;

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
     *
     * <p>GW-26 raised this from 1500 ms while making main's total teardown cost <em>smaller</em>:
     * the queue this now drains carries {@link #shutdownSip()} as well, which used to run inline
     * on main afterwards with no bound at all (an un-REGISTER is network I/O). One bounded wait
     * of 3 s replaces a 1.5 s wait plus an unbounded one, so the worst case for
     * {@code onDestroy} goes from "however long the PBX takes to answer" to
     * {@code CONTROL_QUIT_TIMEOUT_MS + MUTE_RESTORE_TIMEOUT_MS} = 5 s, well inside the ANR
     * budget. It is still the only place main waits on the control thread (AUDIT G2, H11).
     */
    private static final long CONTROL_QUIT_TIMEOUT_MS = 3000L;

    /**
     * Cancels in-flight SIP init at destroy. Final and initialised at construction, so it is
     * usable even if {@code onCreate} never completed.
     *
     * <p>The bounded join above cannot be the whole of shutdown: when it expires the control
     * thread is <em>abandoned</em>, and an abandoned thread parked in
     * {@code createEndpointOnMainThread}'s latch resumes the instant {@code onDestroy} returns
     * and creates a SIP account for this dead service. See {@link LifecycleCancellation} for
     * the full argument, and {@link #initializeSip()} for the checks.
     *
     * <p>Cancellation here is terminal, and per service instance: a new service gets a new one
     * of these. That is what stops an init still sitting in the control queue at destroy from
     * being handed a live token when the thread finally dequeues it.
     */
    private final LifecycleCancellation sipInitCancellation = new LifecycleCancellation();

    /**
     * Lifecycle state that must outlive the process, kept in its own preferences file so it
     * cannot collide with the three config files ({@code gateway_prefs}, {@code gsm_audio_config},
     * {@code device_mute_prefs}) that GW-24 is about to reorganise. It is not configuration:
     * nothing in the UI edits it and nothing but {@link #stop()} and {@link #onStartCommand}
     * writes it.
     */
    private static final String PREFS_LIFECYCLE = "gateway_lifecycle";

    /**
     * True while the gateway is down because a human asked for it - the {@code STOP} broadcast,
     * or the UI's stop button. Persisted because the in-memory {@link #stopRequested} is reset
     * by {@code onCreate}, so a system restart used to silently undo an explicit stop.
     *
     * <p>Deliberately narrow: <b>only</b> an explicit user stop latches it. A crash, an OOM kill
     * or a boot must still bring the gateway back, because a gateway that does not come back is
     * worse than the bug this closes.
     */
    private static final String KEY_USER_STOPPED = "user_stopped";

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

    /**
     * Duplicate-{@link #stop()} guard for <em>this</em> service instance. It is not the record
     * of a user stop - {@code onCreate} resets it, so it cannot be - that is
     * {@link #KEY_USER_STOPPED}, which is persisted.
     */
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
        // ...and handed back, so the manager can assert its own thread ownership (GW-15).
        // The two-step wiring is what the circular dependency costs.
        endpointManager.setControlThread(control);

        accountManager = new SipAccountManager(config, endpointManager);

        // Call management. The control thread goes in through the constructor: since GW-11
        // every CallManager method asserts it, so the manager cannot exist without knowing
        // which thread owns it.
        callManager = new CallManager(this, config, control);
        // Construction-time wiring, on main, before the control thread has any work queued -
        // the one CallManager method that does not assert the control thread. See its javadoc.
        callManager.setListener(callListener);

        // Audio bridge. Owned by the control thread since GW-12 - it takes the thread so it
        // can assert that on every state-mutating entry point.
        audioBridge = new AudioBridgeManager(this, config, control);

        // Diagnostic SIP test call (no GSM leg) - see SipTestCallManager. Still main-bound:
        // its internals call pjsua2 from the main looper, which SIP init registers with
        // pjlib. GW-10 changes only who demuxes its callbacks, not where they are handled.
        // It takes the control thread only to hand the audio bridge back to its owner.
        testCall = new SipTestCallManager(this, config, accountManager, audioBridge,
                this, mainHandler, control);

        // Reconnection strategy. GW-15 moved its timer onto the control looper, so the action
        // runs there directly - attemptReconnect() needs that thread anyway, and the hop that
        // used to provide it is now redundant. Its pending/enabled flags are confined to that
        // thread too, which is what closes the duplicate-reconnect race (AUDIT F6).
        reconnection = new ReconnectionStrategy(control, this::attemptReconnect);

        // Watchdog. Same shape: control-looper timer, control-thread check - so the tick is
        // ordered against the call and registration events it inspects.
        watchdog = new ServiceWatchdog(control, this::checkOrphanedCalls);

        // Account listener
        accountManager.setListener(accountListener);
    }

    /**
     * <h3>Restart semantics (GW-26 §5)</h3>
     * The service stays {@code START_STICKY} on every ordinary path: a gateway that does not
     * come back after a crash, an OOM kill or a reboot is worse than the bug this closes. The
     * one suppressed case is a <b>sticky redelivery</b> ({@code intent == null}, i.e. the system
     * restarting us of its own accord) that arrives after a human explicitly stopped the
     * gateway - previously that quietly resurrected it, which made the documented {@code STOP}
     * broadcast unreliable.
     *
     * <p>Any start carrying an intent is somebody asking for the gateway, so it clears the
     * latch: {@code BootReceiver}, the {@code START} broadcast, the UI and
     * {@code GatewayInCallService} all reach us that way.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // The check-then-set on isRunning below is what this asserts; the field's javadoc has
        // claimed the assertion was here since GW-10, and until GW-26 only the getter had it.
        assertMainThread("onStartCommand");
        Log.d(TAG, "Service starting");

        // Unconditional and first: a startForegroundService() we answer with stopSelf() still
        // owes the system a startForeground() within 5 s, or it kills the process.
        startForegroundNotification();

        if (intent == null && isUserStopped(this)) {
            Log.i(TAG, "Sticky restart after an explicit user stop - staying down");
            stopSelf();
            return START_NOT_STICKY;
        }
        setUserStopped(this, false);

        if (!isRunning) {
            isRunning = true;

            // Both own state confined to the control thread since GW-15, so arming them is a
            // post like everything else. Queued ahead of initializeSip, which is the order
            // they ran in before.
            control.post(() -> {
                reconnection.setEnabled(true);
                watchdog.start();
            });

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

    /**
     * <h3>Shape of teardown (GW-26, closes AUDIT G2, H8, H8c, H11)</h3>
     * Four rules, in this order:
     * <ol>
     *   <li><b>Stop new work arriving.</b> {@code isRunning}, {@code instance} and the status
     *       snapshot go first, before anything is torn down.
     *   <li><b>Cancel, do not wait.</b> {@link #sipInitCancellation} invalidates an in-flight
     *       {@link #initializeSip()} immediately, including one parked in
     *       {@code createEndpointOnMainThread}'s latch waiting for a runnable that is queued
     *       behind <em>this method</em>. Without it, the bounded join below abandons that
     *       thread and it goes on to register a SIP account for a service that is gone.
     *   <li><b>One posted task owns everything the control thread owns</b>
     *       ({@link #teardownOnControlThread()}), including {@link #shutdownSip()}, which used
     *       to run on main afterwards. Nothing on main touches pjsua2 any more, so main's
     *       {@code deleteAccount()} can no longer destroy a conference port while the control
     *       thread is inside {@code unwireBridge()}'s liveness check - a pjmedia
     *       {@code abort()}, not an exception (H11).
     *   <li><b>Every step is individually guarded</b>, catching {@link Throwable} rather than
     *       {@link Exception}: with {@code libpjsua2} absent - the static loader above catches
     *       {@code UnsatisfiedLinkError} and lets the service run without it - every pjsua2 call
     *       throws an {@code Error}, and {@code SipAccountManager.deleteAccount()} catches only
     *       {@code Exception}. That {@code Error} used to escape {@code shutdownSip()} and skip
     *       the wake-lock release, the telephony unlisten, the mute restore and
     *       {@code stopForeground} (H8).
     * </ol>
     *
     * <p>{@code super.onDestroy()} is called <b>last</b>, once teardown has finished.
     *
     * <p>The two waits main performs here are both bounded and both deliberate: the
     * {@code quitSafely} join ({@link #CONTROL_QUIT_TIMEOUT_MS}), which is the app's only
     * main-blocks-on-control wait, and {@code awaitRestore}
     * ({@link #MUTE_RESTORE_TIMEOUT_MS}), which waits on {@code DeviceMuteManager}'s own
     * executor and not on the control thread. Do not add a third.
     *
     * <p><b>What still runs after this returns.</b> If the join expires the control thread is
     * abandoned, and {@code quitSafely} drains messages that are already due - so the teardown
     * task will still run, later, on that abandoned thread. That is deliberate: whatever a
     * doomed init managed to create is torn down by the task queued behind it, on the same
     * thread, in order. Main does not wait for it and must not.
     */
    @Override
    public void onDestroy() {
        final long startedAt = SystemClock.uptimeMillis();
        Log.d(TAG, "Service destroying");

        // 1. Stop new work arriving. Before anything else, and before super.onDestroy().
        isRunning = false;
        instance = null;
        status = GatewayStatus.UNAVAILABLE;

        // 2. Cancel in-flight SIP init. Not a request to stop soon - it makes the 30 s
        //    endpoint-creation latch give up within one poll interval, so the teardown task
        //    posted below reaches the head of the control queue in milliseconds instead of
        //    long after the join has expired.
        sipInitCancellation.cancel();

        // 3. Hand the device's mic and earpiece back (AUDIT B1). Queued here rather than
        //    waited on here, so the restore runs while the teardown below does its own work.
        DeviceMuteManager mute = null;
        long lease = muteLease.getAndSet(DeviceMuteManager.NO_LEASE);
        if (lease != DeviceMuteManager.NO_LEASE) {
            mute = DeviceMuteManager.getInstance(this);
            final DeviceMuteManager releasing = mute;
            final long releasedLease = lease;
            teardownStep("mute release", () -> releasing.release(releasedLease));
        }

        // 4. Silence the two things that feed work INTO the control thread from outside it,
        //    before it is retired. Both are called from main here and both stay ahead of the
        //    quit deliberately: an SMS ContentObserver firing, or a NanoHTTPD worker calling
        //    reloadConfig(), after the looper is gone is a post to a dead thread.
        //
        //    GW-21 moved the observer ONTO the control looper, so the unregister could not
        //    stay on main: it would race an onChange already running there. smsHandler.stop()
        //    now latches a flag synchronously (an in-flight scan sees it and stops handing
        //    messages over) and POSTS the unregister to the control thread. quitSafely()
        //    below drains what is already queued, so it still runs - and it runs on the one
        //    thread that dispatches onChange, which is what makes it safe. Keep this order:
        //    posting the unregister after quitSafely() would leak the observer.
        if (smsHandler != null) {
            teardownStep("smsHandler.stop", smsHandler::stop);
        }
        teardownStep("stopWebServer", this::stopWebServer);

        // 5. Everything the control thread owns, as one task on that thread, behind whatever it
        //    is already running. Posted rather than called: quitSafely() drains what is queued.
        if (control != null) {
            control.post(this::teardownOnControlThread);

            // The one main-blocks-on-control wait in the app, bounded on purpose. See
            // GatewayControlThread.quitSafely(long) and this method's javadoc.
            control.quitSafely(CONTROL_QUIT_TIMEOUT_MS);
        } else {
            // onCreate never got as far as constructing it. Nothing was ever queued.
            Log.w(TAG, "No control thread - destroying a service that never finished onCreate");
        }

        // 6. The rest of main's teardown, after the control thread is retired - the wake lock
        //    in particular stays held while that thread does its work. Each step independent, so
        //    one failure cannot skip the rest: powerController.release() must run on every path
        //    or the Gateway::CpuWakeLock leaks (H8).
        if (powerController != null) {
            teardownStep("powerController.release", powerController::release);
        }
        if (telephonyManager != null && phoneStateListener != null) {
            teardownStep("telephony unlisten", () ->
                    telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE));
        }

        // 7. Only now wait on the restore queued in step 3: by this point it has almost always
        //    finished behind the control-thread teardown, so the wait costs nothing. Bounded
        //    either way - a phone left without a microphone is worse than a slow teardown, but
        //    not unboundedly.
        if (mute != null) {
            final DeviceMuteManager restoring = mute;
            teardownStep("awaitRestore", () -> {
                if (!restoring.awaitRestore(MUTE_RESTORE_TIMEOUT_MS)) {
                    Log.w(TAG, "Mute restore still running after "
                            + MUTE_RESTORE_TIMEOUT_MS + " ms");
                }
            });
        }

        teardownStep("stopForeground", () -> stopForeground(true));

        // 8. Last, per GW-26 §4: the framework teardown runs once ours has finished, not before.
        super.onDestroy();
        Log.d(TAG, "Service destroyed in " + (SystemClock.uptimeMillis() - startedAt)
                + " ms on main");
    }

    /**
     * Everything {@code onDestroy} hands to the control thread, in order, as one task.
     *
     * <p>One task rather than three posts so the sequence cannot be interleaved with anything a
     * late pjsua callback might still queue, and so {@link #shutdownSip()} is guaranteed to run
     * <em>after</em> the bridge is unwired, on the same thread. That adjacency is the whole of
     * GW-12's argument, and {@code deleteAccount()} running on main was the one remaining path
     * that could violate it (AUDIT H11).
     */
    @ControlThread
    private void teardownOnControlThread() {
        control.assertOnControlThread("teardownOnControlThread");
        Log.d(TAG, "Control-thread teardown starting");

        // Both timers live on this looper (GW-15), so disarming them here is ordinary ordering
        // rather than a cross-thread poke. quitSafely() drops their still-future messages
        // anyway; this is what keeps `running` and `pending` honest if the service is ever
        // destroyed without quitting the looper.
        if (watchdog != null) {
            teardownStep("watchdog.stop", watchdog::stop);
        }
        if (reconnection != null) {
            teardownStep("reconnection.disable", () -> {
                reconnection.setEnabled(false);
                reconnection.cancel();
            });
        }
        if (audioBridge != null) {
            teardownStep("stopAudioBridge", this::stopAudioBridge);
        }
        if (accountManager != null && endpointManager != null) {
            teardownStep("shutdownSip", this::shutdownSip);
        }

        Log.d(TAG, "Control-thread teardown complete");
    }

    /**
     * Run one teardown step, absorbing whatever it throws.
     *
     * <p>{@link Throwable}, not {@link Exception}, and that is the point rather than
     * defensiveness: the reachable failure here <em>is</em> an {@code Error}. The static
     * initialiser above catches {@code UnsatisfiedLinkError} and lets the service run with no
     * {@code libpjsua2}, after which every pjsua2 call throws {@code UnsatisfiedLinkError} or
     * {@code NoClassDefFoundError} - neither an {@code Exception}, and neither caught by
     * {@code SipAccountManager.deleteAccount()}. Before GW-26 that {@code Error} escaped
     * {@code shutdownSip()} on main and skipped every remaining teardown step, including
     * {@code powerController.release()} (AUDIT H8).
     */
    private static void teardownStep(String what, Runnable step) {
        try {
            step.run();
        } catch (Throwable t) {
            Log.e(TAG, "Teardown step '" + what + "' failed: " + t, t);
        }
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
     *
     * <h3>Cancellation (GW-26, AUDIT H8c)</h3>
     * Before GW-26 this method had no cancellation check anywhere, and the bounded join in
     * {@code onDestroy} was therefore not a shutdown mechanism but an abandonment. The
     * pathological run: the join expires while this task sits in the 30 s endpoint latch; the
     * runnable that latch waits for was queued behind {@code onDestroy} on main, so it runs the
     * instant {@code onDestroy} returns; this method then walks straight on to
     * {@code createAccount(this)} and <b>registers a fresh SIP account for a destroyed
     * service</b>, against the {@code static} Endpoint that main only ran
     * {@code hangupAllCalls()} on. Its callbacks post to a looper that has been quit, so nothing
     * ever tears it down.
     *
     * <p>So a {@link LifecycleCancellation.Token} is taken at entry, checked before every
     * blocking step, and handed to {@code createEndpoint} so the latch itself aborts early. A
     * cancelled init returns silently: no reconnect, no notification, no status publish - the
     * service it was initialising no longer exists.
     *
     * <p>The token is taken at entry rather than at post time and that is safe only because
     * cancellation is <b>terminal</b>: this task is frequently still <em>queued</em> when
     * destroy runs, so {@code begin()} below executes after {@code cancel()}, and a reusable
     * generation would hand it a live token. See {@link LifecycleCancellation}.
     *
     * <p>The check is advisory, and deliberately so. If teardown lands between the last check
     * and {@code createAccount}, the account <em>is</em> created - and then deleted by the
     * teardown task queued behind this one, on this same thread. Cancellation bounds how far a
     * doomed init gets; single-thread ordering is what cleans up the rest.
     */
    @ControlThread
    private void initializeSip() {
        control.assertOnControlThread("initializeSip");
        final LifecycleCancellation.Token cancel = sipInitCancellation.begin();
        try {
            Log.d(TAG, "Initializing SIP...");

            // Create endpoint (hops to main and waits - see the javadoc above). The token goes
            // in so the wait aborts at destroy instead of 30 s later.
            cancel.throwIfCancelled("SIP init");
            endpointManager.createEndpoint(cancel);

            // Hand THIS thread to pjlib now that an endpoint exists to register with. This
            // MUST happen before any other PJSIP call from here. Idempotent and one-shot:
            // GatewayControlThread also tries this at the head of every task, so whichever
            // gets there first is the only registration that ever happens.
            cancel.throwIfCancelled("SIP init");
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

            // Initialize audio bridge. Opens ALSA with retry - blocking, hence a check first.
            cancel.throwIfCancelled("SIP init");
            audioBridge.initialize();

            // Create and register account. THE step cancellation exists for: an account created
            // here for a destroyed service is a live SIP registration nothing owns.
            cancel.throwIfCancelled("SIP init");
            accountManager.createAccount(this);

            Log.d(TAG, "SIP initialized");

        } catch (LifecycleCancellation.CancelledException e) {
            // Deliberately not a failure: no reconnect, no notification, no publishStatus. The
            // service is gone; the teardown task behind us on this queue owns what is left.
            Log.i(TAG, "SIP init abandoned: " + e.getMessage());
            return;
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

    /**
     * Tear the audio bridge down. Posted from {@code onDestroy} so it runs on the thread that
     * owns the wiring; see the comment there.
     */
    @ControlThread
    private void stopAudioBridge() {
        control.assertOnControlThread("stopAudioBridge");
        audioBridge.stopBridge(AudioBridgeManager.ANY_GENERATION);
        audioBridge.stopAudioStreams();
    }

    /**
     * Tear SIP down. <b>Control thread</b> since GW-26 (AUDIT G2): the un-REGISTER inside
     * {@code deleteAccount()} is network I/O that {@code account.delete()} then waits on, and it
     * used to run inline on main from {@code onDestroy}.
     *
     * <p>Moving it here also closes H11. The bounded join can abandon the control thread, and
     * while it was abandoned main went on to {@code deleteAccount()} - destroying the call's
     * conference port, which is exactly what {@code unwireBridge()}'s liveness check on the
     * control thread must not race. The failure mode of that race is a pjmedia {@code abort()},
     * not an exception, so no {@code try/catch} would have caught it. Now both are steps of
     * {@link #teardownOnControlThread()}, in order, on one thread.
     *
     * <p>The audio bridge is <em>not</em> torn down here: {@link #stopAudioBridge()} is the
     * step before this one. The port itself is deliberately never deleted - GW-12 removed the
     * {@code release()} that would have, because it nulled a port the pjmedia RT thread can
     * still be inside.
     */
    @ControlThread
    private void shutdownSip() {
        control.assertOnControlThread("shutdownSip");
        Log.d(TAG, "Shutting down SIP...");

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
     * is now a control-thread task. Since GW-15 {@code ReconnectionStrategy} counts its
     * backoff on the control looper too, so this runs directly as its timer action - no hop,
     * and the backoff state is ordered against the registration events that drive it.
     *
     * <p>{@code hasTransport()} below is the reason the thread matters: it talks to pjsua, and
     * pjsua aborts the process when an unregistered thread calls in. The control thread is the
     * one thread this process registers.
     */
    @ControlThread
    private void attemptReconnect() {
        control.assertOnControlThread("attemptReconnect");
        if (!isRunning) return;

        Log.d(TAG, "Attempting reconnect...");

        try {
            // ONE snapshot. This used to read getAccount() twice and dereference the second
            // read - the same shape as F4, and the account can be deleted between the two
            // (the reload does exactly that, and so does onDestroy from main).
            GatewayAccount account = accountManager.getAccount();

            // Check if endpoint is properly initialized (has transport)
            // CRITICAL: Must check hasTransport() - creating account without transport causes PJSIP crash
            if (!endpointManager.isInitialized() || !endpointManager.hasTransport() || account == null) {
                // Endpoint not ready, transport missing, or account missing - need full init
                Log.d(TAG, "Endpoint/transport/account not ready, performing full initialization");
                initializeSip();
            } else if (!accountManager.isCurrentAccount(account)) {
                // Only reachable if main's onDestroy deleted the account after the read above,
                // i.e. the service is going away. Re-registering it would be pointless and
                // rescheduling a reconnect would fight the teardown.
                Log.w(TAG, "Account was replaced during reconnect, not re-registering");
            } else {
                // Endpoint and transport ready, just re-register
                account.setRegistration(true);
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
            // CallManager never saw it, so nothing else will ever free it (AUDIT H7).
            callManager.buryCall(call, "incoming call disposed before handling");
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
            audioBridge.stopBridge(AudioBridgeManager.ANY_GENERATION);
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

        // Owned native memory - deleted before startBridge's own getInfo() takes another
        // (AUDIT H7). startBridge is deliberately inside the try, not after it: it must not
        // run when getInfo() threw.
        CallInfo info = null;
        try {
            info = call.getInfo();
            int callState = info.getState();
            Pjsua2Lifetime.delete(info);
            info = null;
            if (callState == pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED) {
                audioBridge.startBridge(call);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling media state: " + e.getMessage());
        } finally {
            Pjsua2Lifetime.delete(info);
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

    /**
     * <b>Cross-check only since GW-13.</b> This used to be the second, competing driver of the
     * GSM transitions: it called {@code startAudioStreams()}/{@code onGsmCallConnected()} on
     * OFFHOOK and the stop pair on IDLE, racing the Telecom {@code Call.Callback} path with no
     * ordering guarantee between them (AUDIT D3). It now mutates nothing.
     *
     * <p>It is kept rather than deleted because the modem's own view is the only independent
     * witness to a GSM leg the {@code InCallService} never told us about, and the experiment
     * that would retire that risk - a logging-only build run for a day, per the issue's
     * §Risk - has not been run. A discrepancy here is logged loudly and is a finding, not a
     * repair: repairing from this path is exactly what GW-13 removed.
     */
    @ControlThread
    private void handlePhoneState(int state, String phoneNumber) {
        control.assertOnControlThread("handlePhoneState");
        Log.d(TAG, "Phone state: " + state);

        if (state != lastPhoneState) {
            boolean telecomLegUp = currentGsmCallId != GatewayInCallService.NO_GSM_CALL;
            if (state == TelephonyManager.CALL_STATE_OFFHOOK && !telecomLegUp) {
                Log.w(TAG, "GSM source cross-check: modem is OFFHOOK but Telecom has not"
                        + " reported an active leg - InCallService may have missed a call");
            } else if (state == TelephonyManager.CALL_STATE_IDLE && telecomLegUp) {
                Log.w(TAG, "GSM source cross-check: modem is IDLE but GSM leg "
                        + currentGsmCallId + " is still tracked - a DISCONNECTED may have"
                        + " been missed, and the audio streams are still up");
            }
        }

        lastPhoneState = state;
    }

    /** Called from {@code GatewayInCallService} on main. */
    public void onIncomingGsmCall(String callerNumber, int simSlot, long gsmCallId) {
        control.runOrPost(() -> handleIncomingGsmCall(callerNumber, simSlot, gsmCallId));
    }

    @ControlThread
    private void handleIncomingGsmCall(String callerNumber, int simSlot, long gsmCallId) {
        control.assertOnControlThread("handleIncomingGsmCall");
        powerController.wakeScreen();
        // Ends up in makeSipCallWithCallerId via the listener, which asserts it is on this
        // thread - the dial and the DISCONNECTED it can provoke must share one queue.
        callManager.onIncomingGsmCall(callerNumber, simSlot, gsmCallId);
        publishStatus();
    }

    /**
     * Called from {@code GatewayInCallService}'s Telecom callback, on main.
     *
     * <p><b>Since GW-13 this is the single source of truth for the GSM leg.</b> The
     * {@code PhoneStateListener} path no longer drives anything (see
     * {@link #handlePhoneState(int, String)}); this one does, because it is the only one that
     * carries call identity and therefore the only one that can tell a late event for a
     * finished call apart from a live event for the current one.
     *
     * <p>The incoming-timeout cancel stays on main deliberately:
     * {@code GatewayInCallService}'s timeout state is main-owned and asserts it. Everything
     * that touches the bridge, the state machine or the mute lease is posted.
     *
     * @param gsmCallId the leg's identity, or {@link GatewayInCallService#NO_GSM_CALL} when
     *                  the callback belongs to a call the InCallService no longer tracks
     */
    public void onGsmCallStateChanged(android.telecom.Call call, long gsmCallId, int state) {
        if (state == android.telecom.Call.STATE_ACTIVE) {
            // Cancel incoming timeout - call is now bridged
            GatewayInCallService inCallService = GatewayInCallService.getInstance();
            if (inCallService != null) {
                inCallService.cancelIncomingTimeout();
            }
        }
        control.post(() -> handleGsmCallState(gsmCallId, state));
    }

    /**
     * Telecom reported that the tracked GSM leg was removed. The {@code DISCONNECTED} state
     * callback has normally already run the teardown, in which case this is a logged no-op;
     * it exists as the plan §3d backstop for the case where that callback never arrives.
     */
    public void onGsmCallRemoved(long gsmCallId) {
        control.post(() -> {
            handleGsmCallEnded(gsmCallId, "call removed");
            publishStatus();
        });
    }

    /** Package-private rather than private so {@code GsmCallLifecycleTest} can drive it. */
    @ControlThread
    void handleGsmCallState(long gsmCallId, int state) {
        control.assertOnControlThread("handleGsmCallState");

        if (state == android.telecom.Call.STATE_ACTIVE) {
            handleGsmCallConnected(gsmCallId);
        } else if (state == android.telecom.Call.STATE_DISCONNECTED) {
            handleGsmCallEnded(gsmCallId, "disconnected");
        }
        publishStatus();
    }

    /**
     * Idempotent per leg (GW-13 §4): the second ACTIVE for a leg already up does nothing, so
     * the audio streams are started once and exactly one {@link DeviceMuteManager} lease is
     * taken out per call.
     */
    @ControlThread
    private void handleGsmCallConnected(long gsmCallId) {
        if (gsmCallId == GatewayInCallService.NO_GSM_CALL) {
            Log.w(TAG, "GSM ACTIVE for a call the InCallService is not tracking - ignoring");
            return;
        }
        if (gsmCallId == currentGsmCallId) {
            Log.d(TAG, "GSM call " + gsmCallId + " is already active - ignoring duplicate");
            return;
        }
        if (gsmCallId == lastEndedGsmCallId) {
            Log.w(TAG, "GSM ACTIVE for call " + gsmCallId + " which has already ended - ignoring");
            return;
        }
        if (currentGsmCallId != GatewayInCallService.NO_GSM_CALL) {
            // The previous leg never reported its end. Its teardown is now unreachable by id,
            // so say so rather than let it look like a clean handover.
            Log.w(TAG, "GSM call " + gsmCallId + " became active while " + currentGsmCallId
                    + " was still tracked");
        }
        currentGsmCallId = gsmCallId;

        // Start audio immediately (don't wait for mute)
        audioBridge.startAudioStreams();
        callManager.onGsmCallConnected(gsmCallId);

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
    }

    /**
     * The one GSM teardown path (GW-13 §1, plan §3d).
     *
     * <p>Before GW-13 this work was split: {@code handlePhoneState}'s IDLE branch stopped the
     * audio streams <em>unconditionally</em>, while this Telecom path only told
     * {@code CallManager}. That mattered because GW-11 made {@code terminateAllCalls()} return
     * early when the machine is already {@code IDLE} - so for a leg that never reached
     * {@code BRIDGED} (the SIP leg refused, or the GSM leg hung up during ring, or a modem
     * that goes ACTIVE for a moment after the session was already torn down)
     * {@code onCallsTerminated()} never fires and never stops the streams. Deleting the
     * listener's branch without moving that call here would leave such a leg with no teardown
     * at all, and the symptom is the one GW-08 was written to kill: an orphaned
     * {@code MixerEnforce} thread re-asserting the call routing and the mic mute every 2 s
     * with no PCM and no call - a phone with a dead microphone until reboot.
     *
     * <p>So {@code AudioBridgeManager.stopAudioStreams()} runs here <b>independently of
     * {@code CallManager}'s state</b>. The only thing that can suppress it is identity: an end
     * naming a leg while a <em>different</em> leg is current is the stale-stop scenario
     * (call 1's teardown arriving after call 2 connected) and must not tear down the live
     * call's audio. An end for a leg already torn down is a no-op, which is what keeps it to
     * exactly one {@code Audio streams stopped} per call even with the {@code onCallRemoved}
     * backstop wired in.
     */
    @ControlThread
    private void handleGsmCallEnded(long gsmCallId, String reason) {
        control.assertOnControlThread("handleGsmCallEnded");

        if (gsmCallId != GatewayInCallService.NO_GSM_CALL && gsmCallId == lastEndedGsmCallId) {
            Log.d(TAG, "GSM call " + gsmCallId + " already ended (" + reason + ") - ignoring");
            return;
        }
        if (currentGsmCallId != GatewayInCallService.NO_GSM_CALL
                && gsmCallId != currentGsmCallId) {
            Log.w(TAG, "GSM end for call " + gsmCallId + " (" + reason + ") while "
                    + currentGsmCallId + " is the current leg - ignoring, it would tear down"
                    + " the live call's audio");
            return;
        }

        Log.d(TAG, "GSM call " + gsmCallId + " ended (" + reason + ")");
        lastEndedGsmCallId = gsmCallId;
        currentGsmCallId = GatewayInCallService.NO_GSM_CALL;

        // Unconditional, and before the state machine is told: see the note above.
        audioBridge.stopAudioStreams();
        callManager.onGsmCallEnded(gsmCallId);

        // Restore device speaker/mic. Driven by the lease rather than by
        // handlesMicMute(), so a profile that changed mid-call cannot strand a mute we
        // took out earlier. Non-blocking: AUDIT H2c, this path must not grow.
        long lease = muteLease.getAndSet(DeviceMuteManager.NO_LEASE);
        if (lease != DeviceMuteManager.NO_LEASE) {
            DeviceMuteManager.getInstance(this).release(lease);
        }
    }

    // ========== SMS Handling ==========

    /**
     * Build the SMS handler and start it. Called on main from {@code onStartCommand}; the
     * handler's own {@code start()} hands the work to the control thread, so nothing here
     * blocks (GW-21, AUDIT G1).
     */
    private void initSmsHandler() {
        smsHandler = new SmsHandler(this, control, new SmsHandler.SmsCallback() {
            /**
             * Always the control thread since GW-21 gave the {@code ContentObserver} the
             * control looper, so {@code runOrPost} always dispatches <b>inline</b> here. That
             * is deliberate and load-bearing: {@code processInbox}'s
             * mark-in-flight-then-forward ordering depends on the send being synchronous with
             * respect to the scan. It is kept as {@code runOrPost} rather than a direct call
             * because that is also what hands this thread to pjlib before
             * {@link #sendSipMessage} touches pjsua2.
             */
            @Override
            public void onIncomingSms(String from, String body, long smsId, int simSlot) {
                control.runOrPost(() -> handleIncomingGsmSms(from, body, smsId, simSlot));
            }

            @Override
            public void onSmsSendStatus(String destination, String status, String errorMessage) {
                Log.d(TAG, "SMS to " + destination + ": " + status);
            }
        });
        smsHandler.start();
    }

    @ControlThread
    private void handleIncomingGsmSms(String from, String body, long smsId, int simSlot) {
        control.assertOnControlThread("handleIncomingGsmSms");
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

    /**
     * Forward one GSM SMS to the PBX as a SIP MESSAGE.
     *
     * <p><b>AUDIT F4 lives here.</b> This method captures {@code accountManager.getAccount()}
     * and hands it to {@code buddy.create(account, ...)} - a native call on the account's
     * native peer. It used to run on main while {@code doReloadConfig}, on another thread,
     * could {@code delete()} that peer and null the field in between: a use-after-free on a
     * pjsua2 object, whose failure mode is an abort, not an exception.
     *
     * <p>Closed in two layers. The one that actually closes it: this now runs on the control
     * thread, which is the only thread that deletes an account outside {@code onDestroy}, so
     * the read and the use cannot be separated by a delete. The second layer is the
     * {@code isCurrentAccount} re-check below, which covers the one remaining writer - main's
     * {@code shutdownSip()} during {@code onDestroy}. That is already ordered behind
     * {@code control.quitSafely(...)}, so it only matters if that bounded join times out.
     */
    @ControlThread
    private void sendSipMessage(String toExt, String gsmSender, String body, long smsId, int simSlot) {
        control.assertOnControlThread("sendSipMessage");
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
            // Last look before the native call: on this thread nothing can have moved the
            // account since the read above, but main's onDestroy path can still delete it if
            // the quitSafely() join timed out. Re-check rather than hand pjsua2 a freed peer.
            if (!accountManager.isCurrentAccount(account)) {
                Log.w(TAG, "sendSipMessage: account was replaced mid-send, aborting smsId=" + smsId);
                smsHandler.unprocessSms(smsId);
                return;
            }
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
    //
    // GW-25. The watchdog's own state is declared here rather than with the rest of the
    // fields at the top of the class: everything in this section is written and read on the
    // control thread by checkOrphanedCalls() and nothing else, so keeping it next to the code
    // that owns it is what makes "control-thread-confined, no synchronisation" checkable by
    // reading one screen.

    /**
     * Upper bound on a single call (GW-25 §3). A call still up after this is not a long
     * conversation, it is a transition that was missed - so reaching it is an error log and a
     * bug to investigate, not a normal path. Two hours is the brief's suggestion and is well
     * past any plausible bridged call; the cost of getting it wrong is a hung-up call, the
     * cost of not having it is a GSM leg billing forever.
     */
    static final long MAX_CALL_DURATION_MS = 2L * 60 * 60 * 1000;

    /**
     * How long a Telecom-ACTIVE GSM leg may exist with no live SIP leg before the watchdog
     * reaps it (AUDIT H9, the reverse orphan).
     *
     * <p><b>This number is the whole safety argument for the inbound direction.</b> On
     * {@code MODE_ANSWER_FIRST} the GSM leg is answered <em>first</em>, so Telecom reports
     * {@code STATE_ACTIVE} - and {@link #currentGsmCallId} is adopted - before any SIP leg
     * exists; {@code GatewayInCallService.makeSipCallWithRetry} then retries for up to
     * {@code MAX_SIP_RETRIES(40) x 500 ms = 20 s}, and going ACTIVE has already cancelled the
     * 30 s {@code INCOMING_TIMEOUT_MS}. During that whole window a perfectly healthy inbound
     * call has a live GSM leg, no SIP leg, {@code CallManager} at {@code IDLE} and - because
     * {@code gsmCallPlacedTime} is only ever set by {@code placeGsmCall()}, the SIP-&gt;GSM
     * dial - {@code isInGracePeriod() == false}. A rule without this dwell hangs up every
     * inbound call.
     *
     * <p>45 s is past both of the mechanisms that are supposed to act first (the 20 s retry
     * chain and the 30 s incoming timeout), so the watchdog only ever fires when both of them
     * have already failed.
     */
    static final long GSM_WITHOUT_SIP_MAX_MS = 45_000L;

    /**
     * How long {@code BRIDGED} with open ALSA streams and a frame counter that does not move
     * counts as a dead transmit leg (GW-25 §2). Detection only - see
     * {@link #checkSilentBridge}.
     */
    static final long SILENT_BRIDGE_STALL_MS = 10_000L;

    /** How long {@code TERMINATING} may last before it is logged. See {@link #noteTerminatingDwell}. */
    static final long TERMINATING_DWELL_MAX_MS = 30_000L;

    /**
     * Wall-clock instant the watchdog first saw a call, or 0. The anchor for
     * {@link #MAX_CALL_DURATION_MS}, and the raw value {@code GatewayStatus} carries so the
     * duration can be derived from the clock rather than frozen (plan §2.7, trap 1).
     */
    @ControlThread
    private long callUpSinceWallMs = 0L;

    /** The GSM leg {@link #gsmWithoutSipSinceWallMs} is timing, or {@code NO_GSM_CALL}. */
    @ControlThread
    private long gsmWithoutSipLegId = GatewayInCallService.NO_GSM_CALL;

    /** When that leg was first seen without a live SIP leg. Reset whenever either changes. */
    @ControlThread
    private long gsmWithoutSipSinceWallMs = 0L;

    /** Last {@code GsmAudioPort.getFramesRequested()} the watchdog saw; -1 = nothing yet. */
    @ControlThread
    private long bridgeFramesRequested = -1L;

    /** When that count last changed. */
    @ControlThread
    private long bridgeFramesStalledSinceWallMs = 0L;

    /** Latch: one diagnostic dump per silent-bridge episode, not one per 3 s tick. */
    @ControlThread
    private boolean silentBridgeReported = false;

    @ControlThread
    private long terminatingSinceWallMs = 0L;

    @ControlThread
    private boolean terminatingDwellReported = false;

    /** Calls the watchdog has torn down. Zero is the acceptance number for the soak. */
    @ControlThread
    private long watchdogTerminations = 0L;

    /** Silent-bridge episodes diagnosed. Detection only, so this can climb with no terminations. */
    @ControlThread
    private long silentBridgeEpisodes = 0L;

    @ControlThread
    private String lastWatchdogFinding = "";

    @ControlThread
    private long lastWatchdogFindingAtWallMs = 0L;

    /**
     * The watchdog tick. Since GW-15 the timer fires on the control looper as well, so this is
     * the timer action itself rather than a hop off main - which matters because it can
     * terminate calls, and is now ordered against every other event that touches them.
     *
     * <p>Doubles as the backstop that keeps {@link #status} from going stale between call
     * events, which is why {@link #publishStatus()} is the first statement and sits ahead of
     * every early return: it is the only thing keeping the 1 Hz UI fresh between call events.
     *
     * <h3>The rules, in order (GW-25)</h3>
     * <ol>
     *   <li><b>Max call duration</b> - a fail-safe, so it runs before anything that can return
     *       early.
     *   <li><b>AUDIT D6</b> - the tracked leg is gone from Telecom but its end was never
     *       processed. Runs regardless of {@code CallManager} state, because the leg in
     *       question has already left the state machine. Its trigger is deliberately
     *       <em>Telecom-based</em>: using the {@code PhoneStateListener}'s modem reading would
     *       re-create the second source of truth GW-13 deleted, so that listener stays
     *       observational and this asks Telecom, through the single {@code inCallService}
     *       resolved below.
     *   <li><b>AUDIT H9, the reverse orphan</b> - a Telecom-ACTIVE GSM leg with no SIP leg,
     *       sustained for {@link #GSM_WITHOUT_SIP_MAX_MS}. See that constant for why the dwell
     *       is the entire safety argument.
     *   <li><b>Brief §2b</b> - {@code IDLE} with a registered, undisposed SIP call. The old
     *       tick short-circuited on {@code !hasActiveCall()} and could never see this.
     *   <li><b>H9's original direction</b> - a SIP call with no GSM leg (unchanged).
     * </ol>
     *
     * <h3>Why "ACTIVE" is spelled {@code currentGsmCallId != NO_GSM_CALL}</h3>
     * {@code hasLiveGsmCall()} is <em>not</em> {@code STATE_ACTIVE} - it is also true for
     * RINGING, DIALING, CONNECTING and HOLDING. {@link #currentGsmCallId} is set only by
     * {@link #handleGsmCallConnected(long)}, i.e. only on Telecom {@code STATE_ACTIVE}, and
     * cleared only when the end is processed; it is control-thread-confined and free to read.
     * Treating RINGING as "live GSM" in one rule and as "no GSM" in another is how a watchdog
     * produces contradictory terminations, so both directions read the same two signals.
     *
     * <h3>What must not be terminated</h3>
     * <ul>
     *   <li>The inbound pre-answer window - covered by the dwell above.
     *   <li>The diagnostic test call. {@code SipTestCallManager} in {@code BRIDGE} mode sets
     *       {@code Wiring.active} with {@code CallManager} at {@code IDLE} and <b>no GSM leg
     *       at all</b>, and its {@code GatewayCall} is demuxed by {@code isDiagnostic()} and
     *       never reaches {@code CallManager}. So it trips none of these rules: every one of
     *       them needs either a tracked GSM leg or a call registered with {@code CallManager}.
     *       It also never calls {@code startAudioStreams()}, which is the extra discriminator
     *       the silent-bridge detector uses.
     *   <li>Anything at all while the {@code InCallService} is unbound. {@code getInstance()
     *       == null} reads as "no GSM leg" by design, so a transient unbind used to look
     *       exactly like an orphan. The orphan rules are now skipped for that tick and say so
     *       in the log; the max-duration fail-safe still runs, which is what stops a
     *       <em>permanently</em> unbound service from parking a call forever.
     * </ul>
     */
    @ControlThread
    void checkOrphanedCalls() {  // package-private so WatchdogInvariantsTest can tick it
        control.assertOnControlThread("checkOrphanedCalls");
        publishStatus();

        final long now = System.currentTimeMillis();
        final CallManager.CallState state = callManager.getState();
        final boolean trackedGsmLeg = currentGsmCallId != GatewayInCallService.NO_GSM_CALL;
        final boolean anyCallUp = state != CallManager.CallState.IDLE || trackedGsmLeg;

        if (!anyCallUp) {
            callUpSinceWallMs = 0L;
        } else if (callUpSinceWallMs == 0L) {
            callUpSinceWallMs = now;
        }

        noteTerminatingDwell(state, now);
        checkSilentBridge(state, now);

        // Rule 1: the hard deadline. Ahead of every early return, because a fail-safe that
        // only runs in the healthy shapes is not one.
        if (anyCallUp && now - callUpSinceWallMs >= MAX_CALL_DURATION_MS) {
            String finding = "call up for " + ((now - callUpSinceWallMs) / 1000)
                    + " s, past the " + (MAX_CALL_DURATION_MS / 1000) + " s maximum";
            Log.e(TAG, "INVARIANT: " + finding + " (state=" + state + ", gsmLeg="
                    + currentGsmCallId + ") - a state transition was missed; terminating");
            watchdogTerminate(finding);
            return;
        }

        // ONE resolution of the instance, held in a local for the whole tick. Asking
        // GatewayInCallService.getInstance() twice - once for "is it bound", once for "is the
        // leg live" - would put an unbind between the two reads, and the D6 rule below acts on
        // exactly the answer that unbind produces. That is the false positive this guard
        // exists to prevent, so it must not be reintroduced by the guard itself.
        final GatewayInCallService inCallService = GatewayInCallService.getInstance();
        if (inCallService == null) {
            // Not an orphan, an unbound service. See the note above.
            Log.w(TAG, "No InCallService bound - skipping the orphan rules this tick");
            return;
        }
        final boolean gsmLegLive = inCallService.hasLiveGsmCall();

        // Rule 2: AUDIT D6. Telecom has no leg, but ours was never ended.
        if (trackedGsmLeg && !gsmLegLive) {
            String finding = "GSM leg " + currentGsmCallId + " is tracked but Telecom no"
                    + " longer has it - a DISCONNECTED was missed";
            Log.e(TAG, "INVARIANT (AUDIT D6): " + finding + " - the audio streams and the"
                    + " mute lease are still held; repairing");
            watchdogTerminate(finding);
            return;
        }

        // Rule 3: AUDIT H9, the reverse orphan. isInGracePeriod() is kept as the brief asks,
        // but it is only ever true on the SIP->GSM direction - the dwell is what protects
        // GSM->SIP. See GSM_WITHOUT_SIP_MAX_MS.
        if (trackedGsmLeg && gsmLegLive && !callManager.hasLiveSipCall()
                && !callManager.isInGracePeriod()) {
            if (noteGsmLegWithoutSip(currentGsmCallId, now)) {
                String finding = "GSM leg " + currentGsmCallId + " has been active with no"
                        + " SIP leg for " + ((now - gsmWithoutSipSinceWallMs) / 1000) + " s";
                Log.e(TAG, "INVARIANT (AUDIT H9): " + finding + " - this burns GSM minutes;"
                        + " terminating");
                watchdogTerminate(finding);
                return;
            }
        } else {
            resetGsmWithoutSipWatch();
        }

        if (state == CallManager.CallState.IDLE) {
            // Rule 4: brief §2b. The old tick returned here unconditionally, so a call the
            // state machine had forgotten was invisible to it forever.
            if (callManager.hasLiveSipCall()) {
                String finding = "CallManager is IDLE but still holds a live SIP call";
                Log.e(TAG, "INVARIANT: " + finding + " - reaping it");
                recordFinding(finding);
                watchdogTerminations++;
                // terminateAllCalls() returns early from IDLE and would do nothing at all.
                callManager.hangupSipCall();
                publishStatus();
            }
            return;
        }

        if (callManager.isInGracePeriod()) return;

        // Rule 5: H9's original direction - a SIP call with no GSM leg. Since GW-13 this asks
        // Telecom about the leg it is tracking rather than reading the PhoneStateListener's
        // process-wide lastPhoneState: that field said "some call is up somewhere", never
        // "*this* call is up", and it is now observational only.
        if (!gsmLegLive && callManager.getCurrentSipCall() != null) {
            Log.w(TAG, "Orphaned SIP call detected, terminating");
            watchdogTerminate("SIP call with no GSM leg");
        }
    }

    /**
     * The watchdog's remedy. Which teardown to use is not a free choice (plan §3d):
     * {@code terminateAllCalls()} returns early from {@code IDLE} and will <b>not</b> stop the
     * audio streams for a leg that never left it - which is exactly the shape most of these
     * rules fire on. {@link #handleGsmCallEnded(long, String)} stops the streams
     * unconditionally, releases the {@code DeviceMuteManager} lease, and drives
     * {@code CallManager}, which hangs both legs up when the machine <em>is</em> past
     * {@code IDLE}. So the tracked leg goes through that, and the direct Telecom disconnect
     * afterwards covers the one case neither reaches: a live Telecom leg while the machine
     * never left {@code IDLE}. Every step is idempotent, so the overlap is harmless.
     */
    @ControlThread
    private void watchdogTerminate(String finding) {
        control.assertOnControlThread("watchdogTerminate");
        recordFinding(finding);
        watchdogTerminations++;

        long leg = currentGsmCallId;
        if (leg != GatewayInCallService.NO_GSM_CALL) {
            handleGsmCallEnded(leg, "watchdog: " + finding);
        } else if (callManager.hasActiveCall()) {
            callManager.terminateAllCalls();
        }

        GatewayInCallService inCallService = GatewayInCallService.getInstance();
        if (inCallService != null && inCallService.hasLiveGsmCall()) {
            inCallService.disconnectCall();
        }

        resetWatchdogCallClocks();
        publishStatus();
    }

    /**
     * Bridged, streaming, and pjmedia has stopped asking for frames - the transmit leg is
     * dead even though nothing threw (GW-25 §2, AUDIT D6's audio half).
     *
     * <p><b>Detection only.</b> The brief is explicit that this must not auto-terminate until
     * it has been shown not to false-positive over a week of real calls, and
     * {@code GatewayStatus.WatchdogFindings.getSilentBridgeEpisodes()} is where that evidence
     * accumulates.
     *
     * <p>The counter comes from {@code GsmAudioPort.getFramesRequested()}, which GW-23a added
     * as an {@code AtomicLong} read - safely published, no lock, and nothing that could park
     * the pjmedia RT thread behind {@code close()}'s drain (PHASE-1-PLAN §3b). This file does
     * not touch {@code GsmAudioPort}.
     *
     * <p>Two independent guards keep the diagnostic test call out of here: it leaves
     * {@code CallManager} at {@code IDLE}, and it never calls {@code startAudioStreams()}, so
     * {@code isAudioStreaming()} is false.
     *
     * <p>The dump is latched to once per episode. It emits ~20 logcat lines and creates ~8
     * owned pjsua2 objects (released since GW-22); at a 3 s tick, running it every time would
     * be ~1200 invocations an hour.
     */
    @ControlThread
    private void checkSilentBridge(CallManager.CallState state, long nowWallMs) {
        if (state != CallManager.CallState.BRIDGED || !audioBridge.isAudioStreaming()) {
            resetSilentBridgeWatch();
            return;
        }
        GsmAudioPort port = audioBridge.getGsmAudioPort();
        if (port == null) {
            resetSilentBridgeWatch();
            return;
        }
        if (!noteBridgeFrames(port.getFramesRequested(), nowWallMs)) {
            return;
        }

        silentBridgeEpisodes++;
        String finding = "bridged and streaming, but pjmedia has requested no frame for "
                + ((nowWallMs - bridgeFramesStalledSinceWallMs) / 1000) + " s"
                + " (framesRequested=" + bridgeFramesRequested + ")";
        recordFinding(finding);
        Log.e(TAG, "INVARIANT: " + finding + " - the transmit leg is dead. Detection only,"
                + " the call is left up (GW-25 section 2).");

        GatewayCall sipCall = callManager.getCurrentSipCall();
        if (sipCall != null && !sipCall.isDisposed()) {
            // The conference-wiring half of this dump is the part nothing else logs, and is
            // what says whether local->call(TX to SIP) is false.
            SipDiagnostics.dumpAndLog(sipCall, port, "watchdog: silent bridge");
        }
    }

    /**
     * Frame-counter bookkeeping for {@link #checkSilentBridge}, split out because it is pure
     * and therefore the only part of the detector a JVM test can drive - everything around it
     * ends in pjsua2.
     *
     * @return true exactly once per stall episode, on the first tick past the dwell
     */
    @ControlThread
    boolean noteBridgeFrames(long framesRequested, long nowWallMs) {
        if (framesRequested != bridgeFramesRequested) {
            bridgeFramesRequested = framesRequested;
            bridgeFramesStalledSinceWallMs = nowWallMs;
            silentBridgeReported = false;
            return false;
        }
        if (silentBridgeReported) return false;
        if (nowWallMs - bridgeFramesStalledSinceWallMs < SILENT_BRIDGE_STALL_MS) return false;
        silentBridgeReported = true;
        return true;
    }

    /**
     * Dwell bookkeeping for the reverse orphan. Split out for the same reason as
     * {@link #noteBridgeFrames}, and keyed on the leg id so a new call starts a new clock.
     *
     * @return true once the leg has been ACTIVE with no SIP leg for
     *         {@link #GSM_WITHOUT_SIP_MAX_MS}
     */
    @ControlThread
    boolean noteGsmLegWithoutSip(long gsmCallId, long nowWallMs) {
        if (gsmCallId != gsmWithoutSipLegId) {
            gsmWithoutSipLegId = gsmCallId;
            gsmWithoutSipSinceWallMs = nowWallMs;
            return false;
        }
        return nowWallMs - gsmWithoutSipSinceWallMs >= GSM_WITHOUT_SIP_MAX_MS;
    }

    /**
     * The fourth hard deadline the brief asks for, shipped as a log and nothing more.
     *
     * <p>Said plainly: <b>this is near-unreachable and there is no remedy to build.</b>
     * {@code terminateAllCalls()} walks {@code TERMINATING -> IDLE} synchronously with no
     * suspension point in between, {@code CallManager.transition()} is private, and there is
     * no API to force the machine out of {@code TERMINATING}. So a tick can only observe that
     * state if something is wedged inside {@code terminateAllCalls()} itself - in which case
     * the control thread is stuck and this method is not running either. It exists so that if
     * the state ever does stick, the log says so rather than the symptom being silent.
     */
    @ControlThread
    private void noteTerminatingDwell(CallManager.CallState state, long nowWallMs) {
        if (state != CallManager.CallState.TERMINATING) {
            terminatingSinceWallMs = 0L;
            terminatingDwellReported = false;
            return;
        }
        if (terminatingSinceWallMs == 0L) {
            terminatingSinceWallMs = nowWallMs;
            return;
        }
        if (terminatingDwellReported) return;
        if (nowWallMs - terminatingSinceWallMs < TERMINATING_DWELL_MAX_MS) return;

        terminatingDwellReported = true;
        String finding = "CallManager has been TERMINATING for "
                + ((nowWallMs - terminatingSinceWallMs) / 1000) + " s";
        recordFinding(finding);
        Log.e(TAG, "INVARIANT: " + finding + " - detection only; there is no API to force"
                + " TERMINATING -> IDLE and terminateAllCalls() has no suspension point"
                + " inside that state, so reaching this means the control thread is wedged.");
    }

    @ControlThread
    private void recordFinding(String finding) {
        lastWatchdogFinding = finding;
        lastWatchdogFindingAtWallMs = System.currentTimeMillis();
    }

    @ControlThread
    private void resetSilentBridgeWatch() {
        bridgeFramesRequested = -1L;
        bridgeFramesStalledSinceWallMs = 0L;
        silentBridgeReported = false;
    }

    @ControlThread
    private void resetGsmWithoutSipWatch() {
        gsmWithoutSipLegId = GatewayInCallService.NO_GSM_CALL;
        gsmWithoutSipSinceWallMs = 0L;
    }

    @ControlThread
    private void resetWatchdogCallClocks() {
        callUpSinceWallMs = 0L;
        resetGsmWithoutSipWatch();
        resetSilentBridgeWatch();
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
        CallOpParam prm = null;
        SipHeaderVector headers = null;
        SipHeader callerIdHeader = null;
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

            // Same F4 re-check as sendSipMessage: `new GatewayCall(this, account)` is a native
            // call on the account's peer, and main's onDestroy can still delete it.
            if (!accountManager.isCurrentAccount(account)) {
                Log.w(TAG, "SIP account was replaced before the dial, aborting");
                return;
            }

            GatewayCall call = new GatewayCall(this, account);

            // prm / headers / callerIdHeader are Java-created and therefore ours to delete
            // (AUDIT H7). txOpt is NOT - CallOpParam.getTxOption() hands back a view into prm
            // ((ptr, false)); deleting it would free part of prm.
            prm = new CallOpParam(true);  // true = use default values

            // Add custom SIP headers (Asterisk reads via PJSIP_HEADER())
            SipTxOption txOpt = prm.getTxOption();
            headers = new SipHeaderVector();

            // Add CallerID header
            if (callerId != null && !callerId.isEmpty()) {
                callerIdHeader = new SipHeader();
                callerIdHeader.setHName("X-GSM-CallerID");
                callerIdHeader.setHValue(callerId);
                headers.add(callerIdHeader);  // copies, so the header may be freed after
                Log.d(TAG, "Added X-GSM-CallerID: " + callerId);
            }

            txOpt.setHeaders(headers);  // copy-assigns the vector, so ditto
            Pjsua2Lifetime.delete(callerIdHeader);
            callerIdHeader = null;
            Pjsua2Lifetime.delete(headers);
            headers = null;

            // The call MUST be registered with CallManager before makeCall() runs: PJSIP can
            // still deliver onCallState(DISCONNECTED) synchronously on this thread (immediate
            // transport failure, or a 403/404 from the PBX), and a handler that cannot find
            // its own call leaves a dead one registered forever. Since GW-10 that handler is
            // queued on THIS thread rather than run inline, which is why the assert at the
            // top of this method is load-bearing: it is what puts the handler behind the dial
            // in a single queue instead of on a second thread. placeOutgoingSipCall owns the
            // ordering and the compare-and-clear on failure - see AUDIT D2 / GW-06.
            // `prm` is a reassignable local now (the finally below deletes it), so the lambda
            // captures an effectively-final alias instead.
            final CallOpParam callParam = prm;
            if (!callManager.placeOutgoingSipCall(call, c -> c.makeCall(uri, callParam))) {
                Log.e(TAG, "SIP call to " + uri + " was not placed");
                return;
            }

            Log.d(TAG, "SIP call to " + uri + " (CallerID: " + callerId + ", SIM: " + simSlot + ")");

        } catch (Exception e) {
            Log.e(TAG, "Failed to make SIP call: " + e.getMessage());
        } finally {
            // makeCall() has returned by now (placeOutgoingSipCall runs the lambda inline), and
            // pjsua2 retains nothing from any of these.
            Pjsua2Lifetime.delete(callerIdHeader);
            Pjsua2Lifetime.delete(headers);
            Pjsua2Lifetime.delete(prm);
        }
    }

    /**
     * Tear down whatever is up. Called from the Telecom timeout on main and from NanoHTTPD.
     *
     * <p>No longer {@code synchronized} (GW-11 §4, plan §3c). This monitor and
     * {@code CallManager.hangupSipCall}'s nested inside it were held across a pjsua2 BYE, a
     * Telecom {@code disconnectCall()} and {@code GsmAudioPort.stopCapture()} - ~1.75 s of
     * join plus a ~250 ms native drain - while being entered from main and from pjsua
     * workers. They protected nothing even then: {@code terminateAllCalls()}, the actual
     * caller, never took either. What serialises the teardown now is the control thread, and
     * dropping the monitors removes a deadlock surface rather than a guarantee.
     */
    public void hangupCall() {
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
            //
            // GW-11 §5: this gate and the start it guards are inside one control-thread task,
            // so an incoming gateway call - which reaches CallManager only through this same
            // queue - cannot land between them.
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

    /**
     * An explicit, human-initiated stop: the {@code STOP} broadcast
     * ({@code GatewayControlReceiver}) and the UI's stop button ({@code MainViewModel}).
     *
     * <p>Latched in {@link #KEY_USER_STOPPED} so nothing brings the gateway back behind the
     * operator's back - not a sticky restart, and not {@code GatewayInCallService.onCreate}.
     * The flag is written <em>before</em> {@code stopSelf()}, which matters: {@code onDestroy}
     * nulls {@link #instance} first, so an InCallService that binds during teardown sees no
     * service and would otherwise start one.
     */
    public void stop() {
        stop(true);
    }

    /**
     * @param userRequested true for a human stop, which latches {@link #KEY_USER_STOPPED};
     *                      false for an internal stop that must leave every restart path
     *                      working (the reload's give-up branch)
     */
    private void stop(boolean userRequested) {
        if (stopRequested) {
            Log.w(TAG, "Stop already requested, ignoring duplicate");
            return;
        }
        stopRequested = true;
        Log.d(TAG, "Stop requested (userRequested=" + userRequested + ")");
        if (userRequested) {
            setUserStopped(this, true);
        }
        // Called from main (the broadcast receiver, MainViewModel, the reload path). The
        // reconnect flags belong to the control thread since GW-15, so this is a post; it is
        // ordered ahead of onDestroy's own disarm on the same queue.
        if (control != null) {
            control.post(() -> reconnection.setEnabled(false));
        }
        stopSelf();
    }

    // ========== Persisted stop latch (GW-26 §5) ==========

    /**
     * True when the gateway is down because a human stopped it.
     *
     * <p>Public and static so {@code GatewayInCallService.onCreate} can consult it before
     * starting us: it binds whenever the app is the default dialler, sees a null
     * {@link #instance}, and would otherwise restart the service the operator just stopped -
     * including <em>during</em> our own teardown, since {@code onDestroy} nulls
     * {@code instance} first.
     */
    public static boolean isUserStopped(Context context) {
        return context.getSharedPreferences(PREFS_LIFECYCLE, Context.MODE_PRIVATE)
                .getBoolean(KEY_USER_STOPPED, false);
    }

    /**
     * {@code commit()}, not {@code apply()}: the write must be on disk before {@code stopSelf()}
     * lets the process go, and setting it is a rare, human-paced event.
     *
     * <p>Package-visible rather than private so {@code PjsipSipServiceLifecycleTest} can clear
     * the latch between tests without hard-coding the preference file name.
     */
    static void setUserStopped(Context context, boolean stopped) {
        SharedPreferences prefs =
                context.getSharedPreferences(PREFS_LIFECYCLE, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_USER_STOPPED, false) == stopped) {
            return;
        }
        prefs.edit().putBoolean(KEY_USER_STOPPED, stopped).commit();
        Log.i(TAG, "User-stop latch " + (stopped ? "set" : "cleared"));
    }

    /**
     * Reload configuration and re-register SIP account.
     * Use this instead of full service restart when only config changed.
     * Thread-safe, can be called from any thread.
     *
     * <p>Callable from anywhere, but the reload itself is one task on one queue. Two POSTs
     * 50 ms apart therefore produce two <em>sequential</em> reloads: the second is still
     * sitting in the control queue while the first runs, and the queue will not start it
     * until the first has returned. Nothing interleaves, and nothing is dropped.
     */
    public void reloadConfig() {
        control.runOrPost(this::doReloadConfig);
    }

    /**
     * Re-entrancy guard, not a cross-thread flag. It was {@code volatile} because it was set
     * on main and cleared on the {@code ConfigReload} worker; both are gone, this is written
     * and read only by {@link #doReloadConfig}, and the control thread is the only thread that
     * runs it. Serialising two reloads is the queue's job, not this field's - see
     * {@link #reloadConfig()}. What is left for this flag to catch is a reload triggered from
     * <em>inside</em> a reload, which {@code runOrPost} would run inline.
     */
    private boolean reloadInProgress = false;

    /**
     * Counts reload attempts. Owned by the control thread and published in the snapshot; it
     * is what tells the UI to re-read its config now that the reload no longer relaunches
     * {@code MainActivity}. See {@link GatewayStatus#getConfigGeneration()} and
     * {@code MainViewModel.updateServiceState}.
     */
    private long configGeneration = 0L;

    /**
     * The reload, as one ordered sequence on the thread that owns every manager it touches.
     *
     * <p>Was a main-thread hop that spawned a {@code ConfigReload} bare thread which then
     * synchronised with main by sleeping (AUDIT F5). Both sleeps are gone:
     *
     * <ul>
     *   <li>The 100 ms after {@code terminateAllCalls()} stood in for "the {@code
     *       mainHandler.post} that ran it has finished". There is no post any more - the call
     *       is a plain method call on this thread, and a method call has completed when it
     *       returns. Sequencing by construction.
     *   <li>The 500 ms "small delay for cleanup" after {@code deleteAccount()} is removed, and
     *       <b>nothing replaces it</b>, because none of the three conditions it could have
     *       been guessing at is one it was able to establish:
     *       <ol>
     *         <li><i>"pjsua has finished tearing the account down."</i> Already true before
     *             the sleep started. {@code Account.delete()} is the SWIG destructor;
     *             {@code Account::shutdown()} calls {@code pjsua_acc_del()}, which invalidates
     *             and frees the account slot synchronously under the pjsua lock. When
     *             {@code deleteAccount()} returns there is nothing left for
     *             {@code pjsua_acc_add()} to collide with.
     *         <li><i>"the un-REGISTER got out on the wire."</i> The sleep sat <b>after</b>
     *             {@code delete()}, and {@code delete()} destroys the registration client.
     *             Whatever the un-REGISTER was going to do had to happen before
     *             {@code deleteAccount()} returned; waiting afterwards is provably too late to
     *             affect it. A wait for this would have to sit between
     *             {@code setRegistration(false)} and {@code delete()}, and be a condition (the
     *             un-REGISTER's final response), not a duration.
     *         <li><i>"the old account's {@code onRegState(false)} has been handled."</i> That
     *             handling is {@code control.post(...)} - it lands on <b>this thread's own
     *             queue</b>, and this method is that thread. Sleeping inside a control-thread
     *             task cannot drain the control queue. The handler ran after the whole reload
     *             either way, with or without the sleep.
     *       </ol>
     *       What the reload actually needs is that no other thread observes the half-torn-down
     *       state between {@code deleteAccount()} and {@code createAccount()}. That is what
     *       being consecutive statements on the one thread that owns the account gives it -
     *       and it is stronger than any sleep, because it holds however long each step takes.
     * </ul>
     *
     * <p>The {@code MainActivity} relaunch that used to end this method is gone too: a config
     * save from the web interface must not yank the foreground activity out from under
     * whoever is holding the phone. {@link #configGeneration} replaces it - the UI's 1 Hz
     * snapshot poll sees the counter advance and re-reads its config LiveData in place.
     */
    @ControlThread
    private void doReloadConfig() {
        control.assertOnControlThread("doReloadConfig");
        if (reloadInProgress) {
            Log.w(TAG, "Reload re-entered from inside a reload - ignoring");
            return;
        }
        reloadInProgress = true;

        // Bumped here rather than on success: the caller (WebConfigServer) has already written
        // the new values to SharedPreferences by the time it calls in, so the UI must re-read
        // them even if the re-registration below fails - that is exactly when the operator
        // needs to see what was actually saved.
        configGeneration++;

        Log.i(TAG, "Reloading configuration (generation " + configGeneration + ")...");
        updateNotification("Reloading...");

        try {
            // 0. Check if endpoint exists
            if (!endpointManager.isInitialized()) {
                Log.w(TAG, "Endpoint not initialized, cannot reload - stopping service");
                // stop(false): this is the gateway giving up on itself, not a human stopping
                // it, so it must NOT latch the user-stop flag - every restart path has to keep
                // working. NOTE: the comment that used to sit here claimed START_STICKY would
                // bring the service back. It does not: a service ended by stopSelf() is not
                // restarted, so this branch leaves the gateway down until something starts it.
                // Pre-existing, out of GW-26's scope, filed as AUDIT H14.
                mainHandler.post(() -> stop(false));
                return;
            }

            // 1. Make sure this thread is known to PJSIP (idempotent, one-shot)
            if (!control.registerWithPjlib()) {
                Log.e(TAG, "Failed to register thread, aborting reload");
                updateNotification("Reload failed: thread registration");
                return;
            }

            // 2. Stop any active calls. Was a mainHandler.post + sleep because this ran on a
            //    foreign thread; it is now simply in order, on the owning thread. Idempotent
            //    since GW-11 - a no-op that fires no listener when nothing is up - so step 3
            //    stays unconditional rather than relying on it to tear the bridge down.
            callManager.terminateAllCalls();

            // 3. Unwire whatever is wired and stop the streams, whether or not step 2 did
            //    anything. ANY_GENERATION because this is a blanket teardown: the reload does
            //    not know, and must not care, which call's wiring is up.
            audioBridge.stopBridge(AudioBridgeManager.ANY_GENERATION);
            audioBridge.stopAudioStreams();

            // 4. Delete old account. Everything that dereferences the account runs on this
            //    thread (F4), so no in-flight Buddy or Call can be holding it across this.
            accountManager.deleteAccount();

            // 5. Check if endpoint needs recreation (TLS changed)
            if (endpointManager.needsRecreation()) {
                // TLS change requires killing the entire process because:
                // 1. PJSIP endpoint cannot be safely destroyed/recreated at runtime
                // 2. Thread registration is tied to specific Endpoint instance
                // 3. Static endpoint survives service restart but threads don't
                Log.i(TAG, "TLS setting changed, restarting process");
                restartProcess();
                return;
            }

            // 6. Create new account with new settings
            accountManager.createAccount(PjsipSipService.this);

            Log.i(TAG, "Configuration reloaded successfully");
            updateNotification("SIP Registered");

        } catch (Exception e) {
            Log.e(TAG, "Reload failed: " + e.getMessage(), e);
            updateNotification("Reload error: " + e.getMessage());
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
     *
     * <p><b>Stays its own bare thread, deliberately</b> (plan §2.1). It is called from
     * {@link #doReloadConfig}, i.e. from the control thread, and it ends by killing the
     * process: it must not run <em>on</em> the looper it is about to destroy, and it must not
     * hold that looper busy while it waits for {@code am start} and then kills everything.
     * The {@code Thread.sleep} inside it is not reload sequencing - it is a bare thread giving
     * the relaunched activity a moment before the process dies, on a thread nothing else is
     * waiting on. It is out of GW-14's scope on purpose.
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
        status = GatewayStatus.capture(isRunning, accountManager, callManager, audioBridge,
                configGeneration, GatewayCall.getCallsCreated(), GatewayCall.getCallsDeleted(),
                new GatewayStatus.WatchdogFindings(callUpSinceWallMs, watchdogTerminations,
                        silentBridgeEpisodes, lastWatchdogFinding, lastWatchdogFindingAtWallMs));
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
     * The check-then-set on {@link #isRunning} in {@link #onStartCommand} is main-only and this
     * is what says so - GW-26 added the call there, which until then the field's javadoc had
     * claimed without it existing. Same shape as {@code GatewayInCallService.assertMainThread}:
     * log loudly rather than throw, because a violation here is a wrong-thread bug to fix, not
     * a reason to kill a live gateway. Cross-thread <em>reads</em> of the flag are fine and
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
            .setSmallIcon(R.drawable.ic_notification_gateway)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }
}
