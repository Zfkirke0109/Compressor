package compress.joshattic.us.quality

import androidx.media3.common.MimeTypes
import compress.joshattic.us.BatchQualityBitratePolicy
import compress.joshattic.us.VideoSourceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The size gate's record keeps the assessed rung's own verdict and scope, on either decision. */
class SizeGateEvidenceTest {

    private val hevc = ProbeEncodeShape(iFrameIntervalSeconds = 1f, maxBFrames = 0)

    private fun rung(ratio: Double, verdict: String, planned: Int, vararg means: Double) = RungEvidence(
        ratio = ratio, outcome = RungEvidence.MEASURED, verdict = verdict,
        requestedVideoBitrate = (ratio * 12_000_000).toInt(),
        configId = EncodeConfigIdentity.of("video/hevc", hevc, (ratio * 12_000_000).toInt()),
        windows = (0 until planned).map { WindowRef(it * 10_000_000L, it * 10_000_000L + 1_200_000L) },
        scores = means.map { WindowScore(71, it, it - 1.5, it - 1.6) },
        rateFactors = emptyList(), rateDiag = null, elapsedMs = 1, encoderNames = listOf("c2.qti.hevc.encoder")
    )

    @Test
    fun aMarginalRungKeptOnPredictedSizeStaysMarginal() {
        // b287 regular job_8227cd6d6a43: 0.97 measured 95.70 on its one planned window.
        val assessed = rung(0.97, "marginal", 1, 95.7019398749062)
        val gate = SizeGateEvidence.of(
            keepOriginal = true, assessedRatio = 0.97, rung = assessed,
            predictedBytes = 114_912_600L, sourceBytes = 102_508_481L,
            overshoot = 1.1444063863537028, overshootMeasured = false
        )
        assertEquals(SizeGateEvidence.KEEP_ORIGINAL, gate.verdict)
        assertTrue(gate.keepsOriginal)
        assertEquals("marginal", gate.rungVerdict)
        assertFalse(gate.clearedSelectionMargin)
        assertEquals(1, gate.measuredWindows)
        assertEquals(1, gate.plannedWindows)
        assertEquals(SizeGateEvidence.SCOPE_COMPLETE, gate.scope)
        assertEquals(SizeGateEvidence.PREDICTED_NOT_SMALLER, gate.disposition)
        val fields = gate.fields()
        assertEquals(0.97, fields["assessedRatio"])
        assertEquals(assessed.id, fields["assessedRungId"])
        assertEquals("marginal", fields["rungVerdict"])
        assertEquals(false, fields["clearedSelectionMargin"])
        assertEquals("complete", fields["scope"])
        assertEquals("predicted", fields["sizeBasis"])
        assertEquals("sampled_probe_windows", fields["qualityBasis"])
        assertEquals("predicted_not_smaller", fields["disposition"])
        assertEquals(false, fields["overshootMeasured"])
    }

    @Test
    fun aCleanPassKeptOnPredictedSizeIsToldApartFromAMarginalOne() {
        // b287 Secure Folder job_31e5faeaf665: 0.90 passed on three of three windows.
        val gate = SizeGateEvidence.of(
            keepOriginal = true, assessedRatio = 0.90,
            rung = rung(0.90, "passed", 3, 99.5743957723856, 98.54452288978827, 96.99546166108556),
            predictedBytes = 126_265_012L, sourceBytes = 123_783_185L,
            overshoot = 1.1222605797067002, overshootMeasured = true
        )
        assertEquals("passed", gate.rungVerdict)
        assertTrue(gate.clearedSelectionMargin)
        assertEquals(3, gate.measuredWindows)
        assertEquals(SizeGateEvidence.SCOPE_COMPLETE, gate.scope)
        assertEquals(SizeGateEvidence.PREDICTED_NOT_SMALLER, gate.disposition)
    }

