package compress.joshattic.us.quality

/**
 * Whether a certification outcome lets a candidate stand, on the basis its plan was built on.
 * `BatchCompressorViewModel.certifyPixels` asks this and nothing else, so the rule is testable
 * without a device.
 *
 * The three bases are the three rules that existed before; this only puts them in one place and
 * adds [PairScoreOutcome.Incomplete] (b177 F1):
 *
 *  - [Basis.MEASURED_REQUIRED]: the encode overturned a keep-original decision and needs measured
 *    proof. Only a fully scored, passing sample stands ([ExhaustivePerceptualLosslessPolicy]).
 *  - [Basis.PROBE_BASIS]: a ladder ran. Measured evidence rules; absent evidence stands only at or
 *    above the codec default ratio, because a sub-default target rested on pixels alone
 *    ([QualityProbePolicy.certificationOutcomePasses]).
 *  - [Basis.NO_PROBE_BASIS]: no ladder ran (4K-class). Measured evidence rules; absent evidence
 *    leaves the structural verdict standing ([QualityProbePolicy.certificationOutcomePassesWithoutProbeBasis]).
 *
 * A partial sample is judged window by window first: a scored window with enough frames below the
 * bar rejects on every basis, and a scored window with too few frames rejects exactly as it does
 * in a complete sample. Only a partial sample whose every scored window passed falls back to the
 * absent-evidence rule of its basis. It is never pixel certification.
 */
object CertificationGate {

    enum class Basis { MEASURED_REQUIRED, PROBE_BASIS, NO_PROBE_BASIS }

    data class Verdict(
        /** The candidate may stand (final acceptance still needs the structural verdict). */
        val accepted: Boolean,
        val decision: CertificationDecision,
        /** Measured windows backed this acceptance: the only case that may wear the full PL label. */
        val pixelCertified: Boolean
    )

    fun basisOf(requiresMeasuredCertification: Boolean, probeEligible: Boolean): Basis = when {
        requiresMeasuredCertification -> Basis.MEASURED_REQUIRED
        probeEligible -> Basis.PROBE_BASIS
        else -> Basis.NO_PROBE_BASIS
    }

    fun evaluate(basis: Basis, usedRatio: Double, defaultRatio: Double, outcome: PairScoreOutcome): Verdict {
        val accepted = when (basis) {
            Basis.MEASURED_REQUIRED -> ExhaustivePerceptualLosslessPolicy.measuredCertificationPasses(outcome)
            Basis.PROBE_BASIS -> QualityProbePolicy.certificationOutcomePasses(usedRatio, defaultRatio, outcome)
            Basis.NO_PROBE_BASIS -> QualityProbePolicy.certificationOutcomePassesWithoutProbeBasis(outcome)
        }
        return Verdict(
            accepted = accepted,
            decision = CertificationDecision.of(outcome),
            pixelCertified = QualityProbePolicy.isPixelCertified(accepted, outcome)
        )
    }
}
