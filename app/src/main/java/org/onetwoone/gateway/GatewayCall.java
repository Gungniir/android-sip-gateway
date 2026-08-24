package org.onetwoone.gateway;

import android.os.SystemClock;
import android.util.Log;

import org.onetwoone.gateway.call.CallGraveyard;
import org.onetwoone.gateway.sip.Pjsua2Lifetime;
import org.pjsip.pjsua2.*;

import java.util.concurrent.atomic.AtomicLong;

/**
 * PJSIP Call implementation for GSM-SIP Gateway.
 * Handles call state changes and media state.
 *
 * <h3>Native lifetime (GW-22 / AUDIT H7)</h3>
 * Every value object pulled out of a call - {@link CallInfo} above all - is owned native
 * memory the Java side must release. See {@link Pjsua2Lifetime} for which pjsua2 objects those
 * are and why the finalizer is not good enough.
 *
 * <p>The {@code Call} itself is a SWIG <em>director</em> with {@code swigCMemOwn = true} and a
 * <b>weak</b> director global ref, so this object is collectible and its {@code finalize()}
 * would run {@code delete_Call} on the FinalizerDaemon thread, which is not registered with
 * pjlib. {@link CallGraveyard} deletes it deterministically on the control thread instead;
 * this class implements {@link CallGraveyard.Doomed} for it and counts every creation and
 * every deletion, whoever performs it.
 */
public class GatewayCall extends Call implements CallGraveyard.Doomed {
    private static final String TAG = "GatewayCall";

    /** pjmedia_stream_dtmf_event_flags - set on the last packet of an RFC4733 event. */
    private static final int DTMF_EVENT_END = 2;

    /**
     * The RFC4733 end packet is retransmitted, and a SIP INFO digit can surface through both
     * callbacks at once. Collapse identical digits that arrive back-to-back; no human (and no
     * PBX) repeats a digit this fast.
     */
    private static final long DTMF_DUPLICATE_WINDOW_MS = 60;

    /**
     * Who this call belongs to.
     *
     * <p>The gateway state machine and the diagnostic test call share one PJSIP account, so
     * every callback has to be demuxed. That used to be done by asking
     * {@code SipTestCallManager.owns(call)} - a mutable field comparison. Once the callbacks
     * became {@code control.post(...)} that turned into a live bug: a failed diagnostic dial
     * nulls the manager's {@code call} field in its catch block, so a <em>queued</em>
     * {@code DISCONNECTED} for the diagnostic call would evaluate {@code owns()} as false,
     * fall through into {@code CallManager}, and run {@code terminateAllCalls()} on a live,
     * unrelated gateway call.
     *
     * <p>Ownership is fixed at construction and immutable, so evaluating it late is safe by
     * construction. (GW-11 §4, pulled forward into GW-10 - see plan §2.6.)
     */
    public enum Owner {
        /** A gateway leg: drives CallManager, the GSM dial and the audio bridge. */
        GATEWAY,
        /** The diagnostic SIP call: no GSM leg, must stay out of the state machine. */
        DIAGNOSTIC
    }

    /**
     * The service the callbacks report to, or null once {@link #dispose()} has run.
     *
     * {@code volatile} and always snapshotted before use: {@code dispose()} is called from
     * main, from the control thread and from pjsua workers, while the callbacks below run on
     * a pjsua worker. A bare {@code if (service != null) service.…} is a TOCTOU on a field
     * another thread is nulling (AUDIT H6).
     */
    private volatile SipCallService service;

    /**
     * Set the instant PJSIP reports DISCONNECTED, <em>synchronously on the callback
     * thread</em>, and by {@link #dispose()} from wherever the teardown runs.
     *
     * <p>The flag is never posted - only the handling that follows it is. It gates every
     * subsequent call into pjsua2, and some of those failures are pjmedia assertions, i.e.
     * {@code abort()} rather than a catchable exception (plan §2.6).
     */
    private volatile boolean disposed = false;
    private volatile String lastDtmfDigit;
    private volatile long lastDtmfAtMs;

    /** Immutable, so a demux done late gives the same answer as one done inline. */
    private final Owner owner;

