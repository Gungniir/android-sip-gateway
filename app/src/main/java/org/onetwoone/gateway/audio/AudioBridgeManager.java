package org.onetwoone.gateway.audio;

import android.content.Context;
import android.util.Log;

import org.onetwoone.gateway.GatewayCall;
import org.onetwoone.gateway.GsmAudioPort;
import org.onetwoone.gateway.config.GatewayConfig;
import org.onetwoone.gateway.core.ControlThread;
import org.onetwoone.gateway.core.GatewayControlThread;
import org.onetwoone.gateway.diag.SipDiagnostics;
import org.onetwoone.gateway.sip.Pjsua2Lifetime;
import org.pjsip.pjsua2.*;

/**
 * Manages audio bridging between SIP and GSM calls.
 *
 * Uses GsmAudioPort to:
 * - Capture audio from GSM voice call (VOC_REC) and send to SIP
 * - Receive audio from SIP and play to GSM (Incall_Music)
 *
 * This is the core of the gateway's audio functionality.
 *
 * <h3>Threading (GW-12)</h3>
 * Every state-mutating entry point runs on the {@link GatewayControlThread} and asserts it.
 * That single-owner rule is not a tidiness measure - it is the only thing that makes
 * {@link #unwireBridge(Wiring)} safe. Disconnecting a conference port PJSIP has already destroyed
 * trips a pjmedia assertion, which is {@code abort()} and therefore uncatchable; the guard
 * against it is a liveness check, and a liveness check is worth nothing if another thread can
 * destroy or re-wire the port between the check and the use. See {@link #unwireBridge(Wiring)}.
 *
 * <h3>Generations (GW-12)</h3>
 * Wiring is tagged with the {@code GatewayCall.getGeneration()} it belongs to, and
 * {@link #stopBridge(long)} disconnects only if that tag still matches. Two different bugs
 * need it: a teardown for a call that has already been replaced must not disconnect its
 * successor's audio, and a stale queued {@code CONFIRMED} must not bridge a call that is no
 * longer current (AUDIT D1b - see {@link #admitGeneration}).
 *
 * <p>The read-only accessors ({@link #isBridgeActive()}, {@link #getGsmAudioPort()},
 * {@link #isAudioStreaming()}, {@link #handlesMicMute()}, {@link #getStatusString()},
 * {@link #isInitialized()}) are deliberately <em>not</em> asserted: they are read from
 * NanoHTTPD workers, from main and by the 1 Hz status snapshot. They snapshot {@link #wiring}
 * once and answer from that snapshot.
 */
public class AudioBridgeManager {
    private static final String TAG = "AudioBridge";

    /** No call: nothing is wired, and no generation has been seen yet. */
    public static final long NO_GENERATION = 0L;

    /**
     * Passed to {@link #stopBridge(long)} by a teardown that must unwire whatever is wired,
     * whichever call it belongs to: service destroy, {@code onCallsTerminated}, a config
     * reload. It also means "the gateway has no current call any more", so it resets the
     * generation high-water mark - see {@link #stopBridge(long)}.
     *
     * <p>There is deliberately no no-argument {@code stopBridge()}. Every caller has to say
     * which of the two it means, because "unwire everything" and "unwire my call" differ in
     * exactly the case the generation exists for.
     */
    public static final long ANY_GENERATION = -1L;

    /**
     * Everything that outlives a service instance: the GSM audio port <em>and</em> what it is
     * currently wired to.
     *
     * <p>This is AUDIT E2. The port used to be {@code static} (so it survives {@code onDestroy}
     * the way the pjsua {@code Endpoint} does) while {@code bridgeActive} and
     * {@code wiredCallMedia} were plain instance fields that did not. A restarted service
     * therefore saw {@code bridgeActive == false} while the static port was still wired to a
     * call from the previous instance: {@code stopBridge()} early-returned and the conference
     * links leaked for the life of the process. Keeping the three together in one
     * process-scoped holder is what makes a restarted service adopt the <em>real</em> state.
     *
     * <p>The mutable fields are owned by the control thread. {@code active} is nonetheless
     * volatile because {@link #getStatusString()} and {@link #isBridgeActive()} read it from
     * elsewhere - exactly as the old {@code bridgeActive} field did.
     */
    static final class Wiring {
        /**
         * Never replaced once published: nothing deletes the port (see {@code shutdownSip}).
         * Null only in JVM tests, which cannot construct one - the native {@code AudioMediaPort}
         * base class is not available there. Every consumer null-checks it exactly as the old
         * bare {@code gsmAudioPort} field was null-checked.
         */
        final GsmAudioPort port;

