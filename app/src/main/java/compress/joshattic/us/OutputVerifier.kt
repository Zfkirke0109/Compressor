package compress.joshattic.us

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MimeTypes
import java.io.File

object OutputVerifier {
    fun verify(
        context: Context,
        source: BatchVideoItem,
        outputFile: File,
        modeLabel: String,
        privacyMode: MetadataPrivacyMode,
        encoderMode: EncoderMode = EncoderMode.NOT_EXPOSED,
        perceptualStatusOverride: PerceptualLosslessStatus? = null,
        perceptualStatusReason: String? = null
    ): OutputVerificationReport {
        val outputProbe = readFileProbe(outputFile)
        val sourceTracks = readTrackProbe(context, source.sourceUri)
        val outputTracks = readTrackProbe(outputFile.absolutePath)
        val outputMetadata = runCatching {
            VideoMetadataPreserver.capture(context, Uri.fromFile(outputFile))
        }.getOrDefault(VideoMetadataSnapshot())

        val playable = outputFile.exists() &&
            outputFile.length() > 0L &&
            outputProbe.durationMs > 0L &&
            outputTracks.videoCodec != null

        val quality = BatchQualityPreset.fromLabel(modeLabel)
        val remuxOnly = quality == BatchQualityPreset.REMUX_ONLY
        val perceptuallyLossless = quality == BatchQualityPreset.PERCEPTUALLY_LOSSLESS
        val videoMatches = sameDimensions(source.originalWidth, source.originalHeight, outputProbe.width, outputProbe.height)
        val sourceFps = OutputVerificationFormatter.effectiveFps(source.originalFps, sourceTracks.videoFrameRate)
        val outputFps = OutputVerificationFormatter.effectiveFps(outputProbe.fps, outputTracks.videoFrameRate)
        val fpsComparison = OutputVerificationFormatter.fpsComparison(sourceFps, outputFps)
        val remuxFpsBlockReason = OutputVerificationFormatter.remuxFpsBlockReason(sourceFps, outputFps)
        val fpsMatches = fpsComparison != VerificationComparison.WARN
        val videoCodecMatches = codecsMatch(sourceTracks.videoCodec, outputTracks.videoCodec)
        val audioCodecMatches = codecsMatch(sourceTracks.audioCodec, outputTracks.audioCodec)
        val colorTransferMatches = sourceTracks.colorTransfer == null ||
            outputTracks.colorTransfer == null ||
            sourceTracks.colorTransfer == outputTracks.colorTransfer
        val colorStandardMatches = sourceTracks.colorStandard == null ||
            outputTracks.colorStandard == null ||
            sourceTracks.colorStandard == outputTracks.colorStandard
        val hdrMatches = colorTransferMatches &&
            colorStandardMatches &&
            (sourceTracks.hdrLabel == "not exposed" || outputTracks.hdrLabel == "not exposed" || sourceTracks.hdrLabel == outputTracks.hdrLabel)
        val videoCodecComparison = if (remuxOnly) {
            OutputVerificationFormatter.exposedComparison(
                sourceExposed = sourceTracks.videoCodec != null,
                outputExposed = outputTracks.videoCodec != null,
                matches = videoCodecMatches
            )
        } else {
            VerificationComparison.NEUTRAL
        }
        val audioCodecComparison = if (remuxOnly) {
            OutputVerificationFormatter.exposedComparison(
                sourceExposed = sourceTracks.audioCodec != null,
                outputExposed = outputTracks.audioCodec != null,
                matches = audioCodecMatches
            )
        } else {
            VerificationComparison.NEUTRAL
        }
        val hdrComparison = OutputVerificationFormatter.exposedComparison(
            sourceExposed = sourceTracks.hdrLabel != "not exposed",
            outputExposed = outputTracks.hdrLabel != "not exposed",
            matches = hdrMatches
        )
        val sourceVideoBitrate = sourceVideoBitrate(source, sourceTracks)
        val outputVideoBitrate = outputTracks.videoBitrate
        val fourKSixtyHdr = isFourKSixtyHdr(source, sourceTracks, sourceFps)
        val perceptualResult = if (perceptuallyLossless) {
            PerceptualLosslessVerifier.verify(
                PerceptualLosslessVerificationInput(
                    playable = playable,
                    sourceWidth = source.originalWidth,
                    sourceHeight = source.originalHeight,
                    outputWidth = outputProbe.width,
                    outputHeight = outputProbe.height,
                    sourceFps = sourceFps,
                    outputFps = outputFps,
                    sourceVideoBitrate = sourceVideoBitrate,
                    outputVideoBitrate = outputVideoBitrate,
                    sourceHdrLike = source.originalHdrLike || sourceTracks.hdrLabel != "not exposed",
                    sourceColorTransfer = sourceTracks.colorTransfer,
                    outputColorTransfer = outputTracks.colorTransfer,
                    sourceColorStandard = sourceTracks.colorStandard,
                    outputColorStandard = outputTracks.colorStandard,
                    sourceRotationDegrees = source.metadataSnapshot.rotationDegrees,
                    outputRotationDegrees = outputProbe.rotationDegrees,
                    sourceAudioCodec = sourceTracks.audioCodec,
                    outputAudioCodec = outputTracks.audioCodec,
                    fourKSixtyHdr = fourKSixtyHdr
                )
            )
        } else {
            null
        }
        val finalPerceptualStatus = perceptualStatusOverride ?: perceptualResult?.status
        val finalPerceptualReason = perceptualStatusReason ?: perceptualResult?.reason

        val replacementSafe = playable && when {
            remuxOnly -> videoMatches && fpsMatches && remuxFpsBlockReason == null && videoCodecMatches && audioCodecMatches && hdrMatches
            perceptuallyLossless -> perceptualResult?.status == PerceptualLosslessStatus.PL_VERIFIED
            else -> true
        }
        val blockReason = when {
            playable.not() -> "output did not pass playability verification"
            remuxOnly && !videoMatches -> "remux output changed resolution"
            remuxOnly && remuxFpsBlockReason != null -> remuxFpsBlockReason
            remuxOnly && !fpsMatches -> "remux output changed FPS"
            remuxOnly && !videoCodecMatches -> "remux output changed video codec"
            remuxOnly && sourceTracks.audioCodec != null && outputTracks.audioCodec == null -> "remux output audio codec was not exposed"
            remuxOnly && !audioCodecMatches -> "remux output changed audio codec"
            remuxOnly && !hdrMatches -> "remux output changed HDR/color metadata"
            perceptuallyLossless && !videoMatches -> "Perceptually Lossless output changed resolution"
            perceptuallyLossless && !fpsMatches -> "Perceptually Lossless output changed FPS"
            perceptuallyLossless && perceptualResult?.status == PerceptualLosslessStatus.PL_UNVERIFIED ->
                "Perceptually Lossless verification failed: ${perceptualResult.reason}"
            else -> null
        }

        val sourceLocationText = if (source.metadataSnapshot.hasLocation && !privacyMode.removeLocation) "source location" else "not required"

        return OutputVerificationReport(
            playability = if (playable) "opens" else "failed",
            encoderMode = encoderMode.reportLabel,
            video = OutputVerificationFormatter.transition(
                "${source.originalWidth}x${source.originalHeight}",
                dimensionLabel(outputProbe.width, outputProbe.height),
                OutputVerificationFormatter.exposedComparison(
                    sourceExposed = source.originalWidth > 0 && source.originalHeight > 0,
                    outputExposed = outputProbe.width > 0 && outputProbe.height > 0,
                    matches = videoMatches
                )
            ),
            fps = OutputVerificationFormatter.transition(
                fpsLabel(sourceFps),
                fpsLabel(outputFps),
                fpsComparison
            ),
            videoCodec = OutputVerificationFormatter.transition(
                codecLabel(sourceTracks.videoCodec),
                codecLabel(outputTracks.videoCodec),
                videoCodecComparison
            ),
            audioCodec = OutputVerificationFormatter.transition(
                codecLabel(sourceTracks.audioCodec),
                codecLabel(outputTracks.audioCodec),
                audioCodecComparison
            ),
            audioBitrate = "${bitrateLabel(sourceTracks.audioBitrate)} -> ${bitrateLabel(outputTracks.audioBitrate)}",
            hdr = OutputVerificationFormatter.transition(
                sourceTracks.hdrLabel,
                outputTracks.hdrLabel,
                hdrComparison
            ),
            galleryDate = OutputDateVerificationFormatter.galleryDateStatus(
                sourceHasDate = source.metadataSnapshot.hasDate,
                outputHasGalleryDate = outputMetadata.dateSource?.startsWith("MediaStore") == true,
                privacyMode = privacyMode
            ),
            mp4CreationTime = OutputDateVerificationFormatter.mp4CreationTimeStatus(
                sourceRawDateTag = source.metadataSnapshot.rawDateTag,
                outputRawDateTag = outputMetadata.rawDateTag,
                privacyMode = privacyMode
            ),
            location = when {
                privacyMode.removeLocation -> "omitted by privacy setting"
                outputMetadata.hasLocation -> "verified"
                source.metadataSnapshot.hasLocation -> "$sourceLocationText will be restored where Android allows"
                else -> "not exposed"
            },
            rotation = "${rotationLabel(source.metadataSnapshot.rotationDegrees)} -> ${rotationLabel(outputProbe.rotationDegrees)}",
            fileSize = "${formatFileSize(source.originalSize)} -> ${formatFileSize(outputFile.length())}",
            replacementSafe = replacementSafe,
            replacementBlockReason = blockReason,
            perceptualStatus = finalPerceptualStatus?.reportLabel,
            perceptualReason = finalPerceptualReason,
            sourceVideoBitrate = sourceVideoBitrate,
            outputVideoBitrate = outputVideoBitrate
        )
    }

