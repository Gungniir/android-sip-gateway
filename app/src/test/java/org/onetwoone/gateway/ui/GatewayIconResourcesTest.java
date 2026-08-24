package org.onetwoone.gateway.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.util.TypedValue;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.onetwoone.gateway.R;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GW-44 - the launcher and notification icons, checked against the real resource table.
 *
 * <p>Like {@link GatewayThemeTest} these need the MERGED resources, so they do not use
 * {@code manifest = Config.NONE}; {@code testOptions.unitTests.includeAndroidResources} in
 * app/build.gradle is what makes that work.
 *
 * <p><b>Why these tests exist at all.</b> Every defect they catch is invisible on the two
 * test devices, because both are API 26+:
 *
 * <ul>
 *   <li>minSdkVersion is 23. API 23-25 never look in {@code mipmap-anydpi-v26}, so an icon
 *       shipped only as an adaptive icon leaves Android 6 and 7 with no launcher icon.
 *       Nothing on a modern device can show that.</li>
 *   <li>From API 21 a notification small icon keeps only its ALPHA channel - the system
 *       discards the colour and re-tints. A coloured or filled drawable therefore renders as
 *       a solid white blob in the status bar. Code review does not catch this; it looks
 *       perfectly reasonable in the XML.</li>
 * </ul>
 *
 * <p>The density tests assert on {@link Resources#getValue} - the resource PATH the resolver
 * actually picked for a given SDK and density - rather than on a decoded bitmap. That is the
 * invariant that matters (which file ships to which Android version) and it does not depend
 * on how faithfully Robolectric emulates bitmap decoding.
 *
 * <p><b>What is NOT tested here, and cannot be.</b> Whether any of it looks right. There is
 * no device, no instrumented test, and no screenshot: a JVM test cannot see a launcher apply
 * its mask, cannot see the status bar tint a silhouette, and cannot judge whether the mark is
 * legible at 24dp on a phone at arm's length. It also cannot check the manifest's
 * {@code android:roundIcon} attribute, because {@code ApplicationInfo} exposes no field for
 * it - only {@code android:icon} is reachable, so only that one is asserted below.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class GatewayIconResourcesTest {

    /** Density bucket -> the pixel edge a 48dp launcher icon has in that bucket. */
    private static final Map<String, Integer> BUCKETS = new LinkedHashMap<>();

    static {
        BUCKETS.put("mdpi", 48);
        BUCKETS.put("hdpi", 72);
        BUCKETS.put("xhdpi", 96);
        BUCKETS.put("xxhdpi", 144);
        BUCKETS.put("xxxhdpi", 192);
    }

    private static final int[] LAUNCHER_ICONS = {R.mipmap.ic_launcher, R.mipmap.ic_launcher_round};

    private Resources resources() {
        Context context = ApplicationProvider.getApplicationContext();
        return context.getResources();
    }

    /** The resource file the resolver picks for {@code id} under the current configuration. */
    private String resolvedPath(int id) {
        TypedValue value = new TypedValue();
        resources().getValue(id, value, true);
        assertNotNull("no resource resolved for " + resources().getResourceEntryName(id),
                value.string);
        return value.string.toString();
    }

    // ---------------------------------------------------------------- API 23-25: the rasters

    /**
     * The half that goes missing. On API 23-25 both launcher icons must resolve to a PNG out
     * of the matching density bucket - never to the adaptive XML, which those versions cannot
     * read.
     */
    @Test
    @Config(sdk = 23)
    public void legacyRastersCoverEveryDensityBucket() {
        for (Map.Entry<String, Integer> bucket : BUCKETS.entrySet()) {
            RuntimeEnvironment.setQualifiers("+" + bucket.getKey());

            for (int id : LAUNCHER_ICONS) {
                String name = resources().getResourceEntryName(id);
                String path = resolvedPath(id);

                assertTrue(name + " on API 23 at " + bucket.getKey() + " resolved to " + path
                                + ", which is not a PNG - API 23-25 cannot read an adaptive icon,"
                                + " so this device would show no launcher icon at all",
                        path.endsWith(".png"));
                assertTrue(name + " at " + bucket.getKey() + " resolved to " + path
                                + ", i.e. the wrong density bucket - that bucket is missing",
                        path.contains("mipmap-" + bucket.getKey()));
            }
        }
    }

    /** The rasters must also be the size their bucket claims, not a stretched copy. */
    @Test
    @Config(sdk = 23)
    public void legacyRastersAreTheirBucketsPixelSize() {
        for (Map.Entry<String, Integer> bucket : BUCKETS.entrySet()) {
            RuntimeEnvironment.setQualifiers("+" + bucket.getKey());

            for (int id : LAUNCHER_ICONS) {
                int expected = bucket.getValue();
                android.graphics.BitmapFactory.Options options =
                        new android.graphics.BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeResource(resources(), id, options);

                assertEquals(resources().getResourceEntryName(id) + " in mipmap-" + bucket.getKey()
                                + " is " + options.outWidth + "px wide",
                        expected, options.outWidth);
                assertEquals(resources().getResourceEntryName(id) + " in mipmap-" + bucket.getKey()
                                + " is " + options.outHeight + "px tall",
                        expected, options.outHeight);
            }
        }
    }

    // ------------------------------------------------------------------ API 26+: the adaptive

    /** From API 26 the same two names must resolve to the adaptive icon instead. */
    @Test
    @Config(sdk = 26)
    public void adaptiveIconTakesOverFromApi26() {
        for (int id : LAUNCHER_ICONS) {
            String path = resolvedPath(id);
            assertTrue(resources().getResourceEntryName(id) + " on API 26 resolved to " + path
                            + " rather than the adaptive icon in mipmap-anydpi-v26",
                    path.contains("mipmap-anydpi-v26") && path.endsWith(".xml"));
        }
    }

    /**
     * The adaptive layers must keep referencing GW-40's launcher colours. Those two are
     * deliberately day-only - a launcher icon does not follow night mode - and GW-40 has a
     * test pinning that; this is the other end of the same contract.
     */
    @Test
    public void adaptiveLayersUseTheLauncherPalette() {
        assertEquals("ic_launcher_background.xml stopped referencing @color/ic_launcher_background",
                R.color.ic_launcher_background,
                soleColorReference(R.drawable.ic_launcher_background));
        assertEquals("ic_launcher_foreground.xml stopped referencing @color/ic_launcher_foreground",
                R.color.ic_launcher_foreground,
                soleColorReference(R.drawable.ic_launcher_foreground));
    }

    // ------------------------------------------------------ notification icons: white silhouettes

    /**
     * Every colour in a notification icon must be opaque white. The system keeps only the
     * alpha channel from API 21 on, so anything else is not "a different colour" - it is a
     * white blob where the mark should be.
     */
    @Test
    public void notificationIconsAreWhiteOnTransparent() {
        for (int id : new int[]{R.drawable.ic_notification_gateway,
                R.drawable.ic_notification_battery}) {
            String name = resources().getResourceEntryName(id);
            List<Integer> colors = vectorColors(id);

            assertTrue(name + " declares no fillColor or strokeColor at all - either it is not"
                            + " the vector this test thinks it is, or the parse silently found"
                            + " nothing and this assertion would pass vacuously",
                    colors.size() >= 2);

            for (int color : colors) {
                assertEquals(name + " uses " + String.format("#%08X", color) + "; a notification"
                                + " small icon is masked to its alpha channel and must be"
                                + " opaque white",
                        0xFFFFFFFF, color);
            }
        }
    }

    /** Both notification icons must resolve to a vector, at every SDK the app supports. */
    @Test
    @Config(sdk = 23)
    public void notificationIconsResolveOnTheOldestSupportedApi() {
        for (int id : new int[]{R.drawable.ic_notification_gateway,
                R.drawable.ic_notification_battery}) {
            String path = resolvedPath(id);
            assertTrue(resources().getResourceEntryName(id) + " resolved to " + path,
                    path.endsWith(".xml"));
            assertNotNull(resources().getDrawable(id, null));
        }
    }

    // ------------------------------------------------------------------------------ manifest

    /**
     * The manifest must point at {@code @mipmap/ic_launcher}, not back at a {@code drawable/}.
     * A drawable has no density buckets, which is how the app came to ship a single 48dp
     * indigo circle for every screen.
     */
    @Test
    public void manifestIconPointsAtTheMipmap() {
        ApplicationInfo info = ApplicationProvider.getApplicationContext().getApplicationInfo();
        assertEquals("android:icon is "
                        + safeEntryName(info.icon) + ", expected mipmap/ic_launcher",
                R.mipmap.ic_launcher, info.icon);
    }

    // ------------------------------------------------------------------------------- helpers

    private String safeEntryName(int id) {
        try {
            return resources().getResourceTypeName(id) + "/" + resources().getResourceEntryName(id);
        } catch (Resources.NotFoundException e) {
            return "0x" + Integer.toHexString(id);
        }
    }

    /**
     * The one colour resource a single-layer vector references. Fails if the layer hardcodes a
     * literal instead, which is what GW-40's "no hex outside colors.xml" rule forbids.
     */
    private int soleColorReference(int drawableRes) {
        List<Integer> references = new ArrayList<>();
        try (XmlResourceParser parser = resources().getXml(drawableRes)) {
            for (int event = parser.getEventType();
                    event != XmlPullParser.END_DOCUMENT;
                    event = parser.next()) {
                if (event != XmlPullParser.START_TAG || !"path".equals(parser.getName())) {
                    continue;
                }
                for (int i = 0; i < parser.getAttributeCount(); i++) {
                    String attr = parser.getAttributeName(i);
                    if (!"fillColor".equals(attr) && !"strokeColor".equals(attr)) {
                        continue;
                    }
                    int reference = parser.getAttributeResourceValue(i, 0);
                    if (reference == 0) {
                        fail(resources().getResourceEntryName(drawableRes) + " hardcodes a colour"
                                + " in " + attr + " instead of referencing @color/*");
                    }
                    if (!references.contains(reference)) {
                        references.add(reference);
                    }
                }
            }
        } catch (Exception e) {
            throw new AssertionError("could not parse "
                    + resources().getResourceEntryName(drawableRes), e);
        }

        assertEquals(resources().getResourceEntryName(drawableRes)
                + " references " + references.size() + " colours, expected exactly 1",
                1, references.size());
        return references.get(0);
    }

    /** Every fillColor/strokeColor in a vector drawable, resolved to an ARGB int. */
    private List<Integer> vectorColors(int drawableRes) {
        List<Integer> colors = new ArrayList<>();
        try (XmlResourceParser parser = resources().getXml(drawableRes)) {
            for (int event = parser.getEventType();
                    event != XmlPullParser.END_DOCUMENT;
                    event = parser.next()) {
                if (event != XmlPullParser.START_TAG) {
                    continue;
                }
                for (int i = 0; i < parser.getAttributeCount(); i++) {
                    String attr = parser.getAttributeName(i);
                    if (!"fillColor".equals(attr) && !"strokeColor".equals(attr)) {
                        continue;
                    }
                    // A colour may be compiled either as a reference (@android:color/white)
                    // or as a literal ARGB int; handle both rather than assuming one.
                    int reference = parser.getAttributeResourceValue(i, 0);
                    colors.add(reference != 0
                            ? resources().getColor(reference, null)
                            : parser.getAttributeIntValue(i, 0));
                }
            }
        } catch (Exception e) {
            throw new AssertionError("could not parse "
                    + resources().getResourceEntryName(drawableRes), e);
        }
        return colors;
    }
}
