package org.onetwoone.gateway.sip;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.onetwoone.gateway.config.GatewayConfig;
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
 */
public class SipEndpointManager {
    private static final String TAG = "SipEndpoint";

    // Endpoint is static to survive service restart
    private static Endpoint endpoint;
    private static boolean endpointUseTls = false;

    private final GatewayConfig config;

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
     */
    public boolean hasTransport() {
        if (endpoint == null) {
            return false;
        }
        try {
            // Use transportEnum() to get list of transport IDs
            IntVector transports = endpoint.transportEnum();
            boolean hasTransports = transports != null && transports.size() > 0;
            if (transports != null) {
                transports.delete();
            }
            return hasTransports;
        } catch (Exception e) {
            Log.w(TAG, "Error checking transport: " + e.getMessage());
            return false;
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
     * @throws TlsChangedException if TLS setting changed and process restart is required
     * @throws Exception if creation fails
     */
    public void createEndpoint() throws Exception {
        boolean useTls = config.isUseTls();

        // Check if endpoint already exists
        if (endpoint != null) {
            if (endpointUseTls != useTls) {
                // TLS changed - cannot safely recreate endpoint, must restart process
                Log.e(TAG, "TLS setting changed (" + endpointUseTls + " -> " + useTls + "), process restart required");
                throw new TlsChangedException(endpointUseTls, useTls);
            } else {
                Log.d(TAG, "Reusing existing endpoint");

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
            createEndpointOnMainThread(useTls);
            return;
        }

        createEndpointInternal(useTls);
    }

    /**
     * Create endpoint on main thread using Handler and wait for completion.
     */
    private void createEndpointOnMainThread(boolean useTls) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> errorRef = new AtomicReference<>();

        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(() -> {
            try {
                createEndpointInternal(useTls);
            } catch (Exception e) {
                errorRef.set(e);
            } finally {
                latch.countDown();
            }
        });

        // Wait for completion (max 30 seconds)
        if (!latch.await(30, TimeUnit.SECONDS)) {
            throw new Exception("Timeout waiting for endpoint creation");
        }

        // Re-throw any exception from main thread
        Exception error = errorRef.get();
        if (error != null) {
            throw error;
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

        // Log config
        LogConfig logConfig = epConfig.getLogConfig();
        logConfig.setLevel(4);
        logConfig.setConsoleLevel(4);

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
                endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, transportConfig);
            } catch (Exception e) {
                // Port 5061 may be taken (e.g. by the phone's IMS/VoLTE stack) - fall back to ephemeral port.
                // The PBX learns our contact from REGISTER, so the local port doesn't matter.
                Log.w(TAG, "TLS port 5061 unavailable, falling back to ephemeral port: " + e.getMessage());
                transportConfig.setPort(0);
                endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, transportConfig);
            }
            Log.d(TAG, "Created TLS transport");
        } else {
            // UDP transport
            transportConfig.setPort(5060);
            try {
                endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, transportConfig);
            } catch (Exception e) {
                // Port 5060 may be taken (e.g. by the phone's IMS/VoLTE stack) - fall back to ephemeral port.
                // The PBX learns our contact from REGISTER, so the local port doesn't matter.
                Log.w(TAG, "UDP port 5060 unavailable, falling back to ephemeral port: " + e.getMessage());
                transportConfig.setPort(0);
                endpoint.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, transportConfig);
            }
            Log.d(TAG, "Created UDP transport");
        }
    }

    /**
     * Disable video codecs to save resources.
     */
    private void disableVideoCodecs() {
        try {
            CodecInfoVector2 videoCodecs = endpoint.videoCodecEnum2();
            for (int i = 0; i < videoCodecs.size(); i++) {
                CodecInfo codec = videoCodecs.get(i);
                endpoint.videoCodecSetPriority(codec.getCodecId(), (short) 0);
            }
            Log.d(TAG, "Video codecs disabled");
        } catch (Exception e) {
            Log.w(TAG, "Error disabling video codecs: " + e.getMessage());
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
     * Register a thread with PJSIP.
     * Required for any thread that calls PJSIP functions.
     *
     * @param threadName Name for the thread
     */
    /**
     * Register a thread with PJSIP.
     * Required before calling any PJSIP functions from external threads.
     *
     * @param threadName Name for the thread
     * @return true if registration succeeded, false otherwise
     */
    public boolean registerThread(String threadName) {
        if (endpoint == null) {
            Log.w(TAG, "Cannot register thread '" + threadName + "', endpoint is null");
            return false;
        }

        try {
            endpoint.libRegisterThread(threadName);
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
        if (endpoint == null) {
            return;
        }

        Log.d(TAG, "Shutting down endpoint (keeping alive for reuse)");

        try {
            // Hangup all calls
            endpoint.hangupAllCalls();
        } catch (Exception e) {
            Log.w(TAG, "Error hanging up calls: " + e.getMessage());
        }
    }

    /**
     * Completely destroy the endpoint.
     * Should only be called when app is terminating or TLS setting changed.
     */
    public void destroyEndpoint() {
        if (endpoint == null) {
            return;
        }

        Log.d(TAG, "Destroying endpoint");

        try {
            endpoint.libDestroy();
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
