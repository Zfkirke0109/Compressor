package compress.joshattic.us

import java.util.Locale

enum class MetadataPrivacyMode(
    val label: String,
    val description: String,
    val removeDate: Boolean,
    val removeLocation: Boolean
) {
    PRESERVE_ALL(
        "Preserve all metadata",
        "Keep source date and location where Android allows.",
        removeDate = false,
        removeLocation = false
    ),
    REMOVE_LOCATION(
        "Remove location only",
        "Keep dates, omit GPS/location metadata.",
        removeDate = false,
        removeLocation = true
    ),
    REMOVE_DATE(
        "Remove date only",
        "Keep location, omit source date/time metadata.",
        removeDate = true,
        removeLocation = false
    ),
    REMOVE_DATE_LOCATION(
        "Remove location + date",
        "Omit source date/time and GPS/location metadata.",
        removeDate = true,
        removeLocation = true
    ),
    KEEP_TECHNICAL(
        "Keep camera/video technical metadata",
        "Keep codec, rotation, and color behavior; omit private date/location.",
        removeDate = true,
        removeLocation = true
    ),
    PRIVACY_SHARING(
        "Privacy mode for sharing",
        "Prepare a sharing copy without source date or GPS/location.",
        removeDate = true,
        removeLocation = true
    );

    val summary: String
        get() = when {
            removeDate && removeLocation -> "source date/location omitted"
            removeDate -> "source date omitted"
            removeLocation -> "source location omitted"
            else -> "source metadata preserved"
        }

    companion object {
        fun fromLabel(label: String): MetadataPrivacyMode {
            return entries.firstOrNull { it.label == label } ?: PRESERVE_ALL
        }
    }
}

enum class BatchPresetOption(val label: String) {
    S23_BEST("S23 Ultra Best Quality"),
    S23_STORAGE("S23 Ultra Storage Saver"),
    SOCIAL("Social Upload"),
    ARCHIVE("Archive Mode"),
    HDR_SAFE("HDR Safe Mode");

    companion object {
        fun fromLabel(label: String): BatchPresetOption? {
            return entries.firstOrNull { it.label == label }
        }
    }
}

data class CompressionRecommendation(
    val title: String,
    val expectedSavings: String,
    val qualityRisk: String,
    val reason: String,
    val qualityPreset: String,
    val codecOption: String,
    val frameRateOption: String
) {
    val summary: String
        get() = "Recommended: $title • Expected savings: $expectedSavings • Quality risk: $qualityRisk"
}

data class OutputVerificationReport(
    val playability: String,
    val video: String,
    val fps: String,
    val videoCodec: String,
    val audioCodec: String,
    val audioBitrate: String,
    val hdr: String,
    val date: String,
    val location: String,
    val rotation: String,
    val fileSize: String,
    val replacementSafe: Boolean,
    val replacementBlockReason: String? = null
) {
    val summaryLines: List<String>
        get() = listOf(
            "Playability: $playability",
            "Video: $video",
            "FPS: $fps",
            "Codec: $videoCodec",
            "Audio: $audioCodec",
            "Audio bitrate: $audioBitrate",
            "HDR/color: $hdr",
            "Date: $date",
            "Location: $location",
            "Rotation: $rotation",
            "Size: $fileSize"
        )
}

data class BatchItemMetrics(
    val operationLabel: String,
    val elapsedMs: Long,
    val outputBytes: Long,
    val savedBytes: Long,
    val thermalStart: String,
    val thermalEnd: String,
    val batteryStart: Int?,
    val batteryEnd: Int?,
    val cooldownMs: Long
) {
    val elapsedSeconds: Double get() = elapsedMs.coerceAtLeast(1L) / 1000.0
    val speedLabel: String
        get() {
            val mb = outputBytes / (1024.0 * 1024.0)
            return String.format(Locale.US, "%.1f MB/min", mb / elapsedSeconds * 60.0)
        }
    val savingsPerMinuteLabel: String
        get() {
            val mb = savedBytes.coerceAtLeast(0L) / (1024.0 * 1024.0)
            return String.format(Locale.US, "%.1f MB saved/min", mb / elapsedSeconds * 60.0)
        }
    val batterySummary: String
        get() = "${batteryStart?.let { "$it%" } ?: "not available"} -> ${batteryEnd?.let { "$it%" } ?: "not available"}"
    val summary: String
        get() = "$operationLabel: ${String.format(Locale.US, "%.1fs", elapsedSeconds)} • $speedLabel • $savingsPerMinuteLabel • thermal $thermalStart -> $thermalEnd • battery $batterySummary"
}

data class BatchMetricsSummary(
    val totalElapsedMs: Long,
    val totalCooldownMs: Long,
    val doneCount: Int,
    val failedCount: Int,
    val skippedCount: Int,
    val totalSavedBytes: Long
) {
    val summaryLines: List<String>
        get() = listOf(
            "Elapsed: ${String.format(Locale.US, "%.1f min", totalElapsedMs.coerceAtLeast(0L) / 60000.0)}",
            "Cooldown used: ${totalCooldownMs / 1000}s",
            "Saved: ${formatFileSize(totalSavedBytes.coerceAtLeast(0L))}",
            "Done: $doneCount • Failed: $failedCount • Skipped: $skippedCount"
        )
}

fun VideoMetadataSnapshot.filteredForPrivacy(mode: MetadataPrivacyMode): VideoMetadataSnapshot {
    return copy(
        dateTakenMs = if (mode.removeDate) null else dateTakenMs,
        dateModifiedSeconds = if (mode.removeDate) null else dateModifiedSeconds,
        dateAddedSeconds = if (mode.removeDate) null else dateAddedSeconds,
        rawDateTag = if (mode.removeDate) null else rawDateTag,
        dateSource = if (mode.removeDate) null else dateSource,
        latitude = if (mode.removeLocation) null else latitude,
        longitude = if (mode.removeLocation) null else longitude,
        rawLocationTag = if (mode.removeLocation) null else rawLocationTag
    )
}
