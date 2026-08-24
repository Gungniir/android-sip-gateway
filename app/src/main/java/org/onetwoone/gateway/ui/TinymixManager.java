package org.onetwoone.gateway.ui;

import android.content.Context;
import android.util.Log;

import org.onetwoone.gateway.GsmAudioNative;
import org.onetwoone.gateway.RootHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages tinymix binary extraction and mixer control detection.
 *
 * Handles:
 * - Extracting tinymix binary from assets
 * - Running tinymix commands via root
 * - Parsing mixer controls (DEC volume, MUX, speaker)
 */
public class TinymixManager {
    private static final String TAG = "TinymixManager";

    /**
     * A full {@code tinymix -D N} dump is a couple of thousand controls over {@code su},
     * so it gets a longer budget than {@link RootHelper#DEFAULT_TIMEOUT_MS}. Bounded all
     * the same — the old code did a bare {@code waitFor()} (GW-20 §4).
     */
    private static final int TINYMIX_TIMEOUT_MS = 15000;

    /**
     * Represents a mixer control detected from tinymix output.
     */
    public static class MixerControl {
        public String name;         // e.g. "DEC1 Volume", "DEC1 MUX", "EAR_S"
        public int controlId;       // tinymix control ID
        public String currentValue; // Current value (volume or mux value)
        public ControlType type;
        public int originalValue;   // Original volume value for restore

        public MixerControl(String name, int controlId, String currentValue, ControlType type) {
            this.name = name;
            this.controlId = controlId;
            this.currentValue = currentValue;
            this.type = type;
            this.originalValue = -1;
        }

        @Override
        public String toString() {
            return name + " (" + currentValue + ")";
        }
    }

    public enum ControlType {
        VOLUME,     // DEC Volume controls (INT type)
        MUX,        // DEC MUX controls (ENUM type)
        SPEAKER     // Speaker/Earpiece controls (EAR_S, SPK)
    }

    private final Context context;
    private File tinymixFile;

    public TinymixManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Extract tinymix binary from assets if not already extracted.
     *
     * @return true if tinymix is available, false otherwise
     */
    public boolean ensureTinymixExtracted() {
        if (tinymixFile != null && tinymixFile.exists()) {
            return true;
        }

        // Always use tinymix-arm64 (64-bit) since the APK is built for arm64-v8a only
        String assetName = "tinymix-arm64";
        tinymixFile = new File(context.getFilesDir(), "tinymix");

        if (tinymixFile.exists()) {
            return true;
        }

        try {
            InputStream is = context.getAssets().open(assetName);
            FileOutputStream os = new FileOutputStream(tinymixFile);
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) > 0) {
                os.write(buffer, 0, len);
            }
            os.close();
            is.close();

            // Make executable
            tinymixFile.setExecutable(true, false);

