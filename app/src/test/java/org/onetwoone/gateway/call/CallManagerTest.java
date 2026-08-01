package org.onetwoone.gateway.call;

import android.app.Application;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.onetwoone.gateway.config.GatewayConfig;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;

import static org.junit.Assert.*;

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
}
