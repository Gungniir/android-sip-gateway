package org.onetwoone.gateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Handler;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests the charging state machine of {@link BatteryLimitService} (AUDIT B4 / GW-05).
 *
 * <p>Two safety properties are under test:
 * <ol>
 *   <li>a charging decision superseded while queued is dropped, never applied late;</li>
 *   <li>every service teardown force-enables charging — including a teardown that happens before
 *       initialisation finished, which is the {@code stopSelf()}-after-foreground-failure path.</li>
 * </ol>
 *
 * <p>The privileged shell is stubbed, so no test ever execs {@code su}. Robolectric runs a
 * background {@link android.os.HandlerThread} for real, so ordering is pinned down with explicit
 * gates rather than by idling a paused looper.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class BatteryLimitServiceTest {

    private static final long TIMEOUT_S = 10L;

    private static final String SUSPEND = "/sys/class/power_supply/battery/input_suspend";
    private static final String BATT_EN = "/sys/class/power_supply/battery/charging_enabled";
    private static final String USB_EN = "/sys/class/power_supply/usb/charging_enabled";

    /** Records every command, answers the probe reads, and can block the path probe on demand. */
    private static final class FakeShell implements BatteryLimitService.RootShell {
        final List<String> commands = Collections.synchronizedList(new ArrayList<String>());

        /** When set, "cat" commands block until it is released (simulates a slow su probe). */
        volatile CountDownLatch catGate;
        final CountDownLatch catReached = new CountDownLatch(1);

        @Override
        public String exec(String command) {
            commands.add(command);

            if (command.startsWith("cat ")) {
                CountDownLatch gate = catGate;
                if (gate != null) {
                    catReached.countDown();
                    try {
                        gate.await(TIMEOUT_S, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (command.startsWith("cat " + SUSPEND)) {
                    return "0\n";
                }
                if (command.startsWith("cat " + BATT_EN)) {
                    return "1\n";
                }
            }
            // Every other candidate node does not exist on this pretend device.
            return "";
        }

        List<String> snapshot() {
            synchronized (commands) {
                return new ArrayList<>(commands);
            }
        }

        void clear() {
            commands.clear();
        }
    }

    private FakeShell shell;
    private ServiceController<BatteryLimitService> controller;
    private BatteryLimitService service;
    private boolean destroyed;

    @Before
    public void setUp() {
        shell = new FakeShell();
        BatteryLimitService.setRootShellForTest(shell);
        controller = Robolectric.buildService(BatteryLimitService.class);
    }

    @After
    public void tearDown() {
        CountDownLatch gate = shell.catGate;
        if (gate != null) {
            gate.countDown();   // never leave the control thread parked in the fake shell
        }
        if (!destroyed && controller != null) {
            try {
                controller.destroy();
            } catch (Exception ignored) {
                // best effort
            }
        }
        BatteryLimitService.setRootShellForTest(null);
    }

    // ---------------------------------------------------------------- helpers

    private void createService() {
        service = controller.create().get();
        assertNotNull("control thread must exist from onCreate onwards",
                service.controlLooperForTest());
    }

    private Handler controlHandler() {
        return new Handler(service.controlLooperForTest());
    }

    /** Post a barrier onto the control thread and wait for it: everything queued earlier has run. */
    private void awaitControlIdle() throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        assertTrue("control looper rejected the barrier", controlHandler().post(new Runnable() {
            @Override
            public void run() {
                done.countDown();
            }
        }));
        assertTrue("control thread stalled", done.await(TIMEOUT_S, TimeUnit.SECONDS));
    }

    /** Let initialisation and the main-thread follow-ups complete. */
    private void settle() throws InterruptedException {
        awaitControlIdle();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        awaitControlIdle();
    }

    /**
     * Park the control thread inside a runnable so decisions posted afterwards are guaranteed to
     * still be sitting in the queue.
     */
    private CountDownLatch parkControlThread() throws InterruptedException {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        assertTrue(controlHandler().post(new Runnable() {
            @Override
            public void run() {
                entered.countDown();
                try {
                    release.await(TIMEOUT_S, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }));
        assertTrue("control thread never picked up the park runnable",
                entered.await(TIMEOUT_S, TimeUnit.SECONDS));
        return release;
    }

    private void destroyService() {
        controller.destroy();
        destroyed = true;
    }

    /** Only the per-path writes issued by setCharging(); excludes the batched force-enable sweep. */
    private List<String> perPathWrites() {
        List<String> out = new ArrayList<>();
        for (String c : shell.snapshot()) {
            if (c.startsWith("echo ") && !c.contains(";")) {
                out.add(c);
            }
        }
        return out;
    }

    private boolean sawForceEnableSweep() {
        for (String c : shell.snapshot()) {
            if (c.contains("echo 0 > " + SUSPEND + " 2>/dev/null")
                    && c.contains("echo 1 > " + BATT_EN + " 2>/dev/null")
                    && c.contains("echo 1 > " + USB_EN + " 2>/dev/null")) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- tests

    @Test
    public void activeChargingPathsArePublishedAsAnImmutableSnapshot() throws Exception {
        createService();
        settle();

        List<String[]> paths = service.activeChargingPaths;
        assertEquals("only the two nodes the fake device exposes", 2, paths.size());
        assertEquals(SUSPEND, paths.get(0)[0]);
        assertEquals(BATT_EN, paths.get(1)[0]);

        try {
            paths.add(new String[]{"/sys/nope", "0", "1"});
            fail("activeChargingPaths must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // exactly what we want
        }
    }

    /**
     * The core of GW-05: a decision superseded while it sat in the control queue must be skipped,
     * not applied late. Under the old code the two decisions became two threads that could reach
     * sysfs in either order.
     */
    @Test
    public void supersededDisableDecisionIsNeverApplied() throws Exception {
        createService();
        settle();
        shell.clear();

        CountDownLatch release = parkControlThread();

        // Both decisions come from a thread that is not the control thread, so both queue up.
        // "disable" is superseded by "enable" before either can be applied.
        service.requestCharging(false);
        service.requestCharging(true);

        release.countDown();
        awaitControlIdle();

        List<String> writes = perPathWrites();
        assertEquals("one reconcile pass over the two active paths", 2, writes.size());
        assertTrue(writes.contains("echo 0 > " + SUSPEND));   // 0 == enable for input_suspend
        assertTrue(writes.contains("echo 1 > " + BATT_EN));   // 1 == enable
        assertFalse("the superseded disable must never reach sysfs",
                writes.contains("echo 1 > " + SUSPEND));
        assertFalse("the superseded disable must never reach sysfs",
                writes.contains("echo 0 > " + BATT_EN));
        assertFalse(service.isChargingDisabled());
    }

    /** The reverse order: whichever decision was made last is the one that lands. */
    @Test
    public void supersededEnableDecisionIsNeverApplied() throws Exception {
        createService();
        settle();
        shell.clear();

        CountDownLatch release = parkControlThread();

        service.requestCharging(true);
        service.requestCharging(false);

        release.countDown();
        awaitControlIdle();

        List<String> writes = perPathWrites();
        assertEquals(2, writes.size());
        assertTrue(writes.contains("echo 1 > " + SUSPEND));   // 1 == disable for input_suspend
        assertTrue(writes.contains("echo 0 > " + BATT_EN));
        assertTrue(service.isChargingDisabled());
    }

    /**
     * Teardown while charging is disabled must re-enable it. This is the escape hatch that keeps an
     * unattended gateway phone from being stranded.
     */
    @Test
    public void destroyForceEnablesChargingWhileDisabled() throws Exception {
        createService();
        settle();

        service.requestCharging(false);
        awaitControlIdle();
        assertTrue("precondition: charging is disabled", service.isChargingDisabled());

        shell.clear();
        destroyService();

        assertTrue("onDestroy must sweep every known path with its enable value",
                sawForceEnableSweep());
        assertFalse(service.isChargingDisabled());
        assertTrue(service.desiredChargingEnabledForTest());
    }

    /**
     * The {@code stopSelf()}-after-foreground-start-failure path: onDestroy can run before
     * initialisation produced any state at all. The escape hatch must not depend on the probed
     * path list, because a previous process may have left charging disabled.
     */
    @Test
    public void destroyForceEnablesWhileInitialisationIsStillInFlight() throws Exception {
        shell.catGate = new CountDownLatch(1);   // park the probe inside findChargingPaths()

        createService();
        assertTrue("probe never started", shell.catReached.await(TIMEOUT_S, TimeUnit.SECONDS));
        assertTrue("precondition: no paths published yet", service.activeChargingPaths.isEmpty());

        shell.clear();
        destroyService();

        assertTrue("the escape hatch must not depend on the probed path list",
                sawForceEnableSweep());

        shell.catGate.countDown();
    }

    /** A decision still queued when the service is destroyed must never be applied afterwards. */
    @Test
    public void queuedDisableIsDroppedByDestroy() throws Exception {
        createService();
        settle();

        CountDownLatch release = parkControlThread();
        service.requestCharging(false);   // provably still in the queue: the thread is parked
        shell.clear();

        destroyService();                 // clears the queue, then force-enables

        // The reconcile was removed from the queue while the control thread was parked, so it can
        // no longer run; releasing the thread only lets it drain onDestroy's own force-enable.
        release.countDown();

        for (String c : perPathWrites()) {
            assertFalse("a queued disable escaped onDestroy: " + c,
                    c.equals("echo 1 > " + SUSPEND) || c.equals("echo 0 > " + BATT_EN));
        }
        assertTrue(service.desiredChargingEnabledForTest());
        assertTrue(sawForceEnableSweep());
        assertFalse(service.isChargingDisabled());
    }
}
