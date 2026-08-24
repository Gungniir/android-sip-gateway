/*
 * GSM Audio JNI - Native tinyalsa integration for GSM-SIP Gateway
 *
 * Replaces tinycap/tinyplay processes with direct ALSA access.
 * All parameters are configurable - no hardcoded device paths.
 *
 * Concurrency (see docs/refactor/issues/GW-01, AUDIT A1/A2)
 * --------------------------------------------------------
 * Three kinds of thread reach this file:
 *   - the pjmedia real-time thread, 50x/second, in readFrame()/writeFrame();
 *   - whichever thread ends a call (main / pjsua worker / ConfigReload), in close();
 *   - the GsmAudioOpen worker, in open().
 *
 * Every field of g_ctx is guarded by g_ctx->lock. open() and close() hold it across
 * their whole body, so a half-built context is never observable.
 *
 * The hot path must NOT hold the lock across pcm_read()/pcm_write(): that would make
 * close() block a full ALSA period behind a reader, and would serialise capture against
 * playback. Instead readFrame()/writeFrame() take a *reference* to the pcm under the
 * lock (io_acquire()) and drop it afterwards (io_release()). close() flips is_open to
 * 0, issues pcm_stop() on both PCMs while still holding the lock - the DROP ioctl is
 * what wakes a reader parked in the kernel - then waits, bounded, for the reference
 * count to reach zero before freeing anything.
 *
 * Nothing on the read/write path allocates.
 */

#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <stddef.h>
#include <stdint.h>
#include <pthread.h>
#include <errno.h>
#include <time.h>

#include "tinyalsa/include/tinyalsa/asoundlib.h"

#define TAG "GsmAudioNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

/* Upper bound on how long close() will wait for in-flight pcm_read/pcm_write to return.
 * One ALSA period is ~20 ms, so this is ~12 periods of head-room. It MUST stay bounded:
 * an unbounded wait would turn a rare crash into a deterministic hang on every hangup. */
#define IO_DRAIN_TIMEOUT_MS 250

/* Audio context - holds all state. Every field is guarded by `lock`. */
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
    int active_io;              /* readers + writers currently inside pcm_read/pcm_write */

    /* Upsample scratch for writeFrame()'s resample branch (AUDIT H3). Allocated once per
     * open() and freed in close() AFTER the I/O drain, so a writer holding an io_ref can
     * never be using it while it is freed - exactly the discipline the PCM handles get.
     * Sized for the largest 20 ms frame the playback device can take. */
    short *resample_buf;
    unsigned int resample_samples;   /* capacity of resample_buf, in samples */

    pthread_mutex_t lock;
    pthread_cond_t io_drained;  /* broadcast when active_io falls to 0 */
};

/* Statically allocated on purpose: the lock has to exist before any thread can reach it
 * (the old lazy calloc() in open() was itself a race - AUDIT A2), and a context that is
 * never freed can never be freed out from under a racing caller. */
static struct gsm_audio_ctx g_ctx_storage = {
    .lock = PTHREAD_MUTEX_INITIALIZER,
    /* io_drained is initialised in JNI_OnLoad, against CLOCK_MONOTONIC. */
};
static struct gsm_audio_ctx *const g_ctx = &g_ctx_storage;

/*
 * Library entry point. Initialises the drain condvar alongside the (statically
 * initialised) mutex, and pins it to CLOCK_MONOTONIC so the bounded wait in close()
 * cannot be stretched by a wall-clock jump - an Android phone resets its realtime clock
 * from NITZ/NTP at arbitrary moments. Runs once, before any native method below can be
 * called; the condvar is never destroyed.
 */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;

    pthread_condattr_t attr;
    pthread_condattr_init(&attr);
    pthread_condattr_setclock(&attr, CLOCK_MONOTONIC);
    pthread_cond_init(&g_ctx->io_drained, &attr);
    pthread_condattr_destroy(&attr);

    return JNI_VERSION_1_6;
}

/*
 * A borrowed reference to one open PCM, plus the format fields the write path needs.
 * Snapshotted under the lock so the hot path never touches g_ctx unlocked. Callers put
 * this on the stack - io_acquire()/io_release() allocate nothing.
 */
struct io_ref {
    struct pcm  *pcm;
    unsigned int capture_rate;
    unsigned int capture_channels;
    unsigned int playback_rate;
    unsigned int playback_channels;
    short       *resample_buf;      /* borrowed for as long as the reference is held */
    unsigned int resample_samples;
};

