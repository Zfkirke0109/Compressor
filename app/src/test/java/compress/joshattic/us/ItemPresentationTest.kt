package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a row says about the original and the copy, why it is on a second attempt, and what a
 * recommendation would change. Pure functions of the row, so every state is checked here.
 */
class ItemPresentationTest {

    private fun item(
        status: BatchItemStatus = BatchItemStatus.Pending,
        outputSize: Long = 0L,
        outputPath: String? = null,
        replacementOutcome: String? = null,
        copySaved: Boolean? = null,
        galleryCopySaved: Boolean = false,
        terminal: BatchTerminalResult? = null
    ) = BatchVideoItem(
        sourceUri = TestUris.placeholder, originalName = "clip.mp4", originalSize = 100_000_000L,
        originalWidth = 1920, originalHeight = 1080, originalBitrate = 8_000_000, originalAudioBitrate = 128_000,
        originalFps = 30f, durationMs = 60_000L, status = status, outputSize = outputSize, outputPath = outputPath,
        replacementOutcome = replacementOutcome, copySaved = copySaved, galleryCopySaved = galleryCopySaved, terminalResult = terminal
    )

    private fun line(item: BatchVideoItem, replace: Boolean = false) = OriginalStatus.describe(item, replace)

    @Test
    fun theOriginalAndTheCopyAreDescribedForWhatTheyAre() {
        assertEquals("Original stays untouched (copy mode)", line(item()).text)
        assertEquals("Original will be replaced only after its copy is verified", line(item(), replace = true).text)
        assertEquals("Original untouched; a separate copy is being made", line(item(BatchItemStatus.Compressing)).text)
        assertEquals("Original untouched until its copy is verified", line(item(BatchItemStatus.Compressing), replace = true).text)
        // A copy inside the app is not a copy in the gallery.
        val ready = item(BatchItemStatus.Done, outputSize = 1L, outputPath = "/cache/out.mp4", terminal = BatchTerminalResult.TRANSCODED_SMALLER)
        assertEquals("Original untouched; copy ready in the app, not saved yet", line(ready).text)
        assertEquals("Original untouched; copy saved to the gallery", line(ready.copy(galleryCopySaved = true)).text)
        assertEquals("Original untouched; copy saved to the gallery", line(item(BatchItemStatus.SavedCopy, copySaved = true)).text)
        assertEquals(OriginalStatus.Tone.WARNING, line(item(BatchItemStatus.SavedCopy, copySaved = false)).tone)
        assertEquals("Original replaced by the verified copy", line(item(BatchItemStatus.Replaced)).text)
        // Kept outcomes, failures and cancellations leave the original and make no copy.
        for (kept in listOf(
            item(BatchItemStatus.Done, terminal = BatchTerminalResult.ALREADY_HIGHLY_OPTIMIZED),
            item(BatchItemStatus.Failed, terminal = BatchTerminalResult.OUTPUT_VALIDATION_FAILED),
            item(BatchItemStatus.Cancelled, terminal = BatchTerminalResult.CANCELLED)
        )) assertEquals("Original untouched; no copy was made", line(kept).text)
    }

    @Test
    fun aReplacementThatLeftTheOriginalIncompleteIsNeverCalledKept() {
        for (outcome in listOf(ReplacementOutcome.AT_RISK_ORIGINAL_PRESERVED, ReplacementOutcome.AT_RISK_RECOVERY_RETAINED)) {
            // The item's status after such a replacement is SavedCopy, which alone would read as safe.
            val l = line(item(BatchItemStatus.SavedCopy, replacementOutcome = outcome, copySaved = true))
            assertEquals(OriginalStatus.Tone.WARNING, l.tone)
            assertTrue(l.text, l.text.startsWith("Original may be incomplete"))
            assertFalse(l.text, l.text.contains("untouched"))
        }
        assertTrue(line(item(BatchItemStatus.SavedCopy, replacementOutcome = ReplacementOutcome.AT_RISK_RECOVERY_RETAINED)).text
            .contains("Do not clear the app's storage"))
    }

