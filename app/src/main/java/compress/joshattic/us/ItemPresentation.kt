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
        // A replacement asked for and not made. Only a proven save is "saved": copySaved is null
        // when none was attempted (the output was missing when the replacement came to run).
        item.status == BatchItemStatus.SavedCopy -> {
            // A refused replacement never opened the original. One whose write did not stand wrote the
            // rollback copy back and read it back equal: the original's bytes, but not a file left alone.
            val original = if (item.replacementOutcome in RESTORED) "Original restored intact after a failed replacement" else "Original untouched"
            when (item.copySaved) {
                true -> Line("$original; copy saved to the gallery", Tone.DONE)
                false -> Line("$original; saving a copy failed", Tone.WARNING)
                null -> Line("$original; no copy was saved", Tone.WARNING)
            }
        }
        item.status == BatchItemStatus.Compressing ->
            Line(if (replaceOriginals) "Original untouched until its copy is verified" else "Original untouched; a separate copy is being made", Tone.NEUTRAL)
        item.status == BatchItemStatus.Done && item.outputSize > 0L && item.outputPath != null ->
            if (item.galleryCopySaved) Line("Original untouched; copy saved to the gallery", Tone.DONE)
            else Line("Original untouched; copy ready in the app, not saved yet", Tone.NEUTRAL)
        item.status == BatchItemStatus.Pending && !item.isAlreadyCompressed ->
            Line(if (replaceOriginals) "Original will be replaced only after its copy is verified" else "Original stays untouched (copy mode)", Tone.NEUTRAL)
        else -> Line("Original untouched; no copy was made", Tone.NEUTRAL)
    }

    /** The replacement opened the original for writing and the rollback copy was written back. */
    private val RESTORED = setOf(
        ReplacementTransaction.RESTORED_WRITE_FAILED,
        ReplacementTransaction.RESTORED_READBACK_UNAVAILABLE,
        ReplacementTransaction.RESTORED_READBACK_MISMATCH
    )
}

/**
 * The row's badge, a few words read before anything else on it. What happened to the original
 * outranks how the encode went: a row's terminal result is set before its replacement runs, so a
 * row whose original may now be incomplete would otherwise still read "Compressed". A copy is
 * called saved only when its save was proven ([OriginalStatus] says the same in full).
 */
object StatusLabel {

    /** The badges the batch's end notice points to, by these words. */
    const val ORIGINAL_AT_RISK = "Original at risk"
    const val NOT_REPLACED = "Not replaced"

    fun of(item: BatchVideoItem): String {
        if (isAtRisk(item)) return ORIGINAL_AT_RISK
        when (item.status) {
            BatchItemStatus.Replaced -> return "Replaced"
            BatchItemStatus.SavedCopy -> return if (item.copySaved == true) "Copy saved" else NOT_REPLACED
            else -> Unit
        }
        item.terminalResult?.let { return terminal(it) }
        return when {
            item.status == BatchItemStatus.Skipped || item.isAlreadyCompressed -> "Skipped"
            item.status == BatchItemStatus.Compressing ->
                item.phaseFraction?.takeIf { item.phase.hasFraction }?.let { "${item.phase.label} ${(it * 100f).toInt()}%" }
                    ?: item.phase.label
            item.status == BatchItemStatus.Done -> "Copy ready"
            item.status == BatchItemStatus.Failed -> "Failed"
            item.status == BatchItemStatus.Cancelled -> "Cancelled"
            else -> "Ready"
        }
    }

    /** Whether the badge is drawn as an error: a failure, an original at risk, or no copy where one was due. */
    fun isAlarming(item: BatchVideoItem): Boolean = isAtRisk(item) || isFailed(item) || isNotReplacedAndNotSaved(item)

    /** A replacement failed and its rollback could not be confirmed: the original may be incomplete. */
    fun isAtRisk(item: BatchVideoItem): Boolean =
        item.replacementOutcome == ReplacementOutcome.AT_RISK_ORIGINAL_PRESERVED ||
            item.replacementOutcome == ReplacementOutcome.AT_RISK_RECOVERY_RETAINED

    /** The encode itself failed. */
    fun isFailed(item: BatchVideoItem): Boolean = item.terminalResult?.isFailure == true || item.status == BatchItemStatus.Failed

    /** A replacement was asked for and not made, and the output was not saved as a copy in its place. */
    fun isNotReplacedAndNotSaved(item: BatchVideoItem): Boolean = item.status == BatchItemStatus.SavedCopy && item.copySaved != true