    /**
     * Hands out {@link #generation}. Process-scoped and monotonic, like the audio bridge's
     * wiring state that consumes it - a service restart must not start handing out numbers a
     * still-wired bridge has already seen. Starts at 1 so 0 can mean "no generation".
     */
    private static final AtomicLong GENERATIONS = new AtomicLong(1);

    /**
     * This call's place in the process-wide order of calls, fixed at construction.
     *
     * <p>What {@code AudioBridgeManager} uses to refuse a stale wiring request (AUDIT D1b).
     * {@code CallManager.onSipCallState} fires {@code onSipCallConnected(call)} on CONFIRMED
     * without checking that {@code call} is the current one, and since GW-10 that callback is
     * posted - so a CONFIRMED for a call that has already been replaced can arrive after its
     * successor is up. Comparing generations answers "is this still the newest call the bridge
     * has been asked about?", which a {@code call == currentSipCall} identity check cannot:
     * identity says nothing about ordering once calls can legitimately be replaced.
     *
     * <p>Immutable, so evaluating it late gives the same answer as evaluating it inline -
     * the same property that makes {@link Owner} safe to demux on.
     */
    private final long generation = GENERATIONS.getAndIncrement();

    /**
     * Process-wide counts of {@code Call} objects constructed and destroyed, published in the
     * status snapshot as {@code callsCreated} / {@code callsDeleted} (AUDIT H7).
     *
     * <p>Their difference is the number of {@code Call} objects still alive, which is the one
     * number that says whether the graveyard is keeping up. It should equal the number of
     * active calls - 0 or 1 - whenever the gateway is idle.
     *
     * <p>{@code callsDeleted} is bumped from the {@link #delete()} override below, so it counts
     * <em>every</em> deletion, including one performed by the finalizer if {@link CallGraveyard}
     * ever gives up on a call. That is deliberate: a widening gap and a "Call deleted on thread
     * FinalizerDaemon" line together are the exact evidence that the deterministic path failed.
     */
    private static final AtomicLong CALLS_CREATED = new AtomicLong();
    private static final AtomicLong CALLS_DELETED = new AtomicLong();

    /** Guards {@link #CALLS_DELETED} against {@code delete()}'s idempotence. Monitor-guarded. */
    private boolean counted = false;

    /**
     * Constructor for outgoing gateway calls.
     */
    public GatewayCall(SipCallService service, Account account) {
        this(service, account, Owner.GATEWAY);
    }

    /**
     * Constructor for outgoing calls with an explicit owner (the diagnostic test call).
     */
    public GatewayCall(SipCallService service, Account account, Owner owner) {
        super(account);
        this.service = service;
        this.owner = owner == null ? Owner.GATEWAY : owner;
        CALLS_CREATED.incrementAndGet();
    }

    /**
     * Constructor for incoming calls. Incoming calls are always gateway calls - the
     * diagnostic call is only ever placed outbound.
     */
    public GatewayCall(SipCallService service, Account account, int callId) {
        super(account, callId);
        this.service = service;
        this.owner = Owner.GATEWAY;
        CALLS_CREATED.incrementAndGet();
    }

    /**
     * Count the destruction wherever it happens, then destroy.
     *
     * <p>{@code Call.delete()} is idempotent and reachable from three directions -
     * {@link CallGraveyard}, {@code swigDirectorDisconnect()}, and {@code finalize()} on the
     * FinalizerDaemon thread - so the count is guarded by {@link #counted} and taken under the
     * same monitor the superclass uses.
     */
    @Override
    public synchronized void delete() {
        super.delete();
        if (!counted) {
            counted = true;
            long total = CALLS_DELETED.incrementAndGet();
            Log.i(TAG, "Call deleted on " + Thread.currentThread().getName()
                    + " (created=" + CALLS_CREATED.get() + " deleted=" + total + ")");
        }
    }

    /** Process-wide count of {@code Call} objects constructed. See {@link #CALLS_CREATED}. */
    public static long getCallsCreated() {
        return CALLS_CREATED.get();
    }

    /** Process-wide count of {@code Call} objects destroyed. See {@link #CALLS_CREATED}. */
    public static long getCallsDeleted() {
        return CALLS_DELETED.get();
    }

    /** Never null, never changes. See {@link Owner}. */
    public Owner getOwner() {
        return owner;
    }

