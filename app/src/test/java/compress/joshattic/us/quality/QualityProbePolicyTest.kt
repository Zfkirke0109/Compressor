package compress.joshattic.us.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityProbePolicyTest {

    private fun good(frames: Int = 30) = WindowScore(frames, mean = 97.0, p5 = 93.0, min = 88.0)

    @Test
    fun candidateLadderIsLowestFirstClampedToHardFloor() {
        assertEquals(listOf(0.70, 0.80, 0.90), QualityProbePolicy.candidateRatios(0.90, allowBelowDefault = true))
        // Ladder can never go below the hard floor even for high defaults.
        assertEquals(
            listOf(0.60, 0.65, 0.75),
            QualityProbePolicy.candidateRatios(0.75, allowBelowDefault = true)
        )
        assertEquals(listOf(0.90), QualityProbePolicy.candidateRatios(0.90, allowBelowDefault = false))
        assertTrue(QualityProbePolicy.candidateRatios(0.62, true).all { it >= QualityProbePolicy.HARD_RATIO_FLOOR })
    }

    @Test
    fun pixelScoreableGeometryReachesFourKClass() {
        // At/below the 4K-class cap: the finished output can be scored, so it can be pixel-proven.
        assertTrue(QualityProbePolicy.isPixelScoreableGeometry(1920, 1080))
        assertTrue(QualityProbePolicy.isPixelScoreableGeometry(1080, 1920)) // portrait
        assertTrue(QualityProbePolicy.isPixelScoreableGeometry(1280, 720))
        assertTrue(QualityProbePolicy.isPixelScoreableGeometry(2560, 1440)) // 1440p
        assertTrue(QualityProbePolicy.isPixelScoreableGeometry(3840, 2160)) // 4K, the S23 Ultra case
        assertTrue(QualityProbePolicy.isPixelScoreableGeometry(2160, 3840)) // 4K portrait
        // Above the cap two simultaneous 8K decoders are needed: still no evidence.
        assertFalse(QualityProbePolicy.isPixelScoreableGeometry(7680, 4320)) // 8K
        // Degenerate dimensions never scoreable.
        assertFalse(QualityProbePolicy.isPixelScoreableGeometry(0, 0))
        assertFalse(QualityProbePolicy.isPixelScoreableGeometry(-1, 1080))
        // Exactly at the cap boundary (3840*2176) is scoreable; one pixel over is not.
        assertTrue(QualityProbePolicy.isPixelScoreableGeometry(3840, 2176))
        assertFalse(QualityProbePolicy.isPixelScoreableGeometry(3841, 2176))
    }

    @Test
    fun probeLadderGeometryStaysAtTheAffordableBar() {
        // The ladder's trial encodes stay 1080p-class: at 4K they cannot finish inside the
        // prober's budget, so the plan keeps its default ratio and relies on certification.
        assertTrue(QualityProbePolicy.isProbeLadderGeometry(1920, 1080))
        assertTrue(QualityProbePolicy.isProbeLadderGeometry(1920, 1088))
        assertTrue(QualityProbePolicy.isProbeLadderGeometry(1080, 1920))
        assertFalse(QualityProbePolicy.isProbeLadderGeometry(1921, 1088))
        assertFalse(QualityProbePolicy.isProbeLadderGeometry(2560, 1440))
        assertFalse(QualityProbePolicy.isProbeLadderGeometry(3840, 2160))
        assertFalse(QualityProbePolicy.isProbeLadderGeometry(0, 0))
    }

    @Test
    fun ladderGeometryIsStrictlyNarrowerThanScoreableGeometry() {
        // The invariant the two gates rest on: anything worth a ladder is always scoreable, and
        // 4K-class sources are scoreable WITHOUT being ladder-eligible. If these ever collapse
        // into one predicate, 4K silently loses either its certification or its budget guard.
        assertTrue(QualityProbePolicy.PROBE_LADDER_MAX_PIXELS < VmafPairScorer.MAX_COMPARE_PIXELS)
        listOf(1280 to 720, 1920 to 1080, 1920 to 1088).forEach { (w, h) ->
            assertTrue(QualityProbePolicy.isProbeLadderGeometry(w, h))
            assertTrue(QualityProbePolicy.isPixelScoreableGeometry(w, h))
        }
        listOf(2560 to 1440, 3840 to 2160).forEach { (w, h) ->
            assertFalse(QualityProbePolicy.isProbeLadderGeometry(w, h))
            assertTrue(QualityProbePolicy.isPixelScoreableGeometry(w, h))
        }
    }

    @Test
    fun certificationWithoutProbeBasisStillFailsOnMeasuredEvidence() {
        // Sources above the ladder bar never had a pixel-justified target, so merely-unavailable
        // evidence leaves the structural verdict standing (the behavior they already get today).
        assertTrue(
            QualityProbePolicy.certificationOutcomePassesWithoutProbeBasis(PairScoreOutcome.Unavailable)
        )
        // ...but measured evidence still rules in the negative direction, exactly as elsewhere.
        assertFalse(
            QualityProbePolicy.certificationOutcomePassesWithoutProbeBasis(PairScoreOutcome.MisalignmentRejected)
        )
        assertFalse(
            QualityProbePolicy.certificationOutcomePassesWithoutProbeBasis(
                PairScoreOutcome.Scored(listOf(good(), good().copy(mean = 90.0)))
            )
        )
        assertTrue(
            QualityProbePolicy.certificationOutcomePassesWithoutProbeBasis(
                PairScoreOutcome.Scored(listOf(good(), good(), good()))
            )
        )
    }

    @Test
    fun unavailableEvidenceNeverWearsThePixelProvenLabel() {
        // The no-probe-basis fallback accepts, but acceptance is not proof: only measured windows
        // may set pixelCertified, so a 4K output whose scoring failed still reads structural-only.
        val acceptedWithoutEvidence =
            QualityProbePolicy.certificationOutcomePassesWithoutProbeBasis(PairScoreOutcome.Unavailable)
        assertTrue(acceptedWithoutEvidence)
        assertFalse(
            QualityProbePolicy.isPixelCertified(acceptedWithoutEvidence, PairScoreOutcome.Unavailable)
        )
        // A measured pass at 4K-class geometry is the case this whole change exists to unlock.
        val scored = PairScoreOutcome.Scored(listOf(good(), good(), good()))
        assertTrue(
            QualityProbePolicy.isPixelCertified(
                QualityProbePolicy.certificationOutcomePassesWithoutProbeBasis(scored),
                scored
            )
        )
    }

    @Test
    fun windowsPassRequiresEveryWindowAboveEveryThreshold() {
        assertTrue(QualityProbePolicy.windowsPass(listOf(good(), good(), good())))
        assertFalse(QualityProbePolicy.windowsPass(null))
        assertFalse(QualityProbePolicy.windowsPass(emptyList()))
        // One weak window rejects the whole ladder step.
        assertFalse(QualityProbePolicy.windowsPass(listOf(good(), good().copy(p5 = 89.9))))
        assertFalse(QualityProbePolicy.windowsPass(listOf(good().copy(mean = 95.0))))
        assertFalse(QualityProbePolicy.windowsPass(listOf(good().copy(min = 80.0))))
        // Too few compared frames = no evidence, not weak evidence.
        assertFalse(QualityProbePolicy.windowsPass(listOf(good(frames = 5))))
    }

    @Test
    fun certificationFailsClosedOnlyForSubDefaultRatios() {
        val passing = listOf(good(), good())
        val failing = listOf(good(), good().copy(min = 60.0))

        // Measured results always decide, regardless of ratio.
        assertTrue(QualityProbePolicy.certificationPasses(0.70, 0.90, passing))
        assertFalse(QualityProbePolicy.certificationPasses(0.70, 0.90, failing))
        assertFalse(QualityProbePolicy.certificationPasses(0.90, 0.90, failing))

        // Unmeasurable: the legacy default-ratio path keeps its structural verdict...
        assertTrue(QualityProbePolicy.certificationPasses(0.90, 0.90, null))
        // ...but a sub-default encode's only justification WAS pixel evidence -> fail closed.
        assertFalse(QualityProbePolicy.certificationPasses(0.70, 0.90, null))
    }

    @Test
    fun worstWindowShortfallMeasuresTheBindingMargin() {
        // Bars are 95.5 / 91.0 / 84.0. A passing set has a non-positive shortfall.
        assertTrue(QualityProbePolicy.worstWindowShortfall(listOf(good(), good()))!! <= 0.0)
        // Fails by 2.0 on the mean (worst of the three), others passing.
        val nearMiss = WindowScore(30, mean = 93.5, p5 = 92.0, min = 88.0)
        assertEquals(2.0, QualityProbePolicy.worstWindowShortfall(listOf(good(), nearMiss))!!, 1e-9)
        // The WORST window across the set wins.
        val badP5 = WindowScore(30, mean = 97.0, p5 = 80.0, min = 88.0) // p5 short by 11
        assertEquals(11.0, QualityProbePolicy.worstWindowShortfall(listOf(nearMiss, badP5))!!, 1e-9)
        assertNull(QualityProbePolicy.worstWindowShortfall(null))
        assertNull(QualityProbePolicy.worstWindowShortfall(emptyList()))
    }

    @Test
    fun upwardRefinementOnlyFiresForARealNearMissBelowTheCeiling() {
        val nearMiss = listOf(WindowScore(30, mean = 93.5, p5 = 92.0, min = 88.0)) // short by 2.0
        val farMiss = listOf(WindowScore(30, mean = 85.0, p5 = 92.0, min = 88.0))  // short by 10.5
        val exactBoundary = listOf(WindowScore(30, mean = 93.0, p5 = 92.0, min = 88.0)) // short by 2.5

        // Near-miss at 0.95 -> retry at the 0.97 ceiling.
        assertEquals(0.97, QualityProbePolicy.upwardRefinementCandidate(0.95, nearMiss)!!, 1e-9)
        // Exactly at the margin still qualifies; a hair past it does not.
        assertEquals(0.97, QualityProbePolicy.upwardRefinementCandidate(0.95, exactBoundary)!!, 1e-9)
        assertNull(QualityProbePolicy.upwardRefinementCandidate(0.95, farMiss))
        // Already at/above the ceiling: nowhere higher to go.
        assertNull(QualityProbePolicy.upwardRefinementCandidate(0.97, nearMiss))
        // A rung that actually passed has nothing to refine.
        assertNull(QualityProbePolicy.upwardRefinementCandidate(0.95, listOf(good())))
        // No measured scores -> cannot judge a near-miss -> no extra probe.
        assertNull(QualityProbePolicy.upwardRefinementCandidate(0.95, null))
    }

    @Test
    fun onlyMeasuredScoredWindowsCountAsPixelCertified() {
        // Regression guard for QUAL-001's subtlety: "certification passed" is NOT "pixels proved it".
        val scored = PairScoreOutcome.Scored(listOf(good(), good()))

        // The only true case: a pass backed by real measured windows.
        assertTrue(QualityProbePolicy.isPixelCertified(certificationPassed = true, outcome = scored))

        // Unavailable passes acceptance at/above the default ratio via the STRUCTURAL fallback —
        // honest acceptance, but never pixel evidence. This is the case that would otherwise wear
        // the full perceptual label dishonestly.
        assertFalse(
            QualityProbePolicy.isPixelCertified(
                certificationPassed = QualityProbePolicy.certificationOutcomePasses(0.90, 0.90, PairScoreOutcome.Unavailable),
                outcome = PairScoreOutcome.Unavailable
            )
        )

        // Measured misalignment is evidence AGAINST, never certification.
        assertFalse(QualityProbePolicy.isPixelCertified(true, PairScoreOutcome.MisalignmentRejected))

        // A failed certification is never pixel-certified, even with measured windows.
        assertFalse(QualityProbePolicy.isPixelCertified(certificationPassed = false, outcome = scored))
    }

    @Test
    fun measuredMisalignmentNeverCertifiesNotEvenStructurally() {
        val passing = PairScoreOutcome.Scored(listOf(good(), good()))
        val failing = PairScoreOutcome.Scored(listOf(good(), good().copy(min = 60.0)))

        // Scored outcomes decide exactly like the legacy list path.
        assertTrue(QualityProbePolicy.certificationOutcomePasses(0.90, 0.90, passing))
        assertFalse(QualityProbePolicy.certificationOutcomePasses(0.90, 0.90, failing))

        // Unavailable evidence keeps the legacy structural fallback semantics.
        assertTrue(QualityProbePolicy.certificationOutcomePasses(0.90, 0.90, PairScoreOutcome.Unavailable))
        assertFalse(QualityProbePolicy.certificationOutcomePasses(0.70, 0.90, PairScoreOutcome.Unavailable))

        // Measured misalignment is evidence AGAINST the output (frame loss/retiming):
        // it must fail even at the default ratio, where mere unavailability would pass.
        assertFalse(QualityProbePolicy.certificationOutcomePasses(0.90, 0.90, PairScoreOutcome.MisalignmentRejected))
        assertFalse(QualityProbePolicy.certificationOutcomePasses(0.70, 0.90, PairScoreOutcome.MisalignmentRejected))
    }

    @Test
    fun misalignmentYieldsNoMeasuredWindowsSoItCannotBeReadAsDegradation() {
        // The ratchet in BatchCompressorViewModel fires on a MEASURED rejection at the safest
        // rung, which SmartPerceptualProfileEngine then turns into skipped probes for a whole
        // profile class. The property that keeps misalignment out of it is that a misaligned
        // window produces no WindowScore at all — only PairScoreOutcome.Scored carries windows,
        // so PerceptualQualityProber.probeOneRatio returns null and the rung is "unmeasurable",
        // never "measured and failing". Pinned here because getting it wrong trains the engine
        // on pairing noise as if it were pixel evidence.
        val misaligned: PairScoreOutcome = PairScoreOutcome.MisalignmentRejected
        assertFalse(misaligned is PairScoreOutcome.Scored)

        // With no measured windows there is nothing for the near-miss machinery to read, so an
        // unalignable ladder can neither compute a shortfall nor spend another probe encode.
        assertNull(QualityProbePolicy.worstWindowShortfall(null))
        assertNull(QualityProbePolicy.worstWindowShortfall(emptyList()))
        assertNull(QualityProbePolicy.upwardRefinementCandidate(0.90, null))
        assertNull(QualityProbePolicy.upwardRefinementCandidate(0.90, emptyList()))

        // And an empty/absent score list never passes the bar, so it can never certify either.
        assertFalse(QualityProbePolicy.windowsPass(null))
        assertFalse(QualityProbePolicy.windowsPass(emptyList()))
    }

    @Test
    fun probeWindowsSampleAwayFromClipEdges() {
        // Too short to probe honestly.
        assertTrue(QualityProbePolicy.probeWindows(1_500_000L).isEmpty())

        // Short clip: one centered window.
        val single = QualityProbePolicy.probeWindows(6_000_000L)
        assertEquals(1, single.size)
        assertTrue(single[0].startUs > 0 && single[0].endUs < 6_000_000L)

        // Long clip: three windows at 20/50/80%, all inside the clip.
        val durationUs = 60_000_000L
        val triple = QualityProbePolicy.probeWindows(durationUs)
        assertEquals(3, triple.size)
        triple.forEach {
            assertTrue(it.startUs >= 0)
            assertTrue(it.endUs <= durationUs)
            assertEquals(1_200_000L, it.endUs - it.startUs)
        }
        assertTrue(triple[0].startUs < triple[1].startUs && triple[1].startUs < triple[2].startUs)
    }

    @Test
    fun probeFastPathFloorSitsBelowTheTransparencyGate() {
        // The fast path may only skip probing for sources ALREADY excluded by evidence margin;
        // it must never eat into the gray band the ladder is meant to test (0.05..0.08 bpp).
        assertTrue(
            QualityProbePolicy.PROBE_MIN_SOURCE_BITS_PER_PIXEL <
                compress.joshattic.us.BatchQualityBitratePolicy.PERCEPTUAL_LOSSLESS_MIN_SOURCE_BITS_PER_PIXEL
        )
    }

    @Test
    fun windowThresholdsSitAboveTheFullClipSuiteThresholds() {
        // The margin is the whole point: sampled windows must look BETTER than the full-clip
        // bar (mean 95 / 1%-low 90 / min 80) before a lower ratio is trusted.
        assertTrue(QualityProbePolicy.WINDOW_MEAN_MIN > 95.0)
        assertTrue(QualityProbePolicy.WINDOW_P5_MIN > 90.0)
        assertTrue(QualityProbePolicy.WINDOW_MIN_MIN > 80.0)
        assertNull(null) // keep junit4 import shape stable
    }

    @Test
    fun bppClassedLadderShapesMatchTheEvidence() {
        // Healthy sources: full downward ladder PLUS one safer retreat rung above the default.
        assertEquals(
            listOf(0.70, 0.80, 0.90, 0.95),
            QualityProbePolicy.candidateRatiosForSource(0.90, sourceBitsPerPixel = 0.15)
        )
        // The retreat rung is capped at the safest useful ceiling.
        assertEquals(
            listOf(0.75, 0.85, 0.95, 0.97),
            QualityProbePolicy.candidateRatiosForSource(0.95, sourceBitsPerPixel = 0.20)
        )
        // Starved-but-probeable sources get safest-biased rungs only: their low rungs are
        // already disproven, but the safer rungs have never been measured — these are their
        // first-ever trial encodes instead of a prediction-only rejection.
        assertEquals(
            listOf(0.90, 0.95),
            QualityProbePolicy.candidateRatiosForSource(0.90, sourceBitsPerPixel = 0.05)
        )
        // Below the noise line: no ladder; inference stands.
        assertTrue(QualityProbePolicy.candidateRatiosForSource(0.90, sourceBitsPerPixel = 0.02).isEmpty())
        assertTrue(QualityProbePolicy.candidateRatiosForSource(0.90, sourceBitsPerPixel = null).isEmpty())
        // Ladder never drops below the hard floor.
        assertTrue(
            QualityProbePolicy.candidateRatiosForSource(0.70, sourceBitsPerPixel = 0.30)
                .all { it >= QualityProbePolicy.HARD_RATIO_FLOOR }
        )
    }

    @Test
    fun refinementBisectsOnlyWhenTheGapIsWorthAnEncode() {
        // Pass at 0.80 after failing 0.70: midpoint 0.75 is worth one probe.
        assertEquals(0.75, QualityProbePolicy.refinementCandidate(0.80, 0.70)!!, 1e-9)
        // Narrow gap: not worth an extra encode.
        assertNull(QualityProbePolicy.refinementCandidate(0.75, 0.70))
        // Nothing failed below: bisect toward the hard floor when the gap allows.
        assertEquals(0.65, QualityProbePolicy.refinementCandidate(0.70, null)!!, 1e-9)
        // Passing at the floor itself leaves nothing to refine.
        assertNull(QualityProbePolicy.refinementCandidate(QualityProbePolicy.HARD_RATIO_FLOOR, null))
        // The refinement may never dip below the hard floor.
        val refined = QualityProbePolicy.refinementCandidate(0.90, null)
        assertTrue(refined == null || refined >= QualityProbePolicy.HARD_RATIO_FLOOR)
    }

    @Test
    fun theTwoQualityBarsAreOrderedAndDistinct() {
        val pl = QualityProbePolicy.PERCEPTUAL_LOSSLESS
        val hq = QualityProbePolicy.HIGH_QUALITY

        // The transparency bar must keep its calibrated values exactly.
        assertEquals(95.5, pl.meanMin, 1e-9)
        assertEquals(91.0, pl.p5Min, 1e-9)
        assertEquals(84.0, pl.minMin, 1e-9)

        // HQ is explicitly LOSSY, so every threshold must sit strictly below transparency. If
        // these ever met or crossed, "High Quality" could quietly accept what PL rejects while
        // still being described as the lower-quality mode.
        assertTrue(hq.meanMin < pl.meanMin)
        assertTrue(hq.p5Min < pl.p5Min)
        assertTrue(hq.minMin < pl.minMin)

        // ...and HQ must not be a free-for-all either; it is still a quality bar.
        assertTrue(hq.meanMin > 80.0)
        assertTrue(hq.minMin > 60.0)
    }

    @Test
    fun barAwareWindowsPassDefaultsToTransparencyForEveryExistingCaller() {
        // The regression that matters: adding a second bar must not move the PL decision.
        // A window between the two bars passes HQ and fails PL.
        val between = listOf(WindowScore(30, mean = 94.0, p5 = 89.5, min = 82.0))

        assertFalse(QualityProbePolicy.windowsPass(between))
        assertFalse(QualityProbePolicy.windowsPass(between, QualityProbePolicy.PERCEPTUAL_LOSSLESS))
        assertTrue(QualityProbePolicy.windowsPass(between, QualityProbePolicy.HIGH_QUALITY))
    }

    @Test
    fun theHighQualityBarStillRejectsRealDegradation() {
        // A lower bar is not an absent one.
        val bad = listOf(WindowScore(30, mean = 70.0, p5 = 60.0, min = 40.0))
        assertFalse(QualityProbePolicy.windowsPass(bad, QualityProbePolicy.HIGH_QUALITY))

        // One weak window still rejects the whole set, same as PL.
        val mixed = listOf(good(), WindowScore(30, mean = 94.0, p5 = 70.0, min = 82.0))
        assertFalse(QualityProbePolicy.windowsPass(mixed, QualityProbePolicy.HIGH_QUALITY))

        // Too few compared frames is still "no evidence", not "weak evidence", at any bar.
        val tooFew = listOf(WindowScore(5, mean = 99.0, p5 = 99.0, min = 99.0))
        assertFalse(QualityProbePolicy.windowsPass(tooFew, QualityProbePolicy.HIGH_QUALITY))
        assertFalse(QualityProbePolicy.windowsPass(tooFew, QualityProbePolicy.PERCEPTUAL_LOSSLESS))
    }
}