    @Test
    fun anEncodeVerdictIsStillOnlyAPredictionAboutSize() {
        val gate = SizeGateEvidence.of(
            keepOriginal = false, assessedRatio = 0.75, rung = rung(0.75, "marginal", 3, 96.4, 97.5, 96.0),
            predictedBytes = 33_366_670L, sourceBytes = 44_000_000L, overshoot = 1.05, overshootMeasured = true
        )
        assertEquals(SizeGateEvidence.ENCODE, gate.verdict)
        assertFalse(gate.keepsOriginal)
        assertEquals(SizeGateEvidence.PREDICTED_SMALLER, gate.disposition)
        // A marginal rung sent on to a full encode has still not cleared the selection margin.
        assertFalse(gate.clearedSelectionMargin)
        assertEquals("predicted", gate.fields()["sizeBasis"])
    }

    @Test
    fun aRungThatStoppedEarlyOrWasNotFoundIsNeverReportedComplete() {
        val early = SizeGateEvidence.of(true, 0.95, rung(0.95, "marginal", 3, 95.6), 10L, 9L, 1.0, false)
        assertEquals(SizeGateEvidence.SCOPE_PARTIAL, early.scope)
        assertEquals(1, early.measuredWindows)
        assertEquals(3, early.plannedWindows)
        val missing = SizeGateEvidence.of(true, 0.95, null, 10L, 9L, 1.0, false)
        assertNull(missing.rungVerdict)
        assertNull(missing.rungId)
        assertFalse(missing.clearedSelectionMargin)
        assertEquals(SizeGateEvidence.SCOPE_UNKNOWN, missing.scope)
        assertEquals("unknown", missing.fields()["scope"])
    }

    private fun measured(ratio: Double, mean: Double, vararg factors: Double): RungEvidence {
        val evidence = rung(ratio, "", 1, mean)
        return evidence.copy(
            verdict = QualityProbePolicy.rungVerdict(evidence.scores).name.lowercase(),
            encodeShapeId = EncodeConfigIdentity.shapeOf("video/hevc", hevc),
            rateFactors = factors.toList()
        )
    }

    private fun decision(selected: RungEvidence, vararg others: RungEvidence) = ProbeDecision(
        provenRatio = selected.ratio, probedRatios = others.map { it.ratio } + selected.ratio,
        windowScores = selected.scores, detail = "original selection",
        marginal = selected.verdict == "marginal", provenRateFactors = selected.rateFactors,
        rungs = others.toList() + selected
    )

    @Test
    fun sizeRejectedPassCanRecoverAnAlreadyMeasuredMarginalTrial() {
        // A 7.137 s S26 clip. Both ratios scored all 71 pairs at the same planned position.
        val source = VideoSourceInfo(width = 2160, height = 3840, frameRate = 60f, durationMs = 7137,
            totalBitrate = 99_431_732, audioBitrate = 255_980, videoMime = MimeTypes.VIDEO_H265, audioMime = MimeTypes.AUDIO_AAC)
        fun observed(ratio: Double, factor: Double, score: WindowScore): RungEvidence {
            val bps = (source.videoBitrate * ratio).toInt()
            return measured(ratio, score.mean, factor).copy(
                requestedVideoBitrate = bps, configId = EncodeConfigIdentity.of(MimeTypes.VIDEO_H265, hevc, bps),
                windows = listOf(WindowRef(2_968_500, 4_168_500)), scores = listOf(score)
            )
        }
        val lower = observed(0.75, 1.132, WindowScore(71, 95.697, 94.130, 93.941))
        val upper = observed(0.95, 1.076, WindowScore(71, 96.152, 94.429, 93.965))
        val original = decision(upper, lower).copy(saferPassingRatio = 0.97, saferPassingRateFactors = listOf(1.4))
        fun predict(ratio: Double, factors: List<Double>) = BatchQualityBitratePolicy.predictedPerceptualLosslessBytes(
            source, MimeTypes.VIDEO_H265, learnedTargetRatio = ratio,
            expectedOvershootFactor = MeasuredOvershoot.forPrediction(1.1558123412076382, factors),
            pixelProvenRatioFloor = ratio
        )
        assertEquals(98_352_000L, predict(upper.ratio, upper.rateFactors))
        assertEquals(77_694_874L, predict(lower.ratio, lower.rateFactors))
        val selected = original.withSizeViableAlternative(88_708_020L, ::predict)
        assertEquals(lower.ratio, selected.provenRatio!!, 0.0)
        assertEquals(lower.scores, selected.windowScores)
        assertEquals(lower.rateFactors, selected.provenRateFactors)
        assertTrue(selected.marginal)
        assertNull(selected.saferPassingRatio)
        assertTrue(selected.saferPassingRateFactors.isEmpty())
        assertEquals(original.rungs, selected.rungs)
    }