    /** Monotonic, unique, and fixed at construction. See {@link #generation}. */
    public long getGeneration() {
        return generation;
    }

    /**
     * Mark this call as disposed - no more callbacks will be processed.
     * Call this when the call is disconnected.
     */
    public void dispose() {
        disposed = true;
        service = null;
        Log.d(TAG, "Call disposed");
    }

    public boolean isDisposed() {
        return disposed;
    }

    @Override
    public void onCallState(OnCallStateParam prm) {
        if (disposed) {
            Log.d(TAG, "Ignoring onCallState - call disposed");
            return;
        }

        // Owned native memory (`new CallInfo(ptr, true)`), and this is the highest-frequency
        // pjsua2 allocation in the app - ~5-6 per call, one per SIP state change. See
        // Pjsua2Lifetime; AUDIT H7.
        CallInfo info = null;
        try {
            info = getInfo();
            int state = info.getState();
            String stateText = info.getStateText();

            Log.d(TAG, "Call state: " + stateText + " (" + state + ")");

            // Mark as disposed on disconnect to prevent further callbacks
            if (state == pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED) {
                disposed = true;
            }

            // Snapshot: dispose() nulls the field from another thread - same pattern as
            // relayDtmf() below.
            SipCallService svc = service;
            if (svc != null) {
                svc.onCallState(this, state);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onCallState: " + e.getMessage());
        } finally {
            Pjsua2Lifetime.delete(info);
        }
    }

    @Override
    public void onCallMediaState(OnCallMediaStateParam prm) {
        if (disposed) {
            Log.d(TAG, "Ignoring onCallMediaState - call disposed");
            return;
        }

        Log.d(TAG, "Media state changed");

        try {
            // Snapshot: dispose() nulls the field from another thread - same pattern as
            // relayDtmf() below.
            SipCallService svc = service;
            if (svc != null) {
                svc.onCallMediaState(this);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onCallMediaState: " + e.getMessage());
        }
    }

    @Override
    public void onCallTransferRequest(OnCallTransferRequestParam prm) {
        Log.d(TAG, "Transfer request: " + prm.getDstUri());
        // Reject transfers for now
        prm.setStatusCode(pjsip_status_code.PJSIP_SC_DECLINE);
    }

    @Override
    public void onCallReplaced(OnCallReplacedParam prm) {
        Log.d(TAG, "Call replaced by " + prm.getNewCallId());
    }

    @Override
    public void onInstantMessage(OnInstantMessageParam prm) {
        Log.d(TAG, "IM: " + prm.getMsgBody());
    }

    @Override
    public void onDtmfDigit(OnDtmfDigitParam prm) {
        // Deprecated callback. PJSIP fires it for RFC4733 as well, at the *start* of the
        // event - roughly one keypress duration before onDtmfEvent reports the same digit,
        // which is too far apart for any duplicate window to absorb. onDtmfEvent already
        // covers RFC4733, so only SIP INFO is taken from here.
        if (prm.getMethod() != pjsua_dtmf_method.PJSUA_DTMF_METHOD_SIP_INFO) {
            return;
        }
        relayDtmf(prm.getDigit(), "INFO");
    }

    @Override
    public void onDtmfEvent(OnDtmfEventParam prm) {
        // Fires for every packet of the event (begin, updates, end) - only the end is a
        // completed keypress.
        if ((prm.getFlags() & DTMF_EVENT_END) == 0) {
            return;
        }
        relayDtmf(prm.getDigit(), "event");
    }

    private void relayDtmf(String digit, String source) {
        if (disposed || digit == null || digit.isEmpty()) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (digit.equals(lastDtmfDigit) && now - lastDtmfAtMs < DTMF_DUPLICATE_WINDOW_MS) {
            return;
        }
        lastDtmfDigit = digit;
        lastDtmfAtMs = now;

        Log.d(TAG, "DTMF from SIP: " + digit + " (" + source + ")");

        SipCallService svc = service;
        if (svc != null) {
            try {
                svc.onDtmfDigit(this, digit);
            } catch (Exception e) {
                Log.e(TAG, "Error relaying DTMF: " + e.getMessage());
            }
        }
    }
}
