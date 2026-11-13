/*
 * JNI bridge for SHADOW scoring with VMAF v1 (libvmaf 3.2.0, vmaf_v1.0.16_5d0h phone model).
 *
 * Shadow only. No acceptance decision reads these scores: the Perceptually Lossless verdict is
 * still computed by libcompressorvmaf.so (libvmaf 3.0.0, vmaf_v0.6.1), whose 95.5/91/84
 * thresholds were calibrated against that model. VMAF v1 (Netflix, June 2026) drops VIF and
 * adds banding (CAMBI) and chroma (speed_chroma) awareness; nothing establishes that its scores
 * sit on the same scale, so adopting it as the verdict needs its own calibration round — which
 * is what these shadow scores are collected for.
 *
 * Isolation: this library statically links its OWN libvmaf 3.2.0 with every libvmaf symbol
 * hidden (-fvisibility=hidden at build time, --exclude-libs,ALL and -Bsymbolic at link time), so
 * its calls can never bind to the 3.0.0 copy inside libcompressorvmaf.so, nor the reverse.
 *
 * Precision: Netflix documents v1 as "ideally applied at 10-bit precision for SDR", so 8-bit
 * frames are expanded to 10-bit with bit replication ((v << 2) | (v >> 6)), which maps 0->0 and
 * 255->1023 exactly.
 *
 * Contract matches VmafNative: open -> readFrames* -> flush -> close, one session at a time.
 */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "libvmaf/libvmaf.h"

#define TAG "VmafNativeV1"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

#define V1_MODEL "vmaf_v1.0.16_5d0h"

/*
 * Smallest frame (both display dimensions) the v1 model can score WITHOUT CRASHING THE PROCESS.
 *
 * v1's speed_chroma feature works on the chroma planes (half the luma size), rescales them by
 * the model's speed_prescale 0.6, then needs >= one 5-pixel block after 4 dyadic downscales:
 * (int)(c * 0.6 + 0.5) >> 4 >= 5  ->  c >= 133  ->  luma >= 266. Below that libvmaf 3.2.0 logs
 * "SpEED: image too small" and then SEGFAULTS rather than failing the call. Measured on a host
 * build of the same source: 266x266, 266x1920 and 1920x266 score; 264x400 and 400x264 crash.
 * A shadow scorer must never be able to kill the app, so smaller frames get no v1 session.
 */
#define V1_MIN_DIMENSION 266

typedef struct {
    VmafContext *vmaf;
    VmafModel *model;
    unsigned frame_index;
    int width;
    int height;
} V1Session;

static inline uint16_t to10(uint8_t v) { return (uint16_t)((v << 2) | (v >> 6)); }

static int fill_picture_i420_10bit(VmafPicture *pic, const uint8_t *data, int w, int h) {
    int err = vmaf_picture_alloc(pic, VMAF_PIX_FMT_YUV420P, 10, w, h);
    if (err) return err;
    const uint8_t *src = data;
    for (int i = 0; i < h; i++) {
        uint16_t *row = (uint16_t *)((uint8_t *)pic->data[0] + (size_t)i * pic->stride[0]);
        for (int x = 0; x < w; x++) row[x] = to10(src[x]);
        src += w;
    }
    int cw = w / 2, ch = h / 2;
    for (int p = 1; p <= 2; p++) {
        for (int i = 0; i < ch; i++) {
            uint16_t *row = (uint16_t *)((uint8_t *)pic->data[p] + (size_t)i * pic->stride[p]);
            for (int x = 0; x < cw; x++) row[x] = to10(src[x]);
            src += cw;
        }
    }
    return 0;
}

JNIEXPORT jstring JNICALL
Java_compress_joshattic_us_quality_VmafNativeV1_nativeModelName(JNIEnv *env, jobject thiz) {
    (void)thiz;
    return (*env)->NewStringUTF(env, V1_MODEL "@10bit/libvmaf" );
}

