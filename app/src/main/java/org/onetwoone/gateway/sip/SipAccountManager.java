package org.onetwoone.gateway.sip;

import android.util.Log;

import org.onetwoone.gateway.GatewayAccount;
import org.onetwoone.gateway.config.GatewayConfig;
import org.pjsip.pjsua2.*;

/**
 * Manages SIP account registration.
 *
 * Responsibilities:
 * - Creating and configuring the SIP account
 * - Handling registration state changes
 * - Managing SRTP settings
 * - Credential management
 */
public class SipAccountManager {
    private static final String TAG = "SipAccount";

    private final GatewayConfig config;
    private final SipEndpointManager endpointManager;

    /**
     * Written on the {@code GatewayControl} thread ({@link #createAccount} from SIP init and
     * from reload, {@link #deleteAccount} from reload) and on main ({@code onDestroy}'s
     * shutdown); read from the control thread, main (SMS, the diagnostic call) and NanoHTTPD
     * workers. Snapshot before use.
     */
    private volatile GatewayAccount account;

    /**
     * Written on a pjsua worker ({@link #onRegState}), <em>synchronously</em> and before the
     * listener is invoked - GW-10 posts the listener's handling onto the control thread but
     * never the flag itself, because it gates later calls into pjsua2 (plan §2.6). Read from
     * the control thread, main and NanoHTTPD.
     */
    private volatile boolean registered = false;

    /** Written on a pjsua worker ({@link #onRegState}); read from the control thread and NanoHTTPD. */
    private volatile String lastError = null;

    public interface AccountListener {
        void onRegistrationState(boolean registered, String reason);
        void onIncomingCall(GatewayAccount account, int callId, int simSlotHint);
        void onInstantMessage(String from, String to, String body, int simSlot);
    }

    private AccountListener listener;

    public SipAccountManager(GatewayConfig config, SipEndpointManager endpointManager) {
        this.config = config;
        this.endpointManager = endpointManager;
    }

    public void setListener(AccountListener listener) {
        this.listener = listener;
    }

    /**
     * Get the current account.
     */
    public GatewayAccount getAccount() {
        return account;
    }

    /**
     * Check if currently registered.
     */
    public boolean isRegistered() {
        return registered;
    }

    /**
     * Get last registration error.
     */
    public String getLastError() {
        return lastError;
    }

    /**
     * Create and register the SIP account.
     *
     * @param callbackService Service to receive callbacks (for GatewayAccount)
     * @throws Exception if registration fails
     */
    public void createAccount(Object callbackService) throws Exception {
        Endpoint endpoint = endpointManager.getEndpoint();
        if (endpoint == null) {
            throw new IllegalStateException("Endpoint not created");
        }

        // CRITICAL: Check that transport exists before creating account
        // PJSIP crashes with assertion failure if account is created without transport
        if (!endpointManager.hasTransport()) {
            throw new IllegalStateException("No transport available - cannot create account");
        }

        String server = config.getSipServer();
        String user = config.getSipUser();
        String password = config.getSipPassword();
        String realm = config.getSipRealm();
        // Empty realm should be treated as wildcard for digest auth
        if (realm == null || realm.isEmpty()) {
            realm = "*";
        }
        boolean useTls = config.isUseTls();
        int port = config.getEffectiveSipPort();

        if (server.isEmpty() || user.isEmpty()) {
            throw new IllegalArgumentException("SIP server and user must be configured");
        }

        Log.d(TAG, "Registering account: " + user + "@" + server + ":" + port + " (TLS=" + useTls + ", realm=" + realm + ")");

        // Create account config
        AccountConfig accConfig = new AccountConfig();

        // Build SIP URI
        String transport = useTls ? ";transport=tls" : "";
        String idUri = "sip:" + user + "@" + server + transport;
        String regUri = "sip:" + server + ":" + port + transport;

        accConfig.setIdUri(idUri);
        accConfig.getRegConfig().setRegistrarUri(regUri);
        accConfig.getRegConfig().setTimeoutSec(300);
        accConfig.getRegConfig().setRetryIntervalSec(60);

        // Credentials
        AuthCredInfo cred = new AuthCredInfo("digest", realm, user, 0, password);
        accConfig.getSipConfig().getAuthCreds().add(cred);

        // NAT config
        AccountNatConfig natConfig = accConfig.getNatConfig();
        natConfig.setIceEnabled(false);
        natConfig.setSdpNatRewriteUse(1);
        natConfig.setViaRewriteUse(1);
        natConfig.setSipOutboundUse(1);

        // Media config - SRTP mandatory
        AccountMediaConfig mediaConfig = accConfig.getMediaConfig();
        mediaConfig.setSrtpUse(pjmedia_srtp_use.PJMEDIA_SRTP_MANDATORY);
        mediaConfig.setSrtpSecureSignaling(0); // Don't require TLS for SRTP
        Log.d(TAG, "SRTP set to mandatory");

        // Create account with callback service
        // The callbackService should be PjsipSipService which handles callbacks
        account = new AccountCallbackWrapper(callbackService);
        account.create(accConfig);

        Log.d(TAG, "Account created, waiting for registration...");
    }

