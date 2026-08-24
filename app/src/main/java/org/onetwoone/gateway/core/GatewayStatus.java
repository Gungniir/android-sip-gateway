package org.onetwoone.gateway.core;

import android.os.Bundle;

import org.onetwoone.gateway.audio.AudioBridgeManager;
import org.onetwoone.gateway.call.CallManager;
import org.onetwoone.gateway.sip.SipAccountManager;

/**
 * An immutable read-only view of the gateway, published from the control thread to a
 * {@code volatile} field and read from anywhere.
 *
 * <p>Why it exists: after GW-10 the lifecycle managers ({@code CallManager},
 * {@code SipAccountManager}, {@code AudioBridgeManager}) are owned by the control thread.
 * The 1 Hz UI poll runs on main and must not reach into them. It reads this instead.
 *
 * <h3>Two rules this class exists to enforce</h3>
 * <ol>
 *   <li><b>Nothing time-derived is frozen.</b> {@link #isInGracePeriod()} carries the raw
 *       wall-clock instant the GSM call was placed and re-evaluates the deadline on every
 *       call. Snapshotting it as a {@code boolean} would report "in grace period" for the
 *       whole life of the snapshot, and the watchdog acts on that.
 *   <li><b>The test-call report is not in here.</b> It is a {@code StringBuilder} capped at
 *       20 000 chars, appended from two threads and polled at 1 Hz; copying it into every
 *       snapshot would make publishing cost proportional to report length. It stays its own
 *       field on {@code PjsipSipService} and its own read.
 * </ol>
 *
 * <p>The field set is deliberately narrow: the recon behind plan §2.7 found the whole live
 * read surface to be {@code isRunning}, {@code isSipRegistered} and the composite status
 * string. Everything else that looked like a status getter is dead code (noted for GW-31),
 * or needs a genuinely live pjsua2 object - and those must be posted to the control thread
 * and dereferenced there, never described here.
 *
 * <p>{@link #toBundle()} exists because the snapshot's second consumer is
 * {@code GatewayControlReceiver}'s {@code GET_STATUS}, which is a {@code TODO} stub today.
 */
public final class GatewayStatus {

    /** What the UI sees before the service has ever published anything. */
    public static final GatewayStatus UNAVAILABLE = new GatewayStatus(
            false, false, "Not configured", "Idle", "Not initialized", "IDLE", 0L, 0L,
            0L, 0L, 0L);

    private final boolean running;
    private final boolean sipRegistered;
    private final String sipStatus;
    private final String callStatus;
    private final String audioStatus;
    private final String callState;

    /**
     * Wall-clock instant the GSM call was placed, or 0. Raw on purpose - see
     * {@link #isInGracePeriod()}.
     */
    private final long gsmCallPlacedAtWallMs;

    /**
     * How many config reloads the control thread has run. See {@link #getConfigGeneration()}.
     */
    private final long configGeneration;

    /**
     * Process-wide counts of pjsua2 {@code Call} objects constructed and destroyed. See
     * {@link #getCallsAlive()}.
     */
    private final long callsCreated;
    private final long callsDeleted;

    /** Wall-clock instant this snapshot was taken, for staleness diagnostics. */
    private final long capturedAtWallMs;

    GatewayStatus(boolean running, boolean sipRegistered, String sipStatus, String callStatus,
                  String audioStatus, String callState, long gsmCallPlacedAtWallMs,
                  long configGeneration, long callsCreated, long callsDeleted,
                  long capturedAtWallMs) {
        this.running = running;
        this.sipRegistered = sipRegistered;
        this.sipStatus = sipStatus;
        this.callStatus = callStatus;
        this.audioStatus = audioStatus;
        this.callState = callState;
        this.gsmCallPlacedAtWallMs = gsmCallPlacedAtWallMs;
        this.configGeneration = configGeneration;
        this.callsCreated = callsCreated;
        this.callsDeleted = callsDeleted;
        this.capturedAtWallMs = capturedAtWallMs;
    }

