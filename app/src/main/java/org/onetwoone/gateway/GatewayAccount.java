package org.onetwoone.gateway;

import android.util.Log;

import org.onetwoone.gateway.sip.Pjsua2Lifetime;
import org.pjsip.pjsua2.*;

/**
 * PJSIP Account implementation for GSM-SIP Gateway.
 * Base class for SIP account - callbacks should be overridden by subclasses.
 *
 * Note: This is typically used via SipAccountManager.AccountCallbackWrapper
 * which overrides all callback methods.
 */
public class GatewayAccount extends Account {
    private static final String TAG = "GatewayAccount";

    /**
     * Default constructor for subclasses that override callbacks.
     */
    public GatewayAccount() {
        // Subclasses should override callback methods
    }

    @Override
    public void onRegState(OnRegStateParam prm) {
        // Owned native memory (Account.getInfo() -> (ptr, true)). AUDIT H7. Reached only when
        // a subclass does NOT override this - SipAccountManager's wrapper does, and deletes
        // its own.
        AccountInfo info = null;
        try {
            info = getInfo();
            boolean registered = (info.getRegStatus() == pjsip_status_code.PJSIP_SC_OK);
            String reason = info.getRegStatusText();
            Log.d(TAG, "Registration state: " + info.getRegStatus() + " - " + reason);
            // Subclasses should override to handle
        } catch (Exception e) {
            Log.e(TAG, "Error in onRegState: " + e.getMessage());
        } finally {
            Pjsua2Lifetime.delete(info);
        }
    }

    @Override
    public void onIncomingCall(OnIncomingCallParam prm) {
        Log.d(TAG, "Incoming call, callId=" + prm.getCallId());
        // Subclasses should override to handle
    }

    @Override
    public void onInstantMessage(OnInstantMessageParam prm) {
        try {
            String from = prm.getFromUri();
            String body = prm.getMsgBody();
            Log.d(TAG, "Instant message from " + from + ": " + body);
            // Subclasses should override to handle
        } catch (Exception e) {
            Log.e(TAG, "Error handling instant message: " + e.getMessage());
        }
    }
}
