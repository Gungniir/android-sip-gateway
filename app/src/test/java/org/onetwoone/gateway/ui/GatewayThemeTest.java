package org.onetwoone.gateway.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;

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
 * GW-40 - the design system's invariants, checked against the real resource table.
 *
 * <p>These tests need the MERGED resources, so unlike the rest of the suite they do not use
 * {@code manifest = Config.NONE}; {@code testOptions.unitTests.includeAndroidResources} in
 * app/build.gradle is what makes that work. {@code application = Application.class} keeps
 * {@code GatewayApplication} (and its WorkManager init) out of a test that is only about
 * resources.
 *
 * <p>What is deliberately NOT tested here: whether the result looks good. No instrumented
 * test exists, no device is attached, and a unit test cannot judge contrast on a phone at
 * arm's length. These check the things that can silently break without anyone noticing -
 * a theme that lost its Material parent, an attribute wired to the wrong token, a night
 * palette with a hole in it, and a type scale that drifted back under lint's 12sp floor.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class GatewayThemeTest {

    /** Every theme attribute the app or the Material components actually read. */
    private static final int[] REQUIRED_ATTRS = {
            R.attr.colorPrimary,
            R.attr.colorPrimaryVariant,
            R.attr.colorOnPrimary,
            R.attr.colorSecondary,
            R.attr.colorOnSecondary,
            R.attr.colorSurface,
            R.attr.colorOnSurface,
            R.attr.colorError,
            R.attr.colorOnError,
            R.attr.colorControlNormal,
            R.attr.colorControlActivated,
            R.attr.colorControlHighlight,
            R.attr.materialButtonStyle,
            R.attr.editTextStyle,
            R.attr.spinnerStyle,
            R.attr.checkboxStyle,
            R.attr.radioButtonStyle,
            R.attr.actionBarStyle,
            R.attr.textAppearanceBody1,
            R.attr.textAppearanceButton,
            android.R.attr.colorBackground,
            android.R.attr.windowBackground,
            android.R.attr.textColorPrimary,
            android.R.attr.textColorSecondary,
            android.R.attr.textColorHint,
            android.R.attr.statusBarColor,
    };

    private Resources.Theme theme(int styleRes) {
        Context context = ApplicationProvider.getApplicationContext();
        return new ContextThemeWrapper(context, styleRes).getTheme();
    }

    private static int resolve(Resources.Theme theme, int attr) {
        TypedValue out = new TypedValue();
        assertTrue("theme does not define attribute 0x" + Integer.toHexString(attr),
                theme.resolveAttribute(attr, out, true));
        return out.data;
    }

    /**
     * The manifest points at {@code @style/AppTheme}. Before GW-40 that style had an empty
     * body, so every one of these attributes came from whatever Theme.AppCompat.Light
     * happened to hand out. If a future edit drops the Material parent or the body, this is
     * the test that says so rather than a device that looks subtly wrong.
     */
    @Test
    public void appThemeDefinesEveryAttributeTheAppReads() {
        Resources.Theme theme = theme(R.style.AppTheme);
        for (int attr : REQUIRED_ATTRS) {
            TypedValue out = new TypedValue();
            assertTrue("AppTheme does not resolve attribute 0x" + Integer.toHexString(attr),
                    theme.resolveAttribute(attr, out, true));
        }
    }

    /**
     * AppTheme is an alias for Theme.Gateway, kept only so AndroidManifest.xml needs no
     * edit (GW-44 owns the application tag in wave 2). If someone edits one and not the
     * other, the app silently ships the wrong theme.
     */
    @Test
    public void appThemeIsAnAliasForThemeGateway() {
        Resources.Theme alias = theme(R.style.AppTheme);
        Resources.Theme real = theme(R.style.Theme_Gateway);
        for (int attr : REQUIRED_ATTRS) {
            assertEquals("AppTheme and Theme.Gateway disagree on attribute 0x"
                            + Integer.toHexString(attr),
                    resolve(real, attr), resolve(alias, attr));
        }
    }

    /**
     * The point of a token set is that the theme is wired to it rather than to hex values.
     * Checking a representative attribute per role catches a copy-paste that points
     * colorOnPrimary at the surface colour, which no compiler and no lint check would.
     */
    @Test
    public void themeAttributesAreWiredToTheTokens() {
        Context context = ApplicationProvider.getApplicationContext();
        Resources res = context.getResources();
        Resources.Theme theme = theme(R.style.AppTheme);

        assertEquals(res.getColor(R.color.gw_primary, null), resolve(theme, R.attr.colorPrimary));
        assertEquals(res.getColor(R.color.gw_on_primary, null), resolve(theme, R.attr.colorOnPrimary));
        assertEquals(res.getColor(R.color.gw_surface_variant, null), resolve(theme, R.attr.colorSurface));
        assertEquals(res.getColor(R.color.gw_on_surface, null), resolve(theme, R.attr.colorOnSurface));
        assertEquals(res.getColor(R.color.gw_surface, null),
                resolve(theme, android.R.attr.colorBackground));
        assertEquals(res.getColor(R.color.gw_status_bar, null),
                resolve(theme, android.R.attr.statusBarColor));

        // The fault colour is one colour everywhere: colorError, the destructive button
        // overlay and the error status chip must not drift apart.
        assertEquals(res.getColor(R.color.gw_state_error, null), resolve(theme, R.attr.colorError));
        assertEquals(res.getColor(R.color.gw_state_error, null),
                resolve(theme(R.style.ThemeOverlay_Gateway_Destructive), R.attr.colorPrimary));
    }

    /**
     * Same theme, night configuration. This is the check that the DayNight parent is real:
     * if someone re-parents onto a non-DayNight Material theme the attributes still
     * resolve, but they stop changing with the configuration and the night palette becomes
     * dead weight.
     */
    @Test
    @Config(qualifiers = "night")
    public void themeFollowsTheNightConfiguration() {
        Context context = ApplicationProvider.getApplicationContext();
        Resources res = context.getResources();
        Resources.Theme theme = theme(R.style.AppTheme);

        for (int attr : REQUIRED_ATTRS) {
            TypedValue out = new TypedValue();
            assertTrue("AppTheme does not resolve attribute 0x" + Integer.toHexString(attr)
                    + " in night configuration", theme.resolveAttribute(attr, out, true));
        }

        assertEquals(res.getColor(R.color.gw_primary, null), resolve(theme, R.attr.colorPrimary));
        assertEquals(res.getColor(R.color.gw_surface, null),
                resolve(theme, android.R.attr.colorBackground));

        // Night really is a dark surface. Guards against a values-night/colors.xml that
        // exists but was never wired to the theme.
        int surface = res.getColor(R.color.gw_surface, null);
        assertTrue("night gw_surface is not dark: #" + Integer.toHexString(surface),
                luminance(surface) < 0.2f);
    }

    @Test
    public void dayPaletteIsALightSurface() {
        Resources res = ApplicationProvider.<Application>getApplicationContext().getResources();
        int surface = res.getColor(R.color.gw_surface, null);
        assertTrue("day gw_surface is not light: #" + Integer.toHexString(surface),
                luminance(surface) > 0.8f);
    }

    /**
     * Material's own components refuse to be constructed under a non-Material theme: the
     * MaterialButton / MaterialCheckBox / SwitchMaterial constructors call
     * {@code ThemeEnforcement}, which throws IllegalArgumentException with "your app theme
     * to be Theme.MaterialComponents" if the required attributes are missing.
     *
     * <p>That makes construction itself the assertion, and it is the check that the theme
     * can host wave 2: GW-41 replaces the raw {@code <Switch>} with SwitchMaterial and
     * assigns the Widget.Gateway.Button styles, and if this theme could not carry them the
     * failure would land in that wave rather than this one.
     */
    @Test
    public void themeCanHostMaterialComponents() {
        Context themed = new ContextThemeWrapper(
                ApplicationProvider.<Application>getApplicationContext(), R.style.AppTheme);

        assertNotNull(new com.google.android.material.button.MaterialButton(themed));
        assertNotNull(new com.google.android.material.checkbox.MaterialCheckBox(themed));
        assertNotNull(new com.google.android.material.radiobutton.MaterialRadioButton(themed));
        assertNotNull(new com.google.android.material.switchmaterial.SwitchMaterial(themed));
        assertNotNull(new com.google.android.material.textview.MaterialTextView(themed));
    }

    @Test
    @Config(qualifiers = "night")
    public void themeCanHostMaterialComponentsAtNightToo() {
        Context themed = new ContextThemeWrapper(
                ApplicationProvider.<Application>getApplicationContext(), R.style.AppTheme);

        assertNotNull(new com.google.android.material.button.MaterialButton(themed));
        assertNotNull(new com.google.android.material.switchmaterial.SwitchMaterial(themed));
    }

    /**
     * Every gw_* token must exist in BOTH configurations and must actually differ between
     * them.
     *
     * <p>The "must differ" half is the strict one, and it is strict on purpose. A palette
     * where a token happens to be identical in day and night is almost always a token
     * someone forgot to add to values-night, not a deliberate choice - and the failure mode
     * is invisible until a device with dark mode on is in front of you. If a token ever
     * genuinely needs the same value in both, that is a decision worth making explicitly,
     * and this test is where the conversation starts.
     *
     * <p>Reflection over R.color rather than a hand-kept list, so a token added tomorrow is
     * covered without anyone remembering to add it here.
     */
    @Test
    public void everyTokenIsRedefinedForNight() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Resources dayRes = context.getResources();

        List<String> identical = new ArrayList<>();
        int checked = 0;
        for (Field field : R.color.class.getFields()) {
            String name = field.getName();
            if (!name.startsWith("gw_")) {
                continue;
            }
            int id = field.getInt(null);
            int day = dayRes.getColor(id, null);
            int night = nightColor(id);
            checked++;
            if (day == night) {
                identical.add(name + " = #" + Integer.toHexString(day));
            }
        }

        assertTrue("no gw_* tokens found - has the palette been renamed?", checked >= 20);
        if (!identical.isEmpty()) {
            fail("tokens with no distinct values-night value (" + identical.size() + " of "
                    + checked + "): " + identical);
        }
    }

    /**
     * The launcher colours are the exception, and the exception has to hold: a launcher
     * icon is drawn by the launcher and never follows the app's night mode, so GW-44 needs
     * these two to be single-valued. They are also the only two colours in colors.xml whose
     * NAMES are load-bearing across waves.
     */
    @Test
    public void launcherColoursAreNotNightAware() {
        Resources dayRes = ApplicationProvider.<Application>getApplicationContext().getResources();
        assertEquals(dayRes.getColor(R.color.ic_launcher_background, null),
                nightColor(R.color.ic_launcher_background));
        assertEquals(dayRes.getColor(R.color.ic_launcher_foreground, null),
                nightColor(R.color.ic_launcher_foreground));
    }

    /**
     * Lint's SmallSp flags text under 12sp, and lint-baseline.xml carries two of them. The
     * scale has no step below Caption, so a layout built from it cannot reintroduce the
     * issue - unless someone adds a smaller step, which is what this catches.
     */
    @Test
    public void noTypeScaleStepIsBelowTheSmallSpFloor() throws Exception {
        Resources res = ApplicationProvider.<Application>getApplicationContext().getResources();
        float scaledDensity = res.getDisplayMetrics().scaledDensity;

        int checked = 0;
        for (Field field : R.dimen.class.getFields()) {
            String name = field.getName();
            if (!name.startsWith("gw_text_")) {
                continue;
            }
            float sp = res.getDimension(field.getInt(null)) / scaledDensity;
            assertTrue(name + " is " + sp + "sp, below lint's 12sp SmallSp floor", sp >= 12f);
            checked++;
        }
        assertTrue("no gw_text_* steps found - has the type scale been renamed?", checked >= 6);
    }

    /**
     * The spacing scale steps in even dp - a 4dp rhythm plus one 2dp hairline step. A stray
     * 5dp or 13dp is how a scale stops being a scale.
     */
    @Test
    public void everySpacingStepIsOnTheGrid() throws Exception {
        Resources res = ApplicationProvider.<Application>getApplicationContext().getResources();
        float density = res.getDisplayMetrics().density;

        int checked = 0;
        for (Field field : R.dimen.class.getFields()) {
            String name = field.getName();
            if (!name.startsWith("gw_space_")) {
                continue;
            }
            float dp = res.getDimension(field.getInt(null)) / density;
            assertEquals(name + " is " + dp + "dp, an odd step off the scale", 0f, dp % 2f, 0.01f);
            checked++;
        }
        assertTrue("no gw_space_* steps found", checked >= 5);
    }

    /** Resolve a colour under the night configuration without disturbing this test's own. */
    private static int nightColor(int colorRes) {
        android.content.res.Configuration night =
                new android.content.res.Configuration(
                        ApplicationProvider.<Application>getApplicationContext()
                                .getResources().getConfiguration());
        night.uiMode = (night.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                | android.content.res.Configuration.UI_MODE_NIGHT_YES;
        Context nightContext = ApplicationProvider.<Application>getApplicationContext()
                .createConfigurationContext(night);
        return nightContext.getResources().getColor(colorRes, null);
    }

    /** Relative luminance, good enough to tell "light surface" from "dark surface". */
    private static float luminance(int color) {
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }
}
