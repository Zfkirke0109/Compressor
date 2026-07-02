package compress.joshattic.us

import kotlin.math.abs

internal enum class VerificationTransitionStatus {
    MATCH,
    MISMATCH,
    NOT_EXPOSED
}

internal object OutputVerificationFormatter {
    private const val REMUX_OUTPUT_FPS_NOT_EXPOSED = "remux output FPS was not exposed"

    fun transition(
        sourceLabel: String,
        outputLabel: String,
        status: VerificationTransitionStatus
    ): String {
        return when (status) {
            VerificationTransitionStatus.MATCH -> "$sourceLabel -> $outputLabel ok"
            VerificationTransitionStatus.MISMATCH -> "$sourceLabel -> $outputLabel warn"
            VerificationTransitionStatus.NOT_EXPOSED -> "$sourceLabel -> $outputLabel"
        }
    }

    fun fpsComparison(sourceFps: Float, outputFps: Float): VerificationTransitionStatus {
        if (sourceFps <= 0f || outputFps <= 0f) return VerificationTransitionStatus.NOT_EXPOSED
        return if (abs(sourceFps - outputFps) <= 1.0f) {
            VerificationTransitionStatus.MATCH
        } else {
            VerificationTransitionStatus.MISMATCH
        }
    }

    fun remuxFpsBlockReason(sourceFps: Float, outputFps: Float): String? {
        if (sourceFps <= 0f || outputFps <= 0f) {
            return if (sourceFps > 0f && outputFps <= 0f) {
                REMUX_OUTPUT_FPS_NOT_EXPOSED
            } else {
                "remux FPS was not exposed"
            }
        }

        return if (fpsComparison(sourceFps, outputFps) == VerificationTransitionStatus.MISMATCH) {
            "remux output changed FPS"
        } else {
            null
        }
    }
}