/*
 * Take a reference to the capture (capture != 0) or playback PCM. While the reference is
 * held, close() cannot free it: it sees active_io > 0 and waits.
 *
 * @return 1 on success - the caller MUST balance it with io_release();
 *         0 if the device is closed - the caller must NOT call io_release().
 */
static int io_acquire(int capture, struct io_ref *out) {
    pthread_mutex_lock(&g_ctx->lock);

    struct pcm *p = capture ? g_ctx->capture_pcm : g_ctx->playback_pcm;
    if (!g_ctx->is_open || !p) {
        pthread_mutex_unlock(&g_ctx->lock);
        return 0;
    }

    out->pcm               = p;
    out->capture_rate      = g_ctx->capture_rate;
    out->capture_channels  = g_ctx->capture_channels;
    out->playback_rate     = g_ctx->playback_rate;
    out->playback_channels = g_ctx->playback_channels;
    out->resample_buf      = g_ctx->resample_buf;
    out->resample_samples  = g_ctx->resample_samples;
    g_ctx->active_io++;

    pthread_mutex_unlock(&g_ctx->lock);
    return 1;
}

/* Drop a reference taken by io_acquire() and wake a waiting close() on the last one. */
static void io_release(void) {
    pthread_mutex_lock(&g_ctx->lock);
    if (--g_ctx->active_io == 0) {
        pthread_cond_broadcast(&g_ctx->io_drained);
    }
    pthread_mutex_unlock(&g_ctx->lock);
}

/*
 * Wait for every in-flight pcm_read/pcm_write to return. Must be called with the lock
 * held (pthread_cond_timedwait drops it while waiting, which is what lets io_release()
 * run). Bounded: on timeout it logs loudly and returns so close() can never hang.
 */
