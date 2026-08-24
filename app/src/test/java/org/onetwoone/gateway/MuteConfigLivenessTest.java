package org.onetwoone.gateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.os.Handler;

import org.junit.After;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A mute-configuration change must reach the live {@link DeviceMuteManager} — AUDIT
 * <b>H4</b> and Phase 2 plan §2.5.
 *
 * <p>Two defects meet here, and together they made the custom preset a no-op:
 *
 * <ul>
 *   <li>the operator's checkbox selection was stored under a key nothing read (H4), so
 *       {@code getAllMuteControls()} came back empty on exactly the devices that had been
 *       configured; and</li>
 *   <li>{@code currentPreset} was read once in the constructor and {@code savePreset} had no
 *       callers, so selecting a preset in either UI wrote preferences the live singleton
 *       never re-read — switching <em>to</em> {@code custom} did nothing until the process
 *       restarted.</li>
 * </ul>
 *
 * <p>These tests use a context-backed manager, so the preset and card come from
 * {@code GatewayConfig} exactly as they do in production.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class MuteConfigLivenessTest {

    private static final long TIMEOUT_S = 10L;

    /** In-memory ALSA mixer: a control that is not seeded cannot be read or written. */
    private static final class FakeMixer implements DeviceMuteManager.MixerBackend {
        final Map<String, String> enums = new ConcurrentHashMap<>();
        final Map<String, Integer> values = new ConcurrentHashMap<>();
        final List<String> writes = Collections.synchronizedList(new ArrayList<String>());

        @Override
        public boolean setEnum(int card, String control, String value) {
            writes.add("setEnum " + control + "=" + value);
            enums.put(control, value);
            return true;
        }

        @Override
        public boolean setValue(int card, String control, int value) {
            writes.add("setValue " + control + "=" + value);
            values.put(control, value);
            return true;
        }

        @Override
        public String getEnum(int card, String control) {
            String v = enums.get(control);
            return v == null ? "" : v;
        }

        @Override
        public int getValue(int card, String control) {
            Integer v = values.get(control);
            return v == null ? -1 : v;
        }

        List<String> writeLog() {
            synchronized (writes) {
                return new ArrayList<>(writes);
            }
        }
    }

    private Application app;
    private GatewayConfig config;
    private FakeMixer mixer;
    private DeviceMuteManager manager;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("gsm_audio_config", Context.MODE_PRIVATE).edit().clear().commit();
        app.getSharedPreferences("device_mute_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        try {
            java.lang.reflect.Field instance = GatewayConfig.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, null);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        GatewayConfig.init(app);
        config = GatewayConfig.getInstance();
        mixer = new FakeMixer();
    }

    @After
    public void tearDown() {
        if (manager != null) {
            manager.quitForTest();
            manager = null;
        }
    }

    /** Blocks until the MuteControls thread has drained everything queued so far. */
    private void awaitMuteIdle() throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        Handler h = new Handler(manager.muteLooperForTest());
        assertTrue("mute looper rejected the barrier", h.post(new Runnable() {
            @Override
            public void run() {
                done.countDown();
            }
        }));
        assertTrue("MuteControls thread stalled", done.await(TIMEOUT_S, TimeUnit.SECONDS));
    }

    private void muteOnce() throws InterruptedException {
        long lease = manager.newLease();
        manager.acquire(lease);
        awaitMuteIdle();
    }

    /**
     * The headline: a mute-control selection saved through {@code GatewayConfig} — which is
     * where both UIs now write, and where the legacy web-UI key is migrated to — is what the
     * custom preset mutes.
     */
    @Test
    public void customPresetMutesTheConfiguredControls() throws Exception {
        mixer.values.put("DEC1 Volume", 84);
        mixer.enums.put("EAR_S", "SWITCH");

        config.edit()
                .setMutePreset(DeviceMuteManager.PRESET_CUSTOM)
                .setMicMuteControls(new LinkedHashSet<>(Arrays.asList("DEC1 Volume", "EAR_S")))
                .apply();
        manager = DeviceMuteManager.forTesting(app, mixer);

        muteOnce();

        assertEquals(Arrays.asList("setValue DEC1 Volume=0", "setEnum EAR_S=ZERO"),
                mixer.writeLog());
        assertTrue(manager.isMuted());
    }

    /**
     * The staleness bug: the manager is built while the preset is a device preset, the
     * operator then switches to {@code custom}, and the very next call must honour it. Before
     * the fix this muted the {@code redmi_note_7} list — the preset the process started with.
     */
    @Test
    public void aPresetChangeTakesEffectOnTheNextCall() throws Exception {
        mixer.values.put("DEC1 Volume", 84);
        mixer.values.put("DEC2 Volume", 80);
        mixer.values.put("DEC3 Volume", 76);
        mixer.values.put("DEC4 Volume", 72);
        mixer.values.put("DEC5 Volume", 68);
        mixer.enums.put("EAR_S", "SWITCH");
        mixer.enums.put("SPK", "SWITCH");
        for (int i = 1; i <= 5; i++) {
            mixer.enums.put("DEC" + i + " MUX", "ADC" + i);
        }

        config.setMutePreset(DeviceMuteManager.PRESET_REDMI_NOTE_7);
        manager = DeviceMuteManager.forTesting(app, mixer);

        // The operator switches to custom, exactly as MainViewModel.selectMutePreset and
        // WebConfigServer.postConfig now do: a GatewayConfig write and nothing else.
        config.edit()
                .setMutePreset(DeviceMuteManager.PRESET_CUSTOM)
                .setMicMuteControls(new LinkedHashSet<>(Arrays.asList("DEC3 Volume")))
                .apply();

        muteOnce();

        assertEquals("the custom list, not the preset the process started with",
                Collections.singletonList("setValue DEC3 Volume=0"), mixer.writeLog());
    }

    /** The same liveness in the other direction: custom → a device preset. */
    @Test
    public void switchingBackToADevicePresetAlsoTakesEffect() throws Exception {
        mixer.values.put("DEC1 Volume", 84);
        mixer.enums.put("EAR_S", "SWITCH");
        mixer.enums.put("SPK", "SWITCH");

        config.edit()
                .setMutePreset(DeviceMuteManager.PRESET_CUSTOM)
                .setMicMuteControls(new LinkedHashSet<>(Arrays.asList("DEC1 Volume")))
                .apply();
        manager = DeviceMuteManager.forTesting(app, mixer);

        config.setMutePreset(DeviceMuteManager.PRESET_REDMI_NOTE_7);

        muteOnce();

        List<String> writes = mixer.writeLog();
        assertEquals("the preset's speaker controls come first", "setEnum EAR_S=ZERO", writes.get(0));
        assertTrue("the preset list must have been used", writes.contains("setEnum SPK=ZERO"));
    }

    /**
     * The card is refreshed the same way. It is read per acquire, so a card change also lands
     * on the next call rather than at the next process start.
     */
    @Test
    public void theSoundCardIsRefreshedToo() throws Exception {
        config.setMutePreset(DeviceMuteManager.PRESET_CUSTOM);
        config.setMicMuteControls(new LinkedHashSet<>(Arrays.asList("DEC1 Volume")));
        config.setAudioCard(0);
        manager = DeviceMuteManager.forTesting(app, mixer);

        config.setAudioCard(2);

        final List<Integer> cards = Collections.synchronizedList(new ArrayList<Integer>());
        DeviceMuteManager.MixerBackend recording = new DeviceMuteManager.MixerBackend() {
            @Override public boolean setEnum(int card, String c, String v) { cards.add(card); return true; }
            @Override public boolean setValue(int card, String c, int v) { cards.add(card); return true; }
            @Override public String getEnum(int card, String c) { return "SWITCH"; }
            @Override public int getValue(int card, String c) { return 84; }
        };
        manager.quitForTest();
        manager = DeviceMuteManager.forTesting(app, recording);
        config.setAudioCard(2);

        muteOnce();

        assertEquals(Collections.singletonList(2), new ArrayList<>(cards));
    }
}
