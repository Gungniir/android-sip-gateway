package org.onetwoone.gateway.call;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;

import org.onetwoone.gateway.GatewayCall;
import org.onetwoone.gateway.GatewayAccount;
import org.onetwoone.gateway.GatewayInCallService;
import org.onetwoone.gateway.config.GatewayConfig;
import org.onetwoone.gateway.core.ControlThread;
import org.onetwoone.gateway.core.GatewayControlThread;
import org.onetwoone.gateway.sip.Pjsua2Lifetime;
import org.pjsip.pjsua2.*;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The gateway's call state machine: one thread, one explicit transition table.
 *
 * <h3>Call flows</h3>
 * <ol>
 *   <li><b>SIP → GSM</b> - an INVITE from the PBX is answered and a GSM call is dialled:
 *       {@code IDLE → SIP_INCOMING → SIP_ANSWERED → GSM_DIALING → BRIDGED}.
 *   <li><b>GSM → SIP</b> - an inbound GSM call provokes an outgoing INVITE:
 *       {@code IDLE → GSM_INCOMING → SIP_DIALING → BRIDGED}.
 * </ol>
 * The two directions used to share {@code SIP_INCOMING}, which is why the UI reported an
 * inbound GSM call as "Incoming SIP call". GW-11 split them (AUDIT D1).
 *
 * <h3>Threading</h3>
 * Every public method runs on the {@link GatewayControlThread} and says so with
 * {@link GatewayControlThread#assertOnControlThread(String)}. Off-thread readers go through
 * the immutable {@link org.onetwoone.gateway.core.GatewayStatus} snapshot instead. The one
 * deliberate exception is {@link #setListener}, which is construction-time wiring - see its
 * javadoc.
 *
 * <p>Because the thread is single, nothing in here is synchronised: the {@code synchronized}
 * that used to sit on {@link #hangupSipCall()} protected nothing (its actual caller,
 * {@link #terminateAllCalls()}, never took the monitor) while holding it across a pjsua2 BYE,
 * a Telecom {@code disconnectCall()} and a ~250 ms native drain. See plan §3c.
 */
@ControlThread
public class CallManager {
    private static final String TAG = "CallMgr";

    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[0-9]{10,15}$");

    /**
     * How long after a GSM dial the watchdog must not call a SIP call orphaned.
     * Public because {@code GatewayStatus.isInGracePeriod()} derives the same deadline from
     * the raw timestamp rather than freezing a boolean into the snapshot (plan §2.7).
     */
    public static final long GSM_CALL_GRACE_PERIOD_MS = 5000;

    private final Context context;
    private final GatewayConfig config;
    private final GsmDtmfSender dtmfSender;
    private final GatewayControlThread control;

    /**
     * Where finished gateway {@code Call} objects go to be deleted on the control thread
     * instead of on the FinalizerDaemon. See {@link CallGraveyard} - including why its timing
     * is a heuristic and what to revert if it misbehaves on hardware. AUDIT H7.
     */
    @ControlThread
    private final CallGraveyard graveyard;

    // Current calls. Confined to the control thread: every writer and every reader asserts
    // it. `volatile` is kept deliberately - assertOnControlThread throws only in debug
    // builds, and in release it merely logs, so a stray off-thread read must at least be a
    // well-defined one rather than a torn 64-bit long or an indefinitely stale reference.
    @ControlThread
    private volatile GatewayCall currentSipCall;
    @ControlThread
    private volatile String pendingGsmDestination;
    @ControlThread
    private volatile int pendingGsmSimSlot = 1;
    @ControlThread
    private volatile long gsmCallPlacedTime = 0;

    /**
     * Identity of the GSM leg this machine is driving, or
     * {@link GatewayInCallService#NO_GSM_CALL} (GW-13 §3).
     *
     * <p>Adopted from the first identified GSM event for a leg - {@code onIncomingGsmCall} for
     * the GSM→SIP direction, {@code onGsmCallConnected} for SIP→GSM, where no
     * {@code android.telecom.Call} exists yet at dial time - and cleared by
     * {@link #terminateAllCalls()}. Its only job is to let a lifecycle event that names a
     * *different* leg be dropped instead of moving the machine: without it, a
     * {@code DISCONNECTED} for call 1 arriving after call 2 has connected terminates call 2,
     * and no amount of ordering discipline in the callers can prevent that.
     */
    @ControlThread
    private volatile long currentGsmCallId = GatewayInCallService.NO_GSM_CALL;

    /**
     * The state machine's alphabet. The legal edges between these are
     * {@link #LEGAL_TRANSITIONS}; nothing may assign {@link #state} except
     * {@link #transition}.
     */
    public enum CallState {
        IDLE,
        /** SIP→GSM: an INVITE arrived from the PBX and has not been answered yet. */
        SIP_INCOMING,
        /** SIP→GSM: the SIP leg is answered, the GSM dial is pending. */
        SIP_ANSWERED,
        /** SIP→GSM: the GSM leg is dialling. */
        GSM_DIALING,
        /** GSM→SIP: a GSM call is ringing and the SIP dial is pending. */
        GSM_INCOMING,
        /** GSM→SIP: the outgoing INVITE has gone to the PBX. */
        SIP_DIALING,
        /** Both legs are connected and the audio bridge is up. */
        BRIDGED,
        /** Both legs are being torn down. */
        TERMINATING
    }

    /**
     * The transition table (GW-11 §2). Every edge the state machine may take, and no others.
     *
     * <p>{@code IDLE} is deliberately <em>not</em> a source for {@code TERMINATING}: there is
     * nothing to terminate, and {@link #terminateAllCalls()} returns before it gets that far.
     * That early return is what makes termination idempotent, so a second
     * {@code terminateAllCalls()} cannot fire {@code onCallsTerminated()} a second time.
     */
    private static final Map<CallState, Set<CallState>> LEGAL_TRANSITIONS = legalTransitions();

    private static Map<CallState, Set<CallState>> legalTransitions() {
        Map<CallState, Set<CallState>> t = new EnumMap<>(CallState.class);
        t.put(CallState.IDLE,         EnumSet.of(CallState.SIP_INCOMING, CallState.GSM_INCOMING));
        t.put(CallState.SIP_INCOMING, EnumSet.of(CallState.SIP_ANSWERED, CallState.TERMINATING));
        t.put(CallState.GSM_INCOMING, EnumSet.of(CallState.SIP_DIALING,  CallState.TERMINATING));
        t.put(CallState.SIP_ANSWERED, EnumSet.of(CallState.GSM_DIALING,  CallState.TERMINATING));
        t.put(CallState.SIP_DIALING,  EnumSet.of(CallState.BRIDGED,      CallState.TERMINATING));
        t.put(CallState.GSM_DIALING,  EnumSet.of(CallState.BRIDGED,      CallState.TERMINATING));
        t.put(CallState.BRIDGED,      EnumSet.of(CallState.TERMINATING));
        t.put(CallState.TERMINATING,  EnumSet.of(CallState.IDLE));
        return Collections.unmodifiableMap(t);
    }

    /** Never assigned outside {@link #transition}. See {@link #currentSipCall} on volatility. */
    @ControlThread
    private volatile CallState state = CallState.IDLE;

    public interface CallListener {
        void onCallStateChanged(CallState state);
        void onSipCallConnected(GatewayCall call);
        void onGsmCallNeeded(String destination, int simSlot);
        void onSipCallNeeded(String destination, String callerId, int simSlot);
        void onCallsTerminated();
        void onError(String error);
    }

    private CallListener listener;

    public CallManager(Context context, GatewayConfig config, GatewayControlThread control) {
        this(context, config, control, new GsmDtmfSender());
    }

    // Visible for testing.
    CallManager(Context context, GatewayConfig config, GatewayControlThread control,
                GsmDtmfSender dtmfSender) {
        if (control == null) {
            throw new IllegalArgumentException("CallManager needs the control thread to assert on");
        }
        this.context = context.getApplicationContext();
        this.config = config;
        this.control = control;
        this.dtmfSender = dtmfSender;
        this.graveyard = new CallGraveyard(control);
    }

    /**
     * Hand a finished call to the graveyard. Idempotent by identity, so a call that reaches
     * two burial sites is buried once.
     *
     * <p>The caller must already have disposed it and dropped every reference to it. Public
     * because {@code PjsipSipService} owns one path - an incoming call disposed before it could
     * be handled - that never reaches this class any other way.
     */
    @ControlThread
    public void buryCall(GatewayCall call, String why) {
        control.assertOnControlThread("CallManager.buryCall");
        graveyard.bury(call, why);
    }

    /**
     * How many calls the graveyard has deleted deterministically. Visible for testing - the
     * status snapshot publishes {@code GatewayCall}'s process-wide counters instead, because
     * those also catch a deletion the finalizer performed, which is the failure this is
     * watching for.
     */
    @ControlThread
    long getCallsBuried() {
        return graveyard.getDeletedCount();
    }

    /**
     * Wire up the one listener. <b>The only public method here that does not assert the
     * control thread</b>, and deliberately so: it is called from {@code Service.onCreate} on
     * main, before the control thread has been given any work, and moving it onto the control
     * thread would open a window in which a callback fires with no listener attached. It is
     * construction-time wiring, not a runtime mutator - do not call it again once the service
     * is running.
     *
     * <p>Publication is safe by the same happens-before that publishes the manager itself:
     * every control-thread task is queued after this call, and {@code Handler.post} is a
     * happens-before edge.
     */
    public void setListener(CallListener listener) {
        this.listener = listener;
    }

    // ========== Transitions ==========

    /**
     * The single writer of {@link #state} (GW-11 §2).
     *
     * <p>Rejects, loudly and without throwing, in two cases:
     * <ul>
     *   <li>the machine is not in {@code from} - the caller reasoned from a state that has
     *       since moved;
     *   <li>{@code from → to} is not in {@link #LEGAL_TRANSITIONS}.
     * </ul>
     * A rejection is a bug in the caller or in the table, never a reason to kill a live
     * gateway, so it is a log line and a no-op. Run the call matrix on a debug build and
     * treat every {@code ILLEGAL TRANSITION} line as a finding.
     *
     * @return true if the state actually moved
     */
    @ControlThread
    private boolean transition(CallState from, CallState to, String reason) {
        CallState current = state;
        if (current != from) {
            Log.e(TAG, "ILLEGAL TRANSITION " + from + " -> " + to + " (" + reason
                    + "): machine is in " + current + ", not " + from);
            return false;
        }
        Set<CallState> allowed = LEGAL_TRANSITIONS.get(from);
        if (allowed == null || !allowed.contains(to)) {
            Log.e(TAG, "ILLEGAL TRANSITION " + from + " -> " + to + " (" + reason
                    + "): not in the transition table, ignored");
            return false;
        }

        state = to;
        Log.d(TAG, "State " + from + " -> " + to + " (" + reason + ")");
        notifyStateChanged();
        return true;
    }

    // ========== State Accessors ==========

    @ControlThread
    public CallState getState() {
        control.assertOnControlThread("CallManager.getState");
        return state;
    }

    @ControlThread
    public GatewayCall getCurrentSipCall() {
        control.assertOnControlThread("CallManager.getCurrentSipCall");
        return currentSipCall;
    }

    @ControlThread
    public String getPendingGsmDestination() {
        control.assertOnControlThread("CallManager.getPendingGsmDestination");
        return pendingGsmDestination;
    }

    @ControlThread
    public int getPendingGsmSimSlot() {
        control.assertOnControlThread("CallManager.getPendingGsmSimSlot");
        return pendingGsmSimSlot;
    }

    @ControlThread
    public boolean hasActiveCall() {
        control.assertOnControlThread("CallManager.hasActiveCall");
        return state != CallState.IDLE;
    }

    /**
     * True while the GSM leg is still inside its post-dial grace period, re-read from the
     * clock every time (GW-11 §6). The watchdog depends on this staying derived: frozen into
     * a boolean it would keep an orphaned call invisible for as long as the freeze lived.
     */
    @ControlThread
    public boolean isInGracePeriod() {
        control.assertOnControlThread("CallManager.isInGracePeriod");
        long placedAt = gsmCallPlacedTime;
        if (placedAt == 0) return false;
        return System.currentTimeMillis() - placedAt < GSM_CALL_GRACE_PERIOD_MS;
    }

    /**
     * Wall-clock instant the current GSM call was placed, or 0.
     *
     * <p>Exists so {@link org.onetwoone.gateway.core.GatewayStatus} can carry the raw
     * timestamp and derive the grace period from it, instead of freezing
     * {@link #isInGracePeriod()} into a snapshot that would then answer "yes" for as long as
     * the snapshot lives (plan §2.7, trap 1).
     */
    @ControlThread
    public long getGsmCallPlacedAtWallMs() {
        control.assertOnControlThread("CallManager.getGsmCallPlacedAtWallMs");
        return gsmCallPlacedTime;
    }

    /**
     * True when a SIP call is registered <em>and</em> has not been disposed.
     *
     * Callers that only want to know "is the gateway busy on SIP right now" must ask this
     * rather than {@code getCurrentSipCall() != null}: a disposed leftover is not a call in
     * progress, and treating it as one is what used to block every diagnostic call.
     */
    @ControlThread
    public boolean hasLiveSipCall() {
        control.assertOnControlThread("CallManager.hasLiveSipCall");
        GatewayCall call = currentSipCall;
        return call != null && !call.isDisposed();
    }

    // ========== SIP Call Handling ==========

    /**
     * How the SIP layer actually places a call that has already been registered.
     *
     * Kept as a callback so that the register-before-dial ordering in
     * {@link #placeOutgoingSipCall} lives in exactly one place and cannot be got wrong -
     * or "tidied" back into the wrong order - at a call site.
     */
    public interface SipCallPlacer {
        void place(GatewayCall call) throws Exception;
    }

    /**
     * Place an outgoing SIP call (GSM→SIP direction), registering it <em>before</em> it is
     * dialled.
     *
     * PJSIP still delivers {@code onCallState(PJSIP_INV_STATE_DISCONNECTED)} synchronously,
     * on the dialling thread, from inside {@code makeCall()} - an immediate transport
     * failure, a 403/404 from the PBX, or a local pjsua rejection. What changed with GW-10
     * is where that callback is <em>handled</em>: {@code PjsipSipService.onCallState} now
     * does {@code control.post(...)}, so the handling runs as a later task rather than
     * re-entering this method from underneath. Register-before-dial is still mandatory -
     * only now because the queued handler must find the call already registered, rather than
     * because it might run inside {@code makeCall()}. If the call were registered only
     * afterwards, the handler would find {@code currentSipCall == null}, skip the clear, and
     * the assignment would then store an already-dead call as the current one: state machine
     * at {@code IDLE} with a non-null disposed {@code currentSipCall}, blocking every later
     * diagnostic call. See docs/refactor/AUDIT.md D2.
     *
     * <p>The ordering is only airtight while the dial itself runs on the control thread, so
     * that the queued handler is behind it in the same queue.
     * {@code PjsipSipService.makeSipCallWithCallerId} asserts exactly that.
     *
     * <p>Inline delivery has not gone away and this method must keep tolerating it: PJSIP can
     * still call straight back on the dialling thread, and the unit tests drive that path
     * deliberately.
     *
     * @return true if the call was registered and handed to PJSIP without an immediate
     *         exception; false if it was refused or failed to start (state is clean either
     *         way)
     */
    @ControlThread
    public boolean placeOutgoingSipCall(GatewayCall call, SipCallPlacer placer) {
        control.assertOnControlThread("CallManager.placeOutgoingSipCall");
        if (!setOutgoingSipCall(call)) {
            return false;
        }

        try {
            placer.place(call);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to place outgoing SIP call: " + e.getMessage());
            onOutgoingCallFailed(call);
            notifyError("Failed to place SIP call: " + e.getMessage());
            return false;
        }
    }

    /**
     * Register an outgoing SIP call (for GSM→SIP direction) so it can be tracked and cleaned
     * up.
     *
     * This must happen <em>before</em> the INVITE goes out - prefer
     * {@link #placeOutgoingSipCall}, which enforces that.
     *
     * <p>When this is the SIP leg of an inbound GSM call - the only way it is reached in
     * production - it also moves the machine {@code GSM_INCOMING → SIP_DIALING}. From any
     * other state the slot is claimed without a transition: registering a call object is not
     * on its own a state change, and the D2 regression tests register calls from states the
     * table has no edge out of.
     *
     * @return true if the call is now the current one; false if a live call already holds the
     *         slot, in which case the new call must not be placed
     */
    @ControlThread
    public boolean setOutgoingSipCall(GatewayCall call) {
        control.assertOnControlThread("CallManager.setOutgoingSipCall");
        if (call == null) {
            Log.w(TAG, "Ignoring null outgoing SIP call");
            return false;
        }

        GatewayCall existing = currentSipCall;
        if (existing != null && existing != call) {
            if (!existing.isDisposed()) {
                // Overwriting would strand the old call: nothing else holds a reference to
                // it, so it could never be hung up. Refuse the new one instead.
                Log.e(TAG, "Refusing to replace a live SIP call with a new outgoing one");
                return false;
            }
            Log.w(TAG, "Replacing a disposed SIP call reference");
        }

        currentSipCall = call;
        Log.d(TAG, "Outgoing SIP call registered before dialling");

        if (state == CallState.GSM_INCOMING) {
            transition(CallState.GSM_INCOMING, CallState.SIP_DIALING,
                    "INVITE going out for the inbound GSM call");
        }
        return true;
    }

    /**
     * An outgoing SIP call never got off the ground - {@code makeCall} threw.
     *
     * Compare-and-clear: the call is unregistered only if it is <em>still</em> the current
     * one. On the posted path (GW-10) a DISCONNECTED for this call is queued on the control
     * thread and runs after this method, so the interleaving this guards against is no longer
     * the common one - but the guard stays, for two reasons that are still live: PJSIP can
     * deliver inline on the dialling thread (the unit tests model exactly that), and
     * {@code terminateAllCalls()} below clears the field from inside this very method.
     */
    @ControlThread
    public void onOutgoingCallFailed(GatewayCall call) {
        control.assertOnControlThread("CallManager.onOutgoingCallFailed");
        if (call == null) {
            return;
        }

        // Snapshot: an inline DISCONNECTED from inside makeCall may already have cleared the
        // field on this very thread.
        GatewayCall registered = currentSipCall;
        if (registered != call) {
            Log.d(TAG, "Failed outgoing SIP call is no longer the current one, nothing to clear");
            disposeQuietly(call);
            return;
        }

        Log.w(TAG, "Outgoing SIP call failed before it connected, resetting");

        if (state != CallState.IDLE) {
            // Clears currentSipCall via hangupSipCall(), drops the GSM leg and returns to IDLE.
            terminateAllCalls();
        }

        // Nothing was torn down (already IDLE), so unregister by hand. Deliberately a FRESH
        // read, not the snapshot above: terminateAllCalls() a few lines up clears the field
        // through hangupSipCall(), and that in-method clear - not a cross-thread one - is
        // what this second check is asking about. Still necessary after GW-10.
        if (currentSipCall == call) {
            currentSipCall = null;
            disposeQuietly(call);
        }
    }

    private void disposeQuietly(GatewayCall call) {
        try {
            call.dispose();
        } catch (Exception e) {
            Log.w(TAG, "Error disposing SIP call: " + e.getMessage());
        }
        // An outgoing call whose makeCall() threw never took a pjsua slot, so its id is still
        // PJSUA_INVALID_ID from construction and the graveyard will free it on the first
        // sweep. Before GW-22 nothing ever did.
        graveyard.bury(call, "outgoing call failed");
    }

    /**
     * Handle incoming SIP call.
     * This will answer the SIP call and extract destination for GSM call.
     *
     * @param simSlotHint SIM slot the PBX requested via X-GSM-SIM, or 0 to fall back to
     *                    deriving the slot from the caller extension
     */
    @ControlThread
    public void onIncomingSipCall(GatewayCall call, int simSlotHint) {
        control.assertOnControlThread("CallManager.onIncomingSipCall");
        if (state != CallState.IDLE) {
            Log.w(TAG, "Already have active call, rejecting incoming");
            rejectCall(call);
            return;
        }

        currentSipCall = call;
        transition(CallState.IDLE, CallState.SIP_INCOMING, "INVITE received from the PBX");

        // Owned native memory (AUDIT H7). extractCallDetails() reads through it, so the
        // delete waits until that has returned.
        CallInfo info = null;
        try {
            // Get call info to extract destination
            info = call.getInfo();
            String remoteUri = info.getRemoteUri();

            Log.d(TAG, "Incoming SIP call from: " + remoteUri);

            // Extract GSM destination and SIM slot from headers or URI
            extractCallDetails(call, info, simSlotHint);

            // Answer the call
            answerSipCall(call);

        } catch (Exception e) {
            Log.e(TAG, "Error handling incoming SIP call: " + e.getMessage());
            terminateAllCalls();
            notifyError("Failed to handle incoming call: " + e.getMessage());
        } finally {
            Pjsua2Lifetime.delete(info);
        }
    }

    /**
     * Extract destination number and SIM slot from SIP call.
     */
    private void extractCallDetails(GatewayCall call, CallInfo info, int simSlotHint) throws Exception {
        // SIP URIs:
        // - remoteUri = caller (e.g., "102" <sip:102@server>)
        // - localUri = called destination (e.g., <sip:+79810293335@server>)

        String remoteUri = info.getRemoteUri();
        String localUri = info.getLocalUri();

        // Extract destination from LOCAL URI (the number being called = GSM destination)
        String dest = extractPhoneNumber(localUri);

        // The PBX picks the SIM with X-GSM-SIM. Without that header - an older dialplan, or a
        // call that reached us some other way - derive it from the caller extension instead.
        int simSlot = simSlotHint;
        String simSource = "header";
        if (simSlot == 0) {
            simSlot = config.getSimSlotForCaller(extractExtension(remoteUri));
            simSource = "caller ext";
        }

        if (dest == null || dest.isEmpty()) {
            Log.e(TAG, "No destination in localUri: " + localUri);
            throw new IllegalStateException("No destination number found in call");
        }

        pendingGsmDestination = dest;
        pendingGsmSimSlot = simSlot;

        Log.d(TAG, "Call details: dest=" + dest + ", SIM=" + simSlot + " (from " + simSource + ")");
    }

    /**
     * Answer a SIP call.
     */
    private void answerSipCall(GatewayCall call) {
        // Java-created, so ours to delete (AUDIT H7). answer() marshals it synchronously and
        // retains nothing, so it goes as soon as that has returned - and not before, because
        // the listener notification further down re-enters this class.
        CallOpParam prm = null;
        try {
            prm = new CallOpParam();
            prm.setStatusCode(pjsip_status_code.PJSIP_SC_OK);
            call.answer(prm);
            Pjsua2Lifetime.delete(prm);
            prm = null;

            transition(CallState.SIP_INCOMING, CallState.SIP_ANSWERED, "SIP leg answered");

            Log.d(TAG, "SIP call answered");

            // Notify that GSM call is needed. Read once, so the null check and the number
            // actually dialled cannot disagree: the listener below re-enters this class
            // (placeGsmCall, and terminateAllCalls if it fails), and terminateAllCalls nulls
            // this field.
            String destination = pendingGsmDestination;
            if (listener != null && destination != null) {
                listener.onGsmCallNeeded(destination, pendingGsmSimSlot);
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to answer SIP call: " + e.getMessage());
            terminateAllCalls();
            notifyError("Failed to answer call: " + e.getMessage());
        } finally {
            Pjsua2Lifetime.delete(prm);
        }
    }

    /**
     * Reject a SIP call.
     */
    private void rejectCall(GatewayCall call) {
        CallOpParam prm = null;
        try {
            prm = new CallOpParam();
            prm.setStatusCode(pjsip_status_code.PJSIP_SC_BUSY_HERE);
            call.hangup(prm);
        } catch (Exception e) {
            Log.e(TAG, "Error rejecting call: " + e.getMessage());
        } finally {
            Pjsua2Lifetime.delete(prm);
        }
    }

    /**
     * Handle SIP call state change.
     */
    @ControlThread
    public void onSipCallState(GatewayCall call, int pjsipState) {
        control.assertOnControlThread("CallManager.onSipCallState");
        Log.d(TAG, "SIP call state: " + pjsipState);

        if (pjsipState == pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED) {
            // Call connected
            if (listener != null) {
                listener.onSipCallConnected(call);
            }
        } else if (pjsipState == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) {
            // Call ended
            Log.d(TAG, "SIP call disconnected");

            // Clear reference first (may already be null if call was from somewhere else)
            boolean wasCurrent = (currentSipCall == call);
            if (wasCurrent) {
                currentSipCall = null;
            }

            // Identity guard (GW-10). The clear above always had one; the teardown below did
            // not. That was survivable only while this callback ran inline on the pjsua
            // worker. Now it arrives by control.post(...), so a DISCONNECTED queued for call
            // A can be handled after call B has taken the slot - and an unguarded
            // terminateAllCalls() would tear down a live, unrelated call B on A's news.
            // Only the call that actually holds the slot may end the session.
            if (wasCurrent && state != CallState.IDLE) {
                terminateAllCalls();
            } else if (!wasCurrent && state != CallState.IDLE) {
                Log.d(TAG, "Disconnect is for a call that no longer holds the slot, "
                        + "leaving the current session alone");
            }

            // The call is finished and nothing holds it any more. This used to read "DON'T
            // delete the call object - PJSIP manages the native lifecycle ... it will be GC'd
            // eventually", which had it backwards: being GC'd is exactly the problem, because
            // the finalizer runs delete_Call on a thread pjlib does not know. The graveyard
            // does the same deletion later, on this thread, and only once pjsua has released
            // the call slot. AUDIT H7; see CallGraveyard for why that is a heuristic.
            // Deliberately unguarded by wasCurrent: a rejected incoming call never held the
            // slot, and it is just as finished.
            graveyard.bury(call, "DISCONNECTED");
        }
    }

    /**
     * Handle a DTMF digit pressed on the SIP leg (typically a voice menu on the far end of
     * the GSM call asking the caller to press something). The digit is replayed on the GSM
     * leg out-of-band via Telecom.
     */
    @ControlThread
    public void onSipDtmf(String digit) {
        control.assertOnControlThread("CallManager.onSipDtmf");
        if (!config.isDtmfRelayEnabled()) {
            Log.d(TAG, "DTMF relay disabled, ignoring '" + digit + "'");
            return;
        }

        if (state == CallState.IDLE || state == CallState.TERMINATING) {
            Log.d(TAG, "No call in progress, ignoring DTMF '" + digit + "'");
            return;
        }

        dtmfSender.enqueue(digit);
    }

    // ========== GSM Call Handling ==========

    /**
     * Place a GSM call.
     */
    @ControlThread
    public void placeGsmCall(String number, int simSlot) {
        control.assertOnControlThread("CallManager.placeGsmCall");
        if (!isValidPhoneNumber(number)) {
            Log.e(TAG, "Invalid phone number: " + number);
            notifyError("Invalid phone number");
            return;
        }

        Log.d(TAG, "Placing GSM call to " + number + " via SIM" + simSlot);

        // Timestamp first: transition() notifies the listener synchronously, and the status
        // snapshot that gets published from there must already carry the dial instant or its
        // derived grace period reads "expired" for the whole first publish.
        gsmCallPlacedTime = System.currentTimeMillis();
        transition(CallState.SIP_ANSWERED, CallState.GSM_DIALING, "dialling the GSM leg");

        try {
            TelecomManager telecomManager = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
            if (telecomManager == null) {
                throw new IllegalStateException("TelecomManager not available");
            }

            Uri uri = Uri.fromParts("tel", number, null);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Use placeCall with phone account for SIM selection
                PhoneAccountHandle accountHandle = getPhoneAccountForSim(simSlot);

                android.os.Bundle extras = new android.os.Bundle();
                if (accountHandle != null) {
                    extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accountHandle);
                }

                telecomManager.placeCall(uri, extras);
            } else {
                // Legacy: use ACTION_CALL intent
                Intent callIntent = new Intent(Intent.ACTION_CALL, uri);
                callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(callIntent);
            }

            Log.d(TAG, "GSM call initiated");

        } catch (Exception e) {
            Log.e(TAG, "Failed to place GSM call: " + e.getMessage());
            notifyError("Failed to place GSM call: " + e.getMessage());
            terminateAllCalls();
        }
    }

    /**
     * Get PhoneAccountHandle for specific SIM slot.
     */
    private PhoneAccountHandle getPhoneAccountForSim(int simSlot) {
        try {
            TelecomManager telecomManager = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
            SubscriptionManager subManager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);

            if (telecomManager == null || subManager == null) {
                return null;
            }

            List<PhoneAccountHandle> accounts = telecomManager.getCallCapablePhoneAccounts();
            List<SubscriptionInfo> subs = subManager.getActiveSubscriptionInfoList();

            if (accounts == null || subs == null) {
                return null;
            }

            // Find subscription for the requested slot
            for (SubscriptionInfo sub : subs) {
                if (sub.getSimSlotIndex() + 1 == simSlot) {
                    int subId = sub.getSubscriptionId();

                    // Find matching phone account
                    for (PhoneAccountHandle account : accounts) {
                        String id = account.getId();
                        if (id.contains(String.valueOf(subId))) {
                            return account;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error getting phone account: " + e.getMessage());
        }

        return null;
    }

    /**
     * Handle GSM call connected.
     * This is called by the service when the GSM leg {@code gsmCallId} becomes active.
     *
     * <p>Idempotent per leg (GW-13 §4): a second connect for the leg already bridged is a
     * logged no-op, and a connect naming a leg that is not the current one is dropped.
     *
     * @param gsmCallId identity of the GSM leg, or {@link GatewayInCallService#NO_GSM_CALL}
     *                  when the caller cannot name one
     */
    @ControlThread
    public void onGsmCallConnected(long gsmCallId) {
        control.assertOnControlThread("CallManager.onGsmCallConnected");

        if (!ownsGsmCall(gsmCallId)) {
            Log.w(TAG, "GSM connect for call " + gsmCallId + " while " + currentGsmCallId
                    + " is the current leg - ignoring");
            return;
        }
        if (gsmCallId != GatewayInCallService.NO_GSM_CALL) {
            // SIP→GSM adopts here: at placeGsmCall() time Telecom has not created the Call
            // yet, so this is the first event that can name the leg.
            currentGsmCallId = gsmCallId;
        }

        Log.d(TAG, "GSM call connected (gsmCallId=" + gsmCallId + ")");

        if (state == CallState.BRIDGED) {
            Log.d(TAG, "Already bridged - ignoring duplicate GSM connect");
            return;
        }

        // Both directions end here. SIP→GSM arrives from GSM_DIALING (we dialled out);
        // GSM→SIP from SIP_DIALING (the inbound GSM call was answered once the PBX picked
        // up). The second edge is new: before the SIP_INCOMING split there was no state to
        // recognise it by, so an inbound GSM call never reached BRIDGED and the UI showed
        // "Incoming SIP call" for the whole conversation.
        if (state == CallState.GSM_DIALING || state == CallState.SIP_DIALING) {
            transition(state, CallState.BRIDGED, "both legs up");
        } else {
            Log.d(TAG, "GSM connect in state " + state + " - nothing to bridge");
        }
    }

    /**
     * Handle GSM call ended.
     *
     * <p>An end naming a leg that is not the current one is ignored and logged (GW-13 §3):
     * that is the stale-stop scenario - call 1's teardown arriving after call 2 has already
     * connected - and identity is the only thing that can tell the two apart, ordering
     * guarantees being unavailable.
     *
     * <p>Note this decides only the <em>state machine's</em> teardown. The audio streams are
     * stopped by {@code PjsipSipService} independently of anything here, because a leg that
     * never reached {@link CallState#BRIDGED} leaves this method with nothing to terminate
     * (plan §3d).
     *
     * @param gsmCallId identity of the GSM leg, or {@link GatewayInCallService#NO_GSM_CALL}
     *                  when the caller cannot name one
     */
    @ControlThread
    public void onGsmCallEnded(long gsmCallId) {
        control.assertOnControlThread("CallManager.onGsmCallEnded");

        if (!ownsGsmCall(gsmCallId)) {
            Log.w(TAG, "GSM end for call " + gsmCallId + " which is not the current leg "
                    + currentGsmCallId + " - ignoring");
            return;
        }

        Log.d(TAG, "GSM call ended (gsmCallId=" + gsmCallId + ")");
        currentGsmCallId = GatewayInCallService.NO_GSM_CALL;
        pendingGsmDestination = null;
        gsmCallPlacedTime = 0;

        if (state != CallState.IDLE) {
            terminateAllCalls();
        }
    }

    /**
     * Whether an event naming {@code gsmCallId} may drive this machine.
     *
     * <p>True when the event names the leg already adopted, when no leg is adopted (nothing
     * to contradict it), or when the caller could not name one at all - an untagged event is
     * treated as it was before GW-13 rather than being silently dropped. False only when the
     * machine is demonstrably driving a <em>different</em> leg.
     */
    @ControlThread
    private boolean ownsGsmCall(long gsmCallId) {
        return gsmCallId == GatewayInCallService.NO_GSM_CALL
                || currentGsmCallId == GatewayInCallService.NO_GSM_CALL
                || currentGsmCallId == gsmCallId;
    }

    // ========== Incoming GSM Call ==========

    /**
     * Handle incoming GSM call (will create outgoing SIP call).
     */
    @ControlThread
    public void onIncomingGsmCall(String callerNumber, int simSlot, long gsmCallId) {
        control.assertOnControlThread("CallManager.onIncomingGsmCall");
        if (state != CallState.IDLE) {
            Log.w(TAG, "Already have active call, ignoring incoming GSM");
            return;
        }

        Log.d(TAG, "Incoming GSM call from " + callerNumber + " on SIM" + simSlot
                + " (gsmCallId=" + gsmCallId + ")");

        // GSM→SIP adopts the identity here, before the SIP leg is even dialled, so a GSM
        // hangup during ring can be matched to the leg that is ringing.
        currentGsmCallId = gsmCallId;

        // Get SIP destination for this SIM
        String sipDest = config.getDestinationForSim(simSlot);
        if (sipDest.isEmpty()) {
            Log.w(TAG, "No SIP destination configured for SIM" + simSlot);
            return;
        }

        transition(CallState.IDLE, CallState.GSM_INCOMING, "GSM call ringing");

        // Notify that SIP call is needed. setOutgoingSipCall, reached synchronously through
        // this listener, takes the machine on to SIP_DIALING.
        if (listener != null) {
            listener.onSipCallNeeded(sipDest, callerNumber, simSlot);
        }
    }

    // ========== Termination ==========

    /**
     * Hangup the current SIP call.
     *
     * <p>Not {@code synchronized} any more (GW-11 §1, plan §3c). The monitor never protected
     * anything - {@code terminateAllCalls()}, its only real caller, did not take it - while
     * being held across a pjsua2 BYE and, through the outer monitor on
     * {@code PjsipSipService.hangupCall}, a Telecom {@code disconnectCall()} and
     * {@code GsmAudioPort.stopCapture()}: a ~1.75 s join plus a ~250 ms native drain, entered
     * from main and from pjsua workers alike. Both monitors are gone; the thread invariant
     * asserted above is what serialises this now.
     */
    @ControlThread
    public void hangupSipCall() {
        control.assertOnControlThread("CallManager.hangupSipCall");
        // One snapshot for both the guard and the teardown.
        GatewayCall callToDispose = currentSipCall;
        if (callToDispose == null) {
            return;
        }

        currentSipCall = null;  // Clear first to prevent multiple calls

        // Mark as disposed to prevent further callbacks
        callToDispose.dispose();

        CallOpParam prm = null;
        try {
            // Check if call is still active before hanging up
            if (callToDispose.isActive()) {
                prm = new CallOpParam();
                prm.setStatusCode(pjsip_status_code.PJSIP_SC_DECLINE);
                callToDispose.hangup(prm);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error hanging up SIP call: " + e.getMessage());
        } finally {
            Pjsua2Lifetime.delete(prm);
        }

        // Nothing else references this call now. It is very likely still holding its pjsua
        // slot at this instant - the BYE above has only just gone out - so the graveyard will
        // re-probe until pjsua invalidates the id, and abandon it to the finalizer if that
        // never happens. AUDIT H7.
        graveyard.bury(callToDispose, "hangupSipCall");
    }

    /**
     * Terminate both legs and reset the machine - idempotent (GW-11 §3).
     *
     * <p>A second call while the first is still running, or one made when there is nothing to
     * terminate, returns immediately. That is what stops {@code onCallsTerminated()} firing
     * twice, which used to mean two concurrent {@code stopBridge()} runs. A plain check, no
     * lock: the single-thread invariant makes check and act indivisible.
     *
     * <p>Consequence worth knowing: {@code hangupCall()} from the web UI or the Telecom
     * timeout no longer runs the bridge teardown when the machine is already {@code IDLE}.
     * IDLE means no call, so there is nothing for it to tear down; the reload path in
     * {@code PjsipSipService.doReloadConfig} stops the bridge itself and does not rely on
     * this.
     */
    @ControlThread
    public void terminateAllCalls() {
        control.assertOnControlThread("CallManager.terminateAllCalls");

        CallState from = state;
        if (from == CallState.IDLE || from == CallState.TERMINATING) {
            Log.d(TAG, "Nothing to terminate, already " + from);
            return;
        }

        Log.d(TAG, "Terminating all calls");
        // Cannot be rejected: every state that gets past the guard above has TERMINATING in
        // its legal set. If it ever is, the log line says so and the machine stays put rather
        // than half-terminating.
        transition(from, CallState.TERMINATING, "tearing both legs down");

        // Drop any DTMF still queued for the GSM leg
        dtmfSender.clear();

        // Hangup SIP call
        hangupSipCall();

        // Hangup GSM call
        hangupGsmCall();

        // Clear state
        currentGsmCallId = GatewayInCallService.NO_GSM_CALL;
        pendingGsmDestination = null;
        pendingGsmSimSlot = 1;
        gsmCallPlacedTime = 0;

        transition(CallState.TERMINATING, CallState.IDLE, "teardown complete");

        if (listener != null) {
            listener.onCallsTerminated();
        }
    }

    /**
     * Hangup GSM call via InCallService.
     */
    private void hangupGsmCall() {
        try {
            GatewayInCallService inCallService = GatewayInCallService.getInstance();
            if (inCallService != null) {
                inCallService.disconnectCall();
                Log.d(TAG, "GSM call hangup requested");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to hangup GSM call: " + e.getMessage());
        }
    }

    // ========== Utilities ==========

    private String extractPhoneNumber(String uri) {
        if (uri == null) return null;

        // Remove sip:/sips: prefix and angle brackets
        String cleaned = uri.replaceAll("[<>]", "");
        if (cleaned.startsWith("sips:")) {
            cleaned = cleaned.substring(5);
        } else if (cleaned.startsWith("sip:")) {
            cleaned = cleaned.substring(4);
        }

        // Get user part (before @)
        int atPos = cleaned.indexOf('@');
        if (atPos > 0) {
            cleaned = cleaned.substring(0, atPos);
        }

        // Check if it's a valid phone number
        if (isValidPhoneNumber(cleaned)) {
            return cleaned;
        }

        return null;
    }

    private String extractExtension(String uri) {
        if (uri == null) return "";

        String cleaned = uri.replaceAll("[<>]", "");
        if (cleaned.startsWith("sips:")) {
            cleaned = cleaned.substring(5);
        } else if (cleaned.startsWith("sip:")) {
            cleaned = cleaned.substring(4);
        }

        int atPos = cleaned.indexOf('@');
        if (atPos > 0) {
            return cleaned.substring(0, atPos);
        }

        return cleaned;
    }

    private boolean isValidPhoneNumber(String number) {
        if (number == null || number.isEmpty()) return false;
        Matcher matcher = PHONE_PATTERN.matcher(number);
        return matcher.matches();
    }

    private void notifyStateChanged() {
        if (listener != null) {
            listener.onCallStateChanged(state);
        }
    }

    private void notifyError(String error) {
        if (listener != null) {
            listener.onError(error);
        }
    }

    /**
     * Status line for the UI. Since the {@code SIP_INCOMING} split it names the direction
     * honestly: an inbound GSM call used to be reported as "Incoming SIP call".
     */
    @ControlThread
    public String getStatusString() {
        control.assertOnControlThread("CallManager.getStatusString");
        switch (state) {
            case IDLE:
                return "Idle";
            case SIP_INCOMING:
                return "Incoming SIP call";
            case SIP_ANSWERED:
                return "Dialing GSM...";
            case GSM_DIALING:
                return "GSM connecting...";
            case GSM_INCOMING:
                return "Incoming GSM call";
            case SIP_DIALING:
                return "Dialing SIP...";
            case BRIDGED:
                return "Call bridged";
            case TERMINATING:
                return "Ending call...";
            default:
                return "Unknown";
        }
    }
}
