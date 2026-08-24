package org.onetwoone.gateway.audio;

import android.app.Application;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.onetwoone.gateway.config.GatewayConfig;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link QualcommAudioProfile}'s read path — AUDIT <b>B1e</b> (GW-20).
 *
 * <p>The profile is what saves the originals {@code teardownMixer} writes back, and until
 * this change both of its readers were dead. {@code getValue} ran
 * {@code su -c 'tinymix -D 0 get "DEC1 Volume"'} on devices with no {@code tinymix}
 * installed (exit 127, empty stdout, {@code readLine()} null) and returned its
 * {@code fallback}; {@code setupMixer} passed {@code VOLUME_READ_FALLBACK = 84}, so every
 * DEC control's "original" was the constant 84 with nothing ever read. {@code getEnum}
 * exec'd {@code filesDir/tinymix}, which the profile never extracts, and swallowed the
 * {@code IOException}.
 *
 * <p>84 is not a harmless sentinel: the measured resting value of {@code DEC* Volume} on
 * lavender is <b>0</b>, so teardown wrote a wrong value rather than an unverified one.
 *
 * <p>The bug is latent today only because {@code getAllMuteControls()} is empty by default
 * (H4's key mismatch). <b>GW-24 is what arms it</b>, which is why GW-20 lands first.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class QualcommMixerReadTest {

    private static final int CARD = 0;
    private static final String ROUTE = "MultiMedia1";

    private static final String DEC1 = "DEC1 Volume";
    private static final String DEC2 = "DEC2 Volume";
    private static final String DEC1_MUX = "DEC1 MUX";
    private static final String EAR_S = "EAR_S";

    /**
     * In-memory mixer that models a real one: a control that is not present cannot be read
     * and cannot be written, which is exactly how the native bridge behaves
     * ({@code mixer_get_ctl_by_name} returning NULL fails the read and the write alike).
     */
    private static final class FakeMixer implements MixerControls {
        final Map<String, Integer> values = new ConcurrentHashMap<>();
        final Map<String, String> enums = new ConcurrentHashMap<>();
        final List<String> writes = Collections.synchronizedList(new ArrayList<>());

        void seed(String control, int value) {
            values.put(control, value);
        }

        void seedEnum(String control, String value) {
            enums.put(control, value);
        }

        @Override
        public boolean setValue(int card, String control, int value) {
            writes.add(control + "=" + value);
            if (!values.containsKey(control)) {
                return false;   // absent control: the write fails too
            }
            values.put(control, value);
            return true;
        }

        @Override
        public boolean setEnum(int card, String control, String value) {
            writes.add(control + "=" + value);
            if (!enums.containsKey(control)) {
                return false;
            }
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
            return writes.contains(control + "=" + value);
        }

        boolean wroteAnythingTo(String control) {
            for (String w : writes) {
                if (w.startsWith(control + "=")) {
                    return true;
                }
            }
            return false;
        }
    }

    private FakeMixer mixer;
    private GatewayConfig config;
    private Application app;

    @Before
    public void setUp() throws Exception {
        ShadowLog.clear();
        app = RuntimeEnvironment.getApplication();
        try {
            Field instance = GatewayConfig.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, null);
        } catch (Exception e) {
            // first run
        }
        GatewayConfig.init(app);
        config = GatewayConfig.getInstance();
        config.setMultimediaRoute(ROUTE);
        config.setManualMuteControls("");
        mixer = new FakeMixer();
    }

    private QualcommAudioProfile profileFor(String... muteControls) {
        config.setMicMuteControls(new HashSet<>(Arrays.asList(muteControls)));
        return new QualcommAudioProfile(app, config, mixer);
    }

    // ------------------------------------------------------------------
    // The backend itself
    // ------------------------------------------------------------------

    /**
     * The migration, asserted structurally: the production backend is the JNI bridge, not a
     * private tinymix shell-out. If a reader is ever reintroduced that spawns a process,
     * this is the test that says no.
     */
    @Test
    public void productionBackendIsTheNativeBridge() throws Exception {
        config.setMicMuteControls(new HashSet<String>());
        QualcommAudioProfile profile = new QualcommAudioProfile(app, config);

        Field mixerField = QualcommAudioProfile.class.getDeclaredField("mixer");
        mixerField.setAccessible(true);
        assertSame("Qualcomm must read and write through MixerControls.NATIVE",
                MixerControls.NATIVE, mixerField.get(profile));
    }

    /**
     * {@code MixerControls.NATIVE.getEnum} used to be a hardcoded {@code return ""}, with a
     * javadoc claiming the native bridge had no ENUM getter. It has had one since B1c, and
     * Qualcomm's saved ENUM originals now depend on it.
     *
     * <p>There is no {@code libgsm_audio.so} on the JVM, so reaching the native method is
     * observable exactly as an {@link UnsatisfiedLinkError} naming it. A silent {@code ""}
     * means the delegation was removed.
     */
    @Test
    public void nativeEnumGetterIsActuallyCalled() {
        try {
            String value = MixerControls.NATIVE.getEnum(CARD, DEC1_MUX);
            fail("NATIVE.getEnum returned \"" + value + "\" without reaching JNI - the ENUM "
                    + "read is stubbed again (AUDIT B1c/B1e)");
        } catch (UnsatisfiedLinkError expected) {
            assertTrue("reached the wrong native method: " + expected.getMessage(),
                    String.valueOf(expected.getMessage()).contains("getMixerControlEnum"));
        }
    }

    // ------------------------------------------------------------------
    // No fabricated originals
    // ------------------------------------------------------------------

    /**
     * The headline: a control whose original cannot be read is left alone, and no value is
     * invented for it. The old code recorded 84 and muted anyway, so teardown wrote 84 into
     * a control whose real resting value is 0.
     */
    @Test
    public void unreadableVolumeIsNeitherMutedNorFabricated() {
        QualcommAudioProfile profile = profileFor(DEC1);   // never seeded => unreadable

        profile.setupMixer(CARD);
        profile.teardownMixer(CARD);

        assertFalse("an unreadable control must not be muted - it could not be restored",
                mixer.wroteAnythingTo(DEC1));
        assertFalse("84 must never be written again (AUDIT B1e)", mixer.wrote(DEC1, 84));
        assertNull(mixer.values.get(DEC1));
    }

    @Test
    public void unreadableEnumIsNeitherMutedNorFabricated() {
        QualcommAudioProfile profile = profileFor(DEC1_MUX, EAR_S);

        profile.setupMixer(CARD);
        profile.teardownMixer(CARD);

        assertFalse(mixer.wroteAnythingTo(DEC1_MUX));
        assertFalse(mixer.wroteAnythingTo(EAR_S));
    }

    /**
     * The value that made B1e damaging rather than merely wrong. 0 is a legitimate reading
     * on lavender, and it must round-trip as a reading — not be mistaken for a failure.
     */
    @Test
    public void zeroIsARealOriginalAndIsRestoredAsOne() {
        mixer.seed(DEC1, 0);
        QualcommAudioProfile profile = profileFor(DEC1);

        profile.setupMixer(CARD);
        assertEquals("setup must still mute a control it could read",
                Integer.valueOf(0), mixer.values.get(DEC1));

        profile.teardownMixer(CARD);
        assertEquals(Integer.valueOf(0), mixer.values.get(DEC1));
        assertFalse("teardown must not invent 84", mixer.wrote(DEC1, 84));
    }

    @Test
    public void readableOriginalsAreSavedAndRestoredExactly() {
        mixer.seed(DEC1, 84);
        mixer.seed(DEC2, 0);
        mixer.seedEnum(DEC1_MUX, "ADC1");
        mixer.seedEnum(EAR_S, "SWITCH_ON");
        QualcommAudioProfile profile = profileFor(DEC1, DEC2, DEC1_MUX, EAR_S);

        profile.setupMixer(CARD);
        assertEquals(Integer.valueOf(0), mixer.values.get(DEC1));
        assertEquals(Integer.valueOf(0), mixer.values.get(DEC2));
        assertEquals("ZERO", mixer.enums.get(DEC1_MUX));
        assertEquals("ZERO", mixer.enums.get(EAR_S));

        profile.teardownMixer(CARD);
        assertEquals(Integer.valueOf(84), mixer.values.get(DEC1));
        assertEquals(Integer.valueOf(0), mixer.values.get(DEC2));
        assertEquals("ADC1", mixer.enums.get(DEC1_MUX));
        assertEquals("SWITCH_ON", mixer.enums.get(EAR_S));
    }

    /** A mixed list: the readable controls are still handled, the unreadable one skipped. */
    @Test
    public void oneUnreadableControlDoesNotStopTheRest() {
        mixer.seed(DEC1, 84);
        mixer.seedEnum(EAR_S, "SWITCH_ON");
        // DEC2 and DEC1_MUX are absent on this device.
        QualcommAudioProfile profile = profileFor(DEC1, DEC2, DEC1_MUX, EAR_S);

        profile.setupMixer(CARD);
        assertEquals(Integer.valueOf(0), mixer.values.get(DEC1));
        assertEquals("ZERO", mixer.enums.get(EAR_S));
        assertFalse(mixer.wroteAnythingTo(DEC2));
        assertFalse(mixer.wroteAnythingTo(DEC1_MUX));

        profile.teardownMixer(CARD);
        assertEquals(Integer.valueOf(84), mixer.values.get(DEC1));
        assertEquals("SWITCH_ON", mixer.enums.get(EAR_S));
    }

    /**
     * A refused mute still records the original (the read succeeded), so teardown puts it
     * back — harmless, and strictly better than dropping a control that may have been
     * written after all. Guards against "skip on unreadable" being widened into "skip on
     * any failure", which would reintroduce B1c from the other side.
     */
    @Test
    public void aReadableControlIsAlwaysRestorable() {
        mixer.seed(DEC1, 84);
        QualcommAudioProfile profile = profileFor(DEC1);

        profile.setupMixer(CARD);
        profile.teardownMixer(CARD);

        assertTrue("restore write missing", mixer.wrote(DEC1, 84));
        assertEquals(Integer.valueOf(84), mixer.values.get(DEC1));
    }
}
