package org.onetwoone.gateway;

import android.content.Context;
import android.util.Log;

import org.onetwoone.gateway.audio.AudioProfile;
import org.onetwoone.gateway.audio.AudioProfileFactory;
import org.onetwoone.gateway.config.GatewayConfig;
import org.pjsip.pjsua2.*;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Custom AudioMediaPort for bridging GSM call audio to SIP.
 * Uses native tinyalsa for direct ALSA access - no tinycap/tinyplay processes.
 *
 * SoC-specific routing (which mixer controls tap the modem voice path, and which
 * PCM devices carry it) is delegated to an {@link AudioProfile} chosen at runtime
 * by {@link AudioProfileFactory}. This class stays SoC-agnostic.
 */
public class GsmAudioPort extends AudioMediaPort {
    private static final String TAG = "GsmAudioPort";

    // Fixed audio parameters
    private static final int BITS = 16;
    private static final int FRAME_TIME_MS = 20;
    private static final int PERIOD_COUNT = 4;

    private final Context context;
    private final int card;
    private final AudioProfile profile;

    // Capture side = the PJSIP port format (GSM→SIP). Playback may run at a
    // different ALSA rate (e.g. MediaTek 48 kHz); the native layer upsamples.
    private final int sampleRate;   // capture / PJSIP port rate
    private final int channels;     // capture / PJSIP port channels
    private final int periodSize;   // capture samples per 20ms period
    private final int frameSize;    // bytes per 20ms PJSIP/capture frame
    private final int playbackRate;
    private final int playbackChannels;
    private final int playbackPeriod;

    private static final int ENFORCE_INTERVAL_MS = 2000;
    // Retry opening the modem voice PCM in case the voice path isn't ready the
    // instant the call connects. (The main open failure - the playback memif
    // rejecting params - was a config-reuse bug fixed in native open().)
    private static final int OPEN_MAX_ATTEMPTS = 20;   // up to ~10s
    private static final int OPEN_RETRY_MS = 500;

    private final AtomicBoolean isCapturing = new AtomicBoolean(false);
    private final AtomicBoolean isPortCreated = new AtomicBoolean(false);

    // Background worker that opens the PCM devices (with retry) at call start.
    private Thread openThread;

    // Periodically re-asserts the profile's mixer routing to defeat the audio
    // HAL re-asserting its own routing shortly after a call connects.
    private Thread enforceThread;

    // Native read/write buffers (reused to avoid allocation)
    private final byte[] captureBuffer;
    private final byte[] playbackBuffer;

    // Statistics
    private long framesRequested = 0;
    private long framesReceived = 0;
    private long captureErrors = 0;
    private long playbackErrors = 0;

    public GsmAudioPort(Context context, GatewayConfig config) {
        super();
        this.context = context.getApplicationContext();

        this.card = config.getAudioCard();
        this.profile = AudioProfileFactory.select(this.context, config);

        this.sampleRate = profile.captureSampleRate();
        this.channels = profile.captureChannels();
        this.periodSize = sampleRate * FRAME_TIME_MS / 1000;
        this.frameSize = sampleRate * (BITS / 8) * channels * FRAME_TIME_MS / 1000;
        this.playbackRate = profile.playbackSampleRate();
        this.playbackChannels = profile.playbackChannels();
        this.playbackPeriod = playbackRate * FRAME_TIME_MS / 1000;

        this.captureBuffer = new byte[frameSize];
        this.playbackBuffer = new byte[frameSize];

        Log.i(TAG, "Profile=" + profile.name() + " card=" + card
                + " capture=" + profile.captureDevice() + "@" + sampleRate + "/" + channels + "ch"
                + " playback=" + profile.playbackDevice() + "@" + playbackRate + "/" + playbackChannels + "ch"
                + " frame=" + frameSize + "B");
    }

    /** SoC audio profile in use (for callers that must adapt, e.g. mic-mute handling). */
    public AudioProfile getProfile() {
        return profile;
    }

    /**
     * Initialize native audio
     */
    public boolean initialize() {
        Log.d(TAG, "Initializing GsmAudioPort (native mode)...");

        // Setup ALSA permissions (requires root)
        if (!RootHelper.setupAlsaPermissions()) {
            Log.e(TAG, "Failed to setup ALSA permissions - native audio won't work");
            return false;
        }

        // Log available mixer controls for debugging on new devices
        GsmAudioNative.logMixerControls(card);

        return true;
    }

