package compress.joshattic.us.quality

import android.util.Log

/**
 * SHADOW scorer: VMAF v1 (`vmaf_v1.0.16_5d0h`, the phone model) via libvmaf 3.2.0.
 *
 * Netflix released the v1 model family in June 2026: it drops VIF, adds banding (CAMBI) and
 * chroma awareness, and ships a phone variant calibrated for a 5H viewing distance. Those are
 * exactly the blind spots of the vmaf_v0.6.1 verdict this app uses today — which is why it is
 * worth measuring, and also why it cannot simply replace the verdict: the 95.5/91/84 window bars
 * were calibrated against v0.6.1, and nothing establishes that v1 scores sit on the same scale.
 *
 * So every scored window records a v1 score ALONGSIDE the verdict score, and nothing reads it.
 * A later calibration round can compare the two on this user's real corpus and decide whether
 * and how to switch. Until then, a v1 score is evidence, never a gate.
 *
 * Best-effort in every way: if the library fails to load, or a session fails to open, or a frame
 * fails to feed, the window keeps its v0.6.1 verdict and simply has no v1 diagnostic.
 */
object VmafNativeV1 {
    private const val TAG = "VmafNativeV1"

    val isAvailable: Boolean by lazy {
        try {
            System.loadLibrary("compressorvmafv1")
            Log.i(TAG, "libvmaf v1 shadow scorer loaded: ${nativeModelName()}")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "VMAF v1 shadow scorer unavailable: ${t.message}")
            false
        }
    }

    val modelName: String by lazy { if (isAvailable) runCatching { nativeModelName() }.getOrDefault("?") else "unavailable" }

    fun open(width: Int, height: Int, threads: Int = 1): Long =
        if (!isAvailable) 0L else runCatching { nativeOpen(width, height, threads) }.getOrDefault(0L)

    fun readFrames(handle: Long, refI420: ByteArray, distI420: ByteArray, width: Int, height: Int): Int =
        nativeReadFrames(handle, refI420, distI420, width, height)

    fun flush(handle: Long): DoubleArray? = nativeFlush(handle)

    fun close(handle: Long) {
        if (handle != 0L) nativeClose(handle)
    }

    private external fun nativeModelName(): String
    private external fun nativeOpen(width: Int, height: Int, threads: Int): Long
    private external fun nativeReadFrames(handle: Long, refI420: ByteArray, distI420: ByteArray, width: Int, height: Int): Int
    private external fun nativeFlush(handle: Long): DoubleArray?
    private external fun nativeClose(handle: Long)
}
