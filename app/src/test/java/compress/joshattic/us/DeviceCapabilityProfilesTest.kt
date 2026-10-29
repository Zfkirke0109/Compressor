package compress.joshattic.us

import androidx.media3.common.MimeTypes
import org.junit.Assert.*
import org.junit.Test

class DeviceCapabilityProfilesTest {
    @Test fun theS26AndS23HaveSeparateDefaultsAndPresets() {
        val s26 = DeviceCapabilityProfiles.forDevice("samsung", "SM-S948U")
        assertTrue(s26.isGalaxyS26Ultra)
        assertFalse(s26.isGalaxyS23Ultra)
        assertEquals("Samsung Galaxy S26 Ultra", s26.name)
        assertEquals(BatchPresetOption.S26_BEST, BatchPresetOption.forDevice(s26).first())
        assertTrue(s26.avoidAv1EncodingByDefault)
        assertEquals(1, s26.recommendedBatchParallelism)
        for (model in listOf("SM-S918U1", "SM-S918B")) {
            val s23 = DeviceCapabilityProfiles.forDevice("Samsung", model)
            assertTrue(s23.isGalaxyS23Ultra)
            assertFalse(s23.isGalaxyS26Ultra)
            assertEquals(BatchPresetOption.S23_BEST, BatchPresetOption.forDevice(s23).first())
        }
    }

    @Test fun modelNamesDoNotInventEncoderSupport() {
        val s26 = DeviceCapabilityProfiles.forDevice("samsung", "SM-S948U")
        assertEquals(MimeTypes.VIDEO_H264, s26.chooseDefaultVideoCodec(listOf(MimeTypes.VIDEO_H264)))
        assertEquals(MimeTypes.VIDEO_H265, s26.chooseDefaultVideoCodec(listOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265)))
        assertFalse(DeviceCapabilityProfiles.forDevice("other", "SM-S948U").isGalaxyS26Ultra)
    }

    @Test fun oldS23PresetLabelsRemainReadable() {
        assertEquals(BatchPresetOption.S23_BEST, BatchPresetOption.fromLabel("S23 Ultra Best Quality"))
        assertEquals(BatchPresetOption.S26_BEST, BatchPresetOption.fromLabel("S26 Ultra Best Quality"))
    }
}
