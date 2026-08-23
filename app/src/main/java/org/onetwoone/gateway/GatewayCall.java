package org.onetwoone.gateway;

import android.os.SystemClock;
import android.util.Log;

import org.pjsip.pjsua2.*;

/**
 * PJSIP Call implementation for GSM-SIP Gateway.
 * Handles call state changes and media state.
 */
public class GatewayCall extends Call {
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
     * The service the callbacks report to, or null once {@link #dispose()} has run.
     *
     * {@code volatile} and always snapshotted before use: {@code dispose()} is called from
     * main, from pjsua workers and from ConfigReload, while the callbacks below run on a
     * pjsua worker. A bare {@code if (service != null) service.…} is a TOCTOU on a field
     * another thread is nulling (AUDIT H6).
     */
    private volatile SipCallService service;
    private volatile boolean disposed = false;
    private volatile String lastDtmfDigit;
    private volatile long lastDtmfAtMs;

    /**
     * Constructor for outgoing calls
     */
    public GatewayCall(SipCallService service, Account account) {
        super(account);
        this.service = service;
    }

    /**
     * Constructor for incoming calls
     */
    public GatewayCall(SipCallService service, Account account, int callId) {
        super(account, callId);
        this.service = service;
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

        try {
            CallInfo info = getInfo();
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
