package org.onetwoone.gateway;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.switchmaterial.SwitchMaterial;

import org.onetwoone.gateway.config.GatewayConfig;
import org.onetwoone.gateway.core.GatewayStatus;
import org.onetwoone.gateway.ui.AudioDeviceManager;
import org.onetwoone.gateway.ui.CollapsibleSection;
import org.onetwoone.gateway.ui.FormGuard;
import org.onetwoone.gateway.ui.MainViewModel;
import org.onetwoone.gateway.ui.StatusHeaderBinder;
import org.onetwoone.gateway.ui.TinymixManager;
import org.onetwoone.gateway.ui.setup.SetupLauncher;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The gateway's main screen (GW-41).
 *
 * <h2>What this activity is</h2>
 *
 * <p>A view. It finds views, binds them to {@code MainViewModel}'s LiveData, and forwards
 * gestures back. It holds no gateway state, reads no manager, and - since this wave - reads
 * no {@code SharedPreferences} either: every configuration value arrives through the
 * ViewModel, which is the only owner of {@code GatewayConfig} on this path.
 *
 * <h2>The shape of the screen</h2>
 *
 * <p>A persistent status header over four collapsible sections. The header renders the whole
 * {@link GatewayStatus} snapshot that GW-45 published and nothing consumed - including the
 * watchdog findings, which are the reason it exists (see {@link StatusHeaderBinder}).
 *
 * <h2>The four hazards this rewrite had to clear (PHASE-4-PLAN §4)</h2>
 *
 * <ul>
 *   <li><b>H-a, hardcoded state colours.</b> {@code setTextColor(0xFF228B22 / 0xFFCC0000)} and
 *       {@code setTextColor(0xFF999999)} were the app's only state colours, as literal ints in
 *       Java where {@code res/values-night} could never reach them. They are gone; every
 *       colour on this screen now comes from the palette, through
 *       {@link StatusHeaderBinder} or a style.
 *   <li><b>H-b, unguarded rebinding.</b> See {@link FormGuard}. Every rebindable input on the
 *       screen goes through it, not just the one field that used to check {@code hasFocus()}.
 *   <li><b>H-c, toasts through LiveData.</b> {@code getToastMessage()} now carries
 *       {@code Event<String>} and is consumed once; a replay after a configuration change is
 *       a no-op instead of a message about something that did not just happen.
 *   <li><b>H-d, mirrored selection state.</b> {@code selectedCard},
 *       {@code selectedCaptureDevice}, {@code selectedPlaybackDevice},
 *       {@code selectedMixerRoute} and the {@code isRefreshing} flag are gone from this class.
 *       They live in the ViewModel, whose setters are idempotent - which is what makes them
 *       safe against {@code Spinner.setSelection()}'s asynchronous callback, something no
 *       flag in an activity could be.
 * </ul>
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "GatewayMain";

    private MainViewModel viewModel;

    /** H-b: the one thing standing between a remote config save and the operator's typing. */
    private final FormGuard formGuard = new FormGuard();

    private StatusHeaderBinder statusHeader;

    private final Map<MainViewModel.Section, CollapsibleSection> sections =
            new EnumMap<>(MainViewModel.Section.class);

    // SIP account and routing
    private EditText sipServerEdit;
    private EditText sipPortEdit;
    private EditText sipUserEdit;
    private EditText sipPasswordEdit;
    private CheckBox useTlsCheckbox;
    private EditText sipRealmEdit;
    private EditText sim1DestinationEdit;
    private EditText sim2DestinationEdit;
    private RadioGroup incomingModeRadioGroup;
    private RadioButton modeAnswerFirst;
    private RadioButton modeSipFirst;
    private Button saveButton;
    private Button connectButton;
    private Button disconnectButton;

    // Battery limit
    private RadioGroup batteryLimitRadioGroup;
    private RadioButton limit60;
    private RadioButton limit100;

    // Audio bridge
    private Spinner socProfileSpinner;
    private Spinner cardSpinner;
    private Spinner captureSpinner;
    private Spinner playbackSpinner;
    private Spinner mixerRouteSpinner;
    private EditText txGainEdit;
    private EditText rxGainEdit;
    private Button saveAudioButton;
    private Button restartButton;

    // Device mute preset
    private Spinner devicePresetSpinner;
    private LinearLayout customMuteContainer;
    private LinearLayout micMuteCheckboxContainer;
    private EditText manualMuteControlsEdit;
    private final Map<String, CheckBox> decCheckboxes = new HashMap<>();

    // Web interface
    private SwitchMaterial webInterfaceSwitch;
    private TextView webInterfaceLabel;

    // Diagnostics
    private TextView permissionsText;
    private EditText testDestinationEdit;
    private Spinner testModeSpinner;
    private Button testCallButton;
    private Button testHangupButton;
    private CheckBox verboseSipLogCheckbox;
    private CheckBox dtmfRelayCheckbox;
    private TextView testReportText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewModel = new ViewModelProvider(this).get(MainViewModel.class);

        findViews();
        setupSections();
        setupSpinnerAdapters();
        setupGuards();
        setupClickHandlers();
        setupObservers();

        // Initialize permissions via root
        viewModel.initPermissions();

        // GW-42. Opened OVER this screen, never instead of it, and only on a genuinely fresh
        // launch - savedInstanceState != null is a recreation, and re-opening the wizard on a
        // night-mode switch would be a wizard the operator cannot close. The decision itself
        // lives in SetupLauncher: this activity reads no SharedPreferences, and a first-run
        // flag read here would have been the one exception to that.
        if (savedInstanceState == null) {
            SetupLauncher.launchIfFirstRun(this);
        }

        // Start service
        viewModel.startService();
    }

    @Override
    protected void onStart() {
        super.onStart();
        viewModel.bindToService();
        viewModel.startPolling();
        viewModel.refreshAudioDevices();
    }

    @Override
    protected void onStop() {
        super.onStop();
        viewModel.stopPolling();
        viewModel.unbindFromService();
    }

    // ========== View setup ==========

    private void findViews() {
        statusHeader = new StatusHeaderBinder(findViewById(R.id.statusHeader));

        sipServerEdit = findViewById(R.id.sipServer);
        sipPortEdit = findViewById(R.id.sipPort);
        sipUserEdit = findViewById(R.id.sipUser);
        sipPasswordEdit = findViewById(R.id.sipPassword);
        useTlsCheckbox = findViewById(R.id.useTls);
        sipRealmEdit = findViewById(R.id.sipRealm);
        sim1DestinationEdit = findViewById(R.id.sim1Destination);
        sim2DestinationEdit = findViewById(R.id.sim2Destination);
        incomingModeRadioGroup = findViewById(R.id.incomingModeRadioGroup);
        modeAnswerFirst = findViewById(R.id.modeAnswerFirst);
        modeSipFirst = findViewById(R.id.modeSipFirst);
        saveButton = findViewById(R.id.saveButton);
        connectButton = findViewById(R.id.connectButton);
        disconnectButton = findViewById(R.id.disconnectButton);

        batteryLimitRadioGroup = findViewById(R.id.batteryLimitRadioGroup);
        limit60 = findViewById(R.id.limit60);
        limit100 = findViewById(R.id.limit100);

        socProfileSpinner = findViewById(R.id.socProfileSpinner);
        cardSpinner = findViewById(R.id.cardSpinner);
        captureSpinner = findViewById(R.id.captureSpinner);
        playbackSpinner = findViewById(R.id.playbackSpinner);
        mixerRouteSpinner = findViewById(R.id.mixerRouteSpinner);
        txGainEdit = findViewById(R.id.txGainEdit);
        rxGainEdit = findViewById(R.id.rxGainEdit);
        saveAudioButton = findViewById(R.id.saveAudioButton);
        restartButton = findViewById(R.id.restartButton);

        devicePresetSpinner = findViewById(R.id.devicePresetSpinner);
        customMuteContainer = findViewById(R.id.customMuteContainer);
        micMuteCheckboxContainer = findViewById(R.id.micMuteCheckboxContainer);
        manualMuteControlsEdit = findViewById(R.id.manualMuteControls);

        webInterfaceSwitch = findViewById(R.id.webInterfaceSwitch);
        webInterfaceLabel = findViewById(R.id.webInterfaceLabel);

        permissionsText = findViewById(R.id.permissionsText);
        testDestinationEdit = findViewById(R.id.testDestination);
        testModeSpinner = findViewById(R.id.testModeSpinner);
        testCallButton = findViewById(R.id.testCallButton);
        testHangupButton = findViewById(R.id.testHangupButton);
        verboseSipLogCheckbox = findViewById(R.id.verboseSipLogCheckbox);
        dtmfRelayCheckbox = findViewById(R.id.dtmfRelayCheckbox);
        testReportText = findViewById(R.id.testReportText);
    }

    private void setupSections() {
        attachSection(MainViewModel.Section.SIP, R.id.sipSectionHeader,
                R.id.sipSectionChevron, R.id.sipSectionBody);
        attachSection(MainViewModel.Section.AUDIO, R.id.audioSectionHeader,
                R.id.audioSectionChevron, R.id.audioSectionBody);
        attachSection(MainViewModel.Section.DIAGNOSTICS, R.id.diagnosticsSectionHeader,
                R.id.diagnosticsSectionChevron, R.id.diagnosticsSectionBody);
        attachSection(MainViewModel.Section.SYSTEM, R.id.systemSectionHeader,
                R.id.systemSectionChevron, R.id.systemSectionBody);
    }

    private void attachSection(MainViewModel.Section section, int headerId, int chevronId,
                               int bodyId) {
        sections.put(section, CollapsibleSection.attach(
                findViewById(headerId),
                findViewById(chevronId),
                findViewById(bodyId),
                () -> viewModel.toggleSection(section)));
    }

    /**
     * Adapters whose contents are fixed. The three device spinners are filled from a root
     * device scan instead and are populated in {@link #updateAudioSpinners}.
     */
    private void setupSpinnerAdapters() {
        socProfileSpinner.setAdapter(adapter(new String[]{
                getString(R.string.option_soc_profile_auto),
                getString(R.string.option_soc_profile_qualcomm),
                getString(R.string.option_soc_profile_mediatek)}));

        mixerRouteSpinner.setAdapter(adapter(MainViewModel.MIXER_ROUTES));
        testModeSpinner.setAdapter(adapter(MainViewModel.TEST_MODES));
        devicePresetSpinner.setAdapter(adapter(DeviceMuteManager.getPresetDescriptions()));
    }

    /**
     * H-b, and the compound-button half of it.
     *
     * <p>Every rebindable input is registered with the guard here, before any observer can
     * fire. A {@code CompoundButton} has one listener slot, so the guard owns it and forwards
     * genuine user changes on; the TLS checkbox passes {@code null} because it is only read
     * at save time.
     */
    private void setupGuards() {
        formGuard.watch(sipServerEdit);
        formGuard.watch(sipPortEdit);
        formGuard.watch(sipUserEdit);
        formGuard.watch(sipPasswordEdit);
        formGuard.watch(sipRealmEdit);
        formGuard.watch(sim1DestinationEdit);
        formGuard.watch(sim2DestinationEdit);
        formGuard.watch(txGainEdit);
        formGuard.watch(rxGainEdit);
        formGuard.watch(manualMuteControlsEdit);
        formGuard.watch(testDestinationEdit);

        // TLS is the only one of the four that waits for the Save button, so it is the only
        // one that stays dirty. The other three write through on change: the instant they
        // have, there is no unsaved work to protect, and leaving them marked would mean a
        // later change made on the web interface could never repaint them.
        formGuard.watch(useTlsCheckbox, null);
        formGuard.watch(verboseSipLogCheckbox, (button, checked) -> {
            viewModel.setVerboseSipLog(checked);
            formGuard.clean(button);
        });
        formGuard.watch(dtmfRelayCheckbox, (button, checked) -> {
            viewModel.setDtmfRelay(checked);
            formGuard.clean(button);
        });
        formGuard.watch(webInterfaceSwitch, (button, checked) -> {
            viewModel.setWebInterfaceEnabled(checked);
            formGuard.clean(button);
        });
    }

    private void setupClickHandlers() {
        saveButton.setOnClickListener(v -> saveSipSettings());
        connectButton.setOnClickListener(v -> viewModel.startService());
        disconnectButton.setOnClickListener(v -> viewModel.stopService());
        saveAudioButton.setOnClickListener(v -> saveAudioConfig());
        restartButton.setOnClickListener(v -> viewModel.restartService());

        testCallButton.setOnClickListener(v -> {
            viewModel.startTestCall(testDestinationEdit.getText().toString().trim(),
                    selectedTestMode());
            // Placing the call persists the destination, so it stops being unsaved work.
            formGuard.clean(testDestinationEdit);
        });
        testHangupButton.setOnClickListener(v -> viewModel.stopTestCall());

        // GW-42. Re-running is unconditional and always allowed - the wizard pre-fills from
        // GatewayConfig and never clears a value, so it cannot cost a working gateway.
        findViewById(R.id.setupWizardButton).setOnClickListener(v -> SetupLauncher.launch(this));

        // Both radio groups write straight through to config, so the binding window matters:
        // without it, repainting the group from the ViewModel would write the value back as
        // if a person had picked it.
        incomingModeRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (formGuard.isBinding()) {
                return;
            }
            viewModel.setIncomingCallMode(checkedId == R.id.modeSipFirst
                    ? GatewayInCallService.MODE_SIP_FIRST
                    : GatewayInCallService.MODE_ANSWER_FIRST);
        });

        batteryLimitRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (formGuard.isBinding()) {
                return;
            }
            viewModel.setBatteryLimit(checkedId == R.id.limit100 ? 100 : 60);
        });

        onSelected(socProfileSpinner, position -> viewModel.setSelectedAudioProfile(
                MainViewModel.AUDIO_PROFILES[position]));

        onSelected(cardSpinner, viewModel::setSelectedCard);

        onSelected(captureSpinner, position -> {
            AudioDeviceManager.AudioDevices devices = viewModel.getAudioDevices().getValue();
            if (devices != null && position < devices.captureDevices.size()) {
                viewModel.setSelectedCaptureDevice(AudioDeviceManager.parseDeviceNumber(
                        devices.captureDevices.get(position)));
            }
        });

        onSelected(playbackSpinner, position -> {
            AudioDeviceManager.AudioDevices devices = viewModel.getAudioDevices().getValue();
            if (devices != null && position < devices.playbackDevices.size()) {
                viewModel.setSelectedPlaybackDevice(AudioDeviceManager.parseDeviceNumber(
                        devices.playbackDevices.get(position)));
            }
        });

        onSelected(mixerRouteSpinner, position ->
                viewModel.setSelectedMixerRoute(MainViewModel.MIXER_ROUTES[position]));

        onSelected(devicePresetSpinner, this::onMutePresetSelected);
    }

    // ========== Observers ==========

    private void setupObservers() {
        // The status surface (GW-45). Both halves render the same header: the snapshot says
        // what the gateway is doing, and serviceConnected says whether there is anything to
        // ask - a freshly created service publishes UNAVAILABLE too.
        viewModel.getGatewayStatus().observe(this, status -> renderStatus());
        viewModel.getServiceConnected().observe(this, connected -> renderStatus());

        // SIP config. Every field guarded (H-b).
        viewModel.getSipConfig().observe(this, config -> {
            if (config == null) {
                return;
            }
            formGuard.bind(sipServerEdit, config.server);
            formGuard.bind(sipPortEdit, String.valueOf(config.port));
            formGuard.bind(sipUserEdit, config.user);
            formGuard.bind(sipPasswordEdit, config.password);
            formGuard.bind(sipRealmEdit, config.realm);
            formGuard.bind(useTlsCheckbox, config.useTls);
            formGuard.bind(sim1DestinationEdit, config.sim1Destination);
            formGuard.bind(sim2DestinationEdit, config.sim2Destination);

            final int checkedId = config.incomingCallMode == GatewayInCallService.MODE_SIP_FIRST
                    ? R.id.modeSipFirst
                    : R.id.modeAnswerFirst;
            formGuard.bindQuietly(() -> incomingModeRadioGroup.check(checkedId));
        });

        // Audio config: the two typed values. The chosen card, devices, route and profile are
        // separate LiveData now (H-d) and are observed below.
        viewModel.getAudioConfig().observe(this, config -> {
            if (config == null) {
                return;
            }
            formGuard.bind(txGainEdit, String.valueOf(config.txGain));
            formGuard.bind(rxGainEdit, String.valueOf(config.rxGain));
        });

        viewModel.getSelectedAudioProfile().observe(this, profile ->
                setSpinnerToValue(socProfileSpinner, MainViewModel.AUDIO_PROFILES, profile));
        viewModel.getSelectedMixerRoute().observe(this, route ->
                setSpinnerToValue(mixerRouteSpinner, MainViewModel.MIXER_ROUTES, route));
        viewModel.getSelectedCard().observe(this, card -> applyDeviceSelections());
        viewModel.getSelectedCaptureDevice().observe(this, device -> applyDeviceSelections());
        viewModel.getSelectedPlaybackDevice().observe(this, device -> applyDeviceSelections());

        viewModel.getBatteryLimit().observe(this, limit -> {
            if (limit == null) {
                return;
            }
            final int checkedId = limit >= 100 ? R.id.limit100 : R.id.limit60;
            formGuard.bindQuietly(() -> batteryLimitRadioGroup.check(checkedId));
        });

        viewModel.getPermissionState().observe(this, state ->
                permissionsText.setText(state.toDisplayString()));

        viewModel.getAudioDevices().observe(this, this::updateAudioSpinners);

        viewModel.getShowCustomControls().observe(this, show ->
                customMuteContainer.setVisibility(
                        Boolean.TRUE.equals(show) ? View.VISIBLE : View.GONE));

        viewModel.getCurrentMutePreset().observe(this, preset ->
                setSpinnerToValue(devicePresetSpinner, DeviceMuteManager.getPresetNames(), preset));

        viewModel.getManualMuteControls().observe(this, controls ->
                formGuard.bind(manualMuteControlsEdit, controls));

        viewModel.getAvailableControls().observe(this, this::populateMuteCheckboxes);

        viewModel.getDiagnosticsConfig().observe(this, config -> {
            if (config == null) {
                return;
            }
            formGuard.bind(testDestinationEdit, config.testDestination);
            setSpinnerToValue(testModeSpinner, MainViewModel.TEST_MODES, config.testMode);
            formGuard.bind(verboseSipLogCheckbox, config.verboseSipLog);
            formGuard.bind(dtmfRelayCheckbox, config.dtmfRelay);
        });

        viewModel.getWebInterfaceEnabled().observe(this, enabled -> {
            boolean on = Boolean.TRUE.equals(enabled);
            formGuard.bind(webInterfaceSwitch, on);
            updateWebInterfaceLabel(on);
        });

        viewModel.getTestReport().observe(this, report -> {
            if (report != null && !report.isEmpty()) {
                testReportText.setText(report);
            }
        });

        for (MainViewModel.Section section : MainViewModel.Section.values()) {
            CollapsibleSection view = sections.get(section);
            viewModel.getSectionExpanded(section).observe(this, expanded -> {
                if (view != null) {
                    view.setExpanded(Boolean.TRUE.equals(expanded));
                }
            });
        }

        // H-c. The event is consumed here; a value LiveData replays after a configuration
        // change arrives already handled and shows nothing.
        viewModel.getToastMessage().observe(this, event -> {
            if (event == null) {
                return;
            }
            String message = event.getContentIfNotHandled();
            if (message != null && !message.isEmpty()) {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ========== Rendering ==========

    /**
     * Draw the status header from the current snapshot.
     *
     * <p>Called on every tick, which is the point: {@code getCallDurationMs()} and
     * {@code isInGracePeriod()} re-read the clock on every call, so the same unchanged
     * snapshot has to be re-rendered each second or the screen shows a stopwatch that never
     * advances. Nothing derived from them is kept here or in the binder.
     */
    private void renderStatus() {
        GatewayStatus status = viewModel.getGatewayStatus().getValue();
        if (status == null) {
            status = GatewayStatus.UNAVAILABLE;
        }
        boolean connected = Boolean.TRUE.equals(viewModel.getServiceConnected().getValue());

        statusHeader.bind(status, connected);

        connectButton.setEnabled(!status.isRunning());
        disconnectButton.setEnabled(status.isRunning());
    }

    private void updateAudioSpinners(AudioDeviceManager.AudioDevices devices) {
        if (devices == null) {
            return;
        }
        cardSpinner.setAdapter(adapter(devices.cards));
        captureSpinner.setAdapter(adapter(devices.captureDevices));
        playbackSpinner.setAdapter(adapter(devices.playbackDevices));
        applyDeviceSelections();
    }

    /**
     * Move the three device spinners onto the ViewModel's selections.
     *
     * <p>No "am I repainting" flag guards this, on purpose. {@code Spinner.setSelection()}
     * delivers {@code onItemSelected} asynchronously, so any such flag would already have
     * been cleared by the time the callback ran - which is exactly what the old
     * {@code isRefreshing} field could not cope with. The listeners call idempotent ViewModel
     * setters instead, so a callback caused by this method changes nothing.
     */
    private void applyDeviceSelections() {
        AudioDeviceManager.AudioDevices devices = viewModel.getAudioDevices().getValue();
        if (devices == null) {
            return;
        }

        Integer card = viewModel.getSelectedCard().getValue();
        if (card != null && card >= 0 && card < devices.cards.size()) {
            cardSpinner.setSelection(card);
        }

        Integer capture = viewModel.getSelectedCaptureDevice().getValue();
        if (capture != null) {
            int index = AudioDeviceManager.findDeviceIndex(devices.captureDevices, capture);
            if (index >= 0) {
                captureSpinner.setSelection(index);
            }
        }

        Integer playback = viewModel.getSelectedPlaybackDevice().getValue();
        if (playback != null) {
            int index = AudioDeviceManager.findDeviceIndex(devices.playbackDevices, playback);
            if (index >= 0) {
                playbackSpinner.setSelection(index);
            }
        }
    }

    private void populateMuteCheckboxes(List<TinymixManager.MixerControl> controls) {
        micMuteCheckboxContainer.removeAllViews();
        decCheckboxes.clear();

        if (controls == null || controls.isEmpty()) {
            TextView noControlsText = new TextView(this);
            noControlsText.setText(R.string.status_no_mixer_controls);
            // H-a: this line was setTextColor(0xFF999999) with setTextSize(12), the third
            // hardcoded colour on the screen. The Footnote step carries both, from the
            // palette, and follows values-night.
            noControlsText.setTextAppearance(R.style.TextAppearance_Gateway_Footnote);
            micMuteCheckboxContainer.addView(noControlsText);
            return;
        }

        Set<String> savedControls = selectedMuteControls();

        for (TinymixManager.MixerControl control : controls) {
            CheckBox checkBox = new CheckBox(this);
            checkBox.setText(control.toString());
            checkBox.setChecked(savedControls.contains(control.name));
            checkBox.setOnCheckedChangeListener((button, checked) ->
                    viewModel.toggleMuteControl(control.name, checked));

            decCheckboxes.put(control.name, checkBox);
            micMuteCheckboxContainer.addView(checkBox);
        }
    }

    private void updateWebInterfaceLabel(boolean enabled) {
        if (enabled) {
            webInterfaceLabel.setText(getString(R.string.label_web_interface_enabled, getDeviceIp()));
        } else {
            webInterfaceLabel.setText(R.string.label_web_interface_disabled);
        }
    }

    private String getDeviceIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface iface = interfaces.nextElement();
                java.util.Enumeration<java.net.InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // Falls through to the loopback name below; an unreachable address is better
            // than a crash on a screen someone is using to fix the network.
        }
        return "localhost";
    }

    // ========== Save actions ==========

    private void saveSipSettings() {
        String server = sipServerEdit.getText().toString();
        String user = sipUserEdit.getText().toString();
        String password = sipPasswordEdit.getText().toString();
        String realm = sipRealmEdit.getText().toString();
        boolean useTls = useTlsCheckbox.isChecked();
        String sim1Dest = sim1DestinationEdit.getText().toString();
        String sim2Dest = sim2DestinationEdit.getText().toString();

        viewModel.saveSipConfig(server, parsedPort(), user, password, realm, useTls,
                sim1Dest, sim2Dest);

        // What is on screen is what is persisted now, so the guard has nothing left to
        // protect: release it, or a later save from the web interface could never repaint
        // these fields again.
        formGuard.clean(sipServerEdit, sipPortEdit, sipUserEdit, sipPasswordEdit, sipRealmEdit,
                useTlsCheckbox, sim1DestinationEdit, sim2DestinationEdit);
    }

    /**
     * The port field, or the last published one if it cannot be parsed.
     *
     * <p>This used to be a bare {@code Integer.parseInt}, which threw
     * {@code NumberFormatException} on an empty field - a crash on the Save button of the
     * commissioning screen, reachable by clearing one box.
     */
    private int parsedPort() {
        try {
            return Integer.parseInt(sipPortEdit.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            MainViewModel.SipConfig config = viewModel.getSipConfig().getValue();
            return config == null ? GatewayConfig.DEFAULT_SIP_PORT : config.port;
        }
    }

    private void saveAudioConfig() {
        Set<String> muteControls = new HashSet<>();
        for (Map.Entry<String, CheckBox> entry : decCheckboxes.entrySet()) {
            if (entry.getValue().isChecked()) {
                muteControls.add(entry.getKey());
            }
        }

        String manualControls = manualMuteControlsEdit.getText().toString().trim();

        viewModel.saveAudioConfig(parsedGain(txGainEdit), parsedGain(rxGainEdit),
                muteControls, manualControls);

        formGuard.clean(txGainEdit, rxGainEdit, manualMuteControlsEdit);
    }

    private static float parsedGain(EditText field) {
        try {
            return Float.parseFloat(field.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            return 0.0f;
        }
    }

    // ========== Small helpers ==========

    private void onMutePresetSelected(int position) {
        String[] presetNames = DeviceMuteManager.getPresetNames();
        if (position < 0 || position >= presetNames.length) {
            return;
        }
        String preset = presetNames[position];
        if (preset.equals(viewModel.getCurrentMutePreset().getValue())) {
            // A repaint, not a choice. Acting on it would re-save the preset and pop a toast
            // every time the screen rebound.
            return;
        }
        viewModel.selectMutePreset(preset);
        Toast.makeText(this,
                getString(R.string.toast_mute_preset,
                        DeviceMuteManager.getPresetDescriptions()[position]),
                Toast.LENGTH_SHORT).show();
    }

    private String selectedTestMode() {
        int position = testModeSpinner.getSelectedItemPosition();
        if (position < 0 || position >= MainViewModel.TEST_MODES.length) {
            return MainViewModel.TEST_MODES[0];
        }
        return MainViewModel.TEST_MODES[position];
    }

    private Set<String> selectedMuteControls() {
        MainViewModel.AudioConfig config = viewModel.getAudioConfig().getValue();
        return config == null ? new HashSet<>() : config.micMuteControls;
    }

    private ArrayAdapter<String> adapter(String[] items) {
        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(this, R.layout.gw_spinner_item, items);
        adapter.setDropDownViewResource(R.layout.gw_spinner_dropdown_item);
        return adapter;
    }

    private ArrayAdapter<String> adapter(List<String> items) {
        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(this, R.layout.gw_spinner_item, items);
        adapter.setDropDownViewResource(R.layout.gw_spinner_dropdown_item);
        return adapter;
    }

    /** Move a spinner onto the position holding {@code value}, if it has one. */
    private static void setSpinnerToValue(Spinner spinner, String[] values, String value) {
        if (value == null) {
            return;
        }
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                if (spinner.getSelectedItemPosition() != i) {
                    spinner.setSelection(i);
                }
                return;
            }
        }
    }

    /** {@code OnItemSelectedListener} without the two methods nothing on this screen needs. */
    private interface SelectionHandler {
        void onSelected(int position);
    }

    private static void onSelected(Spinner spinner, SelectionHandler handler) {
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                handler.onSelected(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }
}