    @Test
    fun aCopyIsCalledSavedOnlyWhenItsSaveWasProven() {
        val done = BatchTerminalResult.TRANSCODED_SMALLER
        // A replacement that was asked for and not made: a copy was saved instead, or it was not.
        assertEquals("Original untouched; copy saved to the gallery", line(item(BatchItemStatus.SavedCopy, copySaved = true)).text)
        assertEquals("Original untouched; saving a copy failed", line(item(BatchItemStatus.SavedCopy, copySaved = false)).text)
        // The output was missing at replacement time, so nothing was saved (REFUSED_OUTPUT_MISSING).
        val missing = line(item(BatchItemStatus.SavedCopy, replacementOutcome = ReplacementOutcome.REFUSED_OUTPUT_MISSING, terminal = done))
        assertEquals("Original untouched; no copy was saved", missing.text)
        assertEquals(OriginalStatus.Tone.WARNING, missing.tone)
        assertEquals("Copy saved", StatusLabel.of(item(BatchItemStatus.SavedCopy, copySaved = true, terminal = done)))
        for (saved in listOf(false, null)) {
            val notSaved = item(BatchItemStatus.SavedCopy, copySaved = saved, terminal = done)
            assertEquals("Not replaced", StatusLabel.of(notSaved))
            assertTrue(StatusLabel.isAlarming(notSaved))
        }
        assertFalse(StatusLabel.isAlarming(item(BatchItemStatus.SavedCopy, copySaved = true, terminal = done)))
    }

    @Test
    fun theBadgeLeadsWithWhatHappenedToTheOriginal() {
        val done = BatchTerminalResult.TRANSCODED_SMALLER
        // A row's terminal result is set before its replacement runs, so the replacement's outcome
        // has to come first: otherwise a row whose original may be incomplete reads "Compressed".
        for (outcome in listOf(ReplacementOutcome.AT_RISK_ORIGINAL_PRESERVED, ReplacementOutcome.AT_RISK_RECOVERY_RETAINED)) {
            for (saved in listOf(true, false, null)) {
                val atRisk = item(BatchItemStatus.SavedCopy, replacementOutcome = outcome, copySaved = saved, terminal = done)
                assertEquals("Original at risk", StatusLabel.of(atRisk))
                assertTrue(StatusLabel.isAlarming(atRisk))
            }
        }
        assertEquals("Replaced", StatusLabel.of(item(BatchItemStatus.Replaced, terminal = done)))
        assertFalse(StatusLabel.isAlarming(item(BatchItemStatus.Replaced, terminal = done)))
        // Without a replacement, the terminal result names the row as before.
        assertEquals("Compressed", StatusLabel.of(item(BatchItemStatus.Done, terminal = done)))
        assertEquals("Already efficient", StatusLabel.of(item(BatchItemStatus.Done, terminal = BatchTerminalResult.ALREADY_HIGHLY_OPTIMIZED)))
        val failed = item(BatchItemStatus.Failed, terminal = BatchTerminalResult.OUTPUT_VALIDATION_FAILED)
        assertEquals("Failed", StatusLabel.of(failed))
        assertTrue(StatusLabel.isAlarming(failed))
        assertFalse(StatusLabel.isAlarming(item(BatchItemStatus.Done, terminal = done)))
        assertEquals("Ready", StatusLabel.of(item()))
        assertEquals("Skipped", StatusLabel.of(item(BatchItemStatus.Skipped)))
    }

