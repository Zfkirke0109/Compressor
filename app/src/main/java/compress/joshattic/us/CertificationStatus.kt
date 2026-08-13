package compress.joshattic.us

import compress.joshattic.us.quality.PairScoreOutcome

/**
 * Why sampled pixel certification did or did not run for a job.
 *
 * Motivated by two 219-job captures in which `certWindowScores` and `certBandingDiag` were null
 * for all 438 jobs with nothing recording why. Absent evidence and unexplained-absent evidence
 * look identical in a capture, and only one of them is a problem — so the record has to say
 * which. A null field is not a measurement; it is a question.
 *
 * Pure: no Android dependencies, fully unit-testable.
 */
object CertificationStatus {

    // Ran, with the outcome the scorer actually returned.
    const val SCORED = "ran_scored"
    const val UNAVAILABLE = "ran_unavailable"
    const val MISALIGNED = "ran_misalignment_rejected"

    // Did not run. Each names the specific gate that stopped it.
    const val SKIPPED_NOT_PL_MODE = "skipped_effective_mode_not_perceptually_lossless"
    const val SKIPPED_NO_PLAN = "skipped_no_perceptual_plan"
    const val SKIPPED_FELL_BACK_TO_REMUX = "skipped_output_already_failed_verification"
    const val SKIPPED_HDR = "skipped_hdr_not_pixel_validated"
    const val SKIPPED_CODEC_DOWNGRADE = "skipped_output_codec_is_a_downgrade"
    const val SKIPPED_VMAF_UNAVAILABLE = "skipped_vmaf_native_unavailable"
    const val SKIPPED_GEOMETRY_ABOVE_CAP = "skipped_geometry_above_pixel_scoring_cap"

    /**
     * Why [pixelCertifiable] was false, or null when it was true.
     *
     * Order matters only for reporting: when several gates are closed the first is named, so the
     * reason stays stable and a capture can be grouped by it. Callers pass the same inputs the
     * gate itself used, so this cannot drift from the decision.
     */
    fun blockReasonFor(
        isHdr: Boolean,
        codecDowngrade: Boolean,
        vmafAvailable: Boolean,
        geometryScoreable: Boolean
    ): String? = when {
        isHdr -> SKIPPED_HDR
        codecDowngrade -> SKIPPED_CODEC_DOWNGRADE
        !vmafAvailable -> SKIPPED_VMAF_UNAVAILABLE
        !geometryScoreable -> SKIPPED_GEOMETRY_ABOVE_CAP
        else -> null
    }

    /** The status for a certification attempt that actually ran. */
    fun forOutcome(outcome: PairScoreOutcome): String = when (outcome) {
        is PairScoreOutcome.Scored -> SCORED
        PairScoreOutcome.Unavailable -> UNAVAILABLE
        PairScoreOutcome.MisalignmentRejected -> MISALIGNED
    }

    /** True when the status means certification never executed. */
    fun didNotRun(status: String?): Boolean = status != null && status.startsWith("skipped_")
}
