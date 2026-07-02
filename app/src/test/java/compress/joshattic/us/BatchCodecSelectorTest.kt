package compress.joshattic.us

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchCodecSelectorTest {
    private val modernProfile = DeviceCapabilityProfile(
        name = "Modern Android device",
        isGalaxyS23Ultra = false,
        preferHevcForDefaultCompression = true,
        avoidAv1EncodingByDefault = false,
        recommendedBatchParallelism = 1
    )

    @Test
    fun explicitAv1MapsToAv1WhenHardwareSupported() {
        val supported = setOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_AV1)

        assertEquals(
            MimeTypes.VIDEO_AV1,
            BatchCodecSelector.chooseOutputMime(BatchCodecOption.AV1, supported, modernProfile)
        )
    }

    @Test
    fun explicitAv1FallsBackToHevcThenH264() {
        assertEquals(
            MimeTypes.VIDEO_H265,
            BatchCodecSelector.chooseOutputMime(
                BatchCodecOption.AV1,
                setOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265),
                modernProfile
            )
        )

        assertEquals(
            MimeTypes.VIDEO_H264,
            BatchCodecSelector.chooseOutputMime(
                BatchCodecOption.AV1,
                setOf(MimeTypes.VIDEO_H264),
                modernProfile
            )
        )
    }

    @Test
    fun autoRemainsHevcFirstWhenAv1IsAvailable() {
        val supported = setOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_AV1)

        assertEquals(
            MimeTypes.VIDEO_H265,
            BatchCodecSelector.chooseOutputMime(BatchCodecOption.AUTO, supported, modernProfile)
        )
    }

    @Test
    fun availableLabelsShowAv1OnlyWhenSupported() {
        assertTrue(
            BatchCodecSelector.availableLabels(
                setOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_AV1)
            ).contains(BatchCodecOption.AV1.label)
        )

        assertFalse(
            BatchCodecSelector.availableLabels(
                setOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265)
            ).contains(BatchCodecOption.AV1.label)
        )
    }
}
