package org.onetwoone.gateway.diag;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import org.onetwoone.gateway.GatewayAccount;
import org.onetwoone.gateway.GatewayCall;
import org.onetwoone.gateway.SipCallService;
import org.onetwoone.gateway.audio.AudioBridgeManager;
import org.onetwoone.gateway.config.GatewayConfig;
import org.onetwoone.gateway.sip.SipAccountManager;
import org.pjsip.pjsua2.AudioMedia;
import org.pjsip.pjsua2.AudioMediaRecorder;
import org.pjsip.pjsua2.CallInfo;
import org.pjsip.pjsua2.CallMediaInfo;
import org.pjsip.pjsua2.CallMediaInfoVector;
import org.pjsip.pjsua2.CallOpParam;
import org.pjsip.pjsua2.ToneDesc;
import org.pjsip.pjsua2.ToneDescVector;
import org.pjsip.pjsua2.ToneGenerator;
import org.pjsip.pjsua2.pjmedia_type;
import org.pjsip.pjsua2.pjsip_inv_state;
import org.pjsip.pjsua2.pjsip_status_code;
import org.pjsip.pjsua2.pjsua_call_media_status;

import java.io.File;

/**
 * Places a diagnostic SIP call that needs no GSM leg, so the SIP media path can be
 * tested on its own.
 *
 * The gateway's real call path mixes three subsystems (ALSA capture, PJSIP's conference
 * bridge, and the PBX) into a single pass/fail signal. This splits them:
 *
 * <ul>
 *   <li>{@link Mode#TONE} - a PJSIP tone generator transmits into the call. If the peer
 *       hears it, the conference bridge and RTP transmit path are fine and the fault is
 *       specific to {@code GsmAudioPort} as a source.</li>
 *   <li>{@link Mode#LOOPBACK} - the call's own media is transmitted back to itself, so
 *       the peer hears themselves. Nothing local is involved, which isolates
 *       SIP/SDP/SRTP/RTP end to end.</li>
 *   <li>{@link Mode#BRIDGE} - the real {@link AudioBridgeManager} wiring, for running the
 *       full chain against an echo target during a live GSM call.</li>
 * </ul>
 *
 * Every mode also records what the PBX sends back to a WAV, and samples
 * {@link SipDiagnostics} every {@value #POLL_INTERVAL_MS} ms.
 *
 * Threading: PJSIP may only be called from a registered thread, so every public entry
 * point hops onto the main thread (registered in {@code PjsipSipService.initializeSip}).
 */
public class SipTestCallManager {
    private static final String TAG = "SipTestCall";

    private static final int POLL_INTERVAL_MS = 2000;
    private static final int DEFAULT_DURATION_SEC = 20;
    private static final int MAX_REPORT_CHARS = 20000;

    // Matches MediaConfig.setClockRate/setChannelCount in SipEndpointManager.
    private static final int TONE_CLOCK_RATE = 8000;
    private static final int TONE_CHANNELS = 1;

    public enum Mode {
        TONE,
        LOOPBACK,
        BRIDGE;

        public static Mode parse(String value) {
            if (value != null) {
                for (Mode m : values()) {
                    if (m.name().equalsIgnoreCase(value.trim())) {
                        return m;
                    }
                }
            }
            return TONE;
        }
    }

    private final Context context;
    private final GatewayConfig config;
    private final SipAccountManager accountManager;
    private final AudioBridgeManager audioBridge;
    private final SipCallService callbackService;
    private final Handler mainHandler;

    private final StringBuilder report = new StringBuilder();

    private GatewayCall call;
    private Mode mode = Mode.TONE;
    private long startedAt;
    private boolean mediaWired;
    private volatile boolean mediaValid;

    // Media we created/linked, kept so teardown can unwire exactly what it wired.
    private AudioMedia wiredCallMedia;
    private ToneGenerator toneGen;
    private AudioMediaRecorder recorder;
    private File recordFile;
    private boolean loopbackWired;

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            if (call == null) return;
            // Self-heal: re-establish the conference links if PJSIP re-created the media
            // stream without us seeing a media-state callback.
            wireMedia();
            appendDiagnostics("poll");
            mainHandler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private final Runnable autoHangup = () -> {
        append("duration elapsed, hanging up");
        stopInternal();
    };

    public SipTestCallManager(Context context,
                              GatewayConfig config,
                              SipAccountManager accountManager,
                              AudioBridgeManager audioBridge,
                              SipCallService callbackService,
                              Handler mainHandler) {
        this.context = context.getApplicationContext();
        this.config = config;
        this.accountManager = accountManager;
        this.audioBridge = audioBridge;
        this.callbackService = callbackService;
        this.mainHandler = mainHandler;
    }

    // ========== Public API (any thread) ==========

    public boolean isActive() {
        return call != null;
    }

    /** True when the given call is the diagnostic call, not a gateway call. */
    public boolean owns(GatewayCall other) {
        return other != null && other == call;
    }

    public synchronized String getReport() {
        return report.toString();
    }