    /**
     * Create PJSIP audio port
     */
    public void createPort() {
        if (isPortCreated.get()) {
            Log.d(TAG, "Port already created");
            return;
        }

        try {
            MediaFormatAudio fmt = new MediaFormatAudio();
            fmt.setType(pjmedia_type.PJMEDIA_TYPE_AUDIO);
            fmt.setId(pjmedia_format_id.PJMEDIA_FORMAT_L16);
            fmt.setClockRate(sampleRate);
            fmt.setChannelCount(channels);
            fmt.setBitsPerSample(BITS);
            fmt.setFrameTimeUsec(FRAME_TIME_MS * 1000);

            super.createPort("gsm_bridge", fmt);
            isPortCreated.set(true);

            Log.d(TAG, "Audio port created: " + sampleRate + "Hz, " + channels + "ch, " + BITS + "bit, frame=" + frameSize);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create port: " + e.getMessage());
        }
    }

    /**
     * PJSIP callback: Need audio to SEND to SIP peer (GSM → SIP direction)
     */
    @Override
    public void onFrameRequested(MediaFrame frame) {
        framesRequested++;

        try {
            ByteVector buf = frame.getBuf();
            buf.clear();

            if (isCapturing.get() && GsmAudioNative.isOpen()) {
                // Read from native ALSA
                int bytesRead = GsmAudioNative.readFrame(captureBuffer);

                if (bytesRead == frameSize) {
                    for (byte b : captureBuffer) {
                        buf.add((short) (b & 0xFF));
                    }
                    frame.setSize(frameSize);
                    frame.setType(pjmedia_frame_type.PJMEDIA_FRAME_TYPE_AUDIO);
                } else {
                    captureErrors++;
                    // Send silence on error
                    for (int i = 0; i < frameSize; i++) buf.add((short) 0);
                    frame.setSize(frameSize);
                    frame.setType(pjmedia_frame_type.PJMEDIA_FRAME_TYPE_NONE);
                }
            } else {
                // Not capturing - send silence
                for (int i = 0; i < frameSize; i++) buf.add((short) 0);
                frame.setSize(frameSize);
                frame.setType(pjmedia_frame_type.PJMEDIA_FRAME_TYPE_NONE);
            }

            // Log every 500 frames (~10 seconds)
            if (framesRequested % 500 == 0) {
                Log.d(TAG, "onFrameRequested: " + framesRequested + " frames, errors=" + captureErrors);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onFrameRequested: " + e.getMessage());
        }
    }

    /**
     * PJSIP callback: RECEIVED audio from SIP peer (SIP → GSM direction)
     */
    @Override
    public void onFrameReceived(MediaFrame frame) {
        framesReceived++;

        try {
            if (!isCapturing.get() || !GsmAudioNative.isOpen()) {
                return;
            }

            ByteVector buf = frame.getBuf();
            long size = frame.getSize();

            if (buf != null && size > 0 && size <= frameSize) {
                // Convert ByteVector to byte[]
                for (int i = 0; i < size; i++) {
                    playbackBuffer[i] = (byte) (buf.get(i) & 0xFF);
                }

                // Write to native ALSA
                int bytesWritten = GsmAudioNative.writeFrame(playbackBuffer);
                if (bytesWritten < 0) {
                    playbackErrors++;
                }
            }

            // Log every 500 frames (~10 seconds)
            if (framesReceived % 500 == 0) {
                Log.d(TAG, "onFrameReceived: " + framesReceived + " frames, errors=" + playbackErrors);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onFrameReceived: " + e.getMessage());
        }
    }

    /**
     * Start audio capture/playback (when GSM call becomes active)
     */
    public void startCapture() {
        if (isCapturing.get()) {
            Log.w(TAG, "Already capturing");
            return;
        }
        if (openThread != null && openThread.isAlive()) {
            Log.w(TAG, "Open already in progress");
            return;
        }

        // Run open on a background thread: the modem voice memif may not accept
        // the PCM params for the first few seconds after a call connects
        // (especially incoming/SIP-first calls), so we retry with backoff. Doing
        // this off the caller (main) thread avoids blocking the call lifecycle.
        openThread = new Thread(this::openWithRetry, "GsmAudioOpen");
        openThread.start();
    }

    private void openWithRetry() {
        Log.d(TAG, "Starting native audio (" + profile.name() + ")...");

        // Re-apply ALSA permissions: the audio HAL recreates /dev/snd/* nodes
        // (resetting perms to system:audio) when a call starts, so a chmod done
        // once at init no longer holds by the time we open the devices here.
        if (!RootHelper.setupAlsaPermissions()) {
            Log.e(TAG, "Failed to (re)apply ALSA permissions - open will likely fail");
        }

        // Setup SoC-specific mixer routing
        profile.setupMixer(card);

        boolean opened = false;
        int usedAttempt = 0;
        for (int attempt = 1; attempt <= OPEN_MAX_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                // Aborted by stopCapture() - let it own teardown.
                Log.d(TAG, "Open aborted before attempt " + attempt);
                return;
            }
            opened = GsmAudioNative.open(
                card, profile.captureDevice(), profile.playbackDevice(),
                sampleRate, channels,
                playbackRate, playbackChannels,
                BITS, periodSize, playbackPeriod, PERIOD_COUNT
            );
            if (opened) { usedAttempt = attempt; break; }

            Log.w(TAG, "Open attempt " + attempt + "/" + OPEN_MAX_ATTEMPTS
                    + " failed; retrying in " + OPEN_RETRY_MS + "ms (voice path may not be ready yet)");
            try {
                Thread.sleep(OPEN_RETRY_MS);
            } catch (InterruptedException e) {
                // Aborted by stopCapture() - let it own teardown.
                Log.d(TAG, "Open aborted during retry");
                return;
            }
        }

        if (!opened) {
            Log.e(TAG, "Failed to open native audio devices after " + OPEN_MAX_ATTEMPTS + " attempts!");
            Log.e(TAG, "Check: 1) Root access 2) Device permissions 3) Correct device numbers");
            profile.teardownMixer(card);
            return;
        }

        isCapturing.set(true);
        startEnforceThread();
        Log.d(TAG, "Native audio started (opened on attempt " + usedAttempt
                + ", ~" + ((usedAttempt - 1) * OPEN_RETRY_MS) + "ms after start)");
    }

    /**
     * Start the background thread that re-asserts the profile's mixer routing
     * every {@link #ENFORCE_INTERVAL_MS} ms while capturing. The audio HAL tends
     * to re-assert its own routing (e.g. re-enabling the local mic) a moment
     * after a call connects, which would otherwise override our setup.
     */
    private void startEnforceThread() {
        stopEnforceThread();
        enforceThread = new Thread(() -> {
            while (isCapturing.get()) {
                try {
                    Thread.sleep(ENFORCE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                if (isCapturing.get()) {
                    profile.enforceMixer(card);
                }
            }
        }, "MixerEnforce");
        enforceThread.start();
    }

    private void stopEnforceThread() {
        if (enforceThread != null) {
            enforceThread.interrupt();
            try {
                // Join briefly so teardownMixer can't race a final enforceMixer.
                enforceThread.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            enforceThread = null;
        }
    }

    /**
     * Stop audio capture/playback
     */
    public void stopCapture() {
        Log.d(TAG, "Stopping native audio...");

        isCapturing.set(false);

        // Abort a pending open worker (may still be retrying) before teardown.
        if (openThread != null) {
            openThread.interrupt();
            try {
                openThread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            openThread = null;
        }

        stopEnforceThread();

        // Close native audio
        GsmAudioNative.close();

        // Teardown mixer
        profile.teardownMixer(card);

        Log.d(TAG, "Native audio stopped. Stats: requested=" + framesRequested +
              ", received=" + framesReceived +
              ", captureErr=" + captureErrors + ", playbackErr=" + playbackErrors);

        // Reset statistics
        framesRequested = 0;
        framesReceived = 0;
        captureErrors = 0;
        playbackErrors = 0;
    }

    public void stop() {
        stopCapture();
    }

    public boolean isCapturing() {
        return isCapturing.get();
    }
}
