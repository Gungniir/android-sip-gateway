package org.onetwoone.gateway;

import android.content.Context;
import android.util.Log;

import org.onetwoone.gateway.audio.AudioProfile;
import org.onetwoone.gateway.audio.AudioProfileFactory;
import org.onetwoone.gateway.config.GatewayConfig;
import org.pjsip.pjsua2.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Custom AudioMediaPort for bridging GSM call audio to SIP.
 * Uses native tinyalsa for direct ALSA access - no tinycap/tinyplay processes.
 *
 * SoC-specific routing (which mixer controls tap the modem voice path, and which
 * PCM devices carry it) is delegated to an {@link AudioProfile} chosen at runtime
 * by {@link AudioProfileFactory}. This class stays SoC-agnostic.
 *
 * <h3>Threading, and the two workers GW-12 decided to keep</h3>
 * {@code startCapture()} / {@code stopCapture()} are reached only from
 * {@code AudioBridgeManager}, which asserts the GatewayControl thread. The two background
 * workers below are <em>not</em> folded into it. GW-12 §7 asked for both; the plan (§2.1)
 * left the decision here, and the answer for both is no.
 *
 * <h4>Why {@code GsmAudioOpen} stays its own thread</h4>
 * <ul>
 *   <li><b>It would make its own cancellation undeliverable.</b> The retry loop is bounded by
 *       {@code stopCapture()} advancing {@link #sessionId}, and {@code stopCapture()} is a
 *       control-thread operation. Run the loop on that same thread and the cancel sits in the
 *       queue behind the loop it is meant to cancel: the GW-08 generation machinery becomes
 *       dead code and the ~10 s window becomes genuinely uninterruptible.</li>
 *   <li><b>It blocks for up to ~10 s</b> (20 attempts × 500 ms), plus a native
 *       {@code GsmAudioNative.open()} that is not interruptible and routinely outlives even
 *       the 1 s join in {@code stopCapture()}. Ten seconds with no call teardown, no
 *       {@code stopBridge}, no phone-state handling and no reconnection is not acceptable on
 *       the thread every lifecycle event is serialised through - and a SIP-first incoming
 *       call, where the caller hangs up mid-retry, is precisely when it happens.</li>
 *   <li><b>{@code profile.setupMixer(card)} shells out to {@code su}</b> once per saved
 *       control to read the originals back (Qualcomm), which is unbounded process-spawn
 *       latency (plan §3c). That is the same reason plan §2.1 keeps {@code MuteControls} and
 *       {@code BatteryOptDisable} off the control thread.</li>
 * </ul>
 * GW-08's generation check is therefore not "defence in depth" here - it remains the primary
 * and only cancellation mechanism.
 *
 * <h4>Why {@code MixerEnforce} stays its own thread</h4>
 * <ul>
 *   <li><b>A {@code postDelayed} loop would self-deadlock.</b> {@link #stopEnforceThread()}
 *       cancels with {@code interrupt()} + {@code join(ENFORCE_JOIN_MS)} <em>while
 *       {@link #stateLock} is held</em>, and it is reached from the open worker
 *       ({@link #startEnforceThread(int)}) as well as from {@code stopCapture()}. Turn the
 *       tick into a control-thread task and "join the in-flight tick" becomes "wait for the
 *       control thread" - while the control thread's own {@code startCapture}/
 *       {@code stopCapture} are blocked on the {@code stateLock} the waiter is holding.</li>
 *   <li><b>Its cadence must not be perturbed by lifecycle work.</b> The whole job is to fight
 *       the audio HAL re-asserting its routing on a fixed 2 s beat. The control thread blocks
 *       for 30 s in {@code createEndpoint}'s latch and ~600 ms in a config reload; the mic
 *       would come back un-muted mid-call in exactly those windows.</li>
 *   <li>It is cheap and it takes no locks: {@code enforceMixer()} is a handful of native JNI
 *       mixer writes and touches no saved state, by {@link AudioProfile} contract.</li>
 * </ul>
 */
public class GsmAudioPort extends AudioMediaPort {
    private static final String TAG = "GsmAudioPort";

    // Fixed audio parameters
    private static final int BITS = 16;
    private static final int FRAME_TIME_MS = 20;
    private static final int PERIOD_COUNT = 4;

    private final Context context;
    private final int card;
    private final AudioProfile profile;

    // Capture side = the PJSIP port format (GSM→SIP). Playback may run at a
    // different ALSA rate (e.g. MediaTek 48 kHz); the native layer upsamples.
    private final int sampleRate;   // capture / PJSIP port rate
    private final int channels;     // capture / PJSIP port channels
    private final int periodSize;   // capture samples per 20ms period
    private final int frameSize;    // bytes per 20ms PJSIP/capture frame
    private final int playbackRate;
    private final int playbackChannels;
    private final int playbackPeriod;

    private static final int ENFORCE_INTERVAL_MS = 2000;
    // Retry opening the modem voice PCM in case the voice path isn't ready the
    // instant the call connects. (The main open failure - the playback memif
    // rejecting params - was a config-reuse bug fixed in native open().)
    // DO NOT shorten this policy: it is tuned for the modem voice path coming up
    // late on SIP-first incoming calls. Cancellation is handled by the session
    // generation below, not by making the retry window smaller.
    private static final int OPEN_MAX_ATTEMPTS = 20;   // up to ~10s
    private static final int OPEN_RETRY_MS = 500;
    /** How long stopCapture() waits for the open worker before it stops caring. */
    private static final int OPEN_JOIN_MS = 1000;
    /** How long we wait for MixerEnforce to notice it has been cancelled. */
    private static final int ENFORCE_JOIN_MS = 500;

    private final AtomicBoolean isCapturing = new AtomicBoolean(false);
    private final AtomicBoolean isPortCreated = new AtomicBoolean(false);

    /**
     * Session generation. One capture session is one startCapture()/stopCapture()
     * pair. The counter is ODD while a session is current and EVEN while idle;
     * its value is the session id.
     *
     * stopCapture() advances it BEFORE doing anything else, and that single write
     * is what invalidates a worker still in flight - including one blocked inside
     * {@link GsmAudioNative#open}, which is a native call, is NOT interruptible,
     * and can therefore outlive the join. A worker publishes its result
     * (isCapturing + MixerEnforce) only while its own generation is still
     * current; a superseded worker instead releases everything it established.
     *
     * Without this, a late open() re-armed capture after teardown had already run
     * and leaked a MixerEnforce thread that re-asserted the call routing and the
     * mic mute every 2 s forever, with no open PCM and no call (AUDIT B3).
     *
     * Every WRITE happens under {@link #stateLock}; reads are unlocked because
     * the worker polls it between retries - hence the atomic.
     */
    private final AtomicInteger sessionId = new AtomicInteger(0);

    /**
     * Serialises the session state transitions: claiming a generation, patching
     * the mixer, publishing a successful open, and releasing either of those.
     *
     * It is NEVER held across the blocking native open() (stopCapture() would
     * stall for the whole retry window) nor across enforceMixer() (MixerEnforce
     * would deadlock against the join in stopEnforceThread()).
     */
    private final Object stateLock = new Object();

    /** Session that owns the current mixer patch; 0 = not patched. Guarded by stateLock. */
    private int mixerOwner = 0;

    /** Session that owns the open PCM pair; 0 = closed. Guarded by stateLock. */
    private int pcmOwner = 0;

    /** Session that owns the live MixerEnforce thread; 0 = none. Guarded by stateLock. */
    private int enforceOwner = 0;

    // Background worker that opens the PCM devices (with retry) at call start.
    // volatile + always snapshotted before use: written by the startCapture()
    // caller, read/cleared from main, from a pjsua worker (onCallsTerminated) or
    // from ConfigReload (AUDIT E4).
    private volatile Thread openThread;

    // Periodically re-asserts the profile's mixer routing to defeat the audio
    // HAL re-asserting its own routing shortly after a call connects.
    // volatile + snapshotted for the same reason as openThread (AUDIT E4).
    private volatile Thread enforceThread;

    // Native read/write buffers (reused to avoid allocation)
    private final byte[] captureBuffer;
    private final byte[] playbackBuffer;

    // Statistics
    private long framesRequested = 0;
    private long framesReceived = 0;
    private long captureErrors = 0;
    private long playbackErrors = 0;

    public GsmAudioPort(Context context, GatewayConfig config) {
        super();
        this.context = context.getApplicationContext();

        this.card = config.getAudioCard();
        this.profile = AudioProfileFactory.select(this.context, config);

        this.sampleRate = profile.captureSampleRate();
        this.channels = profile.captureChannels();
        this.periodSize = sampleRate * FRAME_TIME_MS / 1000;
        this.frameSize = sampleRate * (BITS / 8) * channels * FRAME_TIME_MS / 1000;
        this.playbackRate = profile.playbackSampleRate();
        this.playbackChannels = profile.playbackChannels();
        this.playbackPeriod = playbackRate * FRAME_TIME_MS / 1000;

        this.captureBuffer = new byte[frameSize];
        this.playbackBuffer = new byte[frameSize];

        Log.i(TAG, "Profile=" + profile.name() + " card=" + card
                + " capture=" + profile.captureDevice() + "@" + sampleRate + "/" + channels + "ch"
                + " playback=" + profile.playbackDevice() + "@" + playbackRate + "/" + playbackChannels + "ch"
                + " frame=" + frameSize + "B");
    }

    /** SoC audio profile in use (for callers that must adapt, e.g. mic-mute handling). */
    public AudioProfile getProfile() {
        return profile;
    }

    /**
     * Initialize native audio
     */
    public boolean initialize() {
        Log.d(TAG, "Initializing GsmAudioPort (native mode)...");

        // Setup ALSA permissions (requires root)
        if (!RootHelper.setupAlsaPermissions()) {
            Log.e(TAG, "Failed to setup ALSA permissions - native audio won't work");
            return false;
        }

        // Log available mixer controls for debugging on new devices
        GsmAudioNative.logMixerControls(card);

        return true;
    }

    /**
     * Create PJSIP audio port
     */
    public void createPort() {
        if (isPortCreated.get()) {
            Log.d(TAG, "Port already created");
            return;
        }

        try {
            MediaFormatAudio fmt = new MediaFormatAudio();
            fmt.setType(pjmedia_type.PJMEDIA_TYPE_AUDIO);
            fmt.setId(pjmedia_format_id.PJMEDIA_FORMAT_L16);
            fmt.setClockRate(sampleRate);
            fmt.setChannelCount(channels);
            fmt.setBitsPerSample(BITS);
            fmt.setFrameTimeUsec(FRAME_TIME_MS * 1000);

            super.createPort("gsm_bridge", fmt);
            isPortCreated.set(true);

            Log.d(TAG, "Audio port created: " + sampleRate + "Hz, " + channels + "ch, " + BITS + "bit, frame=" + frameSize);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create port: " + e.getMessage());
        }
    }

    /**
     * PJSIP callback: Need audio to SEND to SIP peer (GSM → SIP direction)
     */
    @Override
    public void onFrameRequested(MediaFrame frame) {
        framesRequested++;

        try {
            ByteVector buf = frame.getBuf();
            buf.clear();

            if (isCapturing.get() && GsmAudioNative.isOpen()) {
                // Read from native ALSA
                int bytesRead = GsmAudioNative.readFrame(captureBuffer);

                if (bytesRead == frameSize) {
                    for (byte b : captureBuffer) {
                        buf.add((short) (b & 0xFF));
                    }
                    frame.setSize(frameSize);
                    frame.setType(pjmedia_frame_type.PJMEDIA_FRAME_TYPE_AUDIO);
                } else {
                    captureErrors++;
                    // Send silence on error
                    for (int i = 0; i < frameSize; i++) buf.add((short) 0);
                    frame.setSize(frameSize);
                    frame.setType(pjmedia_frame_type.PJMEDIA_FRAME_TYPE_NONE);
                }
            } else {
                // Not capturing - send silence
                for (int i = 0; i < frameSize; i++) buf.add((short) 0);
                frame.setSize(frameSize);
                frame.setType(pjmedia_frame_type.PJMEDIA_FRAME_TYPE_NONE);
            }

            // Log every 500 frames (~10 seconds)
            if (framesRequested % 500 == 0) {
                Log.d(TAG, "onFrameRequested: " + framesRequested + " frames, errors=" + captureErrors);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onFrameRequested: " + e.getMessage());
        }
    }

    /**
     * PJSIP callback: RECEIVED audio from SIP peer (SIP → GSM direction)
     */
    @Override
    public void onFrameReceived(MediaFrame frame) {
        framesReceived++;

        try {
            if (!isCapturing.get() || !GsmAudioNative.isOpen()) {
                return;
            }

            ByteVector buf = frame.getBuf();
            long size = frame.getSize();

            if (buf != null && size > 0 && size <= frameSize) {
                // Convert ByteVector to byte[]
                for (int i = 0; i < size; i++) {
                    playbackBuffer[i] = (byte) (buf.get(i) & 0xFF);
                }

                // Write to native ALSA
                int bytesWritten = GsmAudioNative.writeFrame(playbackBuffer);
                if (bytesWritten < 0) {
                    playbackErrors++;
                }
            }

            // Log every 500 frames (~10 seconds)
            if (framesReceived % 500 == 0) {
                Log.d(TAG, "onFrameReceived: " + framesReceived + " frames, errors=" + playbackErrors);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onFrameReceived: " + e.getMessage());
        }
    }

    /**
     * Start audio capture/playback (when GSM call becomes active)
     */
    public void startCapture() {
        final int mySession;
        synchronized (stateLock) {
            int current = sessionId.get();
            if (isSessionActive(current)) {
                // Covers both "already capturing" and "an open is still in
                // flight" - with generations those are the same condition.
                Log.w(TAG, "Capture session " + current + " is already current"
                        + " (capturing=" + isCapturing.get() + ") - ignoring startCapture()");
                return;
            }
            mySession = current + 1;            // odd => a session is current
            sessionId.set(mySession);

            // Run open on a background thread. GW-12 §7 proposed folding this onto the
            // GatewayControl thread now that the caller can block; that was rejected, and
            // deliberately - see the "Why GsmAudioOpen stays its own thread" note on this
            // class. In short: the retry window is up to ~10 s, setupMixer() shells out to
            // `su` an unbounded number of times, and the cancel that bounds all of it is
            // stopCapture(), which is itself a control-thread operation - so on one thread
            // the cancel could never be delivered while the loop it cancels was running.
            Thread worker = new Thread(() -> openWithRetry(mySession), "GsmAudioOpen-" + mySession);
            openThread = worker;
            worker.start();
        }
    }

    /**
     * Opens the PCM pair for session {@code mySession}, retrying while the modem
     * voice path comes up. Every step is gated on the session still being current
     * so that a stopCapture() issued at any point during this method leaves
     * nothing behind - see {@link #sessionId} and {@link #releaseLocked(int)}.
     */
    private void openWithRetry(final int mySession) {
        try {
            Log.d(TAG, "Starting native audio (" + profile.name() + "), session " + mySession + "...");

            if (!isCurrent(mySession)) {
                Log.d(TAG, "Open aborted before start (session " + mySession + " superseded)");
                return;                          // nothing established yet
            }

            // Re-apply ALSA permissions: the audio HAL recreates /dev/snd/* nodes
            // (resetting perms to system:audio) when a call starts, so a chmod done
            // once at init no longer holds by the time we open the devices here.
            if (!RootHelper.setupAlsaPermissions()) {
                Log.e(TAG, "Failed to (re)apply ALSA permissions - open will likely fail");
            }

            // Setup SoC-specific mixer routing. The currency check and the patch
            // are one atomic step: otherwise stopCapture() could tear the mixer
            // down in between and we would re-patch it with nobody left to undo it.
            synchronized (stateLock) {
                if (!isCurrent(mySession)) {
                    Log.d(TAG, "Open aborted before mixer setup (session " + mySession + " superseded)");
                    return;                      // still nothing established
                }
                profile.setupMixer(card);
                mixerOwner = mySession;
            }

            boolean opened = false;
            int usedAttempt = 0;
            for (int attempt = 1; attempt <= OPEN_MAX_ATTEMPTS; attempt++) {
                if (!isCurrent(mySession)) {
                    Log.d(TAG, "Open aborted before attempt " + attempt
                            + " (session " + mySession + " superseded)");
                    releaseSession(mySession);
                    return;
                }
                // NOT under stateLock and NOT interruptible: this is the call that
                // can outlive stopCapture()'s join. It is safe only because the
                // generation check below runs before anything is published.
                opened = GsmAudioNative.open(
                    card, profile.captureDevice(), profile.playbackDevice(),
                    sampleRate, channels,
                    playbackRate, playbackChannels,
                    BITS, periodSize, playbackPeriod, PERIOD_COUNT
                );
                if (opened) { usedAttempt = attempt; break; }

                Log.w(TAG, "Open attempt " + attempt + "/" + OPEN_MAX_ATTEMPTS
                        + " failed; retrying in " + OPEN_RETRY_MS + "ms (voice path may not be ready yet)");
                try {
                    Thread.sleep(OPEN_RETRY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.d(TAG, "Open aborted during retry (session " + mySession + ")");
                    releaseSession(mySession);
                    return;
                }
                if (!isCurrent(mySession)) {
                    Log.d(TAG, "Open aborted after retry sleep (session " + mySession + " superseded)");
                    releaseSession(mySession);
                    return;
                }
            }

            if (!opened) {
                Log.e(TAG, "Failed to open native audio devices after " + OPEN_MAX_ATTEMPTS + " attempts!");
                Log.e(TAG, "Check: 1) Root access 2) Device permissions 3) Correct device numbers");
                synchronized (stateLock) {
                    // End our own session so a later startCapture() is not refused
                    // by the "already current" guard, then undo our mixer patch.
                    endSessionLocked(mySession);
                    releaseLocked(mySession);
                }
                return;
            }

            synchronized (stateLock) {
                // We opened the PCM pair; claim it so that whoever releases this
                // session closes it. Only claim if nobody newer already has it.
                if (pcmOwner == 0) {
                    pcmOwner = mySession;
                } else if (pcmOwner != mySession) {
                    Log.e(TAG, "Session " + mySession + " opened the PCM pair while session "
                            + pcmOwner + " still owns it - not claiming it");
                }

                if (!isCurrent(mySession)) {
                    // Superseded while the uninterruptible native open() was in
                    // flight. stopCapture() has already run and closed NOTHING,
                    // because nothing was open at the time it looked - so this
                    // worker owns the cleanup of both the device it opened and
                    // the mixer it patched.
                    Log.w(TAG, "Open for session " + mySession
                            + " completed after cancellation - releasing it");
                    releaseLocked(mySession);
                    return;
                }

                isCapturing.set(true);
                startEnforceThread(mySession);
            }

            Log.d(TAG, "Native audio started (session " + mySession + ", opened on attempt " + usedAttempt
                    + ", ~" + ((usedAttempt - 1) * OPEN_RETRY_MS) + "ms after start)");
        } catch (RuntimeException | java.lang.Error e) {
            // Never leave the mixer patched (mic muted) because the worker died,
            // e.g. UnsatisfiedLinkError if libgsm_audio.so failed to load.
            // (java.lang.Error is qualified: org.pjsip.pjsua2.* also exports "Error".)
            Log.e(TAG, "Open worker for session " + mySession + " failed unexpectedly", e);
            synchronized (stateLock) {
                endSessionLocked(mySession);
                releaseLocked(mySession);
            }
        } finally {
            synchronized (stateLock) {
                if (openThread == Thread.currentThread()) {
                    openThread = null;
                }
            }
        }
    }

    /** True while {@code gen} is still the current session generation. */
    private boolean isCurrent(int gen) {
        return sessionId.get() == gen;
    }

    /** True when {@code gen} denotes a running session (odd) rather than the idle state (even). */
    private static boolean isSessionActive(int gen) {
        return (gen & 1) != 0;
    }

    /** Ends session {@code gen} if it is still current, returning the counter to idle. */
    private void endSessionLocked(int gen) {
        if (sessionId.get() == gen) {
            sessionId.set(gen + 1);              // even => idle
        }
    }

    /** {@link #releaseLocked(int)} for callers that do not already hold {@link #stateLock}. */
    private void releaseSession(int gen) {
        synchronized (stateLock) {
            releaseLocked(gen);
        }
    }

    /**
     * Releases everything session {@code gen} established: the PCM pair it opened
     * and the mixer patch it applied. Both halves are ownership-checked, so a late
     * worker can never close a device or tear down a mixer that a NEWER session
     * has meanwhile established, and a resource is never released twice.
     *
     * The ownership check is also what guards the double teardown between this
     * path and {@link #stopCapture()}; {@code teardownMixer()} being idempotent
     * (GW-04) is the backstop underneath it, not the primary guard.
     *
     * Caller must hold {@link #stateLock}.
     */
    private void releaseLocked(int gen) {
        if (gen == 0) {
            return;                              // no session to release
        }
        if (pcmOwner == gen) {
            GsmAudioNative.close();
            pcmOwner = 0;
        }
        if (mixerOwner == gen) {
            profile.teardownMixer(card);
            mixerOwner = 0;
        }
    }

    /**
     * Start the background thread that re-asserts the profile's mixer routing
     * every {@link #ENFORCE_INTERVAL_MS} ms while capturing. The audio HAL tends
     * to re-assert its own routing (e.g. re-enabling the local mic) a moment
     * after a call connects, which would otherwise override our setup.
     */
    private void startEnforceThread(final int gen) {
        stopEnforceThread();
        // The loop is bounded by its own session generation as well as by
        // isCapturing, so that no MixerEnforce thread can outlive its session
        // even if it is somehow started late or missed its interrupt.
        // It deliberately does NOT take stateLock: stopEnforceThread() joins it
        // while holding that lock, and enforceMixer() touches no saved state
        // (AudioProfile contract), so no lock is needed here.
        Thread worker = new Thread(() -> {
            while (isCurrent(gen) && isCapturing.get()) {
                try {
                    Thread.sleep(ENFORCE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (isCurrent(gen) && isCapturing.get()) {
                    profile.enforceMixer(card);
                }
            }
            Log.d(TAG, "MixerEnforce for session " + gen + " exiting");
        }, "MixerEnforce-" + gen);
        enforceThread = worker;
        enforceOwner = gen;
        worker.start();
    }

    /** Caller must hold {@link #stateLock} (enforceOwner is guarded by it). */
    private void stopEnforceThread() {
        Thread worker = enforceThread;           // snapshot: written from several threads
        enforceThread = null;
        enforceOwner = 0;
        if (worker == null) {
            return;
        }
        worker.interrupt();
        try {
            // Join briefly so teardownMixer can't race a final enforceMixer.
            worker.join(ENFORCE_JOIN_MS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Stop audio capture/playback
     */
    public void stopCapture() {
        Log.d(TAG, "Stopping native audio...");

        final int ended;
        final Thread worker;
        synchronized (stateLock) {
            int current = sessionId.get();
            // Advance out of the current session FIRST. From this write onwards
            // any worker still in flight - including one stuck inside the
            // uninterruptible native open() - is superseded, will refuse to
            // publish, and will release whatever it established.
            if (isSessionActive(current)) {
                ended = current;
                sessionId.set(current + 1);      // even => idle
            } else {
                ended = 0;                       // nothing was running
            }
            isCapturing.set(false);
            worker = openThread;                 // snapshot before use
            openThread = null;
        }

        // Nudge a pending open worker (it may be sleeping between retries).
        // Done outside stateLock: the worker takes that lock to clean itself up.
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(OPEN_JOIN_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            // The join may well time out - GsmAudioNative.open() is a blocking
            // native call and does not observe the interrupt. That is safe now:
            // the generation bump above superseded the worker, so when open()
            // finally returns the worker releases the device itself instead of
            // re-arming capture behind our back (AUDIT B3).
            if (worker.isAlive()) {
                Log.w(TAG, "Open worker for session " + ended
                        + " outlived the join - it will release itself when open() returns");
            }
        }

        synchronized (stateLock) {
            // A new session can legitimately have started while we were joining
            // the worker (back-to-back calls); never stop ITS enforce thread.
            if (enforceOwner > ended) {
                Log.w(TAG, "Leaving MixerEnforce for newer session " + enforceOwner + " running");
            } else {
                stopEnforceThread();
            }
            // Close the device and unpatch the mixer, but only what THIS session
            // owns - for the same reason. Releasing nothing here is normal when
            // the worker never got as far as opening, or already released itself.
            releaseLocked(ended);
        }

        Log.d(TAG, "Native audio stopped. Stats: requested=" + framesRequested +
              ", received=" + framesReceived +
              ", captureErr=" + captureErrors + ", playbackErr=" + playbackErrors);

        // Reset statistics
        framesRequested = 0;
        framesReceived = 0;
        captureErrors = 0;
        playbackErrors = 0;
    }

    public void stop() {
        stopCapture();
    }

    public boolean isCapturing() {
        return isCapturing.get();
    }
}
