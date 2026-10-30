package compress.joshattic.us.quality

/** Final PL transcodes require complete measured pixel evidence, independent of planning basis.
 * Basis/ratios remain in the API and diagnostics to explain how the candidate was selected.
 * They never authorize structural-only acceptance. Numeric gates are unchanged.
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
