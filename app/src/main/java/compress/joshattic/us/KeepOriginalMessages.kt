package compress.joshattic.us

/**
 * What the user is told when a Perceptually Lossless run keeps a file as-is WITHOUT a measured
 * rejection, and on what basis.
 *
 * Until b163 every such file read "Already optimal — original retained" followed by the plan's
 * reason, for example "Source is already heavily compressed; a re-encode would visibly lose
 * quality". That sentence claims a perceptual fact that nothing measured. In batch_1790270611232
 * it was shown for twelve 8K sources that cannot be pixel-scored on this device at all, and for
 * four files whose probe export timed out. The rule now: a keep-original message names its basis,
 * and only a measurement may say "visibly".
 *
 * Pure, so every wording is unit-tested.
 */
object KeepOriginalMessages {

    /**
     * @param reason the plan's remux reason (the heuristic that decided), or null.
     * @param evidencePreferred the learning engine's class-level latch decided.
     * @param probedRatios ratios the probe ladder attempted on THIS file, if any.
     * @param probeDetail the ladder's own summary of what it measured, if it ran.
     * @param pixelCertifiableBlockReason why this file cannot be pixel-scored at all, if so
     *   (a CertificationStatus.SKIPPED_* value), else null.
     */
    fun upFront(
        reason: String?,
        evidencePreferred: Boolean,
        probedRatios: List<Double>,
        probeDetail: String?,
        pixelCertifiableBlockReason: String?
    ): String {
        val why = reason?.let { softenUnmeasuredClaim(it) } ?: "No re-encode was planned."
        return "Kept original, no copy written. $why ${basis(evidencePreferred, probedRatios, probeDetail, pixelCertifiableBlockReason)}"
    }

    /** The basis sentence alone, for records and tests. */
    fun basis(
        evidencePreferred: Boolean,
        probedRatios: List<Double>,
        probeDetail: String?,
        pixelCertifiableBlockReason: String?
    ): String = when {
        evidencePreferred ->
            "Basis: earlier measured failures for this device and content class (learned), not a measurement of this file."
        probedRatios.isEmpty() && pixelCertifiableBlockReason != null ->
            "Basis: heuristic. This file cannot be pixel-measured on this device: ${describeBlock(pixelCertifiableBlockReason)}."
        probedRatios.isEmpty() ->
            "Basis: heuristic. This file was not pixel-measured (no probe ran)."
        else ->
            "Basis: heuristic. Probes at ${probedRatios.joinToString(", ") { "%.2f".format(java.util.Locale.US, it) }} " +
                "produced no measurement that could decide it" +
                (probeDetail?.let { " (${shorten(it)})" } ?: "") + "."
    }

    /**
     * A heuristic reason may not assert what the user would see. "would visibly lose quality"
     * becomes "is predicted to lose quality"; measured wording stays only where a measurement
     * produced it (the SKIPPED_WOULD_DEGRADE path never comes through here).
     */
    internal fun softenUnmeasuredClaim(reason: String): String =
        reason
            .replace("a re-encode would visibly lose quality", "a re-encode is predicted to lose quality")
            .replace("kept exact stream copy", "the original is kept")

    private fun describeBlock(blockReason: String): String = when (blockReason) {
        CertificationStatus.SKIPPED_HDR -> "HDR has no validated quality model"
        CertificationStatus.SKIPPED_CODEC_DOWNGRADE -> "the output codec would be a downgrade from the source's"
        CertificationStatus.SKIPPED_VMAF_UNAVAILABLE -> "the VMAF scorer is not available on this device build"
        CertificationStatus.SKIPPED_GEOMETRY_ABOVE_CAP -> "its resolution is above the 4K scoring limit"
        else -> blockReason
    }

    private fun shorten(detail: String): String {
        val cut = detail.indexOf(" — ")
        val head = if (cut > 0) detail.substring(0, cut) else detail
        return if (head.length > 140) head.substring(0, 137) + "..." else head
    }
}
