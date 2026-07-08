package compress.joshattic.us

object PerceptualLosslessVerifier {
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