        /** True between a successful {@code startBridge} and the matching {@code stopBridge}. */
        volatile boolean active;

        /**
         * The call media currently wired to {@link #port}, kept so {@code stopBridge()} can
         * unwire exactly what {@code startBridge()} wired. Leaving conference links dangling
         * across calls is how a port ends up with a stale listener count.
         */
        @ControlThread
        AudioMedia callMedia;

        /** Conference slot of {@link #callMedia}, for logging. */
        @ControlThread
        int confSlot = -1;

        /**
         * {@code GatewayCall.getGeneration()} of the call {@link #callMedia} belongs to.
         * This is what makes "unwire exactly what we wired" hold: {@link #stopBridge(long)}
         * disconnects only if the bridge is still wired to the generation it was asked about.
         */
        @ControlThread
        long wiredGeneration = NO_GENERATION;

        /**
         * The newest <em>gateway</em> generation {@link #startBridge} has been asked to wire.
         * A request for anything older is a stale queued callback and is refused (AUDIT D1b).
         * Diagnostic calls do not move it - see {@link #admitGeneration}.
         */
        @ControlThread
        long newestGeneration = NO_GENERATION;

        Wiring(GsmAudioPort port) {
            this.port = port;
        }
    }

    /**
     * Process-scoped, like the pjsua {@code Endpoint}: a service restart reuses the port
     * rather than creating a second one against the same ALSA devices.
     *
     * <p>Written only by {@link #initialize()} on the control thread; volatile so the
     * unasserted read-only accessors get a defined value.
     */
    private static volatile Wiring wiring;

    private final Context context;
    private final GatewayConfig config;
    private final GatewayControlThread control;

    public interface BridgeListener {
        void onBridgeStarted();
        void onBridgeStopped();
        void onBridgeError(String error);
    }

    private BridgeListener listener;

    public AudioBridgeManager(Context context, GatewayConfig config, GatewayControlThread control) {
        this.context = context.getApplicationContext();
        this.config = config;
        this.control = control;
    }

    public void setListener(BridgeListener listener) {
        this.listener = listener;
    }

    /**
     * Initialize the GSM audio port.
     * Should be called once when the service starts.
     */
    @ControlThread
    public void initialize() {
        control.assertOnControlThread("AudioBridgeManager.initialize");

        Wiring existing = wiring;
        if (existing != null) {
            Log.d(TAG, "Audio port already initialized");
            adoptStaleWiring(existing);
            return;
        }

        try {
            Log.d(TAG, "Initializing GSM audio port...");

            // Create GsmAudioPort - it selects the SoC audio profile from config.
            // Published to the holder first (unchanged ordering), then driven through the
            // local so the rest of this method cannot see a different object.
            GsmAudioPort port = new GsmAudioPort(context, config);
            wiring = new Wiring(port);

            // Initialize native audio
            if (!port.initialize()) {
                Log.w(TAG, "Native audio init failed, will retry on call");
            }

            // Create PJSIP port
            port.createPort();

            Log.d(TAG, "GSM audio port initialized");

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize audio port: " + e.getMessage(), e);
            wiring = null;
        }
    }