static void drain_io_locked(void) {
    if (g_ctx->active_io == 0) {
        return;
    }

    struct timespec deadline;
    clock_gettime(CLOCK_MONOTONIC, &deadline);
    deadline.tv_sec  += IO_DRAIN_TIMEOUT_MS / 1000;
    deadline.tv_nsec += (long)(IO_DRAIN_TIMEOUT_MS % 1000) * 1000000L;
    if (deadline.tv_nsec >= 1000000000L) {
        deadline.tv_nsec -= 1000000000L;
        deadline.tv_sec  += 1;
    }

    LOGI("close: draining %d in-flight PCM I/O", g_ctx->active_io);

    while (g_ctx->active_io > 0) {
        int rc = pthread_cond_timedwait(&g_ctx->io_drained, &g_ctx->lock, &deadline);
        if (rc == 0) {
            continue;
        }
        LOGE("!!! PCM drain gave up after %d ms with %d I/O still in flight (rc=%d) - "
             "closing anyway. A racing pcm_read/pcm_write may now touch freed memory; "
             "if this ever fires, pcm_stop() is not waking the reader.",
             IO_DRAIN_TIMEOUT_MS, g_ctx->active_io, rc);
        return;
    }

    LOGD("close: PCM I/O drained");
}

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
 *
 * The entire body runs under g_ctx->lock: the fields written here are read by the
 * pjmedia RT thread through io_acquire(), and a close() racing an open() must observe
 * one or the other, never a half-built context (AUDIT A2).
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

    pthread_mutex_lock(&g_ctx->lock);

    if (g_ctx->is_open) {
        LOGE("Already open, close first");
        pthread_mutex_unlock(&g_ctx->lock);
        return JNI_FALSE;
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
     * struct, so each device gets its own copy. Do NOT collapse these two into a
     * single shared struct - capture and playback negotiate different periods (and,
     * on MediaTek, different rates) and the second open would inherit the first
     * device's granted values. */
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

    /* Open PLAYBACK first, then capture. This ORDER IS LOAD-BEARING - do not swap it.
     * On MediaTek AFE, opening capture (dev 5) first and then playback (dev 2)
     * in the same process makes the playback hw_params ioctl fail with EINVAL
     * (the shared modem-voice backend ends up in a state the playback memif
     * rejects). Opening playback first - when it is the only stream on the
     * backend - succeeds, and capture then attaches cleanly. Verified on-device. */

    /* Open playback device (adaptive period) - FIRST, see above */
    g_ctx->playback_pcm = open_pcm_adaptive(card, playbackDevice, PCM_OUT, &playback_config);
    if (!g_ctx->playback_pcm) {
        LOGE("Failed to open playback PCM %d:%d (all period combos)", card, playbackDevice);
        pthread_mutex_unlock(&g_ctx->lock);
        return JNI_FALSE;
    }
    LOGI("Playback PCM opened: %d:%d", card, playbackDevice);

    /* Open capture device (adaptive period) - SECOND, see above */
    g_ctx->capture_pcm = open_pcm_adaptive(card, captureDevice, PCM_IN, &capture_config);
    if (!g_ctx->capture_pcm) {
        LOGE("Failed to open capture PCM %d:%d (all period combos)", card, captureDevice);
        pcm_close(g_ctx->playback_pcm);
        g_ctx->playback_pcm = NULL;
        pthread_mutex_unlock(&g_ctx->lock);
        return JNI_FALSE;
    }
    LOGI("Capture PCM opened: %d:%d", card, captureDevice);

    /* Upsample scratch (AUDIT H3). writeFrame() used to malloc()/free() this on every
     * frame, 50x/second, on the pjmedia RT thread. A 20 ms frame can never produce more
     * than playback_rate/50 samples per channel, and the PJSIP port format is fixed for
     * the life of the port, so one allocation here covers every frame.
     * Only the MediaTek profile (capture 8 kHz, playback 48 kHz) ever reads it; Qualcomm
     * has capture_rate == playback_rate and takes the no-copy fast path. */
    g_ctx->resample_samples = (unsigned int)(playbackRate / 50) * (unsigned int)playbackChannels;
    if (g_ctx->resample_samples > 0) {
        g_ctx->resample_buf = (short *)malloc((size_t)g_ctx->resample_samples * sizeof(short));
        if (!g_ctx->resample_buf) {
            LOGE("Failed to allocate %u-sample upsample scratch", g_ctx->resample_samples);
            g_ctx->resample_samples = 0;
            pcm_close(g_ctx->capture_pcm);
            g_ctx->capture_pcm = NULL;
            pcm_close(g_ctx->playback_pcm);
            g_ctx->playback_pcm = NULL;
            pthread_mutex_unlock(&g_ctx->lock);
            return JNI_FALSE;
        }
        LOGI("Upsample scratch: %u samples (%u bytes)", g_ctx->resample_samples,
             g_ctx->resample_samples * (unsigned int)sizeof(short));
    }

    /* Open mixer */
    g_ctx->mixer = mixer_open(card);
    if (!g_ctx->mixer) {
        LOGE("Warning: Failed to open mixer for card %d", card);
        /* Continue anyway - mixer might not be needed */
    } else {
        LOGI("Mixer opened for card %d", card);
    }

    /* Published last: io_acquire() hands out references only once this is set. */
    g_ctx->is_open = 1;

    pthread_mutex_unlock(&g_ctx->lock);
    return JNI_TRUE;
}

/*
 * Close audio devices.
 *
 * Called from whichever thread ends the call (main, a pjsua worker, or ConfigReload)
 * while the pjmedia RT thread is, with high probability, blocked inside pcm_read().
 * pcm_close() free()s the struct pcm, so it must not run until every in-flight
 * pcm_read/pcm_write has returned (AUDIT A1).
 *
 * Blocks the caller for up to IO_DRAIN_TIMEOUT_MS. Never call it from the RT thread.
 */
JNIEXPORT void JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_close(JNIEnv *env, jclass clazz) {
    LOGI("Closing audio");

    pthread_mutex_lock(&g_ctx->lock);

    /* 1. No new I/O may start: io_acquire() refuses from here on. */
    g_ctx->is_open = 0;

    /* 2. Wake whoever is already parked in the kernel. pcm_stop() issues
     *    SNDRV_PCM_IOCTL_DROP, which aborts a blocked READI/WRITEI; without it a reader
     *    sits in pcm_read() for up to a full period and the drain below would time out.
     *    This happens under the lock, before anything is freed, so the structs a reader
     *    might be touching are still valid. */
    if (g_ctx->capture_pcm && pcm_stop(g_ctx->capture_pcm) != 0) {
        LOGD("pcm_stop(capture) returned an error (harmless if already stopped): %s",
             pcm_get_error(g_ctx->capture_pcm));
    }
    if (g_ctx->playback_pcm && pcm_stop(g_ctx->playback_pcm) != 0) {
        LOGD("pcm_stop(playback) returned an error (harmless if already stopped): %s",
             pcm_get_error(g_ctx->playback_pcm));
    }

    /* 3. Bounded wait for in-flight readers/writers to leave. */
    drain_io_locked();

    /* 4. Only now is it safe to free. */
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

    /* Freed here, after the drain, for the same reason the PCM handles are: a writer that
     * took an io_ref borrowed this pointer and may still be interpolating into it. */
    if (g_ctx->resample_buf) {
        free(g_ctx->resample_buf);
        g_ctx->resample_buf = NULL;
    }
    g_ctx->resample_samples = 0;

    pthread_mutex_unlock(&g_ctx->lock);
    LOGI("Audio closed");
}

