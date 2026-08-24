package org.onetwoone.gateway.ui.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.onetwoone.gateway.MainActivity;
import org.onetwoone.gateway.R;
import org.onetwoone.gateway.config.GatewayConfig;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

/**
 * GW-42 - the wizard, inflated and driven, against the real resource table.
 *
 * <h2>What this is testing for</h2>
 *
 * <p>{@link SetupStepMachineTest} proves the cursor and the scoreboard. This file proves the
 * things that can only go wrong once the machine is wired to a screen and to
 * {@code SharedPreferences}, and each of them is a way to strand or rob the person holding the
 * phone:
 *
 * <ul>
 *   <li>Skip is reachable and enabled on <em>every</em> step, and skipping through five times
 *       ends the wizard rather than looping.
 *   <li>The first-run flag is written by every dismissal - finishing, skipping through,
 *       closing - and by none of them is it left unwritten, because a wizard that comes back
 *       on every launch is the same trap as one that blocks.
 *   <li>Re-running pre-fills from {@code GatewayConfig} and <b>cannot wipe it</b>: a blank
 *       field keeps the stored value, and a skip writes nothing at all.
 *   <li>Moving back and forth does not lose what was typed.
 *   <li>The verification destination never defaults to a feature code, which is the failure
 *       this step was specifically designed around.
 * </ul>
 *
 * <p><b>What it does not prove.</b> Anything about appearance, and anything that needs a PBX or
 * a modem. Whether a registration is real, whether the PBX routes a destination, and whether
 * audio crosses the bridge are all device work - see PHASE-4-VALIDATION.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class SetupWizardTest {

    private ActivityController<SetupActivity> controller;
    private SetupActivity activity;

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
            controller = null;
        }
    }

    private void launch() {
        controller = Robolectric.buildActivity(SetupActivity.class).setup();
        activity = controller.get();
    }

    /** Drive the activity all the way out, the way closing it does. */
    private void closeWizard() {
        controller.pause().stop().destroy();
        controller = null;
    }

    private <T extends View> T view(int id) {
        T found = activity.findViewById(id);
        assertNotNull("view " + activity.getResources().getResourceEntryName(id)
                + " is missing from the wizard layout", found);
        return found;
    }

    private Button skipButton() {
        return view(R.id.setupSkipButton);
    }

    private Button nextButton() {
        return view(R.id.setupNextButton);
    }

    private static GatewayConfig config() {
        return GatewayConfig.getInstance();
    }

    /** Put a complete, working account in place, as if this handset were already in service. */
    private static void storeWorkingAccount() {
        GatewayConfig config = config();
        config.updateSipConfig("pbx.example.org", 5061, "gateway7", "s3cret", "example.org", true);
        config.updateSimDestinations("201", "202");
    }

    // ========== Inflation ==========

    @Test
    public void theWizardInflates() {
        launch();
        assertNotNull(activity.findViewById(R.id.setupRoot));
        assertNotNull(activity.findViewById(R.id.setupStepIndicator));
    }

    /** And in the night configuration, where a token defined in only one of the two shows up. */
    @Test
    @Config(qualifiers = "night")
    public void theWizardInflatesAtNightToo() {
        launch();
        assertNotNull(activity.findViewById(R.id.setupRoot));
        assertNotNull(activity.findViewById(R.id.setupSipServer));
    }

    /**
     * The wizard carries its own header, so the manifest gives it the no-app-bar theme. Pinned
     * here because losing that attribute would put a second, emptier header above the first and
     * cost ~56dp on the screen the step body is already competing for.
     */
    @Test
    public void theWizardHasNoActionBarOfItsOwn() {
        launch();
        assertNull("Theme.Gateway.Setup was not applied", activity.getSupportActionBar());
    }

    @Test
    public void everyStepHasItsBodyInTheLayout() {
        launch();
        for (SetupStep step : SetupStep.values()) {
            assertNotNull("step " + step + " has no body in activity_setup.xml",
                    activity.findViewById(step.bodyViewId()));
        }
    }

    @Test
    public void everyActionIsWired() {
        launch();
        int[] buttons = {R.id.setupCloseButton, R.id.setupBackButton, R.id.setupSkipButton,
                R.id.setupNextButton, R.id.setupRootCheckButton, R.id.setupGrantButton,
                R.id.setupRefreshPermissionsButton, R.id.setupBatteryOptButton,
                R.id.setupClaimDialerButton, R.id.setupSaveAccountButton,
                R.id.setupStartGatewayButton, R.id.setupReRegisterButton,
                R.id.setupTestCallButton, R.id.setupTestHangupButton};
        for (int id : buttons) {
            assertTrue(activity.getResources().getResourceEntryName(id) + " has no click handler",
                    this.<View>view(id).hasOnClickListeners());
        }
    }

    // ========== One step at a time ==========

    @Test
    public void onlyTheCurrentStepIsVisible() {
        launch();

        for (SetupStep step : SetupStep.values()) {
            int expected = step == SetupStep.ROOT ? View.VISIBLE : View.GONE;
            assertEquals("step " + step + " has the wrong visibility on step 1",
                    expected, this.<View>view(step.bodyViewId()).getVisibility());
        }

        nextButton().performClick();

        assertEquals(View.GONE, this.<View>view(SetupStep.ROOT.bodyViewId()).getVisibility());
        assertEquals(View.VISIBLE,
                this.<View>view(SetupStep.PERMISSIONS.bodyViewId()).getVisibility());
    }

    @Test
    public void theHeaderCountsTheSteps() {
        launch();

        TextView indicator = view(R.id.setupStepIndicator);
        assertEquals(activity.getString(R.string.setup_step_indicator, 1, 5),
                indicator.getText().toString());

        nextButton().performClick();
        nextButton().performClick();

        assertEquals(activity.getString(R.string.setup_step_indicator, 3, 5),
                indicator.getText().toString());
        assertEquals(activity.getString(R.string.setup_step_dialer_title),
                this.<TextView>view(R.id.setupStepTitle).getText().toString());
    }

    /** Next becomes Finish on the last step, so nothing implies a sixth. */
    @Test
    public void theLastStepOffersFinishRatherThanNext() {
        launch();
        for (int i = 0; i < 4; i++) {
            nextButton().performClick();
        }
        assertEquals(activity.getString(R.string.setup_action_finish),
                nextButton().getText().toString());
    }

    // ========== Skip - the property the whole issue turns on ==========

    /**
     * <b>Skip is enabled on every step</b>, and Next is too. Back is the only control that is
     * ever disabled, and only because there is nothing behind step 1.
     */
    @Test
    public void skipAndNextAreNeverDisabledOnAnyStep() {
        launch();

        for (int i = 0; i < SetupStep.values().length; i++) {
            assertTrue("Skip is disabled on step " + (i + 1), skipButton().isEnabled());
            assertTrue("Next is disabled on step " + (i + 1), nextButton().isEnabled());
            if (i == 0) {
                assertFalse("Back should be dead on step 1",
                        this.<Button>view(R.id.setupBackButton).isEnabled());
            } else {
                assertTrue("Back is disabled on step " + (i + 1),
                        this.<Button>view(R.id.setupBackButton).isEnabled());
            }
            if (i < SetupStep.values().length - 1) {
                nextButton().performClick();
            }
        }
    }

    /** Skipping through every step ends the wizard rather than looping on the last one. */
    @Test
    public void skippingEveryStepFinishesTheWizard() {
        launch();

        for (int i = 0; i < SetupStep.values().length; i++) {
            assertFalse("the wizard finished early, on step " + (i + 1), activity.isFinishing());
            skipButton().performClick();
        }

        assertTrue("skipping the last step must end the wizard", activity.isFinishing());
    }

    /** And so does walking to the end and pressing Finish. */
    @Test
    public void finishingFromTheLastStepEndsTheWizard() {
        launch();
        for (int i = 0; i < 4; i++) {
            nextButton().performClick();
        }
        assertFalse(activity.isFinishing());

        nextButton().performClick();

        assertTrue(activity.isFinishing());
    }

    @Test
    public void closeEndsTheWizardFromAnyStep() {
        launch();
        nextButton().performClick();
        nextButton().performClick();

        this.<View>view(R.id.setupCloseButton).performClick();

        assertTrue(activity.isFinishing());
    }

    // ========== The first-run flag ==========

    @Test
    public void aFreshHandsetHasNotSeenTheWizard() {
        assertFalse(config().isSetupCompleted());
    }

    /** Skipping counts as done: the wizard must not come back on the next launch. */
    @Test
    public void skippingEveryStepMarksTheWizardSeen() {
        launch();
        for (int i = 0; i < SetupStep.values().length; i++) {
            skipButton().performClick();
        }
        closeWizard();

        assertTrue("a skipped-through wizard would reappear on every launch",
                config().isSetupCompleted());
    }

    @Test
    public void finishingMarksTheWizardSeen() {
        launch();
        for (int i = 0; i < SetupStep.values().length; i++) {
            nextButton().performClick();
        }
        closeWizard();

        assertTrue(config().isSetupCompleted());
    }

    /** Closing half way through counts too - the operator dismissed it. */
    @Test
    public void closingHalfWayMarksTheWizardSeen() {
        launch();
        nextButton().performClick();
        this.<View>view(R.id.setupCloseButton).performClick();
        closeWizard();

        assertTrue(config().isSetupCompleted());
    }

    /**
     * A configuration change is not a dismissal. Rotating the phone must not silently retire
     * the wizard on a handset whose operator has not decided anything yet.
     */
    @Test
    public void aConfigurationChangeDoesNotMarkTheWizardSeen() {
        launch();
        controller.pause().stop();

        assertFalse("onStop without isFinishing() is a recreation, not a dismissal",
                config().isSetupCompleted());

        controller.destroy();
        controller = null;
    }

    // ========== First-run detection, from the main screen ==========

    @Test
    public void theMainScreenOpensTheWizardOnAHandsetThatHasNeverSeenIt() {
        ActivityController<MainActivity> main =
                Robolectric.buildActivity(MainActivity.class).setup();

        Intent started = shadowOf(main.get()).getNextStartedActivity();
        assertNotNull("a fresh handset should be offered the wizard", started);
        assertEquals(SetupActivity.class.getName(), started.getComponent().getClassName());

        main.pause().stop().destroy();
    }

    /** And leaves it alone once it has been dismissed, however it was dismissed. */
    @Test
    public void theMainScreenDoesNotReopenTheWizardOnceItHasBeenSeen() {
        config().setSetupCompleted(true);

        ActivityController<MainActivity> main =
                Robolectric.buildActivity(MainActivity.class).setup();

        assertNull("the wizard reappeared on a handset that has already dismissed it",
                shadowOf(main.get()).getNextStartedActivity());

        main.pause().stop().destroy();
    }

    /** The re-run entry point exists on the main screen and starts the wizard. */
    @Test
    public void theSystemSectionCanReRunTheWizard() {
        config().setSetupCompleted(true);

        ActivityController<MainActivity> main =
                Robolectric.buildActivity(MainActivity.class).setup();
        View button = main.get().findViewById(R.id.setupWizardButton);
        assertNotNull("the System section has no way back into the wizard", button);
        assertTrue(button.hasOnClickListeners());

        button.performClick();

        Intent started = shadowOf(main.get()).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(SetupActivity.class.getName(), started.getComponent().getClassName());

        main.pause().stop().destroy();
    }

    // ========== The account step: pre-fill, and never wiping ==========

    @Test
    public void theAccountStepPreFillsFromWhatIsStored() {
        storeWorkingAccount();
        launch();

        assertEquals("pbx.example.org", this.<EditText>view(R.id.setupSipServer).getText().toString());
        assertEquals("5061", this.<EditText>view(R.id.setupSipPort).getText().toString());
        assertEquals("gateway7", this.<EditText>view(R.id.setupSipUser).getText().toString());
        assertEquals("s3cret", this.<EditText>view(R.id.setupSipPassword).getText().toString());
        assertEquals("example.org", this.<EditText>view(R.id.setupSipRealm).getText().toString());
        assertEquals("201", this.<EditText>view(R.id.setupSim1Destination).getText().toString());
        assertEquals("202", this.<EditText>view(R.id.setupSim2Destination).getText().toString());
    }

    /** And says so, so that replacing it is a decision rather than a discovery. */
    @Test
    public void theAccountStepSaysWhatIsAlreadyConfigured() {
        storeWorkingAccount();
        launch();

        TextView banner = view(R.id.setupSipExistingBanner);
        assertEquals(View.VISIBLE, banner.getVisibility());
        assertTrue(banner.getText().toString().contains("gateway7"));
        assertTrue(banner.getText().toString().contains("pbx.example.org"));
    }

    @Test
    public void theBannerStaysAwayOnAnUnconfiguredHandset() {
        launch();
        assertEquals(View.GONE, this.<View>view(R.id.setupSipExistingBanner).getVisibility());
    }

    /**
     * <b>Skipping the account step writes nothing.</b> The guarantee that makes re-running the
     * wizard on a working gateway safe.
     */
    @Test
    public void skippingTheWholeWizardChangesNoConfiguration() {
        storeWorkingAccount();
        launch();

        for (int i = 0; i < SetupStep.values().length; i++) {
            skipButton().performClick();
        }

        assertEquals("pbx.example.org", config().getSipServer());
        assertEquals(5061, config().getSipPort());
        assertEquals("gateway7", config().getSipUser());
        assertEquals("s3cret", config().getSipPassword());
        assertEquals("example.org", config().getSipRealm());
        assertTrue(config().isUseTls());
        assertEquals("201", config().getSim1Destination());
        assertEquals("202", config().getSim2Destination());
    }

    /**
     * <b>An emptied box keeps the stored value.</b> The password is the case that matters: on
     * a re-run, someone who clears it and moves on must not lose the credential that was
     * working five seconds ago.
     */
    @Test
    public void aClearedFieldDoesNotWipeWhatIsStored() {
        storeWorkingAccount();
        launch();
        goToAccountStep();

        this.<EditText>view(R.id.setupSipPassword).setText("");
        this.<EditText>view(R.id.setupSipRealm).setText("");
        this.<EditText>view(R.id.setupSim2Destination).setText("");
        this.<EditText>view(R.id.setupSipServer).setText("pbx2.example.org");

        nextButton().performClick();

        assertEquals("the account moved, as asked", "pbx2.example.org", config().getSipServer());
        assertEquals("the password was wiped by an empty box", "s3cret", config().getSipPassword());
        assertEquals("example.org", config().getSipRealm());
        assertEquals("202", config().getSim2Destination());
    }

    /** The port too: an empty or unparseable box keeps the stored port, never resets to 5060. */
    @Test
    public void anUnparseablePortKeepsTheStoredOne() {
        storeWorkingAccount();
        launch();
        goToAccountStep();

        this.<EditText>view(R.id.setupSipPort).setText("");
        nextButton().performClick();

        assertEquals(5061, config().getSipPort());
    }

    /** What is typed is what is saved, on the normal path. */
    @Test
    public void theAccountStepSavesWhatWasTyped() {
        launch();
        goToAccountStep();

        this.<EditText>view(R.id.setupSipServer).setText("pbx.kurus.me");
        this.<EditText>view(R.id.setupSipPort).setText("5060");
        this.<EditText>view(R.id.setupSipUser).setText("gsmgw");
        this.<EditText>view(R.id.setupSipPassword).setText("hunter2");
        this.<EditText>view(R.id.setupSim1Destination).setText("301");

        this.<View>view(R.id.setupSaveAccountButton).performClick();

        assertEquals("pbx.kurus.me", config().getSipServer());
        assertEquals(5060, config().getSipPort());
        assertEquals("gsmgw", config().getSipUser());
        assertEquals("hunter2", config().getSipPassword());
        assertEquals("301", config().getSim1Destination());
    }

    /** Save without moving on stays on the step, so a save is not also a navigation. */
    @Test
    public void savingDoesNotAdvanceTheWizard() {
        launch();
        goToAccountStep();

        this.<EditText>view(R.id.setupSipServer).setText("pbx.kurus.me");
        this.<View>view(R.id.setupSaveAccountButton).performClick();

        assertEquals(View.VISIBLE,
                this.<View>view(SetupStep.SIP_ACCOUNT.bodyViewId()).getVisibility());
        assertFalse(activity.isFinishing());
    }

    // ========== Back-navigation keeps what was typed ==========

    @Test
    public void goingBackAndForwardKeepsWhatWasEntered() {
        launch();
        goToAccountStep();

        this.<EditText>view(R.id.setupSipServer).setText("half.typed.example");

        this.<View>view(R.id.setupBackButton).performClick();
        assertEquals(View.VISIBLE, this.<View>view(SetupStep.DIALER.bodyViewId()).getVisibility());

        nextButton().performClick();

        assertEquals("moving back and forward threw away an unsaved edit",
                "half.typed.example",
                this.<EditText>view(R.id.setupSipServer).getText().toString());
        assertEquals("and it should not have been persisted either", "", config().getSipServer());
    }

    /** Back is the step cursor, not a save. Nothing is written on the way. */
    @Test
    public void backWritesNothing() {
        storeWorkingAccount();
        launch();
        goToAccountStep();

        this.<EditText>view(R.id.setupSipServer).setText("not.saved.example");
        this.<View>view(R.id.setupBackButton).performClick();

        assertEquals("pbx.example.org", config().getSipServer());
    }

    // ========== The verification step ==========

    /**
     * <b>The default destination is never a feature code.</b> The gateway's trunk cannot dial
     * one - the in-app {@code *43} test call is rejected by the PBX's {@code from-gsm-gateway}
     * context - so defaulting to the shipped {@code *43} would fail the wizard on a correctly
     * configured gateway.
     */
    @Test
    public void theVerificationDestinationNeverDefaultsToAFeatureCode() {
        assertEquals("the shipped default is still the feature code this test is about",
                "*43", config().getTestDestination());
        config().updateSimDestinations("201", "");

        launch();

        String destination = this.<EditText>view(R.id.setupTestDestination).getText().toString();
        assertFalse("the wizard offered a feature code as its default destination",
                SetupViewModel.isFeatureCode(destination));
        assertEquals("the SIM routing is the best available guess at a routable extension",
                "201", destination);
    }

    /** With nothing to go on, it asks rather than guessing. */
    @Test
    public void withNoRoutingConfiguredTheDestinationIsLeftEmpty() {
        launch();
        assertEquals("", this.<EditText>view(R.id.setupTestDestination).getText().toString());
    }

    /** A stored non-feature-code destination wins: it is what the operator last used. */
    @Test
    public void aStoredExtensionIsPreferredOverTheSimRouting() {
        config().setTestDestination("777");
        config().updateSimDestinations("201", "202");

        launch();

        assertEquals("777", this.<EditText>view(R.id.setupTestDestination).getText().toString());
    }

    /** Typing a feature code warns. It does not refuse - the deployment is not ours to assume. */
    @Test
    public void typingAFeatureCodeWarnsWithoutBlocking() {
        launch();
        goToVerifyStep();

        View warning = view(R.id.setupTestDestinationWarning);
        assertEquals(View.GONE, warning.getVisibility());

        this.<EditText>view(R.id.setupTestDestination).setText("*43");
        assertEquals(View.VISIBLE, warning.getVisibility());
        assertTrue("dialling it anyway must stay possible",
                this.<View>view(R.id.setupTestCallButton).isEnabled());
        assertTrue(nextButton().isEnabled());

        this.<EditText>view(R.id.setupTestDestination).setText("201");
        assertEquals(View.GONE, warning.getVisibility());
    }

    /** With no gateway running, the step says so rather than claiming a failure. */
    @Test
    public void withNothingRunningRegistrationIsUnknownRatherThanFailed() {
        launch();
        goToVerifyStep();

        assertEquals(activity.getString(R.string.setup_registration_not_running),
                this.<TextView>view(R.id.setupRegistrationDetail).getText().toString());
    }

    /** The step that cannot check audio says so on screen, permanently. */
    @Test
    public void theVerificationStepStatesWhatItCannotCheck() {
        launch();
        goToVerifyStep();

        TextView caveat = view(R.id.setupAudioCaveat);
        assertEquals(View.VISIBLE, caveat.getVisibility());
        assertEquals(activity.getString(R.string.setup_audio_caveat),
                caveat.getText().toString());
    }

    // ========== The pure verification logic ==========

    @Test
    public void featureCodesAreRecognisedAndNothingElseIs() {
        assertTrue(SetupViewModel.isFeatureCode("*43"));
        assertTrue(SetupViewModel.isFeatureCode("  *97 "));
        assertTrue(SetupViewModel.isFeatureCode("#1"));
        assertFalse(SetupViewModel.isFeatureCode("201"));
        assertFalse(SetupViewModel.isFeatureCode("+79991234567"));
        assertFalse(SetupViewModel.isFeatureCode(""));
        assertFalse(SetupViewModel.isFeatureCode(null));
    }

    /**
     * The transcript's five outcomes, in the order that matters.
     *
     * <p>A normal test call ends CONFIRMED then DISCONNECTED, so confirmation has to win over
     * disconnection or every successful call would read as unanswered. An error wins over both,
     * because a wiring failure is reported after the call is up.
     */
    @Test
    public void theTranscriptIsReadForExactlyWhatItSays() {
        assertSame(SetupViewModel.TestCallVerdict.NOT_RUN, SetupViewModel.verdictOf(null));
        assertSame(SetupViewModel.TestCallVerdict.NOT_RUN, SetupViewModel.verdictOf("   "));

        assertSame(SetupViewModel.TestCallVerdict.DIALING,
                SetupViewModel.verdictOf("[  0.0s] mode=TONE\n[  0.1s] INVITE sent\n"));

        assertSame(SetupViewModel.TestCallVerdict.ANSWERED,
                SetupViewModel.verdictOf("[  0.1s] INVITE sent\n[  1.2s] call CONFIRMED\n"));

        assertSame("a completed call ends disconnected and is still an answered call",
                SetupViewModel.TestCallVerdict.ANSWERED,
                SetupViewModel.verdictOf("INVITE sent\ncall CONFIRMED\ncall DISCONNECTED\n"));

        assertSame(SetupViewModel.TestCallVerdict.NOT_ANSWERED,
                SetupViewModel.verdictOf("INVITE sent\ncall DISCONNECTED\n"));

        assertSame(SetupViewModel.TestCallVerdict.FAILED,
                SetupViewModel.verdictOf("ERROR: no SIP account (is the gateway registered?)"));

        assertSame("an error after the call came up is still a failure",
                SetupViewModel.TestCallVerdict.FAILED,
                SetupViewModel.verdictOf("call CONFIRMED\nERROR: wiring failed: boom"));
    }

    @Test
    public void aBlankFieldMeansKeepWhatIsStored() {
        assertEquals("stored", SetupViewModel.keepStoredIfBlank(null, "stored"));
        assertEquals("stored", SetupViewModel.keepStoredIfBlank("", "stored"));
        assertEquals("stored", SetupViewModel.keepStoredIfBlank("   ", "stored"));
        assertEquals("typed", SetupViewModel.keepStoredIfBlank("  typed  ", "stored"));
        assertEquals("", SetupViewModel.keepStoredIfBlank("", ""));
    }

    @Test
    public void anImpossiblePortKeepsTheStoredOne() {
        assertEquals(5061, SetupViewModel.parsePort("", 5061));
        assertEquals(5061, SetupViewModel.parsePort(null, 5061));
        assertEquals(5061, SetupViewModel.parsePort("not a port", 5061));
        assertEquals(5061, SetupViewModel.parsePort("0", 5061));
        assertEquals(5061, SetupViewModel.parsePort("70000", 5061));
        assertEquals(5060, SetupViewModel.parsePort(" 5060 ", 5061));
    }

    // ========== Helpers ==========

    private void goToAccountStep() {
        for (int i = 0; i < 3; i++) {
            nextButton().performClick();
        }
        assertEquals(View.VISIBLE,
                this.<View>view(SetupStep.SIP_ACCOUNT.bodyViewId()).getVisibility());
    }

    private void goToVerifyStep() {
        for (int i = 0; i < 4; i++) {
            nextButton().performClick();
        }
        assertEquals(View.VISIBLE,
                this.<View>view(SetupStep.VERIFY.bodyViewId()).getVisibility());
    }
}
