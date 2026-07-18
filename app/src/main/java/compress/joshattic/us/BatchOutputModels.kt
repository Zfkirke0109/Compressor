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
    val verdict: String,
    val playability: String,
    val video: String,
    val fps: String,
    val videoBitrate: String,
    val videoCodec: String,
    val audioCodec: String,
    val audioDetails: String,
    val audioBitrate: String,
    val hdr: String,
    val colorStandard: String,
    val colorRange: String,
    val mediaStoreDate: String,
    val mp4Date: String,
    val location: String,
    val rotation: String,
    val fileSize: String,
    val replacementSafe: Boolean,
    val replacementBlockReason: String? = null,
    val criticalFieldsComplete: Boolean = false,
    val verified: Boolean = false,
    val durationParity: String = "",
    // True only when a Perceptually Lossless output failed SOLELY on the inferred video
    // bitrate floor — every structural, color, audio, timing, and metadata check passed.
    // That one case may be re-judged by sampled pixel certification (measured pixels
    // outrank the inferred floor); any other failure combination is terminal.
    val failedOnlyOnVideoBitrateFloor: Boolean = false,
    // True ONLY when sampled on-device VMAF actually measured this output against its source and
    // those measured windows passed. It is NOT set by probe eligibility, and NOT set when
    // certification was merely unavailable (decoder/geometry/alignment failure) and the encode was
    // accepted structurally at the codec-default ratio. Defaults false so any path that does not
    // explicitly prove pixels stays honest — the "Verified" wording is chosen from this flag.
    val pixelCertified: Boolean = false
) {
    val summaryLines: List<String>
        get() = listOf(
            "Verdict: $verdict",
            "Playability: $playability",
            "Video: $video",
            "FPS: $fps",
            "Video bitrate: $videoBitrate",
            "Codec: $videoCodec",
            "Audio: $audioCodec",
            "Audio details: $audioDetails",
            "Audio bitrate: $audioBitrate",
            "HDR/color: $hdr",
            "Color standard: $colorStandard",
            "Color range: $colorRange",
            "MediaStore date: $mediaStoreDate",
            "MP4/retriever date: $mp4Date",
            "Location: $location",
            "Rotation: $rotation",
            "Size: $fileSize"
        ) + (if (durationParity.isNotBlank()) listOf("Duration/frames: $durationParity") else emptyList())

    /**
     * Records whether sampled pixel scoring ACTUALLY certified this output, and qualifies the
     * Perceptually Lossless success wording accordingly.
     *
     * Only the unqualified PL success verdict is ever rewritten: remux, lossy-mode, "Unverified" and
     * failure verdicts pass through untouched. A verdict is never UPGRADED — passing
     * [pixelCertified] = true cannot turn a structural/failed verdict into a pixel-certified one; it
     * only preserves the existing full claim when pixels genuinely backed it. This keeps the strong
     * wording reserved for outputs whose pixels were measured and passed.
     */
    fun withCertificationBasis(pixelCertified: Boolean): OutputVerificationReport {
        if (verdict != PERCEPTUALLY_LOSSLESS_VERIFIED) return copy(pixelCertified = pixelCertified)
        return copy(
            pixelCertified = pixelCertified,
            verdict = if (pixelCertified) PERCEPTUALLY_LOSSLESS_VERIFIED else PERCEPTUALLY_LOSSLESS_STRUCTURAL_ONLY
        )
    }

    companion object {
        /** Full claim: structural checks passed AND sampled pixels were measured and passed. */
        const val PERCEPTUALLY_LOSSLESS_VERIFIED = "Perceptually Lossless Verified"

        /**
         * Structural checks passed but NO pixels were sampled (source above the VMAF scoring cap,
         * VMAF unavailable on this device/ABI, or certification produced no measured evidence). The
         * encode is still accepted under the existing rules — this wording just refuses to imply
         * perceptual proof that was never obtained.
         */
        const val PERCEPTUALLY_LOSSLESS_STRUCTURAL_ONLY =
            "Perceptually Lossless — structural checks only (pixels not sampled)"
    }
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
    val processedCount: Int,
    val realCompressionCount: Int,
    val nonCompressionCount: Int,
    val failedCount: Int,
    val skippedCount: Int,
    val cancelledCount: Int,
    val totalSavedBytes: Long
) {
    val summaryLines: List<String>
        get() = listOf(
            "Elapsed: ${String.format(Locale.US, "%.1f min", totalElapsedMs.coerceAtLeast(0L) / 60000.0)}",
            "Cooldown used: ${totalCooldownMs / 1000}s",
            "Saved by real compression: ${formatFileSize(totalSavedBytes.coerceAtLeast(0L))}",
            "Processed: $processedCount • Real compressions: $realCompressionCount",
            "Remuxed/kept/no size win: $nonCompressionCount",
            "Failed: $failedCount • Skipped: $skippedCount • Cancelled: $cancelledCount"
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
