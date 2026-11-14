package compress.joshattic.us

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Re-encoding AAC can only lose information, and the old policy re-encoded it UPWARD — 128 kbps
 * became 192 kbps in High Quality and 256 kbps in Perceptually Lossless — adding a lossy
 * generation and a bigger audio track. It was also the stage the 2026-09-01 trace caught spinning
 * (AudioGraph.ProducedOutput 17,922 times after every other stage had stopped).
 */
class AudioPassThroughPolicyTest {

    private val aac = "audio/mp4a-latm"

    @Test
    fun aacIsCopiedThroughInEveryModeThatWouldNotLowerIt() {
        for (mode in listOf(
            BatchQualityMode.PERCEPTUAL_LOSSLESS,
            BatchQualityMode.HIGH_QUALITY,
            BatchQualityMode.REMUX_ONLY
        )) {
            assertTrue("$mode", BatchQualityBitratePolicy.shouldPassThroughAudio(aac, 128_000, mode))
            assertTrue("$mode unknown bitrate", BatchQualityBitratePolicy.shouldPassThroughAudio(aac, 0, mode))
        }
    }

    @Test
    fun storageSaverStillLowersAHighBitrateTrack() {
        // A labelled lossy trade the user chose: 256 kbps is brought down, so it must re-encode.
        assertFalse(BatchQualityBitratePolicy.shouldPassThroughAudio(aac, 256_000, BatchQualityMode.STORAGE_SAVER))
        // Nothing to lower: a 96 kbps track would be pushed UP to 128 kbps — copy it instead.
        assertTrue(BatchQualityBitratePolicy.shouldPassThroughAudio(aac, 96_000, BatchQualityMode.STORAGE_SAVER))
        // "Lower" cannot be established without a bitrate, so keep the existing behaviour.
        assertFalse(BatchQualityBitratePolicy.shouldPassThroughAudio(aac, 0, BatchQualityMode.STORAGE_SAVER))
    }

    @Test
    fun nonAacAudioIsNeverPassedThrough() {
        // The MP4 output's audio is AAC; another codec cannot simply be copied into it.
        for (mime in listOf("audio/opus", "audio/ac3", "audio/raw", null)) {
            assertFalse("$mime", BatchQualityBitratePolicy.shouldPassThroughAudio(mime, 128_000, BatchQualityMode.HIGH_QUALITY))
        }
    }
}
