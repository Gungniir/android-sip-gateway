/*
 * GSM Audio JNI - Native tinyalsa integration for GSM-SIP Gateway
 *
 * Replaces tinycap/tinyplay processes with direct ALSA access.
 * All parameters are configurable - no hardcoded device paths.
 */

#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <pthread.h>
#include <errno.h>

#include "tinyalsa/include/tinyalsa/asoundlib.h"

#define TAG "GsmAudioNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

/* Audio context - holds all state */
struct gsm_audio_ctx {
    struct pcm *capture_pcm;
    struct pcm *playback_pcm;
    struct mixer *mixer;

    unsigned int card;
    unsigned int capture_device;
    unsigned int playback_device;
    unsigned int capture_rate;
    unsigned int capture_channels;
    unsigned int playback_rate;
    unsigned int playback_channels;
    unsigned int bits;
    unsigned int period_count;

    int is_open;
    pthread_mutex_t lock;
};

static struct gsm_audio_ctx *g_ctx = NULL;

/* Helper: Get PCM format from bits */
static enum pcm_format bits_to_format(unsigned int bits) {
    switch (bits) {
        case 32: return PCM_FORMAT_S32_LE;
        case 24: return PCM_FORMAT_S24_LE;
        case 16:
        default: return PCM_FORMAT_S16_LE;
    }
}

/*
 * Open a PCM, trying several period_size/period_count combos.
 * Some MediaTek memifs (notably playback dev 2 on the modem voice path) reject
 * the default small exact period/period-count at HW_PARAMS even though other
 * combos on the same device work. Returns an open+ready pcm, or NULL.
 * On success, *base is updated to the combo that worked.
 */
static struct pcm *open_pcm_adaptive(unsigned int card, unsigned int device,
                                     unsigned int flags, struct pcm_config *base) {
    /* (period_size, period_count) candidates, requested combo first. */
    unsigned int combos[][2] = {
        { base->period_size, base->period_count },
        { base->period_size, 2 },
        { 1024, 4 }, { 1024, 2 },
        { 1920, 4 }, { 960, 4 }, { 480, 4 }, { 240, 4 },
    };
    int n = (int)(sizeof(combos) / sizeof(combos[0]));
    for (int i = 0; i < n; i++) {
        struct pcm_config cfg = *base;
        cfg.period_size = combos[i][0];
        cfg.period_count = combos[i][1];
        struct pcm *p = pcm_open(card, device, flags, &cfg);
        if (p && pcm_is_ready(p)) {
            LOGI("PCM %u:%u (%s) opened: period_size=%u period_count=%u",
                 card, device, (flags & PCM_IN) ? "capture" : "playback",
                 combos[i][0], combos[i][1]);
            base->period_size = combos[i][0];
            base->period_count = combos[i][1];
            return p;
        }
        LOGE("PCM %u:%u open failed at period_size=%u count=%u: %s",
             card, device, combos[i][0], combos[i][1],
             p ? pcm_get_error(p) : "null");
        if (p) pcm_close(p);
    }
    return NULL;
}

/*
 * Open audio devices
 *
 * @param card          Sound card number (usually 0)
 * @param captureDevice PCM device for capture (VOC_REC)
 * @param playbackDevice PCM device for playback (Incall_Music)
 * @param sampleRate    Sample rate in Hz (8000 for GSM)
 * @param captureRate/captureChannels    Capture (GSM->SIP) format
 * @param playbackRate/playbackChannels  Playback (SIP->GSM) format
 * @param bits          Bits per sample (16)
 * @param capturePeriod/playbackPeriod   Period size (frames) per device
 * @param periodCount   Number of periods (4)
 *
 * Capture and playback may run at DIFFERENT rates: on MediaTek the modem-voice
 * playback memif locks to 48 kHz once the mute/inject routing is applied, while
 * the capture memif has an SRC and accepts 8 kHz. writeFrame() upsamples from
 * captureRate (= the PJSIP port rate) to playbackRate.
 */
