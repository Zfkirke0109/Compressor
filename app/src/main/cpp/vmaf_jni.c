/*
 * JNI bridge for on-device VMAF (libvmaf v3.0.0, arm64 NEON, built-in models).
 *
 * Usage contract (single session at a time, guarded on the Kotlin side):
 *   long ctx = nativeOpen(width, height, useNeg ? 1 : 0, nThreads)
 *   nativeReadFrames(ctx, refI420, distI420, width, height)   // repeated, in display order
 *   double[] scores = nativeFlush(ctx)                        // per-frame VMAF scores
 *   nativeClose(ctx)
 *
 * Frames are planar I420 (Y then U then V, no padding, even dimensions), 8-bit.
 * The phone-calibrated model ("vmaf_v0.6.1" + phone flag) is loaded from the models
 * built into libvmaf, so no model files ship in the APK.
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "libvmaf/libvmaf.h"

#define TAG "VmafNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

typedef struct {
    VmafContext *vmaf;
    VmafModel *model;
    unsigned frame_index;
    int width;
    int height;
} VmafSession;

static int fill_picture_i420(VmafPicture *pic, const uint8_t *data, int w, int h) {
    int err = vmaf_picture_alloc(pic, VMAF_PIX_FMT_YUV420P, 8, w, h);
    if (err) return err;
    const uint8_t *src = data;
    // Y plane
    for (int i = 0; i < h; i++) {
        memcpy((uint8_t *)pic->data[0] + (size_t)i * pic->stride[0], src, (size_t)w);
        src += w;
    }
    // U, V planes
    int cw = w / 2, ch = h / 2;
    for (int p = 1; p <= 2; p++) {
        for (int i = 0; i < ch; i++) {
            memcpy((uint8_t *)pic->data[p] + (size_t)i * pic->stride[p], src, (size_t)cw);
            src += cw;
        }
    }
    return 0;
}

JNIEXPORT jlong JNICALL
Java_compress_joshattic_us_quality_VmafNative_nativeOpen(
        JNIEnv *env, jobject thiz, jint width, jint height, jint phoneModel, jint nThreads) {
    (void)env; (void)thiz;
    if (width <= 0 || height <= 0 || (width & 1) || (height & 1)) {
        LOGE("invalid dimensions %dx%d", width, height);
        return 0;
    }
    VmafSession *s = calloc(1, sizeof(VmafSession));
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

    VmafModelConfig model_cfg = {
        .name = "vmaf",
        .flags = phoneModel ? VMAF_MODEL_FLAG_ENABLE_TRANSFORM : VMAF_MODEL_FLAGS_DEFAULT,
    };
    if (vmaf_model_load(&s->model, &model_cfg, "vmaf_v0.6.1")) {
        LOGE("vmaf_model_load(vmaf_v0.6.1) failed");
        vmaf_close(s->vmaf);
        free(s);
        return 0;
    }
    if (vmaf_use_features_from_model(s->vmaf, s->model)) {
        LOGE("vmaf_use_features_from_model failed");
        vmaf_model_destroy(s->model);
        vmaf_close(s->vmaf);
        free(s);
        return 0;
    }
    LOGI("vmaf session open %dx%d phone=%d threads=%u", width, height, phoneModel, cfg.n_threads);
    return (jlong)(intptr_t)s;
}

JNIEXPORT jint JNICALL
Java_compress_joshattic_us_quality_VmafNative_nativeReadFrames(
        JNIEnv *env, jobject thiz, jlong handle,
        jbyteArray refI420, jbyteArray distI420, jint width, jint height) {
    (void)thiz;
    VmafSession *s = (VmafSession *)(intptr_t)handle;
    if (!s || width != s->width || height != s->height) return -1;

    const size_t need = (size_t)width * height * 3 / 2;
    if ((size_t)(*env)->GetArrayLength(env, refI420) < need ||
        (size_t)(*env)->GetArrayLength(env, distI420) < need) {
        LOGE("frame buffer too small");
        return -2;
    }

    jbyte *ref = (*env)->GetByteArrayElements(env, refI420, NULL);
    jbyte *dist = (*env)->GetByteArrayElements(env, distI420, NULL);
    if (!ref || !dist) {
        if (ref) (*env)->ReleaseByteArrayElements(env, refI420, ref, JNI_ABORT);
        return -3;
    }

    VmafPicture ref_pic, dist_pic;
    int err = fill_picture_i420(&ref_pic, (const uint8_t *)ref, width, height);
    if (!err) {
        err = fill_picture_i420(&dist_pic, (const uint8_t *)dist, width, height);
        if (err) vmaf_picture_unref(&ref_pic);
    }
    (*env)->ReleaseByteArrayElements(env, refI420, ref, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, distI420, dist, JNI_ABORT);
    if (err) {
        LOGE("picture alloc failed: %d", err);
        return -4;
    }

    /* vmaf_read_pictures takes ownership of the pictures on success. */
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
Java_compress_joshattic_us_quality_VmafNative_nativeFlush(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    VmafSession *s = (VmafSession *)(intptr_t)handle;
    if (!s) return NULL;

    /* Signal end of stream. */
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
        if (vmaf_score_at_index(s->vmaf, s->model, &score, i)) {
            LOGE("vmaf_score_at_index(%u) failed", i);
            score = -1.0;
        }
        tmp[i] = score;
    }
    (*env)->SetDoubleArrayRegion(env, out, 0, (jsize)n, tmp);
    free(tmp);
    return out;
}

JNIEXPORT void JNICALL
Java_compress_joshattic_us_quality_VmafNative_nativeClose(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    VmafSession *s = (VmafSession *)(intptr_t)handle;
    if (!s) return;
    if (s->model) vmaf_model_destroy(s->model);
    if (s->vmaf) vmaf_close(s->vmaf);
    free(s);
}

JNIEXPORT jstring JNICALL
Java_compress_joshattic_us_quality_VmafNative_nativeVersion(
        JNIEnv *env, jobject thiz) {
    (void)thiz;
    return (*env)->NewStringUTF(env, vmaf_version());
}
