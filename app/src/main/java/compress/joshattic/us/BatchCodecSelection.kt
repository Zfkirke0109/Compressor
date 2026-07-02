package compress.joshattic.us

import androidx.media3.common.MimeTypes

internal enum class BatchCodecOption(val label: String) {
    AUTO("Auto"),
    AV1("AV1"),
    HEVC("HEVC"),
    H264("H.264");

    companion object {
        fun fromLabel(label: String): BatchCodecOption {
            return entries.firstOrNull { it.label == label } ?: AUTO
        }
    }
}

internal object BatchCodecSelector {
    fun availableLabels(supportedCodecs: Set<String>): List<String> {
        return buildList {
            add(BatchCodecOption.AUTO.label)
            if (supportedCodecs.contains(MimeTypes.VIDEO_AV1)) add(BatchCodecOption.AV1.label)
            add(BatchCodecOption.HEVC.label)
            add(BatchCodecOption.H264.label)
        }
    }

    fun chooseOutputMime(
        requested: BatchCodecOption,
        supportedCodecs: Set<String>,
        profile: DeviceCapabilityProfile
    ): String {
        return when (requested) {
            BatchCodecOption.AV1 -> firstSupported(
                supportedCodecs,
                MimeTypes.VIDEO_AV1,
                MimeTypes.VIDEO_H265,
                MimeTypes.VIDEO_H264
            )
            BatchCodecOption.HEVC -> firstSupported(
                supportedCodecs,
                MimeTypes.VIDEO_H265,
                MimeTypes.VIDEO_H264
            )
            BatchCodecOption.H264 -> MimeTypes.VIDEO_H264
            BatchCodecOption.AUTO -> {
                val autoSupported = buildList {
                    add(MimeTypes.VIDEO_H264)
                    if (supportedCodecs.contains(MimeTypes.VIDEO_H265)) add(MimeTypes.VIDEO_H265)
                }
                val profileChoice = profile.chooseDefaultVideoCodec(autoSupported)
                if (autoSupported.contains(profileChoice)) profileChoice else MimeTypes.VIDEO_H264
            }
        }
    }

    fun labelForMime(mimeType: String): String {
        return when (mimeType) {
            MimeTypes.VIDEO_AV1 -> BatchCodecOption.AV1.label
            MimeTypes.VIDEO_H265 -> BatchCodecOption.HEVC.label
            else -> BatchCodecOption.H264.label
        }
    }

    private fun firstSupported(supportedCodecs: Set<String>, vararg candidates: String): String {
        return candidates.firstOrNull { supportedCodecs.contains(it) } ?: MimeTypes.VIDEO_H264
    }
}
