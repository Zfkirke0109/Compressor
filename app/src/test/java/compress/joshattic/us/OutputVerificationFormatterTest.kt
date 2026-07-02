package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutputVerificationFormatterTest {
    @Test
    fun matchingFpsAppendsOk() {
        val comparison = OutputVerificationFormatter.fpsComparison(60f, 60f)

        assertEquals(
            "60 -> 60 ok",
            OutputVerificationFormatter.transition("60", "60", comparison)
        )
    }

    @Test
    fun mismatchedFpsAppendsWarn() {
        val comparison = OutputVerificationFormatter.fpsComparison(60f, 30f)

        assertEquals(
            "60 -> 30 warn",
            OutputVerificationFormatter.transition("60", "30", comparison)
        )
    }

    @Test
    fun missingOutputFpsIsNeutralForNormalReport() {
        val comparison = OutputVerificationFormatter.fpsComparison(60f, 0f)

        assertEquals(
            "60 -> not exposed",
            OutputVerificationFormatter.transition("60", "not exposed", comparison)
        )
    }

    @Test
    fun remuxMissingOutputFpsBlocksReplacementSafety() {
        assertEquals(
            "remux output FPS was not exposed",
            OutputVerificationFormatter.remuxFpsBlockReason(60f, 0f)
        )
    }

    @Test
    fun remuxMatchingFpsDoesNotBlockReplacementSafety() {
        assertNull(OutputVerificationFormatter.remuxFpsBlockReason(60f, 60f))
    }
}