    @Test
    fun aViableOriginalChoiceOrAnUnprovenLadderIsLeftAlone() {
        val upper = measured(0.95, 98.0)
        val original = decision(upper, measured(0.75, 97.0))
        assertSame(original, original.withSizeViableAlternative(100) { _, _ -> 90L })
        assertSame(original, original.withSizeViableAlternative(0) { _, _ -> 110L })
        val rejected = original.copy(provenRatio = null, highestCandidateMeasuredRejected = true)
        assertSame(rejected, rejected.withSizeViableAlternative(100) { _, _ -> 90L })
    }

    @Test
    fun aCompleteMarginPassTakesPriorityAndUsesItsOwnSizePrediction() {
        val earlyMarginal = measured(0.70, 95.8)
        val mid = measured(0.80, 98.0, 1.5, 1.5)
        val upper = measured(0.90, 98.0, 1.1, 1.1)
        val original = decision(mid, earlyMarginal, upper)
        val selected = original.withSizeViableAlternative(100) { ratio, factors ->
            (ratio * 100 * MeasuredOvershoot.forPrediction(1.0, factors).coerceAtLeast(1.0)).toLong()
        }
        assertEquals(upper.ratio, selected.provenRatio!!, 0.0)
        assertFalse(selected.marginal)
        assertEquals(upper.rateFactors, selected.provenRateFactors)
    }

    @Test
    fun chooseTheHighestAffordableRungWhenAllPassingAlternativesAreMarginal() {
        val original = decision(measured(0.97, 95.9), measured(0.75, 95.7), measured(0.85, 95.8))
        val selected = original.withSizeViableAlternative(100) { ratio, _ -> (ratio * 110).toLong() }
        assertEquals(0.85, selected.provenRatio!!, 0.0)
        assertTrue(selected.marginal)
    }

    @Test
    fun aFailAnIncompleteWindowOrADifferentEncodeCannotSupplyAnAlternative() {
        val upper = measured(0.95, 98.0)
        val lower = measured(0.75, 97.0)
        val ineligible = listOf(
            lower.copy(outcome = RungEvidence.UNAVAILABLE),
            lower.copy(verdict = "failed"),
            lower.copy(scores = listOf(WindowScore(10, 99.0, 98.0, 97.0))),
            lower.copy(scores = listOf(WindowScore(36, 92.0, 90.0, 80.0))),
            lower.copy(windows = emptyList(), scores = emptyList()),
            lower.copy(windows = lower.windows + WindowRef(5_000_000L, 6_200_000L)),
            lower.copy(windows = listOf(WindowRef(5_000_000L, 6_200_000L))),
            lower.copy(encodeShapeId = "other-shape"),
            lower.copy(encoderNames = listOf("other-encoder"))
        )
        for (candidate in ineligible) {
            val original = decision(upper, candidate)
            assertSame(original, original.withSizeViableAlternative(100) { ratio, _ -> if (ratio == 0.95) 110L else 75L })
        }
        val original = decision(upper, lower)
        assertSame(original, original.withSizeViableAlternative(100) { _, _ -> 100L })
    }
}
