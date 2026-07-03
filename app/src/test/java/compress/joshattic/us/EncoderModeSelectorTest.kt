package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Test

class EncoderModeSelectorTest {
    @Test
    fun perceptuallyLosslessUsesConservativeVbr() {
        assertEquals(
            EncoderMode.PERCEPTUAL_VBR,
            EncoderModeSelector.chooseInitialMode(BatchQualityPreset.PERCEPTUALLY_LOSSLESS)
        )
    }

    @Test
    fun highQualityUsesMedia3HighQualityTargeting() {
        assertEquals(
            EncoderMode.HIGH_QUALITY,
            EncoderModeSelector.chooseInitialMode(BatchQualityPreset.HIGH_QUALITY)
        )
    }

    @Test
    fun storageSaverUsesStorageSaverVbr() {
        assertEquals(
            EncoderMode.STORAGE_SAVER,
            EncoderModeSelector.chooseInitialMode(BatchQualityPreset.STORAGE_SAVER)
        )
    }

    @Test
    fun remuxModeLeavesEncoderModeNeutral() {
        assertEquals(
            EncoderMode.NOT_EXPOSED,
            EncoderModeSelector.chooseInitialMode(BatchQualityPreset.REMUX_ONLY)
        )
    }
}