/*
 * Read audio frame from capture device (GSM -> SIP direction)
 *
 * Runs on the pjmedia RT thread, 50x/second. Allocates nothing and holds no lock across
 * the blocking pcm_read(); the io_ref keeps the PCM alive for exactly that long.
 *
 * This is the ONLY open check the caller needs (AUDIT H2b): is_open is tested inside
 * io_acquire() under the lock, so a Java-side isOpen() pre-check was a third acquisition
 * of the same mutex per frame per direction, deciding nothing this call does not decide.
 *
 * @param buffer Byte array to fill with PCM data
 * @return Number of bytes read; 0 if the device is closed (NOT an error - it is the
 *         normal race at end of call); -1 if pcm_read() itself failed.
 */
JNIEXPORT jint JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_readFrame(
        JNIEnv *env, jclass clazz, jbyteArray buffer) {

    jsize len = (*env)->GetArrayLength(env, buffer);
    jbyte *buf = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (!buf) {
        LOGE("Failed to get byte array elements");
        return -1;
    }

    /* Reference taken after the JNI pin, so a GC stall inside GetByteArrayElements() is
     * not something close() has to wait out. */
    struct io_ref io;
    if (!io_acquire(1, &io)) {
        (*env)->ReleaseByteArrayElements(env, buffer, buf, JNI_ABORT);
        return 0;                        /* closed, not broken - see the contract above */
    }

    int ret = pcm_read(io.pcm, buf, len);
    if (ret != 0) {
        /* pcm_get_error() dereferences io.pcm - still alive, the reference is held. */
        LOGE("pcm_read failed: %s", pcm_get_error(io.pcm));
    }

    io_release();

    (*env)->ReleaseByteArrayElements(env, buffer, buf, 0);

    return (ret != 0) ? -1 : (jint)len;
}

/*
 * Write audio frame to playback device (SIP -> GSM direction)
 *
 * Runs on the pjmedia RT thread, 50x/second. Holds no lock across the blocking
 * pcm_write(); the io_ref keeps the PCM alive for exactly that long and carries the
 * rate/channel snapshot and the preallocated upsample scratch the resampler needs, so no
 * g_ctx field is read unlocked and nothing here allocates (AUDIT H3).
 *
 * Like readFrame(), this is the only open check the caller needs (AUDIT H2b).
 *
 * `length` is explicit because the caller's buffer is sized for the largest possible frame
 * and pjmedia may hand it a shorter one. Using GetArrayLength() here instead - as this
 * function used to - writes the untouched tail of the previous frame back out to the
 * modem (AUDIT H2e).
 *
 * @param buffer Byte array with PCM data
 * @param length How many bytes of `buffer` are this frame; must be 0..buffer.length
 * @return Number of bytes accepted; 0 if the device is closed (NOT an error); -1 if
 *         pcm_write() itself failed, the length is out of range, or the frame does not
 *         fit the upsample scratch.
 */
