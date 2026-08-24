package org.onetwoone.gateway;

import android.content.Context;
import android.util.Log;

import org.onetwoone.gateway.audio.AudioProfile;
import org.onetwoone.gateway.audio.AudioProfileFactory;
import org.onetwoone.gateway.config.GatewayConfig;
import org.pjsip.pjsua2.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
 * <h4>The two RT callbacks</h4>
 * {@link #onFrameRequested} and {@link #onFrameReceived} run on the pjmedia conference
 * clock thread, 50x/second each, <em>with {@code conf->mutex} held for their whole
 * duration</em>. Three rules follow and none of them are negotiable:
 * <ul>
 *   <li><b>No blocking, no unbounded allocation, no lock the control thread can hold
 *       across I/O.</b> GW-01's {@code io_acquire} is a short critical section; that is
 *       the only acceptable shape. A lock here would park the RT thread behind
 *       {@code close()}'s drain.</li>
 *   <li><b>ALSA stays inside these callbacks</b> for now, and that is deliberate. Holding
 *       {@code conf->mutex} across the {@code pcm_read} is what starves
 *       {@code pjmedia_conf_remove_port} (AUDIT E5) - but it is also the happens-before
 *       edge that proves our callback is not in flight when the port is removed, which is
 *       what keeps AUDIT A1 latent. Moving ALSA to a dedicated I/O thread breaks that edge
 *       and is <b>GW-23b</b>, which is gated on forcing GW-01's drain to actually execute
 *       on hardware and on bounding tinyalsa's unbounded {@code EPIPE} restart.</li>
 *   <li><b>Java-side allocation is zero.</b> GW-23a removed the per-element
 *       {@code ByteVector} loops, the {@code Short} boxing they caused, and the
 *       finalizable wrapper {@code MediaFrame.getBuf()} minted twice per frame. See
 *       {@link org.pjsip.pjsua2.PjByteVectorAccess} for what that costs in ABI coupling
 *       and how it is re-verified at runtime.</li>
 * </ul>
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

    /**
     * Pre-sized frame buffer handed to pjmedia on the capture path, and its raw C++
     * address. Allocated once here instead of being grown by {@code add()} 320 times per
     * frame; {@code MediaFrame.setBuf} then copies it into the frame in a single call.
     *
     * <p>Touched only by {@link #onFrameRequested}, i.e. only by the pjmedia RT thread.
     * Null when {@link #bulkFrameCopy} is false.
     */
    private final ByteVector txFrame;
    private final long txFramePtr;

    /**
     * Whether the bulk JNI copy path is usable (AUDIT H2).
     *
     * <p>Proven at construction, not assumed: the constructor round-trips a pattern
     * through both {@link GsmAudioNative#pjBufRead} / {@link GsmAudioNative#pjBufWrite}
     * and pjsua2's own generated per-element accessors, and only sets this when they
     * agree. A PJSIP rebuild that changed {@code std::vector}'s layout therefore costs
     * performance (the old loops come back) rather than corrupting memory. See
     * {@link org.pjsip.pjsua2.PjByteVectorAccess}.
     */
    private final boolean bulkFrameCopy;

    /**
     * Frame statistics. Written only by the pjmedia RT thread (once per frame per
     * direction), read and reset by {@link #stopCapture()} on the GatewayControl thread,
     * and readable at any time through the accessors below.
     *
     * <p>They were plain non-volatile {@code long}s, so the reset in {@code stopCapture()}
     * and every off-thread read were unsafely published. {@link AtomicLong} fixes that
     * without adding a lock: there is exactly ONE writer, the increments are uncontended,
     * and nothing here can park the RT thread behind {@code close()}'s drain - which is
     * what PHASE-1-PLAN §3b forbids and why GW-25 was told to stay out of this file.
     */
    private final AtomicLong framesRequested = new AtomicLong();
    private final AtomicLong framesReceived = new AtomicLong();
    private final AtomicLong captureErrors = new AtomicLong();
    private final AtomicLong playbackErrors = new AtomicLong();

    public GsmAudioPort(Context context, GatewayConfig config) {
        super();
        this.context = context.getApplicationContext();

        this.card = config.getAudioCard();
        this.profile = AudioProfileFactory.select(this.context, config);

        this.sampleRate = profile.captureSampleRate();
        this.channels = profile.captureChannels();
        this.periodSize = sampleRate * FRAME_TIME_MS / 1000;
        this.frameSize = frameSizeBytes(sampleRate, BITS, channels);
        this.playbackRate = profile.playbackSampleRate();
        this.playbackChannels = profile.playbackChannels();
        this.playbackPeriod = playbackRate * FRAME_TIME_MS / 1000;

        this.captureBuffer = new byte[frameSize];
        this.playbackBuffer = new byte[frameSize];

        ByteVector tx = null;
        long txPtr = 0;
        boolean bulk = false;
        try {
            tx = PjByteVectorAccess.allocate(frameSize);
            txPtr = PjByteVectorAccess.address(tx);
            bulk = verifyBulkFrameCopy(tx, txPtr, frameSize);
            if (!bulk) {
                Log.e(TAG, "Bulk frame copy unavailable (ByteVector ABI check failed)"
                        + " - falling back to per-element JNI. If PJSIP was rebuilt, see"
                        + " org.pjsip.pjsua2.PjByteVectorAccess.");
            }
        } catch (RuntimeException | java.lang.Error e) {
            // java.lang.Error is qualified: org.pjsip.pjsua2.* also exports "Error".
            Log.e(TAG, "Bulk frame copy unavailable - falling back to per-element JNI", e);
            bulk = false;
        }
        if (!bulk && tx != null) {
            tx.delete();                    // owned native memory; do not defer to a finalizer
        }
        this.bulkFrameCopy = bulk;
        this.txFrame = bulk ? tx : null;
        this.txFramePtr = bulk ? txPtr : 0;

        Log.i(TAG, "Profile=" + profile.name() + " card=" + card
                + " capture=" + profile.captureDevice() + "@" + sampleRate + "/" + channels + "ch"
                + " playback=" + profile.playbackDevice() + "@" + playbackRate + "/" + playbackChannels + "ch"
                + " frame=" + frameSize + "B"
                + " bulkCopy=" + bulkFrameCopy);
    }

    /**
     * Proves, on this device and against this build of {@code libpjsua2.so}, that the
     * native bulk copy and pjsua2's own per-element accessors see the same bytes at the
     * same offsets.
     *
     * <p>Runs once, off the RT thread, before any frame is moved. The order matters: the
     * size gate comes first (a wrong layout will not report exactly {@code size}), then
     * the read direction is validated against data written by pjsua2 itself, and only
     * then is a native write attempted. That way the first thing a mismatched layout
     * meets is a bounds-checked read, not a memcpy into an address we guessed.
     *
     * @return true if the bulk path may be used
     */
    private static boolean verifyBulkFrameCopy(ByteVector vec, long ptr, int size) {
        if (vec == null || ptr == 0 || size <= 0) {
            return false;
        }
        if (GsmAudioNative.pjBufSize(ptr) != size) {
            return false;
        }

        // Seed through pjsua2's generated accessor - that is the ground truth.
        for (int i = 0; i < size; i++) {
            vec.set(i, (short) ((i * 31 + 7) & 0xFF));
        }
        byte[] readBack = new byte[size];
        if (GsmAudioNative.pjBufRead(ptr, readBack, size) != size) {
            return false;
        }
        for (int i = 0; i < size; i++) {
            if ((readBack[i] & 0xFF) != ((i * 31 + 7) & 0xFF)) {
                return false;
            }
        }

        // Now the other direction: write natively, verify through pjsua2.
        byte[] probe = new byte[size];
        for (int i = 0; i < size; i++) {
            probe[i] = (byte) ((i * 17 + 3) & 0xFF);
        }
        if (GsmAudioNative.pjBufWrite(ptr, probe, size) != size) {
            return false;
        }
        for (int i = 0; i < size; i++) {
            if (vec.get(i) != (short) ((i * 17 + 3) & 0xFF)) {
                return false;
            }
        }

        // Leave it silent rather than full of test pattern.
        return GsmAudioNative.pjBufWrite(ptr, new byte[size], size) == size;
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
     *
     * <p>Runs on the pjmedia conference clock thread, 50×/s, holding {@code conf->mutex}
     * for its whole duration. Nothing here may block, allocate unboundedly, or take a
     * lock the control thread can hold across I/O.
     */
    @Override
    public void onFrameRequested(MediaFrame frame) {
        final long requested = framesRequested.incrementAndGet();

        try {
            // No GsmAudioNative.isOpen() pre-check: readFrame() tests is_open under the
            // native lock inside io_acquire() and returns 0 for "closed" (AUDIT H2b).
            if (isCapturing.get()) {
                // Read from native ALSA
                int bytesRead = GsmAudioNative.readFrame(captureBuffer);

                if (bytesRead == frameSize && publishCapturedFrame(frame)) {
                    frame.setSize(frameSize);
                    frame.setType(pjmedia_frame_type.PJMEDIA_FRAME_TYPE_AUDIO);
                } else {
                    // 0 means the device closed under us - the ordinary end-of-call race,
                    // which the old isOpen() pre-check used to swallow. Only a real ALSA
                    // failure counts as an error, so the counter stays comparable.
                    if (bytesRead != 0) {
                        captureErrors.incrementAndGet();
                    }
                    noData(frame);
                }
            } else {
                noData(frame);
            }

            // Log every 500 frames (~10 seconds)
            if (requested % 500 == 0) {
                Log.d(TAG, "onFrameRequested: " + requested + " frames, errors=" + captureErrors.get());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onFrameRequested: " + e.getMessage());
        }
    }

    /**
     * Hands {@link #captureBuffer} to pjmedia. Returns false if the copy failed, in which
     * case the caller must send {@link #noData}.
     *
     * <p>Fast path: fill the pre-sized {@link #txFrame} with one memcpy and let
     * {@code setBuf} assign it into the frame — 2 JNI calls where the loop below needs
     * {@code frameSize}&nbsp;+&nbsp;2, plus ~160 {@code Short} boxes and ~10
     * {@code std::vector} growth reallocations (AUDIT H2).
     *
     * <p>One C++ allocation per frame remains and cannot be removed from here:
     * pjsua2's {@code get_frame} stack-constructs a fresh {@code MediaFrame} every tick,
     * so its {@code buf} always starts empty and must acquire storage once, whether that
     * comes from {@code setBuf}'s copy-assign or from a {@code reserve()}. Removing it
     * would mean writing the vector's control block from C, which
     * {@link org.pjsip.pjsua2.PjByteVectorAccess} deliberately refuses to do. Java-side
     * allocation on this path is now zero, which is what the GC-pause dropouts were
     * about.
     */
    private boolean publishCapturedFrame(MediaFrame frame) {
        if (bulkFrameCopy) {
            if (GsmAudioNative.pjBufWrite(txFramePtr, captureBuffer, frameSize) != frameSize) {
                return false;
            }
            frame.setBuf(txFrame);
            return true;
        }
        ByteVector buf = frame.getBuf();
        if (buf == null) {
            return false;
        }
        buf.clear();
        for (byte b : captureBuffer) {
            buf.add((short) (b & 0xFF));
        }
        return true;
    }

    /**
     * Hand pjmedia an empty {@code PJMEDIA_FRAME_TYPE_NONE} frame: we have no audio this
     * tick, either because capture is not running or because the ALSA read failed.
     *
     * <p>It used to write {@code frameSize} zero bytes into the frame buffer one boxed
     * {@code Short} at a time and then declare {@code size = frameSize}. Every byte of
     * that was dead work, and the claim was internally inconsistent. Confirmed against
     * the sources this build actually links, pjproject <b>2.14.1</b>:
     * <ul>
     *   <li>{@code pjsua2/media.cpp:get_frame} sets
     *       {@code frame->size = PJ_MIN(frame_.buf.size(), frame_.size)} and only then
     *       memcpy's — so an empty vector yields a 0-byte copy no matter what
     *       {@code setSize} said. Declaring 320 over a 0-length vector was never a read
     *       of unset memory, but it was a lie the clamp happened to absorb.</li>
     *   <li>{@code pjmedia/conference.c:get_frame} does
     *       {@code if (frame_type != PJMEDIA_FRAME_TYPE_AUDIO) { rx_level = 0; continue; }}
     *       on the direct path — the buffer is never read — and on the resampling path
     *       {@code read_port} calls {@code pjmedia_zero_samples()} itself. Either way our
     *       silence was overwritten or ignored.</li>
     * </ul>
     * So {@code size = 0} is both cheaper and the honest description. Do not re-add a
     * {@code setSize(frameSize)} here without re-reading both of those functions: with a
     * zero-length vector it would be a size that does not match its buffer, and only the
     * {@code PJ_MIN} clamp stands between that and a bad copy.
     */
    private static void noData(MediaFrame frame) {
        frame.setSize(0);
        frame.setType(pjmedia_frame_type.PJMEDIA_FRAME_TYPE_NONE);
    }

    /**
     * PJSIP callback: RECEIVED audio from SIP peer (SIP → GSM direction)
     *
     * <p>Same RT constraints as {@link #onFrameRequested}.
     */
    @Override
    public void onFrameReceived(MediaFrame frame) {
        final long received = framesReceived.incrementAndGet();

        try {
            // No GsmAudioNative.isOpen() pre-check here either - writeFrame() reports a
            // closed device as 0 and never touches a freed PCM (AUDIT H2b).
            if (!isCapturing.get()) {
                return;
            }

            final int size = usableFrameBytes(frame.getSize(), frameSize);

            if (size > 0) {
                if (fetchFrameBytes(frame, size)) {
                    // Write to native ALSA. The length is explicit: playbackBuffer is
                    // sized for a full frame and is reused, so handing the whole array
                    // over would append the tail of the PREVIOUS frame to a short one
                    // (AUDIT H2e).
                    int bytesWritten = GsmAudioNative.writeFrame(playbackBuffer, size);
                    if (bytesWritten < 0) {
                        playbackErrors.incrementAndGet();
                    }
                } else {
                    playbackErrors.incrementAndGet();
                }
            }

            // Log every 500 frames (~10 seconds)
            if (received % 500 == 0) {
                Log.d(TAG, "onFrameReceived: " + received + " frames, errors=" + playbackErrors.get());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onFrameReceived: " + e.getMessage());
        }
    }

    /**
     * Copies the first {@code size} bytes of the frame into {@link #playbackBuffer}.
     *
     * <p>Fast path: one memcpy straight out of pjmedia's vector. It reads the frame's
     * buffer address through the generated accessor rather than {@code frame.getBuf()},
     * which would allocate a fresh <em>finalizable</em> {@code ByteVector} wrapper on the
     * RT thread every single frame (AUDIT H2).
     *
     * @return false if the bytes could not be obtained; the caller counts that as an error
     */
    private boolean fetchFrameBytes(MediaFrame frame, int size) {
        if (bulkFrameCopy) {
            long bufPtr = PjByteVectorAccess.bufAddress(frame);
            return bufPtr != 0
                    && GsmAudioNative.pjBufRead(bufPtr, playbackBuffer, size) == size;
        }
        ByteVector buf = frame.getBuf();
        if (buf == null) {
            return false;
        }
        for (int i = 0; i < size; i++) {
            playbackBuffer[i] = (byte) (buf.get(i) & 0xFF);
        }
        return true;
    }

    /**
     * Bytes in one {@link #FRAME_TIME_MS} ms PCM frame at the given format.
     *
     * <p>Package-visible and static so the arithmetic can be tested without a live pjsua2
     * endpoint. For the gateway's 8 kHz / 16-bit / mono port this is <b>320</b> bytes, not
     * 160: the count is bytes, not samples, which is why the per-element JNI loops this
     * ticket removed ran 320 times and not 160.
     */
    static int frameSizeBytes(int sampleRate, int bits, int channels) {
        return sampleRate * (bits / 8) * channels * FRAME_TIME_MS / 1000;
    }

    /**
     * How many bytes of a received {@link MediaFrame} are usable audio, given the
     * capacity of the reusable playback buffer.
     *
     * <p>Returns 0 for "drop this frame": a non-positive size, or one larger than the
     * buffer can hold (which would mean the negotiated format changed under us). The
     * non-zero result is the length that MUST be handed to
     * {@link GsmAudioNative#writeFrame} - the buffer is reused, so anything past it is
     * the previous frame (AUDIT H2e).
     *
     * <p>Package-visible and static purely so it is testable.
     */
    static int usableFrameBytes(long reportedSize, int bufferCapacity) {
        if (reportedSize <= 0 || reportedSize > bufferCapacity) {
            return 0;
        }
        return (int) reportedSize;
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

        // Read and reset in one step so a frame delivered between the log and the reset
        // is not silently discarded from the next session's counts.
        Log.d(TAG, "Native audio stopped. Stats: requested=" + framesRequested.getAndSet(0) +
              ", received=" + framesReceived.getAndSet(0) +
              ", captureErr=" + captureErrors.getAndSet(0) +
              ", playbackErr=" + playbackErrors.getAndSet(0));
    }

    public void stop() {
        stopCapture();
    }

    public boolean isCapturing() {
        return isCapturing.get();
    }

    /**
     * Frames pjmedia has asked us for in the current capture session (GSM&rarr;SIP).
     *
     * <p>Safe to call from any thread and cheap - a single volatile read, no lock. It is
     * the "is the bridge actually moving audio" signal AUDIT D6 / GW-25 wanted; combined
     * with {@link #isCapturing()} it distinguishes "streams running and pumping" from
     * "streams running and silent". Reset to 0 by {@link #stopCapture()}.
     */
    public long getFramesRequested() {
        return framesRequested.get();
    }

    /** Frames pjmedia has handed us in the current capture session (SIP&rarr;GSM). */
    public long getFramesReceived() {
        return framesReceived.get();
    }

    /** Failed native reads in the current capture session. */
    public long getCaptureErrors() {
        return captureErrors.get();
    }

    /** Failed native writes in the current capture session. */
    public long getPlaybackErrors() {
        return playbackErrors.get();
    }
}