JNIEXPORT jboolean JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_open(
        JNIEnv *env, jclass clazz,
        jint card, jint captureDevice, jint playbackDevice,
        jint captureRate, jint captureChannels,
        jint playbackRate, jint playbackChannels,
        jint bits, jint capturePeriod, jint playbackPeriod, jint periodCount) {

    LOGI("Opening audio: card=%d, capture=%d@%d/%dch, playback=%d@%d/%dch, bits=%d, period=%d/%d/%d",
         card, captureDevice, captureRate, captureChannels,
         playbackDevice, playbackRate, playbackChannels, bits,
         capturePeriod, playbackPeriod, periodCount);

    if (g_ctx != NULL && g_ctx->is_open) {
        LOGE("Already open, close first");
        return JNI_FALSE;
    }

    /* Allocate context */
    if (g_ctx == NULL) {
        g_ctx = (struct gsm_audio_ctx *)calloc(1, sizeof(struct gsm_audio_ctx));
        if (!g_ctx) {
            LOGE("Failed to allocate context");
            return JNI_FALSE;
        }
        pthread_mutex_init(&g_ctx->lock, NULL);
    }

    g_ctx->card = card;
    g_ctx->capture_device = captureDevice;
    g_ctx->playback_device = playbackDevice;
    g_ctx->capture_rate = captureRate;
    g_ctx->capture_channels = captureChannels;
    g_ctx->playback_rate = playbackRate;
    g_ctx->playback_channels = playbackChannels;
    g_ctx->bits = bits;
    g_ctx->period_count = periodCount;

    /* Per-device config templates.
     * NOTE: pcm_open() writes the granted period_size back into the config
     * struct, so each device gets its own copy. */
    struct pcm_config playback_config;
    memset(&playback_config, 0, sizeof(playback_config));
    playback_config.channels = playbackChannels;
    playback_config.rate = playbackRate;
    playback_config.period_size = playbackPeriod;
    playback_config.period_count = periodCount;
    playback_config.format = bits_to_format(bits);

    struct pcm_config capture_config;
    memset(&capture_config, 0, sizeof(capture_config));
    capture_config.channels = captureChannels;
    capture_config.rate = captureRate;
    capture_config.period_size = capturePeriod;
    capture_config.period_count = periodCount;
    capture_config.format = bits_to_format(bits);

    /* Open PLAYBACK first, then capture.
     * On MediaTek AFE, opening capture (dev 5) first and then playback (dev 2)
     * in the same process makes the playback hw_params ioctl fail with EINVAL
     * (the shared modem-voice backend ends up in a state the playback memif
     * rejects). Opening playback first - when it is the only stream on the
     * backend - succeeds, and capture then attaches cleanly. Verified on-device. */

    /* Open playback device (adaptive period) */
    g_ctx->playback_pcm = open_pcm_adaptive(card, playbackDevice, PCM_OUT, &playback_config);
    if (!g_ctx->playback_pcm) {
        LOGE("Failed to open playback PCM %d:%d (all period combos)", card, playbackDevice);
        return JNI_FALSE;
    }
    LOGI("Playback PCM opened: %d:%d", card, playbackDevice);

    /* Open capture device (adaptive period) */
    g_ctx->capture_pcm = open_pcm_adaptive(card, captureDevice, PCM_IN, &capture_config);
    if (!g_ctx->capture_pcm) {
        LOGE("Failed to open capture PCM %d:%d (all period combos)", card, captureDevice);
        pcm_close(g_ctx->playback_pcm);
        g_ctx->playback_pcm = NULL;
        return JNI_FALSE;
    }
    LOGI("Capture PCM opened: %d:%d", card, captureDevice);

    /* Open mixer */
    g_ctx->mixer = mixer_open(card);
    if (!g_ctx->mixer) {
        LOGE("Warning: Failed to open mixer for card %d", card);
        /* Continue anyway - mixer might not be needed */
    } else {
        LOGI("Mixer opened for card %d", card);
    }

    g_ctx->is_open = 1;
    return JNI_TRUE;
}

/*
 * Close audio devices
 */
JNIEXPORT void JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_close(JNIEnv *env, jclass clazz) {
    LOGI("Closing audio");

    if (g_ctx == NULL) return;

    pthread_mutex_lock(&g_ctx->lock);

    if (g_ctx->capture_pcm) {
        pcm_close(g_ctx->capture_pcm);
        g_ctx->capture_pcm = NULL;
    }

    if (g_ctx->playback_pcm) {
        pcm_close(g_ctx->playback_pcm);
        g_ctx->playback_pcm = NULL;
    }

    if (g_ctx->mixer) {
        mixer_close(g_ctx->mixer);
        g_ctx->mixer = NULL;
    }

    g_ctx->is_open = 0;

    pthread_mutex_unlock(&g_ctx->lock);
    LOGI("Audio closed");
}

/*
 * Read audio frame from capture device (GSM -> SIP direction)
 *
 * @param buffer Byte array to fill with PCM data
 * @return Number of bytes read, or -1 on error
 */
