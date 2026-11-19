package compress.joshattic.us

import org.junit.Assert.*
import org.junit.Test

class HighQualityRetryStateTest {
    private fun row(name: String) = BatchVideoItem(
        sourceUri = TestUris.named(name), originalName = name, originalSize = 52_500_000,
        originalWidth = 1080, originalHeight = 1920, originalBitrate = 7_000_000,
        originalAudioBitrate = 0, originalFps = 60f, durationMs = 60_000
    )

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
