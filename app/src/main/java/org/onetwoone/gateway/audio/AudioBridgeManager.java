package org.onetwoone.gateway.audio;

import android.content.Context;
import android.util.Log;

import org.onetwoone.gateway.GatewayCall;
import org.onetwoone.gateway.GsmAudioPort;
import org.onetwoone.gateway.config.GatewayConfig;
import org.onetwoone.gateway.diag.SipDiagnostics;
import org.pjsip.pjsua2.*;

/**
 * Manages audio bridging between SIP and GSM calls.
 *
 * Uses GsmAudioPort to:
 * - Capture audio from GSM voice call (VOC_REC) and send to SIP
 * - Receive audio from SIP and play to GSM (Incall_Music)
 *
 * This is the core of the gateway's audio functionality.
 */
public class AudioBridgeManager {
    private static final String TAG = "AudioBridge";

    private final Context context;
    private final GatewayConfig config;

    // Static to survive service restart (like Endpoint). Since GW-10 initialize(), the
    // start/stop pairs and the bridge wiring all run on the GatewayControl thread; main (the
    // diagnostic test call) and NanoHTTPD still read it - hence volatile, and snapshotted at
    // every consumer.
    private static volatile GsmAudioPort gsmAudioPort;

    // start/stopBridge() run on the GatewayControl thread; the diagnostic call reaches
    // startBridge/stopBridge from main, and isBridgeActive()/getStatusString() are read from
    // NanoHTTPD and by the status snapshot. volatile makes those reads defined - it does not
    // serialise the start/stop pair, which is E1 and belongs to GW-12.
    private volatile boolean bridgeActive = false;

    // The call media currently wired to gsmAudioPort, kept so stopBridge() can unwire
    // exactly what startBridge() wired. Leaving conference links dangling across calls
    // is how a port ends up with a stale listener count.
    private volatile AudioMedia wiredCallMedia;
    private volatile int wiredConfSlot = -1;

    public interface BridgeListener {
        void onBridgeStarted();
        void onBridgeStopped();
        void onBridgeError(String error);
    }

    private BridgeListener listener;

    public AudioBridgeManager(Context context, GatewayConfig config) {
        this.context = context.getApplicationContext();
        this.config = config;
    }

    public void setListener(BridgeListener listener) {
        this.listener = listener;
    }

    /**
     * Initialize the GSM audio port.
     * Should be called once when the service starts.
     */
    public void initialize() {
        if (gsmAudioPort != null) {
            Log.d(TAG, "Audio port already initialized");
            return;
        }

        try {
            Log.d(TAG, "Initializing GSM audio port...");

            // Create GsmAudioPort - it selects the SoC audio profile from config.
            // Published to the field first (unchanged ordering), then driven through the
            // local so the rest of this method cannot see a different object.
            GsmAudioPort port = new GsmAudioPort(context, config);
            gsmAudioPort = port;

            // Initialize native audio
            if (!port.initialize()) {
                Log.w(TAG, "Native audio init failed, will retry on call");
            }

            // Create PJSIP port
            port.createPort();

            Log.d(TAG, "GSM audio port initialized");

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize audio port: " + e.getMessage(), e);
            gsmAudioPort = null;
        }
    }

