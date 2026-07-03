package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputDateVerificationFormatterTest {
    @Test
    fun mp4CreationTimePreservedWhenRawTagsMatch() {
        assertEquals(
            "MP4 creation_time preserved",
            OutputDateVerificationFormatter.mp4CreationTimeStatus(
                sourceRawDateTag = "2026-05-11T05:19:08Z",
                outputRawDateTag = "2026-05-11T05:19:08Z",
                privacyMode = MetadataPrivacyMode.PRESERVE_ALL
            )
        )
    }

    @Test
    fun mp4CreationTimeDiffersWhenRawTagsDoNotMatch() {
        assertEquals(
            "MP4 creation_time differs",
            OutputDateVerificationFormatter.mp4CreationTimeStatus(
                sourceRawDateTag = "2026-05-11T05:19:08Z",
                outputRawDateTag = "2026-07-02T21:16:29Z",
                privacyMode = MetadataPrivacyMode.PRESERVE_ALL
            )
        )
    }

    @Test
    fun mp4CreationTimeNotExposedWhenAndroidDoesNotExposeEitherTag() {
        assertEquals(
            "Date not exposed",
            OutputDateVerificationFormatter.mp4CreationTimeStatus(
                sourceRawDateTag = null,
                outputRawDateTag = null,
                privacyMode = MetadataPrivacyMode.PRESERVE_ALL
            )
        )
    }

    @Test
    fun privacyDateRemovalIsReportedExplicitly() {
        assertEquals(
            "Date omitted by privacy setting",
            OutputDateVerificationFormatter.mp4CreationTimeStatus(
                sourceRawDateTag = "2026-05-11T05:19:08Z",
                outputRawDateTag = "2026-07-02T21:16:29Z",
                privacyMode = MetadataPrivacyMode.REMOVE_DATE
            )
        )
    }

    @Test
    fun galleryDateRestoreIsSeparateFromMp4CreationTime() {
        assertEquals(
            "restored",
            OutputDateVerificationFormatter.galleryDateStatus(
                sourceHasDate = true,
                outputHasGalleryDate = true,
                privacyMode = MetadataPrivacyMode.PRESERVE_ALL
            )
        )
    }
}
