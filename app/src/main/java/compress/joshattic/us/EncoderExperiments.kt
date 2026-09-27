package compress.joshattic.us

import android.content.Context
import android.content.SharedPreferences

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
        setBFramesEnabled(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE), enabled)
    }

    internal fun setBFramesEnabled(prefs: SharedPreferences, enabled: Boolean) {
        // Existing unsuffixed profiles belong to the setting before this build's first change.
        // Pin it in the same preference update, even if no batch has run since the upgrade.
        val editor = prefs.edit()
        if (!prefs.contains(KEY_BASELINE_B_FRAMES)) {
            editor.putInt(KEY_BASELINE_B_FRAMES, prefs.getInt(KEY_MAX_B_FRAMES, 0))
        }
        editor
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

    private const val KEY_SHADOW_CALIBRATION = "shadow_v1_calibration"

    /**
     * Opt-in: score certification windows with the VMAF v1 shadow model as well, within the
     * budget of quality.ShadowCalibration. Telemetry for model calibration; the verdict never
     * reads it. Off by default since b177, where the unconditional shadow added 748 s to a batch.
     */
    fun isShadowCalibrationEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHADOW_CALIBRATION, false)

    fun setShadowCalibrationEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SHADOW_CALIBRATION, enabled)
            .apply()
    }

    private const val KEY_LONG_GOP = "long_gop_x2"

    /** The longest keyframe interval the long-GOP experiment may request. */
    const val LONG_GOP_MAX_SECONDS = 10f

    /**
     * Opt-in (b177 WP3, one-factor pilot): request twice the source-matched keyframe interval
     * (KeyframeIntervalPolicy), capped at [LONG_GOP_MAX_SECONDS], for the probes AND the full
     * encode alike, so a probe still vouches for the encode it predicts. Every gate is unchanged.
     * Its learned state is kept apart from the baseline's ([learningKeySuffix]): a ratio learned at
     * one GOP is not evidence about another.
     */
    fun isLongGopEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LONG_GOP, false)

    fun setLongGopEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LONG_GOP, enabled)
            .apply()
    }

    /** The keyframe interval to request for a source whose own interval policy gave [baseSeconds]. */
    fun keyframeIntervalSeconds(baseSeconds: Float, longGop: Boolean): Float =
        if (longGop) (baseSeconds * 2f).coerceAtMost(LONG_GOP_MAX_SECONDS) else baseSeconds

    private const val KEY_BASELINE_B_FRAMES = "learning_baseline_b_frames"

    /**
     * Appended to the learned-profile key's encoder field for a configuration the baseline state
     * was not learned with, so an experiment never reads or writes the baseline's ratios (b177 WP3).
     * Empty for the baseline, so existing learned profiles keep their keys.
     *
     * B-frames were never part of the key, so the existing state was learned under whatever the
     * B-frame setting was. The first plan, or the first setting change before any plan, pins the
     * pre-change setting as the baseline ([KEY_BASELINE_B_FRAMES]); other settings learn under ";bf<N>".
     */
    fun learningKeySuffix(longGop: Boolean, bFrames: Int = 0, baselineBFrames: Int = bFrames): String =
        (if (bFrames != baselineBFrames) ";bf$bFrames" else "") + (if (longGop) ";gopx2" else "")

    fun learningKeySuffix(context: Context): String =
        learningKeySuffixForPreferences(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    internal fun learningKeySuffixForPreferences(prefs: SharedPreferences): String {
        val bFrames = prefs.getInt(KEY_MAX_B_FRAMES, 0)
        val baseline = if (prefs.contains(KEY_BASELINE_B_FRAMES)) {
            prefs.getInt(KEY_BASELINE_B_FRAMES, bFrames)
        } else {
            prefs.edit().putInt(KEY_BASELINE_B_FRAMES, bFrames).apply()
            bFrames
        }
        return learningKeySuffix(prefs.getBoolean(KEY_LONG_GOP, false), bFrames, baseline)
    }

    /** The part of an encoder config line that names the experiment, or "" when off. */
    fun describe(maxBFrames: Int): String = if (maxBFrames > 0) ";bframes=$maxBFrames" else ""
}
