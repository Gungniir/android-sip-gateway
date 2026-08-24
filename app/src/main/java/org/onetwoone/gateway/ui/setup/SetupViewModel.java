package org.onetwoone.gateway.ui.setup;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.onetwoone.gateway.PjsipSipService;
import org.onetwoone.gateway.R;
import org.onetwoone.gateway.RootHelper;
import org.onetwoone.gateway.config.GatewayConfig;
import org.onetwoone.gateway.core.GatewayStatus;
import org.onetwoone.gateway.ui.Event;
import org.onetwoone.gateway.ui.MainViewModel;
import org.onetwoone.gateway.ui.PermissionManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Everything the commissioning wizard knows (GW-42).
 *
 * <h2>The step machine</h2>
 *
 * <p>{@link SetupStepMachine} is the cursor and the scoreboard; this class owns one and
 * publishes its {@link SetupStepMachine.Snapshot} so the view renders an immutable value
 * rather than reaching into a mutable object - the GW-45 pattern, for the same reason.
 *
 * <h2>The verification step, and the thing it must not do</h2>
 *
 * <p>The obvious design for step 5 is "dial {@code *43}, the FreePBX echo test, and listen".
 * <b>That does not work on this deployment.</b> The gateway's SIP trunk cannot dial feature
 * codes: the in-app {@code *43} test call is rejected by the PBX's {@code from-gsm-gateway}
 * context, which does not route them. A verification step hardcoded to {@code *43} therefore
 * fails on a <em>correctly configured</em> gateway, and telling someone who did everything
 * right that they failed is the worst outcome a commissioning wizard has.
 *
 * <p>So the destination is configurable and its default is never a feature code - see
 * {@link #defaultVerificationDestination()}. {@link #isFeatureCode(String)} is what the view
 * warns on.
 *
 * <h3>What this step can honestly assert, and what it cannot</h3>
 *
 * <ul>
 *   <li><b>Registration - yes.</b> {@link GatewayStatus#isSipRegistered()} is published by the
 *       control thread and is a real, checkable milestone: the PBX accepted this account. It
 *       is asserted on its own and reported on its own.
 *   <li><b>That an INVITE was answered - yes, as evidence.</b> The test call's transcript says
 *       whether the call reached {@code CONFIRMED}, was disconnected without ever confirming,
 *       or failed before it left. {@link #verdictOf(String)} reads exactly that and nothing
 *       more; the whole transcript is shown beside it so the operator judges rather than
 *       trusts a green tick.
 *   <li><b>That the GSM&#8596;SIP audio bridge works - no.</b> Not from the handset, not by
 *       this wizard, not at all. The bridge is a GSM leg, an ALSA tap and a conference port,
 *       and the only instrument that can confirm it is a human on a real call. The step says
 *       so in those words rather than letting a registered chip imply it.
 * </ul>
 *
 * <p>Nothing here reads {@code /proc/asound}. On the Redmi Note 9 bench device, reading a PCM
 * {@code status} node during a call kernel-panics the phone, and a verification step is
 * exactly where someone would reach for it. Registration and the call transcript come from the
 * published snapshot and from {@code SipTestCallManager}; no audio state is inspected at all.
 *
 * <h2>Why it binds to the service itself instead of borrowing MainViewModel</h2>
 *
 * <p>{@code MainViewModel} would have brought a {@code TinymixManager}, an
 * {@code AudioDeviceManager}, a second copy of every config LiveData on the main screen, and a
 * constructor that kicks off a root mixer scan when the mute preset is {@code custom}. The
 * wizard needs a snapshot, a reload and a test call. It binds for those three and reuses
 * {@code MainViewModel.SipConfig} as the shape of a SIP account, because a parallel type for
 * the same eight fields would be the thing that drifts.
 *
 * <h2>Threading</h2>
 *
 * <p>One single-thread executor for root, exactly as {@code PermissionManager} does it since
 * GW-20, and every privileged command goes through {@link RootHelper}, which bounds the wait
 * and drains both pipes. No root call runs on the main thread and there is no bare
 * {@code Runtime.exec} anywhere in this package.
 */
public class SetupViewModel extends AndroidViewModel {

    private static final String TAG = "SetupVM";

    /** The verification call's mode. See {@link #startVerificationCall(String)}. */
    private static final String VERIFICATION_MODE = "tone";

    /** Long enough to hear something, short enough not to strand a bench call. */
    private static final int VERIFICATION_DURATION_SEC = 15;

    private static final int STATUS_POLL_MS = 1000;

    // ========== The wizard itself ==========

    private final SetupStepMachine machine = new SetupStepMachine();
    private final MutableLiveData<SetupStepMachine.Snapshot> wizard = new MutableLiveData<>();

    // ========== Step 1 - root ==========

    private final MutableLiveData<RootCheck> rootCheck =
            new MutableLiveData<>(RootCheck.notChecked());

    // ========== Steps 2 and 3 - permissions and the dialer role ==========

    private final PermissionManager permissionManager;

    // ========== Step 4 - the SIP account ==========

    private final MutableLiveData<MainViewModel.SipConfig> sipAccount = new MutableLiveData<>();

    // ========== Step 5 - verification ==========

    private final MutableLiveData<GatewayStatus> gatewayStatus =
            new MutableLiveData<>(GatewayStatus.UNAVAILABLE);
    private final MutableLiveData<Boolean> serviceConnected = new MutableLiveData<>(false);
    private final MutableLiveData<String> testReport = new MutableLiveData<>("");
    private final MutableLiveData<TestCallVerdict> testCallVerdict =
            new MutableLiveData<>(TestCallVerdict.NOT_RUN);

    /** One-shot messages for the view to toast. {@link Event}, for GW-41 hazard H-c's reason. */
    private final MutableLiveData<Event<String>> message = new MutableLiveData<>();

    /** @see #getVerificationDestination() */
    private String verificationDestination;

    private final ExecutorService rootExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * The bound service, or null.
     *
     * <p>Lint reads a {@code Service} field on a {@code ViewModel} as a leaked context, and it
     * is right to ask. It is not one here for the same reason {@code MainViewModel}'s identical
     * field is not - which lint accepted only because that one predates the baseline: the
     * reference is a <em>binding</em>, it is dropped in {@link #unbindFromService()}, and
     * {@link #onCleared()} calls that. The suppression is per-field and carries its reason,
     * rather than a new entry in a baseline this phase is not allowed to regenerate.
     */
    @SuppressLint("StaticFieldLeak")
    private PjsipSipService service;

    private boolean serviceBound;
    private boolean polling;

    private final Runnable statusPoller = new Runnable() {
        @Override
        public void run() {
            pollService();
            if (polling) {
                mainHandler.postDelayed(this, STATUS_POLL_MS);
            }
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!(binder instanceof PjsipSipService.LocalBinder)) {
                Log.w(TAG, "Ignoring a binding that is not PjsipSipService.LocalBinder");
                return;
            }
            service = ((PjsipSipService.LocalBinder) binder).getService();
            serviceBound = true;
            pollService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            serviceBound = false;
            pollService();
        }
    };

    public SetupViewModel(Application application) {
        super(application);
        GatewayConfig.init(application);
        permissionManager = new PermissionManager(application);
        publishWizard();
        reloadSipAccount();
    }

    // ========== The step machine ==========

    public LiveData<SetupStepMachine.Snapshot> getWizard() {
        return wizard;
    }

    /**
     * Advance without judging the step just left.
     *
     * @return false when there is no next step, i.e. the wizard is over
     */
    public boolean next() {
        boolean moved = machine.next();
        publishWizard();
        return moved;
    }

    /** @return false when already on the first step */
    public boolean back() {
        boolean moved = machine.back();
        publishWizard();
        return moved;
    }

    /**
     * Skip the step on screen.
     *
     * <p>Works on every step, including the SIP account, where it is the guarantee that
     * matters most: a skip writes nothing at all, so re-running the wizard on a configured
     * gateway and skipping past step 4 cannot disturb a working account.
     *
     * @return false when there is no next step, i.e. the wizard is over
     */
    public boolean skip() {
        boolean moved = machine.skip();
        publishWizard();
        return moved;
    }

    public void recordCurrent(StepOutcome outcome) {
        machine.recordCurrent(outcome);
        publishWizard();
    }

    public void record(SetupStep step, StepOutcome outcome) {
        machine.record(step, outcome);
        publishWizard();
    }

    public void goTo(SetupStep step) {
        machine.goTo(step);
        publishWizard();
    }

    private void publishWizard() {
        wizard.setValue(machine.snapshot());
    }

    // ========== First-run flag ==========

    /**
     * Remember that the wizard has been dismissed, however it was dismissed.
     *
     * <p>Idempotent, and called from {@code SetupActivity.onStop()} when the activity is
     * finishing - which covers the Finish button, the Close button, system back and a swipe
     * away, and does <em>not</em> cover a rotation. See {@link SetupLauncher} for why skipping
     * counts as done.
     */
    public void markSetupSeen() {
        GatewayConfig.getInstance().setSetupCompleted(true);
    }

    // ========== Step 1 - root ==========

    public LiveData<RootCheck> getRootCheck() {
        return rootCheck;
    }

    /**
     * Ask {@code su} who we are.
     *
     * <p>Deliberately <b>not</b> {@code RootHelper.checkRoot()}, which caches its answer for
     * the life of the process. On the phone this wizard is for, the likely sequence is
     * "wizard says no root, operator grants the app root in Magisk, operator presses Check
     * again" - and a cached no would report failure forever. This asks every time.
     */
    public void checkRoot() {
        rootCheck.setValue(RootCheck.checking());
        rootExecutor.execute(() -> {
            RootHelper.RootResult result = RootHelper.run("id");
            boolean rooted = result.success() && result.stdout().contains("uid=0");
            String detail = rooted
                    ? result.stdout()
                    : getApplication().getString(R.string.setup_root_failed_detail,
                            result.exitCode(),
                            result.stderr().isEmpty()
                                    ? getApplication().getString(R.string.setup_no_output)
                                    : result.stderr());
            mainHandler.post(() -> {
                rootCheck.setValue(new RootCheck(
                        rooted ? StepOutcome.PASSED : StepOutcome.FAILED, detail));
                record(SetupStep.ROOT, rooted ? StepOutcome.PASSED : StepOutcome.FAILED);
            });
        });
    }

    // ========== Steps 2 and 3 - permissions and the dialer role ==========

    public LiveData<PermissionManager.PermissionState> getPermissionState() {
        return permissionManager.getPermissionState();
    }

    /** Grant the six runtime permissions via root, then re-read what actually stuck. */
    public void grantPermissions() {
        permissionManager.grantAllPermissionsAsync();
    }

    public void refreshPermissions() {
        permissionManager.refreshPermissionStatus();
    }

    /**
     * Whitelist the app from doze.
     *
     * <p>Its own button rather than part of the grant, because
     * {@code PermissionManager.disableBatteryOptimizationAsync} carries a warning that it can
     * freeze some devices. A step that might wedge the handset it is commissioning has to be
     * something the operator chose, not something the wizard did on the way past.
     */
    public void disableBatteryOptimization() {
        permissionManager.disableBatteryOptimizationAsync();
        message.setValue(new Event<>(
                getApplication().getString(R.string.setup_toast_battery_opt_requested)));
    }

    /** Claim the dialer role via root, then re-read whether it was actually granted. */
    public void claimDialerRole() {
        permissionManager.setDefaultDialerAsync();
    }

    // ========== Step 4 - the SIP account ==========

    public LiveData<MainViewModel.SipConfig> getSipAccount() {
        return sipAccount;
    }

    /** Re-read the persisted account, so the form pre-fills from what is actually stored. */
    public void reloadSipAccount() {
        GatewayConfig config = GatewayConfig.getInstance();
        MainViewModel.SipConfig account = new MainViewModel.SipConfig();
        account.server = config.getSipServer();
        account.port = config.getSipPort();
        account.user = config.getSipUser();
        account.password = config.getSipPassword();
        account.realm = config.getSipRealm();
        account.useTls = config.isUseTls();
        account.sim1Destination = config.getSim1Destination();
        account.sim2Destination = config.getSim2Destination();
        account.incomingCallMode = config.getIncomingCallMode();
        sipAccount.setValue(account);
    }

    /** Whether a SIP account is already stored, for the "you are about to replace this" note. */
    public boolean hasStoredSipAccount() {
        return GatewayConfig.getInstance().isSipConfigured();
    }

    /** {@code user@server}, for that note. Never a password. */
    public String storedSipAccountSummary() {
        GatewayConfig config = GatewayConfig.getInstance();
        return getApplication().getString(R.string.setup_sip_existing_account,
                config.getSipUser(), config.getSipServer());
    }

    /**
     * Persist the SIP account, <b>merging rather than replacing</b>.
     *
     * <h2>The rule</h2>
     *
     * <p><em>A blank field never overwrites a stored value.</em> Every field is pre-filled from
     * {@code GatewayConfig}, so a field is only blank if the operator emptied it or if nothing
     * was stored - and of those two, silently wiping a working account is far the worse
     * outcome. Clearing a value is the main screen's job, where a Save means exactly what it
     * says; the wizard's job is not to break a gateway someone re-ran it to fix.
     *
     * <p>The port follows the same rule via a parse failure: an unparseable or empty port keeps
     * the stored one instead of resetting to 5060. This is the crash the main screen used to
     * have on an empty port box, in merge form.
     *
     * <p>{@code useTls} has no blank, so it is written as given - it is pre-filled from config
     * and only differs if someone moved it.
     *
     * <p>Writing at all is deliberate: this is called by the SIP step's explicit Save, and by
     * Next, which on a form means "keep what I typed". <b>Skip does not call it.</b>
     */
    public void saveSipAccount(String server, String portText, String user, String password,
                               String realm, boolean useTls, String sim1, String sim2) {
        GatewayConfig config = GatewayConfig.getInstance();

        String mergedServer = keepStoredIfBlank(server, config.getSipServer());
        String mergedUser = keepStoredIfBlank(user, config.getSipUser());
        String mergedPassword = keepStoredIfBlank(password, config.getSipPassword());
        String mergedRealm = keepStoredIfBlank(realm, config.getSipRealm());
        String mergedSim1 = keepStoredIfBlank(sim1, config.getSim1Destination());
        String mergedSim2 = keepStoredIfBlank(sim2, config.getSim2Destination());
        int mergedPort = parsePort(portText, config.getSipPort());

        config.updateSipConfig(mergedServer, mergedPort, mergedUser, mergedPassword,
                mergedRealm, useTls);
        config.updateSimDestinations(mergedSim1, mergedSim2);

        reloadSipAccount();

        // Two jobs in one call. The account has to reach the running endpoint or step 5 would
        // verify the previous credentials; and reloadConfig() bumps the snapshot's config
        // generation, which is how the main screen's own poll notices that the wizard changed
        // something underneath it (GW-14). Without it the console behind this activity would
        // still be showing the old account when the wizard closed.
        PjsipSipService bound = service;
        if (bound != null) {
            bound.reloadConfig();
        }

        message.setValue(new Event<>(
                getApplication().getString(R.string.setup_toast_account_saved)));
        Log.d(TAG, "SIP account saved from the wizard: " + mergedUser + "@" + mergedServer);
    }

    /** @return {@code entered}, trimmed, unless it is blank - in which case {@code stored}. */
    static String keepStoredIfBlank(String entered, String stored) {
        if (entered == null) {
            return stored;
        }
        String trimmed = entered.trim();
        return trimmed.isEmpty() ? stored : trimmed;
    }

    static int parsePort(String portText, int stored) {
        try {
            int parsed = Integer.parseInt(portText == null ? "" : portText.trim());
            return parsed > 0 && parsed <= 65535 ? parsed : stored;
        } catch (NumberFormatException ignored) {
            return stored;
        }
    }

    // ========== Step 5 - verification ==========

    public LiveData<GatewayStatus> getGatewayStatus() {
        return gatewayStatus;
    }

    public LiveData<Boolean> getServiceConnected() {
        return serviceConnected;
    }

    public LiveData<String> getTestReport() {
        return testReport;
    }

    public LiveData<TestCallVerdict> getTestCallVerdict() {
        return testCallVerdict;
    }

    public LiveData<Event<String>> getMessage() {
        return message;
    }

    /**
     * A destination the trunk can actually reach, as the field-tested default.
     *
     * <p>In order:
     *
     * <ol>
     *   <li>the stored test destination, <b>unless it is a feature code</b> - which the
     *       shipped default {@code *43} is, so a fresh handset never lands here;
     *   <li>the SIM 1 destination, then the SIM 2 destination. These are the strongest
     *       candidates available: they are the extensions this gateway forwards inbound GSM
     *       calls to, so by construction they are extensions the PBX's inbound context routes.
     *       That is the exact property {@code *43} lacks;
     *   <li>nothing, and the step asks for an extension rather than guessing.
     * </ol>
     */
    public String defaultVerificationDestination() {
        GatewayConfig config = GatewayConfig.getInstance();

        String stored = config.getTestDestination();
        if (isUsableDestination(stored)) {
            return stored.trim();
        }
        String sim1 = config.getSim1Destination();
        if (isUsableDestination(sim1)) {
            return sim1.trim();
        }
        String sim2 = config.getSim2Destination();
        if (isUsableDestination(sim2)) {
            return sim2.trim();
        }
        return "";
    }

    /**
     * The destination in the verification step's box.
     *
     * <p>Held here rather than only in the {@code EditText} so a rotation - or a night-mode
     * switch, which GW-40 made reachable - does not throw away what was typed and re-seed the
     * default over it. Lazily initialised, so the default is computed against the config as it
     * stands when the step is first drawn.
     */
    public String getVerificationDestination() {
        if (verificationDestination == null) {
            verificationDestination = defaultVerificationDestination();
        }
        return verificationDestination;
    }

    public void setVerificationDestination(String destination) {
        verificationDestination = destination == null ? "" : destination;
    }

    private static boolean isUsableDestination(String value) {
        return value != null && !value.trim().isEmpty() && !isFeatureCode(value);
    }

    /**
     * Whether {@code destination} is a PBX feature code.
     *
     * <p>The gateway's trunk cannot dial one: the in-app {@code *43} test call is rejected by
     * the PBX's {@code from-gsm-gateway} context, which routes extensions and not feature
     * codes. This is a warning in the view, never a block - the deployment on the other end of
     * a given handset is not ours to assume, and refusing to dial what the operator typed
     * would be the wizard overruling the person holding the phone.
     */
    public static boolean isFeatureCode(String destination) {
        if (destination == null) {
            return false;
        }
        String trimmed = destination.trim();
        return trimmed.startsWith("*") || trimmed.startsWith("#");
    }

    /** Start the gateway, so there is something to register. */
    public void startGateway() {
        Context context = getApplication();
        Intent intent = new Intent(context, PjsipSipService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
        bindToService();
        message.setValue(new Event<>(getApplication().getString(R.string.toast_connecting)));
    }

    /** Re-register with whatever is currently persisted. */
    public void reRegister() {
        PjsipSipService bound = service;
        if (bound == null) {
            message.setValue(new Event<>(
                    getApplication().getString(R.string.toast_service_not_connected)));
            return;
        }
        bound.reloadConfig();
        message.setValue(new Event<>(
                getApplication().getString(R.string.setup_toast_reregistering)));
    }

    /**
     * Place the verification call.
     *
     * <p>Mode {@code tone}, not {@code bridge}: a tone generator transmits into the call from
     * PJSIP's own conference bridge, which needs no GSM leg and no ALSA tap, so what it
     * exercises - SDP, RTP, the conference bridge, and whether the PBX routes the destination
     * at all - is exactly the part a handset can check while commissioning. {@code bridge}
     * mode needs a live GSM call in progress and would fail here for a reason that has nothing
     * to do with the gateway being wrong.
     *
     * <p>The destination is persisted, so the Diagnostics section on the main screen comes up
     * on the same one afterwards.
     */
    public void startVerificationCall(String destination) {
        PjsipSipService bound = service;
        if (bound == null) {
            message.setValue(new Event<>(
                    getApplication().getString(R.string.toast_service_not_connected)));
            return;
        }
        String dest = destination == null ? "" : destination.trim();
        if (dest.isEmpty()) {
            message.setValue(new Event<>(
                    getApplication().getString(R.string.setup_toast_need_destination)));
            return;
        }

        // The destination is persisted - the wizard has just worked out a routable one and the
        // Diagnostics section should come up on it. The MODE is deliberately not: it is passed
        // to the call directly, so writing it would overwrite an operator's chosen diagnostic
        // mode for no reason. The wizard changes what it must and nothing else.
        GatewayConfig.getInstance().setTestDestination(dest);

        testCallVerdict.setValue(TestCallVerdict.DIALING);
        bound.startTestCall(dest, VERIFICATION_MODE, VERIFICATION_DURATION_SEC);
        message.setValue(new Event<>(
                getApplication().getString(R.string.setup_toast_test_call, dest)));
    }

    public void stopVerificationCall() {
        PjsipSipService bound = service;
        if (bound != null) {
            bound.stopTestCall();
        }
    }

    /**
     * What the test-call transcript says happened - and nothing beyond it.
     *
     * <p>Pure and static so it can be tested exhaustively without a phone, a PBX or an
     * Android runtime. The markers are {@code SipTestCallManager}'s own log lines.
     *
     * <p>Order matters: an error is an error even after the call confirmed (a wiring failure
     * is reported that way), and a normal 15-second test ends {@code CONFIRMED} <em>then</em>
     * {@code DISCONNECTED} - so confirmation has to be checked before disconnection or every
     * successful call would read as unanswered.
     */
    public static TestCallVerdict verdictOf(String report) {
        if (report == null || report.trim().isEmpty()) {
            return TestCallVerdict.NOT_RUN;
        }
        if (report.contains("ERROR:")) {
            return TestCallVerdict.FAILED;
        }
        if (report.contains("call CONFIRMED")) {
            return TestCallVerdict.ANSWERED;
        }
        if (report.contains("call DISCONNECTED")) {
            return TestCallVerdict.NOT_ANSWERED;
        }
        if (report.contains("INVITE sent")) {
            return TestCallVerdict.DIALING;
        }
        return TestCallVerdict.NOT_RUN;
    }

    // ========== Service binding ==========

    public void bindToService() {
        if (serviceBound) {
            return;
        }
        Context context = getApplication();
        context.bindService(new Intent(context, PjsipSipService.class), connection, 0);
    }

    public void unbindFromService() {
        if (!serviceBound) {
            return;
        }
        try {
            getApplication().unbindService(connection);
        } catch (Exception e) {
            Log.w(TAG, "Error unbinding: " + e.getMessage());
        }
        serviceBound = false;
        service = null;
    }

    public void startPolling() {
        if (!polling) {
            polling = true;
            mainHandler.post(statusPoller);
        }
    }

    public void stopPolling() {
        polling = false;
        mainHandler.removeCallbacks(statusPoller);
    }

    /**
     * One tick: the snapshot, the binding, and the test-call transcript.
     *
     * <p>{@link GatewayStatus#UNAVAILABLE} stands in when there is no binding, which is what
     * GW-45 asks for instead of a null-state. Nothing derived from the snapshot is cached -
     * {@code getCallDurationMs()} and {@code isInGracePeriod()} re-read the clock on every
     * call by design.
     */
    private void pollService() {
        final PjsipSipService bound = service;
        final boolean connected = bound != null;

        serviceConnected.setValue(connected);
        gatewayStatus.setValue(connected ? bound.getStatusSnapshot() : GatewayStatus.UNAVAILABLE);

        if (connected) {
            String report = bound.getTestCallReport();
            if (report != null && !report.isEmpty()) {
                testReport.setValue(report);
                testCallVerdict.setValue(verdictOf(report));
            }
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        stopPolling();
        unbindFromService();
        permissionManager.shutdown();
        rootExecutor.shutdown();
    }

    // ========== Value types ==========

    /** The outcome of one {@code su} probe, with whatever {@code su} actually said. */
    public static final class RootCheck {

        private final StepOutcome outcome;
        private final String detail;

        public RootCheck(StepOutcome outcome, String detail) {
            this.outcome = outcome;
            this.detail = detail == null ? "" : detail;
        }

        static RootCheck notChecked() {
            return new RootCheck(StepOutcome.PENDING, "");
        }

        static RootCheck checking() {
            return new RootCheck(StepOutcome.PENDING, "");
        }

        public StepOutcome outcome() {
            return outcome;
        }

        /** {@code id}'s output on success, or the exit code and stderr on failure. */
        public String detail() {
            return detail;
        }
    }

    /**
     * What the verification call's transcript shows.
     *
     * <p>None of these mean "the audio bridge works". {@link #ANSWERED} means an INVITE was
     * accepted by the PBX and media negotiated - a genuine milestone, and not the same claim.
     */
    public enum TestCallVerdict {
        /** No transcript yet. */
        NOT_RUN,
        /** An INVITE has gone out and nothing has come back. */
        DIALING,
        /** The call reached CONFIRMED: the PBX routed the destination and answered. */
        ANSWERED,
        /** The call ended without ever confirming - rejected, busy, or no answer. */
        NOT_ANSWERED,
        /** The call could not be placed, or something failed while it was up. */
        FAILED
    }
}