    /**
     * Unregister and delete the account.
     */
    public void deleteAccount() {
        // Snapshot: createAccount() runs on SipInit/ConfigReload and can replace the field
        // while this runs, and every step below must act on the same account object.
        GatewayAccount doomed = account;
        if (doomed == null) {
            return;
        }

        Log.d(TAG, "Deleting account");

        try {
            doomed.setRegistration(false);
        } catch (Exception e) {
            Log.w(TAG, "Error unregistering: " + e.getMessage());
        }

        try {
            doomed.delete();
        } catch (Exception e) {
            Log.w(TAG, "Error deleting account: " + e.getMessage());
        }

        account = null;
        registered = false;
    }

    /**
     * Called by GatewayAccount when registration state changes, on a pjsua worker.
     *
     * <p>The flag is set here and now, synchronously. The listener's <em>handling</em> is
     * what {@code PjsipSipService} posts onto the control thread - never the flag, which
     * everything from SMS forwarding to the Telecom retry chain gates on.
     */
    public void onRegState(boolean isRegistered, String reason) {
        this.registered = isRegistered;

        if (!isRegistered) {
            this.lastError = reason;
        } else {
            this.lastError = null;
        }

        Log.d(TAG, "Registration state: " + (isRegistered ? "registered" : "failed: " + reason));

        if (listener != null) {
            listener.onRegistrationState(isRegistered, reason);
        }
    }

    /**
     * Get status string for UI.
     */
    public String getStatusString() {
        // The pjsua worker that runs onRegState can change these underneath this method.
        // account/registered are each read once; lastError is snapshotted below so the
        // tested value is the reported one.
        if (account == null) {
            return "Not configured";
        }
        if (registered) {
            return "Registered";
        }
        String error = lastError;
        if (error != null) {
            return "Error: " + error;
        }
        return "Connecting...";
    }

    /**
     * Wrapper class that delegates callbacks to the service.
     * This avoids tight coupling between Account and Service.
     */
    private class AccountCallbackWrapper extends GatewayAccount {
        private final Object callbackService;

        AccountCallbackWrapper(Object callbackService) {
            super(); // Callbacks handled by this wrapper
            this.callbackService = callbackService;
        }

        @Override
        public void onRegState(OnRegStateParam prm) {
            try {
                AccountInfo info = getInfo();
                boolean isReg = (info.getRegStatus() == pjsip_status_code.PJSIP_SC_OK);
                String reason = info.getRegStatusText();

                SipAccountManager.this.onRegState(isReg, reason);
            } catch (Exception e) {
                Log.e(TAG, "Error in onRegState: " + e.getMessage());
            }
        }

        @Override
        public void onIncomingCall(OnIncomingCallParam prm) {
            int simSlotHint = readSimSlotHint(prm.getRdata());

            Log.d(TAG, "Incoming call, callId=" + prm.getCallId()
                    + (simSlotHint > 0 ? ", " + SipHeaderReader.SIM_HEADER + "=" + simSlotHint : ""));

            if (listener != null) {
                listener.onIncomingCall(this, prm.getCallId(), simSlotHint);
            }
        }

        @Override
        public void onInstantMessage(OnInstantMessageParam prm) {
            try {
                String from = prm.getFromUri();
                String to = prm.getToUri();
                String body = prm.getMsgBody();
                String contentType = prm.getContentType();

                // The PBX picks the SIM with X-GSM-SIM; without it, fall back to the caller extension.
                int simSlot = readSimSlotHint(prm.getRdata());
                if (simSlot == 0) {
                    simSlot = config.getSimSlotForCaller(extractExtension(from));
                }

                Log.i(TAG, ">>> RECEIVED SIP MESSAGE: from=" + from + ", to=" + to + ", body=\"" + body + "\", contentType=" + contentType + ", SIM=" + simSlot);

                if (contentType != null && contentType.contains("text/plain")) {
                    if (listener != null) {
                        listener.onInstantMessage(from, to, body, simSlot);
                    }
                } else {
                    Log.w(TAG, "Ignoring non-text/plain MESSAGE: contentType=" + contentType);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling IM: " + e.getMessage());
            }
        }

        /**
         * Read the SIM slot the PBX requested, or 0 when it did not ask for one.
         * Never throws - a missing or unreadable rdata just means "no preference".
         */
        private int readSimSlotHint(SipRxData rdata) {
            try {
                return rdata == null ? 0 : SipHeaderReader.readSimSlot(rdata.getWholeMsg());
            } catch (Exception e) {
                Log.w(TAG, "Could not read " + SipHeaderReader.SIM_HEADER + ": " + e.getMessage());
                return 0;
            }
        }

        private String extractExtension(String uri) {
            if (uri == null) return "";
            // Extract user part from sip:user@domain or sips:user@domain
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
    }
}
