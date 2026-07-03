package compress.joshattic.us

internal object OutputDateVerificationFormatter {
    fun galleryDateStatus(
        sourceHasDate: Boolean,
        outputHasGalleryDate: Boolean,
        privacyMode: MetadataPrivacyMode
    ): String {
        return when {
            privacyMode.removeDate -> "omitted by privacy setting"
            outputHasGalleryDate -> "restored"
            sourceHasDate -> "restore requested where Android allows"
            else -> "not exposed"
        }
    }

    fun mp4CreationTimeStatus(
        sourceRawDateTag: String?,
        outputRawDateTag: String?,
        privacyMode: MetadataPrivacyMode
    ): String {
        if (privacyMode.removeDate) return "Date omitted by privacy setting"

        val source = sourceRawDateTag?.takeIf { it.isNotBlank() }
        val output = outputRawDateTag?.takeIf { it.isNotBlank() }
        return when {
            source == null && output == null -> "Date not exposed"
            source != null && output == null -> "Date not exposed"
            source == null && output != null -> "MP4 creation_time differs"
            source == output -> "MP4 creation_time preserved"
            else -> "MP4 creation_time differs"
        }
    }
}
