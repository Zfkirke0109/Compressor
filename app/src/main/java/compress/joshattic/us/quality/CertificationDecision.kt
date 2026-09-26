package compress.joshattic.us.quality

/**
 * What a certification (or floor-recovery) scoring outcome is evidence OF. Derived from the
 * outcome with [QualityProbePolicy.rungVerdict], so it adds no threshold of its own: the bar,
 * the 12-frame minimum and their meaning live there.
 *
 * Acceptance is unchanged: only [PASSED] certifies an output, exactly as
 * [QualityProbePolicy.windowsPass] did. What changes is what a non-pass is CALLED and what it
 * teaches. `VmafPairScorer` can return [PairScoreOutcome.Scored] with a window of 1-11 frames;
 * `windowsPass` fails it, and `certifyPixels` used to treat that as "sampled VMAF below
 * thresholds": the item was labelled "would visibly lose quality" and the profile learned a
 * quality failure, although no adequately sampled window was below the bar.
 */
enum class CertificationDecision(val wire: String) {
    /** Every window has enough frames and clears the Perceptually Lossless bar. */
    PASSED("passed"),

    /** At least one window with enough frames is below the bar. Measured negative evidence. */
    MEASURED_FAILURE("measured_below_bar"),

    /** No adequate window failed, but at least one held too few frames to decide. Not a measurement. */
    INSUFFICIENT_EVIDENCE("insufficient_frames"),

    /** Nothing could be scored (no library, geometry, decoder failure, empty result). */
    UNAVAILABLE("unavailable"),

    /** Frames could not be time-aligned: positive evidence of frame loss or retiming. */
    MISALIGNED("misaligned");

    /** Evidence against this output: may be labelled a quality failure and may be learned. */
    val isMeasuredNegative: Boolean get() = this == MEASURED_FAILURE || this == MISALIGNED

    companion object {
        fun of(outcome: PairScoreOutcome): CertificationDecision = when (outcome) {
            PairScoreOutcome.Unavailable -> UNAVAILABLE
            is PairScoreOutcome.MisalignmentRejected -> MISALIGNED
            is PairScoreOutcome.Scored -> when (QualityProbePolicy.rungVerdict(outcome.windows)) {
                QualityProbePolicy.RungVerdict.UNMEASURED -> UNAVAILABLE
                QualityProbePolicy.RungVerdict.FAILED -> MEASURED_FAILURE
                QualityProbePolicy.RungVerdict.INSUFFICIENT -> INSUFFICIENT_EVIDENCE
                // Probe selection's extra margin is a probe rule; certification needs the bar only.
                QualityProbePolicy.RungVerdict.MARGINAL, QualityProbePolicy.RungVerdict.PASSED -> PASSED
            }
        }

        /** Fewest compared frames in any window, for the reason text. Null when nothing was scored. */
        fun fewestFrames(outcome: PairScoreOutcome): Int? =
            (outcome as? PairScoreOutcome.Scored)?.windows?.minOfOrNull { it.comparedFrames }
    }
}