    @Test
    fun theOutcomeLineIsNotASuccessWhenTheReplacementDidNotHappen() {
        val done = BatchTerminalResult.TRANSCODED_SMALLER
        val success = OriginalStatus.Line("Outcome: Compressed", OriginalStatus.Tone.DONE)
        // Without a replacement, and after one that was made, the encode's result is the outcome.
        assertEquals(success, OutcomeLine.of(item(BatchItemStatus.Done, terminal = done)))
        assertEquals(success, OutcomeLine.of(item(BatchItemStatus.Replaced, terminal = done)))
        // A refused replacement whose copy was saved instead is the designed fallback; the badge and the original line say so.
        assertEquals(success, OutcomeLine.of(item(BatchItemStatus.SavedCopy, copySaved = true, terminal = done)))
        // The terminal result is the encode's and is settled before the replacement runs: on its own it
        // read "Outcome: Compressed", in the colour of a success, under "Original at risk".
        for (outcome in listOf(ReplacementOutcome.AT_RISK_ORIGINAL_PRESERVED, ReplacementOutcome.AT_RISK_RECOVERY_RETAINED)) {
            for (saved in listOf(true, false, null)) {
                val line = OutcomeLine.of(item(BatchItemStatus.SavedCopy, replacementOutcome = outcome, copySaved = saved, terminal = done))
                assertEquals(OriginalStatus.Line("Outcome: Compressed, but replacing the original failed", OriginalStatus.Tone.WARNING), line)
            }
        }
        for (saved in listOf(false, null)) {
            val line = OutcomeLine.of(item(BatchItemStatus.SavedCopy, copySaved = saved, terminal = done))
            assertEquals(OriginalStatus.Line("Outcome: Compressed, but the original was not replaced", OriginalStatus.Tone.WARNING), line)
        }
        // The encode's own words are kept, whatever they are.
        assertEquals(
            "Outcome: Compressed (lossy mode), but replacing the original failed",
            OutcomeLine.of(item(BatchItemStatus.SavedCopy, replacementOutcome = ReplacementOutcome.AT_RISK_RECOVERY_RETAINED,
                terminal = BatchTerminalResult.LOSSY_SMALLER))?.text
        )
        // A failed encode is its own outcome, as before, and a row without a result has no line.
        assertEquals(
            OriginalStatus.Line("Outcome: Verification failed — original kept", OriginalStatus.Tone.WARNING),
            OutcomeLine.of(item(BatchItemStatus.Failed, terminal = BatchTerminalResult.OUTPUT_VALIDATION_FAILED))
        )
        assertEquals(
            OriginalStatus.Line("Outcome: Original kept — no verified smaller output", OriginalStatus.Tone.DONE),
            OutcomeLine.of(item(BatchItemStatus.Done, terminal = BatchTerminalResult.ALREADY_HIGHLY_OPTIMIZED))
        )
        assertEquals(null, OutcomeLine.of(item()))
    }

    /** Every state a finished row can be in, for the rules that must hold in all of them. */
    private fun everyFinishedRow(): List<BatchVideoItem> {
        val rows = mutableListOf<BatchVideoItem>()
        val outcomes = listOf(null, ReplacementOutcome.NOT_REQUESTED, ReplacementOutcome.REFUSED_OUTPUT_MISSING,
            ReplacementOutcome.REFUSED_BACKUP_FAILED, ReplacementOutcome.AT_RISK_ORIGINAL_PRESERVED, ReplacementOutcome.AT_RISK_RECOVERY_RETAINED)
        for (status in BatchItemStatus.entries) for (terminal in listOf<BatchTerminalResult?>(null) + BatchTerminalResult.entries) {
            for (outcome in outcomes) for (saved in listOf(true, false, null)) {
                rows += item(status, replacementOutcome = outcome, copySaved = saved, terminal = terminal)
            }
        }
        return rows
    }

    @Test
    fun theOutcomeLineIsNeverDrawnAsASuccessOnARowWhoseBadgeIsAnAlarm() {
        for (row in everyFinishedRow()) {
            val line = OutcomeLine.of(row) ?: continue
            val what = "${row.status} ${row.terminalResult} ${row.replacementOutcome} copySaved=${row.copySaved}"
            if (StatusLabel.isAlarming(row)) assertEquals(what, OriginalStatus.Tone.WARNING, line.tone)
            else assertEquals(what, OriginalStatus.Tone.DONE, line.tone)
        }
    }

