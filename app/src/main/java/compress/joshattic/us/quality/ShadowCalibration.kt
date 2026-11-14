package compress.joshattic.us.quality

/**
 * Whether a certification also runs the VMAF v1 shadow model (b177 WP2).
 *
 * The shadow is calibration telemetry: no verdict reads it (the gate is vmaf_v0.6.1 mean/p5/min,
 * see CertificationGate). Until b177 it ran on every certification window. In PL-B it logged
 * 748,120 ms over 74 windows, 399,072 ms of it on one 25 s 4K clip, so it lengthened production
 * batches without affecting a single decision. It is now:
 *
 *  - OFF unless the user turns on the calibration experiment (EncoderExperiments);
 *  - limited to [MAX_WINDOWS_PER_BATCH] certification windows per batch;
 *  - skipped above [MAX_PIXELS] (1080p class), where one v1 window took 98-155 s in b177.
 *
 * Turning it off changes no acceptance: v0 scoring, the frame minimum and every threshold are
 * identical with or without it (PartialScoringTest / ShadowCalibrationTest).
 */
object ShadowCalibration {
    const val MAX_WINDOWS_PER_BATCH = 24
    const val MAX_PIXELS = 1920 * 1088

    data class Decision(val shadow: Boolean, val reason: String)

    fun decide(enabled: Boolean, windowsUsedThisBatch: Int, plannedWindows: Int, width: Int, height: Int): Decision = when {
        !enabled -> Decision(false, "off")
        width.toLong() * height.toLong() > MAX_PIXELS -> Decision(false, "above_1080p_class")
        windowsUsedThisBatch + plannedWindows.coerceAtLeast(1) > MAX_WINDOWS_PER_BATCH -> Decision(false, "batch_budget_spent")
        else -> Decision(true, "calibration")
    }
}
