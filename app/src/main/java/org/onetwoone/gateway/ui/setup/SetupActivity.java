package org.onetwoone.gateway.ui.setup;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import org.onetwoone.gateway.R;
import org.onetwoone.gateway.core.GatewayStatus;
import org.onetwoone.gateway.ui.FormGuard;
import org.onetwoone.gateway.ui.MainViewModel;
import org.onetwoone.gateway.ui.PermissionManager;

import java.util.EnumMap;
import java.util.Map;

/**
 * The commissioning wizard (GW-42).
 *
 * <h2>The constraint that shapes this whole screen</h2>
 *
 * <p><b>Skippable at every step. Re-runnable from settings. Never gates the main screen.</b>
 *
 * <p>The failure mode this issue exists to avoid is a half-provisioned phone that cannot be
 * recovered because the wizard is standing in front of the console you need. This is an
 * appliance you may be fixing in the field, possibly over {@code adb}, possibly with a cracked
 * screen. <b>A wizard that blocks is worse than no wizard.</b> Concretely, and each of these is
 * covered by a test:
 *
 * <ul>
 *   <li>Skip works on every step, including the last, and skipping through all five reaches the
 *       end - {@link SetupStepMachine}.
 *   <li>Closing at any point lands on the main screen, because the wizard is opened
 *       <em>over</em> it and never instead of it - {@link SetupLauncher}.
 *   <li>Nothing on this screen is disabled because a previous step failed. A failed check is
 *       reported and moved past.
 *   <li>Re-running cannot wipe a working configuration: every field is pre-filled from
 *       {@code GatewayConfig}, a blank field never overwrites a stored value, and Skip on the
 *       account step writes nothing at all - {@code SetupViewModel.saveSipAccount}.
 *   <li>The first-run flag is written on any dismissal the operator chose, so the wizard does
 *       not reappear on every launch of a handset that skipped it.
 * </ul>
 *
 * <h2>The shape</h2>
 *
 * <p>One activity, one layout, five bodies, one visible at a time. Not fragments: five
 * fragments would buy back-stack behaviour this screen does not want (its Back is a step
 * cursor, not a navigation stack) at the cost of five lifecycles and a saved-state contract.
 * Toggling visibility also means <b>a step that has been visited keeps what was typed into
 * it</b> while the operator moves back and forth, because the views are never detached.
 *
 * <p>View state - which step, what each step's check said - lives in {@code SetupViewModel},
 * as GW-41 established for the main screen. This class finds views, renders snapshots and
 * forwards gestures.
 *
 * <h2>What it reads</h2>
 *
 * <p>{@link GatewayStatus}, through the ViewModel, and never a manager. Nothing here reads
 * {@code /proc/asound}: on the bench Redmi Note 9, reading a PCM {@code status} node during a
 * call kernel-panics the phone, and the verification step is precisely where someone would
 * reach for one.
 */
public class SetupActivity extends AppCompatActivity {

    private SetupViewModel viewModel;

    /** The same guard the main screen uses, for the same hazard (GW-41 H-b). */
    private final FormGuard formGuard = new FormGuard();

    private final Map<SetupStep, View> bodies = new EnumMap<>(SetupStep.class);

    // Header and footer
    private TextView stepIndicator;
    private TextView stepTitle;
    private TextView stepSummary;
    private Button backButton;
    private Button skipButton;
    private Button nextButton;

    // Step 1 - root
    private SetupChip rootChip;
    private TextView rootDetail;

    // Step 2 - permissions
    private SetupChip permissionsChip;
    private TextView permissionsDetail;

    // Step 3 - dialer role
    private SetupChip dialerChip;
    private TextView dialerDetail;

    // Step 4 - SIP account
    private TextView sipExistingBanner;
    private EditText sipServer;
    private EditText sipPort;
    private EditText sipUser;
    private EditText sipPassword;
    private EditText sipRealm;
    private CheckBox sipUseTls;
    private EditText sim1Destination;
    private EditText sim2Destination;

    // Step 5 - verification
    private SetupChip registrationChip;
    private TextView registrationDetail;
    private EditText testDestination;
    private TextView testDestinationWarning;
    private SetupChip testCallChip;
    private TextView testCallDetail;
    private TextView testReport;

