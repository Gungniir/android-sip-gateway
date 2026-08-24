package org.onetwoone.gateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Service;
import android.content.Intent;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.onetwoone.gateway.core.GatewayControlThread;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.util.ArrayList;
import java.util.List;

/**
 * GW-26 — {@link PjsipSipService}'s destroy path and restart semantics.
 *
 * <p>The first unit test this class has ever had, which is why the bugs it covers survived four
 * phases of refactoring. Everything past {@code createEndpoint()} calls pjsua2 and is therefore
 * unreachable from the JVM — but the whole of GW-26 lives <em>before</em> that point, in
 * ordering and cancellation, so the interesting surface is exactly the reachable one.
 *
 * <h3>How the in-flight-init case is reproduced</h3>
 * Faithfully, and without a mock. {@code onStartCommand} posts {@code initializeSip} to the real
 * control thread, which calls {@code createEndpoint()}, which posts the {@code new Endpoint()}
 * construction to <b>main</b> and waits on it. Under Robolectric's paused main looper the test
 * thread <em>is</em> main and is not idling, so that runnable cannot run — which is precisely
 * the production interleaving, where it is queued behind {@code onDestroy}. The control thread
 * is genuinely parked in the latch when destroy arrives.
 *
 * <p>Assertions are on {@link ShadowLog} and on thread liveness rather than on internal fields:
 * a teardown is a sequence of effects, and the log is where that sequence is recorded on device
 * too.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class PjsipSipServiceLifecycleTest {

    /** Comfortably past {@code CONTROL_QUIT_TIMEOUT_MS} (3 s), for "is it cancelled?" bounds. */
    private static final long CANCELLED_DESTROY_BUDGET_MS = 2000L;

    private Application app;
    private final List<ServiceController<?>> controllers = new ArrayList<>();

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        // Static state (the service instance, the static Endpoint) and the user-stop latch all
        // outlive a single test in a shared Robolectric classloader, and an earlier test can
        // leave an endpoint-creation runnable sitting on the main queue - which would make the
        // "is the control thread parked on main yet?" barrier below answer immediately and
        // wrongly. Drain first, then reset, then clear the log the assertions read.
        shadowOf(Looper.getMainLooper()).idle();
        PjsipSipService.setUserStopped(app, false);
        shadowOf(app).clearStartedServices();
        ShadowLog.clear();
    }

    @After
    public void tearDown() {
        for (ServiceController<?> controller : controllers) {
            try {
                controller.destroy();
            } catch (Throwable ignored) {
                // A test that already destroyed its service, or one that meant to leave it half
                // built. Either way the next test's setUp() is what actually isolates it.
            }
        }
        controllers.clear();
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse("a test must not leak a control thread into the next one",
                controlThreadIsAlive());
    }

    // ========== Cancellation: destroy must not abandon an initialising control thread ==========

    /**
     * The headline. Before GW-26 {@code initializeSip} had no cancellation check anywhere, so
     * the bounded {@code quitSafely} join was not a shutdown mechanism: it expired, the control
     * thread was <em>abandoned</em>, its 30 s latch then resolved the instant {@code onDestroy}
     * returned, and it walked on to {@code createAccount(this)} — registering a fresh SIP
     * account for a destroyed service, with its callbacks going to a quit looper so nothing
     * ever tore it down (AUDIT H8c, as amended).
     *
     * <p>Two independent signals that it is now cancelled rather than abandoned: the thread is
     * <em>dead</em> when {@code onDestroy} returns (an expired join leaves it alive), and the
     * whole destroy costs far less than the join bound it would otherwise have burned.
     */
    @Test
    public void destroyCancelsAnInFlightSipInitInsteadOfAbandoningTheThread() throws Exception {
        PjsipSipService service = startedService();

        long startedAt = System.nanoTime();
        service.onDestroy();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertFalse("an expired join abandons the control thread; cancellation retires it",
                controlThreadIsAlive());
        assertTrue("destroy must be cancelled, not timed out: " + elapsedMs + " ms",
                elapsedMs < CANCELLED_DESTROY_BUDGET_MS);
        assertLogged("SIP init abandoned");
    }

    /**
     * The damage H8c actually does. A cancelled init must stop <em>before</em> the account is
     * created, and must not treat cancellation as a failure worth reconnecting from — the
     * service it would reconnect for is gone.
     */
    @Test
    public void aCancelledInitNeitherCompletesNorSchedulesAReconnect() throws Exception {
        PjsipSipService service = startedService();

        service.onDestroy();
        // Let main drain, exactly as it does when onDestroy returns on device: this is the
        // moment the abandoned thread used to wake up and carry on.
        shadowOf(Looper.getMainLooper()).idle();

        assertLogged("SIP init abandoned");
        assertNotLogged("SIP initialized");
        assertNotLogged("Scheduling reconnection");
    }

    /**
     * The variant that terminal cancellation exists for, and the one a reusable "cancellation
     * generation" would have got wrong. Here the control thread has <em>not</em> started
     * {@code initializeSip} when destroy runs — the task is still in the queue, so it calls
     * {@code begin()} afterwards. It must be born cancelled, not handed a fresh live token and
     * allowed to go on and register an account for a service that is already gone.
     *
     * <p>No barrier, deliberately: this races {@code onDestroy} against the control thread
     * picking the task up, and must hold whichever wins.
     */
    @Test
    public void anInitStillQueuedWhenDestroyRunsIsCancelledToo() {
        ServiceController<PjsipSipService> controller =
                Robolectric.buildService(PjsipSipService.class);
        controllers.add(controller);
        controller.create();
        controller.get().onStartCommand(new Intent(), 0, 1);

        controller.get().onDestroy();
        shadowOf(Looper.getMainLooper()).idle();

        assertFalse(controlThreadIsAlive());
        assertNotLogged("SIP initialized");
    }

    // ========== Ordering: what runs where, and in which order ==========

    /**
     * AUDIT G2 and H11. {@code shutdownSip()} used to run inline on main after the join had
     * already abandoned the control thread, so main's {@code deleteAccount()} could destroy a
     * conference port while the control thread was inside {@code unwireBridge()}'s liveness
     * check — a pjmedia {@code abort()}, which no {@code try/catch} would have caught.
     *
     * <p>It now runs as a step of the single control-thread teardown task, after the bridge is
     * unwired. {@code shutdownSip()} asserts that thread, and debug builds throw on a violation,
     * so reaching "SIP shutdown complete" without a caught teardown step is the proof.
     */
    @Test
    public void sipShutdownRunsOnTheControlThreadBehindTheBridgeTeardown() throws Exception {
        PjsipSipService service = startedService();

        service.onDestroy();

        assertOrder("Control-thread teardown starting", "Shutting down SIP...");
        assertOrder("Shutting down SIP...", "SIP shutdown complete");
        assertOrder("SIP shutdown complete", "Control-thread teardown complete");
        assertNotLogged("Teardown step 'shutdownSip' failed");
    }

    /**
     * The control thread is retired before main finishes teardown, and main's own steps run
     * after it — the wake-lock release in particular, which is the acceptance criterion.
     */
    @Test
    public void mainSideTeardownRunsAfterTheControlThreadIsRetired() throws Exception {
        PjsipSipService service = startedService();

        service.onDestroy();

        assertOrder("Control-thread teardown complete", "CPU WakeLock released");
        assertOrder("CPU WakeLock released", "Service destroyed in");
    }

    // ========== Guarded teardown: one failing step must not skip the rest ==========

    /**
     * AUDIT H8, by its reachable path rather than the brief's. The static loader catches
     * {@code UnsatisfiedLinkError} and lets the service run with no {@code libpjsua2}; every
     * pjsua2 call then throws an {@code Error}, and {@code SipAccountManager.deleteAccount()}
     * catches only {@code Exception}. That {@code Error} escaped {@code shutdownSip()} on main
     * and skipped {@code powerController.release()}, the telephony unlisten, the mute restore
     * and {@code stopForeground} — leaking {@code Gateway::CpuWakeLock}.
     *
     * <p>{@link ThrowingTeardownService} throws the same class of failure from a main-side step
     * that sits immediately <em>before</em> the wake-lock release, so this asserts the guard,
     * not the ordering.
     */
    @Test
    public void anErrorInOneTeardownStepDoesNotSkipTheWakeLockRelease() {
        ServiceController<ThrowingTeardownService> controller =
                Robolectric.buildService(ThrowingTeardownService.class);
        controllers.add(controller);
        controller.create();

        controller.get().onDestroy();

        assertLogged("Teardown step 'stopWebServer' failed");
        assertLogged("CPU WakeLock released");
        assertLogged("Service destroyed in");
    }

    /**
     * The reachable partial state §2.7 identifies: {@code onCreate} ran, {@code onStartCommand}
     * never did (a bind-only service). Nothing has been started, so most of teardown has nothing
     * to do — and must say so rather than throwing.
     */
    @Test
    public void aServiceThatWasOnlyBoundDestroysCleanly() {
        ServiceController<PjsipSipService> controller =
                Robolectric.buildService(PjsipSipService.class);
        controllers.add(controller);
        controller.create();

        controller.get().onDestroy();

        assertLogged("Service destroyed in");
        assertNull(PjsipSipService.getInstance());
    }

    /**
     * The extreme of the same argument: a service whose {@code onCreate} never ran at all, so
     * every manager — including the control thread — is null. Teardown must be a guarded no-op,
     * not an NPE that Android escalates.
     */
    @Test
    public void aServiceWhoseOnCreateNeverRanDestroysWithoutNpe() {
        new PjsipSipService().onDestroy();

        assertLogged("No control thread");
        assertLogged("Service destroyed in");
    }

    @Test
    public void destroyClearsTheStaticInstanceBeforeTearingAnythingDown() throws Exception {
        PjsipSipService service = startedService();
        assertNotNull(PjsipSipService.getInstance());

        service.onDestroy();

        assertNull(PjsipSipService.getInstance());
        assertOrder("Service destroying", "Control-thread teardown starting");
    }

    // ========== Restart semantics ==========

    /**
     * GW-26 §5. {@code stopRequested} is reset by {@code onCreate}, so it could never survive a
     * restart; the latch that does is persisted, and it is written before {@code stopSelf()} so
     * that anything reacting to the service going away already sees it.
     */
    @Test
    public void anExplicitStopLatchesAcrossTheProcess() throws Exception {
        PjsipSipService service = startedService();

        service.stop();

        assertTrue(PjsipSipService.isUserStopped(app));
        assertTrue(shadowOf(service).isStoppedBySelf());
    }

    /**
     * The suppressed case, and the only one: a sticky redelivery ({@code intent == null}, the
     * system restarting us of its own accord) that lands after a human stopped the gateway.
     */
    @Test
    public void aStickyRestartAfterAUserStopStaysDown() {
        PjsipSipService.setUserStopped(app, true);
        ServiceController<PjsipSipService> controller =
                Robolectric.buildService(PjsipSipService.class);
        controllers.add(controller);
        controller.create();

        int result = controller.get().onStartCommand(null, 0, 1);

        assertEquals(Service.START_NOT_STICKY, result);
        assertTrue(shadowOf(controller.get()).isStoppedBySelf());
        assertLogged("staying down");
    }

    /**
     * The direction that matters more: a gateway that does not come back after a crash, an OOM
     * kill or a reboot is worse than the bug §5 closes. Every one of those paths reaches us as
     * an ordinary start, with the latch clear.
     */
    @Test
    public void anOrdinarySystemRestartStillBringsTheGatewayBack() {
        ServiceController<PjsipSipService> controller =
                Robolectric.buildService(PjsipSipService.class);
        controllers.add(controller);
        controller.create();

        int result = controller.get().onStartCommand(null, 0, 1);

        assertEquals(Service.START_STICKY, result);
        assertFalse(shadowOf(controller.get()).isStoppedBySelf());
    }

    /** Anything carrying an intent is somebody asking for the gateway, so it clears the latch. */
    @Test
    public void anExplicitStartClearsTheLatch() {
        PjsipSipService.setUserStopped(app, true);
        ServiceController<PjsipSipService> controller =
                Robolectric.buildService(PjsipSipService.class);
        controllers.add(controller);
        controller.create();

        int result = controller.get().onStartCommand(new Intent(), 0, 1);

        assertEquals(Service.START_STICKY, result);
        assertFalse(PjsipSipService.isUserStopped(app));
    }

    /**
     * The reload's give-up branch stops the service too, and it is <b>not</b> a user stop — it
     * must leave every restart path working. (Its old comment claimed {@code START_STICKY} would
     * bring the service back; it does not, which is AUDIT H14.)
     */
    @Test
    public void anInternalStopDoesNotLatchTheGatewayDown() throws Exception {
        PjsipSipService service = startedService();

        service.reloadConfig();
        shadowOf(Looper.getMainLooper()).idle();

        assertFalse("only a human stop may suppress the restart paths",
                PjsipSipService.isUserStopped(app));
    }

    // ========== GatewayInCallService must honour the latch too ==========

    /**
     * {@code onDestroy} nulls {@code instance} first, so an InCallService binding during
     * teardown — or any bind after an explicit STOP, since the app is the default dialler —
     * used to restart the gateway the operator had just stopped.
     */
    @Test
    public void theInCallServiceDoesNotRestartAGatewayTheUserStopped() {
        PjsipSipService.setUserStopped(app, true);

        ServiceController<GatewayInCallService> controller =
                Robolectric.buildService(GatewayInCallService.class);
        controllers.add(controller);
        controller.create();

        assertNull("a stopped gateway must stay stopped",
                shadowOf(app).getNextStartedService());
    }

    @Test
    public void theInCallServiceStillStartsTheGatewayWhenNobodyStoppedIt() {
        ServiceController<GatewayInCallService> controller =
                Robolectric.buildService(GatewayInCallService.class);
        controllers.add(controller);
        controller.create();

        Intent started = shadowOf(app).getNextStartedService();
        assertNotNull("binding as default dialler must still bring the gateway up", started);
        assertEquals(PjsipSipService.class.getName(), started.getComponent().getClassName());
    }

    // ========== Helpers ==========

    /**
     * A created, started service whose SIP init is parked in the endpoint hop on the real
     * control thread — i.e. in exactly the state {@code onDestroy} has to cancel out of.
     *
     * <p>{@code onStartCommand} is called directly rather than through
     * {@code ServiceController.startCommand()}, which idles the main looper afterwards — idling
     * would let the endpoint-creation runnable run and defeat the whole point.
     *
     * <p>Returning only once the control thread is parked is what makes these tests
     * deterministic rather than a race against thread start-up: parked, the control queue holds
     * exactly the teardown message and nothing that a frozen Robolectric clock can reorder.
     */
    private PjsipSipService startedService() throws Exception {
        ServiceController<PjsipSipService> controller =
                Robolectric.buildService(PjsipSipService.class);
        controllers.add(controller);
        controller.create();
        controller.get().onStartCommand(new Intent(), 0, 1);
        awaitEndpointHopQueuedOnMain();
        return controller.get();
    }

    /**
     * Block until the control thread has posted the endpoint construction to main, i.e. until it
     * is in (or entering) the latch {@code onDestroy} has to cancel. A non-idle main looper is
     * the observable form of "the control thread is now waiting on main" — {@code setUp} drains
     * the queue first so nothing else can be what makes it non-idle.
     */
    private void awaitEndpointHopQueuedOnMain() throws Exception {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (shadowOf(Looper.getMainLooper()).isIdle()) {
            assertTrue("the control thread never reached the endpoint hop",
                    System.currentTimeMillis() < deadline);
            Thread.sleep(2);
        }
    }

    private static boolean controlThreadIsAlive() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (GatewayControlThread.THREAD_NAME.equals(t.getName()) && t.isAlive()) {
                return true;
            }
        }
        return false;
    }

    private static void assertLogged(String fragment) {
        assertTrue("expected a log line containing \"" + fragment + "\"", indexOfLog(fragment) >= 0);
    }

    private static void assertNotLogged(String fragment) {
        assertEquals("did not expect a log line containing \"" + fragment + "\"",
                -1, indexOfLog(fragment));
    }

    private static void assertOrder(String first, String second) {
        int a = indexOfLog(first);
        int b = indexOfLog(second);
        assertTrue("expected \"" + first + "\"", a >= 0);
        assertTrue("expected \"" + second + "\"", b >= 0);
        assertTrue("\"" + first + "\" must precede \"" + second + "\"", a < b);
    }

    private static int indexOfLog(String fragment) {
        List<ShadowLog.LogItem> logs = ShadowLog.getLogs();
        for (int i = 0; i < logs.size(); i++) {
            ShadowLog.LogItem item = logs.get(i);
            if (item.msg != null && item.msg.contains(fragment)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Stands in for the {@code libpjsua2}-is-missing path: a teardown step that throws an
     * {@code Error} rather than an {@code Exception}, from a step immediately before
     * {@code powerController.release()}.
     */
    public static class ThrowingTeardownService extends PjsipSipService {
        @Override
        public void stopWebServer() {
            throw new UnsatisfiedLinkError("no pjsua2, as on a device without the native lib");
        }
    }
}
