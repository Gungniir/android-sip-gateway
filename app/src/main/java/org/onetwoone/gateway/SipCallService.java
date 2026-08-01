package org.onetwoone.gateway;

/**
 * Interface for services that handle SIP call callbacks.
 * Implemented by PjsipSipService.
 */
public interface SipCallService {
    /**
     * Called when SIP call state changes.
     * @param call The call
     * @param state PJSIP state (pjsip_inv_state)
     */
    void onCallState(GatewayCall call, int state);

    /**
     * Called when SIP call media state changes.
     * @param call The call
     */
    void onCallMediaState(GatewayCall call);

    /**
     * Called when the SIP peer presses a DTMF digit (RFC4733 or SIP INFO).
     * @param call The call the digit arrived on
     * @param digit Single digit: 0-9, *, #, A-D
     */
    void onDtmfDigit(GatewayCall call, String digit);
}
