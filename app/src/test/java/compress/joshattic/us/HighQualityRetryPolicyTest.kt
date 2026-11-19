package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighQualityRetryPolicyTest {

    @Test
    fun onlyGenuineKeepOriginalTerminalsAreRetryCandidates() {
        // The four honest "kept the original — HQ could shrink it" outcomes.
        assertTrue(HighQualityRetryPolicy.terminalIsRetryCandidate(BatchTerminalResult.TRANSCODED_NOT_MEANINGFULLY_SMALLER))
        assertTrue(HighQualityRetryPolicy.terminalIsRetryCandidate(BatchTerminalResult.ALREADY_HIGHLY_OPTIMIZED))
        assertTrue(HighQualityRetryPolicy.terminalIsRetryCandidate(BatchTerminalResult.REMUX_PREFERRED_BY_EVIDENCE))
        assertTrue(HighQualityRetryPolicy.terminalIsRetryCandidate(BatchTerminalResult.UNEXPECTED_REMUX))

        // Already shrank — nothing to offer.
        assertFalse(HighQualityRetryPolicy.terminalIsRetryCandidate(BatchTerminalResult.TRANSCODED_SMALLER))
        assertFalse(HighQualityRetryPolicy.terminalIsRetryCandidate(BatchTerminalResult.LOSSY_SMALLER))

        // Failures / cancels / our own outputs / explicit user-chosen remux are never auto-offered.
        assertFalse(HighQualityRetryPolicy.terminalIsRetryCandidate(BatchTerminalResult.OUTPUT_VALIDATION_FAILED))
        assertFalse(HighQualityRetryPolicy.terminalIsRetryCandidate(BatchTerminalResult.ENCODER_FAILURE))
        assertFalse(HighQualityRetryPolicy.terminalIsRetryCandidate(BatchTerminalResult.UNSUPPORTED_CONTAINER))
        assertFalse(HighQualityRetryPolicy.terminalIsRetryCandidate(BatchTerminalResult.FAILED))
        assertFalse(HighQualityRetryPolicy.terminalIsRetryCandidate(BatchTerminalResult.CANCELLED))
        assertFalse(HighQualityRetryPolicy.terminalIsRetryCandidate(BatchTerminalResult.SKIPPED_ALREADY_COMPRESSED))
        assertFalse(HighQualityRetryPolicy.terminalIsRetryCandidate(BatchTerminalResult.EXPLICIT_REMUX))
        assertFalse(HighQualityRetryPolicy.terminalIsRetryCandidate(null))

        // Deliberately excluded: the app measured visible quality loss here, so we do not
        // auto-suggest a lossy re-encode on exactly those clips.
        assertFalse(HighQualityRetryPolicy.terminalIsRetryCandidate(BatchTerminalResult.SKIPPED_WOULD_DEGRADE))
    }

    @Test
    fun highQualityTargetMirrorsTheEncoderAndNeverThrowsOnLowBitrateSources() {
        // Healthy 1080p 10 Mbps: 0.72x = 7.2 Mbps, well above the 5 Mbps floor.
        assertEquals(7_200_000, HighQualityRetryPolicy.highQualityTargetVideoBitrate(10_000_000, 1080))
        // 4K 15 Mbps: 0.72x = 10.8 Mbps, lifted to the 12 Mbps 4K floor (still below source).
        assertEquals(12_000_000, HighQualityRetryPolicy.highQualityTargetVideoBitrate(15_000_000, 2160))
        // Low 1080p 3 Mbps: the shared encoder floor clamps back to source.
        assertEquals(3_000_000, HighQualityRetryPolicy.highQualityTargetVideoBitrate(3_000_000, 1080))
        // Unknown source bitrate -> 0.
        assertEquals(0, HighQualityRetryPolicy.highQualityTargetVideoBitrate(0, 1080))
    }

    private val bigFile = 500L * 1024 * 1024
    private val smallFile = 20L * 1024 * 1024

    private val portrait = VideoSourceInfo(
        width = 1080, height = 1920, frameRate = 60f, durationMs = 60_000L,
        totalBitrate = 7_000_000, audioPresent = false
    )

    private fun offered(source: VideoSourceInfo, bytes: Long): Boolean = HighQualityRetryPolicy.isEligible(
        true, BatchTerminalResult.ALREADY_HIGHLY_OPTIMIZED, source, bytes
    )

    @Test
    fun theOfferUsesTheActualOrientationIndependentTargetAndTheWholeFile() {
        val size = 52_500_000L
        assertEquals(0.28, HighQualityRetryPolicy.predictedSavingFraction(portrait, size), 1e-9)
        assertTrue(offered(portrait, size))
        assertTrue(offered(portrait.copy(width = 1920, height = 1080), size))

        // A 6 Mbps video at the 5 Mbps floor saves 16.7% of video but only 14.77% of this
        // clip with its copied 768 kbps audio. The saving is too small for the large-file path.
        val surround = portrait.copy(totalBitrate = 6_768_000, audioPresent = true,
            audioMime = "audio/mp4a-latm", audioBitrate = 768_000)
        assertEquals(1_000_000.0 / 6_768_000, HighQualityRetryPolicy.predictedSavingFraction(surround, 50_760_000), 1e-9)
        assertFalse(offered(surround, 50_760_000))
    }

    @Test
    fun theOfferIncludesTheAudioEncoderRequestEvenWhenVideoIsAtItsFloor() {
        val pcm = portrait.copy(totalBitrate = 4_536_000, audioPresent = true,
            audioMime = "audio/raw", audioBitrate = 1_536_000)
        val bytes = 34_020_000L
        assertEquals(9_120_000.0 / bytes, HighQualityRetryPolicy.predictedSavingFraction(pcm, bytes), 1e-9)
        assertTrue(offered(pcm, bytes))
        assertFalse(offered(pcm.copy(audioMime = "audio/mp4a-latm"), bytes))
    }

    @Test
    fun inconsistentTagsUnexplainedContainerBytesAndUnknownsCannotCreateOffers() {
        val source = portrait.copy(totalBitrate = 10_000_000)
        // The planned video alone exceeds the file's observed 5 Mbps average.
        assertFalse(offered(source, 37_500_000))
        // Only the 2.8 Mbps video cut is credited. The other 75 MB in this file is unexplained.
        assertEquals(0.14, HighQualityRetryPolicy.predictedSavingFraction(source, 150_000_000), 1e-9)
        assertFalse(offered(source, 150_000_000))
        assertFalse(offered(source, 0L))
        assertFalse(offered(source.copy(width = 0), 75_000_000))
        assertFalse(offered(source.copy(durationMs = 0L), 75_000_000))
        assertFalse(offered(source.copy(audioPresent = true, audioMime = "audio/mp4a-latm"), 75_000_000))
        assertFalse(HighQualityRetryPolicy.highQualityWouldShrink(10_000_000, 1080, 0L))
    }

    @Test
    fun highQualityWouldShrinkOnlyWhenTheFloorLeavesRealHeadroom() {
        // Healthy sources clear the 15% margin easily (0.72 ratio).
        assertTrue(HighQualityRetryPolicy.highQualityWouldShrink(10_000_000, 1080, smallFile))
        // High-bitrate-but-low-bpp 4K: floor-lifted 12M vs 15M source = 20% off -> worth it.
        assertTrue(HighQualityRetryPolicy.highQualityWouldShrink(15_000_000, 2160, smallFile))
        // Floor eats the ENTIRE saving (target == source): never offered, at any file size.
        assertFalse(HighQualityRetryPolicy.highQualityWouldShrink(3_000_000, 1080, smallFile))
        assertFalse(HighQualityRetryPolicy.highQualityWouldShrink(3_000_000, 1080, 50L * 1024 * 1024 * 1024))
        // Unknown bitrate fails closed.
        assertFalse(HighQualityRetryPolicy.highQualityWouldShrink(0, 1080, bigFile))
    }

    @Test
    fun largeFilesQualifyOnAbsoluteSavingBelowTheRelativeBar() {
        // The real 2026-07-31 S23 case: 471.9 MB 4K clip, 13.49 Mbps video, floor-clamped target
        // 12 Mbps = 11.0% predicted cut -> under the 15% bar, but ~51.9 MB of real saving.
        val fourKBitrate = 13_490_187
        val fourKSize = 471_900_000L
        assertEquals(0.11, HighQualityRetryPolicy.predictedSavingFraction(fourKBitrate, 2160), 0.005)
        // Under the old relative-only rule this was silently dropped; now it qualifies.
        assertTrue(HighQualityRetryPolicy.highQualityWouldShrink(fourKBitrate, 2160, fourKSize))
        // ...but the SAME bitrate on a small file does not clear the absolute bar.
        assertFalse(HighQualityRetryPolicy.highQualityWouldShrink(fourKBitrate, 2160, 30L * 1024 * 1024))
        // Unknown size cannot justify the absolute path (fails closed).
        assertFalse(HighQualityRetryPolicy.highQualityWouldShrink(fourKBitrate, 2160, 0L))
    }

    @Test
    fun aTrivialRelativeCutIsNeverOfferedNoMatterHowHugeTheFile() {
        // 4K at 12.5 Mbps -> target 12 Mbps = 4% cut. Even on a 100 GB file (4 GB of predicted
        // saving) this stays unoffered: it is not worth real quality loss and a long encode.
        val fraction = HighQualityRetryPolicy.predictedSavingFraction(12_500_000, 2160)
        assertTrue(fraction > 0.0 && fraction < HighQualityRetryPolicy.MIN_HEADROOM_FRACTION_FOR_LARGE_FILES)
        assertFalse(HighQualityRetryPolicy.highQualityWouldShrink(12_500_000, 2160, 100L * 1024 * 1024 * 1024))
    }

    @Test
    fun predictedSavingFractionIsZeroExactlyWhenTheFloorEatsEverything() {
        // Below-floor sources: target == source -> zero predicted saving. These are the clips that
        // really produced BIGGER output on device, so they must never be offered.
        assertEquals(0.0, HighQualityRetryPolicy.predictedSavingFraction(3_000_000, 1080), 1e-9)
        assertEquals(0.0, HighQualityRetryPolicy.predictedSavingFraction(802_555, 720), 1e-9)
        assertEquals(0.0, HighQualityRetryPolicy.predictedSavingFraction(0, 1080), 1e-9)
        // Healthy source keeps the full 0.72 ratio -> 28% predicted.
        assertEquals(0.28, HighQualityRetryPolicy.predictedSavingFraction(10_000_000, 1080), 0.005)
    }

    @Test
    fun isEligibleRequiresPerceptualLosslessBatchCandidateTerminalAndRealShrink() {
        // The happy path: PL batch, kept-original terminal, and HQ genuinely shrinks it.
        assertTrue(
            HighQualityRetryPolicy.isEligible(
                batchWasPerceptualLossless = true,
                terminal = BatchTerminalResult.ALREADY_HIGHLY_OPTIMIZED,
                sourceVideoBitrate = 15_000_000,
                sourceHeight = 2160,
                sourceSizeBytes = 100L * 1024 * 1024
            )
        )
        val size = 100L * 1024 * 1024
        // Not a PL batch (e.g. already ran in High Quality): never offer.
        assertFalse(
            HighQualityRetryPolicy.isEligible(false, BatchTerminalResult.ALREADY_HIGHLY_OPTIMIZED, 15_000_000, 2160, size)
        )
        // A real compression already happened: nothing to offer.
        assertFalse(
            HighQualityRetryPolicy.isEligible(true, BatchTerminalResult.TRANSCODED_SMALLER, 15_000_000, 2160, size)
        )
        // Visible-loss skips are never auto-offered even when HQ could shrink them.
        assertFalse(
            HighQualityRetryPolicy.isEligible(true, BatchTerminalResult.SKIPPED_WOULD_DEGRADE, 15_000_000, 2160, size)
        )
        // Candidate terminal but HQ cannot honestly shrink it (floor exceeds source).
        assertFalse(
            HighQualityRetryPolicy.isEligible(true, BatchTerminalResult.REMUX_PREFERRED_BY_EVIDENCE, 3_000_000, 1080, size)
        )
    }
}
