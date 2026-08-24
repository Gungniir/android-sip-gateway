package org.onetwoone.gateway.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.os.Bundle;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.onetwoone.gateway.audio.AudioBridgeManager;
import org.onetwoone.gateway.call.CallManager;
import org.onetwoone.gateway.config.GatewayConfig;
import org.onetwoone.gateway.sip.SipAccountManager;
import org.onetwoone.gateway.sip.SipEndpointManager;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

/**
 * GW-10 — the {@link GatewayStatus} snapshot the UI reads instead of the live managers.
 *
 * <p>The test that matters is {@link #gracePeriodIsDerivedFromTheClockNotFrozen()}: plan
 * §2.7 trap 1. A snapshot that froze {@code isInGracePeriod()} as a boolean would keep
 * telling the watchdog "still dialling" for as long as that snapshot lived, and the orphaned
 * call it is supposed to catch would stay invisible.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class GatewayStatusTest {

    /** How much grace period is left when the derived-accessor test starts waiting. */
    private static final long GRACE_REMAINING_MS = 400L;

    private Application app;
    private CallManager callManager;
    private SipAccountManager accountManager;
    private AudioBridgeManager audioBridge;

    @Before
    public void setUp() throws Exception {
        app = RuntimeEnvironment.getApplication();

        Field instance = GatewayConfig.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
        GatewayConfig.init(app);

        GatewayConfig config = GatewayConfig.getInstance();
        // capture() is control-thread code and CallManager now asserts it, so run the control
        // thread on Robolectric's main looper - the established pattern in this suite.
        callManager = new CallManager(app, config,
                new GatewayControlThread(Looper.getMainLooper(), null));
        accountManager = new SipAccountManager(config, new SipEndpointManager(config));
        audioBridge = new AudioBridgeManager(app, config,
                new GatewayControlThread(Looper.getMainLooper(), null));
    }

    @Test
    public void captureReadsTheLiveManagersOnce() {
        GatewayStatus status = GatewayStatus.capture(
                true, accountManager, callManager, audioBridge, 0L, 0L, 0L, null);

        assertTrue(status.isRunning());
        assertFalse("no account has been created", status.isSipRegistered());
        assertEquals("Not configured", status.getSipStatus());
        assertEquals("Idle", status.getCallStatus());
        assertEquals("Not initialized", status.getAudioStatus());
        assertEquals("IDLE", status.getCallState());
    }

    @Test
    @SuppressWarnings("deprecation") // GW-45 deprecated the composite; this pins what it says
    public void statusTextIsTheThreeLineCompositeTheUiAlwaysShowed() {
        GatewayStatus status = GatewayStatus.capture(
                false, accountManager, callManager, audioBridge, 0L, 0L, 0L, null);

        assertEquals("SIP: Not configured\nCall: Idle\nAudio: Not initialized",
                status.getStatusText());
    }

    /**
     * Put the manager where a GSM dial would, without going near Telecom - which under
     * Robolectric would throw and take {@code terminateAllCalls()} straight back to IDLE.
     * Same reflection trick {@code CallManagerTest} uses for {@code state}.
     *
     * @param msLeftInGracePeriod how much of the grace period should still be ahead
     */
    private void pretendGsmCallPlaced(long msLeftInGracePeriod) throws Exception {
        Field state = CallManager.class.getDeclaredField("state");
        state.setAccessible(true);
        state.set(callManager, CallManager.CallState.GSM_DIALING);

        Field placedAt = CallManager.class.getDeclaredField("gsmCallPlacedTime");
        placedAt.setAccessible(true);
        placedAt.set(callManager, System.currentTimeMillis()
                - (CallManager.GSM_CALL_GRACE_PERIOD_MS - msLeftInGracePeriod));
    }

    /** A snapshot is a value: later manager changes must not leak into one already taken. */
    @Test
    public void aTakenSnapshotDoesNotTrackLaterManagerChanges() throws Exception {
        GatewayStatus before = GatewayStatus.capture(
                true, accountManager, callManager, audioBridge, 0L, 0L, 0L, null);

        pretendGsmCallPlaced(CallManager.GSM_CALL_GRACE_PERIOD_MS);

        assertEquals("the old snapshot must not change", "Idle", before.getCallStatus());
        assertEquals("IDLE", before.getCallState());
        assertEquals("GSM connecting...",
                GatewayStatus.capture(
                        true, accountManager, callManager, audioBridge, 0L, 0L, 0L, null)
                        .getCallStatus());
    }

    /**
     * Plan §2.7 trap 1. Freeze this as a boolean and this test fails: the <em>same</em>
     * snapshot object has to answer "yes" now and "no" once the deadline has passed.
     *
     * <p>Real time, not Robolectric's: the grace period is measured with
     * {@code System.currentTimeMillis()}, which {@code ShadowSystemClock.advanceBy} does not
     * move (it drives {@code uptimeMillis}/{@code elapsedRealtime} only). So the window is
     * shrunk to {@value #GRACE_REMAINING_MS} ms and the test waits it out - cheap, and it
     * exercises the production expression rather than a substitute for it.
     */
    @Test
    public void gracePeriodIsDerivedFromTheClockNotFrozen() throws Exception {
        pretendGsmCallPlaced(GRACE_REMAINING_MS);

        GatewayStatus status = GatewayStatus.capture(
                true, accountManager, callManager, audioBridge, 0L, 0L, 0L, null);
        assertTrue("the grace period has not run out yet", status.isInGracePeriod());

        Thread.sleep(GRACE_REMAINING_MS + 100);

        assertFalse("the SAME snapshot must now say the grace period is over",
                status.isInGracePeriod());
        assertFalse("and so must the manager it came from", callManager.isInGracePeriod());
    }

    @Test
    public void noGsmDialMeansNoGracePeriod() {
        GatewayStatus status = GatewayStatus.capture(
                true, accountManager, callManager, audioBridge, 0L, 0L, 0L, null);
        assertFalse(status.isInGracePeriod());
    }

    /** The snapshot's second consumer is GET_STATUS, so it has to flatten. */
    @Test
    public void flattensIntoABundle() {
        GatewayStatus status = GatewayStatus.capture(
                true, accountManager, callManager, audioBridge, 0L, 0L, 0L, null);
        Bundle bundle = status.toBundle();

        assertTrue(bundle.getBoolean("running"));
        assertFalse(bundle.getBoolean("sip_registered"));
        assertEquals("Not configured", bundle.getString("sip_status"));
        assertEquals("Idle", bundle.getString("call_status"));
        assertEquals("Not initialized", bundle.getString("audio_status"));
        assertEquals("IDLE", bundle.getString("call_state"));
        assertFalse(bundle.getBoolean("in_grace_period"));
        assertEquals(0L, bundle.getLong("config_generation"));
        assertEquals(status.getCapturedAtWallMs(), bundle.getLong("captured_at_wall_ms"));
    }

    /**
     * GW-14. The reload counter is what replaced the {@code MainActivity} relaunch, so it has
     * to survive capture and flatten with everything else - {@code GET_STATUS} is the other
     * consumer, and "has the config changed since I last looked" is exactly the question a
     * broadcast caller polling for status wants answered.
     */
    @Test
    public void configGenerationIsCarriedThroughCaptureAndTheBundle() {
        GatewayStatus status =
                GatewayStatus.capture(
                        true, accountManager, callManager, audioBridge, 7L, 0L, 0L, null);

        assertEquals(7L, status.getConfigGeneration());
        assertEquals(7L, status.toBundle().getLong("config_generation"));
    }

    /** Nothing has been reloaded before the service has published anything. */
    @Test
    public void unavailableReportsNoConfigGeneration() {
        assertEquals(0L, GatewayStatus.UNAVAILABLE.getConfigGeneration());
    }

    /**
     * GW-22 / AUDIT H7. {@code callsCreated - callsDeleted} is the acceptance number for the
     * soak - it must equal the number of live calls - so both halves have to survive capture
     * and reach {@code GET_STATUS}'s bundle, and the difference has to be derived rather than
     * snapshotted separately.
     */
    @Test
    public void callCountersAreCarriedThroughCaptureAndTheBundle() {
        GatewayStatus status = GatewayStatus.capture(
                true, accountManager, callManager, audioBridge, 0L, 500L, 499L, null);

        assertEquals(500L, status.getCallsCreated());
        assertEquals(499L, status.getCallsDeleted());
        assertEquals("one Call still alive", 1L, status.getCallsAlive());

        Bundle bundle = status.toBundle();
        assertEquals(500L, bundle.getLong("calls_created"));
        assertEquals(499L, bundle.getLong("calls_deleted"));
        assertEquals(1L, bundle.getLong("calls_alive"));
    }

    /**
     * The healthy steady state: everything created has been deleted, so nothing is alive. A
     * soak that ends here with zero tombstones is what closes H7.
     */
    @Test
    public void callCountersReportNothingAliveWhenTheyMatch() {
        GatewayStatus status = GatewayStatus.capture(
                true, accountManager, callManager, audioBridge, 0L, 500L, 500L, null);

        assertEquals(0L, status.getCallsAlive());
    }

    /** No call has been made before the service has published anything. */
    @Test
    public void unavailableReportsNoCalls() {
        assertEquals(0L, GatewayStatus.UNAVAILABLE.getCallsCreated());
        assertEquals(0L, GatewayStatus.UNAVAILABLE.getCallsDeleted());
        assertEquals(0L, GatewayStatus.UNAVAILABLE.getCallsAlive());
    }

    // ========== GW-25: the watchdog's findings ==========

    /**
     * GW-25 §8. The findings are what turns "the watchdog logged something at 3am" into
     * something {@code GET_STATUS} can answer, so both counters and the last finding have to
     * survive capture and reach the bundle.
     */
    @Test
    public void watchdogFindingsAreCarriedThroughCaptureAndTheBundle() {
        GatewayStatus.WatchdogFindings findings = new GatewayStatus.WatchdogFindings(
                0L, 3L, 5L, "GSM leg 7 has been active with no SIP leg for 45 s", 1234L);

        GatewayStatus status = GatewayStatus.capture(
                true, accountManager, callManager, audioBridge, 0L, 0L, 0L, findings);

        assertEquals(3L, status.getWatchdog().getTerminations());
        assertEquals(5L, status.getWatchdog().getSilentBridgeEpisodes());
        assertEquals("GSM leg 7 has been active with no SIP leg for 45 s",
                status.getWatchdog().getLastFinding());
        assertEquals(1234L, status.getWatchdog().getLastFindingAtWallMs());

        Bundle bundle = status.toBundle();
        assertEquals(3L, bundle.getLong("watchdog_terminations"));
        assertEquals(5L, bundle.getLong("silent_bridge_episodes"));
        assertEquals("GSM leg 7 has been active with no SIP leg for 45 s",
                bundle.getString("last_watchdog_finding"));
        assertEquals(1234L, bundle.getLong("last_watchdog_finding_at_wall_ms"));
    }

    /**
     * Plan §2.7 trap 1 again, for the value GW-25 added. The max-call-duration fail-safe is a
     * deadline on this number, so freezing the <em>duration</em> instead of the raw instant
     * would give the UI a stopwatch that never moves and the watchdog a call that never ages.
     * The same snapshot has to report a bigger number a moment later.
     */
    @Test
    public void callDurationIsDerivedFromTheClockNotFrozen() throws Exception {
        GatewayStatus.WatchdogFindings findings = new GatewayStatus.WatchdogFindings(
                System.currentTimeMillis() - 1_000L, 0L, 0L, "", 0L);

        GatewayStatus status = GatewayStatus.capture(
                true, accountManager, callManager, audioBridge, 0L, 0L, 0L, findings);

        long first = status.getCallDurationMs();
        assertTrue("about a second of call so far", first >= 1_000L);

        Thread.sleep(250L);

        assertTrue("the SAME snapshot must have aged with the clock",
                status.getCallDurationMs() >= first + 200L);
    }

    /** No call means no duration, not a duration measured from the epoch. */
    @Test
    public void noCallMeansNoDuration() {
        GatewayStatus status = GatewayStatus.capture(
                true, accountManager, callManager, audioBridge, 0L, 0L, 0L, null);

        assertEquals(0L, status.getWatchdog().getCallUpSinceWallMs());
        assertEquals(0L, status.getCallDurationMs());
        assertEquals(0L, status.toBundle().getLong("call_duration_ms"));
    }

    /**
     * {@code capture} tolerates a null findings object for the same reason it tolerates null
     * managers: it runs during teardown too, and a snapshot is more useful than an NPE on the
     * control thread.
     */
    @Test
    public void missingFindingsReadAsNone() {
        GatewayStatus status = GatewayStatus.capture(
                true, accountManager, callManager, audioBridge, 0L, 0L, 0L, null);

        assertEquals(0L, status.getWatchdog().getTerminations());
        assertEquals("", status.getWatchdog().getLastFinding());
    }

    /** Before the service has published anything the UI still needs something to read. */
    @Test
    public void unavailableIsUsableBeforeAnythingIsPublished() {
        assertFalse(GatewayStatus.UNAVAILABLE.isRunning());
        assertFalse(GatewayStatus.UNAVAILABLE.isSipRegistered());
        assertFalse(GatewayStatus.UNAVAILABLE.isInGracePeriod());
        assertEquals("IDLE", GatewayStatus.UNAVAILABLE.getCallState());
        assertEquals(0L, GatewayStatus.UNAVAILABLE.getWatchdog().getTerminations());
        assertEquals(0L, GatewayStatus.UNAVAILABLE.getCallDurationMs());
    }

    /**
     * {@code capture} runs during service teardown too, when managers may already be gone.
     * It must produce a snapshot rather than an NPE on the control thread.
     */
    @Test
    public void captureToleratesMissingManagers() {
        GatewayStatus status = GatewayStatus.capture(false, null, null, null, 0L, 0L, 0L, null);

        assertFalse(status.isRunning());
        assertFalse(status.isSipRegistered());
        assertFalse(status.isInGracePeriod());
        assertEquals("IDLE", status.getCallState());
    }
}
