package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Test

class EncoderModeSelectorTest {
    @Test
    fun originalModeStartsWithCqAttempt() {
        assertEquals(
            EncoderMode.CQ,
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
