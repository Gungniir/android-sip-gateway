package org.onetwoone.gateway.audio;

/**
 * SoC-specific audio routing profile for the GSM↔SIP call-audio bridge.
 *
 * Different SoCs expose the modem voice PCM through completely different ALSA
 * mixer topologies (Qualcomm: VOC_REC_DL/Incall_Music; MediaTek: the PCM_2
 * crossbar). A profile encapsulates the capture/playback PCM device numbers, the
 * ALSA sample format, and the mixer routing needed to tap the call, so
 * {@link org.onetwoone.gateway.GsmAudioPort} stays SoC-agnostic.
 *
 * <h3>Threading contract</h3>
 *
 * A profile is reached from three different threads and must be safe for all of
 * them (AUDIT B2):
 *
 * <ul>
 *   <li>{@link #setupMixer(int)} — the {@code GsmAudioOpen} thread.</li>
 *   <li>{@link #teardownMixer(int)} — main, a pjsua worker, or {@code ConfigReload}.</li>
 *   <li>{@link #enforceMixer(int)} — the {@code MixerEnforce} thread, every 2s.</li>
 * </ul>
 *
 * Implementations must therefore honour all of the following:
 *
 * <ul>
 *   <li><b>Setup and teardown are paired.</b> Every {@code setupMixer} is matched
 *       by exactly one {@code teardownMixer}, and the saved originals live in a
 *       single immutable object behind one {@code volatile} reference, built
 *       complete before it is published. A reader must never be able to observe a
 *       half-built or half-cleared collection of originals.</li>
 *   <li><b>Teardown is idempotent.</b> A {@code teardownMixer} with nothing saved
 *       is a logged no-op, never a partial teardown.</li>
 *   <li><b>Setup is self-guarding.</b> A {@code setupMixer} that finds a live
 *       snapshot (the previous session never tore down) must restore that snapshot
 *       first and log an error — it must never silently discard the originals, or
 *       the values it saves are its own mute values and the local microphone stays
 *       muted until the device reboots.</li>
 *   <li><b>{@code enforceMixer} never touches saved state</b> — see its own
 *       javadoc. It reads only the static control lists.</li>
 * </ul>
 *
 * Control names, the values written to them and the order they are written in are
 * per-SoC reverse-engineering results validated on real hardware. Do not change
 * them.
 */
public interface AudioProfile {

    /** Human-readable name for logging/UI (e.g. "Qualcomm", "MediaTek"). */
    String name();

    /** PCM capture device number (GSM far-end → SIP). */
    int captureDevice();

    /** PCM playback device number (SIP → GSM far-end). */
    int playbackDevice();

    /** Capture (GSM→SIP) ALSA sample rate in Hz. Also the PJSIP port rate. */
    int captureSampleRate();

    /** Capture (GSM→SIP) ALSA channel count. */
    int captureChannels();

    /**
     * Playback (SIP→GSM) ALSA sample rate in Hz. May differ from capture: on
     * MediaTek the modem playback memif locks to 48 kHz. The native layer
     * upsamples from captureSampleRate() to this rate.
     */
    int playbackSampleRate();

    /** Playback (SIP→GSM) ALSA channel count. */
    int playbackChannels();

    /**
     * Enable the mixer routing that taps the modem voice path (and, where the
     * SoC requires it, mutes the local mic into the uplink). Called when a GSM
     * call becomes active, before the PCM devices are opened, on the
     * {@code GsmAudioOpen} thread.
     *
     * Saves the prior value of every control it overwrites so
     * {@link #teardownMixer(int)} can put them back. If a previous session's
     * originals are still held (i.e. its teardown never ran), they are restored
     * first and the condition is logged as an error — they are never discarded.
     */
    void setupMixer(int card);

    /**
     * Restore every control touched by {@link #setupMixer(int)} to its prior value
     * and drop the saved originals. Called from main, a pjsua worker or
     * {@code ConfigReload}, i.e. never from the thread that ran the setup.
     *
     * Idempotent: with nothing saved (never set up, or already torn down) this is
     * a logged no-op.
     */
    void teardownMixer(int card);

    /**
     * Re-assert the routing/mic-mute set by {@link #setupMixer(int)} WITHOUT
     * touching the saved originals. Called periodically (every 2s, on the
     * {@code MixerEnforce} thread) while the bridge is active, to defeat the audio
     * HAL re-asserting its own routing shortly after a call connects. Must be
     * idempotent and safe to call repeatedly.
     *
     * Implementations must read <em>only</em> their static control lists here.
     * Reading or writing the saved originals from this thread would race the
     * setup/teardown pair, and saving originals here would capture the profile's
     * own mute values as if they were the device's idle state.
     */
    void enforceMixer(int card);

    /**
     * Whether {@link #setupMixer(int)} already handles muting the local mic.
     * When true, the caller must NOT run the Qualcomm-style DeviceMuteManager
     * (on MediaTek the mic mute is integral to the inject routing).
     */
    boolean handlesMicMute();
}
