package compress.joshattic.us

import android.media.MediaFormat
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Media3 has one BT.601 colour space, so a BT.601 NTSC source is always written as PAL. */
class Bt601VariantColorTest {

    private fun probe(standard: Int?) = OutputVerifier.TrackProbe(
        videoCodec = MimeTypes.VIDEO_H264, audioCodec = null, videoBitrate = 400_000, audioBitrate = 0,
        colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO, colorStandard = standard,
        colorRange = MediaFormat.COLOR_RANGE_LIMITED, audioChannelCount = null, audioSampleRate = null,
        videoFrameRate = 25f
    )

    @Test
    fun ntscToPalPassesForAPerceptuallyLosslessReEncode() {
        val c = OutputVerifier.compareColorTransition(
            BatchQualityMode.PERCEPTUAL_LOSSLESS,
            probe(MediaFormat.COLOR_STANDARD_BT601_NTSC),
            probe(MediaFormat.COLOR_STANDARD_BT601_PAL)
        )
        assertTrue(c.standardMatches)
        assertTrue(c.standardIsBt601Variant)
    }

    @Test
    fun aRemuxMustStillCopyTheTagExactly() {
        val c = OutputVerifier.compareColorTransition(
            BatchQualityMode.REMUX_ONLY,
            probe(MediaFormat.COLOR_STANDARD_BT601_NTSC),
            probe(MediaFormat.COLOR_STANDARD_BT601_PAL)
        )
        assertFalse(c.standardMatches)
    }

    @Test
    fun aRealMatrixChangeStillFails() {
        val c = OutputVerifier.compareColorTransition(
            BatchQualityMode.PERCEPTUAL_LOSSLESS,
            probe(MediaFormat.COLOR_STANDARD_BT601_NTSC),
            probe(MediaFormat.COLOR_STANDARD_BT709)
        )
        assertFalse(c.standardMatches)
        assertFalse(c.standardIsBt601Variant)
    }

    @Test
    fun anUntaggedSourceIsNotTreatedAsBt601() {
        // Deliberately unchanged: VMAF v0.6.1 scores luma only, so it could not catch a matrix
        // mistake on an untagged SD source.
        val c = OutputVerifier.compareColorTransition(
            BatchQualityMode.PERCEPTUAL_LOSSLESS,
            probe(null),
            probe(MediaFormat.COLOR_STANDARD_BT601_PAL)
        )
        assertFalse(c.standardMatches)
    }
}
