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

    @Test
    fun unavailableRungsNameTheirCauseSoTheyCanBeActedOn() {
        // "unmeasurable" alone could not separate an export that timed out on a 52-minute source
        // from a decoder that failed on a 90-second one — different problems, different fixes.
        val detail = QualityProbePolicy.ladderExhaustedDetail(
            measured = 0, misaligned = 0, unavailable = 3,
            unavailableReasons = mapOf("export timed out after 60000ms" to 2, "export failed" to 1)
        )
        assertTrue(detail.contains("3 unmeasurable"))
        assertTrue(detail.contains("2x export timed out after 60000ms"))
        assertTrue(detail.contains("1x export failed"))
        assertTrue(detail.contains("NOT evidence"))
    }

    @Test
    fun theCommonestCauseIsNamedFirst() {
        val detail = QualityProbePolicy.ladderExhaustedDetail(
            measured = 0, misaligned = 0, unavailable = 4,
            unavailableReasons = mapOf("export failed" to 1, "scorer produced no evidence" to 3)
        )
        assertTrue(
            "commonest cause should lead: $detail",
            detail.indexOf("3x scorer produced no evidence") < detail.indexOf("1x export failed")
        )
    }

    @Test
    fun aLadderWithNoUnavailableRungsGainsNoCauseList() {
        // A fully-measured ladder keeps the original sentence verbatim, so captures stay comparable.
        assertEquals(
            "no candidate ratio passed",
            QualityProbePolicy.ladderExhaustedDetail(4, 0, 0, emptyMap())
        )
    }

    @Test
    fun causesAreOmittedRatherThanFakedWhenUnrecorded() {
        // Callers predating the reason map must not produce an empty bracket.
        val detail = QualityProbePolicy.ladderExhaustedDetail(measured = 0, misaligned = 1, unavailable = 2)
        assertTrue(detail.contains("2 unmeasurable"))
        assertFalse("no empty bracket: $detail", detail.contains("[]"))
    }
}
