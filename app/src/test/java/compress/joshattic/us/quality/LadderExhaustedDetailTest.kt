package compress.joshattic.us.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A probe ladder that measured nothing must never be described as one that measured everything
 * and rejected it.
 *
 * "no candidate ratio passed" is a claim about pixels: every rung was scored and every rung fell
 * below the bar. When no rung produced a single scored window that claim is false, and it is the
 * kind of false claim that compounds — a reader treats it as evidence the clip resists
 * re-encoding, and any threshold calibrated from such captures is fitted to measurement failure.
 *
 * Measured across the five 219-file S23 Ultra captures: 84 of 425 ladder runs (19.8%) reported
 * "no candidate ratio passed" with `probeWindowScores: null` — nothing scored at all.
 */
class LadderExhaustedDetailTest {

    @Test
    fun aFullyMeasuredLadderKeepsTheOriginalWording() {
        // The claim is true here, so the sentence is unchanged — existing captures stay comparable.
        assertEquals(
            "no candidate ratio passed",
            QualityProbePolicy.ladderExhaustedDetail(measured = 4, misaligned = 0, unavailable = 0)
        )
    }

    @Test
    fun aLadderThatMeasuredNothingSaysSoAndDisclaimsTheEvidence() {
        val detail = QualityProbePolicy.ladderExhaustedDetail(measured = 0, misaligned = 3, unavailable = 1)
        assertFalse(
            "must not assert that candidates were measured and rejected",
            detail.startsWith("no candidate ratio passed")
        )
        assertTrue(detail.contains("no probe rung could be measured"))
        assertTrue("must name how many were unalignable", detail.contains("3 not time-alignable"))
        assertTrue("must name how many were unmeasurable", detail.contains("1 unmeasurable"))
        assertTrue(
            "must state plainly that this is not evidence about the clip",
            detail.contains("NOT evidence")
        )
    }

    @Test
    fun aPartiallyMeasuredLadderReportsBothHalves() {
        // Some rungs were real evidence and some were not; hiding either half misleads.
        val detail = QualityProbePolicy.ladderExhaustedDetail(measured = 2, misaligned = 1, unavailable = 1)
        assertTrue(detail.startsWith("no candidate ratio passed"))
        assertTrue(detail.contains("4 rungs"))
        assertTrue(detail.contains("2 measured"))
        assertTrue(detail.contains("1 not time-alignable"))
        assertTrue(detail.contains("1 unmeasurable"))
    }

    @Test
    fun aLadderWithNoRungsAtAllIsNotDescribedAsAMeasurementFailure() {
        // Degenerate input (no candidate ratios): claiming rungs failed would invent a result.
        assertEquals(
            "no candidate ratio passed",
            QualityProbePolicy.ladderExhaustedDetail(measured = 0, misaligned = 0, unavailable = 0)
        )
    }

    @Test
    fun theWordingNeverClaimsMoreRungsThanWereRun() {
        for (m in 0..3) for (x in 0..3) for (u in 0..3) {
            val detail = QualityProbePolicy.ladderExhaustedDetail(m, x, u)
            val total = m + x + u
            if (total > 0 && detail.contains(" rungs")) {
                assertTrue("total must match: $detail", detail.contains("$total rungs"))
            }
        }
    }
}
