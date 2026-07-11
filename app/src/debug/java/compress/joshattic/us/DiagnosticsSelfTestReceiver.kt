package compress.joshattic.us

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug-only trigger for the privacy-safe diagnostics self-test. It exists ONLY in debug builds
 * (this file lives in src/debug), so release builds never ship it. It emits a synthetic
 * session_start + one synthetic job + session_summary through [DiagnosticsRecorder]; it touches no
 * media, replaces no files, and exposes no personal data, so exporting it in a debug build is
 * harmless — the worst a rogue caller can do is add a clearly-marked synthetic session to logcat.
 *
 * Trigger from a PC (normal profile):
 *   adb -s <serial> shell am broadcast \
 *     -a io.github.zfkirke0109.galaxycompressor.DIAG_SELFTEST \
 *     -n io.github.zfkirke0109.galaxycompressor/compress.joshattic.us.DiagnosticsSelfTestReceiver \
 *     --es token t1
 */
class DiagnosticsSelfTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        if (intent.action != ACTION) return
        val token = intent.getStringExtra("token")?.takeIf { it.isNotBlank() }
            ?: System.currentTimeMillis().toString()
        val batchId = DiagnosticsRecorder.runSelfTest(context.applicationContext, token)
        Log.i("CompressorBatch", "diagnostics self-test emitted for $batchId")
    }

    companion object {
        const val ACTION = "io.github.zfkirke0109.galaxycompressor.DIAG_SELFTEST"
    }
}
