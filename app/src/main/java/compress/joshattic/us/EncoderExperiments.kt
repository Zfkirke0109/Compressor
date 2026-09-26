package compress.joshattic.us

import android.content.Context

/**
 * Opt-in encoder experiments, persisted per device, off by default.
 *
 * B-frames. HEVC gains a further 10-20% of bits at equal quality from bidirectional prediction,
 * on top of what a source-matched keyframe interval recovers (KeyframeIntervalPolicy). Whether
 * `c2.qti.hevc.encoder` on this device honours MediaFormat's `max-bframes` request, and what it
 * does to quality per bit, is not known from any capture yet, so it is an experiment: when armed,
 * both the probe clips and the full encode request it, the same gates judge the result, and the
 * request is written into the `encodeResult` config so the capture says which encodes carried it.
 * Nothing about acceptance changes.
 */
object EncoderExperiments {

    private const val PREFS = "encoder_experiments"
    private const val KEY_MAX_B_FRAMES = "max_b_frames"

    /** What "B-frames on" asks the encoder for. */
    const val B_FRAMES_WHEN_ENABLED = 2

    /** 0 when the experiment is off, which leaves Media3's request untouched. */
    fun maxBFrames(context: Context): Int =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MAX_B_FRAMES, 0)

    fun isBFramesEnabled(context: Context): Boolean = maxBFrames(context) > 0

    fun setBFramesEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MAX_B_FRAMES, if (enabled) B_FRAMES_WHEN_ENABLED else 0)
            .apply()
    }

    private const val KEY_SAFER_RUNG_RETRY = "safer_rung_retry"

    /**
     * Opt-in: after a measured certification failure, one full-encode retry at a HIGHER ratio
     * the probes already measured as passing (SaferRungRetry). Off by default until a device run
     * shows what it recovers and costs.
     */
    fun isSaferRungRetryEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SAFER_RUNG_RETRY, false)

    fun setSaferRungRetryEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SAFER_RUNG_RETRY, enabled)
            .apply()
    }

    /** The part of an encoder config line that names the experiment, or "" when off. */
    fun describe(maxBFrames: Int): String = if (maxBFrames > 0) ";bframes=$maxBFrames" else ""
}
