package compress.joshattic.us

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import compress.joshattic.us.ui.theme.CompressorTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The batch row as it renders, in the states the v1.6.311 recording showed going wrong: a phase
 * under another phase's detail, a retry with no explanation, and a per-item button that changed
 * the whole batch. Rendered at the recording's narrow split-screen width and at 200 % text.
 * Needs no media and touches no file; runs with the default device checks.
 */
@RunWith(AndroidJUnit4::class)
class BatchItemCardDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private fun item(status: BatchItemStatus = BatchItemStatus.Pending) = BatchVideoItem(
        sourceUri = android.net.Uri.parse("content://test/item/1"),
        originalName = "A_very_long_recorded_file_name_that_does_not_fit_on_one_narrow_line_20261112.mp4",
        originalSize = 627_000_000L, originalWidth = 1920, originalHeight = 1080, originalBitrate = 8_000_000,
        originalAudioBitrate = 128_000, originalFps = 30f, durationMs = 600_000L, status = status
    )

    private val pl = BatchSettingsView(BatchQualityPreset.ORIGINAL.label, BatchCodecOption.AUTO.label, BatchFrameRateOption.ORIGINAL.label)

    private data class Shown(
        val item: BatchVideoItem, val batchRunning: Boolean, val replaceOriginals: Boolean,
        val fontScale: Float, val widthDp: Int, val onApply: () -> Unit
    )

    // A rule's content can be set once per test; each show() after the first changes this state.
    private var shown by mutableStateOf<Shown?>(null)
    private var contentSet = false

    private fun show(
        item: BatchVideoItem,
        batchRunning: Boolean = false,
        replaceOriginals: Boolean = false,
        fontScale: Float = 1f,
        widthDp: Int = 300,
        onApply: () -> Unit = {}
    ) {
        compose.runOnUiThread { shown = Shown(item, batchRunning, replaceOriginals, fontScale, widthDp, onApply) }
        if (!contentSet) {
            contentSet = true
            compose.setContent {
                shown?.let { s ->
                    Narrow(s.fontScale, s.widthDp) {
                        BatchItemCard(1, s.item, s.batchRunning, s.replaceOriginals, pl, queuedCount = 7, onApplyRecommendation = s.onApply)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun Narrow(fontScale: Float, widthDp: Int, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            CompressorTheme {
                Box(Modifier.width(widthDp.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { content() }
                }
            }
        }
    }

    @Test
    fun verifyingNeverShowsTheFinalizingDetailItReplaced() {
        val token = AttemptToken("batch_test", 0, 1)
        var row = ItemProgressModel.begin(item(), token, 500_000_000L)
        row = ItemProgressModel.finishEncode(ItemProgressModel.enter(row, token, ItemPhase.ENCODING), token)
        row = ItemProgressModel.detail(row, token, "Finalizing: writing the source's metadata into the output…")
        row = ItemProgressModel.enter(row, token, ItemPhase.VERIFYING)
        show(row)
        compose.onNodeWithTag(ItemCardTags.PHASE).performScrollTo().assertIsDisplayed().assertTextEquals("Verifying output")
        compose.onNodeWithText("Finalizing: writing the source's metadata into the output…").assertDoesNotExist()
        compose.onNodeWithTag(ItemCardTags.DETAIL).assertDoesNotExist()
    }

    @Test
    fun theRetryExplanationIsShownAtNarrowWidthAndLargeText() {
        val first = AttemptToken("batch_test", 0, 20)
        val retry = AttemptToken("batch_test", 0, 21)
        var row = ItemProgressModel.begin(item(), first, 500_000_000L)
        row = ItemProgressModel.handOver(row, first, retry, AttemptContext.saferRungRetry(2, 2, 0.77, 0.95, RetryCause.MEASURED_BELOW_BAR))
        row = ItemProgressModel.enter(row, retry, ItemPhase.ENCODING)
        row = ItemProgressModel.encodeProgress(row, retry, 0.03f, 20_500_000L)
        for (scale in listOf(1f, 1.3f, 2f)) {
            show(row, fontScale = scale)
            compose.onNodeWithTag(ItemCardTags.ATTEMPT).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Attempt 2 of 2", substring = true).assertExists()
            compose.onNodeWithText("did not pass the quality check", substring = true).assertExists()
            // One size line: no second "Writing … (temporary)" text from the poller.
            compose.onNodeWithTag(ItemCardTags.SIZE).assertExists()
            compose.onNodeWithTag(ItemCardTags.DETAIL).assertDoesNotExist()
            compose.onNodeWithTag(ItemCardTags.STATUS).assert(
                SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Status: Encoding 3%"))
            )
        }
    }

    @Test
    fun theOriginalAndTheCopyAreNamedForWhatTheyAre() {
        val ready = item(BatchItemStatus.Done).copy(outputSize = 400_000_000L, outputPath = "/cache/out.mp4",
            terminalResult = BatchTerminalResult.TRANSCODED_SMALLER)
        show(ready)
        compose.onNodeWithText("Original untouched; copy ready in the app, not saved yet").performScrollTo().assertIsDisplayed()
        show(ready.copy(galleryCopySaved = true))
        compose.onNodeWithText("Original untouched; copy saved to the gallery").assertExists()
        show(item(BatchItemStatus.SavedCopy).copy(replacementOutcome = ReplacementOutcome.AT_RISK_RECOVERY_RETAINED,
            message = "In-place replacement failed and the original on disk may be incomplete…"))
        compose.onNodeWithTag(ItemCardTags.ORIGINAL_STATUS).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Original may be incomplete", substring = true).assertExists()
        compose.onNodeWithText("In-place replacement failed", substring = true).assertExists()
    }

    private fun lossyRecommendation() = CompressionRecommendation(
        title = "H.264 compatibility mode", expectedSavings = "10-25%", qualityRisk = "Low",
        reason = "This clip is already easy to share, so H.264 keeps compatibility high.",
        qualityPreset = BatchQualityPreset.HIGH.label, codecOption = BatchCodecOption.H264.label,
        frameRateOption = BatchFrameRateOption.ORIGINAL.label
    )

    @Test
    fun aRecommendationNamesItsBatchWideChangesAndAsksFirst() {
        var applied = 0
        show(item().copy(recommendation = lossyRecommendation()), onApply = { applied++ })
        compose.onNodeWithText("Suggestion: H.264 compatibility mode (would leave Perceptually Lossless)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ItemCardTags.APPLY_TO_BATCH).assertDoesNotExist()
        compose.onNodeWithTag(ItemCardTags.SUGGESTION_TOGGLE).performScrollTo().performClick()
        compose.onNodeWithText("not measured", substring = true).assertExists()
        compose.onNodeWithText("Applies to the whole batch, not only this file:").assertExists()
        compose.onNodeWithText("• Quality: Perceptually Lossless → High Quality (lossy)").assertExists()
        compose.onNodeWithTag(ItemCardTags.APPLY_TO_BATCH).performScrollTo().performClick()
        compose.onNodeWithText("Change the whole batch?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals("dismissing changes nothing", 0, applied)
        compose.onNodeWithTag(ItemCardTags.APPLY_TO_BATCH).performScrollTo().performClick()
        compose.onNodeWithText("Apply to batch").performClick()
        assertEquals(1, applied)
    }

    @Test
    fun noRecommendationIsOfferedWhileABatchRuns() {
        show(item().copy(recommendation = lossyRecommendation()), batchRunning = true)
        compose.onNodeWithText("Suggestion:", substring = true).assertDoesNotExist()
        compose.onNodeWithTag(ItemCardTags.APPLY_TO_BATCH).assertDoesNotExist()
    }
}