JNIEXPORT jint JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_readFrame(
        JNIEnv *env, jclass clazz, jbyteArray buffer) {

    if (g_ctx == NULL || !g_ctx->is_open || !g_ctx->capture_pcm) {
        return -1;
    }

    jsize len = (*env)->GetArrayLength(env, buffer);
    jbyte *buf = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (!buf) {
        LOGE("Failed to get byte array elements");
        return -1;
    }

    int ret = pcm_read(g_ctx->capture_pcm, buf, len);

    (*env)->ReleaseByteArrayElements(env, buffer, buf, 0);

    if (ret != 0) {
        LOGE("pcm_read failed: %s", pcm_get_error(g_ctx->capture_pcm));
        return -1;
    }

    return len;
}

/*
 * Write audio frame to playback device (SIP -> GSM direction)
 *
 * @param buffer Byte array with PCM data
 * @return Number of bytes written, or -1 on error
 */
JNIEXPORT jint JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_writeFrame(
        JNIEnv *env, jclass clazz, jbyteArray buffer) {

    if (g_ctx == NULL || !g_ctx->is_open || !g_ctx->playback_pcm) {
        return -1;
    }

    jsize len = (*env)->GetArrayLength(env, buffer);
    jbyte *buf = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (!buf) {
        LOGE("Failed to get byte array elements");
        return -1;
    }

    int ret;
    /* Fast path: capture (= PJSIP port) and playback formats match -> write directly. */
    if (g_ctx->capture_rate == g_ctx->playback_rate &&
        g_ctx->capture_channels == g_ctx->playback_channels) {
        ret = pcm_write(g_ctx->playback_pcm, buf, len);
    } else if (g_ctx->capture_channels == 1 && g_ctx->playback_channels == 1) {
        /* Upsample mono captureRate -> mono playbackRate via linear interpolation.
         * (MediaTek: PJSIP delivers 8 kHz mono; the modem playback memif needs
         * 48 kHz.) */
        const short *in = (const short *)buf;
        int in_n = len / 2;
        long out_n = (long)in_n * g_ctx->playback_rate / g_ctx->capture_rate;
        short *out = (short *)malloc((size_t)out_n * 2);
        if (!out) {
            (*env)->ReleaseByteArrayElements(env, buffer, buf, JNI_ABORT);
            LOGE("writeFrame: malloc failed for %ld out samples", out_n);
            return -1;
        }
        double step = (double)g_ctx->capture_rate / (double)g_ctx->playback_rate;
        for (long j = 0; j < out_n; j++) {
            double srcpos = j * step;
            int i0 = (int)srcpos;
            double frac = srcpos - i0;
            int i1 = (i0 + 1 < in_n) ? i0 + 1 : in_n - 1;
            out[j] = (short)(in[i0] * (1.0 - frac) + in[i1] * frac);
        }
        ret = pcm_write(g_ctx->playback_pcm, out, (unsigned int)(out_n * 2));
        free(out);
    } else {
        /* Unsupported channel conversion - write as-is (should not happen). */
        ret = pcm_write(g_ctx->playback_pcm, buf, len);
    }

    (*env)->ReleaseByteArrayElements(env, buffer, buf, JNI_ABORT);

    if (ret != 0) {
        LOGE("pcm_write failed: %s", pcm_get_error(g_ctx->playback_pcm));
        return -1;
    }

    return len;
}

/*
 * Set mixer control value
 *
 * @param card        Sound card number
 * @param controlName Mixer control name (e.g. "MultiMedia1 Mixer VOC_REC_DL")
 * @param value       Value to set (0 or 1 for switches)
 * @return true on success
 */
JNIEXPORT jboolean JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_setMixerControl(
        JNIEnv *env, jclass clazz,
        jint card, jstring controlName, jint value) {

    const char *name = (*env)->GetStringUTFChars(env, controlName, NULL);
    if (!name) {
        LOGE("Failed to get control name string");
        return JNI_FALSE;
    }

    LOGD("setMixerControl: card=%d, control='%s', value=%d", card, name, value);

    struct mixer *mix = mixer_open(card);
    if (!mix) {
        LOGE("Failed to open mixer for card %d", card);
        (*env)->ReleaseStringUTFChars(env, controlName, name);
        return JNI_FALSE;
    }

    struct mixer_ctl *ctl = mixer_get_ctl_by_name(mix, name);
    if (!ctl) {
        LOGE("Mixer control '%s' not found", name);
        mixer_close(mix);
        (*env)->ReleaseStringUTFChars(env, controlName, name);
        return JNI_FALSE;
    }

    int ret = mixer_ctl_set_value(ctl, 0, value);
    if (ret < 0) {
        LOGE("Failed to set mixer control '%s' to %d: %d", name, value, ret);
        mixer_close(mix);
        (*env)->ReleaseStringUTFChars(env, controlName, name);
        return JNI_FALSE;
    }

    LOGI("Set mixer control '%s' = %d", name, value);

    mixer_close(mix);
    (*env)->ReleaseStringUTFChars(env, controlName, name);
    return JNI_TRUE;
}