    @Test
    fun anOriginalRewrittenFromItsRollbackCopyIsCalledRestoredNotUntouched() {
        // The replacement opened the original, its write did not stand, and the rollback copy was
        // written back and read back equal. The bytes are the original's; the file was not left alone.
        for (outcome in listOf(ReplacementTransaction.RESTORED_WRITE_FAILED, ReplacementTransaction.RESTORED_READBACK_UNAVAILABLE,
            ReplacementTransaction.RESTORED_READBACK_MISMATCH)) {
            val saved = line(item(BatchItemStatus.SavedCopy, replacementOutcome = outcome, copySaved = true))
            assertEquals("Original restored intact after a failed replacement; copy saved to the gallery", saved.text)
            assertEquals(OriginalStatus.Tone.DONE, saved.tone)
            val notSaved = line(item(BatchItemStatus.SavedCopy, replacementOutcome = outcome, copySaved = false))
            assertEquals("Original restored intact after a failed replacement; saving a copy failed", notSaved.text)
            assertEquals(OriginalStatus.Tone.WARNING, notSaved.tone)
        }
        // A refused replacement never opened it.
        for (outcome in listOf(ReplacementOutcome.REFUSED_NOT_REPLACEMENT_SAFE, ReplacementOutcome.REFUSED_OVERWRITE_GUARD,
            ReplacementOutcome.REFUSED_BACKUP_FAILED, ReplacementOutcome.REFUSED_LOW_SPACE, ReplacementOutcome.REFUSED_RECOVERY_UNAVAILABLE,
            ReplacementTransaction.REFUSED_SOURCE_NOT_BOUND, ReplacementTransaction.REFUSED_SOURCE_CHANGED, ReplacementTransaction.REFUSED_STAGING_FAILED)) {
            assertEquals("Original untouched; copy saved to the gallery", line(item(BatchItemStatus.SavedCopy, replacementOutcome = outcome, copySaved = true)).text)
        }
    }

    @Test
    fun everyBatchEndingAndTheRowsResultBlockUseTheseWords() {
        // The words are tested here; this pins that the app shows them. A batch ends in three places
        // (finished, cancelled, stopped by an unhandled failure), and each must carry the warning.
        val root = listOf("src/main/java", "app/src/main/java").map { java.io.File(it) }.first { it.isDirectory }
        val viewModel = root.walkTopDown().single { it.name == "BatchCompressorViewModel.kt" }.readText()
        assertEquals(2, Regex("""errorMessage = BatchEndNotice\.warning\(""").findAll(viewModel).count())
        assertEquals(1, Regex("""errorMessage = BatchEndNotice\.stopped\(""").findAll(viewModel).count())
        assertEquals(1, Regex("""statusMessage = BatchEndNotice\.finished\(""").findAll(viewModel).count())
        assertFalse(viewModel.contains("by real compression.\""))
        val screen = root.walkTopDown().single { it.name == "BatchMainActivity.kt" }.readText()
        assertEquals(1, Regex("""OutcomeLine\.of\(""").findAll(screen).count())
        assertFalse(screen.contains("\"Outcome: "))
    }

