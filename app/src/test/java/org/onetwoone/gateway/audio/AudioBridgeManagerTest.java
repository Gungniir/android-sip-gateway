package org.onetwoone.gateway.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.onetwoone.gateway.BuildConfig;
import org.onetwoone.gateway.config.GatewayConfig;
import org.onetwoone.gateway.core.GatewayControlThread;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GW-12 — control-thread ownership of the audio bridge, and AUDIT E2.
 *
 * <p><b>What can and cannot be covered here.</b> Everything past the guards in
 * {@code startBridge}/{@code unwireBridge} is pjsua2, which needs {@code libpjsua2.so} and an
 * {@code Endpoint} — neither exists on the JVM. So these tests cover exactly the parts that
 * are pure state: which thread a mutator will accept, and what a second
 * {@code AudioBridgeManager} sees of the wiring the first one left behind. The wiring itself
 * (the rewire path, the liveness check, {@code stopTransmit}) is on-device verification —
 * see the GW-12 issue's §Verification.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class AudioBridgeManagerTest {

    private static final long TIMEOUT_S = 10L;

    private Application app;
    private GatewayConfig config;
    private GatewayControlThread control;

    @Before
    public void setUp() throws Exception {
        app = RuntimeEnvironment.getApplication();

        Field instance = GatewayConfig.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
        GatewayConfig.init(app);
        config = GatewayConfig.getInstance();

        // The test body runs on Robolectric's main looper, so "main" is the control thread
        // here and any other thread is a wrong thread.
        control = new GatewayControlThread(Looper.getMainLooper(), null);

        AudioBridgeManager.setWiringForTest(null);
    }

    @After
    public void tearDown() {
        // The holder is process-scoped by design; leaving it set would leak into any other
        // test in this JVM that reads the bridge's status.
        AudioBridgeManager.setWiringForTest(null);
    }

    private AudioBridgeManager newManager() {
        return new AudioBridgeManager(app, config, control);
    }

    // ========== Control-thread ownership ==========

    /**
     * The mechanism that keeps the ownership model from eroding. {@code stopBridge()} is the
     * one worth pinning: it is the method that reaches {@code unwireBridge()}, whose liveness
     * check is only sound because no other thread can be wiring at the same time.
     */
    @Test
    public void stopBridgeRefusesToRunOffTheControlThread() throws Exception {
        AudioBridgeManager bridge = newManager();
        AudioBridgeManager.Wiring wiring = new AudioBridgeManager.Wiring(null);
        wiring.active = true;
        AudioBridgeManager.setWiringForTest(wiring);

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        new Thread(() -> {
            try {
                bridge.stopBridge(AudioBridgeManager.ANY_GENERATION);
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        }, "not-control").start();

        assertTrue(done.await(TIMEOUT_S, TimeUnit.SECONDS));

        if (BuildConfig.DEBUG) {
            assertNotNull("a wrong-thread unwire must fail loudly in debug builds", thrown.get());
            assertTrue(thrown.get() instanceof IllegalStateException);
            assertTrue("the message must name the offending thread",
                    thrown.get().getMessage().contains("not-control"));
        } else {
            assertNull("release builds must not kill a live gateway over this", thrown.get());
        }
    }

    /** Same rule for the other three mutators, so none of them can drift back off-thread. */
    @Test
    public void everyMutatorAssertsTheControlThread() throws Exception {
        AudioBridgeManager bridge = newManager();
        AudioBridgeManager.setWiringForTest(new AudioBridgeManager.Wiring(null));

        assertRefusedOffThread("initialize", bridge::initialize);
        assertRefusedOffThread("stopBridge", () -> bridge.stopBridge(AudioBridgeManager.ANY_GENERATION));
        assertRefusedOffThread("startAudioStreams", bridge::startAudioStreams);
        assertRefusedOffThread("stopAudioStreams", bridge::stopAudioStreams);
    }

    private void assertRefusedOffThread(String what, Runnable mutator) throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        new Thread(() -> {
            try {
                mutator.run();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        }, "not-control").start();
        assertTrue(done.await(TIMEOUT_S, TimeUnit.SECONDS));

        if (BuildConfig.DEBUG) {
            assertNotNull(what + " must assert the control thread", thrown.get());
            assertTrue(what + " must throw IllegalStateException, got " + thrown.get(),
                    thrown.get() instanceof IllegalStateException);
        }
    }

    /** The read-only accessors are deliberately unasserted - NanoHTTPD and main read them. */
    @Test
    public void readOnlyAccessorsAreCallableFromAnyThread() throws Exception {
        AudioBridgeManager bridge = newManager();
        AudioBridgeManager.Wiring wiring = new AudioBridgeManager.Wiring(null);
        wiring.active = true;
        AudioBridgeManager.setWiringForTest(wiring);

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<String> status = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        new Thread(() -> {
            try {
                status.set(bridge.getStatusString());
                bridge.isBridgeActive();
                bridge.isAudioStreaming();
                bridge.handlesMicMute();
                bridge.getGsmAudioPort();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        }, "nano-httpd").start();

        assertTrue(done.await(TIMEOUT_S, TimeUnit.SECONDS));
        assertNull("status reads must not assert a thread", thrown.get());
        assertEquals("Bridge active", status.get());
    }

    // ========== AUDIT E2 — the static port / instance flag split ==========

    /**
     * The E2 regression itself. {@code gsmAudioPort} was static and survived
     * {@code onDestroy}; {@code bridgeActive} was an instance field and did not. So a
     * restarted service saw {@code false} while the port was still wired, {@code stopBridge()}
     * early-returned, and the conference links leaked for the life of the process.
     *
     * <p>Modelled exactly: instance A wires, instance B is what a service restart builds.
     */
    @Test
    public void aRestartedServiceSeesTheWiringTheOldInstanceLeftBehind() {
        AudioBridgeManager oldInstance = newManager();
        AudioBridgeManager.Wiring wiring = new AudioBridgeManager.Wiring(null);
        wiring.active = true;
        wiring.confSlot = 7;
        AudioBridgeManager.setWiringForTest(wiring);

        AudioBridgeManager restarted = newManager();

        assertTrue("the restarted service must see the real state, not a fresh false",
                restarted.isBridgeActive());
        assertEquals("Bridge active", restarted.getStatusString());
        assertTrue("...and the old instance must agree - there is only one state",
                oldInstance.isBridgeActive());

        // And it must actually unwire, rather than early-returning on a stale false.
        restarted.stopBridge(AudioBridgeManager.ANY_GENERATION);

        assertFalse(restarted.isBridgeActive());
        assertNull(wiring.callMedia);
        assertEquals(-1, wiring.confSlot);
    }

    /**
     * The other half of E2: {@code initialize()} on a restart adopts the holder rather than
     * building a second port, and clears wiring whose conference ports cannot still exist -
     * the previous instance's {@code shutdownSip()} deleted the account they belonged to.
     */
    @Test
    public void initializeClearsWiringLeftByAPreviousServiceInstance() {
        AudioBridgeManager.Wiring wiring = new AudioBridgeManager.Wiring(null);
        wiring.active = true;
        wiring.confSlot = 4;
        AudioBridgeManager.setWiringForTest(wiring);

        newManager().initialize();

        assertFalse("a bridge whose call media is gone must not stay marked active",
                wiring.active);
        assertEquals(-1, wiring.confSlot);
        assertTrue("initialize() must adopt the holder, not replace it",
                AudioBridgeManager.wiringForTest() == wiring);
    }

    /** An idle holder is left exactly as it is - adoption must not invent work. */
    @Test
    public void initializeLeavesAnIdleHolderAlone() {
        AudioBridgeManager.Wiring wiring = new AudioBridgeManager.Wiring(null);
        AudioBridgeManager.setWiringForTest(wiring);

        newManager().initialize();

        assertFalse(wiring.active);
        assertTrue(AudioBridgeManager.wiringForTest() == wiring);
    }

    /** No port yet: every mutator has to be a quiet no-op, not an NPE. */
    @Test
    public void mutatorsAreNoOpsBeforeInitialize() {
        AudioBridgeManager bridge = newManager();

        bridge.stopBridge(AudioBridgeManager.ANY_GENERATION);
        bridge.startAudioStreams();
        bridge.stopAudioStreams();

        assertFalse(bridge.isBridgeActive());
        assertFalse(bridge.isInitialized());
        assertFalse(bridge.isAudioStreaming());
        assertFalse(bridge.handlesMicMute());
        assertNull(bridge.getGsmAudioPort());
        assertEquals("Not initialized", bridge.getStatusString());
    }

    // ========== Generation-tagged wiring ==========

    private static AudioBridgeManager.Wiring wiredTo(long generation) {
        AudioBridgeManager.Wiring wiring = new AudioBridgeManager.Wiring(null);
        wiring.active = true;
        wiring.confSlot = 3;
        wiring.wiredGeneration = generation;
        wiring.newestGeneration = generation;
        AudioBridgeManager.setWiringForTest(wiring);
        return wiring;
    }

    /**
     * The teardown half of the generation tag. A {@code stopBridge} raised for a call that has
     * already been replaced must leave the replacement's audio alone - the case the wiring
     * state was always meant to cover ("unwire exactly what we wired") but could not, because
     * it had no way to tell one call's wiring from another's.
     */
    @Test
    public void stopBridgeIgnoresAGenerationThatIsNoLongerWired() {
        AudioBridgeManager.Wiring wiring = wiredTo(9L);

        newManager().stopBridge(8L);

        assertTrue("call 8's teardown must not unwire call 9", wiring.active);
        assertEquals(9L, wiring.wiredGeneration);
    }

    @Test
    public void stopBridgeUnwiresItsOwnGeneration() {
        AudioBridgeManager.Wiring wiring = wiredTo(9L);

        newManager().stopBridge(9L);

        assertFalse(wiring.active);
        assertEquals(AudioBridgeManager.NO_GENERATION, wiring.wiredGeneration);
    }

    /** A full teardown drops whatever is wired, and forgets the high-water mark with it. */
    @Test
    public void anyGenerationUnwiresWhateverIsWiredAndResetsTheHighWaterMark() {
        AudioBridgeManager.Wiring wiring = wiredTo(9L);

        newManager().stopBridge(AudioBridgeManager.ANY_GENERATION);

        assertFalse(wiring.active);
        assertEquals(AudioBridgeManager.NO_GENERATION, wiring.wiredGeneration);
        assertEquals(AudioBridgeManager.NO_GENERATION, wiring.newestGeneration);
    }

    /**
     * AUDIT D1b. {@code CallManager.onSipCallState} fires {@code onSipCallConnected(call)} on
     * CONFIRMED without checking that the call is current, and that callback is posted - so a
     * CONFIRMED for a superseded call can arrive after its replacement is up. The bridge must
     * refuse it rather than trust the caller.
     */
    @Test
    public void aSupersededGenerationIsRefused() {
        AudioBridgeManager bridge = newManager();
        AudioBridgeManager.Wiring wiring = new AudioBridgeManager.Wiring(null);

        assertTrue("call 5 arrives first", bridge.admitGeneration(wiring, false, 5L));
        assertTrue("call 6 replaces it", bridge.admitGeneration(wiring, false, 6L));
        assertFalse("call 5's queued CONFIRMED must not bridge a replaced call",
                bridge.admitGeneration(wiring, false, 5L));

        assertEquals("a refusal must not move the high-water mark", 6L, wiring.newestGeneration);
    }

    /** The same call asking again - the re-INVITE/UPDATE rewire path - is always admitted. */
    @Test
    public void theCurrentGenerationIsAdmittedRepeatedly() {
        AudioBridgeManager bridge = newManager();
        AudioBridgeManager.Wiring wiring = new AudioBridgeManager.Wiring(null);

        assertTrue(bridge.admitGeneration(wiring, false, 6L));
        assertTrue("PJSIP re-creates the media stream on its own codec-locking UPDATE",
                bridge.admitGeneration(wiring, false, 6L));
        assertEquals(6L, wiring.newestGeneration);
    }

    /**
     * The diagnostic test call is operator-initiated, so it is current by definition. It must
     * also leave the gateway's high-water mark alone: a BRIDGE-mode test call placed during a
     * live gateway call would otherwise lock that call out of re-wiring once the test ended.
     */
    @Test
    public void theDiagnosticCallIsExemptAndDoesNotMoveTheHighWaterMark() {
        AudioBridgeManager bridge = newManager();
        AudioBridgeManager.Wiring wiring = new AudioBridgeManager.Wiring(null);

        assertTrue(bridge.admitGeneration(wiring, false, 5L));
        assertTrue("a diagnostic call is never stale", bridge.admitGeneration(wiring, true, 99L));
        assertEquals("...and never advances the gateway's mark", 5L, wiring.newestGeneration);

        assertTrue("so the gateway call can still re-wire after the test ends",
                bridge.admitGeneration(wiring, false, 5L));
    }

    /** A restarted service must not inherit a high-water mark that would refuse the next call. */
    @Test
    public void initializeForgetsTheHighWaterMarkAlongWithTheStaleWiring() {
        AudioBridgeManager.Wiring wiring = wiredTo(9L);

        newManager().initialize();

        assertFalse(wiring.active);
        assertEquals(AudioBridgeManager.NO_GENERATION, wiring.wiredGeneration);
        assertEquals(AudioBridgeManager.NO_GENERATION, wiring.newestGeneration);
    }
}
