package org.onetwoone.gateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.onetwoone.gateway.audio.AudioBridgeManager;
import org.onetwoone.gateway.call.CallManager;
import org.onetwoone.gateway.config.GatewayConfig;
import org.onetwoone.gateway.core.GatewayControlThread;
import org.onetwoone.gateway.core.GatewayStatus;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GW-25 — the watchdog's invariant rules, and above all the healthy calls they must never
 * touch.
 *
 * <h3>Why the negative tests come first</h3>
 * A watchdog that terminates healthy calls is worse than no watchdog, and this one has three
 * shapes that look exactly like an orphan and are not:
 *
 * <ol>
 *   <li><b>The inbound pre-answer window.</b> {@code gsmCallPlacedTime} is assigned in exactly
 *       one place — {@code CallManager.placeGsmCall()}, the SIP→GSM dial — so
 *       {@code isInGracePeriod()} is permanently {@code false} for the whole GSM→SIP
 *       direction. On {@code MODE_ANSWER_FIRST} the GSM leg is answered <em>first</em>, so
 *       Telecom reports {@code STATE_ACTIVE} and {@code currentGsmCallId} is adopted while
 *       {@code GatewayInCallService.makeSipCallWithRetry} still has up to
 *       {@code 40 × 500 ms ≈ 20 s} of retries ahead of it, and going ACTIVE has already
 *       cancelled the 30 s incoming timeout. A reverse-orphan rule without a dwell hangs up
 *       every single inbound call.
 *   <li><b>The diagnostic test call.</b> {@code SipTestCallManager} in {@code BRIDGE} mode
 *       wires the real bridge with {@code CallManager} at {@code IDLE} and no GSM leg at all.
 *       Any "bridge active but a leg is missing → terminate" rule kills it.
 *   <li><b>A transient {@code InCallService} unbind.</b> {@code getInstance() == null} reads
 *       as "no GSM leg" by design, so an unbind is indistinguishable from an orphan unless the
 *       rules check for it.
 * </ol>
 *
 * <h3>How</h3>
 * Same harness as {@code GsmCallLifecycleTest}: {@code PjsipSipService} is built without
 * {@code onCreate()} — which would go into libpjsua2 — and its collaborators are injected. The
 * {@code CallManager} is the real one so the transition table is exercised for real; the audio
 * bridge and the {@code InCallService} are mocks, because what is being asserted is which
 * calls they receive. Wall-clock dwell is simulated by back-dating the watchdog's own clock
 * fields rather than by sleeping.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class WatchdogInvariantsTest {

    private static final long GSM_LEG = 77L;

    /** Where the SIP retry chain gives up: {@code MAX_SIP_RETRIES(40) × 500 ms}. */
    private static final long SIP_RETRY_CHAIN_MS = 20_000L;

    /** {@code GatewayInCallService.INCOMING_TIMEOUT_MS}. */
    private static final long INCOMING_TIMEOUT_MS = 30_000L;

    private PjsipSipService service;
    private AudioBridgeManager audioBridge;
    private CallManager callManager;
    private GatewayInCallService inCallService;
    private GatewayControlThread control;

    @Before
    public void setUp() throws Exception {
        Application app = RuntimeEnvironment.getApplication();

        Field configInstance = GatewayConfig.class.getDeclaredField("instance");
        configInstance.setAccessible(true);
        configInstance.set(null, null);
        GatewayConfig.init(app);

        // The control thread runs on Robolectric's main looper, which is the test thread, so
        // every assertOnControlThread in production code is satisfied inline.
        control = new GatewayControlThread(Looper.getMainLooper(), null);

        audioBridge = mock(AudioBridgeManager.class);
        when(audioBridge.handlesMicMute()).thenReturn(true);
        // The diagnostic test call never calls startAudioStreams(); neither does an idle
        // gateway. Tests that want a live bridge say so.
        when(audioBridge.isAudioStreaming()).thenReturn(false);

        callManager = new CallManager(app, GatewayConfig.getInstance(), control);

        inCallService = mock(GatewayInCallService.class);
        bindInCallService(inCallService);

        service = Robolectric.buildService(PjsipSipService.class).get();
        inject("control", control);
        inject("audioBridge", audioBridge);
        inject("callManager", callManager);
    }

    @After
    public void tearDown() throws Exception {
        bindInCallService(null);
    }

    // ========== harness ==========

    private void inject(String name, Object value) throws Exception {
        Field f = PjsipSipService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private long getLong(String name) throws Exception {
        Field f = PjsipSipService.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getLong(service);
    }

    /** {@code GatewayInCallService.instance} is static; null models a transient unbind. */
    private static void bindInCallService(GatewayInCallService value) throws Exception {
        Field f = GatewayInCallService.class.getDeclaredField("instance");
        f.setAccessible(true);
        f.set(null, value);
    }

    private void callManagerState(CallManager.CallState state) throws Exception {
        Field f = CallManager.class.getDeclaredField("state");
        f.setAccessible(true);
        f.set(callManager, state);
    }

    /**
     * Puts the service where {@code handleGsmCallConnected} would: the leg has been reported
     * {@code STATE_ACTIVE} by Telecom and its end has not been processed. That — not
     * {@code hasLiveGsmCall()}, which is also true for RINGING/DIALING/HOLDING — is what the
     * watchdog means by "the GSM leg is up".
     */
    private void trackGsmLeg(long gsmCallId) throws Exception {
        inject("currentGsmCallId", gsmCallId);
    }

    /** A stand-in for GatewayCall; the real one's super constructor goes into libpjsua2. */
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

    private void registerSipCall(GatewayCall call) throws Exception {
        Field f = CallManager.class.getDeclaredField("currentSipCall");
        f.setAccessible(true);
        f.set(callManager, call);
    }

    /** Pretend the reverse-orphan clock has already been running for {@code elapsedMs}. */
    private void backdateGsmWithoutSip(long gsmCallId, long elapsedMs) throws Exception {
        inject("gsmWithoutSipLegId", gsmCallId);
        inject("gsmWithoutSipSinceWallMs", System.currentTimeMillis() - elapsedMs);
    }

    private long terminations() throws Exception {
        return getLong("watchdogTerminations");
    }

    // ==================================================================================
    //  The false positives. These are worth more than every positive test in this file.
    // ==================================================================================

    /**
     * <b>The one that must never regress.</b> A ringing inbound call in {@code ANSWER_FIRST}
     * mode: Telecom has answered the GSM leg so it is ACTIVE and tracked, the SIP retry chain
     * is still running so there is no SIP leg, {@code CallManager} has not left {@code IDLE},
     * and — because {@code gsmCallPlacedTime} is only ever set by the SIP→GSM dial — the grace
     * period reads {@code false}. Every signal a naive reverse-orphan rule looks at says
     * "orphan". It is a perfectly healthy call.
     *
     * <p>The tick is driven at the two instants that matter: immediately, and at the moment
     * the retry chain gives up.
     */
    @Test
    public void inboundCallInThePreAnswerWindowIsNotTerminated() throws Exception {
        trackGsmLeg(GSM_LEG);
        when(inCallService.hasLiveGsmCall()).thenReturn(true);

        assertEquals("precondition: the machine has not left IDLE",
                CallManager.CallState.IDLE, callManager.getState());
        assertFalse("precondition: the inbound direction has no grace period at all",
                callManager.isInGracePeriod());

        service.checkOrphanedCalls();

        backdateGsmWithoutSip(GSM_LEG, SIP_RETRY_CHAIN_MS);
        service.checkOrphanedCalls();

        backdateGsmWithoutSip(GSM_LEG, INCOMING_TIMEOUT_MS);
        service.checkOrphanedCalls();

        assertEquals("a healthy inbound call was terminated", 0L, terminations());
        verify(audioBridge, never()).stopAudioStreams();
        verify(inCallService, never()).disconnectCall();
        assertEquals("and the leg is still tracked", GSM_LEG, getLong("currentGsmCallId"));
    }

    /**
     * The diagnostic test call: {@code SipTestCallManager} in {@code BRIDGE} mode sets
     * {@code Wiring.active} with {@code CallManager} at {@code IDLE} and <em>no GSM leg at
     * all</em>, and its {@code GatewayCall} is demuxed by {@code isDiagnostic()} so it never
     * reaches {@code CallManager}. It also never calls {@code startAudioStreams()}, which is
     * the discriminator the silent-bridge detector uses.
     */
    @Test
    public void diagnosticBridgeCallIsNotTerminated() throws Exception {
        when(audioBridge.isBridgeActive()).thenReturn(true);
        when(audioBridge.isAudioStreaming()).thenReturn(false);
        when(inCallService.hasLiveGsmCall()).thenReturn(false);

        for (int tick = 0; tick < 5; tick++) {
            service.checkOrphanedCalls();
        }

        assertEquals("the diagnostic test call was terminated", 0L, terminations());
        verify(audioBridge, never()).stopAudioStreams();
        verify(inCallService, never()).disconnectCall();
    }

    /**
     * A transient unbind. {@code GatewayInCallService.getInstance() == null} reads as "no GSM
     * leg" by design, so without an explicit check every orphan rule fires at once and tears
     * down a live call because a <em>Service</em> was rebound.
     */
    @Test
    public void transientInCallServiceUnbindIsNotAnOrphan() throws Exception {
        bindInCallService(null);
        trackGsmLeg(GSM_LEG);
        callManagerState(CallManager.CallState.BRIDGED);

        service.checkOrphanedCalls();

        assertEquals(0L, terminations());
        verify(audioBridge, never()).stopAudioStreams();
        assertEquals(GSM_LEG, getLong("currentGsmCallId"));
    }

    /** An idle gateway with nothing going on must not find anything to repair. */
    @Test
    public void anIdleGatewayFindsNothing() throws Exception {
        when(inCallService.hasLiveGsmCall()).thenReturn(false);

        service.checkOrphanedCalls();

        assertEquals(0L, terminations());
        assertEquals("", service.getStatusSnapshot().getWatchdog().getLastFinding());
        verify(audioBridge, never()).stopAudioStreams();
    }

    /**
     * {@code publishStatus()} is the first statement of the tick and sits ahead of every early
     * return — it is the only thing keeping the 1 Hz UI fresh between call events, and each of
     * the rules above returns early.
     */
    @Test
    public void everyTickRepublishesTheStatusEvenWhenItReturnsEarly() throws Exception {
        bindInCallService(null);  // the earliest early return there is

        GatewayStatus before = service.getStatusSnapshot();
        service.checkOrphanedCalls();
        GatewayStatus after = service.getStatusSnapshot();

        assertTrue("the tick must have published a fresh snapshot", after != before);
        assertTrue(after.getCapturedAtWallMs() > 0L);
    }

    // ==================================================================================
    //  The rules themselves.
    // ==================================================================================

    /**
     * AUDIT D6. GW-13 kept the {@code PhoneStateListener} as a cross-check that logs a
     * discrepancy and repairs nothing, and {@code checkOrphanedCalls} could not see this case
     * either because it returned early unless {@code hasActiveCall()} — and the leg in
     * question has already left the state machine. The state left behind is the one GW-08 was
     * written to kill: audio streams up, {@code MixerEnforce} re-asserting the mic mute every
     * 2 s, and no call.
     *
     * <p>The trigger is deliberately Telecom-based; reading the modem instead would re-create
     * the second source of truth GW-13 deleted.
     */
    @Test
    public void aTrackedLegTelecomHasLostIsReaped() throws Exception {
        trackGsmLeg(GSM_LEG);
        when(inCallService.hasLiveGsmCall()).thenReturn(false);

        service.checkOrphanedCalls();

        assertEquals(1L, terminations());
        // handleGsmCallEnded, not terminateAllCalls: the machine never left IDLE, so
        // terminateAllCalls() would have returned early and stopped nothing (plan §3d).
        verify(audioBridge, times(1)).stopAudioStreams();
        assertEquals("the leg must be released", GatewayInCallService.NO_GSM_CALL,
                getLong("currentGsmCallId"));
        assertTrue(service.getStatusSnapshot().getWatchdog().getLastFinding()
                .contains("Telecom no longer has it"));
    }

    /**
     * AUDIT H9, the reverse orphan: the direction the old watchdog never detected at all, so a
     * failed bridge burned GSM minutes until the far end hung up. Fires only once the dwell
     * has run out — see {@link #inboundCallInThePreAnswerWindowIsNotTerminated()} for why
     * there has to be one.
     */
    @Test
    public void aGsmLegWithNoSipLegIsReapedOnceTheDwellHasElapsed() throws Exception {
        trackGsmLeg(GSM_LEG);
        when(inCallService.hasLiveGsmCall()).thenReturn(true);
        backdateGsmWithoutSip(GSM_LEG, PjsipSipService.GSM_WITHOUT_SIP_MAX_MS + 1_000L);

        service.checkOrphanedCalls();

        assertEquals(1L, terminations());
        verify(audioBridge, times(1)).stopAudioStreams();
        assertEquals(GatewayInCallService.NO_GSM_CALL, getLong("currentGsmCallId"));
        assertTrue(service.getStatusSnapshot().getWatchdog().getLastFinding()
                .contains("no SIP leg"));
    }

    /** A live SIP leg is the whole point of the bridge: with one, the dwell never starts. */
    @Test
    public void aGsmLegWithASipLegIsNeverAReverseOrphan() throws Exception {
        trackGsmLeg(GSM_LEG);
        when(inCallService.hasLiveGsmCall()).thenReturn(true);
        callManagerState(CallManager.CallState.BRIDGED);
        registerSipCall(fakeCall());
        backdateGsmWithoutSip(GSM_LEG, PjsipSipService.GSM_WITHOUT_SIP_MAX_MS * 10);

        service.checkOrphanedCalls();

        assertEquals(0L, terminations());
        verify(audioBridge, never()).stopAudioStreams();
    }

    /**
     * The brief's §2b. {@code checkOrphanedCalls} short-circuited on {@code !hasActiveCall()},
     * so {@code IDLE} with a live registered SIP call was invisible to it forever.
     * {@code terminateAllCalls()} is no remedy here — it returns early from {@code IDLE} — so
     * the reap goes through {@code hangupSipCall()}.
     */
    @Test
    public void idleWithARegisteredLiveSipCallIsReaped() throws Exception {
        when(inCallService.hasLiveGsmCall()).thenReturn(false);
        GatewayCall forgotten = fakeCall();
        registerSipCall(forgotten);

        assertEquals(CallManager.CallState.IDLE, callManager.getState());
        assertTrue("precondition", callManager.hasLiveSipCall());

        service.checkOrphanedCalls();

        assertFalse("the forgotten call must be gone", callManager.hasLiveSipCall());
        verify(forgotten, times(1)).dispose();
        assertEquals(1L, terminations());
    }

    /** A disposed leftover is not a call in progress and must not be reported as one. */
    @Test
    public void idleWithADisposedSipCallIsNotAFinding() throws Exception {
        when(inCallService.hasLiveGsmCall()).thenReturn(false);
        GatewayCall disposed = fakeCall();
        disposed.dispose();
        registerSipCall(disposed);

        service.checkOrphanedCalls();

        assertEquals(0L, terminations());
    }

    /** H9's original direction, unchanged: a SIP call with no GSM leg. */
    @Test
    public void aSipCallWithNoGsmLegIsStillTerminated() throws Exception {
        when(inCallService.hasLiveGsmCall()).thenReturn(false);
        callManagerState(CallManager.CallState.SIP_ANSWERED);
        registerSipCall(fakeCall());

        service.checkOrphanedCalls();

        assertEquals(1L, terminations());
        assertEquals(CallManager.CallState.IDLE, callManager.getState());
    }

    // ========== Hard deadline: max call duration ==========

    /**
     * GW-25 §3. There was no call-start timestamp anywhere before this —
     * {@code gsmCallPlacedTime} is SIP→GSM-only and is cleared on end — so a call whose
     * teardown was missed had no upper bound at all.
     */
    @Test
    public void aCallPastTheMaximumDurationIsTerminated() throws Exception {
        trackGsmLeg(GSM_LEG);
        when(inCallService.hasLiveGsmCall()).thenReturn(true);
        callManagerState(CallManager.CallState.BRIDGED);
        inject("callUpSinceWallMs",
                System.currentTimeMillis() - PjsipSipService.MAX_CALL_DURATION_MS - 1_000L);

        service.checkOrphanedCalls();

        assertEquals(1L, terminations());
        verify(audioBridge, times(1)).stopAudioStreams();
        assertTrue(service.getStatusSnapshot().getWatchdog().getLastFinding()
                .contains("past the"));
    }

    /**
     * The deadline is a fail-safe, so it must survive the shape where every other rule returns
     * early: a permanently unbound {@code InCallService}. That is what stops the unbind check
     * above from turning into a way for a call to live forever.
     */
    @Test
    public void theDurationDeadlineStillFiresWithNoInCallServiceBound() throws Exception {
        bindInCallService(null);
        trackGsmLeg(GSM_LEG);
        callManagerState(CallManager.CallState.BRIDGED);
        inject("callUpSinceWallMs",
                System.currentTimeMillis() - PjsipSipService.MAX_CALL_DURATION_MS - 1_000L);

        service.checkOrphanedCalls();

        assertEquals(1L, terminations());
        verify(audioBridge, times(1)).stopAudioStreams();
    }

    /** The clock is anchored by the first tick that sees a call, and released when it ends. */
    @Test
    public void theDurationClockStartsWithTheCallAndIsClearedWithIt() throws Exception {
        when(inCallService.hasLiveGsmCall()).thenReturn(true);

        service.checkOrphanedCalls();
        assertEquals("no call, no clock", 0L, getLong("callUpSinceWallMs"));

        trackGsmLeg(GSM_LEG);
        service.checkOrphanedCalls();
        long anchored = getLong("callUpSinceWallMs");
        assertTrue("the clock must be anchored", anchored > 0L);

        service.checkOrphanedCalls();
        assertEquals("and must not be re-anchored on every tick",
                anchored, getLong("callUpSinceWallMs"));

        trackGsmLeg(GatewayInCallService.NO_GSM_CALL);
        service.checkOrphanedCalls();
        assertEquals("the call is over", 0L, getLong("callUpSinceWallMs"));
    }

    // ========== Silent bridge: detection only ==========

    /**
     * GW-25 §2/§6. {@code noteBridgeFrames} is the pure half of the detector — the rest ends
     * in pjsua2 and cannot run on the JVM.
     *
     * <p>The two properties that matter: it does not fire before the dwell (a bridge that is
     * merely starting up is not a dead one), and once it has fired it does not fire again for
     * the same stall. {@code SipDiagnostics.dumpAndLog} emits ~20 logcat lines and creates ~8
     * owned pjsua2 objects; at a 3 s tick, one per tick would be ~1200 an hour.
     */
    @Test
    public void theSilentBridgeDumpIsLatchedToOncePerEpisode() {
        long t0 = 1_000_000L;

        assertFalse("first observation only arms the clock", service.noteBridgeFrames(0L, t0));
        assertFalse("still inside the dwell", service.noteBridgeFrames(
                0L, t0 + PjsipSipService.SILENT_BRIDGE_STALL_MS - 1));

        assertTrue("the stall has lasted long enough", service.noteBridgeFrames(
                0L, t0 + PjsipSipService.SILENT_BRIDGE_STALL_MS));

        assertFalse("but only once per episode", service.noteBridgeFrames(
                0L, t0 + PjsipSipService.SILENT_BRIDGE_STALL_MS * 10));
    }

    /** A counter that is moving is a bridge that is working, however slowly. */
    @Test
    public void aMovingFrameCounterNeverTripsTheSilentBridgeDetector() {
        long t = 1_000_000L;
        for (int frame = 0; frame < 20; frame++) {
            assertFalse(service.noteBridgeFrames(frame, t));
            t += PjsipSipService.SILENT_BRIDGE_STALL_MS * 2;
        }
    }

    /** A new stall after the counter moved again is a new episode, and reports again. */
    @Test
    public void theLatchIsReleasedWhenTheCounterMovesAgain() {
        long t0 = 1_000_000L;
        service.noteBridgeFrames(0L, t0);
        assertTrue(service.noteBridgeFrames(0L, t0 + PjsipSipService.SILENT_BRIDGE_STALL_MS));

        long t1 = t0 + PjsipSipService.SILENT_BRIDGE_STALL_MS * 2;
        assertFalse("the counter moved", service.noteBridgeFrames(1L, t1));
        assertTrue("and stalled again",
                service.noteBridgeFrames(1L, t1 + PjsipSipService.SILENT_BRIDGE_STALL_MS));
    }

    // ========== The reverse-orphan dwell, on its own ==========

    /**
     * The dwell has to outlast both mechanisms that are supposed to act before the watchdog:
     * the {@code 40 × 500 ms} SIP retry chain and the 30 s incoming timeout. These are the
     * numbers from {@code GatewayInCallService}, asserted here so that shortening either one
     * of them without revisiting this constant shows up as a failure.
     */
    @Test
    public void theReverseOrphanDwellOutlastsBothInboundTimeouts() {
        assertTrue("must outlast the SIP retry chain",
                PjsipSipService.GSM_WITHOUT_SIP_MAX_MS > SIP_RETRY_CHAIN_MS);
        assertTrue("must outlast the incoming timeout",
                PjsipSipService.GSM_WITHOUT_SIP_MAX_MS > INCOMING_TIMEOUT_MS);

        long t0 = 5_000_000L;
        assertFalse(service.noteGsmLegWithoutSip(GSM_LEG, t0));
        assertFalse("20 s in, the retry chain is still running",
                service.noteGsmLegWithoutSip(GSM_LEG, t0 + SIP_RETRY_CHAIN_MS));
        assertFalse("30 s in, the incoming timeout has only just had its chance",
                service.noteGsmLegWithoutSip(GSM_LEG, t0 + INCOMING_TIMEOUT_MS));
        assertTrue(service.noteGsmLegWithoutSip(
                GSM_LEG, t0 + PjsipSipService.GSM_WITHOUT_SIP_MAX_MS));
    }

    /** A new leg starts a new clock — the previous call's dwell must not carry over. */
    @Test
    public void anewGsmLegRestartsTheDwell() {
        long t0 = 5_000_000L;
        service.noteGsmLegWithoutSip(GSM_LEG, t0);
        assertFalse("a different leg, so the clock restarts", service.noteGsmLegWithoutSip(
                GSM_LEG + 1, t0 + PjsipSipService.GSM_WITHOUT_SIP_MAX_MS));
        assertTrue(service.noteGsmLegWithoutSip(
                GSM_LEG + 1, t0 + PjsipSipService.GSM_WITHOUT_SIP_MAX_MS * 2));
    }

    // ========== H9b ==========

    /**
     * AUDIT H9b lives in {@code GatewayInCallService}, not here, but it is the same failure
     * family: a GSM leg left up with nothing tracking it. Pinned as a reminder that the
     * watchdog is not the backstop for it — the watchdog's rules key off
     * {@code currentGsmCallId}, which only {@code STATE_ACTIVE} sets, and a leg whose
     * {@code answer()} threw never reaches ACTIVE.
     */
    @Test
    public void aLegThatNeverReachedActiveIsInvisibleToTheWatchdog() throws Exception {
        when(inCallService.hasLiveGsmCall()).thenReturn(true);  // RINGING counts as "live"

        service.checkOrphanedCalls();

        assertEquals("nothing tracked, so nothing to reap - H9b must fix it at the source",
                0L, terminations());
        verify(inCallService, never()).disconnectCall();
        verify(inCallService, never()).rejectCall();
    }
}
