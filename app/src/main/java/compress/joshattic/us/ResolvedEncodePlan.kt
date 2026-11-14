package compress.joshattic.us

import java.util.Locale

/**
 * The encode actually about to run, resolved once and read by everything that describes it: the
 * encoder request, the row's estimate and the `CompressorEncoderPlan` log line.
 *
 * Before this, three places computed their own version. In b169 (`job_d127463b57d6`, ratio 0.75)
 * the encoder requested 2,197,440 bps and copied the 128 kbps AAC track, the size gate predicted
 * 247,616,391 bytes, and the output was 246,118,100 bytes. The plan log said 2,490,432 bps and a
 * 256 kbps audio target (it left out the pixel-proven ratio floor and did not know audio would be
 * copied), and the row showed "est 302.5 MB", from the codec-default bitrate, a 256 kbps audio
 * request and 4 % padding.
 *
 * This is description, not policy: the requested bitrate comes from the same
 * [BatchQualityBitratePolicy.calculateVideoBitrate] call the encoder uses, the PL estimate from the
 * same [BatchQualityBitratePolicy.predictedPerceptualLosslessBytes] call the size gate uses, and no
 * gate reads anything here.
 */
data class ResolvedEncodePlan(
    val mode: BatchQualityMode,
    val outputMime: String,
    /** The ratio the plan asked for (learned, default or proven), or null for the mode default. */
    val targetRatio: Double?,
    /** The pixel-proven ratio, which also acts as a floor on the request. */
    val pixelProvenRatioFloor: Double?,
    val requestedVideoBitrate: Int,
    val audio: AudioPlan,
    val durationMs: Long,
    val estimatedBytes: Long,
    val estimate: EstimateBasis
) {
    sealed interface AudioPlan {
        /** The source's compressed audio is copied unchanged. */
        data class Copy(val sourceBitrate: Int) : AudioPlan
        /** Audio is decoded and re-encoded at [requestedBitrate]. */
        data class Reencode(val requestedBitrate: Int) : AudioPlan
        /** The source has no audio track. */
        object None : AudioPlan

        /** Bits per second the estimate counts for audio. */
        val estimateBitrate: Int
            get() = when (this) {
                is Copy -> sourceBitrate.coerceAtLeast(0)
                is Reencode -> requestedBitrate.coerceAtLeast(0)
                None -> 0
            }

        fun describe(): String = when (this) {
            is Copy -> "copy(source=${sourceBitrate}bps)"
            is Reencode -> "reencode(request=${requestedBitrate}bps)"
            None -> "none"
        }
    }

    /**
     * How [estimatedBytes] was made. [overshootPreClamp] is what the evidence said (for a size
     * gate, [compress.joshattic.us.quality.MeasuredOvershoot.forPrediction]); [overshootUsed] is
     * what the prediction multiplied by, after its clamp to 1.0-2.0. b169 row 110 logged "used=0.885"
     * while the prediction used 1.0.
     */
    data class EstimateBasis(
        val kind: Kind,
        val overshootPreClamp: Double?,
        val overshootUsed: Double,
        val containerFactor: Double
    ) {
        enum class Kind(val wire: String) {
            /** Before probing: the mode default or learned ratio. Replaced once the plan resolves. */
            PROVISIONAL("provisional"),
            /** The size gate's own prediction for the proven ratio. */
            SIZE_GATE_PREDICTION("size_gate_prediction"),
            /** Requested bitrates x duration, no measured overshoot. */
            REQUESTED_BITRATES("requested_bitrates")
        }

        fun describe(): String = buildString {
            append(kind.wire)
            append(";overshootUsed=").append(fmt(overshootUsed))
            overshootPreClamp?.let { append(";overshootPreClamp=").append(fmt(it)) }
            append(";container=x").append(fmt(containerFactor))
        }
    }

    val isProvisional: Boolean get() = estimate.kind == EstimateBasis.Kind.PROVISIONAL

    /** The `CompressorEncoderPlan` fields, in one place. */
    fun describe(): String =
        "outputMime=$outputMime; targetRatio=${targetRatio?.let(::fmt) ?: "default"}; " +
            "pixelProvenRatioFloor=${pixelProvenRatioFloor?.let(::fmt) ?: "none"}; " +
            "requestedVideoBitrate=$requestedVideoBitrate; audio=${audio.describe()}; durationMs=$durationMs; " +
            "estimatedBytes=$estimatedBytes; estimate[${estimate.describe()}]"

    companion object {
        /** Padding the older generic estimate added; kept for non-PL modes so their estimate does not jump. */
        const val GENERIC_CONTAINER_FACTOR = 1.04
        /** The factor inside [BatchQualityBitratePolicy.predictedPerceptualLosslessBytes]. */
        const val PL_PREDICTION_CONTAINER_FACTOR = 1.01

        /** The encoder's own rule ([BatchQualityBitratePolicy.shouldPassThroughAudio]), applied to the same source. */
        fun audioPlan(source: VideoSourceInfo, mode: BatchQualityMode): AudioPlan = when {
            source.audioMime == null -> AudioPlan.None
            BatchQualityBitratePolicy.shouldPassThroughAudio(source.audioMime, source.audioBitrate, mode) ->
                AudioPlan.Copy(source.audioBitrate)
            else -> AudioPlan.Reencode(BatchQualityBitratePolicy.calculateAudioBitrate(source, mode))
        }

        /**
         * Resolve the plan the encoder will run.
         *
         * @param overshootPreClamp the overshoot the size gate used, when the size gate ran for
         *   this plan; the estimate is then exactly the gate's prediction. Null otherwise.
         * @param provisional true before the probes have decided the ratio.
         */
        fun resolve(
            source: VideoSourceInfo,
            mode: BatchQualityMode,
            outputMime: String,
            outputFps: Int?,
            outputHeight: Int,
            targetRatio: Double?,
            pixelProvenRatioFloor: Double?,
            overshootPreClamp: Double? = null,
            provisional: Boolean = false
        ): ResolvedEncodePlan {
            val video = BatchQualityBitratePolicy.calculateVideoBitrate(
                source = source,
                mode = mode,
                outputMimeType = outputMime,
                outputFps = outputFps,
                outputHeight = outputHeight,
                learnedTargetRatio = targetRatio,
                pixelProvenRatioFloor = pixelProvenRatioFloor
            )
            val audio = audioPlan(source, mode)
            val durationSec = (source.durationMs / 1000.0).coerceAtLeast(1.0)
            val (bytes, basis) = if (mode == BatchQualityMode.PERCEPTUAL_LOSSLESS && overshootPreClamp != null) {
                val predicted = BatchQualityBitratePolicy.predictedPerceptualLosslessBytes(
                    source = source,
                    outputMimeType = outputMime,
                    learnedTargetRatio = targetRatio,
                    expectedOvershootFactor = overshootPreClamp,
                    pixelProvenRatioFloor = pixelProvenRatioFloor
                )
                predicted to EstimateBasis(
                    kind = EstimateBasis.Kind.SIZE_GATE_PREDICTION,
                    overshootPreClamp = overshootPreClamp,
                    overshootUsed = clampOvershoot(overshootPreClamp),
                    containerFactor = PL_PREDICTION_CONTAINER_FACTOR
                )
            } else {
                val factor = if (mode == BatchQualityMode.PERCEPTUAL_LOSSLESS) PL_PREDICTION_CONTAINER_FACTOR else GENERIC_CONTAINER_FACTOR
                val bits = (video.toDouble() + audio.estimateBitrate) * durationSec
                ((bits * factor) / 8.0).toLong().coerceAtLeast(1L) to EstimateBasis(
                    kind = if (provisional) EstimateBasis.Kind.PROVISIONAL else EstimateBasis.Kind.REQUESTED_BITRATES,
                    overshootPreClamp = null,
                    overshootUsed = 1.0,
                    containerFactor = factor
                )
            }
            return ResolvedEncodePlan(
                mode = mode,
                outputMime = outputMime,
                targetRatio = targetRatio,
                pixelProvenRatioFloor = pixelProvenRatioFloor,
                requestedVideoBitrate = video,
                audio = audio,
                durationMs = source.durationMs,
                estimatedBytes = bytes,
                estimate = basis
            )
        }

        /** The clamp inside [BatchQualityBitratePolicy.predictedPerceptualLosslessBytes], stated where it is reported. */
        fun clampOvershoot(factor: Double): Double = if (factor.isFinite()) factor.coerceIn(1.0, 2.0) else 1.0

        private fun fmt(v: Double) = String.format(Locale.US, "%.3f", v)
    }
}
