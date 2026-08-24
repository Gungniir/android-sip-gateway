package org.onetwoone.gateway.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Application;

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
    private void publishGeneration(long generation) throws Exception {
        // gsmCallPlacedAtWallMs, configGeneration, callsCreated, callsDeleted, watchdog,
        // capturedAt. The call counters (GW-22) and the watchdog findings (GW-25) are
        // irrelevant here; only the reload counter is.
        Constructor<GatewayStatus> ctor = GatewayStatus.class.getDeclaredConstructor(
                boolean.class, boolean.class, String.class, String.class, String.class,
                String.class, long.class, long.class, long.class, long.class,
                GatewayStatus.WatchdogFindings.class, long.class);
        ctor.setAccessible(true);
        GatewayStatus snapshot = ctor.newInstance(
                true, true, "Registered", "Idle", "Not initialized", "IDLE",
                0L, generation, 0L, 0L, GatewayStatus.WatchdogFindings.NONE,
                System.currentTimeMillis());

        Field statusField = PjsipSipService.class.getDeclaredField("status");
        statusField.setAccessible(true);
        statusField.set(service, snapshot);
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

        Field serviceField = MainViewModel.class.getDeclaredField("pjsipService");
        serviceField.setAccessible(true);
        serviceField.set(viewModel, null);
        poll();

        Field seen = MainViewModel.class.getDeclaredField("seenConfigGeneration");
        seen.setAccessible(true);
        assertEquals(-1L, seen.getLong(viewModel));
    }
}
