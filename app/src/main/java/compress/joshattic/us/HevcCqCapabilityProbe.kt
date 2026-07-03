package compress.joshattic.us

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import androidx.media3.common.MimeTypes

internal object HevcCqCapabilityProbe {
    private const val LOG_TAG = "CompressorBatch"
    private const val TARGET_MODEL = "SM-S918U1"
    private const val TARGET_BUILD_DISPLAY = "BP4A.251205.006.S918U1UES8FZF5"

    @Volatile
    private var logged = false

    fun logIfTargetValidationBuild() {
        if (logged || !isTargetValidationBuild()) return
        logged = true

        val codecInfo = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                info.isEncoder &&
                    info.name.equals("c2.qti.hevc.encoder", ignoreCase = true) &&
                    info.supportedTypes.any { it.equals(MimeTypes.VIDEO_H265, ignoreCase = true) }
            }
        }.getOrNull()

        if (codecInfo == null) {
            Log.i(
                LOG_TAG,
                "CQ capability: build=${Build.DISPLAY}, encoder=c2.qti.hevc.encoder not exposed"
            )
            return
        }

        val capabilities = runCatching {
            codecInfo.getCapabilitiesForType(MimeTypes.VIDEO_H265).encoderCapabilities
        }.getOrNull()

        if (capabilities == null) {
            Log.i(
                LOG_TAG,
                "CQ capability: build=${Build.DISPLAY}, encoder=${codecInfo.name}, capabilities not exposed"
            )
            return
        }

        Log.i(
            LOG_TAG,
            "CQ capability: build=${Build.DISPLAY}, encoder=${codecInfo.name}, " +
                "cq=${capabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)}, " +
                "vbr=${capabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)}, " +
                "cbr=${capabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)}"
        )
    }

    private fun isTargetValidationBuild(): Boolean {
        return Build.MODEL.equals(TARGET_MODEL, ignoreCase = true) &&
            Build.DISPLAY.contains(TARGET_BUILD_DISPLAY, ignoreCase = true)
    }
}
