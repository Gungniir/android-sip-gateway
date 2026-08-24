package org.onetwoone.gateway.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.lifecycle.Observer;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.onetwoone.gateway.PjsipSipService;
import org.onetwoone.gateway.config.GatewayConfig;
import org.onetwoone.gateway.core.GatewayStatus;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GW-14 - the UI refresh that replaced the {@code MainActivity} relaunch.
 *
 * <p>The reload used to end by restarting {@code MainActivity} with
 * {@code FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK}, so a config POST from the web
 * interface threw away whatever the person holding the phone was doing. Dropping that leaves a
 * real gap: {@code MainActivity}'s SIP and audio form fields come from
 * {@code MainViewModel.loadConfig()}, which reads {@code GatewayConfig} and is only called from
 * the constructor and after an in-app save - a web save writes SharedPreferences on a NanoHTTPD
 * worker and never touches this ViewModel. These tests are the evidence that the gap is closed
 * by the snapshot's config generation instead of by the restart.
 *
 * <p>The service is instantiated without {@code onCreate()} - it never gets a control thread,
 * an endpoint or an account here, and does not need one: the only thing the ViewModel reads
 * from it is the immutable snapshot, which is exactly the point of the snapshot.
 *
 * <h2>GW-45 - the status surface</h2>
 *
 * <p>The second half of this suite covers what Phase 4 plan §2 C1 found: the poll read the
 * immutable snapshot and then kept three fields of it, one of them a pre-formatted String, so
 * the call state, the duration, the call-object counters and the whole of
 * {@code WatchdogFindings} were unreachable from the UI. The two that carry the design are
 * {@link #theSnapshotIsPublishedVerbatim()} - it must be the <em>same object</em>, not a lossy
 * copy - and {@link #theCallDurationAdvancesBetweenTwoReadsOfTheSamePublishedSnapshot()},
 * which is the property that proves nothing clock-derived was cached on the way through.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class MainViewModelTest {

    private Application app;
    private MainViewModel viewModel;
    private PjsipSipService service;

    @Before
    public void setUp() throws Exception {
        app = RuntimeEnvironment.getApplication();

        Field instance = GatewayConfig.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
        GatewayConfig.init(app);
        GatewayConfig.getInstance().updateSipConfig(
                "old.example.org", 5060, "olduser", "oldpass", "*", false);

        viewModel = new MainViewModel(app);

        service = Robolectric.buildService(PjsipSipService.class).get();
        Field serviceField = MainViewModel.class.getDeclaredField("pjsipService");
        serviceField.setAccessible(true);
        serviceField.set(viewModel, service);
    }

    /** Publish a snapshot with the given reload counter, as {@code publishStatus()} would. */
    private GatewayStatus publishGeneration(long generation) throws Exception {
        // The call counters (GW-22) and the watchdog findings (GW-25) are irrelevant to the
        // reload tests; only the counter is.
        return publish(newSnapshot(generation, GatewayStatus.WatchdogFindings.NONE));
    }

    /**
     * Build a snapshot the way {@code GatewayStatus.capture()} would, without needing the three
     * live managers - the constructor is package-private and this test is not in that package.
     *
     * @param generation the reload counter (GW-14)
     * @param watchdog   the watchdog's findings (GW-25), which carry the call-up instant that
     *                   {@code getCallDurationMs()} derives from
     */
    private GatewayStatus newSnapshot(long generation, GatewayStatus.WatchdogFindings watchdog)
            throws Exception {
        // running, sipRegistered, sipStatus, callStatus, audioStatus, callState,
        // gsmCallPlacedAtWallMs, configGeneration, callsCreated, callsDeleted, watchdog,
        // capturedAt.
        Constructor<GatewayStatus> ctor = GatewayStatus.class.getDeclaredConstructor(
                boolean.class, boolean.class, String.class, String.class, String.class,
                String.class, long.class, long.class, long.class, long.class,
                GatewayStatus.WatchdogFindings.class, long.class);
        ctor.setAccessible(true);
        return ctor.newInstance(
                true, true, "Registered", "GSM<->SIP bridged", "Bridged", "BRIDGED",
                0L, generation, 41L, 40L, watchdog, System.currentTimeMillis());
    }

    /** Put a snapshot where {@code publishStatus()} would, and hand it back for identity. */
    private GatewayStatus publish(GatewayStatus snapshot) throws Exception {
        Field statusField = PjsipSipService.class.getDeclaredField("status");
        statusField.setAccessible(true);
        statusField.set(service, snapshot);
        return snapshot;
    }

    /** Drop the binding, the way {@code onServiceDisconnected} does. */
    private void loseTheService() throws Exception {
        Field serviceField = MainViewModel.class.getDeclaredField("pjsipService");
        serviceField.setAccessible(true);
        serviceField.set(viewModel, null);
    }

    private void poll() throws Exception {
        Method update = MainViewModel.class.getDeclaredMethod("updateServiceState");
        update.setAccessible(true);
        update.invoke(viewModel);
    }

    private String observedSipServer() {
        MainViewModel.SipConfig sip = viewModel.getSipConfig().getValue();
        assertNotNull("the ViewModel always has a config to show", sip);
        return sip.server;
    }

    // ========== GW-14: the config-generation poll ==========

    /**
     * A config POST from the web interface: preferences change under the ViewModel, the reload
     * bumps the generation, and the next 1 Hz poll must repopulate the form. Without the
     * generation check this is the case that used to need {@code CLEAR_TASK}.
     */
    @Test
    public void anExternalConfigReloadRefreshesTheFormInPlace() throws Exception {
        publishGeneration(0L);
        poll();
        assertEquals("nothing has been reloaded yet", "old.example.org", observedSipServer());

        // What WebConfigServer does: write the preferences, then ask for a reload.
        GatewayConfig.getInstance().updateSipConfig(
                "new.example.org", 5061, "newuser", "newpass", "*", false);
        publishGeneration(1L);

        poll();

        assertEquals("the UI must pick the new config up from the snapshot's generation",
                "new.example.org", observedSipServer());
        assertEquals(5061, viewModel.getSipConfig().getValue().port);
        assertEquals("newuser", viewModel.getSipConfig().getValue().user);
    }

    /**
     * The counterweight: the poll runs once a second, and re-reading config on every tick
     * would fight the operator typing into the very fields it rewrites. It must only fire when
     * the generation actually moves.
     */
    @Test
    public void pollingWithNoReloadLeavesTheFormAlone() throws Exception {
        publishGeneration(4L);
        poll();

        // Someone is editing the form; the ViewModel's LiveData holds their in-progress value.
        MainViewModel.SipConfig edited = viewModel.getSipConfig().getValue();
        assertNotNull(edited);
        edited.server = "being.typed.in";

        poll();
        poll();
        poll();

        assertEquals("no reload happened, so nothing may be overwritten",
                "being.typed.in", observedSipServer());
    }

    /**
     * The first snapshot after binding is not a change - {@code loadConfig()} has just run in
     * the constructor, and treating the initial generation as "it moved" would repopulate the
     * form every time the activity re-binds.
     */
    @Test
    public void theFirstSnapshotAfterBindingIsNotTreatedAsAReload() throws Exception {
        MainViewModel.SipConfig edited = viewModel.getSipConfig().getValue();
        assertNotNull(edited);
        edited.server = "being.typed.in";

        publishGeneration(9L);
        poll();

        assertEquals("binding to a service that has already reloaded is not itself a reload",
                "being.typed.in", observedSipServer());
    }

    /**
     * A process restart (the TLS path) starts the counter over at zero. Losing the service must
     * reset what the ViewModel has seen, or the next generation 1 would look like no change.
     */
    @Test
    public void losingTheServiceResetsTheSeenGeneration() throws Exception {
        publishGeneration(3L);
        poll();

        loseTheService();
        poll();

        Field seen = MainViewModel.class.getDeclaredField("seenConfigGeneration");
        seen.setAccessible(true);
        assertEquals(-1L, seen.getLong(viewModel));
    }

    // ========== GW-45: the status surface ==========

    /**
     * Phase 4 plan §2 C1, stated as a test. The poll must hand the UI the object the control
     * thread published - the same instance, not a copy and not a re-wrap - because everything
     * the old three-field {@code ServiceState} dropped is reachable only through it.
     */
    @Test
    public void theSnapshotIsPublishedVerbatim() throws Exception {
        GatewayStatus published = publishGeneration(2L);

        poll();

        GatewayStatus observed = viewModel.getGatewayStatus().getValue();
        assertSame("the UI must get the published object itself, not a lossy copy",
                published, observed);
        assertTrue(viewModel.getServiceConnected().getValue());

        // The fields C1 lists as unreachable before GW-45, reached.
        assertEquals("Registered", observed.getSipStatus());
        assertEquals("GSM<->SIP bridged", observed.getCallStatus());
        assertEquals("Bridged", observed.getAudioStatus());
        assertEquals("BRIDGED", observed.getCallState());
        assertEquals(41L, observed.getCallsCreated());
        assertEquals(40L, observed.getCallsDeleted());
        assertEquals("one pjsua2 Call still alive", 1L, observed.getCallsAlive());
        assertEquals(2L, observed.getConfigGeneration());
        assertNotNull(observed.getWatchdog());
    }

    /**
     * The watchdog block is the single largest thing the old surface dropped, and it is what a
     * status screen would show under "has this gateway been misbehaving". It travels on the
     * same object, so nothing extra is needed to reach it - which is the point.
     */
    @Test
    public void theWatchdogFindingsRideAlongWithTheSnapshot() throws Exception {
        publish(newSnapshot(0L, new GatewayStatus.WatchdogFindings(
                0L, 3L, 5L, "CallManager is IDLE but still holds a live SIP call", 1234L)));

        poll();

        GatewayStatus.WatchdogFindings findings =
                viewModel.getGatewayStatus().getValue().getWatchdog();
        assertEquals(3L, findings.getTerminations());
        assertEquals(5L, findings.getSilentBridgeEpisodes());
        assertEquals("CallManager is IDLE but still holds a live SIP call",
                findings.getLastFinding());
        assertEquals(1234L, findings.getLastFindingAtWallMs());
    }

    /**
     * Plan §4 GW-45 constraint 4. With no binding there is nothing to read, and the value is
     * {@code GatewayStatus.UNAVAILABLE} - not null, and not a sentinel this ViewModel invented.
     * What that should <em>say</em> on screen is presentation, which is why the fact travels as
     * {@code getServiceConnected()} rather than as a string chosen here.
     */
    @Test
    public void unavailableIsPublishedWhenTheServiceIsUnbound() throws Exception {
        publishGeneration(1L);
        poll();
        assertTrue(viewModel.getServiceConnected().getValue());

        loseTheService();
        poll();

        assertSame("the documented not-connected value, not a null-state",
                GatewayStatus.UNAVAILABLE, viewModel.getGatewayStatus().getValue());
        assertFalse(viewModel.getServiceConnected().getValue());
    }

    /** Before the first poll there is still something to render. */
    @Test
    public void theSurfaceIsUsableBeforeTheFirstPoll() {
        assertSame(GatewayStatus.UNAVAILABLE, viewModel.getGatewayStatus().getValue());
        assertFalse(viewModel.getServiceConnected().getValue());
    }

    /**
     * <b>The property that proves nothing was cached.</b> Plan §4 GW-45 constraint 3:
     * {@code getCallDurationMs()} re-reads the clock on every call, and a ViewModel that
     * snapshotted it into a field - or into a formatted String - would give the screen a
     * stopwatch that never advances.
     *
     * <p>Read twice from the <em>same</em> published object, with no second poll in between:
     * the service's {@code publishStatus()} is event-driven, so a call generating no events
     * leaves this exact object in place for many ticks, and it still has to age.
     *
     * <p>Real time, not Robolectric's, for the reason {@code GatewayStatusTest} gives: the
     * duration is measured with {@code System.currentTimeMillis()}, which
     * {@code ShadowSystemClock.advanceBy} does not move.
     */
    @Test
    public void theCallDurationAdvancesBetweenTwoReadsOfTheSamePublishedSnapshot()
            throws Exception {
        publish(newSnapshot(0L, new GatewayStatus.WatchdogFindings(
                System.currentTimeMillis() - 1_000L, 0L, 0L, "", 0L)));

        poll();

        GatewayStatus observed = viewModel.getGatewayStatus().getValue();
        long first = observed.getCallDurationMs();
        assertTrue("about a second of call so far", first >= 1_000L);

        Thread.sleep(250L);

        assertTrue("the SAME published snapshot must have aged with the clock",
                observed.getCallDurationMs() >= first + 200L);
        assertSame("and no republish was needed for it to",
                observed, viewModel.getGatewayStatus().getValue());
    }

    /**
     * The other half of that: the poll must repost every tick even when the service has
     * published nothing new, or an observer rendering the duration would never be asked to
     * redraw it. {@code MutableLiveData.setValue} dispatches unconditionally, and this pins
     * that the poll leans on it rather than short-circuiting on an unchanged instance.
     */
    @Test
    public void everyTickRepublishesEvenWhenTheSnapshotHasNotChanged() throws Exception {
        publishGeneration(0L);
        poll();

        final AtomicInteger deliveries = new AtomicInteger();
        Observer<GatewayStatus> observer = status -> deliveries.incrementAndGet();
        viewModel.getGatewayStatus().observeForever(observer);
        // observeForever delivers the value already held, which is not a tick.
        deliveries.set(0);

        try {
            poll();
            poll();
            poll();

            assertEquals("one delivery per tick, unchanged snapshot or not",
                    3, deliveries.get());
        } finally {
            viewModel.getGatewayStatus().removeObserver(observer);
        }
    }

    // ========== GW-45: the deprecated surface stays working ==========

    /**
     * Plan §4 GW-45 constraint 2. {@code MainActivity} still observes {@code getServiceState()}
     * and wave 1 must leave a working app; GW-41 deletes it with the screen that reads it.
     */
    @Test
    @SuppressWarnings("deprecation")
    public void theDeprecatedSurfaceStillCarriesTheSameStatus() throws Exception {
        publishGeneration(0L);

        poll();

        MainViewModel.ServiceState state = viewModel.getServiceState().getValue();
        assertNotNull(state);
        assertTrue(state.isRunning);
        assertTrue(state.isRegistered);
        assertEquals("SIP: Registered\nCall: GSM<->SIP bridged\nAudio: Bridged",
                state.statusMessage);
        assertEquals(state.statusMessage, viewModel.getStatusText().getValue());
        assertTrue(viewModel.getIsRegistered().getValue());
    }

    /**
     * The unbound case is the one that could have regressed silently: the old code wrote its
     * own "Service not connected" line, and {@code UNAVAILABLE.getStatusText()} is a different
     * string ("SIP: Not configured..."). The deprecated surface has to keep saying what it
     * said.
     */
    @Test
    @SuppressWarnings("deprecation")
    public void theDeprecatedSurfaceKeepsItsOwnDisconnectedLine() throws Exception {
        publishGeneration(0L);
        poll();

        loseTheService();
        poll();

        MainViewModel.ServiceState state = viewModel.getServiceState().getValue();
        assertNotNull(state);
        assertFalse(state.isRunning);
        assertFalse(state.isRegistered);
        assertEquals(MainViewModel.DISCONNECTED_STATUS_TEXT, state.statusMessage);
        assertEquals("Service not connected", viewModel.getStatusText().getValue());
        assertFalse(viewModel.getIsRegistered().getValue());
    }
}
