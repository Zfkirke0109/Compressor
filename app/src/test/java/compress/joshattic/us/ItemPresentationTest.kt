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
