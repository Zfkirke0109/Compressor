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
 * actual minus predicted averaged -0.030 (sd 0.033, range -0.184 to +0.019). Used directly, it would
 * have skipped two encodes that did save (job_4e02400464fd 2.7 %, job_c0f82bb62f94 4.6 %). The
 * gate therefore uses the mean less [MARGIN] (the bias plus three standard deviations), and never
 * less than the learned value. Replayed over the 33 distinct proven-rung encodes of the retained
 * b167 runs, that skips job_732f7ecfb699 (predicted 1.03x the source) and nothing that saved.
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

    /** The factor the worth-encoding prediction uses: the learned one unless the probes bound it higher. */
    fun forPrediction(learned: Double, windowFactors: List<Double>): Double {
        val bound = lowerBound(windowFactors) ?: return learned
        return if (bound > learned) bound else learned
    }

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
