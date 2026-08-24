package org.onetwoone.gateway.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.onetwoone.gateway.config.GatewayConfig;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link QualcommAudioProfile} re-reads its configuration per call — AUDIT <b>H4b</b>
 * (GW-24).
 *
 * <p>The capture/playback devices, the multimedia route and the mic-mute control list were
 * copied into final fields in the constructor. The profile is built once by
 * {@code GsmAudioPort}'s constructor, whose port lives in the process-scoped
 * {@code AudioBridgeManager.Wiring} holder and is never replaced, so changing any of them had
 * no effect until the process restarted — while the UI and {@code reloadConfig} both reported
 * the change as applied.
 *
 * <p>What must <em>not</em> change with it, and is what these tests really guard: teardown
 * and enforce use the values {@code setupMixer} read, not the current ones. Restoring against
 * a list the operator edited mid-call would leave a muted control with nobody left to unmute
 * it — the shape of AUDIT B1c/B2.
 *
 * <p>Gains are deliberately absent: {@code AudioBridgeManager} re-reads
 * {@code getTxGain()/getRxGain()} on every {@code startBridge} already. So is the sound card,
 * which {@code GsmAudioPort} snapshots in its own constructor and passes in — that one still
 * needs a restart.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class QualcommConfigReloadTest {

    private static final int CARD = 0;

    /** In-memory mixer: an absent control cannot be read and cannot be written. */
    private static final class FakeMixer implements MixerControls {
        final Map<String, Integer> values = new ConcurrentHashMap<>();
        final Map<String, String> enums = new ConcurrentHashMap<>();
        final List<String> writes = Collections.synchronizedList(new ArrayList<String>());

        @Override
        public boolean setValue(int card, String control, int value) {
            writes.add(control + "=" + value);
            if (!values.containsKey(control)) return false;
            values.put(control, value);
            return true;
        }

        @Override
        public boolean setEnum(int card, String control, String value) {
            writes.add(control + "=" + value);
            if (!enums.containsKey(control)) return false;
            enums.put(control, value);
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

        boolean wrote(String control, Object value) {
            synchronized (writes) {
                return writes.contains(control + "=" + value);
            }
        }

        boolean wroteAnythingTo(String control) {
            synchronized (writes) {
                for (String w : writes) {
                    if (w.startsWith(control + "=")) return true;
                }
            }
            return false;
        }

        void clearLog() {
            writes.clear();
        }
    }

    private Application app;
    private GatewayConfig config;
    private FakeMixer mixer;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        try {
            java.lang.reflect.Field instance = GatewayConfig.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, null);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        GatewayConfig.init(app);
        config = GatewayConfig.getInstance();
        config.setMultimediaRoute("MultiMedia1");
        config.setManualMuteControls("");
        mixer = new FakeMixer();
    }

    private void setControls(String... controls) {
        config.setMicMuteControls(new LinkedHashSet<>(Arrays.asList(controls)));
    }

    /**
     * The mute list is read at setup, not at construction: a control selected after the
     * profile was built is muted on the next call.
     */
    @Test
    public void muteControlListIsReReadAtEverySetup() {
        setControls("DEC1 Volume");
        mixer.values.put("DEC1 Volume", 84);
        mixer.values.put("DEC2 Volume", 80);
        QualcommAudioProfile profile = new QualcommAudioProfile(app, config, mixer);

        setControls("DEC2 Volume");

        profile.setupMixer(CARD);

        assertTrue("the newly selected control must be muted", mixer.wrote("DEC2 Volume", 0));
        assertFalse("the deselected control must be left alone",
                mixer.wroteAnythingTo("DEC1 Volume"));
    }

    /** Same for the route: the whole VOC_REC/Incall_Music patch follows the current config. */
    @Test
    public void routeIsReReadAtEverySetup() {
        setControls();
        mixer.values.put("MultiMedia2 Mixer VOC_REC_DL", 0);
        QualcommAudioProfile profile = new QualcommAudioProfile(app, config, mixer);

        config.setMultimediaRoute("MultiMedia2");
        profile.setupMixer(CARD);

        assertTrue(mixer.wrote("MultiMedia2 Mixer VOC_REC_DL", 1));
        assertTrue(mixer.wrote("Incall_Music Audio Mixer MultiMedia2", 1));
        assertFalse("the route the profile was built with must not be touched",
                mixer.wroteAnythingTo("MultiMedia1 Mixer VOC_REC_DL"));
    }

    /**
     * The safety property. A config edit between setup and teardown must not redirect the
     * restore: teardown puts back exactly what setup muted, and un-routes exactly what setup
     * routed.
     */
    @Test
    public void teardownFollowsTheSessionThatSetUpNotTheCurrentConfig() {
        setControls("DEC1 Volume");
        mixer.values.put("DEC1 Volume", 84);
        mixer.values.put("DEC2 Volume", 80);
        mixer.values.put("MultiMedia1 Mixer VOC_REC_DL", 0);
        mixer.values.put("MultiMedia2 Mixer VOC_REC_DL", 0);
        QualcommAudioProfile profile = new QualcommAudioProfile(app, config, mixer);

        profile.setupMixer(CARD);
        assertEquals("muted during the call", Integer.valueOf(0), mixer.values.get("DEC1 Volume"));

        // The operator edits the audio settings while the call is up.
        setControls("DEC2 Volume");
        config.setMultimediaRoute("MultiMedia2");
        mixer.clearLog();

        profile.teardownMixer(CARD);

        assertEquals("the muted control must be restored to its own original",
                Integer.valueOf(84), mixer.values.get("DEC1 Volume"));
        assertFalse("a control this session never muted must not be written",
                mixer.wroteAnythingTo("DEC2 Volume"));
        assertTrue("the route that was patched must be the route un-patched",
                mixer.wrote("MultiMedia1 Mixer VOC_REC_DL", 0));
        assertFalse("a route this session never patched must not be un-patched",
                mixer.wroteAnythingTo("MultiMedia2 Mixer VOC_REC_DL"));
    }

    /** enforceMixer re-asserts the live session's list, not the edited config's. */
    @Test
    public void enforceFollowsTheSessionThatSetUp() {
        setControls("DEC1 Volume");
        mixer.values.put("DEC1 Volume", 84);
        mixer.values.put("DEC2 Volume", 80);
        QualcommAudioProfile profile = new QualcommAudioProfile(app, config, mixer);

        profile.setupMixer(CARD);
        setControls("DEC2 Volume");
        mixer.clearLog();

        profile.enforceMixer(CARD);

        assertTrue(mixer.wrote("DEC1 Volume", 0));
        assertFalse(mixer.wroteAnythingTo("DEC2 Volume"));
    }

    /**
     * The PCM device numbers follow the same rule: current config until a call sets up, then
     * pinned for that call — {@code GsmAudioPort} queries them after {@code setupMixer} and
     * may retry the open several times, and every attempt must use the same devices.
     */
    @Test
    public void deviceNumbersAreCurrentBeforeSetupAndPinnedAfter() {
        setControls();
        config.setCaptureDevice(1);
        config.setPlaybackDevice(2);
        QualcommAudioProfile profile = new QualcommAudioProfile(app, config, mixer);

        assertEquals(1, profile.captureDevice());
        assertEquals(2, profile.playbackDevice());

        config.setCaptureDevice(3);
        assertEquals("no call in flight - the current value is the right one",
                3, profile.captureDevice());

        profile.setupMixer(CARD);
        config.setCaptureDevice(9);
        assertEquals("pinned for the duration of the call", 3, profile.captureDevice());

        profile.teardownMixer(CARD);
        profile.setupMixer(CARD);
        assertEquals("and picked up at the next call", 9, profile.captureDevice());
    }

    /**
     * GW-20's skip semantics must survive: a control whose original cannot be read is not
     * muted and no value is invented for it. This is the loop GW-24 arms — before the key
     * unification the list was always empty, so it had never run.
     */
    @Test
    public void unreadableControlIsStillSkippedNotMuted() {
        setControls("DEC1 Volume", "DEC9 Volume");   // DEC9 is not present on this mixer
        mixer.values.put("DEC1 Volume", 84);

        QualcommAudioProfile profile = new QualcommAudioProfile(app, config, mixer);
        profile.setupMixer(CARD);

        assertTrue(mixer.wrote("DEC1 Volume", 0));
        assertFalse("an unreadable control must be left alone",
                mixer.wroteAnythingTo("DEC9 Volume"));

        mixer.clearLog();
        profile.teardownMixer(CARD);

        assertTrue(mixer.wrote("DEC1 Volume", 84));
        assertFalse("and must never be handed a fabricated original",
                mixer.wroteAnythingTo("DEC9 Volume"));
    }

    /**
     * The manual list and the checkbox list are one list to the mixer, and both are read per
     * call.
     */
    @Test
    public void manualControlsAreIncludedAndAlsoReRead() {
        setControls("DEC1 Volume");
        config.setManualMuteControls("EAR_S");
        mixer.values.put("DEC1 Volume", 84);
        mixer.enums.put("EAR_S", "SWITCH");
        mixer.enums.put("SPK", "SWITCH");

        QualcommAudioProfile profile = new QualcommAudioProfile(app, config, mixer);
        config.setManualMuteControls("EAR_S,SPK");
        profile.setupMixer(CARD);

        assertTrue(mixer.wrote("DEC1 Volume", 0));
        assertTrue(mixer.wrote("EAR_S", "ZERO"));
        assertTrue("a manual control added after construction must be muted too",
                mixer.wrote("SPK", "ZERO"));
        assertEquals(new HashSet<>(Arrays.asList("DEC1 Volume", "EAR_S", "SPK")),
                new HashSet<>(config.getAllMuteControls()));
    }
}
