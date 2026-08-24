package org.onetwoone.gateway.diag;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.onetwoone.gateway.GatewayAccount;
import org.onetwoone.gateway.GatewayCall;
import org.onetwoone.gateway.SipCallService;
import org.onetwoone.gateway.audio.AudioBridgeManager;
import org.onetwoone.gateway.call.CallGraveyard;
import org.onetwoone.gateway.config.GatewayConfig;
import org.onetwoone.gateway.core.GatewayControlThread;
import org.onetwoone.gateway.sip.Pjsua2Lifetime;
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

    /**
     * Only ever used to hand {@code AudioBridgeManager} work back to its owner. Since GW-12
     * the bridge is control-thread state and asserts it, while this manager is main-bound, so
     * the two BRIDGE-mode calls hop. Fire-and-forget in both directions: main must never
     * block waiting on the control thread (plan §2.4).
     */
    private final GatewayControlThread control;

    /**
     * The diagnostic call's own graveyard. Separate from {@code CallManager}'s only because
     * each manager buries the calls it is responsible for; the counters they feed
     * ({@code GatewayCall.getCallsCreated/Deleted}) are process-wide either way. AUDIT H7.
     */
    private final CallGraveyard graveyard;

    private final StringBuilder report = new StringBuilder();

    /**
     * Written on main (start/stopInternal), read from main/NanoHTTPD via {@link #isActive}
     * and from the control thread via {@code PjsipSipService.startTestCall} - hence
     * volatile, snapshotted at every consumer (AUDIT D4). Since GW-10 it is no longer read
     * from a pjsua worker to demux callbacks; see {@link #owns}.
     */
    private volatile GatewayCall call;

    /**
     * Written on main, read by {@link #append} which pjsua workers reach through
     * {@link #onCallState}. {@code append} is synchronized but the write is not, so the
     * monitor gives no happens-before here - volatile does.
     */
    private volatile long startedAt;

    // Main-thread only - see assertMainThread(String). Deliberately NOT volatile: these are
    // written and read solely by startInternal / wireMedia / stopInternal and the two
    // main-looper runnables, so the invariant is asserted rather than papered over.
    private Mode mode = Mode.TONE;
    private boolean mediaWired;
    private volatile boolean mediaValid;

    // Media we created/linked, kept so teardown can unwire exactly what it wired.
    // Main-thread only, as above.
    private AudioMedia wiredCallMedia;

    /**
     * The {@code GatewayCall} generation currently wired into the audio bridge in BRIDGE mode,
     * so {@link #unwireMedia()} unwires that and nothing else. Main-thread only, as above.
     */
    private long bridgedGeneration = AudioBridgeManager.NO_GENERATION;

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
                              Handler mainHandler,
                              GatewayControlThread control) {
        this.context = context.getApplicationContext();
        this.config = config;
        this.accountManager = accountManager;
        this.audioBridge = audioBridge;
        this.callbackService = callbackService;
        this.mainHandler = mainHandler;
        this.control = control;
        this.graveyard = new CallGraveyard(control);
    }

    /**
     * Hand a finished diagnostic call to the graveyard.
     *
     * <p>Always a hop: this manager is main-bound and the graveyard is control-thread state.
     * Fire-and-forget, like the two BRIDGE-mode calls above - main must never block waiting on
     * the control thread (plan §3).
     */
    private void bury(GatewayCall finished, String why) {
        if (finished == null) {
            return;
        }
        control.post(() -> graveyard.bury(finished, why));
    }

    // ========== Public API (any thread) ==========

    public boolean isActive() {
        return call != null;
    }

    /**
     * True when the given call is <em>this manager's current</em> diagnostic call.
     *
     * <p><b>Not for callback dispatch.</b> {@code PjsipSipService} demuxes on the call's
     * immutable {@link GatewayCall#getOwner()} instead, because this field is nulled by
     * {@link #startInternal}'s catch block on a failed dial, and a callback evaluated after
     * that would be routed into the gateway state machine (plan §2.6). Left in place for
     * "is this the call I am currently running" questions only; GW-31 decides its fate.
     */
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

    /**
     * Called <em>synchronously on the pjsua callback thread</em>, deliberately.
     *
     * <p>GW-10 posts the gateway callbacks onto the control thread, but this one may not be
     * deferred as a whole: {@link #mediaValid} has to drop the instant PJSIP says
     * DISCONNECTED. It guards every later {@code stopTransmit} against a conference port
     * PJSIP has already destroyed, and that failure is a pjmedia assertion - an
     * {@code abort()}, not something the catch blocks below can contain. A teardown already
     * queued ahead of a late flag flip ({@link #autoHangup}, a user {@code stop()}, the 2 s
     * poller) would run against destroyed media.
     *
     * <p>So: post the handling, never the flag. The flag is set here; the work it gates is
     * posted onto the main looper, which is where this class's internals live.
     */
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
        assertMainThread("startInternal");
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

        CallOpParam dialParam = null;
        try {
            // Assign `call` BEFORE makeCall - do not "tidy" this into `call = new ...;
            // newCall.makeCall(...)` or move the assignment below. PJSIP can deliver
            // onCallState(DISCONNECTED) synchronously on this thread from inside makeCall,
            // and owns(call) must already match or the callback is dropped and the test call
            // never reports its failure. Same reasoning as AUDIT D2 / GW-06 on the gateway
            // path; the catch below unregisters it again if makeCall throws.
            call = new GatewayCall(callbackService, account, GatewayCall.Owner.DIAGNOSTIC);
            // Java-created and therefore ours to delete; makeCall marshals it synchronously
            // and retains nothing (AUDIT H7).
            dialParam = new CallOpParam(true);
            call.makeCall(uri, dialParam);
            append("INVITE sent");
        } catch (Exception e) {
            append("ERROR: makeCall failed: " + e.getMessage());
            Log.e(TAG, "makeCall failed", e);
            // Nothing else will ever reference this call: the demux runs off its immutable
            // Owner, and the manager's own field is being cleared here. Before GW-22 it was
            // simply dropped and left to the finalizer (AUDIT H7).
            bury(call, "diagnostic dial failed");
            call = null;
            return;
        } finally {
            Pjsua2Lifetime.delete(dialParam);
        }

        mainHandler.postDelayed(autoHangup, seconds * 1000L);
        mainHandler.postDelayed(poller, POLL_INTERVAL_MS);
    }

    private void wireMedia() {
        assertMainThread("wireMedia");
        // Snapshot: stopInternal() nulls the field, and onCallState() on a pjsua worker can
        // post that teardown at any point.
        GatewayCall current = call;
        if (current == null) {
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
                    // Hop to the bridge's owner (GW-12). append() is synchronized and is
                    // already reached from foreign threads, so reporting from there is fine.
                    final GatewayCall bridged = current;
                    bridgedGeneration = bridged.getGeneration();
                    control.post(() -> {
                        audioBridge.startBridge(bridged);
                        append("wired GSM bridge; audio streams "
                                + (audioBridge.isAudioStreaming()
                                        ? "open" : "NOT open (no live GSM call?)"));
                    });
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
        // Snapshot: the CallInfo and the media handle must come from the same call object.
        // No null guard on purpose - a null here behaves exactly as before, i.e. the NPE is
        // caught below and reported.
        GatewayCall current = call;
        // `info` is owned; `mediaVec`/`mi` are views into it, so it is released only once the
        // loop is done with them. The returned AudioMedia comes from Call.getMedia(), not from
        // `info`, so it survives the delete - it is a non-owning view of pjsua's own media.
        // AUDIT H7.
        CallInfo info = null;
        try {
            info = current.getInfo();
            CallMediaInfoVector mediaVec = info.getMedia();
            for (int i = 0; i < mediaVec.size(); i++) {
                CallMediaInfo mi = mediaVec.get(i);
                if (mi.getType() == pjmedia_type.PJMEDIA_TYPE_AUDIO
                        && mi.getStatus() == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {
                    return AudioMedia.typecastFromMedia(current.getMedia(i));
                }
            }
        } catch (Exception e) {
            append("ERROR: reading media failed: " + e.getMessage());
        } finally {
            Pjsua2Lifetime.delete(info);
        }
        return null;
    }

    private void stopInternal() {
        assertMainThread("stopInternal");
        // Snapshot: the call we check is the one we hang up and dispose. owns() reads this
        // field from pjsua workers, so it must not be re-read mid-teardown.
        GatewayCall current = call;
        if (current == null) {
            return;
        }

        mainHandler.removeCallbacks(poller);
        mainHandler.removeCallbacks(autoHangup);

        if (mediaValid) {
            appendDiagnostics("final");
        }

        unwireMedia();

        CallOpParam prm = null;
        try {
            if (mediaValid && current.isActive()) {
                prm = new CallOpParam();
                prm.setStatusCode(pjsip_status_code.PJSIP_SC_DECLINE);
                current.hangup(prm);
            }
        } catch (Exception e) {
            append("hangup: " + e.getMessage());
        } finally {
            Pjsua2Lifetime.delete(prm);
        }

        // dispose() stops further callbacks; the graveyard performs the delete() later, on
        // the control thread, once pjsua has released the call slot. The comment that used to
        // sit here ("PJSIP owns the native lifecycle") was wrong in the same way
        // CallManager's was - see CallGraveyard. AUDIT H7.
        current.dispose();
        bury(current, "test call ended");
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
            // Posted, so it runs on the bridge's owning thread. It may therefore land after
            // the hangup below has destroyed the call's conference port - which is the
            // ordinary case for the gateway path too, and exactly what unwireBridge()'s
            // liveness check is there to survive.
            final long generation = bridgedGeneration;
            bridgedGeneration = AudioBridgeManager.NO_GENERATION;
            control.post(() -> audioBridge.stopBridge(generation));
        }

        wiredCallMedia = null;
    }

    private void appendDiagnostics(String label) {
        // Snapshot: the checked call is the dumped one.
        GatewayCall current = call;
        if (current == null) return;
        AudioMedia localSource = localSourceForMode();
        append(SipDiagnostics.dumpAndLog(current, localSource, label));
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

    /**
     * {@link #mode}, {@link #mediaWired}, {@link #wiredCallMedia}, {@link #bridgedGeneration},
     * {@link #toneGen},
     * {@link #recorder}, {@link #recordFile} and {@link #loopbackWired} are owned by the main
     * looper - every public entry point hops onto it before touching them, which is also what
     * makes them safe to call PJSIP from. Same shape as
     * {@code GatewayInCallService.assertMainThread}: log loudly rather than throw, so a
     * wrong-thread diagnostic call still completes and still reports.
     */
    private static void assertMainThread(String what) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.e(TAG, what + " called off the main thread ("
                    + Thread.currentThread().getName() + ") - the test call internals are main-only");
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
