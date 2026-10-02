package compress.joshattic.us.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive mode's short ladder for sources above 1080p. Before it, no source above 1080p was
 * ever probed, so every 4K HEVC SDR clip was kept as-is on an inference with no measurement.
 */
class ShortProbeLadderTest {

    @Test
    fun upTo1080pTheLadderIsAllowedInBothModes() {
        assertTrue(ExhaustivePerceptualLosslessPolicy.probeLadderAllowed(1920, 1080, exhaustive = false))
        assertTrue(ExhaustivePerceptualLosslessPolicy.probeLadderAllowed(1080, 1920, exhaustive = true))
        assertFalse(ExhaustivePerceptualLosslessPolicy.needsShortLadder(1920, 1080))
    }

    @Test
    fun fourKIsProbedOnlyInExhaustiveModeAndOnlyAsAShortLadder() {
        assertFalse(ExhaustivePerceptualLosslessPolicy.probeLadderAllowed(3840, 2160, exhaustive = false))
        assertTrue(ExhaustivePerceptualLosslessPolicy.probeLadderAllowed(3840, 2160, exhaustive = true))
        assertTrue(ExhaustivePerceptualLosslessPolicy.probeLadderAllowed(2160, 3840, exhaustive = true))
        assertTrue(ExhaustivePerceptualLosslessPolicy.needsShortLadder(3840, 2160))
        assertTrue(ExhaustivePerceptualLosslessPolicy.needsShortLadder(2560, 1440))
    }

    @Test
    fun eightKCanNeverBeScoredSoItIsNeverProbed() {
        assertFalse(ExhaustivePerceptualLosslessPolicy.probeLadderAllowed(7680, 4320, exhaustive = true))
        assertFalse(ExhaustivePerceptualLosslessPolicy.needsShortLadder(7680, 4320))
    }

    @Test
    fun aHealthyLadderDropsOnlyItsMiddleRung() {
        val full = QualityProbePolicy.candidateRatiosForSource(0.95, 0.20)
        assertEquals(listOf(0.75, 0.85, 0.95, 0.97), full)
        assertEquals(
            listOf(0.75, 0.95, 0.97),
            ExhaustivePerceptualLosslessPolicy.candidateRatios(0.95, 0.20, exhaustive = true, shortLadder = true)
        )
    }

    @Test
    fun theHighestRungIsTheSameAsTheFullLaddersSoASkipMeansTheSameThing() {
        // A measured rejection at the highest rung lets the batch skip a file as "would visibly
        // lose quality". That claim must rest on the same rung at 4K as at 1080p.
        for (bpp in listOf(0.01, 0.05, 0.20)) {
            val full = ExhaustivePerceptualLosslessPolicy.candidateRatios(0.90, bpp, exhaustive = true)
            val short = ExhaustivePerceptualLosslessPolicy.candidateRatios(0.90, bpp, exhaustive = true, shortLadder = true)
            assertEquals(full.maxOrNull(), short.maxOrNull())
            assertTrue(short.size <= 3)
            assertTrue(full.containsAll(short))
        }
    }

    @Test
    fun starvedAndVeryStarvedLaddersAreAlreadyShort() {
        assertEquals(listOf(0.95, 0.97), ExhaustivePerceptualLosslessPolicy.trimToShortLadder(listOf(0.95, 0.97)))
        assertEquals(listOf(0.97), ExhaustivePerceptualLosslessPolicy.trimToShortLadder(listOf(0.97)))
        assertTrue(ExhaustivePerceptualLosslessPolicy.trimToShortLadder(emptyList()).isEmpty())
    }
}