    /**
     * AUDIT E2: a previous service instance left the bridge marked active. Its account and
     * endpoint were torn down by {@code shutdownSip()}, so the call media it was wired to is
     * gone and the flag is a lie that would otherwise sit there until something asked to wire
     * again.
     *
     * <p>Proving the ports are dead before clearing matters. If they <em>are</em> still live
     * this is not a restart at all but a re-initialisation during a live call (the
     * {@code attemptReconnect} path), and dropping the wiring state there would leave a call
     * bridged with nothing left to unwire it.
     */
    @ControlThread
    private void adoptStaleWiring(Wiring existing) {
        if (!existing.active) {
            return;
        }

        AudioMedia media = existing.callMedia;
        if (media != null && existing.port != null
                && SipDiagnostics.isLiveConfPort(media.getPortId())
                && SipDiagnostics.isLiveConfPort(existing.port.getPortId())) {
            Log.w(TAG, "Adopted a bridge that is still live - leaving it wired");
            return;
        }

        Log.w(TAG, "Adopted a stale bridge from a previous service instance"
                + " (conf slot " + existing.confSlot + " is gone) - clearing it");
        existing.callMedia = null;
        existing.confSlot = -1;
        existing.wiredGeneration = NO_GENERATION;
        existing.newestGeneration = NO_GENERATION;
        existing.active = false;
    }

