package compress.joshattic.us

import androidx.media3.common.MimeTypes

internal data class BatchBitrateSource(
    val originalSize: Long,
    val originalBitrate: Int,
    val originalAudioBitrate: Int,
    val originalHeight: Int,
    val originalFps: Float,
    val durationMs: Long
)

internal object BatchQualityBitratePolicy {
    const val PERCEPTUAL_OVERSIZE_REMUX_FALLBACK_MESSAGE =
        "Source was already highly efficient; kept exact remux instead of larger re-encode."

    private const val PERCEPTUAL_OVERSIZE_TOLERANCE_RATIO = 0.01
    private const val PERCEPTUAL_OVERSIZE_TOLERANCE_BYTES = 16L * 1024L * 1024L

    fun audioBitrate(source: BatchBitrateSource, quality: BatchQualityPreset): Int {
        val original = if (source.originalAudioBitrate > 0) source.originalAudioBitrate else 192_000
        return when (quality) {
            BatchQualityPreset.REMUX_ONLY -> original
            BatchQualityPreset.PERCEPTUALLY_LOSSLESS -> original
            BatchQualityPreset.HIGH_QUALITY -> original.coerceAtMost(256_000)
            BatchQualityPreset.STORAGE_SAVER -> original.coerceAtMost(128_000)
        }
    }

    fun videoBitrate(source: BatchBitrateSource, quality: BatchQualityPreset, videoMimeType: String): Int {
        if (quality == BatchQualityPreset.REMUX_ONLY) {
            return source.originalBitrate.takeIf { it > 0 } ?: fallbackOriginalBitrate(source)
        }
        if (quality == BatchQualityPreset.PERCEPTUALLY_LOSSLESS) {
            val originalTotal = if (source.originalBitrate > 0) source.originalBitrate else fallbackOriginalBitrate(source)
            val audio = if (source.originalAudioBitrate > 0) source.originalAudioBitrate else 192_000
            val originalVideo = (originalTotal - audio).coerceAtLeast(originalTotal / 2)
            val ratio = if (videoMimeType == MimeTypes.VIDEO_H264) 0.95 else 0.88
            val target = (originalVideo * ratio).toInt()
            val floor = perceptualLosslessFloor(source, originalVideo)
            return target.coerceIn(floor, originalVideo)
        }

        val durationSec = (source.durationMs / 1000.0).coerceAtLeast(1.0)
        val targetBits = source.originalSize * 8.0 * quality.targetRatio
        val audioBits = durationSec * audioBitrate(source, quality).toDouble()
        val overheadBits = targetBits * 0.03
        val videoBits = (targetBits - audioBits - overheadBits).coerceAtLeast(targetBits * 0.20)
        val calculated = (videoBits / durationSec).toInt()
        val floor = when {
            source.originalHeight >= 2160 -> 3_500_000
            source.originalHeight >= 1440 -> 2_500_000
            source.originalHeight >= 1080 -> 1_500_000
            source.originalHeight >= 720 -> 900_000
            else -> 450_000
        }
        val ceiling = if (source.originalBitrate > 0) source.originalBitrate else 60_000_000
        return calculated.coerceIn(floor, ceiling)
    }

    fun targetHeight(source: BatchBitrateSource, quality: BatchQualityPreset): Int {
        return when (quality) {
            BatchQualityPreset.REMUX_ONLY -> source.originalHeight
            BatchQualityPreset.PERCEPTUALLY_LOSSLESS -> source.originalHeight
            BatchQualityPreset.HIGH_QUALITY -> source.originalHeight
            BatchQualityPreset.STORAGE_SAVER -> minOf(source.originalHeight, 720)
        }.coerceAtLeast(2)
    }

    fun shouldUseRemuxFallbackForPerceptualOutput(
        quality: BatchQualityPreset,
        baselineSize: Long,
        outputSize: Long
    ): Boolean {
        if (quality != BatchQualityPreset.PERCEPTUALLY_LOSSLESS) return false
        if (baselineSize <= 0L || outputSize <= 0L) return false
        val tolerance = perceptualOversizeToleranceBytes(baselineSize)
        return outputSize > baselineSize + tolerance
    }

    fun perceptualOversizeToleranceBytes(baselineSize: Long): Long {
        if (baselineSize <= 0L) return 0L
        val ratioTolerance = (baselineSize * PERCEPTUAL_OVERSIZE_TOLERANCE_RATIO).toLong()
        return minOf(ratioTolerance, PERCEPTUAL_OVERSIZE_TOLERANCE_BYTES)
    }

    private fun perceptualLosslessFloor(source: BatchBitrateSource, originalVideoBitrate: Int): Int {
        val floor = when {
            source.originalHeight >= 2160 && source.originalFps >= 50f -> 85_000_000
            source.originalHeight >= 2160 -> 55_000_000
            source.originalHeight >= 1440 -> 36_000_000
            source.originalHeight >= 1080 -> 22_000_000
            source.originalHeight >= 720 -> 10_000_000
            else -> 4_000_000
        }
        return minOf(floor, originalVideoBitrate)
    }

    private fun fallbackOriginalBitrate(source: BatchBitrateSource): Int {
        val base = when {
            source.originalHeight >= 2160 -> 50_000_000
            source.originalHeight >= 1440 -> 32_000_000
            source.originalHeight >= 1080 -> 20_000_000
            source.originalHeight >= 720 -> 10_000_000
            else -> 5_000_000
        }
        return if (source.originalFps >= 50f) (base * 1.35).toInt() else base
    }
}
