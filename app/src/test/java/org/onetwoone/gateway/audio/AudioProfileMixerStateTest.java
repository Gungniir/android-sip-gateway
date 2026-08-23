package org.onetwoone.gateway.audio;

import android.app.Application;
import android.util.Log;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.onetwoone.gateway.config.GatewayConfig;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Saved-mixer-state tests for both SoC {@link AudioProfile}s — AUDIT B2 / GW-04.
 *
 * The bug these guard against: the originals used to live in plain mutable maps
 * written by {@code setupMixer} on the GsmAudioOpen thread and read+cleared by
 * {@code teardownMixer} on main / a pjsua worker. Back-to-back calls had teardown
 * reading a map the next setup was clearing, so the local mic mute was never
 * lifted and the phone had no microphone until reboot.
 *
 * {@link #qualcommSurvivesConcurrentSetupAndTeardown()} and
 * {@link #mediaTekSurvivesConcurrentSetupAndTeardown()} are the regression tests:
 * they hammer setup and teardown from two threads and require every control to be
 * back at its original value afterwards.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class AudioProfileMixerStateTest {

    private static final int CARD = 0;
    private static final int ITERATIONS = 1000;

    private static final String ROUTE = "MultiMedia1";
    private static final String VOC_REC = ROUTE + " Mixer VOC_REC_DL";
    private static final String INCALL_MUSIC = "Incall_Music Audio Mixer " + ROUTE;
    private static final String INCALL_MUSIC_2 = "Incall_Music_2 Audio Mixer " + ROUTE;

    private static final String DEC1 = "DEC1 Volume";
    private static final String DEC2 = "DEC2 Volume";
    private static final String DEC3_MUX = "DEC3 MUX";
    private static final String EAR_S = "EAR_S";
    private static final String SPK = "SPK";

    /** In-memory ALSA mixer. Thread-safe, so a failure is the profile's fault. */
    private static final class FakeMixer implements MixerControls {
        final Map<String, Integer> values = new ConcurrentHashMap<>();
        final Map<String, String> enums = new ConcurrentHashMap<>();
        /** Ordered write log; only read single-threaded. */
        final List<String> writes = Collections.synchronizedList(new ArrayList<>());

        void seed(String control, int value) {
            values.put(control, value);
        }

        void seedEnum(String control, String value) {
            enums.put(control, value);
        }

        @Override
        public boolean setValue(int card, String control, int value) {
            assertEquals("wrong card", CARD, card);
            values.put(control, value);
            writes.add(control + "=" + value);
            return true;
        }

        @Override
        public boolean setEnum(int card, String control, String value) {
            assertEquals("wrong card", CARD, card);
            enums.put(control, value);
            writes.add(control + "=" + value);
            return true;
        }

        @Override
        public int getValue(int card, String control, int fallback) {
            Integer v = values.get(control);
            return v == null ? fallback : v;
        }

        @Override
        public String getEnum(int card, String control) {
            String v = enums.get(control);
            return v == null ? "" : v;
        }
    }

    private FakeMixer mixer;
    private GatewayConfig config;
    private Application app;

    @Before
    public void setUp() {
        ShadowLog.clear();
        app = RuntimeEnvironment.getApplication();
        try {
            java.lang.reflect.Field instance = GatewayConfig.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, null);
        } catch (Exception e) {
            // Ignore - first run.
        }
        GatewayConfig.init(app);
        config = GatewayConfig.getInstance();
        config.setMultimediaRoute(ROUTE);
        config.setMicMuteControls(new HashSet<>(Arrays.asList(DEC1, DEC2, DEC3_MUX, EAR_S, SPK)));
        config.setManualMuteControls("");

        mixer = new FakeMixer();
    }

    // ---------------------------------------------------------------- helpers

    /** Idle values the device is expected to be left in. */
    private Map<String, Integer> qualcommOriginalInts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put(VOC_REC, 0);
        m.put(INCALL_MUSIC, 0);
        m.put(INCALL_MUSIC_2, 0);
        m.put(DEC1, 84);
        m.put(DEC2, 70);
        return m;
    }

    private Map<String, String> qualcommOriginalEnums() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(DEC3_MUX, "DMIC1");
        m.put(EAR_S, "SWITCH_ON");
        m.put(SPK, "SWITCH_ON");
        return m;
    }

    private QualcommAudioProfile newQualcomm() {
        for (Map.Entry<String, Integer> e : qualcommOriginalInts().entrySet()) {
            mixer.seed(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, String> e : qualcommOriginalEnums().entrySet()) {
            mixer.seedEnum(e.getKey(), e.getValue());
        }
        return new QualcommAudioProfile(app, config, mixer);
    }

    private Map<String, Integer> mediaTekOriginals() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("UL2_CH1 PCM_2_CAP_CH1", 0);
        m.put("UL2_CH2 PCM_2_CAP_CH1", 0);
        m.put("PCM_2_PB_CH1 DL2_CH1", 0);
        m.put("PCM_2_PB_CH2 DL2_CH2", 0);
        m.put("PCM_2_PB_CH1 ADDA_UL_CH1", 1);
        m.put("PCM_2_PB_CH2 ADDA_UL_CH2", 1);
        return m;
    }

    private MediaTekAudioProfile newMediaTek() {
        for (Map.Entry<String, Integer> e : mediaTekOriginals().entrySet()) {
            mixer.seed(e.getKey(), e.getValue());
        }
        return new MediaTekAudioProfile(app, mixer);
    }

    private void assertAtOriginals(Map<String, Integer> ints, Map<String, String> enums) {
        for (Map.Entry<String, Integer> e : ints.entrySet()) {
            assertEquals("control not restored: " + e.getKey(),
                    e.getValue(), mixer.values.get(e.getKey()));
        }
        for (Map.Entry<String, String> e : enums.entrySet()) {
            assertEquals("enum control not restored: " + e.getKey(),
                    e.getValue(), mixer.enums.get(e.getKey()));
        }
    }

    private static boolean loggedErrorFor(String tag) {
        for (ShadowLog.LogItem item : ShadowLog.getLogsForTag(tag)) {
            if (item.type == Log.ERROR) {
                return true;
            }
        }
        return false;
    }

    private static String describe(List<Throwable> failures) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        for (Throwable t : failures) {
            t.printStackTrace(pw);
        }
        return sw.toString();
    }

    // ------------------------------------------------- single-threaded basics

    @Test
    public void qualcommSetupMutesAndTeardownRestores() {
        QualcommAudioProfile profile = newQualcomm();

        profile.setupMixer(CARD);

        assertEquals(Integer.valueOf(1), mixer.values.get(VOC_REC));
        assertEquals(Integer.valueOf(1), mixer.values.get(INCALL_MUSIC));
        assertEquals(Integer.valueOf(1), mixer.values.get(INCALL_MUSIC_2));
        assertEquals(Integer.valueOf(0), mixer.values.get(DEC1));
        assertEquals(Integer.valueOf(0), mixer.values.get(DEC2));
        assertEquals("ZERO", mixer.enums.get(DEC3_MUX));
        assertEquals("ZERO", mixer.enums.get(EAR_S));
        assertEquals("ZERO", mixer.enums.get(SPK));

        profile.teardownMixer(CARD);

        assertAtOriginals(qualcommOriginalInts(), qualcommOriginalEnums());
    }

    /**
     * The routing controls must be written first and in this exact order; the mute
     * controls follow, in whatever order the config set yields.
     */
    @Test
    public void qualcommPreservesRoutingWriteOrder() {
        QualcommAudioProfile profile = newQualcomm();

        profile.setupMixer(CARD);
        assertEquals(Arrays.asList(VOC_REC + "=1", INCALL_MUSIC + "=1", INCALL_MUSIC_2 + "=1"),
                mixer.writes.subList(0, 3));
        assertEquals(new HashSet<>(Arrays.asList(
                        DEC1 + "=0", DEC2 + "=0",
                        DEC3_MUX + "=ZERO", EAR_S + "=ZERO", SPK + "=ZERO")),
                new HashSet<>(mixer.writes.subList(3, mixer.writes.size())));

        mixer.writes.clear();
        profile.teardownMixer(CARD);
        assertEquals(Arrays.asList(VOC_REC + "=0", INCALL_MUSIC + "=0", INCALL_MUSIC_2 + "=0"),
                mixer.writes.subList(0, 3));
        assertEquals(new HashSet<>(Arrays.asList(
                        DEC1 + "=84", DEC2 + "=70",
                        DEC3_MUX + "=DMIC1", EAR_S + "=SWITCH_ON", SPK + "=SWITCH_ON")),
                new HashSet<>(mixer.writes.subList(3, mixer.writes.size())));
    }

    /** The PCM_2 crossbar order is load-bearing; assert the sequence verbatim. */
    @Test
    public void mediaTekPreservesExactWriteOrder() {
        MediaTekAudioProfile profile = newMediaTek();

        profile.setupMixer(CARD);
        assertEquals(Arrays.asList(
                "UL2_CH1 PCM_2_CAP_CH1=1",
                "UL2_CH2 PCM_2_CAP_CH1=1",
                "PCM_2_PB_CH1 DL2_CH1=1",
                "PCM_2_PB_CH2 DL2_CH2=1",
                "PCM_2_PB_CH1 ADDA_UL_CH1=0",
                "PCM_2_PB_CH2 ADDA_UL_CH2=0"), mixer.writes);

        mixer.writes.clear();
        profile.teardownMixer(CARD);
        assertEquals(Arrays.asList(
                "UL2_CH1 PCM_2_CAP_CH1=0",
                "UL2_CH2 PCM_2_CAP_CH1=0",
                "PCM_2_PB_CH1 DL2_CH1=0",
                "PCM_2_PB_CH2 DL2_CH2=0",
                "PCM_2_PB_CH1 ADDA_UL_CH1=1",
                "PCM_2_PB_CH2 ADDA_UL_CH2=1"), mixer.writes);

        assertAtOriginals(mediaTekOriginals(), Collections.<String, String>emptyMap());
    }

    // ------------------------------------------------------------ idempotence

    @Test
    public void teardownWithNothingSavedIsANoOp() {
        QualcommAudioProfile qualcomm = newQualcomm();
        qualcomm.teardownMixer(CARD);
        assertTrue("teardown with nothing saved must not write: " + mixer.writes,
                mixer.writes.isEmpty());

        MediaTekAudioProfile mediaTek = newMediaTek();
        mediaTek.teardownMixer(CARD);
        assertTrue("teardown with nothing saved must not write: " + mixer.writes,
                mixer.writes.isEmpty());
    }

    @Test
    public void repeatedTeardownIsHarmless() {
        QualcommAudioProfile profile = newQualcomm();
        profile.setupMixer(CARD);
        profile.teardownMixer(CARD);

        mixer.writes.clear();
        profile.teardownMixer(CARD);
        profile.teardownMixer(CARD);
        assertTrue("second teardown must not write: " + mixer.writes, mixer.writes.isEmpty());
        assertAtOriginals(qualcommOriginalInts(), qualcommOriginalEnums());
    }

    @Test
    public void qualcommSetupOverLiveSnapshotRestoresItFirstAndLogsAnError() {
        QualcommAudioProfile profile = newQualcomm();

        profile.setupMixer(CARD);
        ShadowLog.clear();
        mixer.writes.clear();

        // Second setup with no intervening teardown: the previous originals must be
        // written back before the new ones are read, or they are lost forever.
        profile.setupMixer(CARD);

        assertTrue("expected an error log for setup over a live snapshot",
                loggedErrorFor("QualcommAudioProfile"));
        assertTrue("the stale originals must be restored before the new setup writes: "
                        + mixer.writes,
                mixer.writes.indexOf(DEC1 + "=84") >= 0
                        && mixer.writes.indexOf(DEC1 + "=84") < mixer.writes.indexOf(VOC_REC + "=1"));

        // And one teardown must still put the device fully back.
        profile.teardownMixer(CARD);
        assertAtOriginals(qualcommOriginalInts(), qualcommOriginalEnums());
    }

    @Test
    public void mediaTekSetupOverLiveSnapshotRestoresItFirstAndLogsAnError() {
        MediaTekAudioProfile profile = newMediaTek();

        profile.setupMixer(CARD);
        ShadowLog.clear();
        mixer.writes.clear();

        profile.setupMixer(CARD);

        assertTrue("expected an error log for setup over a live snapshot",
                loggedErrorFor("MediaTekAudioProfile"));
        assertEquals("the stale ADDA_UL original must be written back first",
                "PCM_2_PB_CH1 ADDA_UL_CH1=1", mixer.writes.get(4));

        profile.teardownMixer(CARD);
        assertAtOriginals(mediaTekOriginals(), Collections.<String, String>emptyMap());
    }

    /**
     * enforceMixer must read only the static control lists. If it saved or cleared
     * originals, the teardown after it would restore the mute values instead.
     */
    @Test
    public void enforceMixerNeverTouchesSavedState() {
        QualcommAudioProfile qualcomm = newQualcomm();
        qualcomm.enforceMixer(CARD);          // before setup: must not create state
        mixer.writes.clear();
        qualcomm.teardownMixer(CARD);
        assertTrue("enforce must not create restorable state: " + mixer.writes,
                mixer.writes.isEmpty());

        // Re-seed: the pre-setup enforce above muted the controls for real.
        mixer = new FakeMixer();
        qualcomm = newQualcomm();
        qualcomm.setupMixer(CARD);
        for (int i = 0; i < 5; i++) {
            qualcomm.enforceMixer(CARD);
        }
        qualcomm.teardownMixer(CARD);
        assertAtOriginals(qualcommOriginalInts(), qualcommOriginalEnums());

        mixer = new FakeMixer();
        MediaTekAudioProfile mediaTek = newMediaTek();
        mediaTek.setupMixer(CARD);
        for (int i = 0; i < 5; i++) {
            mediaTek.enforceMixer(CARD);
        }
        mediaTek.teardownMixer(CARD);
        assertAtOriginals(mediaTekOriginals(), Collections.<String, String>emptyMap());
    }

    // ------------------------------------------------------ the AUDIT B2 test

    @Test
    public void qualcommSurvivesConcurrentSetupAndTeardown() throws Exception {
        QualcommAudioProfile profile = newQualcomm();
        hammer(profile);
        assertAtOriginals(qualcommOriginalInts(), qualcommOriginalEnums());
    }

    @Test
    public void mediaTekSurvivesConcurrentSetupAndTeardown() throws Exception {
        MediaTekAudioProfile profile = newMediaTek();
        hammer(profile);
        assertAtOriginals(mediaTekOriginals(), Collections.<String, String>emptyMap());
    }

    /**
     * Reproduces the back-to-back-call race: setupMixer on the GsmAudioOpen thread
     * against teardownMixer on a pjsua worker, {@value #ITERATIONS} times each.
     * Neither may throw, and one final teardown must leave every control at its
     * original value.
     */
    private void hammer(AudioProfile profile) throws Exception {
        final List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch start = new CountDownLatch(1);

        Thread setup = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < ITERATIONS; i++) {
                    profile.setupMixer(CARD);
                    Thread.yield();
                }
            } catch (Throwable t) {
                failures.add(t);
            }
        }, "GsmAudioOpen");

        Thread teardown = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < ITERATIONS; i++) {
                    profile.teardownMixer(CARD);
                    Thread.yield();
                }
            } catch (Throwable t) {
                failures.add(t);
            }
        }, "pjsua-worker");

        setup.start();
        teardown.start();
        start.countDown();
        setup.join(60_000);
        teardown.join(60_000);

        assertFalse("setup thread hung", setup.isAlive());
        assertFalse("teardown thread hung", teardown.isAlive());
        if (!failures.isEmpty()) {
            fail("setup/teardown threw under concurrency:\n" + describe(failures));
        }

        // Whatever the interleaving ended on, the call is over: one teardown must
        // put the device back. Anything left mismatched here is a phone with no
        // microphone.
        profile.teardownMixer(CARD);
    }

    /** Sanity: the fake really does track state, so the assertions above mean something. */
    @Test
    public void fakeMixerTracksWrites() {
        FakeMixer m = new FakeMixer();
        m.seed("X", 3);
        assertEquals(3, m.getValue(CARD, "X", 9));
        assertEquals(9, m.getValue(CARD, "missing", 9));
        m.setValue(CARD, "X", 7);
        assertEquals(7, m.getValue(CARD, "X", 9));
        assertEquals("", m.getEnum(CARD, "missing"));
        assertEquals(new ArrayList<>(Collections.singletonList("X=7")), new ArrayList<>(m.writes));
        assertTrue(new HashMap<>(m.values).containsKey("X"));
    }
}
