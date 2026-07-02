package compress.joshattic.us

import kotlin.math.abs

internal enum class VerificationComparison {
    OK,
    WARN,
    NEUTRAL
}

internal object OutputVerificationFormatter {
    private const val REMUX_OUTPUT_FPS_NOT_EXPOSED = "remux output FPS was not exposed"

    fun transition(source: String, output: String, comparison: VerificationComparison): String {
        val suffix = when (comparison) {
            VerificationComparison.OK -> " ok"
            VerificationComparison.WARN -> " warn"
            VerificationComparison.NEUTRAL -> ""
        }
        return "$source -> $output$suffix"
    }

    fun exposedComparison(sourceExposed: Boolean, outputExposed: Boolean, matches: Boolean): VerificationComparison {
        return when {
            !sourceExposed || !outputExposed -> VerificationComparison.NEUTRAL
            matches -> VerificationComparison.OK
            else -> VerificationComparison.WARN
        }
    }

    fun fpsComparison(sourceFps: Float, outputFps: Float): VerificationComparison {
        return exposedComparison(
            sourceExposed = sourceFps > 0f,
            outputExposed = outputFps > 0f,
            matches = abs(sourceFps - outputFps) <= 1.0f
        )
    }

    fun remuxFpsBlockReason(sourceFps: Float, outputFps: Float): String? {
        return when {
            sourceFps <= 0f -> null
            outputFps <= 0f -> REMUX_OUTPUT_FPS_NOT_EXPOSED
            abs(sourceFps - outputFps) > 1.0f -> "remux output changed FPS"
            else -> null
        }
    }
}
