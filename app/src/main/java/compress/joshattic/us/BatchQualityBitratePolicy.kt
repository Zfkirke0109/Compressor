package compress.joshattic.us

import androidx.media3.common.MimeTypes
import kotlin.math.abs
import kotlin.math.max

enum class BatchQualityMode(val label: String) {
    REMUX_ONLY("Remux Only"),
    PERCEPTUAL_LOSSLESS("Perceptually Lossless"),
    HIGH_QUALITY("High Quality"),
    STORAGE_SAVER("Storage Saver");

    companion object {
        fun fromLabel(label: String): BatchQualityMode {
            return when (label.trim()) {
                REMUX_ONLY.label, "Remux only" -> REMUX_ONLY
                PERCEPTUAL_LOSSLESS.label, "Original" -> PERCEPTUAL_LOSSLESS
                HIGH_QUALITY.label, "High" -> HIGH_QUALITY
                STORAGE_SAVER.label, "Medium", "Low" -> STORAGE_SAVER
                else -> PERCEPTUAL_LOSSLESS
            }
        }
    }
}

enum class BatchFrameRateChoice(val label: String, val targetFps: Int?) {
    SOURCE("Original", null),
    FPS120("120 fps", 120),
    FPS60("60 fps", 60),
    FPS30("30 fps", 30),
    FPS24("24 fps", 24);

    companion object {
        fun fromLabel(label: String): BatchFrameRateChoice {
            return entries.firstOrNull { it.label == label } ?: SOURCE
        }
    }
}

enum class BatchCodecChoice(val label: String, val mimeType: String?) {
    AUTO("Auto", null),
    HEVC("HEVC", MimeTypes.VIDEO_H265),
    H264("H.264", MimeTypes.VIDEO_H264),
    AV1("AV1", MimeTypes.VIDEO_AV1);

    companion object {
        fun fromLabel(label: String): BatchCodecChoice {
            return entries.firstOrNull { it.label == label } ?: AUTO
        }
    }
}

data class VideoSourceInfo(
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
    val durationMs: Long = 0L,
    val totalBitrate: Int = 0,
    val audioBitrate: Int = 0,
    val videoMime: String? = null,
    val audioMime: String? = null,
    val colorTransfer: Int? = null,
    val colorStandard: Int? = null,
    val colorRange: Int? = null,
    val rotationDegrees: Int? = null,
    val audioChannelCount: Int? = null,
    val audioSampleRate: Int? = null,
    val audioPresent: Boolean = true,
    val locationPresent: Boolean = false,
    val mediaStoreDatePresent: Boolean = false,
    val mp4DatePresent: Boolean = false
) {
    val isHdr: Boolean
        get() = colorTransfer == android.media.MediaFormat.COLOR_TRANSFER_ST2084 ||
            colorTransfer == android.media.MediaFormat.COLOR_TRANSFER_HLG

    val videoBitrate: Int
        get() {
            if (totalBitrate <= 0) return 0
            if (!audioPresent) return totalBitrate
            val safeAudio = audioBitrate.coerceAtLeast(0)
            return (totalBitrate - safeAudio).coerceAtLeast(totalBitrate / 2)
        }
}

data class BatchModeProfile(
    val label: String,
    val honestSummary: String,
    val visibleLossWarning: String? = null,
    val allowsResolutionReduction: Boolean,
    val allowsFrameRateReduction: Boolean
)

object BatchQualityBitratePolicy {
    const val PERCEPTUAL_LOSSLESS_SIZE_TOLERANCE = 0.03