    /**
     * Start audio bridge for a SIP call.
     * Connects GsmAudioPort to the call's audio media.
     */
    @ControlThread
    public void startBridge(GatewayCall call) {
        control.assertOnControlThread("startBridge");

        // Snapshot: initialize() publishes the holder, and the read-only accessors read it
        // from other threads. Every wiring step below must act on the one holder we checked.
        Wiring state = wiring;
        if (state == null || state.port == null) {
            Log.e(TAG, "Audio port not initialized");
            notifyError("Audio port not initialized");
            return;
        }
        GsmAudioPort port = state.port;

        long generation = call.getGeneration();
        if (!admitGeneration(state, call.getOwner() == GatewayCall.Owner.DIAGNOSTIC, generation)) {
            return;
        }

        // `info` is owned native memory; `mediaVec` and its elements are views INTO it
        // (CallInfo.getMedia() hands back (ptr, false)), so the delete has to be a finally
        // around the whole loop - including the early returns inside it. AUDIT H7.
        CallInfo info = null;
        try {
            info = call.getInfo();
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
                    // The generation guard on both branches is what keeps them meaning what
                    // their comments say: they are about THIS call's media stream being
                    // re-created, not about a different call arriving. A different call takes
                    // the else branch, which unwires properly.
                    if (state.active && state.wiredGeneration == generation
                            && SipDiagnostics.isTransmitting(port, confSlot)) {
                        Log.d(TAG, "Bridge already wired to conf slot " + confSlot);
                        return;
                    }
                    if (state.active && state.wiredGeneration == generation) {
                        Log.i(TAG, "Conference links lost (media stream re-created), rewiring");
                        // Deliberately no stopTransmit: the old port is already gone and
                        // its slot may have been handed to somebody else.
                        state.callMedia = null;
                        state.confSlot = -1;
                    } else if (state.active) {
                        // A DIFFERENT call. Previously this fell into the branch above and
                        // simply dropped the old links on the floor, leaving the previous
                        // call's ports listening to ours for the life of the process (E1).
                        // Here the old media is still the media we wired, so it can be
                        // disconnected properly - liveness-checked, like every other unwire.
                        Log.i(TAG, "Bridge was wired to generation " + state.wiredGeneration
                                + " - unwiring it before wiring generation " + generation);
                        unwireBridge(state);
                        state.active = false;
                        state.wiredGeneration = NO_GENERATION;
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

                    state.callMedia = audioMedia;
                    state.confSlot = confSlot;
                    state.wiredGeneration = generation;
                    state.active = true;

                    Log.i(TAG, "Audio bridge started");

                    // Prove the conference links actually took: a source port with no
                    // listener is never pulled by the bridge, so onFrameRequested would
                    // stay at zero and nothing would ever reach SIP.
                    //
                    // This used to be an unconditional dumpAndLog on every successful wiring -
                    // a PRODUCTION path running the app's single heaviest diagnostic, ~8 owned
                    // pjsua2 objects and ~20 logcat lines per call (AUDIT H7, plan §2.3). The
                    // question it exists to answer is one boolean, so ask that first and pay
                    // for the full dump only when the answer is wrong. When it IS wrong the
                    // dump is now strictly more useful, because it is no longer buried in the
                    // identical dump printed after every healthy call.
                    if (SipDiagnostics.isTransmitting(port, confSlot)) {
                        Log.i(TAG, "Conference links verified: local port " + port.getPortId()
                                + " -> call slot " + confSlot);
                    } else {
                        Log.e(TAG, "Conference links did NOT take - dumping SIP media state");
                        SipDiagnostics.dumpAndLog(call, port, "startBridge");
                    }

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
        } finally {
            Pjsua2Lifetime.delete(info);
        }
    }

    /**
     * AUDIT D1b: refuse to bridge a call that has already been superseded.
     *
     * <p>{@code CallManager.onSipCallState} fires {@code onSipCallConnected(call)} on
     * CONFIRMED without checking that {@code call} is the current one, and since GW-10 that
     * callback is posted - so a CONFIRMED belonging to a call that has since been replaced can
     * arrive after its replacement is already up. {@code startBridge} used to bridge whatever
     * it was handed. It no longer trusts the caller: the decision is made here, from the
     * call's own immutable generation, so it does not depend on {@code CallManager} being
     * fixed (GW-11 owns that file and is editing it concurrently).
     *
     * <p>A {@code call == currentSipCall} identity check would not do: identity says nothing
     * about ordering, so it cannot distinguish "the current call, again" from "a call that was
     * current when this callback was queued".
     *
     * <p>The diagnostic test call is exempt in both directions. It is operator-initiated, so
     * it is current by definition and can never be a stale queued callback; and it must not
     * advance the gateway's high-water mark, or a BRIDGE-mode test call placed during a live
     * gateway call would permanently lock that call out of re-wiring once the test ends.
     *
     * <p>Package-private, and taking the two facts rather than the {@code GatewayCall}, so a
     * JVM test can drive it - a {@code GatewayCall} cannot be constructed without pjsua2.
     */
    @ControlThread
    boolean admitGeneration(Wiring state, boolean diagnostic, long generation) {
        if (diagnostic) {
            return true;
        }
        if (generation < state.newestGeneration) {
            Log.w(TAG, "Refusing to bridge call generation " + generation
                    + ": superseded by generation " + state.newestGeneration);
            return false;
        }
        state.newestGeneration = generation;
        return true;
    }

    /**
     * Unwire the bridge, if it is still wired to {@code generation}.
     *
     * @param generation the {@code GatewayCall.getGeneration()} whose wiring the caller wants
     *                   dropped, or {@link #ANY_GENERATION} for a teardown that must drop
     *                   whatever is wired. Anything else is a no-op, which is the point:
     *                   a teardown for a call that has already been replaced must not
     *                   disconnect its successor's audio.
     */
    @ControlThread
    public void stopBridge(long generation) {
        control.assertOnControlThread("stopBridge");

        Wiring state = wiring;
        if (state == null || !state.active) {
            return;
        }

        if (generation != ANY_GENERATION && generation != state.wiredGeneration) {
            Log.d(TAG, "Not unwiring: the bridge belongs to generation "
                    + state.wiredGeneration + ", not " + generation);
            return;
        }

        Log.d(TAG, "Stopping audio bridge");

        unwireBridge(state);
        state.active = false;
        state.wiredGeneration = NO_GENERATION;
        if (generation == ANY_GENERATION) {
            // A full teardown means the gateway has no current call, so nothing can be stale
            // relative to one. Leaving the high-water mark up would refuse the next call
            // after a process-scoped counter had moved on elsewhere.
            state.newestGeneration = NO_GENERATION;
        }

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
     * ("src_slot&lt;conf-&gt;max_ports"), and that is an abort() rather than a Java exception,
     * so the catch blocks below cannot contain it. Both slots must be proven live first;
     * the catches only cover ordinary pjsua2 errors.
     *
     * <p><b>Why the liveness check is worth anything (GW-12 §4).</b> A liveness check only
     * protects the {@code stopTransmit} that follows it if nothing can destroy or re-wire
     * either port in between. That is not a property of this code - it is a property of the
     * threading model: every path that creates, destroys or re-wires these links
     * ({@link #startBridge}, {@link #stopBridge}, the pjsua call-state callbacks that hang
     * calls up) runs on the control thread, and so does this method. The two statements are
     * therefore adjacent on one thread with no window between them. It is not incidental, and
     * it is the reason a {@code try/catch} here would prove nothing: the failure mode is
     * {@code abort()}.
     */
    @ControlThread
    private void unwireBridge(Wiring state) {
        control.assertOnControlThread("unwireBridge");

        AudioMedia media = state.callMedia;
        GsmAudioPort port = state.port;
        state.callMedia = null;
        state.confSlot = -1;

        if (media == null || port == null) {
            return;
        }

        // Ask the objects themselves rather than trusting the recorded slot: these are the
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
     *
     * <p>Returns immediately: {@code GsmAudioPort.startCapture()} hands the ALSA open to its
     * own worker, deliberately - see that class's {@code GsmAudioOpen} note for why the retry
     * window must not sit on this thread.
     */
    @ControlThread
    public void startAudioStreams() {
        control.assertOnControlThread("startAudioStreams");

        Wiring state = wiring;
        if (state == null || state.port == null) {
            Log.w(TAG, "Audio port not initialized, cannot start streams");
            return;
        }

        try {
            state.port.startCapture();
            Log.d(TAG, "Audio streams started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start audio streams: " + e.getMessage());
        }
    }

    /**
     * Stop the underlying audio streams.
     */
    @ControlThread
    public void stopAudioStreams() {
        control.assertOnControlThread("stopAudioStreams");

        Wiring state = wiring;
        if (state == null || state.port == null) {
            return;
        }

        try {
            state.port.stopCapture();
            Log.d(TAG, "Audio streams stopped");
        } catch (Exception e) {
            Log.e(TAG, "Error stopping audio streams: " + e.getMessage());
        }
    }

    /**
     * Check if bridge is currently active.
     */
    public boolean isBridgeActive() {
        Wiring state = wiring;
        return state != null && state.active;
    }

    /**
     * Check if audio port is initialized.
     */
    public boolean isInitialized() {
        Wiring state = wiring;
        return state != null && state.port != null;
    }

    /** The GSM audio port, or null before {@link #initialize()}. For diagnostics. */
    public GsmAudioPort getGsmAudioPort() {
        Wiring state = wiring;
        return state == null ? null : state.port;
    }

    /** True when the ALSA capture/playback devices are open (i.e. a GSM call is up). */
    public boolean isAudioStreaming() {
        // Snapshot: read from main and from pjsua workers.
        Wiring state = wiring;
        return state != null && state.port != null && state.port.isCapturing();
    }

    /**
     * Whether the active SoC audio profile already mutes the local mic as part of
     * its mixer routing. When true, callers must NOT run DeviceMuteManager.
     * Defaults to false if the port isn't initialized yet.
     */
    public boolean handlesMicMute() {
        // Snapshot: called from the Telecom callback path; the holder is published from the
        // control thread.
        Wiring state = wiring;
        if (state == null || state.port == null) {
            return false;
        }
        AudioProfile profile = state.port.getProfile();
        return profile != null && profile.handlesMicMute();
    }

    /**
     * Get status string for debugging.
     */
    public String getStatusString() {
        // Snapshot: read from NanoHTTPD workers and from the 1 Hz UI poll on main.
        Wiring state = wiring;
        if (state == null) {
            return "Not initialized";
        }
        return state.active ? "Bridge active" : "Idle";
    }

    private void notifyError(String error) {
        if (listener != null) {
            listener.onBridgeError(error);
        }
    }

    // ========== Test hooks ==========

    /** Visible for tests only: the process-scoped holder, or null before initialize(). */
    static Wiring wiringForTest() {
        return wiring;
    }

    /**
     * Visible for tests only: seed or clear the process-scoped holder. Production never
     * replaces it - that is the point of E2 - so this exists purely so a JVM test can put the
     * holder into the state a previous service instance would have left behind.
     */
    static void setWiringForTest(Wiring state) {
        wiring = state;
    }
}
