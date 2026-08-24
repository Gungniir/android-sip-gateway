package org.onetwoone.gateway;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import org.onetwoone.gateway.config.GatewayConfig;

/**
 * API для управления шлюзом из других приложений через Broadcast Intents
 *
 * Поддерживаемые действия (actions):
 * - org.onetwoone.gateway.START         - запустить шлюз
 * - org.onetwoone.gateway.STOP          - остановить шлюз
 * - org.onetwoone.gateway.CONFIGURE     - настроить конфигурацию SIP
 * - org.onetwoone.gateway.GET_STATUS    - получить статус (результат в результирующем Intent)
 * - org.onetwoone.gateway.TEST_CALL     - диагностический SIP-звонок (без GSM-плеча)
 *
 * Параметры для CONFIGURE (extras):
 * - sip_server (String)          - адрес SIP сервера
 * - sip_port (int)               - порт SIP сервера
 * - sip_user (String)            - SIP пользователь
 * - sip_password (String)        - SIP пароль
 * - use_tls (boolean)            - использовать TLS (порт 5061)
 * - sip_realm (String)           - SIP realm (пусто = "*", любой realm)
 * - sim1_destination (String)    - SIP ext для SIM1 (GSM→SIP)
 * - sim2_destination (String)    - SIP ext для SIM2 (GSM→SIP)
 * - incoming_mode (int)          - режим входящих звонков (0=SIP_FIRST, 1=ANSWER_FIRST)
 *
 * Параметры для TEST_CALL (extras):
 * - destination (String)         - куда звонить (по умолчанию из настроек, "*43")
 * - mode (String)                - tone | loopback | bridge
 * - duration (int)               - авто-отбой через N секунд
 * - stop (boolean)               - завершить текущий тестовый звонок
 *
 * adb shell am broadcast -a org.onetwoone.gateway.TEST_CALL \
 *     --es destination '*43' --es mode tone --ei duration 20
 *
 * Пример использования из другого приложения:
 *
 * // Запустить шлюз
 * Intent intent = new Intent("org.onetwoone.gateway.START");
 * intent.setPackage("org.onetwoone.gateway");
 * sendBroadcast(intent);
 *
 * // Настроить SIP
 * Intent config = new Intent("org.onetwoone.gateway.CONFIGURE");
 * config.setPackage("org.onetwoone.gateway");
 * config.putExtra("sip_server", "192.168.1.100");
 * config.putExtra("sip_port", 5060);
 * config.putExtra("sip_user", "gateway");
 * config.putExtra("sip_password", "secret123");
 * config.putExtra("sim1_destination", "101");
 * config.putExtra("sim2_destination", "102");
 * sendBroadcast(config);
 *
 * // Остановить шлюз
 * Intent stop = new Intent("org.onetwoone.gateway.STOP");
 * stop.setPackage("org.onetwoone.gateway");
 * sendBroadcast(stop);
 */
public class GatewayControlReceiver extends BroadcastReceiver {
    private static final String TAG = "GatewayControl";

    // Actions
    public static final String ACTION_START = "org.onetwoone.gateway.START";
    public static final String ACTION_STOP = "org.onetwoone.gateway.STOP";
    public static final String ACTION_CONFIGURE = "org.onetwoone.gateway.CONFIGURE";
    public static final String ACTION_GET_STATUS = "org.onetwoone.gateway.GET_STATUS";
    public static final String ACTION_TEST_CALL = "org.onetwoone.gateway.TEST_CALL";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        Log.i(TAG, "Received action: " + action);

