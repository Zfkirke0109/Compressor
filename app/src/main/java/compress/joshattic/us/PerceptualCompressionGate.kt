package compress.joshattic.us

import android.content.SharedPreferences
import androidx.media3.common.MimeTypes
import kotlin.math.roundToInt

data class UsefulCompressionDecision(
    val keepEncode: Boolean,
    val usefulCompression: Boolean,
    val bytesSaved: Long,
    val percentSaved: Int,
    val minimumRequiredSavingsBytes: Long,
    val fallbackReason: String,
    val deletedOversizedEncode: Boolean
)

object PerceptualCompressionGate {
    private const val MIN_USEFUL_SAVINGS_BYTES = 1L * 1024L * 1024L
    private const val MAX_USEFUL_SAVINGS_BYTES = 32L * 1024L * 1024L
    private const val USEFUL_SAVINGS_RATIO = 0.02

    fun minimumUsefulSavingsBytes(sourceBytes: Long): Long {
        if (sourceBytes <= 0L) return MIN_USEFUL_SAVINGS_BYTES
        val ratioThreshold = (sourceBytes * USEFUL_SAVINGS_RATIO).toLong().coerceAtLeast(1L)
        return minOf(MAX_USEFUL_SAVINGS_BYTES, ratioThreshold).coerceAtLeast(MIN_USEFUL_SAVINGS_BYTES)
    }

    fun evaluate(sourceBytes: Long, encodedBytes: Long): UsefulCompressionDecision {
        val safeSourceBytes = sourceBytes.coerceAtLeast(0L)
        val safeEncodedBytes = encodedBytes.coerceAtLeast(0L)
        val bytesSaved = (safeSourceBytes - safeEncodedBytes).coerceAtLeast(0L)
        val percentSaved = if (safeSourceBytes > 0L) {
            ((bytesSaved.toDouble() / safeSourceBytes.toDouble()) * 100.0).roundToInt()
        } else {
            0
        }
        val minimumRequiredSavingsBytes = minimumUsefulSavingsBytes(safeSourceBytes)
        val usefulCompression = safeSourceBytes > 0L &&
            safeEncodedBytes < safeSourceBytes &&
            bytesSaved >= minimumRequiredSavingsBytes
        val fallbackReason = when {
            safeSourceBytes <= 0L -> "No useful Perceptually Lossless compression found; original left unchanged."
            safeEncodedBytes > safeSourceBytes -> "Source was already highly efficient; Perceptually Lossless re-encode would be larger."
            safeEncodedBytes == safeSourceBytes -> "No useful Perceptually Lossless compression found; original left unchanged."
            bytesSaved < minimumRequiredSavingsBytes -> "No useful compression; savings stayed below the minimum useful threshold."
            else -> "Perceptually Lossless compression kept."
        }
        return UsefulCompressionDecision(
            keepEncode = usefulCompression,
            usefulCompression = usefulCompression,
            bytesSaved = if (usefulCompression) bytesSaved else 0L,
            percentSaved = if (usefulCompression) percentSaved else 0,
            minimumRequiredSavingsBytes = minimumRequiredSavingsBytes,
            fallbackReason = fallbackReason,
            deletedOversizedEncode = !usefulCompression && safeEncodedBytes > 0L
        )
    }
}

data class CompressionProfileKey(
    val sourceCodec: String,
    val resolutionBucket: String,
    val fpsBucket: String,
    val hdrBucket: String,
    val bitrateBucket: String,
    val selectedMode: String,
    val outputCodec: String
)

