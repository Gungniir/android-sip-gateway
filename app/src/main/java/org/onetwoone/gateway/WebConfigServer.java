package org.onetwoone.gateway;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.onetwoone.gateway.ui.TinymixManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fi.iki.elonen.NanoHTTPD;

/**
 * Embedded HTTP server for gateway configuration via web browser.
 *
 * Endpoints:
 * - GET /           - HTML configuration page
 * - GET /api/config - JSON with current settings
 * - POST /api/config - Save settings and restart SIP service
 */
public class WebConfigServer extends NanoHTTPD {
    private static final String TAG = "WebConfig";

    private Context context;
    private Handler mainHandler;
    private TinymixManager tinymixManager;

    public WebConfigServer(Context context, int port) {
        super(port);
        this.context = context;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.tinymixManager = new TinymixManager(context);
    }

    @Override
    public void start() throws IOException {
        super.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        Log.i(TAG, "Web config server started on port " + getListeningPort());
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        Log.d(TAG, method + " " + uri);

        try {
            // API endpoints
            if ("/api/config".equals(uri)) {
                if (Method.GET.equals(method)) {
                    return getConfigJson();
                } else if (Method.POST.equals(method)) {
                    return postConfig(session);
                }
            } else if ("/api/mixer-controls".equals(uri) && Method.GET.equals(method)) {
                return getMixerControlsJson(session);
            } else if ("/api/disable".equals(uri) && Method.POST.equals(method)) {
                return disableWebInterface();
            }

            // Static files from assets
            if ("/".equals(uri) || "/index.html".equals(uri)) {
                return serveAsset("index.html", "text/html");
            } else if ("/style.css".equals(uri)) {
                return serveAsset("style.css", "text/css");
            } else if ("/config.js".equals(uri)) {
                return serveAsset("config.js", "application/javascript");
            }

            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
        } catch (Exception e) {
            Log.e(TAG, "Error handling request: " + e.getMessage(), e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: " + e.getMessage());
        }
    }

    /**
     * Serve a file from assets folder
     */
    private Response serveAsset(String filename, String mimeType) {
        try {
            AssetManager assets = context.getAssets();
            InputStream is = assets.open(filename);
            byte[] data = readAllBytes(is);
            is.close();
            return newFixedLengthResponse(Response.Status.OK, mimeType, new String(data, "UTF-8"));
        } catch (IOException e) {
            Log.e(TAG, "Failed to load asset: " + filename, e);
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found: " + filename);
        }
    }

    private byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[4096];
        int n;
        while ((n = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, n);
        }
        return buffer.toByteArray();
    }

