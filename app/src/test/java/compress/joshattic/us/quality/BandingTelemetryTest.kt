package compress.joshattic.us.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CAMBI banding telemetry. Two properties matter and neither is about compression:
 *
 * 1. It must summarize honestly — a partial or failed native read must produce NO diagnostic
 *    rather than an averaged-in sentinel, because understating banding is the one direction a
 *    banding signal must never fail in.
 * 2. It must stay telemetry. Nothing in [QualityProbePolicy] may read it, or a user-facing
 *    "Perceptually Lossless" claim would start depending on an uncalibrated threshold.
 */
class BandingTelemetryTest {

    @Test
    fun summarizesMeanP95AndMax() {
        val diag = VmafPairScorer.summarizeBanding(doubleArrayOf(0.0, 0.1, 0.2, 0.3, 0.4))
        assertNotNull(diag)
        requireNotNull(diag)
        assertEquals(5, diag.frames)
        assertEquals(0.2, diag.mean, 1e-9)
        assertEquals(0.4, diag.max, 1e-9)
        // p95 mirrors the existing p5 index convention: ((n-1) * 0.95).toInt(), so 5 samples
        // index 3. The single worst frame is carried by [max], which is why both are reported —
        // a percentile alone would let one severely banded frame hide inside the window.
        assertEquals(0.3, diag.p95, 1e-9)
    }

    @Test
    fun aCleanWindowSummarizesToZeroRatherThanNull() {
        // Zero banding is a real, useful measurement — it must be recorded, not dropped.
        val diag = VmafPairScorer.summarizeBanding(doubleArrayOf(0.0, 0.0, 0.0))
        assertNotNull(diag)
        assertEquals(0.0, requireNotNull(diag).max, 1e-9)
    }

    @Test
    fun failedOrMissingReadsProduceNoDiagnostic() {
        // -1.0 is the native per-frame retrieval-failure sentinel. Averaging it in would pull the
        // mean DOWN and make a banding-heavy window look clean.
        assertNull(VmafPairScorer.summarizeBanding(doubleArrayOf(0.2, -1.0, 0.3)))
        assertNull(VmafPairScorer.summarizeBanding(doubleArrayOf(-1.0)))
        assertNull(VmafPairScorer.summarizeBanding(doubleArrayOf(0.1, Double.NaN)))
        assertNull(VmafPairScorer.summarizeBanding(doubleArrayOf(Double.POSITIVE_INFINITY)))
        assertNull(VmafPairScorer.summarizeBanding(doubleArrayOf()))
        assertNull(VmafPairScorer.summarizeBanding(null))
    }

    @Test
    fun compactFormIsLocalePinnedAndCarriesFourDecimals() {
        val default = java.util.Locale.getDefault()
        try {
            // A comma-decimal locale must not corrupt the "/"-separated capture field.
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val compact = WindowBandingDiag(frames = 3, mean = 0.1234, p95 = 0.5678, max = 0.9012).compact()
            assertEquals("cambi=0.1234/0.5678/0.9012", compact)
        } finally {
            java.util.Locale.setDefault(default)
        }
    }

    @Test
    fun bandingNeverInfluencesAnAcceptanceDecision() {
        // The invariant that keeps an uncalibrated metric out of a user-facing quality claim:
        // two windows identical except for catastrophic banding must reach the same verdict.
        fun window(banding: WindowBandingDiag?) = WindowScore(
            comparedFrames = 30, mean = 97.0, p5 = 93.0, min = 88.0, banding = banding
        )

        val clean = listOf(window(WindowBandingDiag(30, 0.0, 0.0, 0.0)))
        val severe = listOf(window(WindowBandingDiag(30, 20.0, 24.0, 24.0)))
        val absent = listOf(window(null))

        assertTrue(QualityProbePolicy.windowsPass(clean))
        assertTrue(QualityProbePolicy.windowsPass(severe))
        assertTrue(QualityProbePolicy.windowsPass(absent))

        listOf(clean, severe, absent).forEach { windows ->
            assertTrue(
                QualityProbePolicy.certificationOutcomePassesWithoutProbeBasis(
                    PairScoreOutcome.Scored(windows)
                )
            )
            assertTrue(
                QualityProbePolicy.certificationOutcomePasses(
                    usedRatio = 0.90,
                    defaultRatio = 0.90,
                    outcome = PairScoreOutcome.Scored(windows)
                )
            )
        }
    }
}
