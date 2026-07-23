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
        // Low 1080p 3 Mbps: floor (5M) exceeds source. The production coerceIn(floor, source)
        // would THROW here; the guarded helper returns the source bitrate (i.e. no shrink).
        assertEquals(3_000_000, HighQualityRetryPolicy.highQualityTargetVideoBitrate(3_000_000, 1080))
        // Unknown source bitrate -> 0.
        assertEquals(0, HighQualityRetryPolicy.highQualityTargetVideoBitrate(0, 1080))
    }

    @Test
    fun highQualityWouldShrinkOnlyWhenTheFloorLeavesRealHeadroom() {
        // Healthy sources clear the 15% margin easily (0.72 ratio).
        assertTrue(HighQualityRetryPolicy.highQualityWouldShrink(10_000_000, 1080))
        // High-bitrate-but-low-bpp 4K: floor-lifted 12M vs 15M source = 20% off -> worth it.
        assertTrue(HighQualityRetryPolicy.highQualityWouldShrink(15_000_000, 2160))
        // Floor eats almost all the saving: 4K at 13 Mbps -> target 12M, only ~8% off -> not worth.
        assertFalse(HighQualityRetryPolicy.highQualityWouldShrink(13_000_000, 2160))
        // Low 1080p where the floor exceeds the source: no shrink at all.
        assertFalse(HighQualityRetryPolicy.highQualityWouldShrink(3_000_000, 1080))
        // Unknown bitrate fails closed.
        assertFalse(HighQualityRetryPolicy.highQualityWouldShrink(0, 1080))
    }

    @Test
    fun isEligibleRequiresPerceptualLosslessBatchCandidateTerminalAndRealShrink() {
        // The happy path: PL batch, kept-original terminal, and HQ genuinely shrinks it.
        assertTrue(
            HighQualityRetryPolicy.isEligible(
                batchWasPerceptualLossless = true,
                terminal = BatchTerminalResult.ALREADY_HIGHLY_OPTIMIZED,
                sourceVideoBitrate = 15_000_000,
                sourceHeight = 2160
            )
        )
        // Not a PL batch (e.g. already ran in High Quality): never offer.
        assertFalse(
            HighQualityRetryPolicy.isEligible(false, BatchTerminalResult.ALREADY_HIGHLY_OPTIMIZED, 15_000_000, 2160)
        )
        // A real compression already happened: nothing to offer.
        assertFalse(
            HighQualityRetryPolicy.isEligible(true, BatchTerminalResult.TRANSCODED_SMALLER, 15_000_000, 2160)
        )
        // Visible-loss skips are never auto-offered even when HQ could shrink them.
        assertFalse(
            HighQualityRetryPolicy.isEligible(true, BatchTerminalResult.SKIPPED_WOULD_DEGRADE, 15_000_000, 2160)
        )
        // Candidate terminal but HQ cannot honestly shrink it (floor exceeds source).
        assertFalse(
            HighQualityRetryPolicy.isEligible(true, BatchTerminalResult.REMUX_PREFERRED_BY_EVIDENCE, 3_000_000, 1080)
        )
    }
}