    /**
     * Start audio bridge for a SIP call.
     * Connects GsmAudioPort to the call's audio media.
     */
    public void startBridge(GatewayCall call) {
        // Snapshot: runs on pjsua workers and on main while release() may null the field.
        // Every wiring step below must act on the one port we checked.
        GsmAudioPort port = gsmAudioPort;
        if (port == null) {
            Log.e(TAG, "Audio port not initialized");
            notifyError("Audio port not initialized");
            return;
        }

        try {
            CallInfo info = call.getInfo();
            CallMediaInfoVector mediaVec = info.getMedia();

            for (int i = 0; i < mediaVec.size(); i++) {
                CallMediaInfo mediaInfo = mediaVec.get(i);

                if (mediaInfo.getType() == pjmedia_type.PJMEDIA_TYPE_AUDIO &&
                    mediaInfo.getStatus() == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {

                    int confSlot = mediaInfo.getAudioConfSlot();

                    // Only skip when the conference links are still live. PJSIP destroys
                    // and re-creates the media stream on every re-INVITE/UPDATE - it
                    // sends one itself right after the 200 OK to lock the codec - which
                    // silently drops every link while keeping the same slot number. An
                    // early return here is what leaves the transmit leg dead
                    // (onFrameRequested stays at 0).
                    if (bridgeActive && SipDiagnostics.isTransmitting(port, confSlot)) {
                        Log.d(TAG, "Bridge already wired to conf slot " + confSlot);
                        return;
                    }
                    if (bridgeActive) {
                        Log.i(TAG, "Conference links lost (media stream re-created), rewiring");
                        // Deliberately no stopTransmit: the old port is already gone and
                        // its slot may have been handed to somebody else.
                        wiredCallMedia = null;
                        wiredConfSlot = -1;
                    }

                    AudioMedia audioMedia = AudioMedia.typecastFromMedia(call.getMedia(i));

                    // Apply gain settings
                    float txGain = GatewayConfig.dbToLinear(config.getTxGain());  // GSM→SIP
                    float rxGain = GatewayConfig.dbToLinear(config.getRxGain());  // SIP→GSM

                    // Adjust levels on our audio port
                    port.adjustTxLevel(txGain);  // What we send to SIP
                    port.adjustRxLevel(rxGain);  // What we receive from SIP

                    Log.d(TAG, String.format("Gain: TX=%.1fdB (%.2f), RX=%.1fdB (%.2f)",
                            config.getTxGain(), txGain, config.getRxGain(), rxGain));

                    // Connect: GSM -> SIP (our audio port -> call audio)
                    port.startTransmit(audioMedia);

                    // Connect: SIP -> GSM (call audio -> our audio port)
                    audioMedia.startTransmit(port);

                    wiredCallMedia = audioMedia;
                    wiredConfSlot = confSlot;
                    bridgeActive = true;

                    Log.i(TAG, "Audio bridge started");

                    // Prove the conference links actually took: a source port with no
                    // listener is never pulled by the bridge, so onFrameRequested would
                    // stay at zero and nothing would ever reach SIP.
                    SipDiagnostics.dumpAndLog(call, port, "startBridge");

                    if (listener != null) {
                        listener.onBridgeStarted();
                    }

                    return;
                }
            }

            Log.w(TAG, "No active audio media found in call");
            notifyError("No active audio media");

        } catch (Exception e) {
            Log.e(TAG, "Failed to start audio bridge: " + e.getMessage(), e);
            notifyError("Failed to start audio bridge: " + e.getMessage());
        }
    }

    /**
     * Stop the audio bridge.
     */
    public void stopBridge() {
        if (!bridgeActive) {
            return;
        }

        Log.d(TAG, "Stopping audio bridge");

        unwireBridge();
        bridgeActive = false;

        if (listener != null) {
            listener.onBridgeStopped();
        }

        Log.i(TAG, "Audio bridge stopped");
    }

    /**
     * Drop the conference-bridge links made by {@link #startBridge}.
     *
     * The call media is usually already gone by the time we get here: a GSM hangup runs
     * terminateAllCalls(), which hangs the SIP call up - destroying its conference port -
     * before this reaches us. Disconnecting a destroyed port trips a pjmedia assertion
     * ("src_slot<conf->max_ports"), and that is an abort() rather than a Java exception,
     * so the catch blocks below cannot contain it. Both slots must be proven live first;
     * the catches only cover ordinary pjsua2 errors.
     */
    private void unwireBridge() {
        // Snapshot both: called from main, pjsua workers and ConfigReload.
        AudioMedia media = wiredCallMedia;
        GsmAudioPort port = gsmAudioPort;
        wiredCallMedia = null;
        wiredConfSlot = -1;

        if (media == null || port == null) {
            return;
        }

        // Ask the objects themselves rather than trusting wiredConfSlot: these are the
        // ids pjsua_conf_disconnect() will actually be handed, and getPortId() is a
        // plain field read that is safe on a torn-down media.
        int callSlot = media.getPortId();
        int localSlot = port.getPortId();

        if (!SipDiagnostics.isLiveConfPort(callSlot) || !SipDiagnostics.isLiveConfPort(localSlot)) {
            Log.d(TAG, "Conference ports already gone (call=" + callSlot
                    + ", local=" + localSlot + ") - nothing to unwire");
            return;
        }

        try {
            port.stopTransmit(media);
        } catch (Exception e) {
            Log.d(TAG, "stopTransmit GSM->SIP: " + e.getMessage());
        }
        try {
            media.stopTransmit(port);
        } catch (Exception e) {
            Log.d(TAG, "stopTransmit SIP->GSM: " + e.getMessage());
        }
    }

    /**
     * Start the underlying audio streams (capture/playback).
     * Should be called when GSM call becomes active.
     */
    public void startAudioStreams() {
        // Snapshot: the checked port must be the started one.
        GsmAudioPort port = gsmAudioPort;
        if (port == null) {
            Log.w(TAG, "Audio port not initialized, cannot start streams");
            return;
        }

        try {
            port.startCapture();
            Log.d(TAG, "Audio streams started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start audio streams: " + e.getMessage());
        }
    }

    /**
     * Stop the underlying audio streams.
     */
    public void stopAudioStreams() {
        // Snapshot: the checked port must be the stopped one.
        GsmAudioPort port = gsmAudioPort;
        if (port == null) {
            return;
        }

        try {
            port.stopCapture();
            Log.d(TAG, "Audio streams stopped");
        } catch (Exception e) {
            Log.e(TAG, "Error stopping audio streams: " + e.getMessage());
        }
    }

    /**
     * Release all resources.
     */
    public void release() {
        stopBridge();
        stopAudioStreams();

        // Snapshot: the checked port must be the deleted one.
        GsmAudioPort port = gsmAudioPort;
        if (port != null) {
            try {
                port.delete();
            } catch (Exception e) {
                Log.w(TAG, "Error deleting audio port: " + e.getMessage());
            }
            gsmAudioPort = null;
        }

        Log.d(TAG, "Audio bridge manager released");
    }

    /**
     * Check if bridge is currently active.
     */
    public boolean isBridgeActive() {
        return bridgeActive;
    }

    /**
     * Check if audio port is initialized.
     */
    public boolean isInitialized() {
        return gsmAudioPort != null;
    }

    /** The GSM audio port, or null before {@link #initialize()}. For diagnostics. */
    public GsmAudioPort getGsmAudioPort() {
        return gsmAudioPort;
    }

    /** True when the ALSA capture/playback devices are open (i.e. a GSM call is up). */
    public boolean isAudioStreaming() {
        // Snapshot: read from main and from pjsua workers.
        GsmAudioPort port = gsmAudioPort;
        return port != null && port.isCapturing();
    }

    /**
     * Whether the active SoC audio profile already mutes the local mic as part of
     * its mixer routing. When true, callers must NOT run DeviceMuteManager.
     * Defaults to false if the port isn't initialized yet.
     */
    public boolean handlesMicMute() {
        // Snapshot: called from the Telecom callback on main; release() can null the field.
        GsmAudioPort port = gsmAudioPort;
        if (port == null) {
            return false;
        }
        AudioProfile profile = port.getProfile();
        return profile != null && profile.handlesMicMute();
    }

    /**
     * Get status string for debugging.
     */
    public String getStatusString() {
        // Snapshot: read from NanoHTTPD workers and from the 1 Hz UI poll on main.
        GsmAudioPort port = gsmAudioPort;
        if (port == null) {
            return "Not initialized";
        }
        return bridgeActive ? "Bridge active" : "Idle";
    }

    private void notifyError(String error) {
        if (listener != null) {
            listener.onBridgeError(error);
        }
    }
}
