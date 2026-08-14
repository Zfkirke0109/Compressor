package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Media3 format fallback silently substitutes an unsupported MIME/resolution and still reports
 * success. A resolution substitution would trip `videoMatches`, which is inside the Perceptually
 * Lossless verification scope, so it is a candidate explanation for the 0% output pass rate seen
 * in both 219-job captures — testable only once requested and actual are recorded together.
 */
class EncoderConfigDeltaTest {

    private fun delta(
        reqMime: String? = "video/hevc",
        actMime: String? = "video/hevc",
        reqW: Int = 1920, reqH: Int = 1080,
        actW: Int = 1920, actH: Int = 1080,
        reqBr: Int = 16_000_000,
        actBr: Int = 15_700_000
    ) = EncoderConfigDelta(
        requestedMime = reqMime, actualMime = actMime,
        requestedWidth = reqW, requestedHeight = reqH,
        actualWidth = actW, actualHeight = actH,
        requestedVideoBitrate = reqBr, actualAverageVideoBitrate = actBr,
        requestedBitrateMode = "CBR", encoderName = "c2.qti.hevc.encoder"
    )

    @Test
    fun anHonouredRequestReportsNoFallback() {
        val d = delta()
        assertFalse(d.mimeChanged)
        assertFalse(d.resolutionChanged)
        assertFalse(d.formatFellBack)
    }

    @Test
    fun aCodecSubstitutionIsDetected() {
        val d = delta(reqMime = "video/hevc", actMime = "video/avc")
        assertTrue(d.mimeChanged)
        assertTrue(d.formatFellBack)
        assertTrue(d.compact().contains("FALLBACK=mime"))
    }

    @Test
    fun aResolutionSubstitutionIsDetected() {
        val d = delta(reqW = 3840, reqH = 2160, actW = 1920, actH = 1080)
        assertTrue(d.resolutionChanged)
        assertTrue(d.formatFellBack)
        assertTrue(d.compact().contains("FALLBACK=resolution"))
    }

    @Test
    fun bothSubstitutionsAreReportedTogether() {
        val d = delta(reqMime = "video/hevc", actMime = "video/avc", reqW = 3840, reqH = 2160, actW = 1920, actH = 1080)
        assertTrue(d.compact().contains("FALLBACK=mime+resolution"))
    }

    @Test
    fun aTransposedPortraitEncodeIsNotAResolutionSubstitution() {
        // Media3 landscape-encodes portrait sources and writes a compensating quarter-turn hint;
        // the displayed frame is unchanged and OutputVerifier.sameDisplayOrientation accepts it.
        // Three clips in batch_1786651683689 were flagged FALLBACK=resolution for exactly this.
        for ((w, h) in listOf(1080 to 1920, 480 to 1030, 676 to 1080)) {
            val d = delta(reqW = w, reqH = h, actW = h, actH = w)
            assertTrue("$w x $h should read as transposed", d.orientationTransposed)
            assertFalse(d.resolutionChanged)
            assertFalse(d.formatFellBack)
            assertFalse(d.compact().contains("FALLBACK"))
            // Still visible in the capture — suppressed from the fallback signal, not from the record.
            assertTrue(d.compact().contains(";coded=transposed"))
        }
    }

    @Test
    fun aTransposedGeometryThatAlsoRescalesIsStillASubstitution() {
        // 1080x1920 -> 1080x1920-transposed is 1920x1080. Anything else changes the picture.
        val d = delta(reqW = 1080, reqH = 1920, actW = 1280, actH = 720)
        assertFalse(d.orientationTransposed)
        assertTrue(d.resolutionChanged)
        assertTrue(d.compact().contains("FALLBACK=resolution"))
    }

    @Test
    fun aSquareRequestCannotBeTransposed() {
        val d = delta(reqW = 1080, reqH = 1080, actW = 1080, actH = 1080)
        assertFalse(d.orientationTransposed)
        assertFalse(d.resolutionChanged)
        assertFalse(d.compact().contains(";coded=transposed"))
    }

    @Test
    fun unknownValuesAreNotReportedAsSubstitutions() {
        // Absent information is not evidence of a fallback. Treating it as one would manufacture
        // false positives in reports, which is worse than reporting nothing.
        assertFalse(delta(actMime = null).mimeChanged)
        assertFalse(delta(reqMime = null).mimeChanged)
        assertFalse(delta(actW = 0, actH = 0).resolutionChanged)
        assertFalse(delta(reqW = 0, reqH = 0).resolutionChanged)
        assertFalse(delta(actW = 0, actH = 0).formatFellBack)
    }

    @Test
    fun mimeComparisonIsCaseInsensitive() {
        assertFalse(delta(reqMime = "video/HEVC", actMime = "video/hevc").mimeChanged)
    }

    @Test
    fun bitrateRatioMeasuresOvershootAndUndershoot() {
        assertEquals(1.25, delta(reqBr = 100_000_000, actBr = 125_000_000).bitrateRatio!!, 1e-9)
        assertEquals(0.96, delta(reqBr = 100_000_000, actBr = 96_000_000).bitrateRatio!!, 1e-9)
        assertNull(delta(reqBr = 0).bitrateRatio)
        assertNull(delta(actBr = 0).bitrateRatio)
    }

    @Test
    fun bitrateDeviationAloneIsNotAFallback() {
        // VBR encoders always miss their target somewhat. Folding that into the fallback signal
        // would flag nearly every encode and destroy its usefulness.
        val d = delta(reqBr = 16_000_000, actBr = 20_000_000)
        assertEquals(1.25, d.bitrateRatio!!, 1e-9)
        assertFalse(d.formatFellBack)
        assertFalse(d.compact().contains("FALLBACK"))
    }

    @Test
    fun compactIsLocalePinned() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val c = delta(reqBr = 100_000_000, actBr = 125_000_000).compact()
            assertTrue("decimal must stay a dot: $c", c.contains("ratio=1.250"))
        } finally {
            java.util.Locale.setDefault(original)
        }
    }
}
