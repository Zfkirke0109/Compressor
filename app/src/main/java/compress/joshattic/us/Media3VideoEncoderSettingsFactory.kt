package compress.joshattic.us

import android.media.MediaCodecInfo
import androidx.media3.transformer.VideoEncoderSettings

internal object Media3VideoEncoderSettingsFactory {
    fun build(targetBitrate: Int, encoderMode: EncoderMode): VideoEncoderSettings {
        return VideoEncoderSettings.Builder().apply {
            if (encoderMode == EncoderMode.HIGH_QUALITY) {
                setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                experimentalSetEnableHighQualityTargeting(true)
            } else {
                setBitrate(targetBitrate)
                setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
        }.build()
    }
}