    @Test
    fun aBatchThatLeftAnOriginalAtRiskSaysSoAboveTheList() {
        val done = BatchTerminalResult.TRANSCODED_SMALLER
        val fine = listOf(
            item(BatchItemStatus.Done, terminal = done), item(BatchItemStatus.Replaced, terminal = done),
            item(BatchItemStatus.SavedCopy, copySaved = true, terminal = done),
            item(BatchItemStatus.Done, terminal = BatchTerminalResult.ALREADY_HIGHLY_OPTIMIZED),
            item(BatchItemStatus.Cancelled, terminal = BatchTerminalResult.CANCELLED), item(BatchItemStatus.Skipped), item()
        )
        assertEquals(null, BatchEndNotice.warning(fine))
        // A failed replacement is not a failed item: its encode succeeded. Counting failures alone, a
        // batch that put an original at risk ended "Finished N outputs" with no warning above the list.
        val heldInApp = item(BatchItemStatus.SavedCopy, replacementOutcome = ReplacementOutcome.AT_RISK_RECOVERY_RETAINED, copySaved = false, terminal = done)
        val inGallery = item(BatchItemStatus.SavedCopy, replacementOutcome = ReplacementOutcome.AT_RISK_ORIGINAL_PRESERVED, copySaved = true, terminal = done)
        assertEquals(
            "1 original may be incomplete after a failed replacement: see the item marked \"Original at risk\" for where its intact copy is. " +
                "An intact copy is held inside the app, so do not clear the app's storage.",
            BatchEndNotice.warning(fine + heldInApp)
        )
        // With every intact copy in the gallery, nothing depends on the app's storage.
        assertEquals(
            "1 original may be incomplete after a failed replacement: see the item marked \"Original at risk\" for where its intact copy is.",
            BatchEndNotice.warning(fine + inGallery)
        )
        assertEquals(
            "2 originals may be incomplete after a failed replacement: see the items marked \"Original at risk\" for where their intact copies are. " +
                "An intact copy is held inside the app, so do not clear the app's storage.",
            BatchEndNotice.warning(listOf(inGallery) + fine + heldInApp)
        )
        assertEquals(
            "2 originals may be incomplete after a failed replacement: see the items marked \"Original at risk\" for where their intact copies are. " +
                "2 intact copies are held inside the app, so do not clear the app's storage.",
            BatchEndNotice.warning(listOf(heldInApp, heldInApp))
        )
        // The badge's own words, so the notice and the row cannot drift apart.
        assertEquals("Original at risk", StatusLabel.of(heldInApp))
        assertEquals(StatusLabel.ORIGINAL_AT_RISK, StatusLabel.of(heldInApp))
    }

    @Test
    fun aBatchEndWarningCountsEveryRowWhoseBadgeIsAnAlarm() {
        val done = BatchTerminalResult.TRANSCODED_SMALLER
        val failed = item(BatchItemStatus.Failed, terminal = BatchTerminalResult.OUTPUT_VALIDATION_FAILED)
        // Failures alone read as they did.
        assertEquals("1 item failed. Tap each item for details.", BatchEndNotice.warning(listOf(failed)))
        assertEquals("2 items failed. Tap each item for details.", BatchEndNotice.warning(listOf(failed, item(BatchItemStatus.Failed, terminal = BatchTerminalResult.ENCODER_FAILURE))))
        // A replacement asked for and not made, with no copy saved in its place.
        val notReplaced = item(BatchItemStatus.SavedCopy, replacementOutcome = ReplacementOutcome.REFUSED_OUTPUT_MISSING, terminal = done)
        assertEquals(
            "1 original was not replaced and its output was not saved as a copy either: see the item marked \"Not replaced\".",
            BatchEndNotice.warning(listOf(notReplaced))
        )
        assertEquals(
            "2 originals were not replaced and their outputs were not saved as copies either: see the items marked \"Not replaced\".",
            BatchEndNotice.warning(listOf(notReplaced, item(BatchItemStatus.SavedCopy, copySaved = false, terminal = done)))
        )
        assertEquals(StatusLabel.NOT_REPLACED, StatusLabel.of(notReplaced))
        // All three, the original at risk first.
        val atRisk = item(BatchItemStatus.SavedCopy, replacementOutcome = ReplacementOutcome.AT_RISK_ORIGINAL_PRESERVED, copySaved = true, terminal = done)
        assertEquals(
            "1 original may be incomplete after a failed replacement: see the item marked \"Original at risk\" for where its intact copy is. " +
                "1 item failed. Tap each item for details. " +
                "1 original was not replaced and its output was not saved as a copy either: see the item marked \"Not replaced\".",
            BatchEndNotice.warning(listOf(failed, notReplaced, atRisk))
        )
        // No warning exactly when no badge is an alarm, and each alarming row is counted once.
        for (row in everyFinishedRow()) {
            val what = "${row.status} ${row.terminalResult} ${row.replacementOutcome} copySaved=${row.copySaved}"
            val warning = BatchEndNotice.warning(listOf(row))
            assertEquals(what, StatusLabel.isAlarming(row), warning != null)
            if (warning != null) assertEquals(what, 1, Regex("""(^|\. )1 (original|item) """).findAll(warning).count())
        }
    }

