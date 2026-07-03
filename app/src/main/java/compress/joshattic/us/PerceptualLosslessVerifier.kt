package compress.joshattic.us

import kotlin.math.abs

enum class PerceptualLosslessStatus(val reportLabel: String) {
    PL_VERIFIED("PL Verified"),
    PL_UNVERIFIED("PL Unverified"),
    REMUX_KEPT("Remux Kept")
}

internal data class PerceptualLosslessVerificationInput(
    val playable: Boolean,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val sourceFps: Float,
    val outputFps: Float,
    val sourceVideoBitrate: Int,
    val outputVideoBitrate: Int,
    val sourceHdrLike: Boolean,
    val sourceColorTransfer: Int?,
    val outputColorTransfer: Int?,
    val sourceColorStandard: Int?,
    val outputColorStandard: Int?,
    val sourceRotationDegrees: Int?,
    val outputRotationDegrees: Int?,
    val sourceAudioCodec: String?,
    val outputAudioCodec: String?,
    val fourKSixtyHdr: Boolean
)

internal data class PerceptualLosslessVerificationResult(
    val status: PerceptualLosslessStatus,
    val reason: String
)

internal object PerceptualLosslessVerifier {
    private const val FOUR_K_SIXTY_HDR_MIN_OUTPUT_BITRATE_RATIO = 0.90

    fun verify(input: PerceptualLosslessVerificationInput): PerceptualLosslessVerificationResult {
        val reason = firstFailureReason(input)
        return if (reason == null) {
            PerceptualLosslessVerificationResult(
                status = PerceptualLosslessStatus.PL_VERIFIED,
                reason = "Perceptually Lossless checks passed."
            )
        } else {
            PerceptualLosslessVerificationResult(
                status = PerceptualLosslessStatus.PL_UNVERIFIED,
                reason = reason
            )
        }
    }

    private fun firstFailureReason(input: PerceptualLosslessVerificationInput): String? {
        if (!input.playable) return "output did not open"
        if (!dimensionsMatch(input)) return "resolution changed"
        if (!fpsPreserved(input.sourceFps, input.outputFps)) return "FPS was not preserved"
        if (!audioPreserved(input.sourceAudioCodec, input.outputAudioCodec)) return "audio track was not preserved"
        if (!rotationPreserved(input.sourceRotationDegrees, input.outputRotationDegrees)) return "rotation was not preserved"
        if (!colorPreserved(input)) return "HDR/color metadata was not preserved"
        if (!fourKSixtyHdrBitratePreserved(input)) {
            return "4K60 HDR output bitrate dropped below 90% of source video bitrate"
        }
        return null
    }

    private fun dimensionsMatch(input: PerceptualLosslessVerificationInput): Boolean {
        if (input.sourceWidth <= 0 || input.sourceHeight <= 0) return false
        return input.sourceWidth == input.outputWidth && input.sourceHeight == input.outputHeight
    }

    private fun fpsPreserved(sourceFps: Float, outputFps: Float): Boolean {
        if (sourceFps <= 0f || outputFps <= 0f) return false
        return abs(sourceFps - outputFps) <= 1.0f
    }

    private fun audioPreserved(sourceAudioCodec: String?, outputAudioCodec: String?): Boolean {
        if (sourceAudioCodec == null) return true
        return outputAudioCodec != null && sourceAudioCodec.equals(outputAudioCodec, ignoreCase = true)
    }

    private fun rotationPreserved(sourceRotation: Int?, outputRotation: Int?): Boolean {
        if (sourceRotation == null) return true
        return outputRotation != null && sourceRotation == outputRotation
    }

    private fun colorPreserved(input: PerceptualLosslessVerificationInput): Boolean {
        if (!input.sourceHdrLike && input.sourceColorTransfer == null && input.sourceColorStandard == null) {
            return true
        }
        val transferPreserved = input.sourceColorTransfer == null ||
            input.outputColorTransfer == input.sourceColorTransfer
        val standardPreserved = input.sourceColorStandard == null ||
            input.outputColorStandard == input.sourceColorStandard
        return transferPreserved && standardPreserved
    }

    private fun fourKSixtyHdrBitratePreserved(input: PerceptualLosslessVerificationInput): Boolean {
        if (!input.fourKSixtyHdr) return true
        if (input.sourceVideoBitrate <= 0 || input.outputVideoBitrate <= 0) return false
        return input.outputVideoBitrate.toDouble() / input.sourceVideoBitrate.toDouble() >=
            FOUR_K_SIXTY_HDR_MIN_OUTPUT_BITRATE_RATIO
    }
}
