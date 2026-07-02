package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Test

class EncoderModeSelectorTest {
    @Test
    fun originalModeChoosesCqWhenSupported() {
        assertEquals(
            EncoderMode.CQ,
            EncoderModeSelector.chooseForOriginal(isOriginalMode = true, cqSupported = true)
        )
    }

    @Test
    fun originalModeChoosesVbrFallbackWhenCqUnsupported() {
        assertEquals(
            EncoderMode.VBR_FALLBACK,
            EncoderModeSelector.chooseForOriginal(isOriginalMode = true, cqSupported = false)
        )
    }

    @Test
    fun nonOriginalModeLeavesEncoderModeNeutral() {
        assertEquals(
            EncoderMode.NOT_EXPOSED,
            EncoderModeSelector.chooseForOriginal(isOriginalMode = false, cqSupported = true)
        )
    }
}
