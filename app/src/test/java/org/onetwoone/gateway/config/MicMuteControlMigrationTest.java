package org.onetwoone.gateway.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The mute-control key migration — AUDIT <b>H4</b> (GW-24).
 *
 * <p>{@code WebConfigServer} wrote the operator's checkbox selection to
 * {@code "mic_mute_controls"} as a {@code StringSet}; {@code GatewayConfig} read
 * {@code "mic_mute_decs"} as a comma-separated {@code String}. Nothing read the web
 * server's key, so the custom preset muted nothing and the local microphone stayed live on
 * the GSM leg. Unifying the two naively is worse than the bug: {@code getString} on a key
 * holding a {@code StringSet} throws {@link ClassCastException} at config-load time, i.e.
 * inside {@code PjsipSipService.onCreate}, and a real device can hold <em>both</em> keys
 * with mismatched types.
 *
 * <p>The migration therefore runs inside {@link GatewayConfig#init(Context)} — before the
 * instance is published, so no reader can see un-migrated preferences — reads through
 * {@code getAll()} and type-checks, and removes the legacy key only after reading the new
 * value back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class MicMuteControlMigrationTest {

    private static final String CANONICAL = GatewayConfig.KEY_MIC_MUTE_CONTROLS;      // mic_mute_decs
    private static final String LEGACY = GatewayConfig.KEY_MIC_MUTE_CONTROLS_LEGACY;  // mic_mute_controls

    private Application app;
    private SharedPreferences audioPrefs;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        audioPrefs = app.getSharedPreferences("gsm_audio_config", Context.MODE_PRIVATE);
        audioPrefs.edit().clear().commit();
        resetSingleton();
    }

    private static void resetSingleton() {
        try {
            java.lang.reflect.Field instance = GatewayConfig.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, null);
        } catch (Exception e) {
            throw new AssertionError("cannot reset the GatewayConfig singleton", e);
        }
    }

    /** Re-run the whole init path, which is where the migration lives. */
    private GatewayConfig initConfig() {
        resetSingleton();
        GatewayConfig.init(app);
        return GatewayConfig.getInstance();
    }

    private Object stored(String key) {
        return audioPrefs.getAll().get(key);
    }

    // ------------------------------------------------------------------
    // The bug itself
    // ------------------------------------------------------------------

    /**
     * The headline case: a device where the operator only ever used the web interface. The
     * selection lives under the legacy key as a StringSet and has never reached the mixer.
     */
    @Test
    public void legacyStringSetIsAdoptedAndTheOldKeyRemoved() {
        audioPrefs.edit()
                .putStringSet(LEGACY, new LinkedHashSet<>(Arrays.asList("DEC1 Volume", "DEC1 MUX")))
                .commit();

        GatewayConfig config = initConfig();

        assertEquals("the web UI's selection must now be what the mixer reads",
                new HashSet<>(Arrays.asList("DEC1 Volume", "DEC1 MUX")),
                new HashSet<>(config.getMicMuteControls()));
        assertTrue("the canonical key must hold a String",
                stored(CANONICAL) instanceof String);
        assertNull("the legacy key must be gone after a successful read-back",
                stored(LEGACY));
    }

    /**
     * What {@code QualcommAudioProfile.setupMixer} and {@code DeviceMuteManager} actually
     * call. Before the migration this returned an empty set on exactly the devices whose
     * operator had configured it, which is why the mute loop had never executed.
     */
    @Test
    public void migratedControlsReachGetAllMuteControls() {
        audioPrefs.edit()
                .putStringSet(LEGACY, new LinkedHashSet<>(Arrays.asList("DEC1 Volume")))
                .putString("manual_mute_controls", "EAR_S, SPK")
                .commit();

        Set<String> all = initConfig().getAllMuteControls();

        assertEquals(new HashSet<>(Arrays.asList("DEC1 Volume", "EAR_S", "SPK")),
                new HashSet<>(all));
    }

    // ------------------------------------------------------------------
    // Mismatched types — the case that would otherwise throw
    // ------------------------------------------------------------------

    /**
     * Both keys present, with different types, which is what a device that used both UIs
     * holds. The in-app list wins: it is the one that has actually been reaching the mixer.
     */
    @Test
    public void bothKeysPresentWithMismatchedTypes() {
        audioPrefs.edit()
                .putStringSet(LEGACY, new LinkedHashSet<>(Arrays.asList("DEC4 Volume", "DEC5 Volume")))
                .putString(CANONICAL, "DEC1 Volume,DEC2 Volume")
                .commit();

        GatewayConfig config = initConfig();

        assertEquals("the list already in effect must win",
                new HashSet<>(Arrays.asList("DEC1 Volume", "DEC2 Volume")),
                new HashSet<>(config.getMicMuteControls()));
        assertNull(stored(LEGACY));
    }

    /**
     * The crash case, isolated: the canonical key itself holding a {@code StringSet}. Every
     * {@code getString} on it throws, so it has to be rewritten even with no legacy key
     * present — and {@link GatewayConfig#getMicMuteControls()} must survive it either way,
     * because it sits on the call-audio path.
     */
    @Test
    public void canonicalKeyHoldingAStringSetIsRewrittenNotThrown() {
        audioPrefs.edit()
                .putStringSet(CANONICAL, new LinkedHashSet<>(Arrays.asList("DEC3 Volume")))
                .commit();

        GatewayConfig config = initConfig();

        assertTrue("must have been rewritten as a String", stored(CANONICAL) instanceof String);
        assertEquals(Collections.singleton("DEC3 Volume"), new HashSet<>(config.getMicMuteControls()));
    }

    /**
     * A value of a type that is neither: the list is unusable, but nothing may throw and the
     * gateway must still start.
     */
    @Test
    public void nonsenseTypeIsIgnoredRatherThanThrown() {
        audioPrefs.edit().putInt(CANONICAL, 42).commit();

        GatewayConfig config = initConfig();

        assertTrue("an unusable value must read as no controls",
                config.getMicMuteControls().isEmpty());
        assertTrue("and must no longer be able to throw on read",
                stored(CANONICAL) instanceof String);
    }

    // ------------------------------------------------------------------
    // Safety properties: idempotent, non-destructive
    // ------------------------------------------------------------------

    @Test
    public void migrationIsIdempotent() {
        audioPrefs.edit()
                .putStringSet(LEGACY, new LinkedHashSet<>(Arrays.asList("DEC1 Volume", "DEC2 MUX")))
                .commit();

        Set<String> first = initConfig().getMicMuteControls();
        Object afterFirst = stored(CANONICAL);

        Set<String> second = initConfig().getMicMuteControls();
        Set<String> third = initConfig().getMicMuteControls();

        assertEquals(new HashSet<>(first), new HashSet<>(second));
        assertEquals(new HashSet<>(first), new HashSet<>(third));
        assertEquals("the stored value must not drift between runs", afterFirst, stored(CANONICAL));
        assertNull("and the legacy key must not come back", stored(LEGACY));
    }

    /** No legacy key, nothing configured: the migration must not invent anything. */
    @Test
    public void freshInstallIsUntouched() {
        GatewayConfig config = initConfig();

        assertTrue(config.getMicMuteControls().isEmpty());
        assertNull(stored(LEGACY));
        assertFalse("nothing configured must not become a stored empty list",
                audioPrefs.contains(CANONICAL));
    }

    /** An empty legacy selection migrates to an empty canonical list, not to nothing at all. */
    @Test
    public void emptyLegacySetMigratesCleanly() {
        audioPrefs.edit().putStringSet(LEGACY, new HashSet<String>()).commit();

        GatewayConfig config = initConfig();

        assertTrue(config.getMicMuteControls().isEmpty());
        assertNull(stored(LEGACY));
    }

    /** Whitespace and empty entries in a hand-edited CSV must not become control names. */
    @Test
    public void blankAndPaddedEntriesAreDropped() {
        audioPrefs.edit().putString(CANONICAL, " DEC1 Volume , ,DEC2 MUX,").commit();

        // Nothing to migrate here (right key, right type), so this is the plain read path.
        Set<String> controls = initConfig().getMicMuteControls();

        assertEquals(new HashSet<>(Arrays.asList("DEC1 Volume", "DEC2 MUX")), new HashSet<>(controls));
    }

    // ------------------------------------------------------------------
    // Round trip
    // ------------------------------------------------------------------

    /**
     * The web interface and the in-app UI now write the same key through the same setter, so
     * a selection saved by either is read back by both — and by the mixer.
     */
    @Test
    public void setterRoundTripsThroughTheCanonicalKeyOnly() {
        GatewayConfig config = initConfig();

        config.setMicMuteControls(new LinkedHashSet<>(Arrays.asList("DEC1 Volume", "EAR_S")));

        assertEquals("DEC1 Volume,EAR_S", stored(CANONICAL));
        assertNull("the setter must never write the legacy key", stored(LEGACY));
        assertEquals(new HashSet<>(Arrays.asList("DEC1 Volume", "EAR_S")),
                new HashSet<>(config.getMicMuteControls()));
    }
}