    public void start(String destination, Mode requestedMode, int durationSec) {
        mainHandler.post(() -> startInternal(destination, requestedMode, durationSec));
    }

    public void stop() {
        mainHandler.post(this::stopInternal);
    }

    // ========== Callbacks from PjsipSipService ==========

    public void onCallState(int pjsipState) {
        if (pjsipState == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) {
            mediaValid = false;
            append("call DISCONNECTED");
            mainHandler.post(this::stopInternal);
        } else if (pjsipState == pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED) {
            append("call CONFIRMED");
        }
    }

    public void onMediaState() {
        mainHandler.post(this::wireMedia);
    }

    // ========== Internals (main thread only) ==========

    private void startInternal(String destination, Mode requestedMode, int durationSec) {
        if (call != null) {
            append("test call already active - ignoring start");
            return;
        }

        startedAt = System.currentTimeMillis();

        GatewayAccount account = accountManager.getAccount();
        if (account == null) {
            resetReport();
            append("ERROR: no SIP account (is the gateway registered?)");
            return;
        }

        String dest = (destination == null || destination.trim().isEmpty())
                ? config.getTestDestination() : destination.trim();
        mode = requestedMode == null ? Mode.TONE : requestedMode;
        int seconds = durationSec > 0 ? durationSec : DEFAULT_DURATION_SEC;

        String uri = SipUriBuilder.build(dest, config.getSipServer(), config.isUseTls());

        resetReport();
        // Clear the PJSIP ring buffer so it holds only this call's SIP messages.
        PjsipLogWriter.get().clear();

        mediaWired = false;
        mediaValid = true;
        append("mode=" + mode + " duration=" + seconds + "s uri=" + uri);

        try {
            call = new GatewayCall(callbackService, account);
            call.makeCall(uri, new CallOpParam(true));
            append("INVITE sent");
        } catch (Exception e) {
            append("ERROR: makeCall failed: " + e.getMessage());
            Log.e(TAG, "makeCall failed", e);
            call = null;
            return;
        }

        mainHandler.postDelayed(autoHangup, seconds * 1000L);
        mainHandler.postDelayed(poller, POLL_INTERVAL_MS);
    }

    private void wireMedia() {
        if (call == null) {
            return;
        }

        AudioMedia audioMedia = findActiveAudioMedia();
        if (audioMedia == null) {
            if (!mediaWired) {
                append("no ACTIVE audio media yet");
            }
            return;
        }

        // PJSIP destroys and re-creates the media stream on every re-INVITE/UPDATE - it
        // sends one itself right after the 200 OK to lock the codec - and that silently
        // drops every conference link while keeping the same slot number. So re-check
        // the links rather than trusting a "wired once" flag.
        if (mediaWired && linksLive(audioMedia)) {
            return;
        }

        if (mediaWired) {
            append("conference links lost (PJSIP re-created the media stream), rewiring");
        }

        wiredCallMedia = audioMedia;
        mediaWired = true;

        try {
            if (recorder == null) {
                startRecorder();
            }
            if (recorder != null) {
                audioMedia.startTransmit(recorder);
            }

            switch (mode) {
                case TONE:
                    wireTone(audioMedia);
                    break;
                case LOOPBACK:
                    audioMedia.startTransmit(audioMedia);
                    loopbackWired = true;
                    append("wired loopback (peer hears itself)");
                    break;
                case BRIDGE:
                    audioBridge.startBridge(call);
                    append("wired GSM bridge; audio streams "
                            + (audioBridge.isAudioStreaming() ? "open" : "NOT open (no live GSM call?)"));
                    break;
            }
        } catch (Exception e) {
            append("ERROR: wiring failed: " + e.getMessage());
            Log.e(TAG, "wiring failed", e);
        }

        appendDiagnostics("wired");
    }

    /** Are the conference links for the current mode (and the recorder) still up? */
    private boolean linksLive(AudioMedia audioMedia) {
        int callSlot;
        try {
            callSlot = audioMedia.getPortId();
        } catch (Exception e) {
            return false;
        }

        if (recorder != null) {
            try {
                if (!SipDiagnostics.isTransmitting(audioMedia, recorder.getPortId())) {
                    return false;
                }
            } catch (Exception e) {
                return false;
            }
        }

        switch (mode) {
            case TONE:
                return SipDiagnostics.isTransmitting(toneGen, callSlot);
            case LOOPBACK:
                return SipDiagnostics.isTransmitting(audioMedia, callSlot);
            case BRIDGE:
                return SipDiagnostics.isTransmitting(audioBridge.getGsmAudioPort(), callSlot);
            default:
                return false;
        }
    }

    private void wireTone(AudioMedia audioMedia) throws Exception {
        if (toneGen == null) {
            toneGen = new ToneGenerator();
            toneGen.createToneGenerator(TONE_CLOCK_RATE, TONE_CHANNELS);

            ToneDesc tone = new ToneDesc();
            tone.setFreq1((short) 440);
            tone.setFreq2((short) 480);
            tone.setOn_msec((short) 1000);
            tone.setOff_msec((short) 500);

            ToneDescVector tones = new ToneDescVector();
            tones.add(tone);

            toneGen.play(tones, true);
        }

        toneGen.startTransmit(audioMedia);
        append("wired 440/480Hz tone -> call");
    }

