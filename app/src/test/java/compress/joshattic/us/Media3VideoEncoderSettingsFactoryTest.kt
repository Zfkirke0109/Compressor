package compress.joshattic.us

import android.media.MediaCodecInfo
import androidx.media3.transformer.VideoEncoderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3VideoEncoderSettingsFactoryTest {
    @Test
    fun highQualityModeUsesMedia3SupportedVbrWithoutFixedBitrate() {
        val settings = Media3VideoEncoderSettingsFactory.build(
            targetBitrate = 12_000_000,
            encoderMode = EncoderMode.HIGH_QUALITY
        )

        assertEquals(VideoEncoderSettings.NO_VALUE, settings.bitrate)
        assertEquals(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR, settings.bitrateMode)
        assertTrue(settings.enableHighQualityTargeting)
    }

    @Test
    fun fallbackModeUsesHighBitrateVbr() {
        val settings = Media3VideoEncoderSettingsFactory.build(
            targetBitrate = 12_000_000,
            encoderMode = EncoderMode.VBR_FALLBACK
        )

        assertEquals(12_000_000, settings.bitrate)
        assertEquals(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR, settings.bitrateMode)
        assertFalse(settings.enableHighQualityTargeting)
    }
}
