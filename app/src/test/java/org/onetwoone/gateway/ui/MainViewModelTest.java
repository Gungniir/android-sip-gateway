package org.onetwoone.gateway.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import java.util.HashSet;
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

    // ========== GW-41: what replaced the deprecated surface ==========

    /**
     * The GW-45 deprecation is complete.
     *
     * <p>Wave 1 kept {@code getServiceState()}, {@code getStatusText()},
     * {@code getIsRegistered()}, the {@code ServiceState} POJO and
     * {@code publishLegacyServiceState()} alive so that {@code MainActivity} still worked
     * while it was rewritten. GW-41 rewrote it and removed them - which was the plan, and is
     * the kind of thing that quietly does not happen. Reflection rather than a compile error
     * because a deleted method cannot be named in source.
     */
    @Test
    public void thePreGw45StatusSurfaceIsGone() {
        for (String gone : new String[]{"getServiceState", "getStatusText", "getIsRegistered",
                "publishLegacyServiceState"}) {
            for (Method method : MainViewModel.class.getDeclaredMethods()) {
                assertFalse("MainViewModel." + gone + " should have been deleted by GW-41",
                        method.getName().equals(gone));
            }
        }
        for (Class<?> nested : MainViewModel.class.getDeclaredClasses()) {
            assertFalse("MainViewModel.ServiceState should have been deleted by GW-41",
                    "ServiceState".equals(nested.getSimpleName()));
        }
        for (Field field : MainViewModel.class.getDeclaredFields()) {
            assertFalse("DISCONNECTED_STATUS_TEXT should have been deleted by GW-41",
                    "DISCONNECTED_STATUS_TEXT".equals(field.getName()));
        }
    }

    /**
     * Plan §4 hazard H-c. A toast is an event, not a state: the value a {@code LiveData}
     * replays to a new observer - which is what a configuration change produces - must not
     * fire a second time.
     */
    @Test
    public void aToastIsDeliveredOnceEvenIfTheLiveDataReplaysIt() {
        viewModel.stopService();

        Event<String> event = viewModel.getToastMessage().getValue();
        assertNotNull("stopService posts a message", event);
        assertNotNull("the first observer gets it", event.getContentIfNotHandled());
        assertNull("a replay after a configuration change must be a no-op",
                event.getContentIfNotHandled());
        assertTrue(event.isHandled());
    }

    /**
     * Plan §4 hazard H-d, the half that lives here: the four audio selections
     * {@code MainActivity} used to mirror are ViewModel state now, seeded from config.
     *
     * <p>Idempotence is the load-bearing property. {@code Spinner.setSelection()} delivers
     * {@code onItemSelected} asynchronously, so a repaint arrives at the listener looking
     * exactly like a choice; the only defence that works is a setter for which the two are
     * indistinguishable <em>and harmless</em>.
     */
    @Test
    public void theAudioSelectionsAreSeededFromConfigAndSettingThemAgainIsANoOp() {
        GatewayConfig.getInstance().updateAudioConfig(1, 5, 2, "MultiMedia3");
        GatewayConfig.getInstance().setAudioProfile("mediatek");

        MainViewModel fresh = new MainViewModel(app);

        assertEquals(Integer.valueOf(1), fresh.getSelectedCard().getValue());
        assertEquals(Integer.valueOf(5), fresh.getSelectedCaptureDevice().getValue());
        assertEquals(Integer.valueOf(2), fresh.getSelectedPlaybackDevice().getValue());
        assertEquals("MultiMedia3", fresh.getSelectedMixerRoute().getValue());
        assertEquals("mediatek", fresh.getSelectedAudioProfile().getValue());

        final AtomicInteger deliveries = new AtomicInteger();
        Observer<Integer> observer = card -> deliveries.incrementAndGet();
        fresh.getSelectedCard().observeForever(observer);
        deliveries.set(0);
        try {
            fresh.setSelectedCard(1);
            fresh.setSelectedCard(1);
            assertEquals("re-selecting the same card must not republish", 0, deliveries.get());

            fresh.setSelectedCard(2);
            assertEquals(1, deliveries.get());
        } finally {
            fresh.getSelectedCard().removeObserver(observer);
        }
    }

    /**
     * GW-41 step 4d, and PHASE-4-PLAN §C8: {@code audio_profile} was settable only from the
     * web page. It has to survive a save from the phone, which means the audio save path has
     * to carry it - it was not one of the values the old signature took.
     */
    @Test
    public void savingAudioPersistsTheSocProfile() {
        viewModel.setSelectedAudioProfile("qualcomm");
        viewModel.setSelectedCard(1);
        viewModel.setSelectedCaptureDevice(7);
        viewModel.setSelectedPlaybackDevice(3);
        viewModel.setSelectedMixerRoute("MultiMedia2");

        viewModel.saveAudioConfig(-6.0f, 1.5f, new HashSet<>(), "DEC1 Volume");

        GatewayConfig config = GatewayConfig.getInstance();
        assertEquals("qualcomm", config.getAudioProfile());
        assertEquals(1, config.getAudioCard());
        assertEquals(7, config.getCaptureDevice());
        assertEquals(3, config.getPlaybackDevice());
        assertEquals("MultiMedia2", config.getMultimediaRoute());
        assertEquals(-6.0f, config.getTxGain(), 0.001f);
        assertEquals(1.5f, config.getRxGain(), 0.001f);
        assertEquals("DEC1 Volume", config.getManualMuteControls());
    }

    /**
     * The incoming call mode has no Save button - the radio group writes straight through.
     * What GW-41 added is that the published {@code SipConfig} moves with it, so the value the
     * screen was last told is the value that is persisted rather than one reload behind.
     */
    @Test
    public void settingTheIncomingCallModeUpdatesBothConfigAndThePublishedForm() {
        viewModel.setIncomingCallMode(1);

        assertEquals(1, GatewayConfig.getInstance().getIncomingCallMode());
        assertEquals(1, viewModel.getSipConfig().getValue().incomingCallMode);
    }

    /**
     * Section expansion is view state, and it lives here so that recreating the activity -
     * which a night-mode switch does - does not close what the operator opened.
     */
    @Test
    public void sectionExpansionIsViewModelStateWithSipOpenFirst() {
        assertTrue("commissioning is the job you cannot do anywhere else",
                viewModel.getSectionExpanded(MainViewModel.Section.SIP).getValue());
        for (MainViewModel.Section section : MainViewModel.Section.values()) {
            if (section != MainViewModel.Section.SIP) {
                assertFalse(section + " should start collapsed",
                        viewModel.getSectionExpanded(section).getValue());
            }
        }

        viewModel.toggleSection(MainViewModel.Section.AUDIO);
        assertTrue(viewModel.getSectionExpanded(MainViewModel.Section.AUDIO).getValue());
        viewModel.toggleSection(MainViewModel.Section.AUDIO);
        assertFalse(viewModel.getSectionExpanded(MainViewModel.Section.AUDIO).getValue());
    }

    /**
     * The diagnostics settings used to be read straight out of {@code GatewayConfig} by the
     * activity, once, at {@code onCreate}. Publishing them is what puts them on the same
     * config-generation reload path as everything else.
     */
    @Test
    public void theDiagnosticsSettingsArePublished() {
        GatewayConfig.getInstance().setTestDestination("*97");
        GatewayConfig.getInstance().setTestMode("bridge");
        GatewayConfig.getInstance().setVerboseSipLog(true);
        GatewayConfig.getInstance().setDtmfRelayEnabled(true);

        MainViewModel fresh = new MainViewModel(app);

        MainViewModel.DiagnosticsConfig diagnostics = fresh.getDiagnosticsConfig().getValue();
        assertNotNull(diagnostics);
        assertEquals("*97", diagnostics.testDestination);
        assertEquals("bridge", diagnostics.testMode);
        assertTrue(diagnostics.verboseSipLog);
        assertTrue(diagnostics.dtmfRelay);
    }
}
