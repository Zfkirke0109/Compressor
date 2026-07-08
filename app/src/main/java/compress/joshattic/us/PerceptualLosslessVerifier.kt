package compress.joshattic.us

object PerceptualLosslessVerifier {
    fun shouldFallbackToRemux(
        report: OutputVerificationReport,
        _sourceBytes: Long,
        _outputBytes: Long
    ): Boolean {
        return !report.verified || !report.replacementSafe
    }
}
