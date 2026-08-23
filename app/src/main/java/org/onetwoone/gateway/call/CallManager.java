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
import org.pjsip.pjsua2.*;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages call coordination between SIP and GSM.
 *
 * Call flows:
 * 1. SIP → GSM: Incoming SIP call triggers GSM outgoing call
 * 2. GSM → SIP: Incoming GSM call triggers SIP outgoing call
 *
 * This class handles:
 * - Call state management
 * - GSM call placement via TelecomManager
 * - SIP call creation and answering
 * - Call termination coordination
 */
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

    // Current calls.
    //
    // Since GW-10 every writer is the control thread: PjsipSipService routes the pjsua
    // callbacks, the Telecom/phone-state hops, the watchdog tick and the public commands
    // through it. Readers are still cross-thread - getStatusString() feeds the status
    // snapshot from the control thread, but NanoHTTPD and the test suite reach the getters
    // directly - so volatile stays (rule 7: this diff does not remove synchronisation the
    // control thread makes redundant). GW-11 formalises the transitions and is where the
    // remaining read-modify-write correctness comes from (AUDIT D1).
    private volatile GatewayCall currentSipCall;
    private volatile String pendingGsmDestination;
    private volatile int pendingGsmSimSlot = 1;
    private volatile long gsmCallPlacedTime = 0;

    // Call state
    public enum CallState {
        IDLE,
        SIP_INCOMING,      // SIP call received, waiting to answer
        SIP_ANSWERED,      // SIP answered, placing GSM call
        GSM_DIALING,       // GSM call is dialing
        BRIDGED,           // Both calls connected, audio bridged
        TERMINATING        // Calls being terminated
    }

    /** See {@link #currentSipCall} for the writer/reader threads. */
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

    public CallManager(Context context, GatewayConfig config) {
        this(context, config, new GsmDtmfSender());
    }

    // Visible for testing.
    CallManager(Context context, GatewayConfig config, GsmDtmfSender dtmfSender) {
        this.context = context.getApplicationContext();
        this.config = config;
        this.dtmfSender = dtmfSender;
    }

    public void setListener(CallListener listener) {
        this.listener = listener;
    }

    // ========== State Accessors ==========

    public CallState getState() {
        return state;
    }

    public GatewayCall getCurrentSipCall() {
        return currentSipCall;
    }

    public String getPendingGsmDestination() {
        return pendingGsmDestination;
    }

    public int getPendingGsmSimSlot() {
        return pendingGsmSimSlot;
    }

    public boolean hasActiveCall() {
        return state != CallState.IDLE;
    }

    public boolean isInGracePeriod() {
        // Snapshot: the watchdog reads this while the control thread clears it.
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
    public long getGsmCallPlacedAtWallMs() {
        return gsmCallPlacedTime;
    }

    /**
     * True when a SIP call is registered <em>and</em> has not been disposed.
     *
     * Callers that only want to know "is the gateway busy on SIP right now" must ask this
     * rather than {@code getCurrentSipCall() != null}: a disposed leftover is not a call in
     * progress, and treating it as one is what used to block every diagnostic call.
     */
    public boolean hasLiveSipCall() {
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
    public boolean placeOutgoingSipCall(GatewayCall call, SipCallPlacer placer) {
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
     * @return true if the call is now the current one; false if a live call already holds the
     *         slot, in which case the new call must not be placed
     */
    public boolean setOutgoingSipCall(GatewayCall call) {
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
    public void onOutgoingCallFailed(GatewayCall call) {
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
    }

    /**
     * Handle incoming SIP call.
     * This will answer the SIP call and extract destination for GSM call.
     *
     * @param simSlotHint SIM slot the PBX requested via X-GSM-SIM, or 0 to fall back to
     *                    deriving the slot from the caller extension
     */
    public void onIncomingSipCall(GatewayCall call, int simSlotHint) {
        if (state != CallState.IDLE) {
            Log.w(TAG, "Already have active call, rejecting incoming");
            rejectCall(call);
            return;
        }

        currentSipCall = call;
        state = CallState.SIP_INCOMING;

        try {
            // Get call info to extract destination
            CallInfo info = call.getInfo();
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
        try {
            CallOpParam prm = new CallOpParam();
            prm.setStatusCode(pjsip_status_code.PJSIP_SC_OK);
            call.answer(prm);

            state = CallState.SIP_ANSWERED;
            notifyStateChanged();

            Log.d(TAG, "SIP call answered");

            // Notify that GSM call is needed. Snapshot: onGsmCallEnded /
            // terminateAllCalls() null this from another thread, so the checked value must
            // be the dialled one.
            String destination = pendingGsmDestination;
            if (listener != null && destination != null) {
                listener.onGsmCallNeeded(destination, pendingGsmSimSlot);
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to answer SIP call: " + e.getMessage());
            terminateAllCalls();
            notifyError("Failed to answer call: " + e.getMessage());
        }
    }

    /**
     * Reject a SIP call.
     */
    private void rejectCall(GatewayCall call) {
        try {
            CallOpParam prm = new CallOpParam();
            prm.setStatusCode(pjsip_status_code.PJSIP_SC_BUSY_HERE);
            call.hangup(prm);
        } catch (Exception e) {
            Log.e(TAG, "Error rejecting call: " + e.getMessage());
        }
    }

    /**
     * Handle SIP call state change.
     */
    public void onSipCallState(GatewayCall call, int pjsipState) {
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

            // DON'T delete the call object - PJSIP manages the native lifecycle.
            // Calling delete() crashes because PJSIP may still reference it.
            // The call object will be GC'd eventually - the disposed flag prevents callback issues.

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
        }
    }

    /**
     * Handle a DTMF digit pressed on the SIP leg (typically a voice menu on the far end of
     * the GSM call asking the caller to press something). The digit is replayed on the GSM
     * leg out-of-band via Telecom.
     */
    public void onSipDtmf(String digit) {
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
    public void placeGsmCall(String number, int simSlot) {
        if (!isValidPhoneNumber(number)) {
            Log.e(TAG, "Invalid phone number: " + number);
            notifyError("Invalid phone number");
            return;
        }

        Log.d(TAG, "Placing GSM call to " + number + " via SIM" + simSlot);

        state = CallState.GSM_DIALING;
        gsmCallPlacedTime = System.currentTimeMillis();
        notifyStateChanged();

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
     * This is called by the service when GSM call becomes active.
     */
    public void onGsmCallConnected() {
        Log.d(TAG, "GSM call connected");

        if (state == CallState.GSM_DIALING) {
            state = CallState.BRIDGED;
            notifyStateChanged();
        }
    }

    /**
     * Handle GSM call ended.
     */
    public void onGsmCallEnded() {
        Log.d(TAG, "GSM call ended");
        pendingGsmDestination = null;
        gsmCallPlacedTime = 0;

        if (state != CallState.IDLE) {
            terminateAllCalls();
        }
    }

    // ========== Incoming GSM Call ==========

    /**
     * Handle incoming GSM call (will create outgoing SIP call).
     */
    public void onIncomingGsmCall(String callerNumber, int simSlot) {
        if (state != CallState.IDLE) {
            Log.w(TAG, "Already have active call, ignoring incoming GSM");
            return;
        }

        Log.d(TAG, "Incoming GSM call from " + callerNumber + " on SIM" + simSlot);

        // Get SIP destination for this SIM
        String sipDest = config.getDestinationForSim(simSlot);
        if (sipDest.isEmpty()) {
            Log.w(TAG, "No SIP destination configured for SIM" + simSlot);
            return;
        }

        state = CallState.SIP_INCOMING; // Reuse state, but it's outgoing SIP
        notifyStateChanged();

        // Notify that SIP call is needed
        if (listener != null) {
            listener.onSipCallNeeded(sipDest, callerNumber, simSlot);
        }
    }

    // ========== Termination ==========

    /**
     * Hangup current SIP call.
     */
    public synchronized void hangupSipCall() {
        // One snapshot for both the guard and the teardown: the writers (onSipCallState,
        // onOutgoingCallFailed) do not take this monitor, so re-reading the field after the
        // null check could hand us a null - or a different call. Since GW-10 all of them run
        // on the control thread, which makes this monitor redundant; GW-11 §1 deletes it,
        // together with the outer one in PjsipSipService.hangupCall (plan §3c). Kept here
        // because GW-10 does not remove synchronisation.
        GatewayCall callToDispose = currentSipCall;
        if (callToDispose == null) {
            return;
        }

        currentSipCall = null;  // Clear first to prevent multiple calls

        // Mark as disposed to prevent further callbacks
        callToDispose.dispose();

        try {
            // Check if call is still active before hanging up
            if (callToDispose.isActive()) {
                CallOpParam prm = new CallOpParam();
                prm.setStatusCode(pjsip_status_code.PJSIP_SC_DECLINE);
                callToDispose.hangup(prm);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error hanging up SIP call: " + e.getMessage());
        }

        // DON'T delete - let PJSIP manage the native object lifecycle
        // The disposed flag prevents any further callback processing
    }

    /**
     * Terminate all calls and reset state.
     */
    public void terminateAllCalls() {
        Log.d(TAG, "Terminating all calls");

        state = CallState.TERMINATING;
        notifyStateChanged();

        // Drop any DTMF still queued for the GSM leg
        dtmfSender.clear();

        // Hangup SIP call
        hangupSipCall();

        // Hangup GSM call
        hangupGsmCall();

        // Clear state
        pendingGsmDestination = null;
        pendingGsmSimSlot = 1;
        gsmCallPlacedTime = 0;

        state = CallState.IDLE;
        notifyStateChanged();

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
     * Get status string for UI.
     */
    public String getStatusString() {
        switch (state) {
            case IDLE:
                return "Idle";
            case SIP_INCOMING:
                return "Incoming SIP call";
            case SIP_ANSWERED:
                return "Dialing GSM...";
            case GSM_DIALING:
                return "GSM connecting...";
            case BRIDGED:
                return "Call bridged";
            case TERMINATING:
                return "Ending call...";
            default:
                return "Unknown";
        }
    }
}
