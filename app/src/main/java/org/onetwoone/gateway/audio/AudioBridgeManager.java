package org.onetwoone.gateway.audio;

import android.content.Context;
import android.util.Log;

import org.onetwoone.gateway.GatewayCall;
import org.onetwoone.gateway.GsmAudioPort;
import org.onetwoone.gateway.config.GatewayConfig;
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

    // Static to survive service restart (like Endpoint)
    private static GsmAudioPort gsmAudioPort;
    private boolean bridgeActive = false;

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

            // Create GsmAudioPort - it selects the SoC audio profile from config
            gsmAudioPort = new GsmAudioPort(context, config);

            // Initialize native audio
            if (!gsmAudioPort.initialize()) {
                Log.w(TAG, "Native audio init failed, will retry on call");
            }

            // Create PJSIP port
            gsmAudioPort.createPort();

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
        if (bridgeActive) {
            Log.w(TAG, "Bridge already active");
            return;
        }

        if (gsmAudioPort == null) {
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

                    AudioMedia audioMedia = AudioMedia.typecastFromMedia(call.getMedia(i));

                    // Apply gain settings
                    float txGain = GatewayConfig.dbToLinear(config.getTxGain());  // GSM→SIP
                    float rxGain = GatewayConfig.dbToLinear(config.getRxGain());  // SIP→GSM

                    // Adjust levels on our audio port
                    gsmAudioPort.adjustTxLevel(txGain);  // What we send to SIP
                    gsmAudioPort.adjustRxLevel(rxGain);  // What we receive from SIP

                    Log.d(TAG, String.format("Gain: TX=%.1fdB (%.2f), RX=%.1fdB (%.2f)",
                            config.getTxGain(), txGain, config.getRxGain(), rxGain));

                    // Connect: GSM -> SIP (our audio port -> call audio)
                    gsmAudioPort.startTransmit(audioMedia);

                    // Connect: SIP -> GSM (call audio -> our audio port)
                    audioMedia.startTransmit(gsmAudioPort);

                    bridgeActive = true;

                    Log.i(TAG, "Audio bridge started");

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

        // The GsmAudioPort handles cleanup internally
        // We just mark the bridge as inactive

        bridgeActive = false;

        if (listener != null) {
            listener.onBridgeStopped();
        }

        Log.i(TAG, "Audio bridge stopped");
    }

    /**
     * Start the underlying audio streams (capture/playback).
     * Should be called when GSM call becomes active.
     */
    public void startAudioStreams() {
        if (gsmAudioPort == null) {
            Log.w(TAG, "Audio port not initialized, cannot start streams");
            return;
        }

        try {
            gsmAudioPort.startCapture();
            Log.d(TAG, "Audio streams started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start audio streams: " + e.getMessage());
        }
    }

    /**
     * Stop the underlying audio streams.
     */
    public void stopAudioStreams() {
        if (gsmAudioPort == null) {
            return;
        }

        try {
            gsmAudioPort.stopCapture();
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

        if (gsmAudioPort != null) {
            try {
                gsmAudioPort.delete();
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

    /**
     * Whether the active SoC audio profile already mutes the local mic as part of
     * its mixer routing. When true, callers must NOT run DeviceMuteManager.
     * Defaults to false if the port isn't initialized yet.
     */
    public boolean handlesMicMute() {
        return gsmAudioPort != null
                && gsmAudioPort.getProfile() != null
                && gsmAudioPort.getProfile().handlesMicMute();
    }

    /**
     * Get status string for debugging.
     */
    public String getStatusString() {
        if (gsmAudioPort == null) {
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