JNIEXPORT jint JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_writeFrame(
        JNIEnv *env, jclass clazz, jbyteArray buffer, jint length) {

    jsize capacity = (*env)->GetArrayLength(env, buffer);
    if (length < 0 || length > capacity) {
        LOGE("writeFrame: length %d out of range for a %d-byte buffer", length, capacity);
        return -1;
    }
    jsize len = (jsize)length;
    jbyte *buf = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (!buf) {
        LOGE("Failed to get byte array elements");
        return -1;
    }

    struct io_ref io;
    if (!io_acquire(0, &io)) {
        (*env)->ReleaseByteArrayElements(env, buffer, buf, JNI_ABORT);
        return 0;                        /* closed, not broken - see the contract above */
    }

    int ret;
    /* Fast path: capture (= PJSIP port) and playback formats match -> write directly. */
    if (io.capture_rate == io.playback_rate &&
        io.capture_channels == io.playback_channels) {
        ret = pcm_write(io.pcm, buf, len);
    } else if (io.capture_channels == 1 && io.playback_channels == 1) {
        /* Upsample mono captureRate -> mono playbackRate via linear interpolation.
         * (MediaTek: PJSIP delivers 8 kHz mono; the modem playback memif needs
         * 48 kHz.)
         * The scratch buffer is preallocated in open() and borrowed through the io_ref -
         * nothing on this path allocates (AUDIT H3). */
        const short *in = (const short *)buf;
        int in_n = len / 2;
        long out_n = (long)in_n * io.playback_rate / io.capture_rate;
        short *out = io.resample_buf;
        if (!out || out_n <= 0 || out_n > (long)io.resample_samples) {
            io_release();
            (*env)->ReleaseByteArrayElements(env, buffer, buf, JNI_ABORT);
            LOGE("writeFrame: %ld out samples exceed the %u-sample scratch (in=%d bytes)",
                 out_n, io.resample_samples, len);
            return -1;
        }
        double step = (double)io.capture_rate / (double)io.playback_rate;
        for (long j = 0; j < out_n; j++) {
            double srcpos = j * step;
            int i0 = (int)srcpos;
            double frac = srcpos - i0;
            int i1 = (i0 + 1 < in_n) ? i0 + 1 : in_n - 1;
            out[j] = (short)(in[i0] * (1.0 - frac) + in[i1] * frac);
        }
        ret = pcm_write(io.pcm, out, (unsigned int)(out_n * 2));
    } else {
        /* Unsupported channel conversion - write as-is (should not happen). */
        ret = pcm_write(io.pcm, buf, len);
    }

    if (ret != 0) {
        /* pcm_get_error() dereferences io.pcm - still alive, the reference is held. */
        LOGE("pcm_write failed: %s", pcm_get_error(io.pcm));
    }

    io_release();

    (*env)->ReleaseByteArrayElements(env, buffer, buf, JNI_ABORT);

    return (ret != 0) ? -1 : (jint)len;
}

/* ---- pjsua2 ByteVector bulk access (AUDIT H2) -----------------------------
 *
 * *** ABI DEPENDENCY - READ THIS BEFORE REBUILDING PJSIP ***
 *
 * pjsua2 hands audio to the app as pj::ByteVector, which types.hpp defines as
 * std::vector<unsigned char>. Moving a 320-byte frame through the SWIG per-element
 * accessors costs ~320 JNI transitions and ~160 java.lang.Short allocations, per frame,
 * per direction, on the pjmedia RT thread. These three functions do the same job as one
 * memcpy each.
 *
 * That requires reaching into the vector, so this code assumes:
 *
 *   struct { unsigned char *__begin_; unsigned char *__end_; unsigned char *__end_cap_; }
 *
 * i.e. libc++'s std::vector layout, data pointer first, element size 1.
 *
 * Not assumed - VERIFIED against the vendored libpjsua2.so:
 *   Java_org_pjsip_pjsua2_pjsua2JNI_ByteVector_1doSize
 *       ldp x8, x9, [x2]        ; load the words at +0 and +8
 *       sub x8, x9, x8          ; size = __end_ - __begin_
 * and the library links libc++_shared.so, the same STL this build uses
 * (ANDROID_STL=c++_shared in app/build.gradle).
 *
 * Constraints that keep a layout mismatch from becoming heap corruption:
 *   - nothing here ever WRITES a vector's control block or resizes it; sizing stays with
 *     pjsua2 (ByteVector(count,value) / MediaFrame::buf assignment), so no assumption is
 *     made about which allocator owns the storage;
 *   - every entry point re-derives the size from the pointers and refuses implausible
 *     values;
 *   - GsmAudioPort's constructor cross-checks BOTH directions against pjsua2's own
 *     generated per-element accessors and falls back to the old loops on any mismatch.
 *
 * If you rebuild PJSIP, run the app once and check logcat for
 * "bulk frame copy unavailable".
 */

/* Largest vector we will believe in. A 20 ms audio frame is 320-1920 bytes; anything in
 * the megabytes means we are not looking at a vector at all. */
#define PJ_VECTOR_SANITY_MAX (1 << 20)

struct pj_byte_vector {
    unsigned char *begin;
    unsigned char *end;
    unsigned char *end_cap;
};

