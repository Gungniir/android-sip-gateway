package org.onetwoone.gateway;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * GW-10 / plan §2.6 P0 — how a callback decides whether it belongs to the gateway or to the
 * diagnostic test call.
 *
 * <p>The bug this guards against, once the callbacks became {@code control.post(...)}: the
 * demux used to compare against {@code SipTestCallManager}'s mutable {@code call} field,
 * which a failed diagnostic dial nulls in its catch block. Evaluated late - which is what a
 * post means - a diagnostic {@code DISCONNECTED} then looked like a gateway one, fell through
 * into {@code CallManager}, and ran {@code terminateAllCalls()} on a live, unrelated gateway
 * call.
 *
 * <p>{@code GatewayCall} cannot be constructed on the JVM (its super constructor goes
 * straight into libpjsua2), so ownership is stubbed. That is enough: the whole point of the
 * fix is that the answer comes from one immutable field on the call and from nothing else.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class CallOwnerDispatchTest {

    private static GatewayCall callOwnedBy(GatewayCall.Owner owner) {
        GatewayCall call = mock(GatewayCall.class);
        when(call.getOwner()).thenReturn(owner);
        return call;
    }

    @Test
    public void diagnosticCallsAreRecognisedByTheirOwner() {
        assertTrue(PjsipSipService.isDiagnostic(callOwnedBy(GatewayCall.Owner.DIAGNOSTIC)));
    }

    @Test
    public void gatewayCallsAreNotDiagnostic() {
        assertFalse(PjsipSipService.isDiagnostic(callOwnedBy(GatewayCall.Owner.GATEWAY)));
    }

    /**
     * The heart of it: ownership does not depend on any manager's current state, so the
     * answer is the same whether the callback is handled inline or three tasks later. There
     * is deliberately nothing to "invalidate" here - that is the fix.
     */
    @Test
    public void ownershipDoesNotChangeOverTime() {
        GatewayCall diagnostic = callOwnedBy(GatewayCall.Owner.DIAGNOSTIC);

        assertTrue(PjsipSipService.isDiagnostic(diagnostic));

        // Whatever the diagnostic manager does to its own bookkeeping in the meantime -
        // including nulling its `call` field on a failed dial - the call still knows who it
        // belongs to.
        assertTrue("a queued callback must demux the same way as an inline one",
                PjsipSipService.isDiagnostic(diagnostic));
    }

    @Test
    public void aNullCallIsNotDiagnostic() {
        assertFalse(PjsipSipService.isDiagnostic(null));
    }
}
