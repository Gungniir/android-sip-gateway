package org.onetwoone.gateway.audio;

/**
 * SoC-specific audio routing profile for the GSM↔SIP call-audio bridge.
 *
 * Different SoCs expose the modem voice PCM through completely different ALSA
 * mixer topologies (Qualcomm: VOC_REC_DL/Incall_Music; MediaTek: the PCM_2
 * crossbar). A profile encapsulates the capture/playback PCM device numbers, the
 * ALSA sample format, and the mixer routing needed to tap the call, so
 * {@link org.onetwoone.gateway.GsmAudioPort} stays SoC-agnostic.
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
     * call becomes active, before the PCM devices are opened.
     */
    void setupMixer(int card);

    /** Restore every control touched by {@link #setupMixer(int)} to its prior value. */
    void teardownMixer(int card);

    /**
     * Re-assert the routing/mic-mute set by {@link #setupMixer(int)} WITHOUT
     * touching the saved originals. Called periodically while the bridge is
     * active to defeat the audio HAL re-asserting its own routing shortly after
     * a call connects. Must be idempotent and safe to call repeatedly.
     */
    void enforceMixer(int card);

    /**
     * Whether {@link #setupMixer(int)} already handles muting the local mic.
     * When true, the caller must NOT run the Qualcomm-style DeviceMuteManager
     * (on MediaTek the mic mute is integral to the inject routing).
     */
    boolean handlesMicMute();
}
