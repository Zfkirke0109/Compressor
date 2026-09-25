package compress.joshattic.us.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe selection margin (QualityProbePolicy.PROBE_SELECTION) replays b165: every
 * certification failure had passed its probe by under 0.5 points, and the margins are the
 * 10th-percentile probe-to-full-encode drift measured on 63 twice-scored windows.
 */
class ProbeSelectionMarginTest {

    private fun w(mean: Double, p5: Double, min: Double) = WindowScore(comparedFrames = 29, mean = mean, p5 = p5, min = min)

    @Test
    fun theAcceptanceBarItselfIsUnchanged() {
        assertEquals(95.5, QualityProbePolicy.PERCEPTUAL_LOSSLESS.meanMin, 0.0)
        assertEquals(91.0, QualityProbePolicy.PERCEPTUAL_LOSSLESS.p5Min, 0.0)
        assertEquals(84.0, QualityProbePolicy.PERCEPTUAL_LOSSLESS.minMin, 0.0)
    }

    @Test
    fun theSelectionBarSitsTheMeasuredDriftAboveTheAcceptanceBar() {
        assertEquals(96.0, QualityProbePolicy.PROBE_SELECTION.meanMin, 1e-9)
        assertEquals(92.25, QualityProbePolicy.PROBE_SELECTION.p5Min, 1e-9)
        assertEquals(85.0, QualityProbePolicy.PROBE_SELECTION.minMin, 1e-9)
    }

    @Test
    fun everyB165CertificationFailureWouldHaveBeenAMarginalProbe() {
        // Probe window scores of the seven b165 jobs whose full encode then failed certification.
        val failedLater = listOf(
            listOf(w(97.770, 94.593, 91.860), w(95.910, 92.437, 89.883), w(96.641, 94.127, 92.125)), // job_458aa0663c3e
            listOf(w(97.402, 92.843, 84.880), w(98.577, 94.649, 93.150), w(96.830, 91.656, 84.343)), // job_2866238d579f
            listOf(w(95.617, 92.472, 85.773), w(97.877, 94.502, 89.278), w(97.369, 94.140, 84.805)), // job_9260d5d28798
            listOf(w(96.677, 92.869, 90.762), w(96.392, 92.007, 90.275), w(95.509, 92.840, 90.314)), // job_7a615b098ee0
            listOf(w(96.105, 94.717, 94.194), w(99.525, 96.697, 92.050), w(95.566, 93.414, 93.133)), // job_402118a5a2d5
            listOf(w(96.802, 91.455, 89.313), w(97.775, 93.047, 91.628), w(96.016, 93.749, 93.078))  // job_dbe605676504
        )
        failedLater.forEach { scores ->
            assertEquals(QualityProbePolicy.RungVerdict.MARGINAL, QualityProbePolicy.rungVerdict(scores))
        }
    }

    @Test
    fun aComfortablePassIsSelectedAndAFailureStaysAFailure() {
        val comfortable = listOf(w(99.899, 100.0, 97.059), w(99.882, 100.0, 96.576), w(99.795, 99.023, 96.002)) // job_be917e463748
        assertEquals(QualityProbePolicy.RungVerdict.PASSED, QualityProbePolicy.rungVerdict(comfortable))
        val failing = listOf(w(90.297, 88.509, 88.143))
        assertEquals(QualityProbePolicy.RungVerdict.FAILED, QualityProbePolicy.rungVerdict(failing))
        assertEquals(QualityProbePolicy.RungVerdict.UNMEASURED, QualityProbePolicy.rungVerdict(null))
        assertEquals(QualityProbePolicy.RungVerdict.UNMEASURED, QualityProbePolicy.rungVerdict(emptyList()))
    }

    @Test
    fun tooFewFramesIsAFailureNotAMarginalPass() {
        val short = listOf(WindowScore(comparedFrames = 5, mean = 99.0, p5 = 99.0, min = 99.0))
        assertEquals(QualityProbePolicy.RungVerdict.FAILED, QualityProbePolicy.rungVerdict(short))
    }

    @Test
    fun barMarginIsTheSmallestClearanceOverWindowsAndGates() {
        val scores = listOf(w(96.0, 95.0, 90.0), w(97.0, 91.3, 88.0))
        assertEquals(0.3, QualityProbePolicy.barMargin(scores), 1e-9)
        assertTrue(QualityProbePolicy.barMargin(listOf(w(95.0, 95.0, 95.0))) < 0.0)
    }

    @Test
    fun theShortLadderGetsTheLongerBudget() {
        assertEquals(ExhaustivePerceptualLosslessPolicy.SHORT_LADDER_PROBE_BUDGET_MS, ExhaustivePerceptualLosslessPolicy.probeBudgetMs(true))
        assertEquals(ExhaustivePerceptualLosslessPolicy.PROBE_BUDGET_MS, ExhaustivePerceptualLosslessPolicy.probeBudgetMs(false))
        assertTrue(ExhaustivePerceptualLosslessPolicy.SHORT_LADDER_PROBE_BUDGET_MS >= 3 * 180_000L)
    }

    @Test
    fun aMarginalSafestRungGetsOneTryAtTheCeiling() {
        assertEquals(QualityProbePolicy.SAFEST_RATIO_CEILING, QualityProbePolicy.upwardMarginCandidate(0.95)!!, 0.0)
        assertEquals(null, QualityProbePolicy.upwardMarginCandidate(QualityProbePolicy.SAFEST_RATIO_CEILING))
    }

    @Test
    fun anOverBudgetLadderStillMeasuresTheSafestRungInsteadOfEncodingBlind() {
        val budget = ExhaustivePerceptualLosslessPolicy.PROBE_BUDGET_MS
        val over = budget + 1
        assertEquals(ExhaustivePerceptualLosslessPolicy.OverBudget.SKIP_TO_SAFEST,
            ExhaustivePerceptualLosslessPolicy.overBudgetAction(0.90, 0.97, over, budget))
        assertEquals(ExhaustivePerceptualLosslessPolicy.OverBudget.PROBE_SAFEST,
            ExhaustivePerceptualLosslessPolicy.overBudgetAction(0.97, 0.97, over, budget))
        assertEquals(ExhaustivePerceptualLosslessPolicy.OverBudget.STOP,
            ExhaustivePerceptualLosslessPolicy.overBudgetAction(0.97, 0.97, 2 * budget + 1, budget))
        assertEquals(ExhaustivePerceptualLosslessPolicy.OverBudget.STOP,
            ExhaustivePerceptualLosslessPolicy.overBudgetAction(0.90, null, over, budget))
    }
}