/*
 * Get mixer control integer value (value index 0)
 *
 * @param card        Sound card number
 * @param controlName Mixer control name
 * @return control value, or -1 if not found / on error
 */
JNIEXPORT jint JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_getMixerControl(
        JNIEnv *env, jclass clazz,
        jint card, jstring controlName) {

    const char *name = (*env)->GetStringUTFChars(env, controlName, NULL);
    if (!name) {
        return -1;
    }

    struct mixer *mix = mixer_open(card);
    if (!mix) {
        LOGE("Failed to open mixer for card %d", card);
        (*env)->ReleaseStringUTFChars(env, controlName, name);
        return -1;
    }

    struct mixer_ctl *ctl = mixer_get_ctl_by_name(mix, name);
    if (!ctl) {
        LOGE("Mixer control '%s' not found", name);
        mixer_close(mix);
        (*env)->ReleaseStringUTFChars(env, controlName, name);
        return -1;
    }

    int value = mixer_ctl_get_value(ctl, 0);

    mixer_close(mix);
    (*env)->ReleaseStringUTFChars(env, controlName, name);
    return value;
}

/*
 * Set mixer control ENUM value by string
 *
 * @param card        Sound card number
 * @param controlName Mixer control name (e.g. "DEC1 MUX")
 * @param value       String value to set (e.g. "ZERO", "ADC1", "ADC2")
 * @return true on success
 */
JNIEXPORT jboolean JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_setMixerControlEnum(
        JNIEnv *env, jclass clazz,
        jint card, jstring controlName, jstring value) {

    const char *name = (*env)->GetStringUTFChars(env, controlName, NULL);
    const char *val = (*env)->GetStringUTFChars(env, value, NULL);
    if (!name || !val) {
        LOGE("Failed to get control name or value string");
        if (name) (*env)->ReleaseStringUTFChars(env, controlName, name);
        if (val) (*env)->ReleaseStringUTFChars(env, value, val);
        return JNI_FALSE;
    }

    LOGD("setMixerControlEnum: card=%d, control='%s', value='%s'", card, name, val);

    struct mixer *mix = mixer_open(card);
    if (!mix) {
        LOGE("Failed to open mixer for card %d", card);
        (*env)->ReleaseStringUTFChars(env, controlName, name);
        (*env)->ReleaseStringUTFChars(env, value, val);
        return JNI_FALSE;
    }

    struct mixer_ctl *ctl = mixer_get_ctl_by_name(mix, name);
    if (!ctl) {
        LOGE("Mixer control '%s' not found", name);
        mixer_close(mix);
        (*env)->ReleaseStringUTFChars(env, controlName, name);
        (*env)->ReleaseStringUTFChars(env, value, val);
        return JNI_FALSE;
    }

    int ret = mixer_ctl_set_enum_by_string(ctl, val);
    if (ret < 0) {
        LOGE("Failed to set mixer control '%s' to '%s': %d", name, val, ret);
        mixer_close(mix);
        (*env)->ReleaseStringUTFChars(env, controlName, name);
        (*env)->ReleaseStringUTFChars(env, value, val);
        return JNI_FALSE;
    }

    LOGI("Set mixer control '%s' = '%s'", name, val);

    mixer_close(mix);
    (*env)->ReleaseStringUTFChars(env, controlName, name);
    (*env)->ReleaseStringUTFChars(env, value, val);
    return JNI_TRUE;
}

/*
 * Get list of mixer controls (for device discovery)
 *
 * @param card Sound card number
 * @return String array of control names, or null on error
 */
JNIEXPORT jobjectArray JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_getMixerControls(
        JNIEnv *env, jclass clazz, jint card) {

    struct mixer *mix = mixer_open(card);
    if (!mix) {
        LOGE("Failed to open mixer for card %d", card);
        return NULL;
    }

    unsigned int count = mixer_get_num_ctls(mix);
    LOGD("Card %d has %u mixer controls", card, count);

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = (*env)->NewObjectArray(env, count, stringClass, NULL);

    for (unsigned int i = 0; i < count; i++) {
        struct mixer_ctl *ctl = mixer_get_ctl(mix, i);
        if (ctl) {
            const char *name = mixer_ctl_get_name(ctl);
            jstring jname = (*env)->NewStringUTF(env, name ? name : "");
            (*env)->SetObjectArrayElement(env, result, i, jname);
            (*env)->DeleteLocalRef(env, jname);
        }
    }

    mixer_close(mix);
    return result;
}