/* Elements currently in the vector, or -1 if it does not look like one. */
static long pj_vector_size(jlong handle) {
    if (handle == 0) {
        return -1;
    }
    const struct pj_byte_vector *v = (const struct pj_byte_vector *)(intptr_t)handle;
    if (v->begin == NULL && v->end == NULL) {
        return 0;                        /* legitimately empty */
    }
    if (v->begin == NULL || v->end == NULL || v->end < v->begin) {
        return -1;
    }
    ptrdiff_t n = v->end - v->begin;
    if (n > PJ_VECTOR_SANITY_MAX) {
        return -1;
    }
    return (long)n;
}

JNIEXPORT jint JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_pjBufSize(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    return (jint)pj_vector_size(handle);
}

/* Vector -> Java array. Reads only; the vector is pjmedia's and must not be disturbed. */
JNIEXPORT jint JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_pjBufRead(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray dst, jint length) {
    (void)clazz;

    if (!dst || length < 0) {
        return -1;
    }
    long have = pj_vector_size(handle);
    if (have < (long)length) {
        return -1;
    }
    if ((*env)->GetArrayLength(env, dst) < length) {
        return -1;
    }
    if (length == 0) {
        return 0;                        /* begin may legitimately be NULL when empty */
    }

    const struct pj_byte_vector *v = (const struct pj_byte_vector *)(intptr_t)handle;
    (*env)->SetByteArrayRegion(env, dst, 0, length, (const jbyte *)v->begin);
    return length;
}

/*
 * Java array -> vector, in place.
 *
 * The vector must ALREADY be exactly `length` elements long: this fills existing storage
 * and never grows it, which is what keeps the control block untouched. The caller owns
 * that vector and pre-sized it once at open time.
 */
JNIEXPORT jint JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_pjBufWrite(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray src, jint length) {
    (void)clazz;

    if (!src || length < 0) {
        return -1;
    }
    if (pj_vector_size(handle) != (long)length) {
        return -1;                       /* wrong size => not ours, or resized behind us */
    }
    if ((*env)->GetArrayLength(env, src) < length) {
        return -1;
    }
    if (length == 0) {
        return 0;                        /* begin may legitimately be NULL when empty */
    }

    struct pj_byte_vector *v = (struct pj_byte_vector *)(intptr_t)handle;
    (*env)->GetByteArrayRegion(env, src, 0, length, (jbyte *)v->begin);
    return length;
}

/*
 * Set mixer control value
 *
 * @param card        Sound card number
 * @param controlName Mixer control name (e.g. "MultiMedia1 Mixer VOC_REC_DL")
 * @param value       Value to set (0 or 1 for switches)
 * @return true on success
 */

/* ---- Cached control-mixer handle -----------------------------------------
 *
 * mixer_open() enumerates every control on the card. Measured on SDM660
 * (Redmi Note 7) that costs a mean of 767 ms per call, max 2.0 s -- and every
 * mixer entry point below used to pay it, once per control.
 *
 * Three threads do that concurrently (GsmAudioOpen running setupMixer,
 * MuteControls running the mute preset, MixerEnforce re-asserting every 2 s),
 * so they also contended: the fast path was 35 ms and the slow path 2 s, purely
 * from overlap. The visible results were a ~4.9 s gap between the GSM call
 * going ACTIVE and audio starting, a 12-control mute taking ~14 s, and mute
 * leases being cancelled mid-write because the call ended before they finished.
 *
 * So the handle is opened once per card and reused. Callers hold
 * g_ctl_mixer_lock for the whole operation, which additionally serialises the
 * three threads and prevents concurrent mixer_open() storms. The individual
 * ioctls are microseconds, so serialising them costs nothing worth measuring.
 *
 * This is deliberately separate from g_ctx->mixer, which belongs to the PCM
 * open/close path and has a different lifetime.
 */
static pthread_mutex_t g_ctl_mixer_lock = PTHREAD_MUTEX_INITIALIZER;
static struct mixer *g_ctl_mixer = NULL;
static int g_ctl_mixer_card = -1;

/*
 * Return the cached mixer for `card`, opening it if needed.
 * Caller must already hold g_ctl_mixer_lock and must unlock on every path.
 * Returns NULL if the card cannot be opened.
 */
