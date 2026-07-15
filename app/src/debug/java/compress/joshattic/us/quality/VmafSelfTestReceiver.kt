package compress.joshattic.us.quality

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Debug-only smoke test for the native VMAF pipeline, triggered over adb:
 *
 *   adb shell am broadcast -a io.github.zfkirke0109.galaxycompressor.VMAF_SELFTEST \
 *       -n io.github.zfkirke0109.galaxycompressor/compress.joshattic.us.quality.VmafSelfTestReceiver
 *
 * Feeds synthetic frames straight through JNI (no video decode): identical ref/dist frames
 * must score high; heavily noise-degraded dist frames must score clearly lower. Results are
 * logged under the VMAF_SELFTEST tag for capture with logcat.
 */
class VmafSelfTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread(name = "vmaf-selftest") {
            try {
                run()
            } catch (t: Throwable) {
                Log.e("VMAF_SELFTEST", "selftest crashed: ${t.message}", t)
            } finally {
                pending.finish()
            }
        }
    }

    private fun run() {
        if (!VmafNative.isAvailable) {
            Log.e("VMAF_SELFTEST", "FAIL: native library unavailable")
            return
        }
        val w = 320
        val h = 240
        val frames = 24
        val rng = Random(42)
        // Structured content (gradients + moving box) so VMAF has real detail to judge.
        val ref = Array(frames) { f ->
            ByteArray(w * h * 3 / 2).also { buf ->
                for (y in 0 until h) for (x in 0 until w) {
                    buf[y * w + x] = (((x + f * 4) % 256) xor ((y * 3) % 256)).toByte()
                }
                java.util.Arrays.fill(buf, w * h, buf.size, 128.toByte())
            }
        }

        val identical = scoreAgainst(ref) { frame, _ -> frame.copyOf() }
        val degraded = scoreAgainst(ref) { frame, _ ->
            frame.copyOf().also { copy ->
                for (i in 0 until w * h) {
                    val noise = rng.nextInt(-48, 49)
                    copy[i] = (copy[i].toInt() and 0xFF).plus(noise).coerceIn(0, 255).toByte()
                }
            }
        }
        if (identical == null || degraded == null) {
            Log.e("VMAF_SELFTEST", "FAIL: scoring returned null (identical=$identical degraded=$degraded)")
            return
        }
        val pass = identical > 90.0 && degraded < identical - 15.0
        Log.i(
            "VMAF_SELFTEST",
            "${if (pass) "PASS" else "FAIL"}: mean_identical=%.2f mean_degraded=%.2f frames=$frames ${w}x$h".format(identical, degraded)
        )
    }

    private fun scoreAgainst(ref: Array<ByteArray>, distort: (ByteArray, Int) -> ByteArray): Double? {
        val w = 320
        val h = 240
        // Default model to match the calibration of QualityProbePolicy thresholds.
        val handle = VmafNative.open(w, h, phoneModel = false, threads = 2)
        if (handle == 0L) return null
        try {
            ref.forEachIndexed { i, frame ->
                val rc = VmafNative.readFrames(handle, frame, distort(frame, i), w, h)
                if (rc < 0) return null
            }
            val scores = VmafNative.flush(handle) ?: return null
            if (scores.isEmpty() || scores.any { it < 0 }) return null
            return scores.average()
        } finally {
            VmafNative.close(handle)
        }
    }
}
