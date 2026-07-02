package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Test

class EncoderModeSelectorTest {
    @Test
    fun originalModeStartsWithMedia3HighQualityTargeting() {
        assertEquals(
            EncoderMode.HIGH_QUALITY,
            EncoderModeSelector.chooseInitialMode(isOriginalMode = true)
        )
    }

    @Test
    fun nonOriginalModeLeavesEncoderModeNeutral() {
        assertEquals(
            EncoderMode.NOT_EXPOSED,
            EncoderModeSelector.chooseInitialMode(isOriginalMode = false)
        )
    }
}