    private data class FileProbe(
        val width: Int,
        val height: Int,
        val fps: Float,
        val durationMs: Long,
        val rotationDegrees: Int?
    )

    private data class TrackProbe(
        val videoCodec: String?,
        val audioCodec: String?,
        val videoBitrate: Int,
        val audioBitrate: Int,
        val hdrLabel: String,
        val videoFrameRate: Float,
        val colorTransfer: Int?,
        val colorStandard: Int?
    )

    private fun readFileProbe(file: File): FileProbe {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
            if (rotation == 90 || rotation == 270) {
                val oldWidth = width
                width = height
                height = oldWidth
            }
            FileProbe(
                width = width,
                height = height,
                fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() ?: 0f,
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                rotationDegrees = rotation
            )
        } catch (_: Exception) {
            FileProbe(0, 0, 0f, 0L, null)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readTrackProbe(context: Context, uri: Uri): TrackProbe {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            readTrackProbe(extractor)
        } catch (_: Exception) {
            TrackProbe(null, null, 0, 0, "not exposed", 0f, null, null)
        } finally {
            extractor.release()
        }
    }

    private fun readTrackProbe(path: String): TrackProbe {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            readTrackProbe(extractor)
        } catch (_: Exception) {
            TrackProbe(null, null, 0, 0, "not exposed", 0f, null, null)
        } finally {
            extractor.release()
        }
    }

    private fun readTrackProbe(extractor: MediaExtractor): TrackProbe {
        var videoCodec: String? = null
        var audioCodec: String? = null
        var videoBitrate = 0
        var audioBitrate = 0
        var videoFrameRate = 0f
        var colorTransfer: Int? = null
        var colorStandard: Int? = null

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            when {
                mime?.startsWith("video/") == true -> {
                    if (videoCodec == null) videoCodec = mime
                    videoBitrate = format.intOrNull(MediaFormat.KEY_BIT_RATE) ?: videoBitrate
                    videoFrameRate = format.floatOrIntOrNull(MediaFormat.KEY_FRAME_RATE) ?: videoFrameRate
                    colorTransfer = format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER) ?: colorTransfer
                    colorStandard = format.intOrNull(MediaFormat.KEY_COLOR_STANDARD) ?: colorStandard
                }
                mime?.startsWith("audio/") == true -> {
                    if (audioCodec == null) audioCodec = mime
                    audioBitrate = format.intOrNull(MediaFormat.KEY_BIT_RATE) ?: audioBitrate
                }
            }
        }

        val hdrLabel = when (colorTransfer) {
            MediaFormat.COLOR_TRANSFER_ST2084 -> "HDR PQ"
            MediaFormat.COLOR_TRANSFER_HLG -> "HDR HLG"
            MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> "SDR"
            null -> if (colorStandard == null) "not exposed" else "color standard $colorStandard"
            else -> "transfer $colorTransfer"
        }

        return TrackProbe(videoCodec, audioCodec, videoBitrate, audioBitrate, hdrLabel, videoFrameRate, colorTransfer, colorStandard)
    }

    private fun sameDimensions(sourceWidth: Int, sourceHeight: Int, outputWidth: Int, outputHeight: Int): Boolean {
        if (sourceWidth <= 0 || sourceHeight <= 0 || outputWidth <= 0 || outputHeight <= 0) return true
        return sourceWidth == outputWidth && sourceHeight == outputHeight
    }

    private fun codecsMatch(left: String?, right: String?): Boolean {
        if (left == null && right == null) return true
        if (left == null || right == null) return false
        return left.equals(right, ignoreCase = true)
    }

    private fun dimensionLabel(width: Int, height: Int): String {
        return if (width > 0 && height > 0) "${width}x${height}" else "not exposed"
    }

    private fun fpsLabel(fps: Float): String {
        return if (fps > 0f) fps.toInt().toString() else "not exposed"
    }

    private fun codecLabel(codec: String?): String {
        return when (codec) {
            MimeTypes.VIDEO_H264 -> "H.264"
            MimeTypes.VIDEO_H265 -> "HEVC"
            MimeTypes.VIDEO_AV1 -> "AV1"
            MimeTypes.AUDIO_AAC -> "AAC"
            null -> "not exposed"
            else -> codec.substringAfter("/")
        }
    }

    private fun bitrateLabel(bitrate: Int): String {
        return if (bitrate > 0) "${bitrate / 1000} kbps" else "not exposed"
    }

    private fun rotationLabel(rotation: Int?): String {
        return rotation?.let { "${it}deg" } ?: "not exposed"
    }

    private fun sourceVideoBitrate(source: BatchVideoItem, sourceTracks: TrackProbe): Int {
        if (sourceTracks.videoBitrate > 0) return sourceTracks.videoBitrate
        val originalTotal = source.originalBitrate
        val audio = if (source.originalAudioBitrate > 0) source.originalAudioBitrate else sourceTracks.audioBitrate
        return if (originalTotal > 0) (originalTotal - audio).coerceAtLeast(originalTotal / 2) else 0
    }

    private fun isFourKSixtyHdr(source: BatchVideoItem, sourceTracks: TrackProbe, sourceFps: Float): Boolean {
        val sourceMime = source.originalVideoMimeType ?: sourceTracks.videoCodec
        val hdrLike = source.originalHdrLike ||
            sourceTracks.colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
            sourceTracks.colorTransfer == MediaFormat.COLOR_TRANSFER_HLG ||
            sourceTracks.colorStandard == MediaFormat.COLOR_STANDARD_BT2020
        return source.originalHeight >= 2160 &&
            sourceFps >= 50f &&
            sourceMime == MimeTypes.VIDEO_H265 &&
            hdrLike
    }

    private fun MediaFormat.intOrNull(key: String): Int? {
        return if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
    }

    private fun MediaFormat.floatOrIntOrNull(key: String): Float? {
        if (!containsKey(key)) return null
        return runCatching { getInteger(key).toFloat() }
            .recoverCatching { getFloat(key) }
            .getOrNull()
    }
}