data class CompressionOutcomeStats(
    val keptEncodeCount: Int = 0,
    val remuxFallbackCount: Int = 0,
    val noUsefulCompressionCount: Int = 0,
    val failedCount: Int = 0,
    val highQualityStrongSavingsCount: Int = 0
) {
    fun record(
        disposition: OutputDisposition,
        selectedMode: BatchQualityMode,
        bytesSaved: Long,
        sourceBytes: Long
    ): CompressionOutcomeStats {
        val strongHighQualitySavings = selectedMode == BatchQualityMode.HIGH_QUALITY &&
            sourceBytes > 0L &&
            bytesSaved >= maxOf(
                PerceptualCompressionGate.minimumUsefulSavingsBytes(sourceBytes),
                (sourceBytes * 0.10).toLong()
            )
        return copy(
            keptEncodeCount = keptEncodeCount + if (disposition == OutputDisposition.KEPT_ENCODE) 1 else 0,
            remuxFallbackCount = remuxFallbackCount + if (disposition == OutputDisposition.REMUX_FALLBACK) 1 else 0,
            noUsefulCompressionCount = noUsefulCompressionCount + if (disposition == OutputDisposition.NO_USEFUL_COMPRESSION) 1 else 0,
            failedCount = failedCount + if (disposition == OutputDisposition.FAILED) 1 else 0,
            highQualityStrongSavingsCount = highQualityStrongSavingsCount + if (strongHighQualitySavings) 1 else 0
        )
    }
}

class CompressionOutcomeHistory(
    private val statsByProfile: MutableMap<CompressionProfileKey, CompressionOutcomeStats> = mutableMapOf()
) {
    fun record(
        profile: CompressionProfileKey,
        disposition: OutputDisposition,
        selectedMode: BatchQualityMode,
        bytesSaved: Long,
        sourceBytes: Long
    ) {
        val updated = statsByProfile[profile].orEmpty().record(disposition, selectedMode, bytesSaved, sourceBytes)
        statsByProfile[profile] = updated
    }

    fun statsFor(profile: CompressionProfileKey): CompressionOutcomeStats {
        return statsByProfile[profile].orEmpty()
    }

    fun serialize(): String {
        return statsByProfile.entries.joinToString("\n") { (key, value) ->
            listOf(
                key.sourceCodec,
                key.resolutionBucket,
                key.fpsBucket,
                key.hdrBucket,
                key.bitrateBucket,
                key.selectedMode,
                key.outputCodec,
                value.keptEncodeCount.toString(),
                value.remuxFallbackCount.toString(),
                value.noUsefulCompressionCount.toString(),
                value.failedCount.toString(),
                value.highQualityStrongSavingsCount.toString()
            ).joinToString(FIELD_SEPARATOR)
        }
    }

    companion object {
        private const val FIELD_SEPARATOR = "\u001F"

        fun deserialize(raw: String?): CompressionOutcomeHistory {
            if (raw.isNullOrBlank()) return CompressionOutcomeHistory()
            val stats = mutableMapOf<CompressionProfileKey, CompressionOutcomeStats>()
            raw.lineSequence()
                .filter { it.isNotBlank() }
                .forEach { line ->
                    val fields = line.split(FIELD_SEPARATOR)
                    if (fields.size != 12) return@forEach
                    val key = CompressionProfileKey(
                        sourceCodec = fields[0],
                        resolutionBucket = fields[1],
                        fpsBucket = fields[2],
                        hdrBucket = fields[3],
                        bitrateBucket = fields[4],
                        selectedMode = fields[5],
                        outputCodec = fields[6]
                    )
                    stats[key] = CompressionOutcomeStats(
                        keptEncodeCount = fields[7].toIntOrNull() ?: 0,
                        remuxFallbackCount = fields[8].toIntOrNull() ?: 0,
                        noUsefulCompressionCount = fields[9].toIntOrNull() ?: 0,
                        failedCount = fields[10].toIntOrNull() ?: 0,
                        highQualityStrongSavingsCount = fields[11].toIntOrNull() ?: 0
                    )
                }
            return CompressionOutcomeHistory(stats)
        }
    }
}

private fun CompressionOutcomeStats?.orEmpty(): CompressionOutcomeStats = this ?: CompressionOutcomeStats()

class CompressionOutcomeHistoryStore(private val prefs: SharedPreferences) {
    fun load(): CompressionOutcomeHistory {
        return CompressionOutcomeHistory.deserialize(prefs.getString(PREF_KEY, null))
    }

