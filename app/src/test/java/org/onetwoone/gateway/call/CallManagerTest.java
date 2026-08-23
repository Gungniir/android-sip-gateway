package org.onetwoone.gateway.call;

import android.app.Application;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.onetwoone.gateway.config.GatewayConfig;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import org.onetwoone.gateway.GatewayCall;
import org.pjsip.pjsua2.pjsip_inv_state;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CallManager.
 * Tests phone number validation, URI parsing, and state management.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class CallManagerTest {

    private CallManager callManager;
    private Application app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();

        // Reset GatewayConfig singleton
        try {
            java.lang.reflect.Field instance = GatewayConfig.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, null);
        } catch (Exception e) {
            // Ignore
        }
        GatewayConfig.init(app);

        callManager = new CallManager(app, GatewayConfig.getInstance());
    }

    @Test
    public void testInitialState() {
        assertEquals("Initial state should be IDLE", CallManager.CallState.IDLE, callManager.getState());
        assertFalse("Should not have active call initially", callManager.hasActiveCall());
        assertNull("Should have no SIP call initially", callManager.getCurrentSipCall());
    }

    @Test
    public void testStatusStrings() {
        assertEquals("IDLE status string", "Idle", callManager.getStatusString());
    }

    @Test
    public void testPhoneNumberValidation() throws Exception {
        // Use reflection to test private method
        Method isValidPhoneNumber = CallManager.class.getDeclaredMethod("isValidPhoneNumber", String.class);
        isValidPhoneNumber.setAccessible(true);

        // Valid numbers
        assertTrue("10 digit number should be valid", (Boolean) isValidPhoneNumber.invoke(callManager, "1234567890"));
        assertTrue("12 digit number should be valid", (Boolean) isValidPhoneNumber.invoke(callManager, "123456789012"));
        assertTrue("Number with + prefix should be valid", (Boolean) isValidPhoneNumber.invoke(callManager, "+79161234567"));
        assertTrue("15 digit number should be valid", (Boolean) isValidPhoneNumber.invoke(callManager, "123456789012345"));

        // Invalid numbers
        assertFalse("9 digit number should be invalid", (Boolean) isValidPhoneNumber.invoke(callManager, "123456789"));
        assertFalse("16 digit number should be invalid", (Boolean) isValidPhoneNumber.invoke(callManager, "1234567890123456"));
        assertFalse("Number with letters should be invalid", (Boolean) isValidPhoneNumber.invoke(callManager, "123456789a"));
        assertFalse("Empty string should be invalid", (Boolean) isValidPhoneNumber.invoke(callManager, ""));
        assertFalse("Null should be invalid", (Boolean) isValidPhoneNumber.invoke(callManager, (String) null));
    }

    @Test
    public void testExtractPhoneNumber() throws Exception {
        Method extractPhoneNumber = CallManager.class.getDeclaredMethod("extractPhoneNumber", String.class);
        extractPhoneNumber.setAccessible(true);

        // SIP URI formats
        assertEquals("+79161234567", extractPhoneNumber.invoke(callManager, "sip:+79161234567@server.com"));
        assertEquals("+79161234567", extractPhoneNumber.invoke(callManager, "<sip:+79161234567@server.com>"));
        assertEquals("1234567890", extractPhoneNumber.invoke(callManager, "sip:1234567890@192.168.1.1"));

        // Invalid URIs
        assertNull("Extension should not match", extractPhoneNumber.invoke(callManager, "sip:101@server.com"));
        assertNull("Null should return null", extractPhoneNumber.invoke(callManager, (String) null));
    }

    @Test
    public void testExtractExtension() throws Exception {
        Method extractExtension = CallManager.class.getDeclaredMethod("extractExtension", String.class);
        extractExtension.setAccessible(true);

        // Various formats
        assertEquals("101", extractExtension.invoke(callManager, "sip:101@server.com"));
        assertEquals("101", extractExtension.invoke(callManager, "<sip:101@server.com>"));
        assertEquals("gateway", extractExtension.invoke(callManager, "sip:gateway@192.168.1.1:5060"));
        assertEquals("+79161234567", extractExtension.invoke(callManager, "sip:+79161234567@server.com"));

        // Edge cases
        assertEquals("", extractExtension.invoke(callManager, (String) null));
    }

    @Test
    public void testGracePeriod() {
        // Initially not in grace period
        assertFalse("Should not be in grace period initially", callManager.isInGracePeriod());
    }

    @Test
    public void testTerminateAllCalls() {
        // Terminate should work even when no active calls
        callManager.terminateAllCalls();

        assertEquals("State should be IDLE after terminate", CallManager.CallState.IDLE, callManager.getState());
        assertFalse("Should not have active call", callManager.hasActiveCall());
    }

    @Test
    public void testCallStateTransitions() {
        // Test state transitions via public methods

        // After onGsmCallConnected (without active call, should stay IDLE)
        callManager.onGsmCallConnected();
        // State depends on previous state, in IDLE it should remain

        // After onGsmCallEnded
        callManager.onGsmCallEnded();
        assertEquals("Should be IDLE after GSM call ended", CallManager.CallState.IDLE, callManager.getState());
    }

    @Test
    public void testListenerCallback() {
        final boolean[] callbackCalled = {false};

        callManager.setListener(new CallManager.CallListener() {
            @Override
            public void onCallStateChanged(CallManager.CallState state) {
                callbackCalled[0] = true;
            }

            @Override
            public void onSipCallConnected(org.onetwoone.gateway.GatewayCall call) {}

            @Override
            public void onGsmCallNeeded(String destination, int simSlot) {}

            @Override
            public void onSipCallNeeded(String destination, String callerId, int simSlot) {}

            @Override
            public void onCallsTerminated() {}

            @Override
            public void onError(String error) {}
        });

        callManager.terminateAllCalls();
        assertTrue("Listener should be called on state change", callbackCalled[0]);
    }

    // ========== DTMF relay ==========

    /** Captures what CallManager hands to the GSM leg instead of touching Telecom. */
    private static class RecordingDtmfSender extends GsmDtmfSender {
        final StringBuilder sent = new StringBuilder();
        int clears;

        @Override
        public void enqueue(String digits) {
            sent.append(digits);
        }

        @Override
        public void clear() {
            clears++;
        }
    }

    private RecordingDtmfSender withDtmfSender(CallManager.CallState state) throws Exception {
        RecordingDtmfSender sender = new RecordingDtmfSender();
        callManager = new CallManager(app, GatewayConfig.getInstance(), sender);

        java.lang.reflect.Field stateField = CallManager.class.getDeclaredField("state");
        stateField.setAccessible(true);
        stateField.set(callManager, state);

        return sender;
    }

    @Test
    public void testDtmfIsRelayedDuringACall() throws Exception {
        RecordingDtmfSender sender = withDtmfSender(CallManager.CallState.BRIDGED);

        callManager.onSipDtmf("1");
        callManager.onSipDtmf("#");

        assertEquals("Digits should reach the GSM leg", "1#", sender.sent.toString());
    }

    @Test
    public void testDtmfIsIgnoredWhenIdle() throws Exception {
        RecordingDtmfSender sender = withDtmfSender(CallManager.CallState.IDLE);

        callManager.onSipDtmf("1");

        assertEquals("No call in progress, nothing to relay", "", sender.sent.toString());
    }

    @Test
    public void testDtmfIsIgnoredWhenRelayDisabled() throws Exception {
        RecordingDtmfSender sender = withDtmfSender(CallManager.CallState.BRIDGED);
        GatewayConfig.getInstance().setDtmfRelayEnabled(false);

        callManager.onSipDtmf("1");

        assertEquals("Relay is off", "", sender.sent.toString());

        GatewayConfig.getInstance().setDtmfRelayEnabled(true);
    }

    @Test
    public void testPendingDtmfIsDroppedOnTermination() throws Exception {
        RecordingDtmfSender sender = withDtmfSender(CallManager.CallState.BRIDGED);

        callManager.terminateAllCalls();

        assertEquals("Queued digits must not outlive the call", 1, sender.clears);
    }

    // ========== Outgoing SIP call registration (AUDIT D2 / GW-06) ==========

    /**
     * A stand-in for GatewayCall. The real one cannot be constructed on the JVM (its super
     * constructor goes straight into libpjsua2), so the identity, the disposed flag and
     * isActive() are faked - those are the only three things CallManager touches.
     */
    private static GatewayCall fakeCall() {
        GatewayCall call = mock(GatewayCall.class);
        AtomicBoolean disposed = new AtomicBoolean(false);
        doAnswer(inv -> {
            disposed.set(true);
            return null;
        }).when(call).dispose();
        when(call.isDisposed()).thenAnswer(inv -> disposed.get());
        when(call.isActive()).thenReturn(false);
        return call;
    }

    private void forceState(CallManager.CallState state) throws Exception {
        java.lang.reflect.Field stateField = CallManager.class.getDeclaredField("state");
        stateField.setAccessible(true);
        stateField.set(callManager, state);
    }

    /**
     * PJSIP can run onCallState(DISCONNECTED) synchronously, on the dialling thread, from
     * inside makeCall() - an immediate transport failure or a 403/404 from the PBX. If the
     * call is registered only afterwards, that callback cannot recognise it, and an
     * already-dead call ends up parked in currentSipCall forever.
     */
    @Test
    public void testSynchronousDisconnectDuringMakeCallLeavesNoPhantom() throws Exception {
        GatewayCall call = fakeCall();
        forceState(CallManager.CallState.SIP_INCOMING);

        boolean placed = callManager.placeOutgoingSipCall(call, c -> {
            // Exactly what GatewayCall.onCallState does for DISCONNECTED: flag itself
            // disposed, then hand the state to CallManager - inline, on this thread.
            c.dispose();
            callManager.onSipCallState(c, pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED);
        });

        assertTrue("makeCall itself did not throw", placed);
        assertNull("A dead call must not stay registered", callManager.getCurrentSipCall());
        assertEquals("State machine must be back to IDLE",
                CallManager.CallState.IDLE, callManager.getState());
        assertFalse("No live SIP call remains", callManager.hasLiveSipCall());
    }

    /**
     * The slot must be genuinely reusable after a failed call: neither the phantom reference
     * nor the refuse-to-overwrite rule may leave the gateway wedged. This is the criterion
     * PjsipSipService.startTestCall gates on, so a regression here makes the audio bridge
     * undiagnosable in the field.
     */
    @Test
    public void testSlotIsReusableAfterASynchronousFailure() throws Exception {
        GatewayCall failed = fakeCall();
        forceState(CallManager.CallState.SIP_INCOMING);

        callManager.placeOutgoingSipCall(failed, c -> {
            c.dispose();
            callManager.onSipCallState(c, pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED);
        });

        // This is the exact predicate PjsipSipService.startTestCall gates on.
        assertFalse("Diagnostic calls must be allowed again", callManager.hasLiveSipCall());

        // ...and the next real outgoing call must get through too.
        AtomicInteger placements = new AtomicInteger();
        GatewayCall next = fakeCall();
        assertTrue("The next outgoing call must not be refused",
                callManager.placeOutgoingSipCall(next, c -> placements.incrementAndGet()));
        assertEquals("Its INVITE must go out", 1, placements.get());
        assertSame(next, callManager.getCurrentSipCall());
    }

    /** A makeCall that throws must leave the same clean state as one that fails async. */
    @Test
    public void testThrowingMakeCallLeavesNoPhantom() throws Exception {
        GatewayCall call = fakeCall();
        forceState(CallManager.CallState.SIP_INCOMING);

        boolean placed = callManager.placeOutgoingSipCall(call, c -> {
            throw new Exception("transport error");
        });

        assertFalse("Placement failed", placed);
        assertNull("Nothing must stay registered", callManager.getCurrentSipCall());
        assertEquals("State machine must be back to IDLE",
                CallManager.CallState.IDLE, callManager.getState());
        assertTrue("The stillborn call must be disposed", call.isDisposed());
    }

    /** The throwing path from an already-IDLE state must not leave a registered phantom. */
    @Test
    public void testThrowingMakeCallFromIdleLeavesNoPhantom() throws Exception {
        GatewayCall call = fakeCall();

        callManager.placeOutgoingSipCall(call, c -> {
            throw new Exception("transport error");
        });

        assertNull("Nothing must stay registered", callManager.getCurrentSipCall());
        assertEquals(CallManager.CallState.IDLE, callManager.getState());
        assertTrue("The stillborn call must be disposed", call.isDisposed());
    }

    /**
     * Overwriting a live call would strand it: nothing else holds a reference, so it could
     * never be hung up. The new call is refused instead.
     */
    @Test
    public void testSetOutgoingSipCallRefusesToReplaceALiveCall() {
        GatewayCall live = fakeCall();
        assertTrue("First registration wins the slot", callManager.setOutgoingSipCall(live));

        GatewayCall second = fakeCall();
        assertFalse("A live call must not be silently replaced",
                callManager.setOutgoingSipCall(second));
        assertSame("The live call keeps the slot", live, callManager.getCurrentSipCall());
    }

    /** A refused call must never be dialled either. */
    @Test
    public void testPlaceOutgoingSipCallDoesNotDialOverALiveCall() {
        GatewayCall live = fakeCall();
        callManager.setOutgoingSipCall(live);

        AtomicInteger placements = new AtomicInteger();
        boolean placed = callManager.placeOutgoingSipCall(fakeCall(), c -> placements.incrementAndGet());

        assertFalse("Placement must be refused", placed);
        assertEquals("No INVITE may go out", 0, placements.get());
        assertSame("The live call keeps the slot", live, callManager.getCurrentSipCall());
    }

    /** A disposed leftover is not a call in progress - it must not wedge the slot. */
    @Test
    public void testSetOutgoingSipCallReplacesADisposedCall() {
        GatewayCall dead = fakeCall();
        callManager.setOutgoingSipCall(dead);
        dead.dispose();

        GatewayCall fresh = fakeCall();
        assertTrue("A disposed reference must not block the next call",
                callManager.setOutgoingSipCall(fresh));
        assertSame(fresh, callManager.getCurrentSipCall());
    }

    /** Compare-and-clear: a late failure report must not cancel somebody else's call. */
    @Test
    public void testOutgoingCallFailedDoesNotClearADifferentCall() {
        GatewayCall other = fakeCall();
        callManager.setOutgoingSipCall(other);

        GatewayCall stale = fakeCall();
        callManager.onOutgoingCallFailed(stale);

        assertSame("The current call must survive", other, callManager.getCurrentSipCall());
        assertFalse("...and must not be disposed", other.isDisposed());
        assertTrue("The stale call is disposed", stale.isDisposed());
    }
}
