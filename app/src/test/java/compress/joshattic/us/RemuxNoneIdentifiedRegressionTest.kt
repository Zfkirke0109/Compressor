package compress.joshattic.us

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the contradiction observed on SM-S918U1, build 9d0913f, batch_1786641623934:
 *
 *   verification=Remux Verification Failed; criticalFieldsComplete=true;
 *   failedOnlyOnVideoBitrateFloor=false; failing=none-identified
 *
 * A FAILED verdict that names no failing predicate is unacceptable — it makes the rejection
 * undiagnosable and indistinguishable from a bug in the verifier itself.
 *
 * Root cause: remuxVerified ANDs 17 predicates, but only 10 are rendered into a field carrying
 * statusSuffix() ("ok"/"warn"). The other seven fail invisibly:
 *   durationMatches and frameCountMatches      - rendered nowhere at all
 *   fpsComparison == NOT_EXPOSED               - transition() emits no suffix for NOT_EXPOSED
 *   locationMatches, mediaStoreDateMatches,
 *   mp4DateMatches                             - their labels say "unverified", never "warn"
 *
 * So a diagnostic derived from rendered display text can never see them.
 */
class RemuxNoneIdentifiedRegressionTest {

    /** Every rendered field passing, exactly as the device reported. */
    private fun reportWith(
        mediaStoreDate: String = "verified",
        mp4Date: String = "verified",
        location: String = "verified",
        fps: String = "30 -> 30 ok"
    ) = OutputVerificationReport(
        verdict = "Remux Verification Failed",
        playability = "opens",
        video = "1920x1080 -> 1920x1080 ok",
        fps = fps,
        videoBitrate = "25 Mbps -> 25 Mbps",
        videoCodec = "avc -> avc ok",
        audioCodec = "none -> none ok",
        audioDetails = "n/a -> n/a ok",
        audioBitrate = "n/a -> n/a ok",
        hdr = "SDR -> SDR ok",
        colorStandard = "BT709 -> BT709 ok",
        colorRange = "limited -> limited ok",
        mediaStoreDate = mediaStoreDate,
        mp4Date = mp4Date,
        location = location,
        rotation = "0 -> 0 ok",
        fileSize = "31 MB -> 31 MB",
        replacementSafe = false,
        criticalFieldsComplete = true,
        verified = false,
        failedOnlyOnVideoBitrateFloor = false
    )

    @Test
    fun aFailedVerdictMustAlwaysNameAtLeastOneFailingCheck() {
        // The device case: metadata parity lost on remux. Renders as "unverified", not "warn".
        val r = reportWith(mediaStoreDate = "unverified")
        assertFalse("precondition: this report is a failure", r.verified)
        assertTrue(
            "a failed verdict named no failing check - this is the none-identified contradiction",
            r.failingChecks().isNotEmpty()
        )
    }

    @Test
    fun lostMp4DateIsNamed() {
        assertTrue(reportWith(mp4Date = "unverified").failingChecks().isNotEmpty())
    }

    @Test
    fun lostLocationIsNamed() {
        assertTrue(reportWith(location = "unverified").failingChecks().isNotEmpty())
    }

    @Test
    fun fpsNotExposedIsNamed() {
        // transition() emits no suffix for NOT_EXPOSED, yet fpsComparison != MATCH fails the verdict.
        assertTrue(reportWith(fps = "30 -> unknown").failingChecks().isNotEmpty())
    }
}