            Log.i(TAG, "Extracted " + assetName + " to " + tinymixFile.getAbsolutePath());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract " + assetName + ": " + e.getMessage());
            tinymixFile = null;
            return false;
        }
    }

    /**
     * Run tinymix for a specific sound card and return raw output.
     *
     * @param soundCard The sound card number
     * @return Raw tinymix output or empty string on failure
     */
    public String runTinymix(int soundCard) {
        if (!ensureTinymixExtracted()) {
            return "";
        }

        // Both branches go through RootHelper so the process is bounded, both pipes are
        // drained, and a non-zero exit is reported as a failure instead of an empty dump
        // that the parser would silently read as "no controls" (GW-20 / AUDIT H1).
        RootHelper.RootResult result;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // Android 11+ (API 30+): mixer control access needs root because of SELinux
            result = RootHelper.run(tinymixFile.getAbsolutePath() + " -D " + soundCard,
                    TINYMIX_TIMEOUT_MS);
        } else {
            // Android 8.1-10: direct access works
            result = RootHelper.exec(new String[]{
                tinymixFile.getAbsolutePath(), "-D", String.valueOf(soundCard)
            }, TINYMIX_TIMEOUT_MS);
        }

        if (!result.success()) {
            Log.e(TAG, "tinymix failed (exit " + result.exitCode() + "): " + result.stderr());
            return "";
        }
        return result.stdout();
    }

    /**
     * Detect all mixer controls for the given sound card.
     *
     * Detects:
     * - DEC Volume controls (microphone volume)
     * - DEC MUX controls (microphone routing)
     * - Speaker/Earpiece controls (EAR_S, SPK)
     *
     * @param soundCard The sound card number
     * @return List of detected mixer controls
     */
    public List<MixerControl> detectControls(int soundCard) {
        List<MixerControl> controls = new ArrayList<>();
        String output = runTinymix(soundCard);

        if (output.isEmpty()) {
            Log.w(TAG, "No tinymix output - control detection failed");
            return controls;
        }

        // Parse tinymix output to find ALL mute controls:
        // Format examples:
        // 33   INT  1  DEC1 Volume  84
        // 1686 ENUM 1  DEC1 MUX     ZERO
        // 105  ENUM 1  EAR_S        ZERO
        // 106  ENUM 1  SPK          ZERO
        Pattern volumePattern = Pattern.compile("^(\\d+)\\s+INT\\s+\\d+\\s+(DEC\\d+) Volume\\s+(\\d+)");
        Pattern muxPattern = Pattern.compile("^(\\d+)\\s+ENUM\\s+\\d+\\s+(DEC\\d+) MUX\\s+(\\w+)");
        Pattern speakerPattern = Pattern.compile("^(\\d+)\\s+ENUM\\s+\\d+\\s+(EAR_S|SPK)\\s+(\\w+)");

        String[] lines = output.split("\n");
        for (String line : lines) {
            // Find DEC Volume controls
            Matcher volMatcher = volumePattern.matcher(line);
            if (volMatcher.find()) {
                String decNum = volMatcher.group(2);  // DEC1, DEC2, etc.
                int controlId = Integer.parseInt(volMatcher.group(1));
                String value = volMatcher.group(3);
                MixerControl control = new MixerControl(decNum + " Volume", controlId, value, ControlType.VOLUME);
                control.originalValue = Integer.parseInt(value);
                controls.add(control);
                Log.i(TAG, "Found DEC Volume: " + control + " (ID=" + controlId + ")");
            }

            // Find DEC MUX controls
            Matcher muxMatcher = muxPattern.matcher(line);
            if (muxMatcher.find()) {
                String decNum = muxMatcher.group(2);  // DEC1, DEC2, etc.
                int controlId = Integer.parseInt(muxMatcher.group(1));
                String muxValue = muxMatcher.group(3);  // ADC1, ADC2, ZERO, etc.
                MixerControl control = new MixerControl(decNum + " MUX", controlId, muxValue, ControlType.MUX);
                controls.add(control);
                Log.i(TAG, "Found DEC MUX: " + control + " (ID=" + controlId + ")");
            }

            // Find Speaker/Earpiece controls (EAR_S, SPK)
            Matcher spkMatcher = speakerPattern.matcher(line);
            if (spkMatcher.find()) {
                String spkName = spkMatcher.group(2);  // EAR_S or SPK
                int controlId = Integer.parseInt(spkMatcher.group(1));
                String spkValue = spkMatcher.group(3);  // ZERO, Switch, etc.
                MixerControl control = new MixerControl(spkName, controlId, spkValue, ControlType.SPEAKER);
                controls.add(control);
                Log.i(TAG, "Found Speaker control: " + control + " (ID=" + controlId + ")");
            }
        }

        Log.i(TAG, "Detected " + controls.size() + " mixer controls for card " + soundCard);

        // Every detect run doubles as GW-20's B1e cross-check. See verifyNativeReads().
        Log.i(TAG, verifyNativeReads(soundCard, controls));

        return controls;
    }

    /**
     * Cross-check the native mixer getters against {@code tinymix}, control by control.
     *
     * <p>This is GW-20's answer to its own Risk section: <em>"a native getter that returns a
     * different representation than tinymix parsing would silently corrupt the saved
     * originals and break restore. Verify value-by-value against tinymix before switching
     * the write path over."</em> {@link org.onetwoone.gateway.audio.QualcommAudioProfile}
     * now saves its originals through {@link GsmAudioNative#getMixerControl} and
     * {@link GsmAudioNative#getMixerControlEnum}; this compares each of those against what
     * {@code tinymix} reports for the same control at the same moment.
     *
     * <p>It runs automatically at the end of {@link #detectControls(int)}, so the on-device
     * procedure is just: tap "Detect mixer controls" (or GET {@code /api/mixer-controls})
     * and read logcat for {@code B1e native-vs-tinymix}. Doing it here rather than on the
     * call path is deliberate — this is the UI component that already has a {@code tinymix}
     * binary to compare against, and it costs nothing during a call.
     *
     * <p>The two should agree by construction: {@code tinymix} prints INT controls from
     * {@code mixer_ctl_get_value(ctl, i)} and ENUM controls from
     * {@code mixer_ctl_get_enum_string(...)}, which are the same tinyalsa primitives the JNI
     * getters call, and both read value index 0. "By construction" is not "verified on
     * hardware", which is what this exists to produce.
     *
     * @return a one-line human-readable summary; the per-control detail goes to logcat
     */
    public String verifyNativeReads(int soundCard, List<MixerControl> controls) {
        if (controls.isEmpty()) {
            return "B1e native-vs-tinymix: nothing to compare";
        }

        // The native getters need the ALSA nodes to be readable; tinymix obtains that for
        // itself via su. Without this the check reports a false mismatch on every control.
        RootHelper.setupAlsaPermissions();

        int agreed = 0;
        int mismatched = 0;
        int unreadable = 0;

        for (MixerControl control : controls) {
            String nativeValue;
            try {
                if (control.type == ControlType.VOLUME) {
                    int value = GsmAudioNative.getMixerControl(soundCard, control.name);
                    nativeValue = value < 0 ? null : String.valueOf(value);
                } else {
                    nativeValue = GsmAudioNative.getMixerControlEnum(soundCard, control.name);
                }
            } catch (Throwable t) {
                // libgsm_audio.so failed to load: GsmAudioNative's static block swallows
                // that, so the failure only surfaces here, as an Error.
                Log.e(TAG, "B1e native-vs-tinymix: native bridge unavailable: " + t);
                return "B1e native-vs-tinymix: native bridge unavailable (" + t + ")";
            }

            if (nativeValue == null) {
                unreadable++;
                Log.e(TAG, "B1e native-vs-tinymix  UNREADABLE  " + control.name
                        + "  tinymix=" + control.currentValue + "  native=<failed>");
            } else if (nativeValue.equals(control.currentValue)) {
                agreed++;
                Log.i(TAG, "B1e native-vs-tinymix  OK          " + control.name
                        + " = " + nativeValue);
            } else {
                mismatched++;
                Log.e(TAG, "B1e native-vs-tinymix  MISMATCH    " + control.name
                        + "  tinymix=" + control.currentValue + "  native=" + nativeValue);
            }
        }

        String summary = "B1e native-vs-tinymix: " + agreed + " agreed, " + mismatched
                + " mismatched, " + unreadable + " unreadable, of " + controls.size()
                + " control(s) on card " + soundCard;
        if (mismatched > 0 || unreadable > 0) {
            Log.e(TAG, summary + " - DO NOT trust the saved originals until this reads "
                    + "0 mismatched, 0 unreadable");
        }
        return summary;
    }
}