    fun modeProfile(mode: BatchQualityMode): BatchModeProfile {
        return when (mode) {
            BatchQualityMode.REMUX_ONLY -> BatchModeProfile(
                label = mode.label,
                honestSummary = "No re-encode. Video/audio streams are copied unchanged.",
                allowsResolutionReduction = false,
                allowsFrameRateReduction = false
            )
            BatchQualityMode.PERCEPTUAL_LOSSLESS -> BatchModeProfile(
                label = mode.label,
                honestSummary = "Conservative re-encode that must preserve resolution, FPS, HDR/color, and audio or fall back to Remux Only.",
                allowsResolutionReduction = false,
                allowsFrameRateReduction = false
            )
            BatchQualityMode.HIGH_QUALITY -> BatchModeProfile(
                label = mode.label,
                honestSummary = "Strong compression with measurable difference possible.",
                allowsResolutionReduction = false,
                allowsFrameRateReduction = false
            )
            BatchQualityMode.STORAGE_SAVER -> BatchModeProfile(
                label = mode.label,
                honestSummary = "Aggressive compression that prioritizes file size.",
                visibleLossWarning = "Visible quality loss may occur.",
                allowsResolutionReduction = true,
                allowsFrameRateReduction = true
            )
        }
    }

    fun outputFpsFor(sourceFps: Float, option: BatchFrameRateChoice): Int? {
        val target = option.targetFps ?: return null
        if (sourceFps <= 0f) return target
        val tolerance = when {
            sourceFps >= 110f -> 2.5f
            sourceFps >= 50f -> 1.0f
            else -> 0.5f
        }
        return if (sourceFps > target + tolerance) target else null
    }

    fun targetHeightFor(sourceHeight: Int, mode: BatchQualityMode): Int {
        return when (mode) {
            BatchQualityMode.REMUX_ONLY,
            BatchQualityMode.PERCEPTUAL_LOSSLESS,
            BatchQualityMode.HIGH_QUALITY -> sourceHeight
            BatchQualityMode.STORAGE_SAVER -> minOf(sourceHeight, 1080)
        }.coerceAtLeast(2)
    }

    fun calculateAudioBitrate(source: VideoSourceInfo, mode: BatchQualityMode): Int {
        val original = source.audioBitrate.takeIf { it > 0 } ?: 256_000
        return when (mode) {
            BatchQualityMode.REMUX_ONLY -> original
            BatchQualityMode.PERCEPTUAL_LOSSLESS -> max(original, 256_000)
            BatchQualityMode.HIGH_QUALITY -> original.coerceAtLeast(192_000).coerceAtMost(320_000)
            BatchQualityMode.STORAGE_SAVER -> original.coerceAtMost(160_000).coerceAtLeast(128_000)
        }
    }

    fun fallbackOriginalBitrate(source: VideoSourceInfo): Int {
        val base = when {
            source.height >= 4320 || source.width >= 7680 -> 120_000_000
            source.height >= 2160 || source.width >= 3840 -> 75_000_000
            source.height >= 1440 -> 32_000_000
            source.height >= 1080 -> 20_000_000
            source.height >= 720 -> 10_000_000
            else -> 5_000_000
        }
        val fpsScale = when {
            source.frameRate >= 110f -> 1.55
            source.frameRate >= 59f -> 1.25
            else -> 1.0
        }
        return (base * fpsScale).toInt()
    }

    fun calculateVideoBitrate(
        source: VideoSourceInfo,
        mode: BatchQualityMode,
        outputMimeType: String,
        outputFps: Int? = null,
        outputHeight: Int = source.height
    ): Int {
        val originalVideoBitrate = source.videoBitrate.takeIf { it > 0 } ?: fallbackOriginalBitrate(source)
        if (mode == BatchQualityMode.REMUX_ONLY) return originalVideoBitrate

        val fpsScale = outputFps?.let {
            (it.toFloat() / source.frameRate.coerceAtLeast(1f)).coerceIn(0.25f, 1f)
        } ?: 1f
        val heightScale = if (source.height > 0 && outputHeight > 0) {
            (outputHeight.toFloat() / source.height.toFloat()).coerceIn(0.25f, 1f)
        } else {
            1f
        }

        return when (mode) {
            BatchQualityMode.PERCEPTUAL_LOSSLESS -> {
                val targetRatio = when {
                    outputMimeType == MimeTypes.VIDEO_H265 && source.videoMime == MimeTypes.VIDEO_H264 -> 0.90
                    outputMimeType == source.videoMime -> 0.95
                    outputMimeType == MimeTypes.VIDEO_H265 -> 0.93
                    else -> 0.97
                }
                val floor = perceptualLosslessBitrateFloor(source)
                val target = (originalVideoBitrate * targetRatio).toInt()
                target.coerceIn(floor.coerceAtMost(originalVideoBitrate), originalVideoBitrate)
            }
            BatchQualityMode.HIGH_QUALITY -> {
                val ratio = 0.72f * fpsScale * heightScale
                val floor = when {
                    source.height >= 2160 -> 12_000_000
                    source.height >= 1440 -> 8_000_000
                    source.height >= 1080 -> 5_000_000
                    source.height >= 720 -> 2_500_000
                    else -> 1_000_000
                }
                (originalVideoBitrate * ratio).toInt().coerceIn(floor, originalVideoBitrate)
            }
            BatchQualityMode.STORAGE_SAVER -> {
                val ratio = 0.42f * fpsScale * heightScale
                val floor = when {
                    outputHeight >= 2160 -> 8_000_000
                    outputHeight >= 1080 -> 3_500_000
                    outputHeight >= 720 -> 1_800_000
                    else -> 900_000
                }
                (originalVideoBitrate * ratio).toInt().coerceIn(floor, originalVideoBitrate)
            }
            BatchQualityMode.REMUX_ONLY -> originalVideoBitrate
        }
    }

