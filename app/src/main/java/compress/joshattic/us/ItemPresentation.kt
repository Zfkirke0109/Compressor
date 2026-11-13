package compress.joshattic.us

import java.util.Locale

/**
 * Text that describes what one phase is doing ("Finalizing: writing the source's metadata…").
 * It belongs to [phase] and is shown only while the row is in that phase: the v1.6.311 recording
 * showed "Verifying output" above a finalizing detail written before verification began, because
 * the row kept one free-form message across every phase. Durable text (an outcome, a refusal, a
 * warning) is not a detail and lives in [BatchVideoItem.message].
 */
data class PhaseDetail(val phase: ItemPhase, val text: String)

/**
 * Why this item is on a later attempt, kept on the row from the moment the attempt starts until the
 * item is reset, whatever the encoder's poller or a later phase writes. [ordinal] is this item's own
 * attempt number (its attempt ledger), never the batch-wide callback token: token 21 in the
 * recording was the item's second attempt, not its twenty-first.
 */
data class AttemptContext(val ordinal: Int, val total: Int, val reason: String) {
    val label: String get() = "Attempt $ordinal of $total"

    companion object {
        /**
         * The context of the safer-rung retry. [rejectedRatio] is the first candidate's ratio, [saferRatio]
         * the measured rung the retry encodes at; [why] says what refused the first candidate.
         */
        fun saferRungRetry(ordinal: Int, total: Int, rejectedRatio: Double, saferRatio: Double, why: RetryCause): AttemptContext {
            val first = String.format(Locale.US, "%.2f", rejectedRatio)
            val safer = String.format(Locale.US, "%.2f", saferRatio)
            val cause = when (why) {
                RetryCause.MEASURED_BELOW_BAR -> "the first candidate ($first) did not pass the quality check"
                RetryCause.NOT_CERTIFIED -> "the first candidate ($first) could not be certified"
                RetryCause.VERIFICATION_FAILED -> "the first candidate ($first) failed output verification"
                RetryCause.TEST_VETO -> "an instrumented test refused the first candidate ($first); not a quality measurement"
            }
            return AttemptContext(ordinal, total, "$cause, so it was discarded; encoding again at the safer measured ratio $safer, which is larger")
        }
    }
}

/** What refused the attempt a retry replaces. */
enum class RetryCause { MEASURED_BELOW_BAR, NOT_CERTIFIED, VERIFICATION_FAILED, TEST_VETO }

/**
 * What has happened, or will happen, to the user's original and to the compressed copy, in words.
 * "Copy ready" (an output inside the app) and "copy saved" (proven in the gallery) are different
 * states, and a replacement that left the original incomplete is never described as kept.
 */
object OriginalStatus {
    enum class Tone { NEUTRAL, DONE, WARNING }

    data class Line(val text: String, val tone: Tone)

    fun describe(item: BatchVideoItem, replaceOriginals: Boolean): Line = when {
        item.replacementOutcome == ReplacementOutcome.AT_RISK_ORIGINAL_PRESERVED ->
            Line("Original may be incomplete; an intact copy of it was saved to the gallery. Check the original.", Tone.WARNING)
        item.replacementOutcome == ReplacementOutcome.AT_RISK_RECOVERY_RETAINED ->
            Line("Original may be incomplete; an intact copy is held inside the app. Do not clear the app's storage.", Tone.WARNING)
        item.status == BatchItemStatus.Replaced ->
            Line("Original replaced by the verified copy", Tone.DONE)
        item.status == BatchItemStatus.SavedCopy ->
            if (item.copySaved == false) Line("Original untouched; saving a copy failed", Tone.WARNING)
            else Line("Original untouched; copy saved to the gallery", Tone.DONE)
        item.status == BatchItemStatus.Compressing ->
            Line(if (replaceOriginals) "Original untouched until its copy is verified" else "Original untouched; a separate copy is being made", Tone.NEUTRAL)
        item.status == BatchItemStatus.Done && item.outputSize > 0L && item.outputPath != null ->
            if (item.galleryCopySaved) Line("Original untouched; copy saved to the gallery", Tone.DONE)
            else Line("Original untouched; copy ready in the app, not saved yet", Tone.NEUTRAL)
        item.status == BatchItemStatus.Pending && !item.isAlreadyCompressed ->
            Line(if (replaceOriginals) "Original will be replaced only after its copy is verified" else "Original stays untouched (copy mode)", Tone.NEUTRAL)
        else -> Line("Original untouched; no copy was made", Tone.NEUTRAL)
    }
}

/**
 * What applying a recommendation would change, for the whole batch. The recommendation is shown on
 * one item but its settings are the batch's, and some of them leave Perceptually Lossless or cap the
 * frame rate; the user is shown each change before it is made. Its savings and risk figures are
 * rules of thumb about the kind of clip, not measurements of this file.
 */
data class RecommendationPreview(
    val changes: List<String>,
    val leavesPerceptuallyLossless: Boolean,
    val capsFrameRate: Boolean
) {
    val changesAnything: Boolean get() = changes.isNotEmpty()

    companion object {
        fun of(recommendation: CompressionRecommendation, quality: String, codec: String, frameRate: String): RecommendationPreview {
            val changes = mutableListOf<String>()
            if (recommendation.qualityPreset != quality) {
                changes += "Quality: ${qualityName(quality)} → ${qualityName(recommendation.qualityPreset)}"
            }
            if (recommendation.codecOption != codec) changes += "Codec: $codec → ${recommendation.codecOption}"
            val caps = recommendation.frameRateOption != frameRate && recommendation.frameRateOption != BatchFrameRateOption.ORIGINAL.label
            if (recommendation.frameRateOption != frameRate) {
                changes += "Frame rate: $frameRate → ${recommendation.frameRateOption}" + if (caps) " (frames are dropped)" else ""
            }
            return RecommendationPreview(
                changes = changes,
                leavesPerceptuallyLossless = quality == BatchQualityPreset.ORIGINAL.label && recommendation.qualityPreset != quality,
                capsFrameRate = caps
            )
        }

        /** The preset's name, with "lossy" on every preset that is. */
        fun qualityName(label: String): String = when (label) {
            BatchQualityPreset.ORIGINAL.label -> "Perceptually Lossless"
            BatchQualityPreset.REMUX_ONLY.label -> "Remux Only (no re-encode)"
            else -> "$label (lossy)"
        }

        /** How the static figures are labelled wherever they are shown. */
        fun estimateLine(recommendation: CompressionRecommendation): String =
            "Rule-of-thumb estimate for this kind of clip, not measured: about ${recommendation.expectedSavings} smaller, " +
                "${recommendation.qualityRisk.lowercase(Locale.US)} quality risk"
    }
}
