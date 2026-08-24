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
 * and dereferenced there, never described here. Narrow is about <em>what may be captured</em>,
 * not about how much of it the UI is allowed to see; growth since (GW-14's reload counter,
 * GW-22's call counters, GW-25's {@link WatchdogFindings}) all obeys the same rule, which is
 * that the control thread already owns the value.
 *
 * <h3>Who reads this</h3>
 *
 * <p>Since GW-45 the object itself is the UI's status surface: {@code MainViewModel} publishes
 * it whole as {@code LiveData<GatewayStatus>} once a second and the screen derives from it.
 * It used to keep three fields and throw the rest away (Phase 4 plan §2 C1), which is why
 * {@link #getStatusText()} exists at all - and why it is now deprecated. <b>Anything added
 * here is reachable by the UI without further plumbing; anything the UI needs belongs here
 * rather than in a live read of a manager.</b>
 *
 * <p>{@link #toBundle()} exists because the snapshot's second consumer is
 * {@code GatewayControlReceiver}'s {@code GET_STATUS}, which is a {@code TODO} stub today.
 *
 * <h3>What is deliberately absent</h3>
 *
 * <p>SMS. Not an oversight and not a rendering gap - see
 * {@code docs/refactor/issues/GW-46-sms-status.md}. The counters a status screen would want
 * (forwarded, failed, last delivery) are not state {@code SmsHandler} keeps today, and the
 * outbound send outcome arrives on <em>main</em>, in a {@code BroadcastReceiver} registered
 * with no handler. Publishing it would mean new counters and a new cross-thread hand-off,
 * which is exactly what three phases of this refactor have been removing.
 */
public final class GatewayStatus {

    /** What the UI sees before the service has ever published anything. */
    public static final GatewayStatus UNAVAILABLE = new GatewayStatus(
            false, false, "Not configured", "Idle", "Not initialized", "IDLE", 0L, 0L,
            0L, 0L, WatchdogFindings.NONE, 0L);

    /**
     * What the watchdog has found, as raw values (GW-25 §8).
     *
     * <p>Built on the control thread by {@code PjsipSipService} and handed to
     * {@link #capture} the way {@code configGeneration} is: it is the watchdog's own
     * bookkeeping, not a manager read, so it is passed in rather than pulled out of a
     * manager here.
     *
     * <p><b>Nothing in here is time-derived.</b> {@link #getCallUpSinceWallMs()} is the raw
     * instant a call became visible to the watchdog; the duration is derived from the clock
     * by {@link GatewayStatus#getCallDurationMs()} on every read. Freezing "call is over the
     * two-hour deadline" into a boolean would be plan §2.7 trap 1 all over again.
     */
    public static final class WatchdogFindings {

        /** No watchdog has run yet - what {@link GatewayStatus#UNAVAILABLE} carries. */
        public static final WatchdogFindings NONE =
                new WatchdogFindings(0L, 0L, 0L, "", 0L);

        private final long callUpSinceWallMs;
        private final long terminations;
        private final long silentBridgeEpisodes;
        private final String lastFinding;
        private final long lastFindingAtWallMs;

        public WatchdogFindings(long callUpSinceWallMs, long terminations,
                                long silentBridgeEpisodes, String lastFinding,
                                long lastFindingAtWallMs) {
            this.callUpSinceWallMs = callUpSinceWallMs;
            this.terminations = terminations;
            this.silentBridgeEpisodes = silentBridgeEpisodes;
            this.lastFinding = lastFinding == null ? "" : lastFinding;
            this.lastFindingAtWallMs = lastFindingAtWallMs;
        }

        /** Wall-clock instant a call first became visible to the watchdog, or 0. */
        public long getCallUpSinceWallMs() {
            return callUpSinceWallMs;
        }

        /**
         * How many calls the watchdog has torn down since the gateway started.
         *
         * <p>The acceptance number for GW-25's false-positive run: thirty normal calls of
         * varying length must leave this at <b>zero</b>. It is here rather than only in
         * logcat precisely so that run can be scored from {@code GET_STATUS}.
         */
        public long getTerminations() {
            return terminations;
        }

        /**
         * How many bridged-but-silent episodes have been diagnosed. Detection only - the
         * watchdog never terminates on this signal (GW-25 §5), so a non-zero count here with
         * {@link #getTerminations()} still at zero is exactly the evidence the brief asks to
         * collect before deciding whether to act on it.
         */
        public long getSilentBridgeEpisodes() {
            return silentBridgeEpisodes;
        }

        /** The last invariant violation the watchdog logged, or "" if there has been none. */
        public String getLastFinding() {
            return lastFinding;
        }

        /** Wall-clock instant of {@link #getLastFinding()}, or 0. */
        public long getLastFindingAtWallMs() {
            return lastFindingAtWallMs;
        }
    }

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

    /** What the watchdog has found. Never null - {@link WatchdogFindings#NONE} instead. */
    private final WatchdogFindings watchdog;

    /** Wall-clock instant this snapshot was taken, for staleness diagnostics. */
    private final long capturedAtWallMs;

    GatewayStatus(boolean running, boolean sipRegistered, String sipStatus, String callStatus,
                  String audioStatus, String callState, long gsmCallPlacedAtWallMs,
                  long configGeneration, long callsCreated, long callsDeleted,
                  WatchdogFindings watchdog, long capturedAtWallMs) {
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
        this.watchdog = watchdog == null ? WatchdogFindings.NONE : watchdog;
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
     * @param watchdog         the watchdog's own bookkeeping, for the same reason - it lives
     *                         on {@code PjsipSipService}, not in a manager. Null is read as
     *                         {@link WatchdogFindings#NONE}.
     */
    @ControlThread
    public static GatewayStatus capture(boolean running,
                                        SipAccountManager account,
                                        CallManager calls,
                                        AudioBridgeManager audio,
                                        long configGeneration,
                                        long callsCreated,
                                        long callsDeleted,
                                        WatchdogFindings watchdog) {
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
                watchdog,
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
     * What the watchdog has found (GW-25). Never null.
     */
    public WatchdogFindings getWatchdog() {
        return watchdog;
    }

    /**
     * How long a call has been up as far as the watchdog is concerned, or 0 when there is
     * none.
     *
     * <p><em>Derived</em>, like {@link #isInGracePeriod()} and for the same reason: the
     * watchdog's max-call-duration fail-safe is a deadline on this number, and a frozen
     * duration would answer with however long the call had been up when the snapshot was
     * taken - which for a 1 Hz UI poll is a stopwatch that never advances, and for the
     * {@code GET_STATUS} broadcast is simply wrong.
     */
    public long getCallDurationMs() {
        long since = watchdog.getCallUpSinceWallMs();
        if (since == 0L) {
            return 0L;
        }
        long elapsed = System.currentTimeMillis() - since;
        return elapsed < 0L ? 0L : elapsed;
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

    /**
     * The three-line composite the UI has always shown.
     *
     * @deprecated GW-45. A compatibility shim for the pre-Phase-4 screen, not a status API:
     *     it glues {@link #getSipStatus()}, {@link #getCallStatus()} and
     *     {@link #getAudioStatus()} into one String that a caller then has to take apart
     *     again to style, colour or lay out any part of it. Read the three separately. Kept
     *     while {@code MainViewModel}'s deprecated {@code getStatusText()} LiveData and
     *     {@code PjsipSipService.getStatus()} still call it; GW-41 removes the first and
     *     GW-31 is where the second belongs.
     */
    @Deprecated
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
        b.putLong("watchdog_terminations", watchdog.getTerminations());
        b.putLong("silent_bridge_episodes", watchdog.getSilentBridgeEpisodes());
        b.putString("last_watchdog_finding", watchdog.getLastFinding());
        b.putLong("last_watchdog_finding_at_wall_ms", watchdog.getLastFindingAtWallMs());
        b.putLong("call_up_since_wall_ms", watchdog.getCallUpSinceWallMs());
        b.putLong("call_duration_ms", getCallDurationMs());
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
                + ", watchdogTerminations=" + watchdog.getTerminations()
                + ", silentBridgeEpisodes=" + watchdog.getSilentBridgeEpisodes()
                + ", sip=" + sipStatus
                + ", call=" + callStatus
                + ", audio=" + audioStatus + "}";
    }
}