    /**
     * GET /api/config - return current configuration as JSON
     */
    private Response getConfigJson() {
        try {
            JSONObject json = new JSONObject();

            // SIP settings
            SharedPreferences sipPrefs = context.getSharedPreferences("gateway_prefs", Context.MODE_PRIVATE);
            json.put("sip_server", sipPrefs.getString("sip_server", "192.168.5.95"));
            json.put("sip_port", sipPrefs.getInt("sip_port", 5060));
            json.put("sip_user", sipPrefs.getString("sip_user", "gateway"));
            json.put("sip_password", sipPrefs.getString("sip_password", "gateway123"));
            json.put("use_tls", sipPrefs.getBoolean("use_tls", false));
            json.put("sip_realm", sipPrefs.getString("sip_realm", ""));
            json.put("sim1_destination", sipPrefs.getString("sim1_destination", "101"));
            json.put("sim2_destination", sipPrefs.getString("sim2_destination", ""));

            // Audio settings
            SharedPreferences audioPrefs = context.getSharedPreferences("gsm_audio_config", Context.MODE_PRIVATE);
            json.put("audio_profile", audioPrefs.getString("audio_profile", "auto"));
            json.put("audio_card", audioPrefs.getInt("card", 0));
            json.put("audio_route", audioPrefs.getString("multimedia_route", "MultiMedia1"));

            // Audio gain (dB)
            json.put("tx_gain", audioPrefs.getFloat("tx_gain", 0.0f));  // GSM→SIP
            json.put("rx_gain", audioPrefs.getFloat("rx_gain", 0.0f));  // SIP→GSM

            // Mute preset
            SharedPreferences mutePrefs = context.getSharedPreferences("device_mute_prefs", Context.MODE_PRIVATE);
            json.put("mute_preset", mutePrefs.getString("mute_preset", DeviceMuteManager.PRESET_REDMI_NOTE_7));

            // Available presets
            JSONArray presetsArray = new JSONArray();
            for (String preset : DeviceMuteManager.getPresetNames()) {
                presetsArray.put(preset);
            }
            json.put("available_presets", presetsArray);

            // Selected mute controls (for custom preset)
            Set<String> selectedControls = audioPrefs.getStringSet("mic_mute_controls", new HashSet<>());
            JSONArray selectedArray = new JSONArray();
            for (String ctrl : selectedControls) {
                selectedArray.put(ctrl);
            }
            json.put("selected_mute_controls", selectedArray);

            // Manual mute controls
            json.put("manual_mute_controls", audioPrefs.getString("manual_mute_controls", ""));

            return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error building config JSON: " + e.getMessage(), e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    /**
     * GET /api/mixer-controls - detect and return available mixer controls
     */
    private Response getMixerControlsJson(IHTTPSession session) {
        try {
            // Get sound card from query parameter or use default
            String cardParam = session.getParms().get("card");
            int soundCard = 0;
            if (cardParam != null) {
                try {
                    soundCard = Integer.parseInt(cardParam);
                } catch (NumberFormatException ignored) {}
            } else {
                // Use saved card
                SharedPreferences audioPrefs = context.getSharedPreferences("gsm_audio_config", Context.MODE_PRIVATE);
                soundCard = audioPrefs.getInt("card", 0);
            }

            // Detect controls
            List<TinymixManager.MixerControl> controls = tinymixManager.detectControls(soundCard);

            // Build JSON response
            JSONObject json = new JSONObject();
            json.put("card", soundCard);

            JSONArray controlsArray = new JSONArray();
            for (TinymixManager.MixerControl ctrl : controls) {
                JSONObject ctrlJson = new JSONObject();
                ctrlJson.put("name", ctrl.name);
                ctrlJson.put("id", ctrl.controlId);
                ctrlJson.put("value", ctrl.currentValue);
                ctrlJson.put("type", ctrl.type.name());
                controlsArray.put(ctrlJson);
            }
            json.put("controls", controlsArray);

            return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error detecting mixer controls: " + e.getMessage(), e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    /**
     * POST /api/config - save configuration and reload SIP
     */
    private Response postConfig(IHTTPSession session) {
        try {
            // Parse POST body
            Map<String, String> body = new HashMap<>();
            session.parseBody(body);
            String postData = body.get("postData");

            if (postData == null || postData.isEmpty()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"No data\"}");
            }

            JSONObject json = new JSONObject(postData);

            // Save SIP settings
            SharedPreferences.Editor sipEditor = context.getSharedPreferences("gateway_prefs", Context.MODE_PRIVATE).edit();
            if (json.has("sip_server")) sipEditor.putString("sip_server", json.getString("sip_server"));
            if (json.has("sip_port")) sipEditor.putInt("sip_port", json.getInt("sip_port"));
            if (json.has("sip_user")) sipEditor.putString("sip_user", json.getString("sip_user"));
            if (json.has("sip_password")) sipEditor.putString("sip_password", json.getString("sip_password"));
            if (json.has("use_tls")) sipEditor.putBoolean("use_tls", json.getBoolean("use_tls"));
            if (json.has("sip_realm")) sipEditor.putString("sip_realm", json.getString("sip_realm"));
            if (json.has("sim1_destination")) sipEditor.putString("sim1_destination", json.getString("sim1_destination"));
            if (json.has("sim2_destination")) sipEditor.putString("sim2_destination", json.getString("sim2_destination"));
            sipEditor.apply();

            // Save audio settings
            SharedPreferences.Editor audioEditor = context.getSharedPreferences("gsm_audio_config", Context.MODE_PRIVATE).edit();
            if (json.has("audio_profile")) audioEditor.putString("audio_profile", json.getString("audio_profile"));
            if (json.has("audio_card")) audioEditor.putInt("card", json.getInt("audio_card"));
            if (json.has("audio_route")) audioEditor.putString("multimedia_route", json.getString("audio_route"));
            if (json.has("tx_gain")) audioEditor.putFloat("tx_gain", (float) json.getDouble("tx_gain"));
            if (json.has("rx_gain")) audioEditor.putFloat("rx_gain", (float) json.getDouble("rx_gain"));
            audioEditor.apply();

            // Save mute preset
            if (json.has("mute_preset")) {
                SharedPreferences.Editor muteEditor = context.getSharedPreferences("device_mute_prefs", Context.MODE_PRIVATE).edit();
                muteEditor.putString("mute_preset", json.getString("mute_preset"));
                muteEditor.apply();
            }

            // Save selected mute controls (for custom preset)
            if (json.has("selected_mute_controls")) {
                JSONArray selectedArray = json.getJSONArray("selected_mute_controls");
                Set<String> selectedSet = new HashSet<>();
                for (int i = 0; i < selectedArray.length(); i++) {
                    selectedSet.add(selectedArray.getString(i));
                }
                audioEditor.putStringSet("mic_mute_controls", selectedSet);
                audioEditor.apply();
            }

            // Save manual mute controls
            if (json.has("manual_mute_controls")) {
                audioEditor.putString("manual_mute_controls", json.getString("manual_mute_controls"));
                audioEditor.apply();
            }

            Log.i(TAG, "Configuration saved, reloading...");

            // Reload config without restarting service
            PjsipSipService service = PjsipSipService.getInstance();
            if (service != null) {
                service.reloadConfig();
            }

            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"ok\",\"message\":\"Configuration saved, reloading...\"}");
        } catch (Exception e) {
            Log.e(TAG, "Error saving config: " + e.getMessage(), e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    /**
     * POST /api/disable - disable web interface
     */
    private Response disableWebInterface() {
        Log.i(TAG, "Disabling web interface...");

        // Save preference
        SharedPreferences.Editor editor = context.getSharedPreferences("gateway_prefs", Context.MODE_PRIVATE).edit();
        editor.putBoolean("web_interface_enabled", false);
        editor.apply();

        // Schedule stop after response is sent
        mainHandler.postDelayed(() -> {
            PjsipSipService service = PjsipSipService.getInstance();
            if (service != null) {
                service.stopWebServer();
            }
        }, 500);

        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"ok\",\"message\":\"Web interface disabled\"}");
    }

}
