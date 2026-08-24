package org.onetwoone.gateway;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.os.Looper;
import android.telecom.Call;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.onetwoone.gateway.audio.AudioBridgeManager;
import org.onetwoone.gateway.call.CallManager;
import org.onetwoone.gateway.config.GatewayConfig;
import org.onetwoone.gateway.core.GatewayControlThread;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import static org.robolectric.Shadows.shadowOf;

import java.lang.reflect.Field;

/**
 * GW-13 — the GSM leg has exactly one source of truth, and it tears the audio down.
 *
 * <h3>What this pins</h3>
 * <b>Plan §3d, the gate.</b> GW-11 made {@code terminateAllCalls()} return early when the
 * machine is already {@code IDLE}, so it no longer fires {@code onCallsTerminated()} and no
 * longer stops the audio streams from {@code IDLE}. That was safe only because
 * {@code handlePhoneState}'s {@code CALL_STATE_IDLE} branch stopped them unconditionally —
 * and GW-13 demotes that path. If the unconditional stop is not carried across to the Telecom
 * path, a GSM leg that never reaches {@code BRIDGED} loses both teardown routes at once and
 * leaves the {@code MixerEnforce} thread re-asserting the mic mute every 2 s with no call:
 * a phone with a dead microphone until reboot.
 *
 * <p>Also pinned here: identity. A {@code DISCONNECTED} naming a leg that is no longer current
 * must not stop the audio of the call that replaced it (the stale-stop scenario), connect and
 * end are idempotent per leg, and exactly one {@code DeviceMuteManager} lease is taken out per
 * call.
 *
 * <h3>How</h3>
 * {@code PjsipSipService} is built without {@code onCreate()} — which would go into libpjsua2 —
 * and its collaborators are injected. {@code CallManager} is the real one, so the transition
 * table is exercised for real and an illegal transition would show up; the audio bridge is a
 * mock because the assertion is about which calls it receives.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class GsmCallLifecycleTest {

    private static final long CALL_1 = 11L;
    private static final long CALL_2 = 22L;

    private PjsipSipService service;
    private AudioBridgeManager audioBridge;
    private CallManager callManager;
    private GatewayControlThread control;

    @Before
    public void setUp() throws Exception {
        Application app = RuntimeEnvironment.getApplication();

        Field configInstance = GatewayConfig.class.getDeclaredField("instance");
        configInstance.setAccessible(true);
        configInstance.set(null, null);
        GatewayConfig.init(app);
        GatewayConfig.getInstance().setSim1Destination("2001");

        // The control thread runs on Robolectric's main looper, which is the test thread, so
        // every assertOnControlThread in production code is satisfied inline. Same pattern as
        // CallManagerTest / GatewayControlThreadTest.
        control = new GatewayControlThread(Looper.getMainLooper(), null);

        audioBridge = mock(AudioBridgeManager.class);
        // The SoC profile handles the mic mute, so DeviceMuteManager stays out of the picture
        // except where a test asks for it explicitly.
        when(audioBridge.handlesMicMute()).thenReturn(true);

        callManager = new CallManager(app, GatewayConfig.getInstance(), control);

        // buildService attaches a base context but does not run onCreate().
        service = Robolectric.buildService(PjsipSipService.class).get();
        inject("control", control);
        inject("audioBridge", audioBridge);
        inject("callManager", callManager);
    }

    private void inject(String name, Object value) throws Exception {
        Field f = PjsipSipService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private void gsmActive(long gsmCallId) {
        service.handleGsmCallState(gsmCallId, Call.STATE_ACTIVE);
    }

    private void gsmDisconnected(long gsmCallId) {
        service.handleGsmCallState(gsmCallId, Call.STATE_DISCONNECTED);
    }

    /**
     * The {@code onCallRemoved} backstop is a {@code control.post}, so drain the looper -
     * Robolectric's main looper is paused, and the control thread is that looper here.
     */
    private void gsmRemoved(long gsmCallId) {
        service.onGsmCallRemoved(gsmCallId);
        shadowOf(Looper.getMainLooper()).idle();
    }

    // ========== The gate: plan §3d ==========

    /**
     * The one that must never regress. A GSM leg goes active, the SIP side never gets far
     * enough to bridge it — {@code CallManager} is still {@code IDLE}, so
     * {@code terminateAllCalls()} returns early and {@code onCallsTerminated()} never fires —
     * and then the leg ends. The audio streams must still be stopped.
     */
    @Test
    public void gsmCallThatNeverReachedBridgedStillStopsTheAudioStreams() {
        gsmActive(CALL_1);
        verify(audioBridge, times(1)).startAudioStreams();

        assertEquals("precondition: the machine never left IDLE, so terminateAllCalls() is a"
                        + " no-op and cannot stop the streams for us",
                CallManager.CallState.IDLE, callManager.getState());

        gsmDisconnected(CALL_1);

        verify(audioBridge, times(1)).stopAudioStreams();
    }

    /**
     * The same, one step earlier: an inbound GSM call whose SIP leg is still being dialled,
     * hung up during ring. {@code CallManager} is in {@code GSM_INCOMING}, never
     * {@code BRIDGED}. The streams were never started here, but the stop must still be issued
     * — the mixer routing and the enforce thread are set up by {@code setupMixer}, not by the
     * bridge reaching {@code BRIDGED}.
     */
    @Test
    public void gsmHangupDuringRingStillStopsTheAudioStreams() {
        callManager.onIncomingGsmCall("+79161234567", 1, CALL_1);
        assertEquals(CallManager.CallState.GSM_INCOMING, callManager.getState());

        gsmDisconnected(CALL_1);

        verify(audioBridge, times(1)).stopAudioStreams();
        assertEquals(CallManager.CallState.IDLE, callManager.getState());
    }

    /**
     * The ordinary bridged call: the Telecom path stops the streams on its own, without help
     * from {@code onCallsTerminated()}.
     *
     * <p>{@code CallManager} has no listener wired here, which is deliberate — it is what
     * makes the assertion about <em>this</em> path rather than about the teardown that
     * happens to follow. In production the listener is wired, so a bridged call whose GSM
     * side ends first stops the streams twice: once here and once from
     * {@code onCallsTerminated()}. That is unchanged from before GW-13 (the
     * {@code PhoneStateListener}'s IDLE branch was the first of the two) and it is benign —
     * {@code GsmAudioPort.stopCapture()} on an already-stopped session releases nothing — but
     * it does mean two {@code Audio streams stopped} lines per such call. See the report.
     */
    @Test
    public void aBridgedCallStopsTheAudioStreamsFromTheTelecomPath() {
        callManager.onIncomingGsmCall("+79161234567", 1, CALL_1);
        gsmActive(CALL_1);
        gsmDisconnected(CALL_1);

        verify(audioBridge, times(1)).startAudioStreams();
        verify(audioBridge, times(1)).stopAudioStreams();
    }

    // ========== Identity: the stale-stop scenario ==========

    /**
     * Back-to-back calls, {@code <1 s} apart. Call 1's {@code DISCONNECTED} is delivered late,
     * after call 2 has already gone active. Without identity it tears down the mixer and
     * closes the PCM of the call that just started — a silent call with no error anywhere.
     */
    @Test
    public void aLateEndForThePreviousCallDoesNotStopTheLiveCallsAudio() {
        gsmActive(CALL_1);
        gsmDisconnected(CALL_1);
        verify(audioBridge, times(1)).stopAudioStreams();

        gsmActive(CALL_2);
        verify(audioBridge, times(2)).startAudioStreams();

        // Call 1's end, arriving late. The stop count must not move: call 2's mixer routing
        // and PCM stay open.
        gsmDisconnected(CALL_1);

        verify(audioBridge, times(1)).stopAudioStreams();
    }

    /**
     * The sharper version, and the one the issue actually describes: call 1's
     * {@code DISCONNECTED} never arrived at all before call 2 went active — the modem or
     * Telecom simply delivered it late. Nothing has "already ended", so only the identity of
     * the live leg can reject it. Without that check the late event closes the PCM and tears
     * down the mixer of the call that is talking: a silent call, no error anywhere.
     */
    @Test
    public void anUndeliveredEndForCall1ArrivingAfterCall2IsActiveIsRejectedOnIdentity() {
        gsmActive(CALL_1);
        // Call 1 never reports its end; call 2 takes over the tracked leg.
        gsmActive(CALL_2);
        verify(audioBridge, times(2)).startAudioStreams();
        verify(audioBridge, never()).stopAudioStreams();

        // ...and now call 1's DISCONNECTED finally lands.
        gsmDisconnected(CALL_1);

        verify(audioBridge, never()).stopAudioStreams();
    }

    /**
     * The same shape one layer down: the state machine must ignore the stale end too, or it
     * terminates the live call's legs even though its audio survived.
     */
    @Test
    public void aLateEndForThePreviousCallDoesNotTerminateTheLiveCall() {
        callManager.onIncomingGsmCall("+79161234567", 1, CALL_1);
        gsmActive(CALL_1);
        gsmDisconnected(CALL_1);

        callManager.onIncomingGsmCall("+79161234567", 1, CALL_2);
        assertEquals(CallManager.CallState.GSM_INCOMING, callManager.getState());

        callManager.onGsmCallEnded(CALL_1);

        assertEquals("call 1's end must not terminate call 2",
                CallManager.CallState.GSM_INCOMING, callManager.getState());
    }

    // ========== Idempotency ==========

    /** A second ACTIVE for the same leg starts nothing a second time. */
    @Test
    public void aRepeatedConnectForTheSameCallIsANoOp() {
        gsmActive(CALL_1);
        gsmActive(CALL_1);
        gsmActive(CALL_1);

        verify(audioBridge, times(1)).startAudioStreams();
    }

    /**
     * Exactly one {@code DeviceMuteManager} lease per call. {@code handlesMicMute()} is
     * consulted once per lease decision and nowhere else on this path, so counting it counts
     * the leases — without dragging the real mute manager, its {@code HandlerThread} and its
     * {@code tinymix} writes into a unit test.
     */
    @Test
    public void exactlyOneMuteLeaseIsTakenOutPerCall() {
        when(audioBridge.handlesMicMute()).thenReturn(true);

        gsmActive(CALL_1);
        gsmActive(CALL_1);
        gsmDisconnected(CALL_1);

        verify(audioBridge, times(1)).handlesMicMute();
    }

    /**
     * A second end for a leg already torn down stops nothing a second time. This is what keeps
     * the {@code onCallRemoved} backstop free: it fires for every call, right after the
     * {@code DISCONNECTED} that already did the work.
     */
    @Test
    public void aRepeatedEndForTheSameCallIsANoOp() {
        gsmActive(CALL_1);
        gsmDisconnected(CALL_1);
        gsmDisconnected(CALL_1);
        gsmRemoved(CALL_1);

        verify(audioBridge, times(1)).stopAudioStreams();
    }

    /**
     * ...and if the {@code DISCONNECTED} never arrives at all, the backstop is what stops the
     * streams. This is the case the {@code PhoneStateListener} used to cover before GW-13
     * demoted it.
     */
    @Test
    public void callRemovalTearsTheAudioDownWhenNoDisconnectedArrives() {
        gsmActive(CALL_1);
        verify(audioBridge, never()).stopAudioStreams();

        gsmRemoved(CALL_1);

        verify(audioBridge, times(1)).stopAudioStreams();
    }

    // ========== The transition table must stay quiet ==========

    /**
     * On hardware the GW-11 table logs zero rejections, and GW-13 must keep it that way.
     * Identity adds early returns, never new edges — but an early return in the wrong place
     * would leave the machine somewhere the next event cannot legally leave. This walks the
     * flows GW-13 introduces, including the ones that are meant to be dropped, and fails on
     * the first rejection.
     */
    @Test
    public void noGw13FlowTripsTheTransitionTable() {
        ShadowLog.clear();

        // Bridged GSM→SIP call, ended from the GSM side, with the backstop behind it.
        callManager.onIncomingGsmCall("+79161234567", 1, CALL_1);
        gsmActive(CALL_1);
        gsmDisconnected(CALL_1);
        gsmRemoved(CALL_1);
        assertEquals(CallManager.CallState.IDLE, callManager.getState());

        // A leg that never bridges: rings, then hangs up.
        callManager.onIncomingGsmCall("+79161234567", 1, CALL_2);
        gsmDisconnected(CALL_2);
        assertEquals(CallManager.CallState.IDLE, callManager.getState());

        // Duplicates and stale events, which must all be dropped rather than applied.
        gsmActive(CALL_1);
        gsmActive(CALL_1);
        gsmDisconnected(CALL_2);
        gsmDisconnected(CALL_1);
        gsmDisconnected(CALL_1);
        gsmActive(GatewayInCallService.NO_GSM_CALL);
        assertEquals(CallManager.CallState.IDLE, callManager.getState());

        java.util.List<String> rejections = new java.util.ArrayList<>();
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (item.msg != null && item.msg.contains("ILLEGAL TRANSITION")) {
                rejections.add(item.tag + ": " + item.msg);
            }
        }
        assertEquals("no GW-13 flow may be rejected by the transition table",
                java.util.Collections.emptyList(), rejections);
    }

    // ========== The demoted PhoneStateListener ==========

    /**
     * The listener path is gone as a driver: an event for a call the InCallService is not
     * tracking cannot start audio, and nothing outside the Telecom path can.
     *
     * <p>Honest limitation: this one passes with or without the explicit
     * {@code NO_GSM_CALL} guard in {@code handleGsmCallConnected}, because the sentinel is
     * {@code 0} and so is the cleared {@code currentGsmCallId} — the duplicate-connect guard
     * catches it either way. The guard is kept for the log line, which would otherwise call
     * an untracked call a duplicate. Revert-and-confirm-failure is therefore not evidence for
     * that specific line; the behaviour asserted here is still real.
     */
    @Test
    public void anUntrackedCallCannotStartTheAudioStreams() {
        gsmActive(GatewayInCallService.NO_GSM_CALL);

        verify(audioBridge, never()).startAudioStreams();
        assertEquals(CallManager.CallState.IDLE, callManager.getState());
    }
}