    fun save(history: CompressionOutcomeHistory) {
        prefs.edit().putString(PREF_KEY, history.serialize()).apply()
    }

    companion object {
        private const val PREF_KEY = "batch_compression_outcome_history"
    }
}

object CompressionProfileBucketizer {
    fun build(
        source: VideoSourceInfo,
        selectedMode: BatchQualityMode,
        outputMime: String?
    ): CompressionProfileKey {
        return CompressionProfileKey(
            sourceCodec = codecLabel(source.videoMime),
            resolutionBucket = resolutionBucket(source.width, source.height),
            fpsBucket = fpsBucket(source.frameRate),
            hdrBucket = if (source.isHdr) "HDR" else "SDR/unknown",
            bitrateBucket = bitrateBucket(source.videoBitrate.takeIf { it > 0 } ?: source.totalBitrate),
            selectedMode = selectedMode.label,
            outputCodec = codecLabel(outputMime)
        )
    }

    private fun resolutionBucket(width: Int, height: Int): String {
        val maxEdge = maxOf(width, height)
        return when {
            maxEdge >= 7680 || height >= 4320 -> "8K"
            maxEdge >= 3840 || height >= 2160 -> "4K"
            maxEdge >= 1920 || height >= 1080 -> "1080p"
            else -> "sub-1080p"
        }
    }

    private fun fpsBucket(frameRate: Float): String {
        return when {
            frameRate >= 100f -> "120"
            frameRate >= 50f -> "60"
            else -> "30"
        }
    }

    private fun bitrateBucket(videoBitrate: Int): String {
        return when {
            videoBitrate >= 120_000_000 -> "120+Mbps"
            videoBitrate >= 80_000_000 -> "80-119Mbps"
            videoBitrate >= 40_000_000 -> "40-79Mbps"
            videoBitrate > 0 -> "<40Mbps"
            else -> "unknown"
        }
    }

    private fun codecLabel(mimeType: String?): String {
        return when (mimeType) {
            MimeTypes.VIDEO_H265 -> "HEVC"
            MimeTypes.VIDEO_H264 -> "H.264"
            MimeTypes.VIDEO_AV1 -> "AV1"
            null -> "unknown"
            else -> mimeType.substringAfter('/')
        }
    }
}

object SmartCompressionAdvisor {
    fun recommendation(
        item: BatchVideoItem,
        source: VideoSourceInfo,
        history: CompressionOutcomeHistory
    ): CompressionRecommendation? {
        val perceptualLosslessStats = history.statsFor(
            CompressionProfileBucketizer.build(
                source = source,
                selectedMode = BatchQualityMode.PERCEPTUAL_LOSSLESS,
                outputMime = MimeTypes.VIDEO_H265
            )
        )
        if (perceptualLosslessStats.noUsefulCompressionCount >= 2) {
            return CompressionRecommendation(
                title = "High Quality + HEVC",
                expectedSavings = "More likely to shrink",
                qualityRisk = "Low",
                reason = "Similar videos on this device usually do not shrink in Perceptually Lossless. Try High Quality for actual savings or Remux Only for zero quality loss.",
                qualityPreset = "High Quality",
                codecOption = "HEVC",
                frameRateOption = "Original"
            )
        }

        val highQualityStats = history.statsFor(
            CompressionProfileBucketizer.build(
                source = source,
                selectedMode = BatchQualityMode.HIGH_QUALITY,
                outputMime = MimeTypes.VIDEO_H265
            )
        )
        if (highQualityStats.highQualityStrongSavingsCount >= 2 && item.originalSize > 0L) {
            return CompressionRecommendation(
                title = "High Quality + HEVC",
                expectedSavings = "Strong savings on similar videos",
                qualityRisk = "Low",
                reason = "High Quality has repeatedly delivered meaningful savings for similar videos on this device.",
                qualityPreset = "High Quality",
                codecOption = "HEVC",
                frameRateOption = "Original"
            )
        }

        return null
    }
}
