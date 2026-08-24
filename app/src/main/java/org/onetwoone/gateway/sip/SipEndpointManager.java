package org.onetwoone.gateway.sip;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.onetwoone.gateway.config.GatewayConfig;
import org.onetwoone.gateway.core.LifecycleCancellation;
import org.onetwoone.gateway.core.ControlThread;
import org.onetwoone.gateway.core.GatewayControlThread;
import org.onetwoone.gateway.diag.PjsipLogWriter;
import org.onetwoone.gateway.diag.SipDiagnostics;
import org.pjsip.pjsua2.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages PJSIP Endpoint lifecycle.
 *
 * Responsibilities:
 * - Creating and configuring the PJSIP endpoint
 * - Managing transports (UDP/TLS)
 * - Starting and stopping the endpoint
 * - Thread registration for PJSIP
 *
 * The endpoint is kept as a singleton because PJSIP library is global in native code.
 *
 * <h3>Threading (GW-15)</h3>
 * Endpoint lifecycle is owned by the {@link GatewayControlThread}: {@link #createEndpoint()}
 * and {@link #hasTransport()} assert it. That assertion is the whole of the fix for AUDIT F1 -
 * the {@code endpoint == null} check-then-act in {@code createEndpoint} can no longer be
 * entered by two threads at once, so {@code libCreate()} cannot be called twice (which aborts
 * natively). Making the field {@code volatile} in GW-07 only made the reads defined; it never
 * closed the race.
 *
 * <p>The one deliberate exception is {@code new Endpoint()} itself, which still runs on
 * <b>main</b> - see {@link #createEndpointOnMainThread(boolean)} for why, and for why the
 * control thread blocking on main there cannot self-deadlock.
 *
 * <p>{@link #registerThread(String)} stays public for exactly one caller: the control thread
 * handing itself to pjlib. Nothing else may register - see that method's javadoc (AUDIT F2).
 */
public class SipEndpointManager {
    private static final String TAG = "SipEndpoint";

    // Endpoint is static to survive service restart.
    //
    // Written on main (createEndpointInternal, which is forced onto main because pjlib
    // auto-registers only the thread that loaded the native library) and read from every
    // other context that talks to PJSIP: since GW-10 that is mostly the GatewayControl thread
    // (SIP init, reload and reconnect all live there now), plus pjsua workers, main itself
    // and NanoHTTPD. Hence volatile; every consumer snapshots before use (AUDIT H5).
    private static volatile Endpoint endpoint;
    private static volatile boolean endpointUseTls = false;

    private final GatewayConfig config;

    /**
     * The lifecycle owner, used only for assertions. Wired in a second step rather than
     * through the constructor because the dependency is circular: {@code PjsipSipService}
     * has to build this manager first, so that it can hand {@link #registerThread(String)} to
     * the control thread as its pjlib registrar, and only then can it hand the control thread
     * back here.
     *
     * <p>Written once on main in {@code onCreate}, read from the control thread - hence
     * volatile. Null only for a manager constructed standalone (unit tests), where the
     * assertions are skipped rather than failing over a thread model the test never set up.
     */
    private volatile GatewayControlThread control;

    public interface EndpointListener {
        void onEndpointStarted();
        void onEndpointError(String error);
    }

    private EndpointListener listener;

    public SipEndpointManager(GatewayConfig config) {
        this.config = config;
    }

    public void setListener(EndpointListener listener) {
        this.listener = listener;
    }

    /**
     * Hand this manager the control thread so it can assert ownership. Call once, on main,
     * from {@code onCreate}, immediately after constructing the control thread.
     */
    public void setControlThread(GatewayControlThread control) {
        this.control = control;
    }

    /**
     * Assert the control thread when one has been wired; no-op otherwise. Same escalation as
     * {@link GatewayControlThread#assertOnControlThread(String)} - throws in debug, logs in
     * release.
     */
    private void assertOnControlThread(String what) {
        GatewayControlThread ctl = control;
        if (ctl != null) {
            ctl.assertOnControlThread(what);
        }
    }

    /**
     * Get the PJSIP endpoint instance.
     * @return Endpoint or null if not created
     */
    public Endpoint getEndpoint() {
        return endpoint;
    }

    /**
     * Check if endpoint is initialized and ready.
     * @return true if endpoint exists and is usable
     */
    public boolean isInitialized() {
        return endpoint != null;
    }

    /**
     * Check if endpoint exists and is using the expected TLS setting.
     */
    public boolean isEndpointValid() {
        return endpoint != null && endpointUseTls == config.isUseTls();
    }

    /**
     * Check if endpoint has at least one transport created.
     * This is critical before creating accounts - PJSIP will crash if account is created without transport.
     *
     * <p><b>Control thread only.</b> This used to call
     * {@code registerThread(Thread.currentThread().getName())} first, because its callers were
     * short-lived threads pjlib had never seen and an unregistered caller aborts the process
     * rather than throwing (commit {@code 2626f5d}). That was the right emergency fix in the
     * wrong place: pjlib takes each thread descriptor out of the pjsua pool and never gives it
     * back - not when the thread dies, not ever - so registering from inside a <em>query</em>
     * leaked one descriptor per transient caller and grew the pool monotonically for the life
     * of the process (AUDIT F2).
     *
     * <p>Since GW-10/GW-15 every caller is the control thread, transitively:
     * {@code createEndpoint}'s endpoint-reuse path (only caller: {@code initializeSip}),
     * {@code attemptReconnect}, and {@code SipAccountManager.createAccount} (only callers:
     * {@code initializeSip} and {@code doReloadConfig}). All four assert the control thread.
     * That thread hands itself to pjlib exactly once, via
     * {@link GatewayControlThread#registerWithPjlib()}. So the assertion below <em>replaces</em>
     * the defensive registration: a wrong-thread caller becomes a programming error we can
     * see, instead of a leak we cannot.
     */
    @ControlThread
    public boolean hasTransport() {
        assertOnControlThread("hasTransport");
        // Snapshot: destroyEndpoint() can null the field from another thread between the
        // check and the transportEnum() call below.
        Endpoint ep = endpoint;
        if (ep == null) {
            return false;
        }
        // Owned native memory. This used to be a bare delete() inside the try, which the
        // brief cited as the codebase's correct pattern - it was not: if size() threw, the
        // delete was skipped. try/finally is the pattern (AUDIT H7, plan §2.3).
        IntVector transports = null;
        try {
            // Use transportEnum() to get list of transport IDs
            transports = ep.transportEnum();
            return transports != null && transports.size() > 0;
        } catch (Exception e) {
            Log.w(TAG, "Error checking transport: " + e.getMessage());
            return false;
        } finally {
            Pjsua2Lifetime.delete(transports);
        }
    }

    /**
     * Check if TLS setting changed and endpoint needs recreation.
     */
    public boolean needsRecreation() {
        return endpoint != null && endpointUseTls != config.isUseTls();
    }

    /**
     * Exception thrown when TLS setting changed and process restart is required.
     * PJSIP endpoint cannot be safely destroyed and recreated at runtime.
     */
    public static class TlsChangedException extends Exception {
        public TlsChangedException(boolean oldTls, boolean newTls) {
            super("TLS setting changed from " + oldTls + " to " + newTls + ", process restart required");
        }
    }

    /**
     * Create and start the PJSIP endpoint.
     *
     * IMPORTANT: This method will NEVER destroy an existing endpoint.
     * If TLS setting changed, throws TlsChangedException - caller must restart the process.
     * PJSIP cannot safely destroy/recreate endpoint at runtime due to thread registration.
     *
     * IMPORTANT: Endpoint creation MUST happen on the main thread because
     * PJSIP auto-registers the thread that loads the native library (main thread).
     * Calling new Endpoint() from any other thread will crash with
     * "pj_thread_this assertion failed".
     *
     * <p><b>Control thread only</b> (GW-15). The {@code endpoint != null} test below and the
     * {@code new Endpoint()} that follows it are a check-then-act on a static: two threads
     * that both observe {@code null} both reach {@code libCreate()}, and the second one aborts
     * the process natively (AUDIT F1). Serialising this method on the one thread that owns SIP
     * lifecycle makes that impossible by construction - no lock, no double-checked idiom, and
     * nothing for a future caller to get wrong except the thread it calls from, which is
     * asserted.
     *
     * @throws TlsChangedException if TLS setting changed and process restart is required
     * @throws Exception if creation fails
     */
    @ControlThread
    public void createEndpoint() throws Exception {
        createEndpoint(LifecycleCancellation.NEVER);
    }

    /**
     * As {@link #createEndpoint()}, but abandons the main-thread hop early if {@code token} is
     * cancelled (GW-26).
     *
     * <p>This is the plumbing that makes non-blocking service destroy possible. Without it the
     * only bound on the hop is the 30 s latch, and a control thread parked in that latch when
     * {@code onDestroy} gives up on its join goes on to create a SIP account for a service that
     * no longer exists — see {@link LifecycleCancellation}.
     *
     * @param token issued by the caller's {@link LifecycleCancellation}; use
     *              {@link LifecycleCancellation#NEVER} when there is no lifecycle to cancel
     *              against
     * @throws LifecycleCancellation.CancelledException if the token is cancelled while waiting
     */
    @ControlThread
    public void createEndpoint(LifecycleCancellation.Token token) throws Exception {
        assertOnControlThread("createEndpoint");
        boolean useTls = config.isUseTls();

        // Check if endpoint already exists
        if (endpoint != null) {
            if (endpointUseTls != useTls) {
                // TLS changed - cannot safely recreate endpoint, must restart process
                Log.e(TAG, "TLS setting changed (" + endpointUseTls + " -> " + useTls + "), process restart required");
                throw new TlsChangedException(endpointUseTls, useTls);
            } else {
                Log.d(TAG, "Reusing existing endpoint");

                // This branch used to call registerThread(Thread.currentThread().getName())
                // here, for the same reason hasTransport() did. The endpoint is static and
                // outlives the service, so the reuse path runs on the GatewayControl thread of
                // the *new* service instance, and initializeSip only calls registerWithPjlib()
                // *after* createEndpoint() returns - so hasTransport() below would reach pjsua
                // from a thread pjlib has never seen, and abort the process rather than throw.
                //
                // It is no longer needed, and the argument is specific to this branch: we only
                // reach it with endpoint != null. The control thread asks its registrar (this
                // manager's registerThread) at the head of *every* task it dispatches and stops
                // asking only once an attempt succeeds; the only thing that makes an attempt
                // fail is a null endpoint - which, on this branch, it was not, for the whole
                // time this task has been running. So the control thread was already known to
                // pjlib before this method was entered. The fresh-creation path below has no
                // such guarantee and needs none: with no endpoint there is no pjsua to call.
                // See GatewayControlThread's "pjlib registration" javadoc.

                // CRITICAL: Check if transport exists when reusing endpoint
                // If transport is missing (e.g. after previous creation failure), recreate it
                if (!hasTransport()) {
                    Log.w(TAG, "Endpoint exists but has no transport - recreating transport");
                    createTransport(useTls);
                    Log.d(TAG, "Transport recreated successfully");
                }

                return;
            }
        }

        Log.d(TAG, "Creating new PJSIP endpoint (TLS=" + useTls + ")");

        // Create endpoint on main thread to avoid PJSIP thread registration crash
        // The native library is loaded on main thread, so that's the only thread
        // that's auto-registered with PJSIP.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.d(TAG, "Not on main thread, delegating endpoint creation");
            createEndpointOnMainThread(useTls, token);
            return;
        }

        createEndpointInternal(useTls);
    }

    /**
     * Create endpoint on main thread using Handler and wait for completion.
     *
     * <h3>Why this await cannot self-deadlock (plan §2.4)</h3>
     * Since GW-10 the caller is the {@code GatewayControl} thread, so this is the control
     * thread blocking on main for up to 30 s. That is allowed, and it is safe in exactly one
     * direction:
     * <ul>
     *   <li><b>Control may block on main. Main must NEVER block waiting on the control
     *       thread.</b> Every main→control hand-off in the app is a fire-and-forget
     *       {@code post}; no main-thread code takes a latch, a {@code Future.get} or a
     *       {@code join} on a control-thread result. The single exception is the
     *       <em>bounded</em> {@code quitSafely} join at service destroy, which times out
     *       instead of waiting forever.
     *   <li>The runnable being awaited needs nothing from the control thread, so the thread
     *       waiting for it cannot be the thread blocking it.
     * </ul>
     * Add a main-blocks-on-control wait anywhere and this becomes a real deadlock. Before
     * GW-10 the caller was the {@code SipInit} bare thread and the safety here was
     * accidental; it is now a phase-wide invariant.
     *
     * <p>GW-15 turns "the caller is the control thread" from an expectation into a checked
     * fact: {@link #createEndpoint()} asserts it. So the two premises above are now both
     * enforced rather than assumed - the waiter is known, and the thing it waits for is a
     * plain main-looper runnable that never calls back into the control thread. Keep the hop
     * itself: it exists because pjsua auto-registers only the thread that loaded the native
     * library, and constructing the {@code Endpoint} anywhere else fails a
     * {@code pj_thread_this} assertion, i.e. aborts.
     *
     * <h3>Cancellation (GW-26)</h3>
     * The 30 s bound is a backstop, not a shutdown mechanism: a control thread parked here when
     * the service is destroyed must give up <em>at destroy</em>, not 30 s later, because by then
     * the service it is initialising is gone and the account it would go on to create has
     * nothing left to tear it down. So the wait polls {@code token} rather than blocking on the
     * latch in one shot — see {@link #awaitCancellably}.
     */
    private void createEndpointOnMainThread(boolean useTls, LifecycleCancellation.Token token)
            throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(() -> {
            // Throwable, not Exception: with libpjsua2 missing (the static loader in
            // PjsipSipService catches UnsatisfiedLinkError and lets the service run without it)
            // every pjsua2 call in createEndpointInternal throws an Error. Catching only
            // Exception let that Error escape onto the main looper and kill the process, while
            // the finally below still counted the latch down - so the control thread carried on
            // as if the endpoint had been created.
            try {
                createEndpointInternal(useTls);
            } catch (Throwable t) {
                errorRef.set(t);
            } finally {
                latch.countDown();
            }
        });

        awaitCancellably(latch, token, ENDPOINT_CREATE_TIMEOUT_MS, "endpoint creation");

        // Re-throw whatever main hit. An Error is wrapped rather than rethrown: the caller
        // (PjsipSipService.initializeSip) handles a failed init by scheduling a reconnect, and
        // an Error thrown out of a control-thread task would instead kill the control thread.
        Throwable error = errorRef.get();
        if (error instanceof Exception) {
            throw (Exception) error;
        }
        if (error != null) {
            throw new Exception("Endpoint creation failed: " + error, error);
        }
    }

    /** How long {@link #createEndpointOnMainThread} waits for main before giving up. */
    static final long ENDPOINT_CREATE_TIMEOUT_MS = 30_000L;

    /**
     * How often the wait wakes to re-check cancellation. Small enough that destroy is not
     * perceptibly delayed, large enough that a 30 s wait costs at most a few hundred wake-ups.
     */
    static final long CANCEL_POLL_MS = 50L;

    /**
     * Wait for {@code latch}, giving up early if {@code token} is cancelled.
     *
     * <p>Package-visible and static so the cancellation semantics can be unit-tested without a
     * pjsua2 endpoint: this is the whole of GW-26's "destroy cancels in-flight init rather than
     * waiting for it" on the SIP side.
     *
     * @throws LifecycleCancellation.CancelledException if cancelled before the latch fires
     * @throws Exception if {@code timeoutMs} elapses first
     */
    static void awaitCancellably(CountDownLatch latch, LifecycleCancellation.Token token,
                                 long timeoutMs, String what) throws Exception {
        final long deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;
        while (true) {
            // Checked before the first wait as well as between waits: destroy may already have
            // happened while this task sat in the control queue.
            token.throwIfCancelled(what);

            long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) {
                throw new Exception("Timeout waiting for " + what);
            }
            if (latch.await(Math.min(CANCEL_POLL_MS, remainingMs), TimeUnit.MILLISECONDS)) {
                return;
            }
        }
    }

    /**
     * Internal method to create endpoint. MUST be called on main thread.
     */
    private void createEndpointInternal(boolean useTls) throws Exception {
        Log.d(TAG, "Creating endpoint (thread: " + Thread.currentThread().getName() + ")");

        // Create endpoint
        endpoint = new Endpoint();
        endpoint.libCreate();

        // Configure endpoint
        EpConfig epConfig = new EpConfig();

        // UA config
        UaConfig uaConfig = epConfig.getUaConfig();
        uaConfig.setUserAgent("GatewayPJSIP/1.0");
        uaConfig.setMaxCalls(4);

        // Log config. Without an explicit writer PJSIP logs to stdout, which Android
        // discards - route it to logcat (and a ring buffer the diagnostics can read) so
        // SIP messages and SDP are actually visible. msgLogging=1 includes full bodies.
        LogConfig logConfig = epConfig.getLogConfig();
        int logLevel = config.isVerboseSipLog() ? 5 : 4;
        logConfig.setLevel(logLevel);
        logConfig.setConsoleLevel(logLevel);
        logConfig.setMsgLogging(1);
        logConfig.setWriter(PjsipLogWriter.get());

        // Media config
        MediaConfig mediaConfig = epConfig.getMedConfig();
        mediaConfig.setClockRate(8000);
        mediaConfig.setSndClockRate(8000);
        mediaConfig.setChannelCount(1);
        mediaConfig.setEcOptions(0); // Disable echo cancellation
        mediaConfig.setEcTailLen(0);
        mediaConfig.setNoVad(true);

        // Initialize endpoint
        endpoint.libInit(epConfig);

        // Create transport
        createTransport(useTls);

        // Start endpoint
        endpoint.libStart();
        endpointUseTls = useTls;

        Log.d(TAG, "Endpoint started");

        // Disable video codecs
        disableVideoCodecs();

        // Log the audio codec inventory once: tells us at a glance whether the codecs
        // the PBX allows are even compiled into this PJSIP build.
        Log.i(TAG, SipDiagnostics.dumpCodecs());

        // Set null audio device (we use custom audio bridging)
        setNullAudioDevice();

        if (listener != null) {
            listener.onEndpointStarted();
        }
    }

    /**
     * Create SIP transport (UDP or TLS).
     */
    private void createTransport(boolean useTls) throws Exception {
        // Snapshot: also reached from the endpoint-reuse path on the control thread, not just
        // from createEndpointInternal on main.
        Endpoint ep = endpoint;
        TransportConfig transportConfig = new TransportConfig();

        if (useTls) {
            // TLS transport
            transportConfig.setPort(5061);

            // TLS settings - use PJSIP's TlsConfig
            TlsConfig tlsConfig = transportConfig.getTlsConfig();
            // Accept any certificate (for self-signed certs)
            // In production, should verify certificates
            tlsConfig.setVerifyServer(false);
            tlsConfig.setVerifyClient(false);

            try {
                ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, transportConfig);
            } catch (Exception e) {
                // Port 5061 may be taken (e.g. by the phone's IMS/VoLTE stack) - fall back to ephemeral port.
                // The PBX learns our contact from REGISTER, so the local port doesn't matter.
                Log.w(TAG, "TLS port 5061 unavailable, falling back to ephemeral port: " + e.getMessage());
                transportConfig.setPort(0);
                ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, transportConfig);
            }
            Log.d(TAG, "Created TLS transport");
        } else {
            // UDP transport
            transportConfig.setPort(5060);
            try {
                ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, transportConfig);
            } catch (Exception e) {
                // Port 5060 may be taken (e.g. by the phone's IMS/VoLTE stack) - fall back to ephemeral port.
                // The PBX learns our contact from REGISTER, so the local port doesn't matter.
                Log.w(TAG, "UDP port 5060 unavailable, falling back to ephemeral port: " + e.getMessage());
                transportConfig.setPort(0);
                ep.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, transportConfig);
            }
            Log.d(TAG, "Created UDP transport");
        }
    }

    /**
     * Disable video codecs to save resources.
     */
    private void disableVideoCodecs() {
        // Owned native memory; the CodecInfo elements it hands out are not (AUDIT H7).
        CodecInfoVector2 videoCodecs = null;
        try {
            // Snapshot: one object for the enum and every setPriority below it.
            Endpoint ep = endpoint;
            videoCodecs = ep.videoCodecEnum2();
            for (int i = 0; i < videoCodecs.size(); i++) {
                CodecInfo codec = videoCodecs.get(i);
                ep.videoCodecSetPriority(codec.getCodecId(), (short) 0);
            }
            Log.d(TAG, "Video codecs disabled");
        } catch (Exception e) {
            Log.w(TAG, "Error disabling video codecs: " + e.getMessage());
        } finally {
            Pjsua2Lifetime.delete(videoCodecs);
        }
    }

    /**
     * Set null audio device to free hardware PCM.
     * We use custom audio bridging via GsmAudioPort.
     */
    private void setNullAudioDevice() {
        try {
            endpoint.audDevManager().setNullDev();
            Log.d(TAG, "Null audio device set");
        } catch (Exception e) {
            Log.w(TAG, "Error setting null audio device: " + e.getMessage());
        }
    }

    /**
     * Hand a thread to pjlib, so that it may call PJSIP functions.
     *
     * <p><b>Call this at most once per thread, and never for a short-lived one.</b> pjlib
     * allocates the thread descriptor out of the pjsua pool and never frees it - not on a
     * repeat registration, not when the thread dies. Registering a NanoHTTPD worker, a
     * reconnect runnable or any other transient thread therefore grows that pool monotonically
     * for the life of the process and leaves a dangling descriptor behind (AUDIT F2).
     *
     * <p>Exactly one legitimate caller is left: {@link GatewayControlThread}, which uses this
     * as its {@code PjlibRegistrar} and stops asking as soon as one attempt succeeds. Main is
     * auto-registered by pjsua when it loads the native library; SIP init re-registers it once
     * by name, so that the log says which thread it is.
     *
     * @param threadName Name for the thread
     * @return true if the thread is known to pjlib
     */
    public boolean registerThread(String threadName) {
        // Snapshot: this runs on threads pjlib has never seen, concurrently with whoever
        // creates or destroys the endpoint - the null check and the two calls below must all
        // see the same object.
        Endpoint ep = endpoint;
        if (ep == null) {
            Log.w(TAG, "Cannot register thread '" + threadName + "', endpoint is null");
            return false;
        }

        try {
            // libIsThreadRegistered() is a thread-local lookup, not a pj_thread_this()
            // call, so it is safe on a thread pjlib has never seen - which is the whole
            // point of asking. Skipping the re-register also stops each pass from
            // leaking another thread descriptor into pjsua's pool.
            if (ep.libIsThreadRegistered()) {
                return true;
            }
            ep.libRegisterThread(threadName);
            Log.d(TAG, "Thread registered: " + threadName);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to register thread '" + threadName + "': " + e.getMessage());
            return false;
        }
    }

    /**
     * Shutdown the endpoint (but keep it alive for reuse).
     * Closes accounts and transports but doesn't destroy the endpoint.
     */
    public void shutdown() {
        // Snapshot: called from main (onDestroy) while the control thread may still be
        // replacing the endpoint.
        Endpoint ep = endpoint;
        if (ep == null) {
            return;
        }

        Log.d(TAG, "Shutting down endpoint (keeping alive for reuse)");

        try {
            // Hangup all calls
            ep.hangupAllCalls();
        } catch (Exception e) {
            Log.w(TAG, "Error hanging up calls: " + e.getMessage());
        }
    }

    /**
     * Completely destroy the endpoint.
     * Should only be called when app is terminating or TLS setting changed.
     */
    public void destroyEndpoint() {
        // Snapshot: the checked object must be the destroyed one.
        Endpoint ep = endpoint;
        if (ep == null) {
            return;
        }

        Log.d(TAG, "Destroying endpoint");

        try {
            ep.libDestroy();
        } catch (Exception e) {
            Log.e(TAG, "Error destroying endpoint: " + e.getMessage());
        }

        endpoint = null;
        endpointUseTls = false;
    }

    /**
     * Check if endpoint is created and running.
     */
    public boolean isRunning() {
        return endpoint != null;
    }

    /**
     * Get endpoint state for debugging.
     */
    public String getStateInfo() {
        if (endpoint == null) {
            return "Endpoint: not created";
        }
        return String.format("Endpoint: running, TLS=%b", endpointUseTls);
    }
}