    fun perceptualLosslessBitrateFloor(source: VideoSourceInfo): Int {
        val absoluteFloor = when {
            source.height >= 4320 || source.width >= 7680 -> 100_000_000
            source.height >= 2160 || source.width >= 3840 -> 48_000_000
            source.height >= 1440 -> 18_000_000
            source.height >= 1080 -> 10_000_000
            source.height >= 720 -> 6_000_000
            else -> 3_000_000
        }
        val ratioFloor = when {
            source.isHdr && source.frameRate >= 110f -> 0.90
            source.isHdr && (source.height >= 4320 || source.width >= 7680) -> 0.90
            source.isHdr && (source.height >= 2160 || source.width >= 3840) && source.frameRate >= 59f -> 0.80
            source.isHdr && (source.height >= 2160 || source.width >= 3840) -> 0.75
            source.frameRate >= 110f -> 0.88
            source.frameRate >= 59f && (source.height >= 2160 || source.width >= 3840) -> 0.74
            source.frameRate >= 59f -> 0.68
            source.height >= 2160 || source.width >= 3840 -> 0.62
            else -> 0.55
        }
        val sourceVideoBitrate = source.videoBitrate.takeIf { it > 0 } ?: fallbackOriginalBitrate(source)
        return max(absoluteFloor, (sourceVideoBitrate * ratioFloor).toInt()).coerceAtMost(sourceVideoBitrate)
    }

    fun estimateOutputSize(
        source: VideoSourceInfo,
        mode: BatchQualityMode,
        outputMimeType: String,
        frameRateChoice: BatchFrameRateChoice
    ): Long {
        if (mode == BatchQualityMode.REMUX_ONLY) {
            val durationSec = (source.durationMs / 1000.0).coerceAtLeast(1.0)
            val bitrate = (source.totalBitrate.takeIf { it > 0 } ?: fallbackOriginalBitrate(source)).toDouble()
            return ((bitrate * durationSec) / 8.0).toLong().coerceAtLeast(1L)
        }
        val durationSec = (source.durationMs / 1000.0).coerceAtLeast(1.0)
        val outputFps = outputFpsFor(source.frameRate, frameRateChoice)
        val outputHeight = targetHeightFor(source.height, mode)
        val videoBitrate = calculateVideoBitrate(source, mode, outputMimeType, outputFps, outputHeight)
        val audioBitrate = calculateAudioBitrate(source, mode)
        val estimatedBits = (videoBitrate + audioBitrate).toDouble() * durationSec
        val containerOverhead = estimatedBits * 0.04
        return ((estimatedBits + containerOverhead) / 8.0).toLong().coerceAtLeast(1L)
    }

    fun fpsMatches(sourceFps: Float, outputFps: Float): Boolean {
        if (sourceFps <= 0f || outputFps <= 0f) return false
        val tolerance = max(1f, sourceFps * 0.03f)
        return abs(sourceFps - outputFps) <= tolerance
    }
}