JNIEXPORT jlong JNICALL
Java_compress_joshattic_us_quality_VmafNativeV1_nativeOpen(
        JNIEnv *env, jobject thiz, jint width, jint height, jint nThreads) {
    (void)env; (void)thiz;
    if (width <= 0 || height <= 0 || (width & 1) || (height & 1)) {
        LOGE("invalid dimensions %dx%d", width, height);
        return 0;
    }
    if (width < V1_MIN_DIMENSION || height < V1_MIN_DIMENSION) {
        LOGI("v1 shadow skipped: %dx%d below the %d px SpEED minimum", width, height, V1_MIN_DIMENSION);
        return 0;
    }
    V1Session *s = calloc(1, sizeof(V1Session));
    if (!s) return 0;
    s->width = width;
    s->height = height;

    VmafConfiguration cfg = {
        .log_level = VMAF_LOG_LEVEL_WARNING,
        .n_threads = (unsigned)(nThreads > 0 ? nThreads : 2),
        .n_subsample = 1,
        .cpumask = 0,
    };
    if (vmaf_init(&s->vmaf, cfg)) {
        LOGE("vmaf_init failed");
        free(s);
        return 0;
    }
    VmafModelConfig model_cfg = { .name = "vmaf_v1", .flags = VMAF_MODEL_FLAGS_DEFAULT };
    if (vmaf_model_load(&s->model, &model_cfg, V1_MODEL)) {
        LOGE("vmaf_model_load(%s) failed", V1_MODEL);
        vmaf_close(s->vmaf);
        free(s);
        return 0;
    }
    if (vmaf_use_features_from_model(s->vmaf, s->model)) {
        LOGE("vmaf_use_features_from_model(%s) failed", V1_MODEL);
        vmaf_model_destroy(s->model);
        vmaf_close(s->vmaf);
        free(s);
        return 0;
    }
    LOGI("v1 session open %dx%d threads=%u model=%s", width, height, cfg.n_threads, V1_MODEL);
    return (jlong)(intptr_t)s;
}

JNIEXPORT jint JNICALL
Java_compress_joshattic_us_quality_VmafNativeV1_nativeReadFrames(
        JNIEnv *env, jobject thiz, jlong handle,
        jbyteArray refI420, jbyteArray distI420, jint width, jint height) {
    (void)thiz;
    V1Session *s = (V1Session *)(intptr_t)handle;
    if (!s || width != s->width || height != s->height) return -1;
    const size_t need = (size_t)width * height * 3 / 2;
    if ((size_t)(*env)->GetArrayLength(env, refI420) < need ||
        (size_t)(*env)->GetArrayLength(env, distI420) < need) {
        return -2;
    }
    jbyte *ref = (*env)->GetByteArrayElements(env, refI420, NULL);
    jbyte *dist = (*env)->GetByteArrayElements(env, distI420, NULL);
    if (!ref || !dist) {
        if (ref) (*env)->ReleaseByteArrayElements(env, refI420, ref, JNI_ABORT);
        if (dist) (*env)->ReleaseByteArrayElements(env, distI420, dist, JNI_ABORT);
        return -3;
    }
    VmafPicture ref_pic, dist_pic;
    int err = fill_picture_i420_10bit(&ref_pic, (const uint8_t *)ref, width, height);
    if (!err) {
        err = fill_picture_i420_10bit(&dist_pic, (const uint8_t *)dist, width, height);
        if (err) vmaf_picture_unref(&ref_pic);
    }
    (*env)->ReleaseByteArrayElements(env, refI420, ref, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, distI420, dist, JNI_ABORT);
    if (err) return -4;
    err = vmaf_read_pictures(s->vmaf, &ref_pic, &dist_pic, s->frame_index);
    if (err) {
        vmaf_picture_unref(&ref_pic);
        vmaf_picture_unref(&dist_pic);
        LOGE("vmaf_read_pictures failed: %d", err);
        return -5;
    }
    s->frame_index++;
    return (jint)s->frame_index;
}

JNIEXPORT jdoubleArray JNICALL
Java_compress_joshattic_us_quality_VmafNativeV1_nativeFlush(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    V1Session *s = (V1Session *)(intptr_t)handle;
    if (!s) return NULL;
    if (vmaf_read_pictures(s->vmaf, NULL, NULL, 0)) {
        LOGE("vmaf flush failed");
        return NULL;
    }
    unsigned n = s->frame_index;
    jdoubleArray out = (*env)->NewDoubleArray(env, (jsize)n);
    if (!out) return NULL;
    jdouble *tmp = malloc(sizeof(jdouble) * (n ? n : 1));
    if (!tmp) return NULL;
    for (unsigned i = 0; i < n; i++) {
        double score = -1.0;
        if (vmaf_score_at_index(s->vmaf, s->model, &score, i)) score = -1.0;
        tmp[i] = score;
    }
    (*env)->SetDoubleArrayRegion(env, out, 0, (jsize)n, tmp);
    free(tmp);
    return out;
}

JNIEXPORT void JNICALL
Java_compress_joshattic_us_quality_VmafNativeV1_nativeClose(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    V1Session *s = (V1Session *)(intptr_t)handle;
    if (!s) return;
    if (s->model) vmaf_model_destroy(s->model);
    if (s->vmaf) vmaf_close(s->vmaf);
    free(s);
}
