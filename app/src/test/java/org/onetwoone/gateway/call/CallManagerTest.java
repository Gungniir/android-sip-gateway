package org.onetwoone.gateway.call;

import android.app.Application;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.onetwoone.gateway.BuildConfig;
import org.onetwoone.gateway.config.GatewayConfig;
import org.onetwoone.gateway.core.GatewayControlThread;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowLooper;

import org.onetwoone.gateway.GatewayCall;
import org.pjsip.pjsua2.pjsip_inv_state;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

/**
 * Unit tests for CallManager: phone-number validation, URI parsing, the outgoing-call
 * registration contract (AUDIT D2), and - since GW-11 - the explicit transition table and the
 * control-thread invariant.
 *
 * <p>The control thread runs on Robolectric's main looper, which is the test thread, so every
 * {@code assertOnControlThread} in production code is satisfied here. The suite-wide pattern:
 * see {@code GatewayControlThreadTest}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class CallManagerTest {

    private CallManager callManager;
    private GatewayControlThread control;
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

        control = new GatewayControlThread(Looper.getMainLooper(), null);
        callManager = new CallManager(app, GatewayConfig.getInstance(), control);
    }

    /** Records everything the manager tells its listener. */
    private static class RecordingListener implements CallManager.CallListener {
        final java.util.List<CallManager.CallState> states = new java.util.ArrayList<>();
        int terminated;
        int errors;
        String sipDestination;
        String sipCallerId;

        @Override
        public void onCallStateChanged(CallManager.CallState state) {
            states.add(state);
        }

        @Override
        public void onSipCallConnected(GatewayCall call) {}

        @Override
        public void onGsmCallNeeded(String destination, int simSlot) {}

        @Override
        public void onSipCallNeeded(String destination, String callerId, int simSlot) {
            sipDestination = destination;
            sipCallerId = callerId;
        }

        @Override
        public void onCallsTerminated() {
            terminated++;
        }

        @Override
        public void onError(String error) {
            errors++;
        }
    }

    private RecordingListener listen() {
        RecordingListener l = new RecordingListener();
        callManager.setListener(l);
        return l;
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
        callManager.onGsmCallConnected(1L);
        // State depends on previous state, in IDLE it should remain

        // After onGsmCallEnded
        callManager.onGsmCallEnded(1L);
        assertEquals("Should be IDLE after GSM call ended", CallManager.CallState.IDLE, callManager.getState());
    }

    /**
     * The listener sees every state change. Driven from BRIDGED rather than IDLE because
     * since GW-11 §3 terminating from IDLE is a no-op - and that is its own test below.
     */
    @Test
    public void testListenerCallback() throws Exception {
        RecordingListener listener = listen();
        forceState(CallManager.CallState.BRIDGED);

        callManager.terminateAllCalls();

        assertEquals("both edges of the teardown must be reported",
                java.util.Arrays.asList(CallManager.CallState.TERMINATING,
                        CallManager.CallState.IDLE),
                listener.states);
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
        callManager = new CallManager(app, GatewayConfig.getInstance(), control, sender);

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

    /**
     * GW-22 / AUDIT H7: the burial wiring, end to end, through the real looper.
     *
     * <p>{@link CallGraveyard} is unit-tested on its own; what this pins down is that
     * {@code hangupSipCall} actually hands the call over, and that the sweep the graveyard
     * schedules really runs on the control thread. The fake reports
     * {@code PJSUA_INVALID_ID}, i.e. pjsua has released the slot - the case where deletion is
     * allowed.
     */
    @Test
    public void hangupHandsTheCallToTheGraveyardAndItIsDeletedOnTheControlThread() {
        ShadowLooper looper = shadowOf(Looper.getMainLooper());
        GatewayCall call = fakeCall();
        when(call.getId()).thenReturn(CallGraveyard.PJSUA_INVALID_ID);

        assertTrue(callManager.setOutgoingSipCall(call));
        callManager.hangupSipCall();

        assertEquals("not deleted from inside hangupSipCall", 0L, callManager.getCallsBuried());

        looper.idleFor(Duration.ofSeconds(5));

        assertEquals(1L, callManager.getCallsBuried());
        verify(call).delete();
    }

    /**
     * The safety half. A call still holding a pjsua slot must not be deleted, however long the
     * looper runs - deleting one presents as a tombstone, not an exception.
     */
    @Test
    public void aCallStillHoldingItsPjsuaSlotIsNeverDeleted() {
        ShadowLooper looper = shadowOf(Looper.getMainLooper());
        GatewayCall call = fakeCall();
        when(call.getId()).thenReturn(9);

        assertTrue(callManager.setOutgoingSipCall(call));
        callManager.hangupSipCall();
        looper.idleFor(Duration.ofSeconds(30));

        assertEquals(0L, callManager.getCallsBuried());
        verify(call, never()).delete();
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
        // GSM_INCOMING, not SIP_INCOMING: this is the GSM→SIP direction, which before the
        // GW-11 split shared the inbound-SIP state.
        forceState(CallManager.CallState.GSM_INCOMING);

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
        forceState(CallManager.CallState.GSM_INCOMING);

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
        forceState(CallManager.CallState.GSM_INCOMING);

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

    // ========== Posted callbacks (GW-10) ==========
    //
    // PjsipSipService no longer runs onSipCallState inline on the pjsua worker; it does
    // control.post(...), so the handler runs as a later task. The two tests above still drive
    // it inline - deliberately, because PJSIP can still deliver inline *to the callback
    // thread* - but inline is no longer the path production takes, so these model the posted
    // one: the callback is separated in time from the event that produced it.

    /**
     * The reason the DISCONNECTED branch needed an identity guard.
     *
     * <p>Call A disconnects; before its handler is drained, call B takes the slot. Handling
     * A's disconnect must not tear down B. Without the guard, {@code terminateAllCalls()}
     * fires on A's news and kills a live, unrelated call.
     */
    @Test
    public void testStaleQueuedDisconnectDoesNotTerminateTheNextCall() throws Exception {
        GatewayCall callA = fakeCall();
        assertTrue(callManager.placeOutgoingSipCall(callA, c -> { }));
        forceState(CallManager.CallState.SIP_ANSWERED);

        // A ends. In production GatewayCall flips `disposed` on the callback thread and only
        // the handling is queued - so model exactly that: flag now, handler later.
        callA.dispose();

        // ...and while that handler is still queued, B takes the slot and gets going.
        GatewayCall callB = fakeCall();
        assertTrue("A disposed leftover must not block B", callManager.setOutgoingSipCall(callB));
        forceState(CallManager.CallState.BRIDGED);

        // Now A's queued DISCONNECTED is finally drained.
        callManager.onSipCallState(callA, pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED);

        assertSame("B must still hold the slot", callB, callManager.getCurrentSipCall());
        assertFalse("B must not have been disposed by A's disconnect", callB.isDisposed());
        assertEquals("B's session must still be up",
                CallManager.CallState.BRIDGED, callManager.getState());
    }

    /** The call that does hold the slot must still tear the session down when it ends. */
    @Test
    public void testCurrentCallsQueuedDisconnectStillTerminatesTheSession() throws Exception {
        GatewayCall call = fakeCall();
        assertTrue(callManager.placeOutgoingSipCall(call, c -> { }));
        forceState(CallManager.CallState.BRIDGED);

        call.dispose();
        callManager.onSipCallState(call, pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED);

        assertNull("The slot must be freed", callManager.getCurrentSipCall());
        assertEquals("The session must be torn down",
                CallManager.CallState.IDLE, callManager.getState());
    }

    /**
     * Register-before-dial still holds when the callback is separated from the dial: the
     * queued handler must find the call already registered, or it cannot clear it.
     */
    @Test
    public void testDeferredDisconnectAfterDialLeavesNoPhantom() throws Exception {
        GatewayCall call = fakeCall();
        forceState(CallManager.CallState.GSM_INCOMING);

        // makeCall returns cleanly; the DISCONNECTED it provoked is still queued.
        assertTrue(callManager.placeOutgoingSipCall(call, c -> c.dispose()));
        assertSame("registered before the dial", call, callManager.getCurrentSipCall());

        // The queued handler runs afterwards, on the control thread.
        callManager.onSipCallState(call, pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED);

        assertNull("A dead call must not stay registered", callManager.getCurrentSipCall());
        assertEquals(CallManager.CallState.IDLE, callManager.getState());
        assertFalse(callManager.hasLiveSipCall());
    }

    // ========== The transition table (GW-11 §2) ==========

    /** Drive {@code transition()} the way production does, without going through Telecom. */
    private boolean transition(CallManager.CallState from, CallManager.CallState to)
            throws Exception {
        Method m = CallManager.class.getDeclaredMethod("transition",
                CallManager.CallState.class, CallManager.CallState.class, String.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(callManager, from, to, "unit test");
    }

    /**
     * An edge that is not in the table is refused - logged, no-op, <em>not</em> thrown. A
     * throw here would kill a live gateway over a state-machine bug; the brief is explicit
     * that it must not.
     */
    @Test
    public void illegalTransitionIsRejectedAndDoesNotMoveTheMachine() throws Exception {
        RecordingListener listener = listen();
        forceState(CallManager.CallState.BRIDGED);

        // BRIDGED may only go to TERMINATING.
        assertFalse("BRIDGED to SIP_ANSWERED is not in the table",
                transition(CallManager.CallState.BRIDGED, CallManager.CallState.SIP_ANSWERED));

        assertEquals("the machine must not have moved",
                CallManager.CallState.BRIDGED, callManager.getState());
        assertTrue("a rejected transition must not be announced as one",
                listener.states.isEmpty());
    }

    /** IDLE is deliberately not a source for TERMINATING - there is nothing to terminate. */
    @Test
    public void idleMayNotTransitionStraightToTerminating() throws Exception {
        assertFalse(transition(CallManager.CallState.IDLE, CallManager.CallState.TERMINATING));
        assertEquals(CallManager.CallState.IDLE, callManager.getState());
    }

    /** A caller reasoning from a state the machine has already left must be refused too. */
    @Test
    public void transitionIsRejectedWhenTheMachineHasAlreadyMovedOn() throws Exception {
        forceState(CallManager.CallState.BRIDGED);

        // IDLE to SIP_INCOMING is a legal edge, but we are not in IDLE.
        assertFalse(transition(CallManager.CallState.IDLE, CallManager.CallState.SIP_INCOMING));
        assertEquals(CallManager.CallState.BRIDGED, callManager.getState());
    }

    /** ...and a legal edge from the state we really are in does move it, exactly once. */
    @Test
    public void legalTransitionMovesTheMachineAndTellsTheListener() throws Exception {
        RecordingListener listener = listen();
        forceState(CallManager.CallState.GSM_INCOMING);

        assertTrue(transition(CallManager.CallState.GSM_INCOMING,
                CallManager.CallState.SIP_DIALING));

        assertEquals(CallManager.CallState.SIP_DIALING, callManager.getState());
        assertEquals(java.util.Collections.singletonList(CallManager.CallState.SIP_DIALING),
                listener.states);
    }

    /**
     * The whole legal table, edge by edge, driven through the same method production uses.
     * If someone widens {@link CallManager.CallState} without widening the table, or the
     * other way round, this is what notices.
     */
    @Test
    public void everyDeclaredEdgeIsWalkableAndNothingElseIs() throws Exception {
        java.util.Map<CallManager.CallState, java.util.Set<CallManager.CallState>> legal =
                new java.util.EnumMap<>(CallManager.CallState.class);
        legal.put(CallManager.CallState.IDLE, java.util.EnumSet.of(
                CallManager.CallState.SIP_INCOMING, CallManager.CallState.GSM_INCOMING));
        legal.put(CallManager.CallState.SIP_INCOMING, java.util.EnumSet.of(
                CallManager.CallState.SIP_ANSWERED, CallManager.CallState.TERMINATING));
        legal.put(CallManager.CallState.GSM_INCOMING, java.util.EnumSet.of(
                CallManager.CallState.SIP_DIALING, CallManager.CallState.TERMINATING));
        legal.put(CallManager.CallState.SIP_ANSWERED, java.util.EnumSet.of(
                CallManager.CallState.GSM_DIALING, CallManager.CallState.TERMINATING));
        legal.put(CallManager.CallState.SIP_DIALING, java.util.EnumSet.of(
                CallManager.CallState.BRIDGED, CallManager.CallState.TERMINATING));
        legal.put(CallManager.CallState.GSM_DIALING, java.util.EnumSet.of(
                CallManager.CallState.BRIDGED, CallManager.CallState.TERMINATING));
        legal.put(CallManager.CallState.BRIDGED, java.util.EnumSet.of(
                CallManager.CallState.TERMINATING));
        legal.put(CallManager.CallState.TERMINATING, java.util.EnumSet.of(
                CallManager.CallState.IDLE));

        for (CallManager.CallState from : CallManager.CallState.values()) {
            for (CallManager.CallState to : CallManager.CallState.values()) {
                forceState(from);
                boolean expected = legal.get(from).contains(to);
                assertEquals(from + " to " + to, expected, transition(from, to));
                assertEquals("state after " + from + " to " + to,
                        expected ? to : from, callManager.getState());
            }
        }
    }

    // ========== Idempotent termination (GW-11 §3) ==========

    /**
     * Two terminations must produce one {@code onCallsTerminated()}. Before GW-11 each one
     * set TERMINATING, hung up, fired the callback and reset to IDLE - so the service ran
     * {@code stopBridge()} twice for a single call.
     */
    @Test
    public void doubleTerminationFiresOnCallsTerminatedExactlyOnce() throws Exception {
        RecordingListener listener = listen();
        forceState(CallManager.CallState.BRIDGED);

        callManager.terminateAllCalls();
        callManager.terminateAllCalls();

        assertEquals("the second termination must find nothing to do", 1, listener.terminated);
        assertEquals(CallManager.CallState.IDLE, callManager.getState());
    }

    /**
     * Re-entrant termination: {@code onCallsTerminated()} runs the audio teardown, and if
     * that path ever calls back in - the watchdog, a queued disconnect - the second entry
     * must find TERMINATING/IDLE and stop. Modelled by terminating from inside the callback.
     */
    @Test
    public void reentrantTerminationFromTheCallbackDoesNotRecurse() throws Exception {
        AtomicInteger reentries = new AtomicInteger();
        callManager.setListener(new RecordingListener() {
            @Override
            public void onCallsTerminated() {
                if (reentries.incrementAndGet() < 5) {
                    callManager.terminateAllCalls();
                }
            }
        });
        forceState(CallManager.CallState.BRIDGED);

        callManager.terminateAllCalls();

        assertEquals("the re-entrant call must find IDLE and return", 1, reentries.get());
    }

    /** Terminating with nothing up must not announce a teardown that never happened. */
    @Test
    public void terminatingFromIdleIsASilentNoOp() {
        RecordingListener listener = listen();

        callManager.terminateAllCalls();

        assertEquals(0, listener.terminated);
        assertTrue(listener.states.isEmpty());
        assertEquals(CallManager.CallState.IDLE, callManager.getState());
    }

    // ========== The SIP_INCOMING split (GW-11 §2) ==========

    /**
     * An inbound GSM call used to be reported to the UI as "Incoming SIP call", because it
     * reused {@code SIP_INCOMING} with a comment apologising for it. It now has its own state.
     */
    @Test
    public void inboundGsmCallIsNoLongerReportedAsAnIncomingSipCall() {
        GatewayConfig.getInstance().setSim1Destination("2001");
        RecordingListener listener = listen();

        callManager.onIncomingGsmCall("+79161234567", 1, 1L);

        assertEquals(CallManager.CallState.GSM_INCOMING, callManager.getState());
        assertEquals("Incoming GSM call", callManager.getStatusString());
        assertEquals("2001", listener.sipDestination);
        assertEquals("+79161234567", listener.sipCallerId);
    }

    /** ...and dialling its SIP leg moves it on, so the UI stops saying "ringing" mid-call. */
    @Test
    public void dialingTheSipLegOfAnInboundGsmCallReachesSipDialingThenBridged() {
        GatewayConfig.getInstance().setSim1Destination("2001");
        callManager.onIncomingGsmCall("+79161234567", 1, 2L);

        assertTrue(callManager.placeOutgoingSipCall(fakeCall(), c -> { }));
        assertEquals(CallManager.CallState.SIP_DIALING, callManager.getState());
        assertEquals("Dialing SIP...", callManager.getStatusString());

        // The GSM leg is answered once the PBX picks up.
        callManager.onGsmCallConnected(2L);
        assertEquals(CallManager.CallState.BRIDGED, callManager.getState());
        assertEquals("Call bridged", callManager.getStatusString());
    }

    /** The SIP to GSM direction keeps its own states and its own status strings. */
    @Test
    public void sipToGsmDirectionKeepsItsOwnStates() throws Exception {
        forceState(CallManager.CallState.SIP_ANSWERED);
        assertEquals("Dialing GSM...", callManager.getStatusString());

        forceState(CallManager.CallState.GSM_DIALING);
        assertEquals("GSM connecting...", callManager.getStatusString());

        callManager.onGsmCallConnected(3L);
        assertEquals(CallManager.CallState.BRIDGED, callManager.getState());
    }

    // ========== No realistic flow may trip the table (GW-11 Risk) ==========

    /** Every {@code ILLEGAL TRANSITION} the manager logged since the last clear. */
    private static java.util.List<String> illegalTransitionsLogged() {
        java.util.List<String> found = new java.util.ArrayList<>();
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if ("CallMgr".equals(item.tag) && item.msg != null
                    && item.msg.contains("ILLEGAL TRANSITION")) {
                found.add(item.msg);
            }
        }
        return found;
    }

    /**
     * The brief's headline risk: "the transition table will reject transitions the code
     * currently performs". This walks every call flow that can be driven on the JVM - both
     * directions, both teardown sides, and the failure branches - and fails on the first
     * rejection, naming it.
     *
     * <p>What it cannot reach is the SIP→GSM answer path, which runs {@code new CallOpParam()}
     * straight into libpjsua2; that edge ({@code SIP_INCOMING → SIP_ANSWERED}) is covered by
     * {@link #everyDeclaredEdgeIsWalkableAndNothingElseIs()} and on-device verification.
     */
    @Test
    public void noRealisticCallFlowTripsTheTransitionTable() throws Exception {
        GatewayConfig.getInstance().setSim1Destination("2001");
        ShadowLog.clear();

        // 1. GSM→SIP, answered, then hung up from the GSM side.
        callManager.onIncomingGsmCall("+79161234567", 1, 101L);
        assertTrue(callManager.placeOutgoingSipCall(fakeCall(), c -> { }));
        callManager.onGsmCallConnected(101L);
        callManager.onSipDtmf("1");
        callManager.onGsmCallEnded(101L);
        assertEquals("GSM-side hangup returns to IDLE",
                CallManager.CallState.IDLE, callManager.getState());

        // 2. GSM→SIP where the PBX refuses the INVITE inline.
        callManager.onIncomingGsmCall("+79161234567", 1, 102L);
        callManager.placeOutgoingSipCall(fakeCall(), c -> {
            c.dispose();
            callManager.onSipCallState(c, pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED);
        });
        assertEquals(CallManager.CallState.IDLE, callManager.getState());

        // 3. GSM→SIP where the GSM caller gives up while the INVITE is still out.
        callManager.onIncomingGsmCall("+79161234567", 1, 103L);
        callManager.placeOutgoingSipCall(fakeCall(), c -> { });
        callManager.onGsmCallEnded(103L);
        assertEquals(CallManager.CallState.IDLE, callManager.getState());

        // 4. GSM→SIP torn down from the SIP side once bridged.
        callManager.onIncomingGsmCall("+79161234567", 1, 104L);
        GatewayCall bridged = fakeCall();
        callManager.placeOutgoingSipCall(bridged, c -> { });
        callManager.onGsmCallConnected(104L);
        bridged.dispose();
        callManager.onSipCallState(bridged, pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED);
        assertEquals(CallManager.CallState.IDLE, callManager.getState());

        // 5. SIP→GSM from the point the SIP leg is answered. placeGsmCall reaches Telecom,
        //    which under Robolectric may refuse - either outcome is a legal path, and this
        //    asserts only that neither is an illegal transition.
        forceState(CallManager.CallState.SIP_ANSWERED);
        callManager.placeGsmCall("+79161234567", 1);
        callManager.onGsmCallConnected(105L);
        callManager.onGsmCallEnded(105L);
        assertEquals(CallManager.CallState.IDLE, callManager.getState());

        // 6. An INVITE arriving while a call is already up is rejected, not transitioned.
        forceState(CallManager.CallState.BRIDGED);
        callManager.onIncomingGsmCall("+79161234567", 1, 106L);
        assertEquals("a second inbound GSM call must not move the machine",
                CallManager.CallState.BRIDGED, callManager.getState());

        // 7. The teardown commands: hangupCall / doReloadConfig call this unguarded, and the
        //    watchdog calls it on an orphan. Twice, then again with nothing up.
        callManager.terminateAllCalls();
        callManager.terminateAllCalls();
        assertEquals(CallManager.CallState.IDLE, callManager.getState());

        assertEquals("no realistic flow may be rejected by the table",
                java.util.Collections.emptyList(), illegalTransitionsLogged());
    }

    /** ...and the check above is only worth anything if it can actually see a rejection. */
    @Test
    public void theIllegalTransitionLogIsWhatTheFlowCheckReads() throws Exception {
        forceState(CallManager.CallState.BRIDGED);
        ShadowLog.clear();

        assertFalse(transition(CallManager.CallState.BRIDGED, CallManager.CallState.IDLE));

        java.util.List<String> logged = illegalTransitionsLogged();
        assertEquals(1, logged.size());
        assertTrue("the log must name from, to and the reason",
                logged.get(0).contains("BRIDGED") && logged.get(0).contains("IDLE")
                        && logged.get(0).contains("unit test"));
    }

    // ========== The control-thread invariant (GW-11 §1) ==========

    /**
     * A reader on the wrong thread must be caught. This is the assertion that keeps the
     * threading model from eroding, and the one that would have fired once a second had the
     * 1 Hz UI poll not already been routed through the {@code GatewayStatus} snapshot.
     */
    @Test
    public void readingStateFromAnotherThreadTripsTheAssertion() throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        new Thread(() -> {
            try {
                callManager.getStatusString();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        }, "not-control").start();

        assertTrue(done.await(10, TimeUnit.SECONDS));

        if (BuildConfig.DEBUG) {
            assertNotNull("debug builds must fail loudly", thrown.get());
            assertTrue(thrown.get() instanceof IllegalStateException);
            assertTrue("the message must name the offending thread",
                    thrown.get().getMessage().contains("not-control"));
        } else {
            assertNull("release builds must not kill a live gateway over this", thrown.get());
        }
    }

    /** A mutator on the wrong thread, same rule. */
    @Test
    public void terminatingFromAnotherThreadTripsTheAssertion() throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        forceState(CallManager.CallState.BRIDGED);

        new Thread(() -> {
            try {
                callManager.terminateAllCalls();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        }, "not-control").start();

        assertTrue(done.await(10, TimeUnit.SECONDS));

        if (BuildConfig.DEBUG) {
            assertNotNull("debug builds must fail loudly", thrown.get());
            assertEquals("...and must not have torn anything down",
                    CallManager.CallState.BRIDGED, callManager.getState());
        } else {
            assertNull(thrown.get());
        }
    }

    /** The manager cannot exist without knowing which thread owns it. */
    @Test
    public void constructorRefusesAMissingControlThread() {
        try {
            new CallManager(app, GatewayConfig.getInstance(), null);
            fail("a CallManager with nothing to assert on is not a CallManager");
        } catch (IllegalArgumentException expected) {
            // as specified
        }
    }

    /** The raw timestamp the status snapshot carries instead of a frozen boolean. */
    @Test
    public void testGsmCallPlacedTimestampIsExposedForTheSnapshot() throws Exception {
        assertEquals("Nothing dialled yet", 0L, callManager.getGsmCallPlacedAtWallMs());

        java.lang.reflect.Field placedAt = CallManager.class.getDeclaredField("gsmCallPlacedTime");
        placedAt.setAccessible(true);
        long now = System.currentTimeMillis();
        placedAt.set(callManager, now);

        assertEquals(now, callManager.getGsmCallPlacedAtWallMs());
        assertTrue(callManager.isInGracePeriod());
    }
}
