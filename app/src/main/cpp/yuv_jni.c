#include <jni.h>
#include "yuv_packing.h"

JNIEXPORT jboolean JNICALL
Java_compress_joshattic_us_quality_I420Packing_packNative(
        JNIEnv *env, jobject self, jobjectArray buffers, jintArray layout, jbyteArray dest,
        jint width, jint height, jint rotation) {
    (void)self;
    if (!buffers || !layout || !dest || (*env)->GetArrayLength(env, buffers) != 3 ||
        (*env)->GetArrayLength(env, layout) != 12) return JNI_FALSE;
    jint values[12];
    (*env)->GetIntArrayRegion(env, layout, 0, 12, values);
    if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
    CompressorPlane planes[3];
    for (int i = 0; i < 3; i++) {
        jobject buffer = (*env)->GetObjectArrayElement(env, buffers, i);
        if (!buffer) return JNI_FALSE;
        const uint8_t *address = (*env)->GetDirectBufferAddress(env, buffer);
        const jlong capacity = (*env)->GetDirectBufferCapacity(env, buffer);
        (*env)->DeleteLocalRef(env, buffer);
        const int offset = values[i * 4], limit = values[i * 4 + 3];
        if (!address || offset < 0 || limit < offset || capacity < limit) return JNI_FALSE;
        planes[i] = (CompressorPlane){address + offset, (size_t)(limit - offset), values[i * 4 + 1], values[i * 4 + 2]};
    }
    const size_t dest_length = (size_t)(*env)->GetArrayLength(env, dest);
    /* Do not pin the Java heap through a 4K transpose; let ART keep scheduling GC. */
    jbyte *output = (*env)->GetByteArrayElements(env, dest, NULL);
    if (!output) return JNI_FALSE;
    const int ok = compressor_pack_i420(planes, width, height, rotation, (uint8_t *)output,
                                         dest_length);
    (*env)->ReleaseByteArrayElements(env, dest, output, ok ? 0 : JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