/*
 * Check if audio is open
 */
JNIEXPORT jboolean JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_isOpen(JNIEnv *env, jclass clazz) {
    return (g_ctx != NULL && g_ctx->is_open) ? JNI_TRUE : JNI_FALSE;
}

/*
 * Get frame size in bytes
 */
JNIEXPORT jint JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_getFrameSize(JNIEnv *env, jclass clazz) {
    if (g_ctx == NULL || !g_ctx->is_open) {
        return 0;
    }
    /* 20ms capture frame: rate/50 samples * channels * bytes_per_sample */
    return (g_ctx->capture_rate / 50) * g_ctx->capture_channels * (g_ctx->bits / 8);
}

/*
 * Get list of PCM devices for a card
 * Returns array of strings: "device_num: name (capture/playback)"
 *
 * @param card Sound card number
 * @param isCapture true for capture devices, false for playback
 * @return String array of device descriptions
 */
JNIEXPORT jobjectArray JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_getPcmDevices(
        JNIEnv *env, jclass clazz, jint card, jboolean isCapture) {

    /* Read /proc/asound/pcm to get device info */
    FILE *fp = fopen("/proc/asound/pcm", "r");
    if (!fp) {
        LOGE("Failed to open /proc/asound/pcm");
        return NULL;
    }

    /* First pass: count matching devices */
    char line[256];
    int count = 0;
    char cardStr[8];
    snprintf(cardStr, sizeof(cardStr), "%02d-", card);

    while (fgets(line, sizeof(line), fp)) {
        if (strncmp(line, cardStr, 3) == 0) {
            /* Check if it's capture or playback */
            int hasCapture = (strstr(line, "capture") != NULL);
            int hasPlayback = (strstr(line, "playback") != NULL);
            if ((isCapture && hasCapture) || (!isCapture && hasPlayback)) {
                count++;
            }
        }
    }

    LOGD("Found %d %s devices on card %d", count, isCapture ? "capture" : "playback", card);

    /* Create result array */
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = (*env)->NewObjectArray(env, count, stringClass, NULL);

    /* Second pass: fill array */
    rewind(fp);
    int idx = 0;
    while (fgets(line, sizeof(line), fp) && idx < count) {
        if (strncmp(line, cardStr, 3) == 0) {
            int hasCapture = (strstr(line, "capture") != NULL);
            int hasPlayback = (strstr(line, "playback") != NULL);
            if ((isCapture && hasCapture) || (!isCapture && hasPlayback)) {
                /* Parse: "00-36: msm-pcm-voice-v2 (*) : : playback 1 : capture 1" */
                int devNum = 0;
                char devName[128] = "";

                /* Get device number after dash */
                char *dash = strchr(line, '-');
                if (dash) {
                    devNum = atoi(dash + 1);
                }

                /* Get device name (between ": " and next " :") */
                char *nameStart = strchr(line, ':');
                if (nameStart) {
                    nameStart += 2; /* skip ": " */
                    char *nameEnd = strstr(nameStart, " :");
                    if (nameEnd) {
                        int len = nameEnd - nameStart;
                        if (len > 0 && len < sizeof(devName)) {
                            strncpy(devName, nameStart, len);
                            devName[len] = '\0';
                        }
                    }
                }

                /* Format: "36: msm-pcm-voice-v2" */
                char formatted[160];
                snprintf(formatted, sizeof(formatted), "%d: %s", devNum, devName);

                jstring jstr = (*env)->NewStringUTF(env, formatted);
                (*env)->SetObjectArrayElement(env, result, idx, jstr);
                (*env)->DeleteLocalRef(env, jstr);
                idx++;
            }
        }
    }

    fclose(fp);
    return result;
}

/*
 * Get number of sound cards
 */
JNIEXPORT jint JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_getCardCount(JNIEnv *env, jclass clazz) {
    /* Check /proc/asound/cards */
    FILE *fp = fopen("/proc/asound/cards", "r");
    if (!fp) {
        return 0;
    }

    int maxCard = -1;
    char line[256];
    while (fgets(line, sizeof(line), fp)) {
        int cardNum;
        if (sscanf(line, " %d [", &cardNum) == 1) {
            if (cardNum > maxCard) maxCard = cardNum;
        }
    }

    fclose(fp);
    return maxCard + 1;
}
