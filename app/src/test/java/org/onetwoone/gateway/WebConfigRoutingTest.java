package org.onetwoone.gateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.onetwoone.gateway.config.GatewayConfig;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * The two config entry points outside the app UI now go through {@link GatewayConfig} —
 * AUDIT <b>H4</b> (GW-24).
 *
 * <p>{@code WebConfigServer} and {@code GatewayControlReceiver} used to open
 * {@code gateway_prefs} / {@code gsm_audio_config} / {@code device_mute_prefs} by name and
 * write raw keys. That is how the mute-control key drifted apart from the one the mixer
 * reads, and how the web page came to render defaults (192.168.5.95, gateway123, 101) that
 * the app itself would never have used.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class WebConfigRoutingTest {

    private Application app;
    private GatewayConfig config;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        for (String name : new String[] {"gateway_prefs", "gsm_audio_config", "device_mute_prefs"}) {
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit();
        }
        try {
            java.lang.reflect.Field instance = GatewayConfig.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, null);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        GatewayConfig.init(app);
        config = GatewayConfig.getInstance();
    }

    // ------------------------------------------------------------------
    // WebConfigServer
    // ------------------------------------------------------------------

    private JSONObject getConfigJson() throws Exception {
        WebConfigServer server = new WebConfigServer(app, GatewayConfig.WEB_SERVER_PORT);
        Method m = WebConfigServer.class.getDeclaredMethod("getConfigJson");
        m.setAccessible(true);
        NanoHTTPD.Response response = (NanoHTTPD.Response) m.invoke(server);
        return new JSONObject(readBody(response));
    }

    private JSONObject postConfigJson(JSONObject body) throws Exception {
        WebConfigServer server = new WebConfigServer(app, GatewayConfig.WEB_SERVER_PORT);
        Method m = WebConfigServer.class.getDeclaredMethod("postConfig", NanoHTTPD.IHTTPSession.class);
        m.setAccessible(true);
        NanoHTTPD.Response response =
                (NanoHTTPD.Response) m.invoke(server, new FakeSession(body.toString()));
        return new JSONObject(readBody(response));
    }

    private static String readBody(NanoHTTPD.Response response) throws IOException {
        InputStream is = response.getData();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }

    /**
     * An unconfigured gateway must show what the app would actually use, not the web
     * server's own hardcoded set. The credentials in particular were served on a page with
     * no authentication (GW-30).
     */
    @Test
    public void configJsonRendersGatewayConfigDefaults() throws Exception {
        JSONObject json = getConfigJson();

        assertEquals("", json.getString("sip_server"));
        assertEquals("", json.getString("sip_user"));
        assertEquals("", json.getString("sip_password"));
        assertEquals("*", json.getString("sip_realm"));
        assertEquals("", json.getString("sim1_destination"));
        assertEquals(GatewayConfig.DEFAULT_SIP_PORT, json.getInt("sip_port"));
        assertEquals("auto", json.getString("audio_profile"));
        assertEquals("MultiMedia1", json.getString("audio_route"));
        assertEquals(GatewayConfig.DEFAULT_MUTE_PRESET, json.getString("mute_preset"));
    }

    /** And when it is configured, it shows the stored values. */
    @Test
    public void configJsonRendersStoredValues() throws Exception {
        config.updateSipConfig("pbx.example.com", 5061, "gw", "secret", "realm", true);
        config.setMicMuteControls(new LinkedHashSet<>(Arrays.asList("DEC1 Volume", "EAR_S")));

        JSONObject json = getConfigJson();

        assertEquals("pbx.example.com", json.getString("sip_server"));
        assertEquals(5061, json.getInt("sip_port"));
        assertEquals("gw", json.getString("sip_user"));
        assertTrue(json.getBoolean("use_tls"));

        JSONArray selected = json.getJSONArray("selected_mute_controls");
        assertEquals(2, selected.length());
    }

    /**
     * The bug, end to end on the web side: a selection posted by the page is read back by
     * {@code getAllMuteControls()} — the call the mixer makes — and not parked under a key
     * nothing consumes.
     */
    @Test
    public void postedMuteControlsReachTheMixerSideGetter() throws Exception {
        JSONObject body = new JSONObject();
        body.put("mute_preset", DeviceMuteManager.PRESET_CUSTOM);
        body.put("selected_mute_controls", new JSONArray(Arrays.asList("DEC1 Volume", "DEC1 MUX")));
        body.put("manual_mute_controls", "EAR_S");

        JSONObject result = postConfigJson(body);
        assertEquals("ok", result.getString("status"));

        assertEquals(new HashSet<>(Arrays.asList("DEC1 Volume", "DEC1 MUX", "EAR_S")),
                new HashSet<>(config.getAllMuteControls()));
        assertEquals(DeviceMuteManager.PRESET_CUSTOM, config.getMutePreset());

        SharedPreferences audioPrefs =
                app.getSharedPreferences("gsm_audio_config", Context.MODE_PRIVATE);
        assertNull("the legacy StringSet key must never be written again",
                audioPrefs.getAll().get("mic_mute_controls"));
    }

    /** A POST writes every field it carries, across all three preference files, in one batch. */
    @Test
    public void postConfigWritesEveryFieldItCarries() throws Exception {
        JSONObject body = new JSONObject();
        body.put("sip_server", "pbx.example.com");
        body.put("sip_port", 5061);
        body.put("sip_user", "gw");
        body.put("sip_password", "secret");
        body.put("use_tls", true);
        body.put("sip_realm", "example");
        body.put("sim1_destination", "101");
        body.put("sim2_destination", "102");
        body.put("audio_profile", "qualcomm");
        body.put("audio_card", 2);
        body.put("audio_route", "MultiMedia2");
        body.put("tx_gain", -3.0);
        body.put("rx_gain", 1.5);
        body.put("mute_preset", "generic");

        postConfigJson(body);

        assertEquals("pbx.example.com", config.getSipServer());
        assertEquals(5061, config.getSipPort());
        assertEquals("gw", config.getSipUser());
        assertEquals("secret", config.getSipPassword());
        assertTrue(config.isUseTls());
        assertEquals("example", config.getSipRealm());
        assertEquals("101", config.getSim1Destination());
        assertEquals("102", config.getSim2Destination());
        assertEquals("qualcomm", config.getAudioProfile());
        assertEquals(2, config.getAudioCard());
        assertEquals("MultiMedia2", config.getMultimediaRoute());
        assertEquals(-3.0f, config.getTxGain(), 0.0001f);
        assertEquals(1.5f, config.getRxGain(), 0.0001f);
        assertEquals("generic", config.getMutePreset());
    }

    /** A POST that carries only some fields must not reset the others to defaults. */
    @Test
    public void postConfigLeavesUnmentionedFieldsAlone() throws Exception {
        config.updateSipConfig("pbx.example.com", 5060, "gw", "secret", "*", false);
        config.setAudioCard(1);

        JSONObject body = new JSONObject();
        body.put("sip_user", "gw2");
        postConfigJson(body);

        assertEquals("gw2", config.getSipUser());
        assertEquals("pbx.example.com", config.getSipServer());
        assertEquals("secret", config.getSipPassword());
        assertEquals(1, config.getAudioCard());
    }

    // ------------------------------------------------------------------
    // GatewayControlReceiver
    // ------------------------------------------------------------------

    private void configure(Intent intent) {
        new GatewayControlReceiver().onReceive(app, intent);
    }

    @Test
    public void configureBroadcastWritesThroughGatewayConfig() {
        Intent intent = new Intent(GatewayControlReceiver.ACTION_CONFIGURE);
        intent.putExtra("sip_server", "pbx.example.com");
        intent.putExtra("sip_port", 5061);
        intent.putExtra("sip_user", "gw");
        intent.putExtra("sip_password", "secret");
        intent.putExtra("use_tls", true);
        intent.putExtra("sip_realm", "example");
        intent.putExtra("sim1_destination", "101");
        intent.putExtra("sim2_destination", "102");
        intent.putExtra("incoming_mode", 1);
        intent.putExtra("audio_card", 3);
        intent.putExtra("audio_route", "MultiMedia3");
        intent.putExtra("mute_preset", DeviceMuteManager.PRESET_CUSTOM);

        configure(intent);

        assertEquals("pbx.example.com", config.getSipServer());
        assertEquals(5061, config.getSipPort());
        assertEquals("gw", config.getSipUser());
        assertEquals("secret", config.getSipPassword());
        assertTrue(config.isUseTls());
        assertEquals("example", config.getSipRealm());
        assertEquals("101", config.getSim1Destination());
        assertEquals("102", config.getSim2Destination());
        assertEquals(1, config.getIncomingCallMode());
        assertEquals(3, config.getAudioCard());
        assertEquals("MultiMedia3", config.getMultimediaRoute());
        assertEquals(DeviceMuteManager.PRESET_CUSTOM, config.getMutePreset());
    }

    /**
     * A CONFIGURE that sets nothing must write nothing. The mute preset used to be applied
     * on its own editor, outside the {@code changed} guard the other two obeyed.
     */
    @Test
    public void configureWithNoRecognisedExtrasWritesNothing() {
        configure(new Intent(GatewayControlReceiver.ACTION_CONFIGURE));

        for (String name : new String[] {"gateway_prefs", "gsm_audio_config", "device_mute_prefs"}) {
            assertTrue(name + " was written by a CONFIGURE that set nothing",
                    app.getSharedPreferences(name, Context.MODE_PRIVATE).getAll().isEmpty());
        }
    }

    /** A CONFIGURE that sets only audio must not touch the SIP file. */
    @Test
    public void configureOnlyTouchesThePreferenceFilesItSets() {
        Intent intent = new Intent(GatewayControlReceiver.ACTION_CONFIGURE);
        intent.putExtra("audio_route", "MultiMedia2");
        configure(intent);

        assertEquals("MultiMedia2", config.getMultimediaRoute());
        assertTrue("gateway_prefs must be untouched",
                app.getSharedPreferences("gateway_prefs", Context.MODE_PRIVATE).getAll().isEmpty());
        assertFalse("device_mute_prefs must be untouched",
                app.getSharedPreferences("device_mute_prefs", Context.MODE_PRIVATE)
                        .contains("mute_preset"));
    }

    /**
     * Minimal {@link NanoHTTPD.IHTTPSession}: everything {@code postConfig} touches is
     * {@code parseBody}, which the real one fills with the request body under "postData".
     */
    private static final class FakeSession implements NanoHTTPD.IHTTPSession {
        private final String postData;

        FakeSession(String postData) {
            this.postData = postData;
        }

        @Override public void execute() {}
        @Override public NanoHTTPD.CookieHandler getCookies() { return null; }
        @Override public Map<String, String> getHeaders() { return new HashMap<>(); }
        @Override public InputStream getInputStream() { return null; }
        @Override public NanoHTTPD.Method getMethod() { return NanoHTTPD.Method.POST; }
        @Override public Map<String, String> getParms() { return new HashMap<>(); }
        @Override public Map<String, List<String>> getParameters() { return new HashMap<>(); }
        @Override public String getQueryParameterString() { return ""; }
        @Override public String getUri() { return "/api/config"; }
        @Override public String getRemoteIpAddress() { return "127.0.0.1"; }
        @Override public String getRemoteHostName() { return "localhost"; }

        @Override
        public void parseBody(Map<String, String> files) {
            files.put("postData", postData);
        }
    }
}