        switch (action) {
            case ACTION_START:
                startGateway(context);
                break;

            case ACTION_STOP:
                stopGateway(context);
                break;

            case ACTION_CONFIGURE:
                configure(context, intent);
                break;

            case ACTION_GET_STATUS:
                // TODO: реализовать получение статуса (нужен ResultReceiver или ContentProvider)
                Log.i(TAG, "GET_STATUS not yet implemented");
                break;

            case ACTION_TEST_CALL:
                testCall(intent);
                break;

            default:
                Log.w(TAG, "Unknown action: " + action);
        }
    }

    private void startGateway(Context context) {
        Log.i(TAG, "Starting gateway service");
        Intent intent = new Intent(context, PjsipSipService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }

        // Start BatteryLimitService with saved limit
        int batteryLimit = GatewayConfig.from(context).getBatteryLimit();
        if (batteryLimit < 100) {
            Log.i(TAG, "Starting battery limit service (limit: " + batteryLimit + "%)");
            Intent batteryIntent = new Intent(context, BatteryLimitService.class);
            batteryIntent.putExtra("limit", batteryLimit);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(batteryIntent);
            } else {
                context.startService(batteryIntent);
            }
        }
    }

    private void stopGateway(Context context) {
        Log.i(TAG, "Stopping gateway service");
        PjsipSipService service = PjsipSipService.getInstance();
        if (service != null) {
            service.stop();
        } else {
            // Fallback if service not running
            Intent intent = new Intent(context, PjsipSipService.class);
            context.stopService(intent);
        }
    }

    private void testCall(Intent intent) {
        PjsipSipService service = PjsipSipService.getInstance();
        if (service == null) {
            Log.w(TAG, "Gateway service not running, cannot place test call");
            return;
        }

        if (intent.getBooleanExtra("stop", false)) {
            Log.i(TAG, "Stopping test call");
            service.stopTestCall();
            return;
        }

        String destination = intent.getStringExtra("destination");
        String mode = intent.getStringExtra("mode");
        int duration = intent.getIntExtra("duration", 0);

        Log.i(TAG, "Test call: destination=" + destination + " mode=" + mode
                + " duration=" + duration);
        service.startTestCall(destination, mode, duration);
    }

    private void configure(Context context, Intent intent) {
        Log.i(TAG, "Configuring gateway");

        // Show current config
        if (intent.getBooleanExtra("show", false)) {
            showConfig(context);
            return;
        }

        boolean changed = false;

        // One batch across all three preference files, applied once at the end - and only if
        // something was actually set. The mute preset used to be written and applied on its
        // own, ahead of and regardless of the `changed` guard the other two editors obeyed
        // (AUDIT H4).
        GatewayConfig.Editor edit = GatewayConfig.from(context).edit();

        if (intent.hasExtra("sip_server")) {
            String server = intent.getStringExtra("sip_server");
            edit.setSipServer(server);
            Log.i(TAG, "Set sip_server: " + server);
            changed = true;
        }

        if (intent.hasExtra("sip_port")) {
            int port = intent.getIntExtra("sip_port", GatewayConfig.DEFAULT_SIP_PORT);
            edit.setSipPort(port);
            Log.i(TAG, "Set sip_port: " + port);
            changed = true;
        }

        if (intent.hasExtra("sip_user")) {
            String user = intent.getStringExtra("sip_user");
            edit.setSipUser(user);
            Log.i(TAG, "Set sip_user: " + user);
            changed = true;
        }

        if (intent.hasExtra("sip_password")) {
            String password = intent.getStringExtra("sip_password");
            edit.setSipPassword(password);
            Log.i(TAG, "Set sip_password: ***");
            changed = true;
        }

        if (intent.hasExtra("use_tls")) {
            boolean useTls = intent.getBooleanExtra("use_tls", false);
            edit.setUseTls(useTls);
            Log.i(TAG, "Set use_tls: " + useTls);
            changed = true;
        }

        if (intent.hasExtra("sip_realm")) {
            String realm = intent.getStringExtra("sip_realm");
            edit.setSipRealm(realm);
            Log.i(TAG, "Set sip_realm: " + (realm.isEmpty() ? "*" : realm));
            changed = true;
        }

        if (intent.hasExtra("sim1_destination")) {
            String dest = intent.getStringExtra("sim1_destination");
            edit.setSim1Destination(dest);
            Log.i(TAG, "Set sim1_destination: " + dest);
            changed = true;
        }

        if (intent.hasExtra("sim2_destination")) {
            String dest = intent.getStringExtra("sim2_destination");
            edit.setSim2Destination(dest);
            Log.i(TAG, "Set sim2_destination: " + dest);
            changed = true;
        }

        if (intent.hasExtra("incoming_mode")) {
            int mode = intent.getIntExtra("incoming_mode", GatewayInCallService.MODE_SIP_FIRST);
            edit.setIncomingCallMode(mode);
            Log.i(TAG, "Set incoming_mode: " + mode);
            changed = true;
        }

        // Audio settings (stored in gsm_audio_config)
        if (intent.hasExtra("audio_card")) {
            int card = intent.getIntExtra("audio_card", 0);
            edit.setAudioCard(card);
            Log.i(TAG, "Set audio_card: " + card);
            changed = true;
        }

        if (intent.hasExtra("audio_route")) {
            String route = intent.getStringExtra("audio_route");
            edit.setMultimediaRoute(route);
            Log.i(TAG, "Set audio_route: " + route);
            changed = true;
        }

        // Device mute preset (stored in device_mute_prefs)
        if (intent.hasExtra("mute_preset")) {
            String preset = intent.getStringExtra("mute_preset");
            edit.setMutePreset(preset);
            Log.i(TAG, "Set mute_preset: " + preset);
            changed = true;
        }

        if (changed) {
            edit.apply();
            Log.i(TAG, "Configuration saved");
        }

        // Restart SIP service if requested
        if (intent.getBooleanExtra("restart", false)) {
            Log.i(TAG, "Restarting SIP service...");
            stopGateway(context);
            // Small delay before restart
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                startGateway(context);
            }, 500);
        }
    }

    private void showConfig(Context context) {
        GatewayConfig config = GatewayConfig.from(context);

        Log.i(TAG, "=== Current Configuration ===");
        Log.i(TAG, "sip_server: " + orNotSet(config.getSipServer()));
        Log.i(TAG, "sip_port: " + config.getSipPort());
        Log.i(TAG, "sip_user: " + orNotSet(config.getSipUser()));
        Log.i(TAG, "sip_password: " + (config.hasSipPassword() ? "****" : "(not set)"));
        Log.i(TAG, "use_tls: " + config.isUseTls());
        String realm = config.getSipRealm();
        Log.i(TAG, "sip_realm: " + (realm.isEmpty() || "*".equals(realm) ? "* (any)" : realm));
        Log.i(TAG, "sim1_destination: " + orNotSet(config.getSim1Destination()));
        Log.i(TAG, "sim2_destination: " + orNotSet(config.getSim2Destination()));
        Log.i(TAG, "audio_card: " + config.getAudioCard());
        Log.i(TAG, "audio_route: " + config.getMultimediaRoute());
        Log.i(TAG, "mute_preset: " + config.getMutePreset());
        Log.i(TAG, "mute_controls: " + orNotSet(String.join(",", config.getAllMuteControls())));
        Log.i(TAG, "=============================");
    }

    /** An unset string setting reads back as empty; say so rather than logging nothing. */
    private static String orNotSet(String value) {
        return value == null || value.isEmpty() ? "(not set)" : value;
    }
}