    /**
     * Take a snapshot of the three lifecycle managers.
     *
     * <p>Must be called on the control thread - it reads state that thread owns. The caller
     * asserts that; this method takes plain references so it stays unit-testable.
     *
     * @param configGeneration the service's reload counter - a plain value, not a manager
     *                         read, so it is passed in rather than captured here
     * @param callsCreated     {@code GatewayCall.getCallsCreated()} - process-wide and static,
     *                         so it is passed in for the same reason as the reload counter
     * @param callsDeleted     {@code GatewayCall.getCallsDeleted()}
     */
    @ControlThread
    public static GatewayStatus capture(boolean running,
                                        SipAccountManager account,
                                        CallManager calls,
                                        AudioBridgeManager audio,
                                        long configGeneration,
                                        long callsCreated,
                                        long callsDeleted) {
        return new GatewayStatus(
                running,
                account != null && account.isRegistered(),
                account == null ? "Not configured" : account.getStatusString(),
                calls == null ? "Idle" : calls.getStatusString(),
                audio == null ? "Not initialized" : audio.getStatusString(),
                calls == null ? CallManager.CallState.IDLE.name() : calls.getState().name(),
                calls == null ? 0L : calls.getGsmCallPlacedAtWallMs(),
                configGeneration,
                callsCreated,
                callsDeleted,
                System.currentTimeMillis());
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isSipRegistered() {
        return sipRegistered;
    }

    public String getSipStatus() {
        return sipStatus;
    }

    public String getCallStatus() {
        return callStatus;
    }

    public String getAudioStatus() {
        return audioStatus;
    }

    /** {@code CallManager.CallState.name()} at capture time. */
    public String getCallState() {
        return callState;
    }

    public long getCapturedAtWallMs() {
        return capturedAtWallMs;
    }

    /**
     * A monotonic counter of config reloads, bumped by {@code PjsipSipService.doReloadConfig}.
     *
     * <p>GW-14 deleted the {@code MainActivity} relaunch with
     * {@code FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK} that used to end a reload, so
     * a config save from the web interface no longer throws away whatever the person holding
     * the phone was doing. This is what replaces it.
     *
     * <p>The rest of the snapshot already covered the <em>status</em> half of "reflect the new
     * config": {@link #getStatusText()} is rebuilt from the live managers on every publish, so
     * the SIP line goes "Connecting..." then "Registered" within a poll of the reload either
     * way. What it did not and should not cover is the <em>configuration</em> half - the form
     * fields in {@code MainActivity} come from {@code MainViewModel.loadConfig()}, which reads
     * {@code GatewayConfig} (SharedPreferences) and is only called from the ViewModel
     * constructor and after an in-app save. A web-interface save writes those preferences from
     * a NanoHTTPD worker and never touches the ViewModel, so the on-screen fields went stale
     * and only the activity restart papered over it.
     *
     * <p>Carrying a counter rather than the values themselves is deliberate: config is not
     * control-thread-owned state, it is preferences that any thread can already read, and
     * plan §2.7 keeps this snapshot to what the control thread owns. The counter says only
     * "the persisted config changed, re-read it", which is the one fact the UI could not get
     * for itself.
     */
    public long getConfigGeneration() {
        return configGeneration;
    }

    /** Process-wide count of pjsua2 {@code Call} objects constructed. */
    public long getCallsCreated() {
        return callsCreated;
    }

    /** Process-wide count of pjsua2 {@code Call} objects destroyed. */
    public long getCallsDeleted() {
        return callsDeleted;
    }

    /**
     * How many pjsua2 {@code Call} objects exist right now (AUDIT H7).
     *
     * <p>The acceptance number for GW-22's soak: it must equal the number of currently active
     * calls - 0 or 1 - once the gateway settles, and a value that climbs across a soak means
     * {@code CallGraveyard} is abandoning calls to the finalizer instead of deleting them.
     *
     * <p>Derived rather than snapshotted for the same reason as {@link #isInGracePeriod()}:
     * both halves come from the same capture, so the difference is consistent, but computing it
     * here keeps the two raw counts available for a rate.
     */
    public long getCallsAlive() {
        return callsCreated - callsDeleted;
    }

    /**
     * True while the GSM leg is still inside its post-dial grace period.
     *
     * <p>A <em>derived</em> accessor, re-reading the clock on every call - never a frozen
     * boolean. The watchdog uses this to decide whether a SIP call with no GSM leg is
     * orphaned, and a stale "yes" makes the orphan invisible for as long as the snapshot
     * lives.
     */
    public boolean isInGracePeriod() {
        if (gsmCallPlacedAtWallMs == 0L) {
            return false;
        }
        return System.currentTimeMillis() - gsmCallPlacedAtWallMs
                < CallManager.GSM_CALL_GRACE_PERIOD_MS;
    }

    /** The three-line composite the UI has always shown. */
    public String getStatusText() {
        return "SIP: " + sipStatus + "\n"
                + "Call: " + callStatus + "\n"
                + "Audio: " + audioStatus;
    }

    /** Flattened for {@code GET_STATUS}. Only primitives and strings, so it also serialises. */
    public Bundle toBundle() {
        Bundle b = new Bundle();
        b.putBoolean("running", running);
        b.putBoolean("sip_registered", sipRegistered);
        b.putString("sip_status", sipStatus);
        b.putString("call_status", callStatus);
        b.putString("audio_status", audioStatus);
        b.putString("call_state", callState);
        b.putBoolean("in_grace_period", isInGracePeriod());
        b.putLong("config_generation", configGeneration);
        b.putLong("calls_created", callsCreated);
        b.putLong("calls_deleted", callsDeleted);
        b.putLong("calls_alive", getCallsAlive());
        b.putLong("captured_at_wall_ms", capturedAtWallMs);
        return b;
    }

    @Override
    public String toString() {
        return "GatewayStatus{running=" + running
                + ", sipRegistered=" + sipRegistered
                + ", configGeneration=" + configGeneration
                + ", callState=" + callState
                + ", calls=" + callsCreated + "/" + callsDeleted
                + " (alive " + getCallsAlive() + ")"
                + ", sip=" + sipStatus
                + ", call=" + callStatus
                + ", audio=" + audioStatus + "}";
    }
}
