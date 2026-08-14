package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rejected output must say WHICH check rejected it.
 *
 * Motivated by a real capture: the 2026-08-13 S23 Ultra batch discarded two outputs that the probe
 * ladder had pixel-proven at ratio 0.65 with VMAF 98.9/97.0/91.0, and the log recorded only
 * "Perceptually Lossless Verification Failed" — no field, no reason, nothing to act on.
 */
class VerificationFailureDiagnosticsTest {

    private fun report(
        audioBitrate: String = "128 kbps -> 128 kbps ok",
        audioCodec: String = "aac -> aac ok",
        fps: String = "30 -> 30 ok",
        durationParity: String = "10.0s -> 10.0s ok"
    ) = OutputVerificationReport(
        verdict = "Perceptually Lossless Verification Failed",
        playability = "opens",
        video = "1920x1080 -> 1920x1080 ok",
        fps = fps,
        videoBitrate = "25 Mbps -> 15 Mbps ok",
        videoCodec = "avc -> hevc ok",
        audioCodec = audioCodec,
        audioDetails = "48000/2 -> 48000/2 ok",
        audioBitrate = audioBitrate,
        hdr = "SDR -> SDR ok",
        colorStandard = "BT709 -> BT709 ok",
        colorRange = "limited -> limited ok",
        mediaStoreDate = "preserved",
        mp4Date = "preserved",
        location = "preserved",
        rotation = "0 -> 0 ok",
        fileSize = "31 MB -> 20 MB ok",
        replacementSafe = false,
        verified = false,
        durationParity = durationParity
    )

    @Test
    fun anAllPassingReportNamesNoFailures() {
        assertTrue(report().failingChecks().isEmpty())
    }

    @Test
    fun aFailingFieldIsNamed() {
        val r = report(audioBitrate = "128 kbps -> 0 kbps warn")
        assertEquals(listOf("audioBitrate"), r.failingChecks())
    }

    @Test
    fun everyFailingFieldIsNamedNotJustTheFirst() {
        val r = report(
            audioBitrate = "128 kbps -> 0 kbps warn",
            audioCodec = "aac -> none warn",
            fps = "30 -> 29 warn"
        )
        val failing = r.failingChecks()
        assertTrue(failing.containsAll(listOf("fps", "audioCodec", "audioBitrate")))
        assertEquals(3, failing.size)
    }

    @Test
    fun freeTextMetadataFieldsWithoutAStatusSuffixNeverCountAsFailures() {
        // "preserved" carries no ok/warn suffix; treating it as a failure would bury the real one.
        val r = report()
        assertTrue("mediaStoreDate" !in r.failingChecks())
        assertTrue("location" !in r.failingChecks())
    }

    @Test
    fun trailingWhitespaceDoesNotHideAFailure() {
        val r = report(audioBitrate = "128 kbps -> 0 kbps warn  ")
        assertEquals(listOf("audioBitrate"), r.failingChecks())
    }

    @Test
    fun aFieldMerelyContainingTheWordWarnIsNotAFailure() {
        // Only the trailing status suffix decides; substring matching would produce false positives.
        val r = report(audioCodec = "aac (no warning) -> aac ok")
        assertTrue(r.failingChecks().isEmpty())
    }
}
