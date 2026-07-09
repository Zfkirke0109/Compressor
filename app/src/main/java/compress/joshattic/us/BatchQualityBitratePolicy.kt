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

    // Minimum predicted savings before a Perceptually Lossless re-encode is worth attempting.
    // Below this, the safe bitrate is so close to the source that Remux Only is the honest choice.
    const val MIN_PERCEPTUAL_LOSSLESS_PREDICTED_SAVINGS = 0.03

    // Perceptually Lossless targets may never exceed this fraction of the source video bitrate;
    // above it the re-encode cannot meaningfully shrink the file and remux should win instead.
    const val PERCEPTUAL_LOSSLESS_MAX_TARGET_RATIO = 0.97

    private const val HDR_120_RATIO_FLOOR = 0.90
    private const val HDR_4K60_RATIO_FLOOR = 0.80
    private const val HDR_4K_RATIO_FLOOR = 0.75
    private const val FPS120_RATIO_FLOOR = 0.88
    private const val FPS60_4K_RATIO_FLOOR = 0.74
    private const val FPS60_RATIO_FLOOR = 0.68
    private const val UHD_RATIO_FLOOR = 0.62
    private const val DEFAULT_RATIO_FLOOR = 0.55

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

    /**
     * Mode-aware FPS planning. Remux Only copies timestamps unchanged and Perceptually Lossless
     * must preserve source FPS/motion (including 120fps), so both ignore the FPS option entirely.
     * Only the lossy modes (High Quality / Storage Saver) honor an explicit FPS cap.
     */
    fun plannedOutputFps(sourceFps: Float, mode: BatchQualityMode, option: BatchFrameRateChoice): Int? {
        return when (mode) {
            BatchQualityMode.REMUX_ONLY,
            BatchQualityMode.PERCEPTUAL_LOSSLESS -> null
            BatchQualityMode.HIGH_QUALITY,
            BatchQualityMode.STORAGE_SAVER -> outputFpsFor(sourceFps, option)
        }
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

    // Resolution classing must be orientation-agnostic: a portrait 1440x2560 clip is QHD-class,
    // not 4K-class, even though its raw height exceeds 2160. Long/short edge, not width/height.
    private fun longEdge(source: VideoSourceInfo): Int = maxOf(source.width, source.height)
    private fun shortEdge(source: VideoSourceInfo): Int = minOf(source.width, source.height)
    internal fun is8kClass(source: VideoSourceInfo): Boolean =
        longEdge(source) >= 7680 || shortEdge(source) >= 4320
    internal fun is4kClass(source: VideoSourceInfo): Boolean =
        longEdge(source) >= 3840 || shortEdge(source) >= 2160

    fun fallbackOriginalBitrate(source: VideoSourceInfo): Int {
        val base = when {
            is8kClass(source) -> 120_000_000
            is4kClass(source) -> 75_000_000
            longEdge(source) >= 2560 -> 32_000_000
            longEdge(source) >= 1920 -> 20_000_000
            longEdge(source) >= 1280 -> 10_000_000
            else -> 5_000_000
        }
        val fpsScale = when {
            source.frameRate >= 110f -> 1.55
            source.frameRate >= 59f -> 1.25
            else -> 1.0
        }
        return (base * fpsScale).toInt()
    }

    /**
     * Default Perceptually Lossless target as a fraction of the source video bitrate,
     * before any local learning adjustment. Kept deliberately conservative.
     */
    fun perceptualLosslessDefaultTargetRatio(source: VideoSourceInfo, outputMimeType: String): Double {
        return when {
            outputMimeType == MimeTypes.VIDEO_H265 && source.videoMime == MimeTypes.VIDEO_H264 -> 0.90
            outputMimeType == source.videoMime -> 0.95
            outputMimeType == MimeTypes.VIDEO_H265 -> 0.93
            else -> 0.97
        }
    }

    fun calculateVideoBitrate(
        source: VideoSourceInfo,
        mode: BatchQualityMode,
        outputMimeType: String,
        outputFps: Int? = null,
        outputHeight: Int = source.height,
        learnedTargetRatio: Double? = null
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
                val defaultRatio = perceptualLosslessDefaultTargetRatio(source, outputMimeType)
                // A learned ratio can only move the target inside [safety floor, max ratio];
                // verification remains the final gate regardless of what was learned.
                val targetRatio = learnedTargetRatio
                    ?.coerceIn(perceptualLosslessRatioFloor(source), PERCEPTUAL_LOSSLESS_MAX_TARGET_RATIO)
                    ?: defaultRatio
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

    /**
     * The ratio floor (fraction of source video bitrate) that Perceptually Lossless may never go
     * below. 120fps floors are deliberately higher than 60fps floors, and HDR floors are higher
     * than SDR floors, because those sources degrade visibly first.
     */
    fun perceptualLosslessRatioFloor(source: VideoSourceInfo): Double {
        // These ratio floors intentionally stay conservative for S23-class camera clips so
        // Perceptually Lossless never targets the kind of large bitrate drops that caused visible
        // degradation on 4K/8K HDR and 120fps footage.
        return when {
            source.isHdr && source.frameRate >= 110f -> HDR_120_RATIO_FLOOR
            source.isHdr && is8kClass(source) -> HDR_120_RATIO_FLOOR
            source.isHdr && is4kClass(source) && source.frameRate >= 59f -> HDR_4K60_RATIO_FLOOR
            source.isHdr && is4kClass(source) -> HDR_4K_RATIO_FLOOR
            source.frameRate >= 110f -> FPS120_RATIO_FLOOR
            source.frameRate >= 59f && is4kClass(source) -> FPS60_4K_RATIO_FLOOR
            source.frameRate >= 59f -> FPS60_RATIO_FLOOR
            is4kClass(source) -> UHD_RATIO_FLOOR
            else -> DEFAULT_RATIO_FLOOR
        }
    }

    fun perceptualLosslessBitrateFloor(source: VideoSourceInfo): Int {
        val absoluteFloor = when {
            is8kClass(source) -> 100_000_000
            is4kClass(source) -> 48_000_000
            longEdge(source) >= 2560 -> 18_000_000
            longEdge(source) >= 1920 -> 10_000_000
            longEdge(source) >= 1280 -> 6_000_000
            else -> 3_000_000
        }
        val ratioFloor = perceptualLosslessRatioFloor(source)
        val sourceVideoBitrate = source.videoBitrate.takeIf { it > 0 } ?: fallbackOriginalBitrate(source)
        return max(absoluteFloor, (sourceVideoBitrate * ratioFloor).toInt()).coerceAtMost(sourceVideoBitrate)
    }

    /**
     * Predicted output bytes for a Perceptually Lossless encode at the given (or default) target
     * ratio. Uses a small realistic container overhead rather than the UI estimate's padding, so
     * the remux-vs-encode decision is not biased toward remux by an inflated estimate.
     *
     * [expectedOvershootFactor] scales the predicted video bitrate by the encoder's measured
     * request-vs-actual behavior (the S23 Ultra HEVC VBR path measured ~1.25x on 4K60 HDR).
     * A requested bitrate is not a guarantee; prediction must use what the encoder actually does.
     */
    fun predictedPerceptualLosslessBytes(
        source: VideoSourceInfo,
        outputMimeType: String,
        learnedTargetRatio: Double? = null,
        expectedOvershootFactor: Double = 1.0
    ): Long {
        val durationSec = (source.durationMs / 1000.0).coerceAtLeast(1.0)
        val videoBitrate = calculateVideoBitrate(
            source = source,
            mode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
            outputMimeType = outputMimeType,
            learnedTargetRatio = learnedTargetRatio
        )
        val overshoot = if (expectedOvershootFactor.isFinite()) expectedOvershootFactor.coerceIn(1.0, 2.0) else 1.0
        // Perceptually Lossless transmuxes (stream-copies) audio when possible, so predicted audio
        // bytes use the source audio bitrate rather than the re-encode request.
        val audioBitrate = source.audioBitrate.coerceAtLeast(0)
        val estimatedBits = (videoBitrate * overshoot + audioBitrate) * durationSec
        return ((estimatedBits * 1.01) / 8.0).toLong().coerceAtLeast(1L)
    }

    /**
     * True when the safe Perceptually Lossless bitrate cannot shrink the file meaningfully
     * (predicted savings below [MIN_PERCEPTUAL_LOSSLESS_PREDICTED_SAVINGS]). In that case the
     * honest action is Remux Only: the source is already near optimal, so keep the exact
     * stream copy instead of spending a re-encode that verification would likely discard.
     */
    fun shouldPreferRemuxForPerceptualLossless(
        source: VideoSourceInfo,
        outputMimeType: String,
        sourceSizeBytes: Long,
        learnedTargetRatio: Double? = null,
        expectedOvershootFactor: Double = 1.0
    ): Boolean {
        if (sourceSizeBytes <= 0L) return false
        val predicted = predictedPerceptualLosslessBytes(
            source,
            outputMimeType,
            learnedTargetRatio,
            expectedOvershootFactor
        )
        val maxUseful = (sourceSizeBytes * (1.0 - MIN_PERCEPTUAL_LOSSLESS_PREDICTED_SAVINGS)).toLong()
        return predicted >= maxUseful
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
        val outputFps = plannedOutputFps(source.frameRate, mode, frameRateChoice)
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
