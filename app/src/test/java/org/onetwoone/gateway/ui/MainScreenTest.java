package org.onetwoone.gateway.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.lifecycle.ViewModelProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.onetwoone.gateway.GatewayInCallService;
import org.onetwoone.gateway.MainActivity;
import org.onetwoone.gateway.PjsipSipService;
import org.onetwoone.gateway.R;
import org.onetwoone.gateway.config.GatewayConfig;
import org.onetwoone.gateway.core.GatewayStatus;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * GW-41 - the main screen, inflated and wired, against the real resource table.
 *
 * <h2>Why this exists</h2>
 *
 * <p>PHASE-4-VALIDATION §0 names the failure class this wave is actually dangerous for: an
 * <b>unwired control</b>. You change a setting, press Save, and nothing happens - or it looks
 * like it worked and is gone after a restart. It is invisible until the gateway misbehaves
 * days later, and a 565-line layout rewritten against 40 {@code findViewById} calls is exactly
 * how one goes missing.
 *
 * <p>There are no instrumented tests in this project and no phone attached to this worktree,
 * so this is as close to that check as a JVM can get: build the real activity under the real
 * theme, drive the real widgets, and read the values back out of {@code GatewayConfig} -
 * which is {@code SharedPreferences}, the thing that has to survive the restart.
 *
 * <p><b>What it does not prove.</b> Nothing about appearance. Contrast, legibility, whether a
 * spinner popup is white-on-white, whether the header fits on a 5-inch screen at a large font
 * scale - none of that is reachable from here, and PHASE-4-VALIDATION is where it belongs.
 * These tests use the merged resources ({@code includeAndroidResources}, added by GW-40) so
 * that a dangling {@code @string} or {@code @style} fails loudly, and that is the limit.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class MainScreenTest {

    /**
     * Every control PHASE-4-VALIDATION §0 says must survive wave 2, by id.
     *
     * <p>25 in the document, plus {@code socProfileSpinner} - the SoC profile, which this
     * wave adds because it was settable only from the web page (plan §C8). The custom mute
     * checkboxes are generated at runtime from a root mixer scan and so are represented here
     * by their container.
     */
    private static final int[] PERSISTED_CONTROLS = {
            R.id.sipServer,
            R.id.sipPort,
            R.id.sipUser,
            R.id.sipPassword,
            R.id.sipRealm,
            R.id.useTls,
            R.id.sim1Destination,
            R.id.sim2Destination,
            R.id.modeSipFirst,
            R.id.modeAnswerFirst,
            R.id.limit60,
            R.id.limit100,
            R.id.cardSpinner,
            R.id.captureSpinner,
            R.id.playbackSpinner,
            R.id.mixerRouteSpinner,
            R.id.txGainEdit,
            R.id.rxGainEdit,
            R.id.devicePresetSpinner,
            R.id.micMuteCheckboxContainer,
            R.id.manualMuteControls,
            R.id.webInterfaceSwitch,
            R.id.testDestination,
            R.id.testModeSpinner,
            R.id.verboseSipLogCheckbox,
            R.id.dtmfRelayCheckbox,
            R.id.socProfileSpinner,
    };

    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private MainViewModel viewModel;

    @Before
    public void setUp() throws Exception {
        Field instance = GatewayConfig.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
        GatewayConfig.init(RuntimeEnvironment.getApplication());
    }

    @After
    public void tearDown() {
        if (controller != null) {
            controller.pause().stop().destroy();
        }
    }

    /** Build the screen the way the launcher does: created, started, resumed. */
    private void launch() {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        viewModel = new ViewModelProvider(activity).get(MainViewModel.class);
    }

    private <T extends View> T view(int id) {
        T found = activity.findViewById(id);
        assertNotNull("view " + activity.getResources().getResourceEntryName(id)
                + " is missing from the layout", found);
        return found;
    }

    // ========== Inflation ==========

    /**
     * The whole screen inflates under the real theme. This is what catches a dangling
     * {@code @string}, {@code @style}, {@code @dimen} or {@code @color} in any of the six
     * layout files the rewrite split it into.
     */
    @Test
    public void theScreenInflates() {
        launch();
        assertNotNull(activity.findViewById(R.id.mainRoot));
        assertNotNull(activity.findViewById(R.id.statusHeader));
    }

    /**
     * The status header scrolls with the sections rather than being pinned above them.
     *
     * <p>GW-41 shipped it pinned outside the {@code ScrollView}, on the argument that health
     * should never be something you scroll back to. On a real handset it cost too much of a
     * short screen and the owner had it scroll instead. That is a layout decision with no
     * runtime symptom - {@code StatusHeaderBinder} finds the header by id either way, so
     * moving it back would break nothing and no other test would notice. Hence this one.
     */
    @Test
    public void theStatusHeaderScrollsWithTheSections() {
        launch();
        View scroll = activity.findViewById(R.id.sectionScroll);
        assertNotNull(scroll);

        View parent = (View) activity.findViewById(R.id.statusHeader).getParent();
        while (parent != null && parent != scroll) {
            parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
        }
        assertEquals("the status header must live inside sectionScroll", scroll, parent);
    }

    /**
     * And in the night configuration. A theme incompatibility fails loudly, which
     * PHASE-4-VALIDATION calls the good news; the point of running it twice is the resources
     * that exist in one configuration and not the other.
     */
    @Test
    @Config(qualifiers = "night")
    public void theScreenInflatesAtNightToo() {
        launch();
        assertNotNull(activity.findViewById(R.id.statusHeader));
        assertNotNull(activity.findViewById(R.id.sipServer));
    }

    /**
     * <b>The acceptance list.</b> Every persisted control from PHASE-4-VALIDATION §0 exists
     * after the rewrite. A layout that silently drops one does not announce itself.
     */
    @Test
    public void everyPersistedControlSurvivedTheRewrite() {
        launch();
        for (int id : PERSISTED_CONTROLS) {
            assertNotNull("control " + activity.getResources().getResourceEntryName(id)
                    + " is missing after the layout rewrite", activity.findViewById(id));
        }
    }

    /** Existing is not the same as bound: every action still has a listener behind it. */
    @Test
    public void everyActionIsWired() {
        launch();
        int[] buttons = {R.id.saveButton, R.id.connectButton, R.id.disconnectButton,
                R.id.saveAudioButton, R.id.restartButton, R.id.testCallButton,
                R.id.testHangupButton,
                R.id.sipSectionHeader, R.id.audioSectionHeader,
                R.id.diagnosticsSectionHeader, R.id.systemSectionHeader};
        for (int id : buttons) {
            assertTrue(activity.getResources().getResourceEntryName(id) + " has no click handler",
                    view(id).hasOnClickListeners());
        }

        int[] spinners = {R.id.socProfileSpinner, R.id.cardSpinner, R.id.captureSpinner,
                R.id.playbackSpinner, R.id.mixerRouteSpinner, R.id.devicePresetSpinner};
        for (int id : spinners) {
            Spinner spinner = view(id);
            assertNotNull(activity.getResources().getResourceEntryName(id)
                            + " has no selection listener",
                    spinner.getOnItemSelectedListener());
        }
    }

    // ========== Round-tripping, the unwired-control check ==========

    /**
     * Set → Save → read it back out of {@code SharedPreferences}.
     *
     * <p>Reading the value back off the same screen would prove nothing: it may be showing
     * the in-memory field it just wrote. {@code GatewayConfig} is the thing that survives a
     * {@code force-stop}, so that is what is asserted.
     */
    @Test
    public void theSipControlsRoundTripThroughGatewayConfig() {
        launch();

        this.<EditText>view(R.id.sipServer).setText("pbx.example.org");
        this.<EditText>view(R.id.sipPort).setText("5061");
        this.<EditText>view(R.id.sipUser).setText("gateway7");
        this.<EditText>view(R.id.sipPassword).setText("s3cret");
        this.<EditText>view(R.id.sipRealm).setText("example.org");
        this.<CheckBox>view(R.id.useTls).setChecked(true);
        this.<EditText>view(R.id.sim1Destination).setText("201");
        this.<EditText>view(R.id.sim2Destination).setText("202");

        view(R.id.saveButton).performClick();

        GatewayConfig config = GatewayConfig.getInstance();
        assertEquals("pbx.example.org", config.getSipServer());
        assertEquals(5061, config.getSipPort());
        assertEquals("gateway7", config.getSipUser());
        assertEquals("s3cret", config.getSipPassword());
        assertEquals("example.org", config.getSipRealm());
        assertTrue(config.isUseTls());
        assertEquals("201", config.getSim1Destination());
        assertEquals("202", config.getSim2Destination());
    }

    /**
     * The audio half, including the SoC profile - the control that did not exist on this
     * surface before (plan §C8, step 4d).
     */
    @Test
    public void theAudioControlsRoundTripThroughGatewayConfig() {
        launch();

        this.<EditText>view(R.id.txGainEdit).setText("-6.0");
        this.<EditText>view(R.id.rxGainEdit).setText("2.5");
        this.<EditText>view(R.id.manualMuteControls).setText("DEC1 Volume, EAR_S");

        // The spinner-backed values go through the ViewModel, which is where they live now.
        viewModel.setSelectedAudioProfile("mediatek");
        viewModel.setSelectedMixerRoute("MultiMedia4");
        viewModel.setSelectedCard(1);
        viewModel.setSelectedCaptureDevice(9);
        viewModel.setSelectedPlaybackDevice(4);

        view(R.id.saveAudioButton).performClick();

        GatewayConfig config = GatewayConfig.getInstance();
        assertEquals(-6.0f, config.getTxGain(), 0.001f);
        assertEquals(2.5f, config.getRxGain(), 0.001f);
        assertEquals("DEC1 Volume, EAR_S", config.getManualMuteControls());
        assertEquals("mediatek", config.getAudioProfile());
        assertEquals("MultiMedia4", config.getMultimediaRoute());
        assertEquals(1, config.getAudioCard());
        assertEquals(9, config.getCaptureDevice());
        assertEquals(4, config.getPlaybackDevice());
    }

    /**
     * The controls with no Save button behind them. These write through on change, which is
     * the other way a control goes quietly unwired: nothing to press, so nothing to notice.
     */
    @Test
    public void theWriteThroughControlsPersistOnChange() {
        launch();

        // MODE_SIP_FIRST is 0 and MODE_ANSWER_FIRST is 1, which is the opposite of the
        // reading order of the radio group - worth naming rather than writing the literals.
        this.<RadioButton>view(R.id.modeAnswerFirst).setChecked(true);
        assertEquals(GatewayInCallService.MODE_ANSWER_FIRST,
                GatewayConfig.getInstance().getIncomingCallMode());
        this.<RadioButton>view(R.id.modeSipFirst).setChecked(true);
        assertEquals(GatewayInCallService.MODE_SIP_FIRST,
                GatewayConfig.getInstance().getIncomingCallMode());

        this.<RadioButton>view(R.id.limit100).setChecked(true);
        assertEquals(100, GatewayConfig.getInstance().getBatteryLimit());
        this.<RadioButton>view(R.id.limit60).setChecked(true);
        assertEquals(60, GatewayConfig.getInstance().getBatteryLimit());

        this.<CheckBox>view(R.id.verboseSipLogCheckbox).setChecked(true);
        assertTrue(GatewayConfig.getInstance().isVerboseSipLog());

        this.<CheckBox>view(R.id.dtmfRelayCheckbox).setChecked(true);
        assertTrue(GatewayConfig.getInstance().isDtmfRelayEnabled());

        this.<CompoundButton>view(R.id.webInterfaceSwitch).setChecked(false);
        assertFalse(GatewayConfig.getInstance().isWebInterfaceEnabled());
    }

    /** What is persisted comes back onto the screen on the next launch. */
    @Test
    public void thePersistedValuesArePaintedBackOnLaunch() {
        GatewayConfig config = GatewayConfig.getInstance();
        config.updateSipConfig("saved.example.org", 5080, "saveduser", "savedpass", "r", true);
        config.updateSimDestinations("301", "302");
        config.setTxGain(-3.5f);
        config.setRxGain(1.25f);
        config.setManualMuteControls("ADC1 MUX");
        config.setTestDestination("*97");
        config.setVerboseSipLog(true);
        config.setDtmfRelayEnabled(true);
        config.setBatteryLimit(100);
        config.setIncomingCallMode(GatewayInCallService.MODE_ANSWER_FIRST);

        launch();

        assertEquals("saved.example.org", text(R.id.sipServer));
        assertEquals("5080", text(R.id.sipPort));
        assertEquals("saveduser", text(R.id.sipUser));
        assertEquals("savedpass", text(R.id.sipPassword));
        assertEquals("r", text(R.id.sipRealm));
        assertTrue(this.<CheckBox>view(R.id.useTls).isChecked());
        assertEquals("301", text(R.id.sim1Destination));
        assertEquals("302", text(R.id.sim2Destination));
        assertEquals("-3.5", text(R.id.txGainEdit));
        assertEquals("1.25", text(R.id.rxGainEdit));
        assertEquals("ADC1 MUX", text(R.id.manualMuteControls));
        assertEquals("*97", text(R.id.testDestination));
        assertTrue(this.<CheckBox>view(R.id.verboseSipLogCheckbox).isChecked());
        assertTrue(this.<CheckBox>view(R.id.dtmfRelayCheckbox).isChecked());
        assertTrue(this.<RadioButton>view(R.id.limit100).isChecked());
        assertTrue(this.<RadioButton>view(R.id.modeAnswerFirst).isChecked());
    }

    // ========== H-b: the operator's typing survives a remote save ==========

    /**
     * <b>Plan §4 hazard H-b, end to end.</b>
     *
     * <p>Someone is filling the form in on the phone. A config POST lands on the web
     * interface, the reload bumps the snapshot's generation, and the 1 Hz poll re-reads
     * config in place - the mechanism GW-14 put in when it deleted the activity relaunch,
     * precisely so the phone-holder's work would survive. The old observer then overwrote all
     * eight SIP fields anyway.
     *
     * <p>Note what is being asserted: the fields the operator touched keep their values while
     * a field they did not touch takes the remote one. A guard that simply refused all
     * updates would pass a weaker version of this test and be wrong.
     */
    @Test
    public void aRemoteConfigSaveDoesNotOverwriteFieldsTheOperatorIsEditing() throws Exception {
        launch();

        // Bind, and let the ViewModel see one snapshot first: the generation counter only
        // means "config changed under us" from the second one onwards, which is the same
        // thing that stops a fresh binding from repainting the form.
        publishGeneration(6L);
        poll();

        // Typing. Not focused any more - they have moved on to the next box, which is the
        // case a bare hasFocus() guard does not cover.
        this.<EditText>view(R.id.sipServer).setText("being.typed.in");
        this.<EditText>view(R.id.sipUser).setText("halfway");

        // Meanwhile, on the web interface.
        GatewayConfig.getInstance().updateSipConfig(
                "remote.example.org", 5062, "remoteuser", "remotepass", "*", false);
        publishGeneration(7L);
        poll();

        assertEquals("the operator's typing must survive a remote save",
                "being.typed.in", text(R.id.sipServer));
        assertEquals("halfway", text(R.id.sipUser));
        assertEquals("a field nobody was editing must take the remote value",
                "remotepass", text(R.id.sipPassword));
        assertEquals("5062", text(R.id.sipPort));

        // And the guard is released by a save, or a later remote change could never land.
        view(R.id.saveButton).performClick();
        GatewayConfig.getInstance().updateSipConfig(
                "later.example.org", 5063, "lateruser", "laterpass", "*", false);
        publishGeneration(8L);
        poll();

        assertEquals("after a save there is no unsaved work to protect",
                "later.example.org", text(R.id.sipServer));
    }

    // ========== The status header ==========

    /**
     * A published {@link GatewayStatus} reaches the views - all of it, including the parts
     * the pre-GW-41 screen had no place for.
     */
    @Test
    public void aPublishedStatusReachesTheHeader() throws Exception {
        launch();

        publish(snapshot(new GatewayStatus.WatchdogFindings(
                System.currentTimeMillis() - 65_000L, 2L, 3L,
                "CallManager is IDLE but still holds a live SIP call",
                System.currentTimeMillis() - 120_000L)));
        poll();

        assertEquals("Registered", text(R.id.statusText));
        assertEquals("GSM<->SIP bridged", text(R.id.callStatusText));
        assertEquals("Bridged", text(R.id.audioStatusText));
        assertEquals("BRIDGED", text(R.id.callChip));

        assertEquals(activity.getString(R.string.chip_registered), text(R.id.sipChip));

        // Derived at draw time, never cached: about a minute of call, formatted.
        assertEquals(View.VISIBLE, view(R.id.callDurationText).getVisibility());
        assertTrue("duration should read as about a minute, was " + text(R.id.callDurationText),
                text(R.id.callDurationText).startsWith("01:0"));

        // GW-22's counters, which had no view at all before.
        assertTrue(text(R.id.callCountersText).contains("alive 1"));

        // GW-25's findings, which are the reason the header exists.
        assertTrue(text(R.id.watchdogSummaryText).contains("terminated 2"));
        assertTrue(text(R.id.watchdogSummaryText).contains("silent bridge 3"));
        assertEquals(View.VISIBLE, view(R.id.watchdogFindingText).getVisibility());
        assertTrue(text(R.id.watchdogFindingText)
                .contains("CallManager is IDLE but still holds a live SIP call"));
    }

    /** A clean watchdog says so once, and does not keep an empty "last finding" row around. */
    @Test
    public void aCleanWatchdogHidesTheFindingRow() throws Exception {
        launch();
        publish(snapshot(GatewayStatus.WatchdogFindings.NONE));
        poll();

        assertEquals(activity.getString(R.string.status_watchdog_clean),
                text(R.id.watchdogSummaryText));
        assertEquals(View.GONE, view(R.id.watchdogFindingText).getVisibility());
    }

    /**
     * "Not bound" and "bound but idle" both read {@code UNAVAILABLE}; only
     * {@code getServiceConnected()} tells them apart, and the words are the view's to choose.
     */
    @Test
    public void anUnboundServiceReadsAsNotConnected() throws Exception {
        launch();
        poll();

        assertEquals(activity.getString(R.string.chip_no_service), text(R.id.sipChip));
        assertEquals(activity.getString(R.string.status_not_connected), text(R.id.statusText));
        assertEquals(View.GONE, view(R.id.callDurationText).getVisibility());
    }

    // ========== Sections ==========

    /**
     * SIP starts open, the rest closed, and a tap on a header toggles the body. Collapsed is
     * not the same as absent: everything in the acceptance list above is reachable by
     * {@code findViewById} whether or not its section is open.
     */
    @Test
    public void theSectionsCollapseAndExpand() {
        launch();

        assertEquals(View.VISIBLE, view(R.id.sipSectionBody).getVisibility());
        assertEquals(View.GONE, view(R.id.audioSectionBody).getVisibility());
        assertEquals(View.GONE, view(R.id.diagnosticsSectionBody).getVisibility());
        assertEquals(View.GONE, view(R.id.systemSectionBody).getVisibility());

        view(R.id.audioSectionHeader).performClick();
        assertEquals(View.VISIBLE, view(R.id.audioSectionBody).getVisibility());
        assertEquals(activity.getString(R.string.label_section_expanded),
                text(R.id.audioSectionChevron));

        view(R.id.audioSectionHeader).performClick();
        assertEquals(View.GONE, view(R.id.audioSectionBody).getVisibility());
        assertEquals(activity.getString(R.string.label_section_collapsed),
                text(R.id.audioSectionChevron));
    }

    // ========== Plumbing ==========

    private String text(int id) {
        return this.<TextView>view(id).getText().toString();
    }

    /**
     * Give the ViewModel a service to read, without ever calling {@code onCreate} on it - the
     * only thing it is asked for is the immutable snapshot, which is the point of the
     * snapshot.
     */
    private PjsipSipService boundService() throws Exception {
        Field serviceField = MainViewModel.class.getDeclaredField("pjsipService");
        serviceField.setAccessible(true);
        PjsipSipService existing = (PjsipSipService) serviceField.get(viewModel);
        if (existing != null) {
            return existing;
        }
        PjsipSipService service = Robolectric.buildService(PjsipSipService.class).get();
        serviceField.set(viewModel, service);
        return service;
    }

    private GatewayStatus snapshot(GatewayStatus.WatchdogFindings watchdog) throws Exception {
        Constructor<GatewayStatus> ctor = GatewayStatus.class.getDeclaredConstructor(
                boolean.class, boolean.class, String.class, String.class, String.class,
                String.class, long.class, long.class, long.class, long.class,
                GatewayStatus.WatchdogFindings.class, long.class);
        ctor.setAccessible(true);
        return ctor.newInstance(
                true, true, "Registered", "GSM<->SIP bridged", "Bridged", "BRIDGED",
                0L, 0L, 41L, 40L, watchdog, System.currentTimeMillis());
    }

    private void publish(GatewayStatus status) throws Exception {
        Field statusField = PjsipSipService.class.getDeclaredField("status");
        statusField.setAccessible(true);
        statusField.set(boundService(), status);
    }

    private void publishGeneration(long generation) throws Exception {
        Constructor<GatewayStatus> ctor = GatewayStatus.class.getDeclaredConstructor(
                boolean.class, boolean.class, String.class, String.class, String.class,
                String.class, long.class, long.class, long.class, long.class,
                GatewayStatus.WatchdogFindings.class, long.class);
        ctor.setAccessible(true);
        publish(ctor.newInstance(true, true, "Registered", "Idle", "Bridged", "IDLE",
                0L, generation, 0L, 0L, GatewayStatus.WatchdogFindings.NONE,
                System.currentTimeMillis()));
    }

    /** One tick of the 1 Hz poll, without waiting a second for it. */
    private void poll() throws Exception {
        Method update = MainViewModel.class.getDeclaredMethod("updateServiceState");
        update.setAccessible(true);
        update.invoke(viewModel);
    }
}