    @Test
    fun aBatchStoppedByAnUnhandledFailureStillWarnsAboutTheRowsItFinished() {
        val done = BatchTerminalResult.TRANSCODED_SMALLER
        val fine = listOf(item(BatchItemStatus.Done, terminal = done), item())
        assertEquals("Batch stopped: disk full", BatchEndNotice.stopped(fine, existing = null, cause = "disk full"))
        assertEquals("already shown", BatchEndNotice.stopped(fine, existing = "already shown", cause = "disk full"))
        val atRisk = item(BatchItemStatus.SavedCopy, replacementOutcome = ReplacementOutcome.AT_RISK_RECOVERY_RETAINED, copySaved = false, terminal = done)
        val warning = BatchEndNotice.warning(fine + atRisk)!!
        assertEquals("$warning Batch stopped: disk full", BatchEndNotice.stopped(fine + atRisk, existing = null, cause = "disk full"))
        assertEquals("$warning already shown", BatchEndNotice.stopped(fine + atRisk, existing = "already shown", cause = "disk full"))
        // A message that already leads with the warning is not given it twice.
        assertEquals(warning, BatchEndNotice.stopped(fine + atRisk, existing = warning, cause = "disk full"))
    }

    @Test
    fun theFinishLineCallsAReductionAReductionNotSavedSpace() {
        // The v1.6.311 batch: 58 rows, 13 smaller outputs, every one a copy beside its original.
        val text = BatchEndNotice.finished(doneCount = 58, realCompressions = 13, reductionBytes = 673_227_786L)
        assertEquals("Finished 58 outputs. Real compressions: 13. Accepted size reduction (real compression): 642.0 MiB.", text)
        assertFalse(text, text.contains("Saved", ignoreCase = true))
        assertTrue(text, text.contains(AcceptedReduction.LABEL))
        assertEquals("Finished 1 output. Real compressions: 0. Accepted size reduction (real compression): 0 MiB.", BatchEndNotice.finished(1, 0, 0L))
    }

    @Test
    fun eachRetryCauseIsNamedAndATestsRefusalIsNotCalledAQualityFailure() {
        fun reason(cause: RetryCause) = AttemptContext.saferRungRetry(2, 2, 0.77, 0.95, cause).reason
        assertTrue(reason(RetryCause.MEASURED_BELOW_BAR).startsWith("the first candidate (0.77) did not pass the quality check"))
        assertTrue(reason(RetryCause.NOT_CERTIFIED).startsWith("the first candidate (0.77) could not be certified"))
        assertTrue(reason(RetryCause.VERIFICATION_FAILED).startsWith("the first candidate (0.77) failed output verification"))
        val veto = reason(RetryCause.TEST_VETO)
        assertTrue(veto, veto.contains("instrumented test") && veto.contains("not a quality measurement"))
        for (cause in RetryCause.entries) assertTrue(reason(cause).endsWith("encoding again at the safer measured ratio 0.95, which is larger"))
        assertEquals("Attempt 2 of 2", AttemptContext.saferRungRetry(2, 2, 0.77, 0.95, RetryCause.MEASURED_BELOW_BAR).label)
    }

