package compress.joshattic.us

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class HighQualityRetryStateTest {
    private fun row(name: String) = BatchVideoItem(
        sourceUri = TestUris.named(name), originalName = name, originalSize = 52_500_000,
        originalWidth = 1080, originalHeight = 1920, originalBitrate = 7_000_000,
        originalAudioBitrate = 0, originalFps = 60f, durationMs = 60_000
    )

    @Test
    fun aShareCannotReplaceTheRowsWhileTheyAreBeingLoadedEncodedOrPublished() {
        val idle = BatchCompressorUiState(deviceProfile = "test")
        assertFalse(idle.shouldDeferSelection(fromShare = true, batchStarting = false))
        for (state in listOf(idle.copy(isLoading = true), idle.copy(isCompressing = true), idle.copy(isSavingCopies = true))) {
            assertTrue(state.shouldDeferSelection(fromShare = true, batchStarting = false))
            assertTrue(state.shouldDeferSelection(fromShare = false, batchStarting = false))
            assertNull(state.highQualityRetrySelection())
        }
        assertTrue(idle.shouldDeferSelection(fromShare = true, batchStarting = true))
        val ready = row("winner").copy(outputPath = "/cache/verified.mp4", outputSize = 3000)
        val finished = idle.copy(items = listOf(ready))
        assertTrue(finished.shouldDeferSelection(fromShare = true, batchStarting = false))
        assertFalse(finished.shouldDeferSelection(fromShare = false, batchStarting = false))
        assertFalse(finished.copy(items = listOf(ready.copy(galleryCopySaved = true)))
            .shouldDeferSelection(fromShare = true, batchStarting = false))
    }

    @Test
    fun normalLauncherAndShareIntentsReturnToTheSingleBatchActivity() {
        val xml = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(Media3Host.inModule("src/main/AndroidManifest.xml"))
        val ns = "http://schemas.android.com/apk/res/android"
        val all = xml.getElementsByTagName("activity")
        val batch = (0 until all.length).map { all.item(it) as Element }
            .single { it.getAttributeNS(ns, "name") == ".BatchMainActivity" }
        assertEquals("singleTask", batch.getAttributeNS(ns, "launchMode"))
        val actions = batch.getElementsByTagName("action")
        assertEquals(setOf("android.intent.action.MAIN", "android.intent.action.SEND", "android.intent.action.SEND_MULTIPLE"),
            (0 until actions.length).map { (actions.item(it) as Element).getAttributeNS(ns, "name") }.toSet())
    }

    @Test
    fun aGalleryReceiptBelongsToOneOutputAttemptForThisSource() {
        val old = row("same").copy(attempt = AttemptToken("pl", 0, 1), outputPath = "/cache/shared.mp4",
            outputMode = "Perceptually Lossless", outputSize = 9000)
        assertTrue(old.withGalleryReceipt(old).galleryCopySaved)
        val later = old.copy(attempt = AttemptToken("hq", 0, 2))
        assertFalse(later.withGalleryReceipt(old).galleryCopySaved)
        assertFalse(old.copy(outputPath = "/cache/hq.mp4").withGalleryReceipt(old).galleryCopySaved)
        assertFalse(old.copy(outputSize = 9010).withGalleryReceipt(old).galleryCopySaved)
    }

    @Test
    fun aFailedOrUnhelpfulRetryRetainsThePriorVerifiedCopyAndItsPublicationReceipt() {
        val remux = row("sanitized").copy(status = BatchItemStatus.Done, outputPath = "/cache/privacy-remux.mp4",
            outputSize = 50_000_000, galleryCopySaved = true, terminalResult = BatchTerminalResult.REMUX_PREFERRED_BY_EVIDENCE)
        val pl = BatchCompressorUiState(items = listOf(remux), highQualityRetryCandidates = listOf(remux.sourceUri),
            deviceProfile = "test")
        val retry = requireNotNull(pl.highQualityRetrySelection())
        for (terminal in listOf(BatchTerminalResult.CANCELLED, BatchTerminalResult.ENCODER_FAILURE,
            BatchTerminalResult.TRANSCODED_NOT_MEANINGFULLY_SMALLER)) {
            val result = retry.items.single().copy(terminalResult = terminal,
                outputPath = if (terminal == BatchTerminalResult.TRANSCODED_NOT_MEANINGFULLY_SMALLER) "/cache/hq.mp4" else null)
            val restored = pl.withHighQualityRetryResults(retry.copy(items = listOf(result)))
            val retained = restored.items.single()
            assertEquals(remux.outputPath, retained.outputPath)
            assertEquals(remux.terminalResult, retained.terminalResult)
            assertTrue(retained.galleryCopySaved)
            assertTrue(retained.message.orEmpty().contains(terminal.label))
        }
    }

    @Test
    fun aSourceFpsCopyRetryRestoresTheOtherOutputsAndTheUsersSettingsOnEveryExit() {
        val winner = row("certified").copy(status = BatchItemStatus.Done, outputPath = "/cache/verified.mp4",
            outputSize = 42_000_000, terminalResult = BatchTerminalResult.TRANSCODED_SMALLER)
        val kept = row("kept").copy(status = BatchItemStatus.Done, galleryCopySaved = true,
            terminalResult = BatchTerminalResult.ALREADY_HIGHLY_OPTIMIZED)
        val pl = BatchCompressorUiState(
            items = listOf(winner, kept), highQualityRetryCandidates = listOf(kept.sourceUri),
            qualityPreset = BatchQualityPreset.ORIGINAL.label, frameRateOption = BatchFrameRateOption.FPS30.label,
            replaceOriginals = true, selectedPreset = "my preset", deviceProfile = "test"
        )
        val retry = requireNotNull(pl.highQualityRetrySelection())
        assertEquals(listOf(kept.sourceUri), retry.items.map { it.sourceUri })
        assertEquals(BatchQualityPreset.HIGH.label, retry.qualityPreset)
        assertEquals(BatchFrameRateOption.ORIGINAL.label, retry.frameRateOption)
        assertFalse(retry.replaceOriginals)
        assertFalse(retry.items.single().galleryCopySaved)
        assertTrue(retry.highQualityRetryCandidates.isEmpty())
        for (terminal in listOf(BatchTerminalResult.LOSSY_SMALLER, BatchTerminalResult.ENCODER_FAILURE, BatchTerminalResult.CANCELLED)) {
            val result = retry.items.single().copy(terminalResult = terminal)
            val restored = pl.withHighQualityRetryResults(retry.copy(items = listOf(result)))
            assertEquals(listOf(winner, result), restored.items)
            assertEquals(pl.qualityPreset, restored.qualityPreset)
            assertEquals(pl.frameRateOption, restored.frameRateOption)
            assertEquals(pl.selectedPreset, restored.selectedPreset)
            assertTrue(restored.replaceOriginals)
            assertNull(restored.highQualityRetrySelection())
        }
    }
}
