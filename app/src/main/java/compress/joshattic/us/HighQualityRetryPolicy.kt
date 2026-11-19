package compress.joshattic.us

/** Estimates whether an explicitly lossy, source-resolution, source-FPS retry is worth offering. */
object HighQualityRetryPolicy {

    const val HIGH_QUALITY_RATIO = BatchQualityBitratePolicy.HIGH_QUALITY_RATIO
    const val MIN_HEADROOM_FRACTION = 0.15
    const val MIN_ABSOLUTE_SAVING_BYTES = 25L * 1024L * 1024L
    // A 471.9 MB 4K source in the 2026-07-31 batch really saved 50.7 MB despite a modest
    // fractional reduction. Keep this second path for substantial absolute savings on big files.
    const val MIN_HEADROOM_FRACTION_FOR_LARGE_FILES = 0.10

    /**
     * Estimate the change to the whole file from the requested video and audio rates. Carry
     * unexplained source/container bytes forward rather than counting them as savings. A tag
     * that overstates the rate is also bounded by the actual file size and duration.
     *
     * A copied AAC track needs its source bitrate. A re-encode has a known requested bitrate,
     * and a silent file has no audio cost. The encoder and the finished mux can differ from the
     * estimate; only the actual output can count as a compression.
     */
    fun predictedSavingFraction(source: VideoSourceInfo, sourceSizeBytes: Long): Double {
        if (sourceSizeBytes <= 0L || source.durationMs <= 0L || source.videoBitrate <= 0 ||
            source.width <= 0 || source.height <= 0) return 0.0
        val audio = ResolvedEncodePlan.audioPlan(source, BatchQualityMode.HIGH_QUALITY)
        if (audio is ResolvedEncodePlan.AudioPlan.Copy && audio.sourceBitrate <= 0) return 0.0
        val targetVideo = BatchQualityBitratePolicy.highQualityTargetVideoBitrate(source)
        val requestedRate = targetVideo.toDouble() + audio.estimateBitrate
        val fileRate = sourceSizeBytes * 8000.0 / source.durationMs
        val sourceRate = minOf(fileRate, source.totalBitrate.toDouble())
        return ((sourceRate - requestedRate) / fileRate).coerceIn(0.0, 1.0)
    }

    fun highQualityWouldShrink(source: VideoSourceInfo, sourceSizeBytes: Long): Boolean =
        qualifies(predictedSavingFraction(source, sourceSizeBytes), sourceSizeBytes)

    private fun qualifies(fraction: Double, sourceSizeBytes: Long): Boolean =
        sourceSizeBytes > 0L && (fraction >= MIN_HEADROOM_FRACTION ||
            (fraction >= MIN_HEADROOM_FRACTION_FOR_LARGE_FILES &&
                sourceSizeBytes * fraction >= MIN_ABSOLUTE_SAVING_BYTES))

    /** Compatibility for video-only rate calculations whose supplied height is a resolution tier. */
    fun highQualityTargetVideoBitrate(sourceVideoBitrate: Int, sourceHeight: Int): Int =
        BatchQualityBitratePolicy.highQualityTargetVideoBitrate(
            VideoSourceInfo(width = sourceHeight, height = sourceHeight), sourceVideoBitrate
        )

    fun predictedSavingFraction(sourceVideoBitrate: Int, sourceHeight: Int): Double {
        if (sourceVideoBitrate <= 0) return 0.0
        val target = highQualityTargetVideoBitrate(sourceVideoBitrate, sourceHeight)
        return (sourceVideoBitrate - target).toDouble() / sourceVideoBitrate
    }

    fun highQualityWouldShrink(sourceVideoBitrate: Int, sourceHeight: Int, sourceSizeBytes: Long): Boolean =
        qualifies(predictedSavingFraction(sourceVideoBitrate, sourceHeight), sourceSizeBytes)

    /** Only these kept-original outcomes receive the automatic suggestion. */
    fun terminalIsRetryCandidate(terminal: BatchTerminalResult?): Boolean = when (terminal) {
        BatchTerminalResult.TRANSCODED_NOT_MEANINGFULLY_SMALLER,
        BatchTerminalResult.ALREADY_HIGHLY_OPTIMIZED,
        BatchTerminalResult.REMUX_PREFERRED_BY_EVIDENCE,
        BatchTerminalResult.UNEXPECTED_REMUX -> true
        else -> false
    }

    fun isEligible(
        batchWasPerceptualLossless: Boolean,
        terminal: BatchTerminalResult?,
        source: VideoSourceInfo,
        sourceSizeBytes: Long
    ): Boolean = batchWasPerceptualLossless && terminalIsRetryCandidate(terminal) &&
        highQualityWouldShrink(source, sourceSizeBytes)

    fun isEligible(
        batchWasPerceptualLossless: Boolean,
        terminal: BatchTerminalResult?,
        sourceVideoBitrate: Int,
        sourceHeight: Int,
        sourceSizeBytes: Long
    ): Boolean = batchWasPerceptualLossless && terminalIsRetryCandidate(terminal) &&
        highQualityWouldShrink(sourceVideoBitrate, sourceHeight, sourceSizeBytes)
}