    private fun recommendation(quality: BatchQualityPreset, codec: BatchCodecOption, fps: BatchFrameRateOption) = CompressionRecommendation(
        title = "H.264 compatibility mode", expectedSavings = "10-25%", qualityRisk = "Low", reason = "r",
        qualityPreset = quality.label, codecOption = codec.label, frameRateOption = fps.label
    )

    @Test
    fun aRecommendationSaysItChangesTheBatchAndWhenItLeavesPerceptuallyLossless() {
        val pl = BatchQualityPreset.ORIGINAL.label
        val auto = BatchCodecOption.AUTO.label
        val original = BatchFrameRateOption.ORIGINAL.label
        // The recording's suggestion, made while the batch was Perceptually Lossless.
        val h264 = RecommendationPreview.of(recommendation(BatchQualityPreset.HIGH, BatchCodecOption.H264, BatchFrameRateOption.ORIGINAL), pl, auto, original)
        assertTrue(h264.leavesPerceptuallyLossless)
        assertFalse(h264.capsFrameRate)
        assertEquals(listOf(
            "Quality: Perceptually Lossless → ${BatchQualityPreset.HIGH.label} (lossy)",
            "Codec: $auto → ${BatchCodecOption.H264.label}"
        ), h264.changes)
        // A 30 fps cap drops frames, and says so.
        val capped = RecommendationPreview.of(recommendation(BatchQualityPreset.HIGH, BatchCodecOption.HEVC, BatchFrameRateOption.FPS30), pl, auto, original)
        assertTrue(capped.capsFrameRate)
        assertTrue(capped.changes.last(), capped.changes.last().endsWith("(frames are dropped)"))
        // A recommendation that keeps PL is not flagged; one the batch already uses changes nothing.
        val keepsPl = RecommendationPreview.of(recommendation(BatchQualityPreset.ORIGINAL, BatchCodecOption.HEVC, BatchFrameRateOption.ORIGINAL), pl, auto, original)
        assertFalse(keepsPl.leavesPerceptuallyLossless)
        assertEquals(listOf("Codec: $auto → ${BatchCodecOption.HEVC.label}"), keepsPl.changes)
        val same = RecommendationPreview.of(recommendation(BatchQualityPreset.ORIGINAL, BatchCodecOption.AUTO, BatchFrameRateOption.ORIGINAL), pl, auto, original)
        assertFalse(same.changesAnything)
        // From a lossy batch, another lossy preset is not "leaving" Perceptually Lossless.
        val fromHigh = RecommendationPreview.of(recommendation(BatchQualityPreset.MEDIUM, BatchCodecOption.HEVC, BatchFrameRateOption.ORIGINAL),
            BatchQualityPreset.HIGH.label, auto, original)
        assertFalse(fromHigh.leavesPerceptuallyLossless)
    }

    @Test
    fun aRecommendationsFiguresAreLabelledAsAnUnmeasuredEstimate() {
        val text = RecommendationPreview.estimateLine(recommendation(BatchQualityPreset.HIGH, BatchCodecOption.H264, BatchFrameRateOption.ORIGINAL))
        assertEquals("Rule-of-thumb estimate for this kind of clip, not measured: about 10-25% smaller, low quality risk", text)
    }

    @Test
    fun theSizeReductionRateDoesNotSayStorageWasSaved() {
        val metrics = BatchItemMetrics(operationLabel = "Encode", elapsedMs = 60_000L, outputBytes = 90L * 1024 * 1024,
            savedBytes = 10L * 1024 * 1024, thermalStart = "a", thermalEnd = "b", batteryStart = 50, batteryEnd = 49, cooldownMs = 0L)
        assertFalse(metrics.summary, metrics.summary.contains("saved"))
        assertTrue(metrics.summary, metrics.summary.contains("10.0 MB smaller/min"))
    }
}
