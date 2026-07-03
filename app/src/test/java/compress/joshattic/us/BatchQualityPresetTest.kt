package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchQualityPresetTest {
    @Test
    fun remuxOnlyCopyIsExplicitAboutNoReencodeAndNoQualityLoss() {
        val description = BatchQualityPreset.REMUX_ONLY.description

        assertEquals("Remux Only", BatchQualityPreset.REMUX_ONLY.label)
        assertTrue(description.contains("Exact stream copy"))
        assertTrue(description.contains("No re-encode"))
        assertTrue(description.contains("No quality loss"))
    }

    @Test
    fun highQualityCopyDoesNotClaimLossless() {
        assertEquals("High Quality", BatchQualityPreset.HIGH_QUALITY.label)
        assertFalse(BatchQualityPreset.HIGH_QUALITY.description.contains("lossless", ignoreCase = true))
        assertTrue(BatchQualityPreset.HIGH_QUALITY.description.contains("Measurable difference possible"))
    }

    @Test
    fun legacyLabelsMapToHonestModes() {
        assertEquals(BatchQualityPreset.REMUX_ONLY, BatchQualityPreset.fromLabel("Remux only"))
        assertEquals(BatchQualityPreset.PERCEPTUALLY_LOSSLESS, BatchQualityPreset.fromLabel("Original"))
        assertEquals(BatchQualityPreset.HIGH_QUALITY, BatchQualityPreset.fromLabel("High"))
        assertEquals(BatchQualityPreset.STORAGE_SAVER, BatchQualityPreset.fromLabel("Medium"))
        assertEquals(BatchQualityPreset.STORAGE_SAVER, BatchQualityPreset.fromLabel("Low"))
    }
}