    private fun terminal(result: BatchTerminalResult): String = when (result) {
        BatchTerminalResult.TRANSCODED_SMALLER,
        BatchTerminalResult.LOSSY_SMALLER -> "Compressed"
        BatchTerminalResult.TRANSCODED_NOT_MEANINGFULLY_SMALLER -> "No size win"
        BatchTerminalResult.ALREADY_HIGHLY_OPTIMIZED -> "Already efficient"
        BatchTerminalResult.REMUX_PREFERRED_BY_EVIDENCE -> "Kept (measured)"
        BatchTerminalResult.EXPLICIT_REMUX -> "Remuxed"
        BatchTerminalResult.UNEXPECTED_REMUX -> "Original kept"
        BatchTerminalResult.SKIPPED_ALREADY_COMPRESSED -> "Skipped"
        BatchTerminalResult.SKIPPED_WOULD_DEGRADE -> "Skipped (quality)"
        BatchTerminalResult.CANCELLED -> "Cancelled"
        BatchTerminalResult.OUTPUT_VALIDATION_FAILED,
        BatchTerminalResult.ENCODER_FAILURE,
        BatchTerminalResult.UNSUPPORTED_CONTAINER,
        BatchTerminalResult.FAILED -> "Failed"
    }
}

/**
 * The result block's first line. A row's terminal result is the encode's, and it is settled before a
 * replacement runs: on its own it read "Outcome: Compressed", in the colour of a success, under a
 * badge that said the original was at risk. When a replacement was asked for and did not happen the
 * line says so, and it is drawn as a success only on a row whose badge is not an alarm.
 */
object OutcomeLine {

    fun of(item: BatchVideoItem): OriginalStatus.Line? {
        val terminal = item.terminalResult ?: return null
        val encode = "Outcome: ${terminal.label}"
        val text = when {
            terminal.isFailure -> encode
            StatusLabel.isAtRisk(item) -> "$encode, but replacing the original failed"
            StatusLabel.isNotReplacedAndNotSaved(item) -> "$encode, but the original was not replaced"
            else -> encode
        }
        return OriginalStatus.Line(text, if (StatusLabel.isAlarming(item)) OriginalStatus.Tone.WARNING else OriginalStatus.Tone.DONE)
    }
}

/**
 * What the batch says above the list when it ends. A failed replacement is not a failed item (its
 * encode succeeded), so a count of failures alone let a batch that put an original at risk end
 * "Finished N outputs" with no warning: the only notice was on that item's row, wherever in the
 * list it was. The warning counts every row whose badge is an alarm ([StatusLabel.isAlarming]),
 * each once, the originals at risk first.
 */
object BatchEndNotice {

    /** The total is named as the summary card names it: a size reduction, not space saved ([AcceptedReduction]). */
    fun finished(doneCount: Int, realCompressions: Int, reductionBytes: Long): String =
        "Finished $doneCount output${if (doneCount == 1) "" else "s"}. Real compressions: $realCompressions. " +
            "${AcceptedReduction.LABEL}: ${formatFileSize(reductionBytes)}."

    fun warning(items: List<BatchVideoItem>): String? {
        val (atRisk, others) = items.partition(StatusLabel::isAtRisk)
        val (failed, rest) = others.partition(StatusLabel::isFailed)
        val notReplaced = rest.count(StatusLabel::isNotReplacedAndNotSaved)
        val heldInApp = atRisk.count { it.replacementOutcome == ReplacementOutcome.AT_RISK_RECOVERY_RETAINED }
        val parts = buildList {
            if (atRisk.size == 1) {
                add("1 original may be incomplete after a failed replacement: see the item marked \"${StatusLabel.ORIGINAL_AT_RISK}\" for where its intact copy is.")
            } else if (atRisk.size > 1) {
                add("${atRisk.size} originals may be incomplete after a failed replacement: see the items marked \"${StatusLabel.ORIGINAL_AT_RISK}\" for where their intact copies are.")
            }
            // Only the app holds these: clearing its storage would delete the last intact original.
            if (heldInApp == 1) add("An intact copy is held inside the app, so do not clear the app's storage.")
            else if (heldInApp > 1) add("$heldInApp intact copies are held inside the app, so do not clear the app's storage.")
            if (failed.isNotEmpty()) add("${failed.size} item${if (failed.size == 1) "" else "s"} failed. Tap each item for details.")
            // What became of each original is its row's to say: untouched, or restored after a failed write.
            if (notReplaced == 1) {
                add("1 original was not replaced and its output was not saved as a copy either: see the item marked \"${StatusLabel.NOT_REPLACED}\".")
            } else if (notReplaced > 1) {
                add("$notReplaced originals were not replaced and their outputs were not saved as copies either: see the items marked \"${StatusLabel.NOT_REPLACED}\".")
            }
        }
        return if (parts.isEmpty()) null else parts.joinToString(" ")
    }

    /**
     * The message of a batch stopped by an unhandled failure. It still owes the warning about the
     * rows it finished; [existing] is a message already on screen, kept as it is.
     */
    fun stopped(items: List<BatchVideoItem>, existing: String?, cause: String): String {
        val message = existing ?: "Batch stopped: $cause"
        val warning = warning(items) ?: return message
        return if (message.startsWith(warning)) message else "$warning $message"
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
