package compress.joshattic.us

object PerceptualLosslessVerifier {
    /**
     * The attempt-ledger outcome of a candidate [shouldFallbackToRemux] discarded. A structural
     * pass whose replacement was blocked (not smaller, or larger than the size tolerance) is not a
     * structural failure: b182 job_50d1ff00cad6 passed every predicate, came out 23,628 bytes
     * larger than its source, and was recorded as `structural_failed`, which a failure tally
     * would count against the verifier.
     */
    fun discardedOutcome(report: OutputVerificationReport): String =
        if (report.verified) AttemptLedger.REPLACEMENT_BLOCKED else AttemptLedger.STRUCTURAL_FAILED

    fun shouldFallbackToRemux(
        report: OutputVerificationReport,
        sourceBytes: Long,
        outputBytes: Long
    ): Boolean {
        if (!report.verified || !report.replacementSafe) return true
        if (sourceBytes <= 0L) return false
        val maxAllowed = (sourceBytes * (1.0 + BatchQualityBitratePolicy.PERCEPTUAL_LOSSLESS_SIZE_TOLERANCE)).toLong()
        return outputBytes > maxAllowed
    }
}
