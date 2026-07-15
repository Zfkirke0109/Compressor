package compress.joshattic.us.quality

import android.util.Log

/**
 * Thin JNI wrapper around libvmaf v3.0.0 (arm64 NEON, built-in models, phone-calibrated
 * transform enabled). All heavy work is native; this object only guards library availability.
 *
 * The native session is NOT thread-safe; callers must confine one session to one thread
 * (see [VmafPairScorer]). If the native library is unavailable (non-arm64 device, load
 * failure), [isAvailable] is false and every caller must fall back to the structural-only
 * pipeline — pixel scoring is an upgrade, never a requirement.
 */
object VmafNative {
    private const val TAG = "VmafNative"

    val isAvailable: Boolean by lazy {
        try {
            System.loadLibrary("compressorvmaf")
            Log.i(TAG, "libvmaf loaded, version=${nativeVersion()}")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "libvmaf unavailable: ${t.message}")
            false
        }
    }

    fun open(width: Int, height: Int, phoneModel: Boolean = true, threads: Int = 2): Long {
        if (!isAvailable) return 0L
        return nativeOpen(width, height, if (phoneModel) 1 else 0, threads)
    }

    fun readFrames(handle: Long, refI420: ByteArray, distI420: ByteArray, width: Int, height: Int): Int =
        nativeReadFrames(handle, refI420, distI420, width, height)

    fun flush(handle: Long): DoubleArray? = nativeFlush(handle)

    fun close(handle: Long) = nativeClose(handle)

    private external fun nativeOpen(width: Int, height: Int, phoneModel: Int, threads: Int): Long
    private external fun nativeReadFrames(
        handle: Long,
        refI420: ByteArray,
        distI420: ByteArray,
        width: Int,
        height: Int
    ): Int

    private external fun nativeFlush(handle: Long): DoubleArray?
    private external fun nativeClose(handle: Long)
    private external fun nativeVersion(): String
}