    private void startRecorder() {
        try {
            File dir = context.getExternalFilesDir("diag");
            if (dir == null) {
                append("WARN: no external files dir, skipping recording");
                return;
            }
            if (!dir.exists() && !dir.mkdirs()) {
                append("WARN: could not create " + dir + ", skipping recording");
                return;
            }
            recordFile = new File(dir, "testcall-" + startedAt + ".wav");
            recorder = new AudioMediaRecorder();
            recorder.createRecorder(recordFile.getAbsolutePath());
            append("recording SIP audio to " + recordFile.getAbsolutePath());
        } catch (Exception e) {
            append("WARN: recorder failed: " + e.getMessage());
            recorder = null;
            recordFile = null;
        }
    }

    private AudioMedia findActiveAudioMedia() {
        try {
            CallInfo info = call.getInfo();
            CallMediaInfoVector mediaVec = info.getMedia();
            for (int i = 0; i < mediaVec.size(); i++) {
                CallMediaInfo mi = mediaVec.get(i);
                if (mi.getType() == pjmedia_type.PJMEDIA_TYPE_AUDIO
                        && mi.getStatus() == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {
                    return AudioMedia.typecastFromMedia(call.getMedia(i));
                }
            }
        } catch (Exception e) {
            append("ERROR: reading media failed: " + e.getMessage());
        }
        return null;
    }

    private void stopInternal() {
        if (call == null) {
            return;
        }

        mainHandler.removeCallbacks(poller);
        mainHandler.removeCallbacks(autoHangup);

        if (mediaValid) {
            appendDiagnostics("final");
        }

        unwireMedia();

        try {
            if (mediaValid && call.isActive()) {
                CallOpParam prm = new CallOpParam();
                prm.setStatusCode(pjsip_status_code.PJSIP_SC_DECLINE);
                call.hangup(prm);
            }
        } catch (Exception e) {
            append("hangup: " + e.getMessage());
        }

        // Don't delete() the Call - PJSIP owns the native lifecycle (same rule as
        // CallManager.hangupSipCall). dispose() stops further callbacks.
        call.dispose();
        call = null;
        mediaWired = false;
        mediaValid = false;

        if (recordFile != null) {
            append("recording: " + recordFile.getAbsolutePath()
                    + " (" + recordFile.length() + " bytes)");
            recordFile = null;
        }

        appendSdp();
        append("test call finished");
    }

    private void unwireMedia() {
        if (toneGen != null) {
            try {
                toneGen.stop();
            } catch (Exception ignored) {
            }
            try {
                if (mediaValid && wiredCallMedia != null) {
                    toneGen.stopTransmit(wiredCallMedia);
                }
            } catch (Exception ignored) {
            }
            try {
                toneGen.delete();
            } catch (Exception ignored) {
            }
            toneGen = null;
        }

        if (loopbackWired && mediaValid && wiredCallMedia != null) {
            try {
                wiredCallMedia.stopTransmit(wiredCallMedia);
            } catch (Exception ignored) {
            }
        }
        loopbackWired = false;

        if (recorder != null) {
            try {
                if (mediaValid && wiredCallMedia != null) {
                    wiredCallMedia.stopTransmit(recorder);
                }
            } catch (Exception ignored) {
            }
            try {
                recorder.delete();
            } catch (Exception ignored) {
            }
            recorder = null;
        }

        if (mode == Mode.BRIDGE) {
            audioBridge.stopBridge();
        }

        wiredCallMedia = null;
    }

    private void appendDiagnostics(String label) {
        if (call == null) return;
        AudioMedia localSource = localSourceForMode();
        append(SipDiagnostics.dumpAndLog(call, localSource, label));
    }

    private AudioMedia localSourceForMode() {
        if (mode == Mode.TONE) {
            return toneGen;
        }
        if (mode == Mode.BRIDGE) {
            return audioBridge.getGsmAudioPort();
        }
        // LOOPBACK transmits the call media into itself.
        return wiredCallMedia;
    }

    private void appendSdp() {
        String sdp = PjsipLogWriter.get().recentMatching("m=audio");
        if (!sdp.isEmpty()) {
            append("--- SIP messages carrying SDP ---\n" + sdp);
        }
    }

    // ========== Report ==========

    private synchronized void resetReport() {
        report.setLength(0);
    }

    private synchronized void append(String line) {
        long elapsed = startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt;
        report.append(String.format(java.util.Locale.US, "[%5.1fs] ", elapsed / 1000.0))
                .append(line).append('\n');
        if (report.length() > MAX_REPORT_CHARS) {
            report.delete(0, report.length() - MAX_REPORT_CHARS);
        }
        Log.i(TAG, line);
    }
}