static struct mixer *ctl_mixer_get_locked(int card) {
    if (g_ctl_mixer && g_ctl_mixer_card == card) {
        return g_ctl_mixer;
    }
    if (g_ctl_mixer) {
        mixer_close(g_ctl_mixer);
        g_ctl_mixer = NULL;
        g_ctl_mixer_card = -1;
    }
    g_ctl_mixer = mixer_open(card);
    if (g_ctl_mixer) {
        g_ctl_mixer_card = card;
        LOGI("Opened control mixer for card %d (cached for reuse)", card);
    }
    return g_ctl_mixer;
}

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

    pthread_mutex_lock(&g_ctl_mixer_lock);
    struct mixer *mix = ctl_mixer_get_locked(card);
    if (!mix) {
        LOGE("Failed to open mixer for card %d", card);
        pthread_mutex_unlock(&g_ctl_mixer_lock);
        (*env)->ReleaseStringUTFChars(env, controlName, name);
        return JNI_FALSE;
    }

    struct mixer_ctl *ctl = mixer_get_ctl_by_name(mix, name);
    if (!ctl) {
        LOGE("Mixer control '%s' not found", name);
        pthread_mutex_unlock(&g_ctl_mixer_lock);
        (*env)->ReleaseStringUTFChars(env, controlName, name);
        return JNI_FALSE;
    }

    int ret = mixer_ctl_set_value(ctl, 0, value);
    if (ret < 0) {
        LOGE("Failed to set mixer control '%s' to %d: %d", name, value, ret);
        pthread_mutex_unlock(&g_ctl_mixer_lock);
        (*env)->ReleaseStringUTFChars(env, controlName, name);
        return JNI_FALSE;
    }

    LOGI("Set mixer control '%s' = %d", name, value);

    pthread_mutex_unlock(&g_ctl_mixer_lock);
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

    pthread_mutex_lock(&g_ctl_mixer_lock);
    struct mixer *mix = ctl_mixer_get_locked(card);
    if (!mix) {
        LOGE("Failed to open mixer for card %d", card);
        pthread_mutex_unlock(&g_ctl_mixer_lock);
        (*env)->ReleaseStringUTFChars(env, controlName, name);
        return -1;
    }

    struct mixer_ctl *ctl = mixer_get_ctl_by_name(mix, name);
    if (!ctl) {
        LOGE("Mixer control '%s' not found", name);
        pthread_mutex_unlock(&g_ctl_mixer_lock);
        (*env)->ReleaseStringUTFChars(env, controlName, name);
        return -1;
    }

    int value = mixer_ctl_get_value(ctl, 0);

    pthread_mutex_unlock(&g_ctl_mixer_lock);
    (*env)->ReleaseStringUTFChars(env, controlName, name);
    return value;
}

/*
 * Get mixer control ENUM value as its item name.
 *
 * The counterpart of setMixerControlEnum. Exists so DeviceMuteManager can snapshot a
 * control's original value before muting it *without* shelling out to tinymix — see
 * AUDIT B1c: the shell-out used a `tinymix get` subcommand that does not exist on the
 * devices this runs on, so every read failed, no original was ever recorded, and the
 * unmute had nothing to restore. That left the microphone dead after every gateway call.
 *
 * @param card        Sound card number
 * @param controlName Mixer control name (e.g. "DEC1 MUX")
 * @return the current item name, or NULL if the control is missing, is not an ENUM,
 *         or cannot be read. NULL (not "") so the Java side can tell "unreadable" from
 *         a genuinely empty item name.
 */
