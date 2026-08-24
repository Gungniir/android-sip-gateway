package org.onetwoone.gateway;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;

import org.onetwoone.gateway.config.GatewayConfig;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.telecom.InCallService;
import android.telecom.PhoneAccountHandle;
import android.telecom.VideoProfile;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;

/**
 * InCallService that receives GSM call events.
 * This service is activated when device receives/makes GSM calls.
 *
 * <h3>Threading</h3>
 * Telecom delivers every callback here on the main looper, but the public API
 * ({@link #getCurrentCall()}, {@link #answerCall()}, {@link #disconnectCall()},
 * {@link #playDtmfTone(char)}, ...) is called from the {@code GatewayControl} thread via
 * {@code CallManager} / {@code PjsipSipService} (before GW-10, from pjsua workers).
 * {@link #currentCall} and {@link #instance} are therefore {@code volatile}, and
 * <b>every</b> consumer must snapshot the field into a local and operate on the local -
 * re-reading the field mid-method races {@link #onCallRemoved(Call)} nulling it and yields
 * an NPE.
 *
 * <p>The main→control direction is always a fire-and-forget post: nothing here waits for a
 * control-thread result (plan §2.4).
 */
public class GatewayInCallService extends InCallService {
    private static final String TAG = "GatewayInCall";

    // Incoming call modes (0=SIP_FIRST is default)
    public static final int MODE_SIP_FIRST = 0;     // Start SIP first, answer GSM when SIP connects (default)
    public static final int MODE_ANSWER_FIRST = 1;  // Answer GSM first, then start SIP
    private static final int INCOMING_TIMEOUT_MS = 30000;  // 30 seconds

    // Bounded SIP retry chain: 40 * 500ms = 20s, which stays safely inside the 30s
    // incoming timeout so the timeout is still the thing that hangs the GSM leg up.
    private static final int MAX_SIP_RETRIES = 40;
    private static final long SIP_RETRY_INTERVAL_MS = 500;

    private static volatile GatewayInCallService instance;

    /**
     * The id handed out when the event does not belong to a tracked GSM leg. Never issued by
     * {@link #CALL_ID_SEQ}, so it can never collide with a real call.
     */
    public static final long NO_GSM_CALL = 0L;

    /**
     * Hands out the identity of each GSM leg (GW-13 §3). {@code android.telecom.Call} is
     * {@code final} and carries no stable id of its own, so the gateway mints one per
     * {@link #onCallAdded(Call)} and threads it through every lifecycle event. Identity is
     * what lets a late event be recognised as belonging to a call that is no longer current
     * and dropped, instead of tearing down the call that replaced it.
     */
    private static final java.util.concurrent.atomic.AtomicLong CALL_ID_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    /** The single GSM leg this gateway is bridging, or null. Snapshot before use. */
    private volatile Call currentCall;