    /**
     * The last outcome this screen pushed into the step machine, per step.
     *
     * <p>The verification step re-renders once a second, and re-recording an unchanged outcome
     * would republish the wizard snapshot at 1 Hz for nothing. This is the "has it actually
     * changed" memory, not a second copy of the state - the machine remains the owner.
     */
    private final Map<SetupStep, StepOutcome> pushed = new EnumMap<>(SetupStep.class);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        viewModel = new ViewModelProvider(this).get(SetupViewModel.class);

        findViews();
        seedVerificationDestination();
        setupGuards();
        setupClickHandlers();
        setupObservers();

        // Nothing privileged runs on its own here. Every root command on this screen is behind
        // a button, because the wizard is also what someone opens to look at a phone that is
        // misbehaving, and a screen that shells out to su the moment it opens is not that.
        viewModel.refreshPermissions();
    }

    @Override
    protected void onStart() {
        super.onStart();
        viewModel.bindToService();
        viewModel.startPolling();
    }

    @Override
    protected void onStop() {
        super.onStop();
        viewModel.stopPolling();
        viewModel.unbindFromService();

        // The first-run flag, written on any dismissal the operator chose - Finish, Close,
        // system back, a swipe from recents - and on none they did not. isFinishing() is what
        // separates those from a rotation, which must not count as having seen the wizard.
        if (isFinishing()) {
            viewModel.markSetupSeen();
        }
    }

    // ========== View setup ==========

    private void findViews() {
        stepIndicator = findViewById(R.id.setupStepIndicator);
        stepTitle = findViewById(R.id.setupStepTitle);
        stepSummary = findViewById(R.id.setupStepSummary);
        backButton = findViewById(R.id.setupBackButton);
        skipButton = findViewById(R.id.setupSkipButton);
        nextButton = findViewById(R.id.setupNextButton);

        for (SetupStep step : SetupStep.values()) {
            // Explicitly typed rather than inlined: findViewById's inferred return type inside
            // a Map.put argument is what lint's FindViewByIdCast is about.
            View body = findViewById(step.bodyViewId());
            bodies.put(step, body);
        }

        rootChip = new SetupChip(findViewById(R.id.setupRootChip));
        rootDetail = findViewById(R.id.setupRootDetail);

        permissionsChip = new SetupChip(findViewById(R.id.setupPermissionsChip));
        permissionsDetail = findViewById(R.id.setupPermissionsDetail);

        dialerChip = new SetupChip(findViewById(R.id.setupDialerChip));
        dialerDetail = findViewById(R.id.setupDialerDetail);

        sipExistingBanner = findViewById(R.id.setupSipExistingBanner);
        sipServer = findViewById(R.id.setupSipServer);
        sipPort = findViewById(R.id.setupSipPort);
        sipUser = findViewById(R.id.setupSipUser);
        sipPassword = findViewById(R.id.setupSipPassword);
        sipRealm = findViewById(R.id.setupSipRealm);
        sipUseTls = findViewById(R.id.setupSipUseTls);
        sim1Destination = findViewById(R.id.setupSim1Destination);
        sim2Destination = findViewById(R.id.setupSim2Destination);

        registrationChip = new SetupChip(findViewById(R.id.setupRegistrationChip));
        registrationDetail = findViewById(R.id.setupRegistrationDetail);
        testDestination = findViewById(R.id.setupTestDestination);
        testDestinationWarning = findViewById(R.id.setupTestDestinationWarning);
        testCallChip = new SetupChip(findViewById(R.id.setupTestCallChip));
        testCallDetail = findViewById(R.id.setupTestCallDetail);
        testReport = findViewById(R.id.setupTestReport);
    }

    /**
     * Put a routable destination in the box before anything is watching it.
     *
     * <p>Seeded from the ViewModel, which holds it across a configuration change, and set
     * <em>before</em> {@link #setupGuards()} so the write does not mark the field dirty.
     */
    private void seedVerificationDestination() {
        testDestination.setText(viewModel.getVerificationDestination());
        updateDestinationWarning(viewModel.getVerificationDestination());
    }

    private void setupGuards() {
        formGuard.watch(sipServer);
        formGuard.watch(sipPort);
        formGuard.watch(sipUser);
        formGuard.watch(sipPassword);
        formGuard.watch(sipRealm);
        formGuard.watch(sim1Destination);
        formGuard.watch(sim2Destination);
        // TLS waits for a save like the rest of the account, so it stays dirty until one.
        formGuard.watch(sipUseTls, null);

        testDestination.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String value = s.toString();
                viewModel.setVerificationDestination(value);
                updateDestinationWarning(value);
            }
        });
    }

    private void setupClickHandlers() {
        findViewById(R.id.setupCloseButton).setOnClickListener(v -> finish());

        backButton.setOnClickListener(v -> viewModel.back());
        skipButton.setOnClickListener(v -> {
            if (!viewModel.skip()) {
                finish();
            }
        });
        nextButton.setOnClickListener(v -> onNext());

        findViewById(R.id.setupRootCheckButton).setOnClickListener(v -> viewModel.checkRoot());

        findViewById(R.id.setupGrantButton).setOnClickListener(v -> viewModel.grantPermissions());
        findViewById(R.id.setupRefreshPermissionsButton)
                .setOnClickListener(v -> viewModel.refreshPermissions());
        findViewById(R.id.setupBatteryOptButton)
                .setOnClickListener(v -> viewModel.disableBatteryOptimization());

        findViewById(R.id.setupClaimDialerButton)
                .setOnClickListener(v -> viewModel.claimDialerRole());

        findViewById(R.id.setupSaveAccountButton).setOnClickListener(v -> saveSipAccount());

        findViewById(R.id.setupStartGatewayButton)
                .setOnClickListener(v -> viewModel.startGateway());
        findViewById(R.id.setupReRegisterButton).setOnClickListener(v -> viewModel.reRegister());
        findViewById(R.id.setupTestCallButton).setOnClickListener(v ->
                viewModel.startVerificationCall(testDestination.getText().toString()));
        findViewById(R.id.setupTestHangupButton)
                .setOnClickListener(v -> viewModel.stopVerificationCall());
    }

    private void setupObservers() {
        viewModel.getWizard().observe(this, this::renderWizard);

        viewModel.getRootCheck().observe(this, this::renderRoot);

        viewModel.getPermissionState().observe(this, this::renderPermissionsAndDialer);

        viewModel.getSipAccount().observe(this, this::renderSipAccount);

        viewModel.getGatewayStatus().observe(this, status -> renderVerification());
        viewModel.getServiceConnected().observe(this, connected -> renderVerification());
        viewModel.getTestCallVerdict().observe(this, verdict -> renderVerification());

        viewModel.getTestReport().observe(this, report -> {
            if (report != null && !report.isEmpty()) {
                testReport.setText(report);
            }
        });

        viewModel.getMessage().observe(this, event -> {
            if (event == null) {
                return;
            }
            String text = event.getContentIfNotHandled();
            if (text != null && !text.isEmpty()) {
                Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ========== Navigation ==========

    /**
     * Next.
     *
     * <p>On the account step this commits the form, because that is what Next means on a form
     * and stranding someone's typing behind an unpressed Save button is its own kind of
     * half-provisioned phone. The commit is a merge, never a replacement - see
     * {@code SetupViewModel.saveSipAccount}. <b>Skip is the path that writes nothing</b>, and
     * it is available on this step like every other.
     */
    private void onNext() {
        SetupStepMachine.Snapshot snapshot = viewModel.getWizard().getValue();
        if (snapshot != null && snapshot.step() == SetupStep.SIP_ACCOUNT && isAccountFormDirty()) {
            saveSipAccount();
        }
        if (!viewModel.next()) {
            finish();
        }
    }

    private boolean isAccountFormDirty() {
        return formGuard.isDirty(sipServer)
                || formGuard.isDirty(sipPort)
                || formGuard.isDirty(sipUser)
                || formGuard.isDirty(sipPassword)
                || formGuard.isDirty(sipRealm)
                || formGuard.isDirty(sipUseTls)
                || formGuard.isDirty(sim1Destination)
                || formGuard.isDirty(sim2Destination);
    }

    private void saveSipAccount() {
        String server = sipServer.getText().toString();
        String port = sipPort.getText().toString();
        String user = sipUser.getText().toString();
        String password = sipPassword.getText().toString();
        String realm = sipRealm.getText().toString();
        boolean useTls = sipUseTls.isChecked();
        String sim1 = sim1Destination.getText().toString();
        String sim2 = sim2Destination.getText().toString();

        // The guard is released BEFORE the write, not after. Saving republishes the account,
        // and the form should come back showing what was actually persisted - which is not
        // always what is in the boxes, because a blank box means "keep the stored value". The
        // password is the case that matters: cleared, it saves the old password, and the box
        // has to come back holding it rather than staying blank and implying it was wiped.
        formGuard.clean(sipServer, sipPort, sipUser, sipPassword, sipRealm, sipUseTls,
                sim1Destination, sim2Destination);

        viewModel.saveSipAccount(server, port, user, password, realm, useTls, sim1, sim2);

        pushOutcome(SetupStep.SIP_ACCOUNT,
                viewModel.hasStoredSipAccount() ? StepOutcome.PASSED : StepOutcome.FAILED);
    }

    // ========== Rendering ==========

    private void renderWizard(SetupStepMachine.Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }

        stepIndicator.setText(getString(R.string.setup_step_indicator,
                snapshot.number(), snapshot.total()));
        stepTitle.setText(snapshot.step().titleRes());
        stepSummary.setText(snapshot.step().summaryRes());

        for (Map.Entry<SetupStep, View> entry : bodies.entrySet()) {
            View body = entry.getValue();
            if (body != null) {
                body.setVisibility(entry.getKey() == snapshot.step() ? View.VISIBLE : View.GONE);
            }
        }

        // Back is the only control on this screen that is ever disabled, and only because
        // there is nothing behind step 1. Skip and Next are never disabled by anything.
        backButton.setEnabled(!snapshot.isFirst());
        nextButton.setText(snapshot.isLast()
                ? R.string.setup_action_finish
                : R.string.setup_action_next);
    }

    private void renderRoot(SetupViewModel.RootCheck check) {
        if (check == null) {
            return;
        }
        rootChip.set(check.outcome(), getString(chipLabel(check.outcome(),
                R.string.setup_chip_root_ok, R.string.setup_chip_root_missing)));
        rootDetail.setText(check.detail().isEmpty()
                ? getString(R.string.setup_root_not_checked)
                : check.detail());
    }

    private void renderPermissionsAndDialer(PermissionManager.PermissionState state) {
        if (state == null) {
            return;
        }

        boolean granted = state.allGranted() && !state.permissions.isEmpty();
        StepOutcome permissions = state.permissions.isEmpty()
                ? StepOutcome.PENDING
                : (granted ? StepOutcome.PASSED : StepOutcome.FAILED);
        permissionsChip.set(permissions, getString(chipLabel(permissions,
                R.string.setup_chip_permissions_ok, R.string.setup_chip_permissions_missing)));
        permissionsDetail.setText(state.toDisplayString());
        pushOutcome(SetupStep.PERMISSIONS, permissions);

        StepOutcome dialer = state.permissions.isEmpty()
                ? StepOutcome.PENDING
                : (state.isDefaultDialer ? StepOutcome.PASSED : StepOutcome.FAILED);
        dialerChip.set(dialer, getString(chipLabel(dialer,
                R.string.setup_chip_dialer_ok, R.string.setup_chip_dialer_missing)));
        dialerDetail.setText(state.isDefaultDialer
                ? R.string.setup_dialer_held
                : R.string.setup_dialer_not_held);
        pushOutcome(SetupStep.DIALER, dialer);
    }

    private void renderSipAccount(MainViewModel.SipConfig account) {
        if (account == null) {
            return;
        }

        formGuard.bind(sipServer, account.server);
        formGuard.bind(sipPort, String.valueOf(account.port));
        formGuard.bind(sipUser, account.user);
        formGuard.bind(sipPassword, account.password);
        formGuard.bind(sipRealm, account.realm);
        formGuard.bind(sipUseTls, account.useTls);
        formGuard.bind(sim1Destination, account.sim1Destination);
        formGuard.bind(sim2Destination, account.sim2Destination);

        // Re-running on a configured gateway has to be obvious, not a surprise discovered
        // afterwards. The banner names the account that is already there; nothing replaces it
        // unless the operator presses Save or Next with something typed.
        boolean configured = viewModel.hasStoredSipAccount();
        sipExistingBanner.setVisibility(configured ? View.VISIBLE : View.GONE);
        if (configured) {
            sipExistingBanner.setText(viewModel.storedSipAccountSummary());
        }
    }

    /**
     * The verification step, drawn from the published snapshot at draw time.
     *
     * <p>Two claims, kept apart on purpose:
     *
     * <ul>
     *   <li><b>Registration</b> - a real check. The control thread publishes it and the PBX is
     *       the thing that decided it.
     *   <li><b>The test call</b> - evidence, not proof, and never a claim about audio. The
     *       transcript is shown in full beside the verdict so the operator reads what actually
     *       happened rather than trusting a chip.
     * </ul>
     */
    private void renderVerification() {
        GatewayStatus status = viewModel.getGatewayStatus().getValue();
        if (status == null) {
            status = GatewayStatus.UNAVAILABLE;
        }
        boolean connected = Boolean.TRUE.equals(viewModel.getServiceConnected().getValue());

        StepOutcome registration;
        if (!connected || !status.isRunning()) {
            registration = StepOutcome.PENDING;
            registrationDetail.setText(R.string.setup_registration_not_running);
        } else {
            registration = status.isSipRegistered() ? StepOutcome.PASSED : StepOutcome.FAILED;
            registrationDetail.setText(status.getSipStatus());
        }
        registrationChip.set(registration, getString(chipLabel(registration,
                R.string.setup_chip_registered, R.string.setup_chip_not_registered)));

        SetupViewModel.TestCallVerdict verdict = viewModel.getTestCallVerdict().getValue();
        if (verdict == null) {
            verdict = SetupViewModel.TestCallVerdict.NOT_RUN;
        }
        testCallChip.set(outcomeOf(verdict), getString(labelOf(verdict)));
        testCallDetail.setText(detailOf(verdict));

        // The step's own outcome follows registration, which is the half that can be checked.
        // A test call that did not connect never marks the step failed on its own: the PBX may
        // simply not route the destination that was tried, and that is not the gateway being
        // wrong.
        pushOutcome(SetupStep.VERIFY, registration);
    }

    private void updateDestinationWarning(String destination) {
        boolean featureCode = SetupViewModel.isFeatureCode(destination);
        testDestinationWarning.setVisibility(featureCode ? View.VISIBLE : View.GONE);
    }

    // ========== Small helpers ==========

    /**
     * Push an outcome into the machine only when it has actually changed.
     *
     * <p>The verification step renders once a second; without this the machine would be
     * rewritten and republished 60 times a minute to say the same thing.
     */
    private void pushOutcome(SetupStep step, StepOutcome outcome) {
        if (pushed.get(step) == outcome) {
            return;
        }
        pushed.put(step, outcome);
        viewModel.record(step, outcome);
    }

    private static int chipLabel(StepOutcome outcome, int okRes, int notOkRes) {
        switch (outcome) {
            case PASSED:
                return okRes;
            case FAILED:
                return notOkRes;
            case SKIPPED:
                return R.string.setup_chip_skipped;
            case PENDING:
            default:
                return R.string.setup_chip_unknown;
        }
    }

    private static StepOutcome outcomeOf(SetupViewModel.TestCallVerdict verdict) {
        switch (verdict) {
            case ANSWERED:
                return StepOutcome.PASSED;
            case NOT_ANSWERED:
            case FAILED:
                return StepOutcome.FAILED;
            case DIALING:
            case NOT_RUN:
            default:
                return StepOutcome.PENDING;
        }
    }

    private static int labelOf(SetupViewModel.TestCallVerdict verdict) {
        switch (verdict) {
            case ANSWERED:
                return R.string.setup_chip_call_answered;
            case NOT_ANSWERED:
                return R.string.setup_chip_call_unanswered;
            case FAILED:
                return R.string.setup_chip_call_failed;
            case DIALING:
                return R.string.setup_chip_call_dialing;
            case NOT_RUN:
            default:
                return R.string.setup_chip_unknown;
        }
    }

    private static int detailOf(SetupViewModel.TestCallVerdict verdict) {
        switch (verdict) {
            case ANSWERED:
                return R.string.setup_test_call_answered;
            case NOT_ANSWERED:
                return R.string.setup_test_call_unanswered;
            case FAILED:
                return R.string.setup_test_call_failed;
            case DIALING:
                return R.string.setup_test_call_dialing;
            case NOT_RUN:
            default:
                return R.string.setup_test_call_not_run;
        }
    }
}
