package compress.joshattic.us.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive mode trades battery for measurement. These pin the two halves of that bargain:
 * every eligible file gets a measured chance, and nothing about what COUNTS as a pass moves.
 */
class ExhaustivePerceptualLosslessPolicyTest {

    private fun window(min: Double) = WindowScore(comparedFrames = 36, mean = min + 2.0, p5 = min + 1.0, min = min)

    @Test
    fun outsideExhaustiveModeTheLadderIsExactlyTheExistingOne() {
        for (bpp in listOf(null, 0.01, 0.05, 0.12)) {
            assertEquals(
                QualityProbePolicy.candidateRatiosForSource(0.9, bpp),
                ExhaustivePerceptualLosslessPolicy.candidateRatios(0.9, bpp, exhaustive = false)
            )
        }
    }

    @Test
    fun aStarvedSourceStillGetsOneMeasuredRungAtTheSafestRatio() {
        // Below the bpp gate the normal ladder is empty: the file would be kept as-is on a
        // class-level PREDICTION. Exhaustive mode measures it once, at the rung that spends the
        // least quality headroom.
        assertTrue(QualityProbePolicy.candidateRatiosForSource(0.9, 0.01).isEmpty())
        assertEquals(
            listOf(QualityProbePolicy.SAFEST_RATIO_CEILING),
            ExhaustivePerceptualLosslessPolicy.candidateRatios(0.9, 0.01, exhaustive = true)
        )
        assertEquals(
            listOf(QualityProbePolicy.SAFEST_RATIO_CEILING),
            ExhaustivePerceptualLosslessPolicy.candidateRatios(0.9, null, exhaustive = true)
        )
    }

    @Test
    fun aHealthySourcesLadderIsUnchangedInExhaustiveMode() {
        assertEquals(
            QualityProbePolicy.candidateRatiosForSource(0.9, 0.12),
            ExhaustivePerceptualLosslessPolicy.candidateRatios(0.9, 0.12, exhaustive = true)
        )
    }

    @Test
    fun theProbeSkipRatchetOnlyAppliesOutsideExhaustiveMode() {
        assertTrue(ExhaustivePerceptualLosslessPolicy.honourProbeSkip(exhaustive = false))
        assertFalse(ExhaustivePerceptualLosslessPolicy.honourProbeSkip(exhaustive = true))
    }

    @Test
    fun anyPredictedSavingIsWorthTryingInExhaustiveMode() {
        assertTrue(ExhaustivePerceptualLosslessPolicy.worthEncoding(1_000_000, 999_999, true, false))
        assertFalse(ExhaustivePerceptualLosslessPolicy.worthEncoding(1_000_000, 1_000_000, true, false))
        assertFalse(ExhaustivePerceptualLosslessPolicy.worthEncoding(1_000_000, 1_200_000, true, false))
        // Outside it, the existing noise threshold decides, exactly as before.
        assertFalse(ExhaustivePerceptualLosslessPolicy.worthEncoding(1_000_000, 999_999, false, false))
        assertTrue(ExhaustivePerceptualLosslessPolicy.worthEncoding(1_000_000, 900_000, false, true))
    }

    @Test
    fun anOverturnedGateIsNeverCertifiedOnStructureAlone() {
        // The ordinary rule lets an unmeasurable certification pass at or above the default
        // ratio. When probes overturned a heuristic that predicted visible loss, only measured
        // passing windows may accept the output.
        assertFalse(ExhaustivePerceptualLosslessPolicy.measuredCertificationPasses(PairScoreOutcome.Unavailable))
        assertFalse(
            ExhaustivePerceptualLosslessPolicy.measuredCertificationPasses(PairScoreOutcome.MisalignmentRejected(null))
        )
        assertTrue(
            QualityProbePolicy.certificationOutcomePasses(0.97, 0.9, PairScoreOutcome.Unavailable)
        )
    }

    @Test
    fun theAcceptanceBarIsTheSameOneTheRestOfTheAppUses() {
        val passing = listOf(window(99.0), window(99.0), window(99.0))
        val failing = listOf(window(99.0), window(10.0), window(99.0))
        assertEquals(
            QualityProbePolicy.windowsPass(passing),
            ExhaustivePerceptualLosslessPolicy.measuredCertificationPasses(PairScoreOutcome.Scored(passing))
        )
        assertFalse(ExhaustivePerceptualLosslessPolicy.measuredCertificationPasses(PairScoreOutcome.Scored(failing)))
    }
}
