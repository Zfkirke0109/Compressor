package compress.joshattic.us

import android.media.MediaFormat
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchQualityBitratePolicyTest {

    // A 1080p source already below the lossy resolution floors: 3 Mbps video is under both the
    // High Quality 1080p floor (5 Mbps) and the Storage Saver 1080p floor (3.5 Mbps). Before the
    // floor-clamp fix, calculateVideoBitrate called coerceIn(floor, source) with min > max on these
    // branches and threw IllegalArgumentException. Now an already-below-floor source resolves to
    // "no shrink" (target == source), matching the Perceptually Lossless branch's existing guard.
    private fun lowBitrate1080pSource() = VideoSourceInfo(
        width = 1920,
        height = 1080,
        frameRate = 30f,
        durationMs = 60_000,
        totalBitrate = 3_000_000,
        audioPresent = false,
        videoMime = MimeTypes.VIDEO_H264,
        colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
        colorStandard = MediaFormat.COLOR_STANDARD_BT709
    )

    @Test
    fun highQualityBelowFloorSourceResolvesToNoShrinkInsteadOfThrowing() {
        val source = lowBitrate1080pSource()
        assertEquals(3_000_000, source.videoBitrate)

        // Must not throw (min > max on coerceIn) and must never target above the source bitrate.
        val target = BatchQualityBitratePolicy.calculateVideoBitrate(
            source = source,
            mode = BatchQualityMode.HIGH_QUALITY,
            outputMimeType = MimeTypes.VIDEO_H265
        )

        assertTrue("target must not exceed source video bitrate", target <= source.videoBitrate)
        // Already below the floor -> no shrink: target clamps back up to the source bitrate.
        assertEquals(source.videoBitrate, target)
    }

    @Test
    fun storageSaverBelowFloorSourceResolvesToNoShrinkInsteadOfThrowing() {
        val source = lowBitrate1080pSource()
        assertEquals(3_000_000, source.videoBitrate)

        val target = BatchQualityBitratePolicy.calculateVideoBitrate(
            source = source,
            mode = BatchQualityMode.STORAGE_SAVER,
            outputMimeType = MimeTypes.VIDEO_H265
        )

        assertTrue("target must not exceed source video bitrate", target <= source.videoBitrate)
        assertEquals(source.videoBitrate, target)
    }
}