JNIEXPORT jstring JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_getMixerControlEnum(
        JNIEnv *env, jclass clazz,
        jint card, jstring controlName) {

    const char *name = (*env)->GetStringUTFChars(env, controlName, NULL);
    if (!name) {
        return NULL;
    }

    pthread_mutex_lock(&g_ctl_mixer_lock);
    struct mixer *mix = ctl_mixer_get_locked(card);
    if (!mix) {
        LOGE("Failed to open mixer for card %d", card);
        pthread_mutex_unlock(&g_ctl_mixer_lock);
        (*env)->ReleaseStringUTFChars(env, controlName, name);
        return NULL;
    }

    struct mixer_ctl *ctl = mixer_get_ctl_by_name(mix, name);
    if (!ctl) {
        LOGE("Mixer control '%s' not found", name);
        pthread_mutex_unlock(&g_ctl_mixer_lock);
        (*env)->ReleaseStringUTFChars(env, controlName, name);
        return NULL;
    }

    if (mixer_ctl_get_type(ctl) != MIXER_CTL_TYPE_ENUM) {
        LOGE("Mixer control '%s' is not an ENUM", name);
        pthread_mutex_unlock(&g_ctl_mixer_lock);
        (*env)->ReleaseStringUTFChars(env, controlName, name);
        return NULL;
    }

    /* For an ENUM, value 0 is the index of the currently selected item. */
    int idx = mixer_ctl_get_value(ctl, 0);
    if (idx < 0 || (unsigned int) idx >= mixer_ctl_get_num_enums(ctl)) {
        LOGE("Mixer control '%s' has out-of-range enum index %d", name, idx);
        pthread_mutex_unlock(&g_ctl_mixer_lock);
        (*env)->ReleaseStringUTFChars(env, controlName, name);
        return NULL;
    }

    const char *item = mixer_ctl_get_enum_string(ctl, idx);
    /* Points into the mixer's own metadata storage - copy it out while we still hold
     * g_ctl_mixer_lock, since ctl_mixer_get_locked() may close and re-open the handle
     * for a different card. */
    jstring result = item ? (*env)->NewStringUTF(env, item) : NULL;
    if (!item) {
        LOGE("Mixer control '%s' enum index %d has no name", name, idx);
    }

    pthread_mutex_unlock(&g_ctl_mixer_lock);
    (*env)->ReleaseStringUTFChars(env, controlName, name);
    return result;
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

    pthread_mutex_lock(&g_ctl_mixer_lock);
    struct mixer *mix = ctl_mixer_get_locked(card);
    if (!mix) {
        LOGE("Failed to open mixer for card %d", card);
        pthread_mutex_unlock(&g_ctl_mixer_lock);
        (*env)->ReleaseStringUTFChars(env, controlName, name);
        (*env)->ReleaseStringUTFChars(env, value, val);
        return JNI_FALSE;
    }

    struct mixer_ctl *ctl = mixer_get_ctl_by_name(mix, name);
    if (!ctl) {
        LOGE("Mixer control '%s' not found", name);
        pthread_mutex_unlock(&g_ctl_mixer_lock);
        (*env)->ReleaseStringUTFChars(env, controlName, name);
        (*env)->ReleaseStringUTFChars(env, value, val);
        return JNI_FALSE;
    }

    int ret = mixer_ctl_set_enum_by_string(ctl, val);
    if (ret < 0) {
        LOGE("Failed to set mixer control '%s' to '%s': %d", name, val, ret);
        pthread_mutex_unlock(&g_ctl_mixer_lock);
        (*env)->ReleaseStringUTFChars(env, controlName, name);
        (*env)->ReleaseStringUTFChars(env, value, val);
        return JNI_FALSE;
    }

    LOGI("Set mixer control '%s' = '%s'", name, val);

    pthread_mutex_unlock(&g_ctl_mixer_lock);
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

    pthread_mutex_lock(&g_ctl_mixer_lock);
    struct mixer *mix = ctl_mixer_get_locked(card);
    if (!mix) {
        LOGE("Failed to open mixer for card %d", card);
        pthread_mutex_unlock(&g_ctl_mixer_lock);
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

    pthread_mutex_unlock(&g_ctl_mixer_lock);
    return result;
}

/*
 * Check if audio is open.
 *
 * Advisory only: the device can be closed the instant after this returns. It exists to
 * skip work, never to make it safe to touch a PCM - readFrame()/writeFrame() re-check
 * under the lock via io_acquire().
 */
JNIEXPORT jboolean JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_isOpen(JNIEnv *env, jclass clazz) {
    pthread_mutex_lock(&g_ctx->lock);
    int is_open = g_ctx->is_open;
    pthread_mutex_unlock(&g_ctx->lock);
    return is_open ? JNI_TRUE : JNI_FALSE;
}

/*
 * Get frame size in bytes
 */
JNIEXPORT jint JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_getFrameSize(JNIEnv *env, jclass clazz) {
    jint size = 0;

    pthread_mutex_lock(&g_ctx->lock);
    if (g_ctx->is_open) {
        /* 20ms capture frame: rate/50 samples * channels * bytes_per_sample */
        size = (jint)((g_ctx->capture_rate / 50) * g_ctx->capture_channels *
                      (g_ctx->bits / 8));
    }
    pthread_mutex_unlock(&g_ctx->lock);

    return size;
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
