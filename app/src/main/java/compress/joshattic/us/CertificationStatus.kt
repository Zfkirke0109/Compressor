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

    // Ran as the bitrate-floor RECOVERY attempt and did not pass, so the encode fell back. A
    // separate family from the ones above because the two certification sites are different
    // questions: recovery asks "did the encoder undershoot the floor while still looking fine?",
    // final certification asks "is the accepted output actually perceptually lossless?".
    //
    // Without these, a recovery attempt that ran and measured real windows was labelled
    // SKIPPED_FELL_BACK_TO_REMUX by the gate evaluated afterwards — a capture then showed
    // populated certWindowScores beside a status claiming certification never happened, which is
    // the same absent-vs-unexplained confusion this whole type exists to prevent.
    const val RECOVERY_SCORED_FAILED = "ran_floor_recovery_scored_below_bar"
    const val RECOVERY_UNAVAILABLE = "ran_floor_recovery_unavailable"
    const val RECOVERY_MISALIGNED = "ran_floor_recovery_misalignment_rejected"

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
        is PairScoreOutcome.MisalignmentRejected -> MISALIGNED
    }

    /**
     * The status for a bitrate-floor recovery attempt that ran and did NOT recover the encode.
     * A recovery that passes is not reported through here: the job goes on to full certification,
     * whose [forOutcome] status is the one that describes the accepted output.
     */
    fun forFailedRecoveryOutcome(outcome: PairScoreOutcome): String = when (outcome) {
        is PairScoreOutcome.Scored -> RECOVERY_SCORED_FAILED
        PairScoreOutcome.Unavailable -> RECOVERY_UNAVAILABLE
        is PairScoreOutcome.MisalignmentRejected -> RECOVERY_MISALIGNED
    }

    /** True when the status means certification never executed. */
    fun didNotRun(status: String?): Boolean = status != null && status.startsWith("skipped_")
}
