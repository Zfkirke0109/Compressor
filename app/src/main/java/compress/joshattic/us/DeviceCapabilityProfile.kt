package compress.joshattic.us

import android.os.Build
import androidx.media3.common.MimeTypes

/**
 * Centralizes device-specific defaults without hard-coding them throughout the
 * ViewModel. This keeps the normal MediaCodec capability checks as the source
 * of truth while letting known devices get safer presets.
 */
data class DeviceCapabilityProfile(
    val name: String,
    val isGalaxyS23Ultra: Boolean,
    val preferHevcForDefaultCompression: Boolean,
    val avoidAv1EncodingByDefault: Boolean,
    val recommendedBatchParallelism: Int,
    val notes: List<String> = emptyList(),
    val isGalaxyS26Ultra: Boolean = false
) {
    val presetFamily: String
        get() = when {
            isGalaxyS26Ultra -> "S26 Ultra"
            isGalaxyS23Ultra -> "S23 Ultra"
            else -> "Device"
        }

    fun chooseDefaultVideoCodec(supportedCodecs: List<String>): String {
        return when {
            preferHevcForDefaultCompression && supportedCodecs.contains(MimeTypes.VIDEO_H265) -> MimeTypes.VIDEO_H265
            supportedCodecs.contains(MimeTypes.VIDEO_H264) -> MimeTypes.VIDEO_H264
            supportedCodecs.isNotEmpty() -> supportedCodecs.first()
            else -> MimeTypes.VIDEO_H264
        }
    }

    fun chooseDefaultVideoCodec(supportedCodecs: List<String>, source: VideoSourceInfo?): String {
        if (source != null) {
            val preferHevcForSource = preferHevcForDefaultCompression &&
                (source.isHdr || source.height >= 2160 || source.width >= 3840 || source.frameRate >= 50f)
            if (preferHevcForSource && supportedCodecs.contains(MimeTypes.VIDEO_H265)) {
                return MimeTypes.VIDEO_H265
            }
        }
        return chooseDefaultVideoCodec(supportedCodecs)
    }
}

object DeviceCapabilityProfiles {
    fun current(): DeviceCapabilityProfile = forDevice(
        Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty(), Build.DEVICE.orEmpty(), Build.PRODUCT.orEmpty()
    )

    /** Model identity changes defaults only; actual encoder support is always queried at runtime. */
    fun forDevice(manufacturer: String, model: String, device: String = "", product: String = ""): DeviceCapabilityProfile {
        val isSamsung = manufacturer.equals("samsung", ignoreCase = true)
        if (isSamsung && model.startsWith("SM-S948", ignoreCase = true)) {
            return DeviceCapabilityProfile(
                name = "Samsung Galaxy S26 Ultra",
                isGalaxyS23Ultra = false,
                isGalaxyS26Ultra = true,
                preferHevcForDefaultCompression = true,
                avoidAv1EncodingByDefault = true,
                recommendedBatchParallelism = 1,
                notes = listOf(
                    "Probe the installed hardware encoders; do not inherit S23 Ultra rate-control assumptions.",
                    "Keep batch encodes sequential for sustained thermal and memory stability.",
                    "AV1 remains opt-in until its actual geometry and quality have been measured."
                )
            )
        }
        val isS23Ultra = isSamsung && (
            model.startsWith("SM-S918", ignoreCase = true) ||
                device.equals("dm3q", ignoreCase = true) ||
                product.contains("dm3q", ignoreCase = true)
            )

        if (isS23Ultra) {
            return DeviceCapabilityProfile(
                name = "Samsung Galaxy S23 Ultra",
                isGalaxyS23Ultra = true,
                preferHevcForDefaultCompression = true,
                avoidAv1EncodingByDefault = true,
                recommendedBatchParallelism = 1,
                notes = listOf(
                    "Prefer HEVC/H.265 for camera videos to keep quality high at smaller file sizes.",
                    "Run batch compression sequentially to avoid thermal throttling during long 4K/8K jobs.",
                    "Avoid AV1 as a default encode target even if a codec advertises support; keep it opt-in after device testing."
                )
            )
        }

        return DeviceCapabilityProfile(
            name = listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Android device" },
            isGalaxyS23Ultra = false,
            preferHevcForDefaultCompression = true,
            avoidAv1EncodingByDefault = false,
            recommendedBatchParallelism = 1
        )
    }
}
