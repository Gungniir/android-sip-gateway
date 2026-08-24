package org.onetwoone.gateway.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.onetwoone.gateway.R;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * GW-40 - the string extraction, checked rather than asserted.
 *
 * <p>GW-40 moved 68 hardcoded literals into strings.xml. The compiler catches a bad
 * {@code R.string} reference in Java; nothing catches a bad one in XML, a format specifier
 * that does not match its arguments, or a resource that resolves to an empty string. These
 * tests cover exactly those three gaps.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class GatewayStringResourcesTest {

    private Context themed() {
        return new ContextThemeWrapper(
                ApplicationProvider.<Application>getApplicationContext(), R.style.AppTheme);
    }

    /**
     * The strongest single check available without a device: inflate the real screen under
     * the real theme.
     *
     * <p>It fails if any {@code @string}, {@code @style}, {@code @dimen} or {@code @color}
     * reference in activity_main.xml dangles. Stated precisely, because it would be easy to
     * over-claim: a bare LayoutInflater does NOT run AppCompat's or Material's view
     * inflater - that happens inside AppCompatDelegate, i.e. only from an AppCompatActivity
     * - so this proves the references resolve, not that the widgets get upgraded. The
     * upgrade path is covered separately by
     * {@link GatewayThemeTest#themeCanHostMaterialComponents()}.
     *
     * <p>GW-41 rewrites this layout in wave 2. This test should survive that rewrite
     * unchanged; if it does not, the rewrite broke something.
     */
    @Test
    public void theMainLayoutInflatesUnderTheGatewayTheme() {
        Context context = themed();
        View root = LayoutInflater.from(context).inflate(R.layout.activity_main, null);
        assertNotNull("activity_main.xml did not inflate under AppTheme", root);

        // Spot-check that the views MainActivity looks up by id survived inflation, so a
        // dangling id is not mistaken for a passing test.
        assertNotNull(root.findViewById(R.id.statusText));
        assertNotNull(root.findViewById(R.id.sipServer));
        assertNotNull(root.findViewById(R.id.connectButton));
        assertNotNull(root.findViewById(R.id.webInterfaceSwitch));
        assertNotNull(root.findViewById(R.id.mixerRouteSpinner));
    }

    @Test
    @Config(qualifiers = "night")
    public void theMainLayoutInflatesInNightConfigurationToo() {
        assertNotNull(LayoutInflater.from(themed()).inflate(R.layout.activity_main, null));
    }

    /**
     * The layout used to hold 43 android:text and 11 android:hint literals and ZERO
     * {@code @string} references. Every one of those attributes now resolves through a
     * resource, so every one of them has an id that resolves to a non-empty string.
     *
     * <p>Reflection over R.string rather than a list of 60-odd names: a string added
     * tomorrow is covered without anyone remembering to add it here.
     */
    @Test
    public void everyStringResourceResolvesToSomething() throws Exception {
        Resources res = ApplicationProvider.<Application>getApplicationContext().getResources();

        List<String> empty = new ArrayList<>();
        int checked = 0;
        for (Field field : R.string.class.getFields()) {
            int id = field.getInt(null);
            String name;
            try {
                name = res.getResourceEntryName(id);
            } catch (Resources.NotFoundException e) {
                fail("R.string." + field.getName() + " does not resolve");
                return;
            }
            // Library strings (appcompat, material) are not ours to police.
            if (!isOurs(field.getName())) {
                continue;
            }
            String value = res.getString(id);
            if (value.trim().isEmpty()) {
                empty.add(name);
            }
            checked++;
        }

        assertTrue("expected the extracted strings to be present, found " + checked,
                checked >= 60);
        assertTrue("string resources that resolve to nothing: " + empty, empty.isEmpty());
    }

    /**
     * Three strings carry positional format specifiers because the literals they replaced
     * were built by concatenation (which is also what closed the five SetTextI18n entries
     * in lint-baseline.xml). A wrong specifier compiles fine and throws at runtime, in a
     * Toast, on a device in a drawer.
     */
    @Test
    public void formattedStringsAcceptTheirArguments() {
        Resources res = ApplicationProvider.<Application>getApplicationContext().getResources();

        assertEquals("Test call to 1000 (tone)",
                res.getString(R.string.toast_test_call, "1000", "tone"));
        assertEquals("Web Interface: http://192.168.1.50:8080",
                res.getString(R.string.label_web_interface_enabled, "192.168.1.50"));
        assertEquals("Preset: Speaker + mic",
                res.getString(R.string.toast_mute_preset, "Speaker + mic"));
    }

    /**
     * The three select_* strings predate GW-40 and PHASE-4-PLAN section C2 records them as
     * dead. They are not: the capture, playback and mixer spinners reference them from
     * android:prompt, which C2's measurement (android:text and android:hint only) could not
     * see. Kept, and pinned here so a future cleanup does not delete them on the strength of
     * that note.
     */
    @Test
    public void theSpinnerPromptStringsAreStillLive() {
        Resources res = ApplicationProvider.<Application>getApplicationContext().getResources();
        assertFalse(res.getString(R.string.select_capture).isEmpty());
        assertFalse(res.getString(R.string.select_playback).isEmpty());
        assertFalse(res.getString(R.string.select_mixer).isEmpty());
    }

    /**
     * The prefixes strings.xml documents as its naming convention. Filtering by them keeps
     * appcompat's and Material's several hundred strings out of the check - and means a
     * string that ignores the convention is invisible here, which is the intended pressure.
     */
    private static final String[] OUR_PREFIXES = {
            "app_name", "section_", "label_", "option_", "hint_",
            "help_", "action_", "status_", "toast_", "select_",
    };

    private static boolean isOurs(String name) {
        for (String prefix : OUR_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