    /**
     * The id of {@link #currentCall}, or {@link #NO_GSM_CALL}. Written on main together with
     * {@code currentCall} and read on main by the callbacks that forward it onwards.
     */
    private volatile long currentCallId = NO_GSM_CALL;

    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());

    /** Owned by the main looper - see {@link #assertMainThread(String)}. */
    private volatile Runnable timeoutRunnable;

    private Call.Callback callCallback = new Call.Callback() {
        @Override
        public void onStateChanged(Call call, int state) {
            // Snapshot once: onCallAdded/onCallRemoved write the pair from this same looper,
            // but re-reading would let the two halves come from different calls.
            final Call tracked = currentCall;
            final long id = (call == tracked) ? currentCallId : NO_GSM_CALL;

            Log.d(TAG, "Call state changed: " + stateToString(state) + " (gsmCallId=" + id + ")");

            // NOTE: Don't mute microphone - it breaks SIP→GSM audio path!
            // The Incall_Music injection uses the same audio path as microphone.

            // Notify PjsipSipService about GSM call state
            PjsipSipService sipService = PjsipSipService.getInstance();
            if (sipService != null) {
                sipService.onGsmCallStateChanged(call, id, state);
            }
        }

        @Override
        public void onDetailsChanged(Call call, Call.Details details) {
            Log.d(TAG, "Call details changed: " + details.getHandle());
        }
    };

    public static GatewayInCallService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.d(TAG, "InCallService created");

        // Ensure SIP service is running.
        //
        // GW-26 §5: a null instance is not on its own a reason to start it. This service binds
        // whenever the app is the default dialler, and PjsipSipService.onDestroy nulls its
        // instance *first* - so a bind landing during teardown, or any bind after an explicit
        // STOP, used to resurrect the gateway the operator had just stopped. The persisted latch
        // is written before stopSelf(), so it is already set by the time we can see the null.
        //
        // Only an explicit user stop suppresses this. After a crash, an OOM kill or a reboot the
        // latch is clear and the gateway comes back, which is the direction that matters.
        if (PjsipSipService.getInstance() == null) {
            if (PjsipSipService.isUserStopped(this)) {
                Log.i(TAG, "SIP service stopped by the user - not restarting it");
            } else {
                Log.w(TAG, "SIP service not running, starting it...");
                Intent intent = new Intent(this, PjsipSipService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        Log.d(TAG, "InCallService destroyed");
    }

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);

        final Call tracked = currentCall;
        if (tracked == call) {
            Log.w(TAG, "onCallAdded for the call already tracked, ignoring duplicate");
            return;
        }
        if (tracked != null && !isDead(tracked)) {
            rejectSecondCall(tracked, call);
            return;
        }
        if (tracked != null) {
            // Corpse: onCallRemoved has not landed yet (or never will). Taking it over is
            // safe and keeps a stuck reference from rejecting every future call forever.
            Log.w(TAG, "Tracked call is already " + stateToString(tracked.getState())
                    + ", replacing it with the new call");
        }

        currentCall = call;
        currentCallId = CALL_ID_SEQ.incrementAndGet();
        call.registerCallback(callCallback);
        Log.d(TAG, "Tracking GSM call as gsmCallId=" + currentCallId);

        Log.d(TAG, "========== onCallAdded START (Android " + Build.VERSION.SDK_INT + ") ==========");
        Log.d(TAG, "Call state: " + stateToString(call.getState()));

        String number = numberOf(call);

        boolean isIncoming = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int dir = call.getDetails().getCallDirection();
            isIncoming = (dir == Call.Details.DIRECTION_INCOMING);
            Log.d(TAG, "Direction from API: " + dir + " (1=INCOMING, 2=OUTGOING)");
        } else {
            // On older APIs, determine by call state
            isIncoming = (call.getState() == Call.STATE_RINGING);
            Log.d(TAG, "Direction from state: " + (isIncoming ? "INCOMING" : "OUTGOING"));
        }

        // Detect SIM slot (1 or 2) from PhoneAccountHandle
        int simSlot = getSimSlotFromCall(call);
        Log.d(TAG, "SIM slot: " + simSlot);

        String direction = isIncoming ? "INCOMING" : "OUTGOING";
        Log.d(TAG, "Call added: " + number + ", direction: " + direction);
        Log.d(TAG, "========== onCallAdded END ==========");

        // Handle incoming GSM call
        if (isIncoming) {
            handleIncomingGsmCall(call, number, simSlot);
        }
    }

    /**
     * The gateway bridges exactly one GSM leg at a time (there is a single ALSA tap and a
     * single SIP leg). Previously a second call - a call-waiting leg, or a second inbound
     * call while one was bridged - silently overwrote {@code currentCall}: the original
     * became invisible to {@link #disconnectCall()} and was never hung up.
     *
     * <p>Deliberate behaviour change: the second leg is now refused, loudly, and never
     * tracked. In particular its callback is <b>not</b> registered, because
     * {@code onStateChanged} forwards into {@code PjsipSipService.onGsmCallStateChanged}
     * and a DISCONNECTED there would tear down the bridge of the call we are keeping.
     */
    private void rejectSecondCall(Call tracked, Call incoming) {
        Log.e(TAG, "========== SECOND GSM CALL REJECTED ==========");
        Log.e(TAG, "Already bridging " + numberOf(tracked) + " (state: " + stateToString(tracked.getState())
                + ") - the gateway can bridge exactly one GSM leg at a time");
        Log.e(TAG, "Refusing new call " + numberOf(incoming) + " (state: " + stateToString(incoming.getState()) + ")");

        try {
            int state = incoming.getState();
            if (state == Call.STATE_RINGING) {
                incoming.reject(false, null);
                Log.e(TAG, "Second GSM call reject() called");
            } else {
                // reject() is a no-op on anything that is not RINGING (e.g. a second
                // outgoing leg), so disconnect it instead - otherwise it would linger
                // untracked and un-hung-up, which is the very bug being fixed.
                incoming.disconnect();
                Log.e(TAG, "Second GSM call disconnect() called (state was " + stateToString(state) + ")");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to refuse second GSM call: " + e.getMessage(), e);
        }

        Log.e(TAG, "========== SECOND GSM CALL REJECTED END ==========");
    }

    /** True if the call is on its way out and can no longer be bridged. */
    private boolean isDead(Call call) {
        int state = call.getState();
        return state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING;
    }

    /** Best-effort dialled/caller number for logging. */
    private String numberOf(Call call) {
        try {
            if (call.getDetails() != null && call.getDetails().getHandle() != null) {
                return call.getDetails().getHandle().getSchemeSpecificPart();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read call handle: " + e.getMessage());
        }
        return "unknown";
    }

    /**
     * Detect SIM slot number (1 or 2) from call's PhoneAccountHandle
     * Returns 0 if unable to determine (single-SIM or error)
     */
    private int getSimSlotFromCall(Call call) {
        try {
            if (call.getDetails() == null || call.getDetails().getAccountHandle() == null) {
                return 0;
            }

            android.telecom.PhoneAccountHandle accountHandle = call.getDetails().getAccountHandle();
            String accountId = accountHandle.getId();

            Log.d(TAG, "PhoneAccountHandle ID: " + accountId);

            // Try to parse slot from account ID
            // Common formats: "0", "1", "89XXXXXXXXXXXXXXXXX" (ICCID with slot prefix)
            // Or use SubscriptionManager to map account to slot

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.telephony.SubscriptionManager subManager =
                    (android.telephony.SubscriptionManager) getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);

                if (subManager != null) {
                    java.util.List<android.telephony.SubscriptionInfo> subList =
                        subManager.getActiveSubscriptionInfoList();

                    if (subList != null) {
                        for (android.telephony.SubscriptionInfo info : subList) {
                            // Check if this subscription matches the account
                            String iccId = info.getIccId();
                            if (accountId.contains(iccId) || accountId.equals(String.valueOf(info.getSubscriptionId()))) {
                                int slot = info.getSimSlotIndex();
                                Log.d(TAG, "Matched subscription: slot=" + slot + ", iccId=" + iccId);
                                return slot + 1;  // Return 1-based slot (1 or 2)
                            }
                        }
                    }
                }
            }

            // Fallback: try to parse slot directly from ID if it's "0" or "1"
            if (accountId.length() == 1 && Character.isDigit(accountId.charAt(0))) {
                int slot = Integer.parseInt(accountId);
                return slot + 1;  // Return 1-based
            }

            Log.w(TAG, "Unable to determine SIM slot from account: " + accountId);
            return 0;  // Unknown

        } catch (Exception e) {
            Log.e(TAG, "Error detecting SIM slot: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Handle incoming GSM call based on configured mode:
     * MODE_ANSWER_FIRST: Answer GSM → Start SIP → Timeout 30s
     * MODE_SIP_FIRST: Start SIP → Answer GSM when SIP connects → Timeout 30s
     */
    private void handleIncomingGsmCall(Call call, String callerNumber, int simSlot) {
        Log.d(TAG, "Incoming GSM call from: " + callerNumber);

        // Get incoming call mode from config
        GatewayConfig.init(this);
        int mode = GatewayConfig.getInstance().getIncomingCallMode();
        Log.d(TAG, "Incoming call mode: " + (mode == MODE_SIP_FIRST ? "SIP_FIRST (default)" : "ANSWER_FIRST"));

        // Setup timeout handler - hangup both calls if not connected within 30s
        setupIncomingTimeout(call);

        if (mode == MODE_ANSWER_FIRST) {
            // Answer GSM first, then start SIP
            Log.d(TAG, "Mode ANSWER_FIRST: Answering GSM first (Android " + Build.VERSION.SDK_INT + ")");
            try {
                call.answer(VideoProfile.STATE_AUDIO_ONLY);
                Log.d(TAG, "GSM call answered successfully");
            } catch (Exception e) {
                Log.e(TAG, "Failed to answer GSM call: " + e.getMessage(), e);
                cancelIncomingTimeout();
                // AUDIT H9b. Returning here used to leave the leg tracked in currentCall with
                // no SIP call ever started AND no timeout left to hang it up - so it rang
                // until the network gave up, and nothing in the app could see it: the SIP
                // retry chain was never entered, and the watchdog's rules key off
                // currentGsmCallId, which only STATE_ACTIVE sets. Hang the leg up here, on the
                // call we were handed rather than on the field, so a racing onCallRemoved
                // cannot make us disconnect a different one.
                try {
                    int state = call.getState();
                    if (state != Call.STATE_DISCONNECTED && state != Call.STATE_DISCONNECTING) {
                        call.disconnect();
                        Log.e(TAG, "GSM leg disconnected after answer() failed (AUDIT H9b)");
                    }
                } catch (Exception disconnectFailed) {
                    Log.e(TAG, "Failed to disconnect the unanswerable GSM leg: "
                            + disconnectFailed.getMessage(), disconnectFailed);
                }
                return;
            }

            // Make SIP call to configured destination with CallerID
            makeSipCallWithRetry(call, callerNumber, simSlot, 0);

        } else {
            // SIP first (default): Start SIP, answer GSM when SIP connects
            Log.d(TAG, "Mode SIP_FIRST: Starting SIP first, will answer GSM when SIP connects");

            // Make SIP call first (don't answer GSM yet)
            makeSipCallWithRetry(call, callerNumber, simSlot, 0);

            // GSM will be answered from PjsipSipService when SIP connects
        }
    }

    /**
     * Setup timeout for incoming call - hangup both GSM and SIP if not connected within 30s.
     * The timeout is tagged with the call it was armed for, so a stale one can never
     * disconnect a later call.
     */
    private void setupIncomingTimeout(final Call armedFor) {
        assertMainThread("setupIncomingTimeout");
        cancelIncomingTimeout();  // Cancel any existing timeout

        timeoutRunnable = new Runnable() {
            @Override
            public void run() {
                Log.w(TAG, "Incoming call timeout (30s) - hanging up both GSM and SIP");

                // Hangup GSM call - snapshot first, onCallRemoved may null the field
                Call call = currentCall;
                if (call == null || call != armedFor) {
                    // Stale timer: the call it was armed for is gone. Hanging the SIP leg
                    // up here would kill whatever call is current NOW, so do nothing -
                    // orphan legs are the watchdog's job, not this timer's.
                    Log.d(TAG, "Timeout fired for a call that is no longer tracked, ignoring");
                    return;
                }

                try {
                    call.disconnect();
                    Log.d(TAG, "GSM call disconnected due to timeout");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to disconnect GSM on timeout: " + e.getMessage());
                }

                // Hangup SIP call
                PjsipSipService sipService = PjsipSipService.getInstance();
                if (sipService != null) {
                    sipService.hangupCall();
                    Log.d(TAG, "SIP call disconnected due to timeout");
                }
            }
        };

        timeoutHandler.postDelayed(timeoutRunnable, INCOMING_TIMEOUT_MS);
        Log.d(TAG, "Incoming timeout set: 30 seconds");
    }

    /**
     * Cancel incoming call timeout (called when bridge is successfully established)
     */
    public void cancelIncomingTimeout() {
        assertMainThread("cancelIncomingTimeout");
        Runnable pending = timeoutRunnable;
        if (pending != null) {
            timeoutHandler.removeCallbacks(pending);
            timeoutRunnable = null;
            Log.d(TAG, "Incoming timeout cancelled");
        }
    }

    /**
     * Ask the SIP side to place the outbound leg, retrying while it is still registering.
     *
     * <p>The chain is bounded ({@link #MAX_SIP_RETRIES} attempts, ~20s - inside the 30s
     * incoming timeout, which stays responsible for hanging the GSM leg up), reuses the
     * single {@link #timeoutHandler} instead of allocating a {@link Handler} per attempt,
     * and is tagged with {@code armedFor} so a chain belonging to a call that has since
     * been removed or replaced stops on its next tick.
     */
    private void makeSipCallWithRetry(final Call armedFor, final String callerNumber,
                                      final int simSlot, final int attempt) {
        // Check if GSM call is still active - single snapshot, no re-read
        final Call call = currentCall;
        if (call == null) {
            Log.w(TAG, "GSM call ended, stopping SIP retry");
            cancelIncomingTimeout();
            return;
        }
        if (call != armedFor) {
            Log.w(TAG, "SIP retry chain belongs to a call that is no longer tracked, stopping");
            return;
        }

        if (attempt >= MAX_SIP_RETRIES) {
            Log.e(TAG, "SIP service still not ready after " + MAX_SIP_RETRIES
                    + " retries (~" + (MAX_SIP_RETRIES * SIP_RETRY_INTERVAL_MS / 1000)
                    + "s), giving up - the incoming timeout will hang up the GSM leg");
            return;
        }

        PjsipSipService sipService = PjsipSipService.getInstance();
        if (sipService != null && sipService.isSipRegistered()) {
            sipService.onIncomingGsmCall(callerNumber, simSlot, currentCallId);
        } else {
            // Retry every 500ms until the GSM call ends or the cap is reached
            Log.w(TAG, "SIP service not ready, retry " + (attempt + 1) + " in 500ms");
            timeoutHandler.postDelayed(
                    () -> makeSipCallWithRetry(armedFor, callerNumber, simSlot, attempt + 1),
                    SIP_RETRY_INTERVAL_MS);
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        call.unregisterCallback(callCallback);

        Log.d(TAG, "Call removed");

        final Call tracked = currentCall;
        if (call == tracked) {
            // Cancel timeout when call is removed
            cancelIncomingTimeout();
            final long removedId = currentCallId;
            currentCall = null;
            currentCallId = NO_GSM_CALL;

            // GW-13 / plan §3d backstop. Removal is the last word Telecom says about a leg,
            // and after GW-13 this class is the *only* source of GSM lifecycle - the
            // PhoneStateListener no longer tears anything down. If a DISCONNECTED state
            // callback is ever dropped, this is what still stops the audio streams, and with
            // it the MixerEnforce thread that would otherwise hold the mic muted until
            // reboot. When DISCONNECTED did arrive, the id says so and the service logs a
            // no-op: teardown is idempotent per call id, so this can only ever add safety.
            PjsipSipService sipService = PjsipSipService.getInstance();
            if (sipService != null) {
                sipService.onGsmCallRemoved(removedId);
            }
        } else {
            // A refused second leg going away must not cancel the tracked call's timeout.
            Log.d(TAG, "Removed call was not the tracked call, current call left untouched");
        }
    }

    public Call getCurrentCall() {
        return currentCall;
    }

    /**
     * Whether the tracked GSM leg is still live - i.e. it exists and its Telecom state is not
     * {@code DISCONNECTED}/{@code DISCONNECTING}.
     *
     * <p>Since GW-13 this is what the watchdog asks instead of reading the
     * {@code PhoneStateListener}'s process-wide {@code lastPhoneState}: it is about the leg
     * this gateway is tracking, so it cannot report some *other* call as this one's.
     */
    public boolean hasLiveGsmCall() {
        Call call = currentCall;
        return call != null && !isDead(call);
    }

    public void answerCall() {
        Call call = currentCall;
        if (call == null) {
            Log.w(TAG, "No current GSM call to answer");
            return;
        }

        Log.d(TAG, "Answering call");
        try {
            // Android 10+ requires VideoProfile
            call.answer(VideoProfile.STATE_AUDIO_ONLY);
        } catch (Exception e) {
            Log.e(TAG, "Failed to answer call: " + e.getMessage());
        }
    }

    public void rejectCall() {
        Call call = currentCall;
        if (call == null) {
            Log.w(TAG, "No current GSM call to reject");
            return;
        }

        Log.d(TAG, "Rejecting call");
        try {
            call.reject(false, null);
        } catch (Exception e) {
            Log.e(TAG, "Failed to reject call: " + e.getMessage());
        }
    }

    public void disconnectCall() {
        Call call = currentCall;
        if (call == null) {
            Log.d(TAG, "No current GSM call to disconnect");
            return;
        }

        int state = call.getState();
        Log.d(TAG, "Disconnecting GSM call (state: " + stateToString(state) + ")");

        try {
            // Only disconnect if call is not already disconnected/disconnecting
            if (state != Call.STATE_DISCONNECTED && state != Call.STATE_DISCONNECTING) {
                call.disconnect();
                Log.d(TAG, "GSM call disconnect() called");
            } else {
                Log.d(TAG, "GSM call already disconnecting/disconnected, skipping");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to disconnect GSM call: " + e.getMessage(), e);
        }
    }

    /**
     * Start a DTMF tone on the GSM leg (out-of-band, via the modem - it does not go
     * through the ALSA bridge, so mic mute does not affect it).
     *
     * Telecom treats the tone as a level, not a one-shot: it keeps playing until
     * {@link #stopDtmfTone()}, so every successful call here must be paired with one.
     *
     * @return true if the tone was started
     */
    public boolean playDtmfTone(char digit) {
        Call call = currentCall;
        if (call == null) {
            Log.w(TAG, "No GSM call, dropping DTMF '" + digit + "'");
            return false;
        }

        int state = call.getState();
        if (state != Call.STATE_ACTIVE) {
            Log.w(TAG, "GSM call is " + stateToString(state) + ", dropping DTMF '" + digit + "'");
            return false;
        }

        try {
            call.playDtmfTone(digit);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to play DTMF '" + digit + "': " + e.getMessage());
            return false;
        }
    }

    /** Stop the tone started by {@link #playDtmfTone(char)}. */
    public void stopDtmfTone() {
        Call call = currentCall;
        if (call == null) {
            return;
        }

        try {
            call.stopDtmfTone();
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop DTMF: " + e.getMessage());
        }
    }

    /**
     * {@link #timeoutRunnable} is owned by the main looper: it is written by
     * {@link #setupIncomingTimeout(Call)} and cleared by {@link #cancelIncomingTimeout()},
     * and the check-then-act pair in the latter is only atomic while both run there.
     * Telecom callbacks and the pjsua-side callers that reach {@code cancelIncomingTimeout}
     * all satisfy that today; log loudly rather than throw if it ever stops being true.
     */
    private static void assertMainThread(String what) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.e(TAG, what + " called off the main thread ("
                    + Thread.currentThread().getName() + ") - timeoutRunnable is main-only");
        }
    }

    private String stateToString(int state) {
        switch (state) {
            case Call.STATE_NEW: return "NEW";
            case Call.STATE_DIALING: return "DIALING";
            case Call.STATE_RINGING: return "RINGING";
            case Call.STATE_HOLDING: return "HOLDING";
            case Call.STATE_ACTIVE: return "ACTIVE";
            case Call.STATE_DISCONNECTED: return "DISCONNECTED";
            case Call.STATE_CONNECTING: return "CONNECTING";
            case Call.STATE_DISCONNECTING: return "DISCONNECTING";
            case Call.STATE_SELECT_PHONE_ACCOUNT: return "SELECT_PHONE_ACCOUNT";
            default: return "UNKNOWN(" + state + ")";
        }
    }

    /**
     * Mute/unmute device microphone.
     * We mute during GSM calls to prevent local sounds from being picked up.
     */
    private void setMicrophoneMute(boolean mute) {
        try {
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                audioManager.setMicrophoneMute(mute);
                Log.d(TAG, "Microphone mute: " + mute);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to set microphone mute: " + e.getMessage());
        }
    }
}
