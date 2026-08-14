package compress.joshattic.us

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug-only learned-state control for A/B experiments, triggered over adb.
 *
 * Learned profile state persists across runs and silently changes what later runs do. The two
 * 219-job captures differed in probe count and runtime partly because the second inherited
 * measured-rejection latches from the first, which is why no cause can be attributed to the code
 * change between them. An experiment arm has to be able to record the state it started from, and
 * to start from a known-empty one.
 *
 * Inspect (non-destructive):
 *   adb shell am broadcast -a io.github.zfkirke0109.galaxycompressor.LEARNING_STATE \
 *     -n io.github.zfkirke0109.galaxycompressor/compress.joshattic.us.LearningStateReceiver \
 *     --es op snapshot
 *
 * Reset (destructive — snapshot first if the prior state matters):
 *   ... --es op reset
 *
 * Results are logged under the CompressorLearning tag. Only technical bucket keys are emitted:
 * manufacturer/model/SDK/codec/resolution/fps/HDR/bitrate class. No file paths, no per-file
 * identifiers, nothing private — the same guarantee the stored keys themselves carry.
 */
class LearningStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        if (intent.action != ACTION) return

        val engine = SmartPerceptualProfileEngine(
            SmartPerceptualProfileEngine.SharedPreferencesProfileStore(context.applicationContext)
        )
        when (intent.getStringExtra("op")?.lowercase()) {
            "snapshot" -> {
                val state = engine.snapshotLearnedState()
                Log.i(TAG, "learning-state snapshot; identity=${engine.learnedStateIdentity()}; entries=${state.size}")
                state.toSortedMap().forEach { (k, v) -> Log.i(TAG, "learning-state entry; $k -> $v") }
            }
            "reset" -> {
                // Log the identity being discarded so a capture can prove what was thrown away.
                val before = engine.learnedStateIdentity()
                engine.resetLearnedState()
                Log.i(TAG, "learning-state reset; before=$before; after=${engine.learnedStateIdentity()}")
            }
            else -> Log.w(TAG, "learning-state: unknown op; expected --es op snapshot|reset")
        }
    }

    companion object {
        private const val TAG = "CompressorLearning"
        const val ACTION = "io.github.zfkirke0109.galaxycompressor.LEARNING_STATE"
    }
}
