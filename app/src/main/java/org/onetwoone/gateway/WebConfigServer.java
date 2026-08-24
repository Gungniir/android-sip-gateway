package org.onetwoone.gateway;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.onetwoone.gateway.config.GatewayConfig;
import org.onetwoone.gateway.ui.TinymixManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashSet;
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

            GatewayConfig config = GatewayConfig.from(context);

            // SIP settings. Rendered from GatewayConfig, which owns the defaults: this used
            // to open "gateway_prefs" itself and fall back to a hardcoded 192.168.5.95 /
            // gateway / gateway123 / 101, none of which the app would ever have used - so an
            // unconfigured gateway showed a working-looking configuration that was fiction,
            // and the page (served without auth, GW-30) published a credential that was not
            // even real.
            json.put("sip_server", config.getSipServer());
            json.put("sip_port", config.getSipPort());
            json.put("sip_user", config.getSipUser());
            json.put("sip_password", config.getSipPassword());
            json.put("use_tls", config.isUseTls());
            json.put("sip_realm", config.getSipRealm());
            json.put("sim1_destination", config.getSim1Destination());
            json.put("sim2_destination", config.getSim2Destination());

            // Audio settings
            json.put("audio_profile", config.getAudioProfile());
            json.put("audio_card", config.getAudioCard());
            json.put("audio_route", config.getMultimediaRoute());

            // Audio gain (dB)
            json.put("tx_gain", config.getTxGain());  // GSM→SIP
            json.put("rx_gain", config.getRxGain());  // SIP→GSM

            // Mute preset
            json.put("mute_preset", config.getMutePreset());

            // Available presets
            JSONArray presetsArray = new JSONArray();
            for (String preset : DeviceMuteManager.getPresetNames()) {
                presetsArray.put(preset);
            }
            json.put("available_presets", presetsArray);

            // Selected mute controls (for custom preset). The wire format stays an array -
            // that is the page's contract - but it is now the same stored list the mixer
            // reads, not a private "mic_mute_controls" StringSet nothing consumed (AUDIT H4).
            JSONArray selectedArray = new JSONArray();
            for (String ctrl : config.getMicMuteControls()) {
                selectedArray.put(ctrl);
            }
            json.put("selected_mute_controls", selectedArray);

            // Manual mute controls
            json.put("manual_mute_controls", config.getManualMuteControls());

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
                soundCard = GatewayConfig.from(context).getAudioCard();
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

            // One batch for the whole request. GatewayConfig.Editor keeps at most one
            // SharedPreferences.Editor per preference file and applies each exactly once, at
            // the end. This method used to call apply() up to five times across three
            // editors - three of them on the audio editor alone - so a reader that landed
            // between two of them saw a half-saved configuration (AUDIT H4).
            GatewayConfig config = GatewayConfig.from(context);
            GatewayConfig.Editor edit = config.edit();

            // SIP settings
            if (json.has("sip_server")) edit.setSipServer(json.getString("sip_server"));
            if (json.has("sip_port")) edit.setSipPort(json.getInt("sip_port"));
            if (json.has("sip_user")) edit.setSipUser(json.getString("sip_user"));
            if (json.has("sip_password")) edit.setSipPassword(json.getString("sip_password"));
            if (json.has("use_tls")) edit.setUseTls(json.getBoolean("use_tls"));
            if (json.has("sip_realm")) edit.setSipRealm(json.getString("sip_realm"));
            if (json.has("sim1_destination")) edit.setSim1Destination(json.getString("sim1_destination"));
            if (json.has("sim2_destination")) edit.setSim2Destination(json.getString("sim2_destination"));

            // Audio settings
            if (json.has("audio_profile")) edit.setAudioProfile(json.getString("audio_profile"));
            if (json.has("audio_card")) edit.setAudioCard(json.getInt("audio_card"));
            if (json.has("audio_route")) edit.setMultimediaRoute(json.getString("audio_route"));
            if (json.has("tx_gain")) edit.setTxGain((float) json.getDouble("tx_gain"));
            if (json.has("rx_gain")) edit.setRxGain((float) json.getDouble("rx_gain"));

            // Mute preset. Was written before the guarded pair below and applied on its own,
            // so a request that changed nothing else still wrote it.
            if (json.has("mute_preset")) edit.setMutePreset(json.getString("mute_preset"));

            // Selected mute controls (for custom preset)
            if (json.has("selected_mute_controls")) {
                JSONArray selectedArray = json.getJSONArray("selected_mute_controls");
                Set<String> selectedSet = new LinkedHashSet<>();
                for (int i = 0; i < selectedArray.length(); i++) {
                    selectedSet.add(selectedArray.getString(i));
                }
                edit.setMicMuteControls(selectedSet);
            }

            // Manual mute controls
            if (json.has("manual_mute_controls")) {
                edit.setManualMuteControls(json.getString("manual_mute_controls"));
            }

            edit.apply();

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
        GatewayConfig.from(context).setWebInterfaceEnabled(false);

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
