package compress.joshattic.us.quality

/**
 * The encoder's overshoot on THIS file, as the proven rung's probe clips measured it, for the
 * prediction that decides whether the full encode is worth running. Size only: nothing here
 * touches a quality threshold, and the post-encode size and verification checks still judge the
 * real output.
 *
 * Why. b167 job_732f7ecfb699 (4K, heavily compressed H.264): the per-profile learned overshoot was
 * 1.003, the probe clips at the proven rung measured 1.26 (mean of three windows), and the full
 * encode came out at 1.235. It wrote 587 MB for a 528 MB source and was discarded for size after
 * 70 s of 4K encoding. The probes had measured the overshoot two minutes earlier.
 *
 * Why a lower bound and not the measurement. The probe model runs high. Over 40 b166/b167 encodes,
 * actual minus predicted averaged -0.030 (sd 0.033, range -0.184 to +0.019), and b168 repeated it
 * (21 encodes, -0.026, range -0.068 to +0.019). The bound is the mean less [MARGIN] (the bias plus
 * three standard deviations).
 *
 * Why the file's own measurement outranks the learned value (b168). The first version of this gate
 * used max(learned, bound). b168 job_458aa0663c3e passed its probes at 0.90 and was kept as
 * "already efficient": its probes measured 1.062 (bound 0.932), but the learned value was 1.119,
 * which is exactly the running average of this bucket's two b167 encodes, 458a itself at 1.003 and
 * 732f at 1.235. One heavily compressed file had raised the prediction for every file in the
 * 4K H.264 bucket, and it outranked the file's own evidence. b167 had encoded 458a at 1.003 and
 * saved 9.5 %, pixel-certified. The replay that claimed "skips nothing that saved" assumed a learned
 * value of 1.0 for every file, so it never saw learned state accumulate in batch order; that claim
 * was wrong. Now, when the file's own probes give a bound, the learned value is not used: it is
 * evidence about other files. It still stands in when the probes gave fewer than [MIN_WINDOWS].
 * Replaying b168's 28 gate decisions with their logged learned values, only 458a changes (to
 * encode); 732f is still skipped (bound 1.13).
 */
object MeasuredOvershoot {

    /** Subtracted from the measured mean; see the class comment for the calibration. */
    const val MARGIN = 0.13

    /** Fewer windows than this is too little content to generalise to the whole file. */
    const val MIN_WINDOWS = 2

    /** Conservative lower bound on the full encode's overshoot, or null without enough windows. */
    fun lowerBound(windowFactors: List<Double>): Double? {
        val usable = windowFactors.filter { it.isFinite() && it > 0.0 }
        if (usable.size < MIN_WINDOWS) return null
        return usable.average() - MARGIN
    }

    /**
     * The factor the worth-encoding prediction uses: this file's probe bound when there is one,
     * else the learned value. (The prediction itself never assumes less than 1.0.)
     */
    fun forPrediction(learned: Double, windowFactors: List<Double>): Double =
        lowerBound(windowFactors) ?: learned

    /** True when [forPrediction] came from this file's probes rather than the learned value. */
    fun fromThisFile(windowFactors: List<Double>): Boolean = lowerBound(windowFactors) != null

    /** `measuredOvershoot=1.260(n=3,bound=1.130),learned=1.003,used=1.130` */
    fun describe(learned: Double, windowFactors: List<Double>): String {
        val usable = windowFactors.filter { it.isFinite() && it > 0.0 }
        val bound = lowerBound(windowFactors)
        val fmt = { v: Double -> "%.3f".format(java.util.Locale.US, v) }
        val measured = if (usable.isEmpty()) "none" else "${fmt(usable.average())}(n=${usable.size}" +
            (bound?.let { ",bound=${fmt(it)}" } ?: ",too few windows") + ")"
        return "measuredOvershoot=$measured,learned=${fmt(learned)},used=${fmt(forPrediction(learned, windowFactors))}"
    }
}
