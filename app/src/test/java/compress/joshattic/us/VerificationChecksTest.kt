package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The structural invariant that makes "failing=none-identified" unrepresentable:
 * a scope passes exactly when it has no failing predicates.
 */
class VerificationChecksTest {

    private fun allPassing() = VerificationChecks(
        playable = true, criticalFieldsComplete = true, durationMatches = true,
        frameCountMatches = true, videoMatches = true, fpsMatches = true,
        videoCodecMatches = true, audioCodecMatches = true, audioShapeMatches = true,
        audioBitratePass = true, hdrMatches = true, standardMatches = true,
        rangeMatches = true, rotationMatches = true, locationMatches = true,
        mediaStoreDateMatches = true, mp4DateMatches = true, videoBitratePass = true
    )

    @Test
    fun allPassingVerifiesEveryScopeAndNamesNothing() {
        val c = allPassing()
        VerificationChecks.Scope.entries.forEach { scope ->
            assertTrue(scope.name, c.passes(scope))
            assertEquals(scope.name, emptyList<String>(), c.failures(scope))
        }
    }

    @Test
    fun passingIsExactlyTheAbsenceOfFailures() {
        // Property check over randomised predicate combinations: the verdict and the diagnostic
        // are two views of one set, so they can never disagree.
        val rng = Random(20260813)
        repeat(2000) {
            fun b() = rng.nextInt(10) != 0 // mostly-true, so failures are sparse and realistic
            val c = VerificationChecks(
                playable = b(), criticalFieldsComplete = b(), durationMatches = b(),
                frameCountMatches = b(), videoMatches = b(), fpsMatches = b(),
                videoCodecMatches = b(), audioCodecMatches = b(), audioShapeMatches = b(),
                audioBitratePass = b(), hdrMatches = b(), standardMatches = b(),
                rangeMatches = b(), rotationMatches = b(), locationMatches = b(),
                mediaStoreDateMatches = b(), mp4DateMatches = b(), videoBitratePass = b()
            )
            VerificationChecks.Scope.entries.forEach { scope ->
                assertEquals(
                    "passes() must equal failures().isEmpty() for $scope on $c",
                    c.passes(scope),
                    c.failures(scope).isEmpty()
                )
            }
        }
    }

    @Test
    fun theSevenInvisiblePredicatesAreEachNamed() {
        // These are precisely the ones that rendered no "warn" and so were undiagnosable.
        val cases = mapOf(
            "durationMatches" to allPassing().copy(durationMatches = false),
            "frameCountMatches" to allPassing().copy(frameCountMatches = false),
            "fpsMatches" to allPassing().copy(fpsMatches = false),
            "locationMatches" to allPassing().copy(locationMatches = false),
            "mediaStoreDateMatches" to allPassing().copy(mediaStoreDateMatches = false),
            "mp4DateMatches" to allPassing().copy(mp4DateMatches = false)
        )
        cases.forEach { (name, checks) ->
            assertFalse(name, checks.passes(VerificationChecks.Scope.REMUX))
            assertEquals(name, listOf(name), checks.failures(VerificationChecks.Scope.REMUX))
        }
    }

    @Test
    fun remuxRequiresTheVideoCodecToBeUnchangedButNotTheBitrateFloor() {
        // A stream copy is a copy: the codec must not change. Its bitrate equals the source's by
        // definition, so the PL video-bitrate floor is not one of its gates.
        val codecChanged = allPassing().copy(videoCodecMatches = false)
        assertFalse(codecChanged.passes(VerificationChecks.Scope.REMUX))

        val floorFailed = allPassing().copy(videoBitratePass = false)
        assertTrue(floorFailed.passes(VerificationChecks.Scope.REMUX))
        assertFalse(floorFailed.passes(VerificationChecks.Scope.PERCEPTUAL_LOSSLESS))
        assertTrue(floorFailed.passes(VerificationChecks.Scope.PERCEPTUAL_LOSSLESS_EXCEPT_VIDEO_BITRATE))
    }

    @Test
    fun theBitrateFloorOnlyCaseIsIsolatable() {
        // The one failure that sampled pixel certification may legitimately re-judge.
        val c = allPassing().copy(videoBitratePass = false)
        assertEquals(
            listOf("videoBitratePass"),
            c.failures(VerificationChecks.Scope.PERCEPTUAL_LOSSLESS)
        )
    }

    @Test
    fun perceptualLosslessDoesNotRequireCodecParity() {
        // PL re-encodes, frequently cross-codec (AVC -> HEVC). Requiring codec parity would
        // reject every legitimate PL result.
        val c = allPassing().copy(videoCodecMatches = false)
        assertTrue(c.passes(VerificationChecks.Scope.PERCEPTUAL_LOSSLESS))
    }
}
