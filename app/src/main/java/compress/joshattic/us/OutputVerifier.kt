package compress.joshattic.us

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MimeTypes
import java.io.File
import java.util.Locale
import kotlin.math.abs

object OutputVerifier {
    internal data class FileProbe(
        val width: Int,
        val height: Int,
        val fps: Float,
        val durationMs: Long,
        val rotationDegrees: Int?
    )

    internal data class TrackProbe(
        val videoCodec: String?,
        val audioCodec: String?,
        val videoBitrate: Int,
        val audioBitrate: Int,
        val colorTransfer: Int?,
        val colorStandard: Int?,
        val colorRange: Int?,
        val audioChannelCount: Int?,
        val audioSampleRate: Int?,
        val videoFrameRate: Float = 0f
    ) {
        val hdrLabel: String
            get() = when (colorTransfer) {
                MediaFormat.COLOR_TRANSFER_ST2084 -> "HDR PQ"
                MediaFormat.COLOR_TRANSFER_HLG -> "HDR HLG"
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> "SDR"
                null -> "not exposed"
                else -> "transfer $colorTransfer"
            }
    }

    internal data class VerificationInput(
        val mode: BatchQualityMode,
        val source: VideoSourceInfo,
        val outputFileProbe: FileProbe,
        val sourceTrackProbe: TrackProbe,
        val outputTrackProbe: TrackProbe,
        val sourceMetadata: VideoMetadataSnapshot,
        val outputMetadata: VideoMetadataSnapshot,
        val sourceSize: Long,
        val outputSize: Long,
        val privacyMode: MetadataPrivacyMode
    )

    fun verify(
        context: Context,
        source: BatchVideoItem,
        outputFile: File,
        modeLabel: String,
        privacyMode: MetadataPrivacyMode
    ): OutputVerificationReport {
        val rawOutputProbe = readFileProbe(outputFile)
        val sourceTracks = probeTracks(context, source.sourceUri)
        val outputTracks = readTrackProbe(outputFile.absolutePath)
        // The retriever's CAPTURE_FRAMERATE is often absent on freshly encoded/remuxed files, so
        // fall back to the extractor's track frame rate before treating FPS as "not exposed".
        val outputProbe = if (rawOutputProbe.fps <= 0f && outputTracks.videoFrameRate > 0f) {
            rawOutputProbe.copy(fps = outputTracks.videoFrameRate)
        } else {
            rawOutputProbe
        }
        val outputMetadata = runCatching {
            VideoMetadataPreserver.capture(context, Uri.fromFile(outputFile))
        }.getOrDefault(VideoMetadataSnapshot())
        val sourceInfo = VideoSourceInfo(
            width = source.originalWidth,
            height = source.originalHeight,
            frameRate = source.originalFps,
            durationMs = source.durationMs,
            totalBitrate = source.originalBitrate,
            audioBitrate = source.originalAudioBitrate,
            videoMime = sourceTracks.videoCodec,
            audioMime = sourceTracks.audioCodec,
            colorTransfer = sourceTracks.colorTransfer,
            colorStandard = sourceTracks.colorStandard,
            colorRange = sourceTracks.colorRange,
            rotationDegrees = source.metadataSnapshot.rotationDegrees,
            audioChannelCount = sourceTracks.audioChannelCount,
            audioSampleRate = sourceTracks.audioSampleRate,
            audioPresent = sourceTracks.audioCodec != null,
            locationPresent = source.metadataSnapshot.hasLocation,
            mediaStoreDatePresent = source.metadataSnapshot.dateSource?.startsWith("MediaStore") == true,
            mp4DatePresent = source.metadataSnapshot.rawDateTag != null
        )
        return verify(
            VerificationInput(
                mode = BatchQualityMode.fromLabel(modeLabel),
                source = sourceInfo,
                outputFileProbe = outputProbe,
                sourceTrackProbe = sourceTracks,
                outputTrackProbe = outputTracks,
                sourceMetadata = source.metadataSnapshot,
                outputMetadata = outputMetadata,
                sourceSize = source.originalSize,
                outputSize = outputFile.length(),
                privacyMode = privacyMode
            )
        )
    }

    internal fun verify(input: VerificationInput): OutputVerificationReport {
        val playable = input.outputSize > 0L &&
            input.outputFileProbe.durationMs > 0L &&
            input.outputTrackProbe.videoCodec != null

        val videoMatches = sameDimensions(
            input.source.width,
            input.source.height,
            input.outputFileProbe.width,
            input.outputFileProbe.height
        )
        val fpsComparison = OutputVerificationFormatter.fpsComparison(
            input.source.frameRate,
            input.outputFileProbe.fps
        )
        val videoCodecMatches = codecsMatch(input.sourceTrackProbe.videoCodec, input.outputTrackProbe.videoCodec)
        val audioCodecMatches = codecsMatch(input.sourceTrackProbe.audioCodec, input.outputTrackProbe.audioCodec)
        val audioShapeMatches = optionalExactMatch(
            input.sourceTrackProbe.audioChannelCount,
            input.outputTrackProbe.audioChannelCount
        ) && optionalExactMatch(
            input.sourceTrackProbe.audioSampleRate,
            input.outputTrackProbe.audioSampleRate
        )
        val hdrMatches = colorMatches(
            input.sourceTrackProbe.colorTransfer,
            input.outputTrackProbe.colorTransfer
        )
        val standardMatches = colorMatches(
            input.sourceTrackProbe.colorStandard,
            input.outputTrackProbe.colorStandard
        )
        val rangeMatches = colorMatches(
            input.sourceTrackProbe.colorRange,
            input.outputTrackProbe.colorRange
        )
        val rotationMatches = input.sourceMetadata.rotationDegrees == null ||
            input.outputFileProbe.rotationDegrees == input.sourceMetadata.rotationDegrees
        val locationMatches = when {
            input.privacyMode.removeLocation -> true
            !input.sourceMetadata.hasLocation -> true
            else -> input.outputMetadata.hasLocation
        }
        val mediaStoreDateMatches = when {
            input.privacyMode.removeDate -> true
            input.sourceMetadata.dateSource?.startsWith("MediaStore") != true -> true
            else -> input.outputMetadata.dateSource?.startsWith("MediaStore") == true
        }
        val mp4DateMatches = when {
            input.privacyMode.removeDate -> true
            input.sourceMetadata.rawDateTag == null -> true
            else -> input.outputMetadata.rawDateTag != null
        }
        val sourceVideoBitrate = input.sourceTrackProbe.videoBitrate.takeIf { it > 0 }
            ?: input.source.videoBitrate.takeIf { it > 0 }
            ?: BatchQualityBitratePolicy.fallbackOriginalBitrate(input.source)

        // Both the batch and remux pipelines finish through Android's MediaMuxer, which does not
        // write per-track bitrate metadata (btrt), so KEY_BIT_RATE is usually absent on outputs.
        // The average bitrate is still measurable from the real file size and duration, so use
        // that instead of failing verification on missing container metadata.
        val measuredOutputTotalBitrate = if (input.outputSize > 0L && input.outputFileProbe.durationMs > 0L) {
            ((input.outputSize * 8_000.0) / input.outputFileProbe.durationMs).toInt()
        } else {
            0
        }

        // Audio in Perceptually Lossless and Remux Only is stream-copied, never re-encoded; when
        // the copied track's bitrate is not exposed but the codec/channels/sample-rate all match
        // the source exactly, the source bitrate is the truthful value for the copied packets.
        val audioLooksStreamCopied = input.outputTrackProbe.audioBitrate <= 0 &&
            audioCodecMatches &&
            input.sourceTrackProbe.audioCodec != null &&
            audioShapeMatches &&
            input.outputTrackProbe.audioChannelCount != null &&
            input.outputTrackProbe.audioSampleRate != null
        val effectiveOutputAudioBitrate = when {
            input.outputTrackProbe.audioBitrate > 0 -> input.outputTrackProbe.audioBitrate
            audioLooksStreamCopied -> input.sourceTrackProbe.audioBitrate
            else -> 0
        }

        val effectiveOutputVideoBitrate = when {
            input.outputTrackProbe.videoBitrate > 0 -> input.outputTrackProbe.videoBitrate
            measuredOutputTotalBitrate > 0 -> {
                val audioShare = effectiveOutputAudioBitrate.coerceAtLeast(0)
                (measuredOutputTotalBitrate - audioShare).coerceAtLeast(measuredOutputTotalBitrate / 2)
            }
            else -> 0
        }
        val videoBitrateMeasured = input.outputTrackProbe.videoBitrate <= 0 && effectiveOutputVideoBitrate > 0

        val minPerceptualVideoBitrate = BatchQualityBitratePolicy.perceptualLosslessBitrateFloor(input.source)
        val bitratePass = when (input.mode) {
            BatchQualityMode.PERCEPTUAL_LOSSLESS -> effectiveOutputVideoBitrate > 0 && effectiveOutputVideoBitrate >= minPerceptualVideoBitrate
            BatchQualityMode.REMUX_ONLY -> effectiveOutputVideoBitrate <= 0 || sourceVideoBitrate <= 0 || effectiveOutputVideoBitrate >= (sourceVideoBitrate * 0.98).toInt()
            else -> true
        }
        val audioBitratePass = when {
            input.sourceTrackProbe.audioCodec == null -> input.outputTrackProbe.audioCodec == null || input.mode != BatchQualityMode.REMUX_ONLY
            // A stream-copied track carries the source packets verbatim, so bitrate parity holds
            // even when neither container exposes a numeric value.
            audioLooksStreamCopied -> true
            input.mode == BatchQualityMode.PERCEPTUAL_LOSSLESS -> {
                val floor = maxOf(input.source.audioBitrate, 256_000)
                effectiveOutputAudioBitrate > 0 && effectiveOutputAudioBitrate >= floor * 0.9
            }
            input.mode == BatchQualityMode.REMUX_ONLY -> {
                if (input.sourceTrackProbe.audioBitrate <= 0) {
                    // Bitrate parity is unknowable when the source hides it; codec/shape/size
                    // checks still gate the stream copy.
                    true
                } else {
                    effectiveOutputAudioBitrate > 0 &&
                        abs(effectiveOutputAudioBitrate - input.sourceTrackProbe.audioBitrate) <= input.sourceTrackProbe.audioBitrate * 0.05
                }
            }
            else -> true
        }

        // playable already guarantees outputTrackProbe.videoCodec != null, so it is not re-checked.
        // Audio bitrate counts as complete when exposed OR derivable from an exact stream copy;
        // MediaMuxer outputs never carry btrt metadata, so requiring the raw key would make every
        // on-device output permanently unverifiable.
        val criticalFieldsComplete = playable &&
            input.outputFileProbe.width > 0 &&
            input.outputFileProbe.height > 0 &&
            input.outputFileProbe.fps > 0f &&
            (input.sourceTrackProbe.audioCodec == null || (
                input.outputTrackProbe.audioCodec != null &&
                    (effectiveOutputAudioBitrate > 0 || input.sourceTrackProbe.audioBitrate <= 0) &&
                    input.outputTrackProbe.audioChannelCount != null &&
                    input.outputTrackProbe.audioSampleRate != null
                )) &&
            (!input.source.isHdr || (
                input.outputTrackProbe.colorTransfer != null &&
                    input.outputTrackProbe.colorStandard != null &&
                    input.outputTrackProbe.colorRange != null
                ))

        val perceptuallyLosslessVerified = playable &&
            criticalFieldsComplete &&
            videoMatches &&
            fpsComparison == VerificationTransitionStatus.MATCH &&
            hdrMatches &&
            standardMatches &&
            rangeMatches &&
            audioCodecMatches &&
            audioShapeMatches &&
            audioBitratePass &&
            bitratePass &&
            rotationMatches &&
            locationMatches &&
            mediaStoreDateMatches &&
            mp4DateMatches

        val remuxVerified = playable &&
            criticalFieldsComplete &&
            videoMatches &&
            fpsComparison == VerificationTransitionStatus.MATCH &&
            videoCodecMatches &&
            audioCodecMatches &&
            audioShapeMatches &&
            audioBitratePass &&
            hdrMatches &&
            standardMatches &&
            rangeMatches &&
            rotationMatches &&
            locationMatches &&
            mediaStoreDateMatches &&
            mp4DateMatches

        val outputWithinTolerance = input.sourceSize <= 0L ||
            input.outputSize <= (input.sourceSize * (1.0 + BatchQualityBitratePolicy.PERCEPTUAL_LOSSLESS_SIZE_TOLERANCE)).toLong()

        // Replacing the original is destructive on a locked-down device, so Perceptually Lossless
        // replacement demands a STRICTLY smaller output. A verified output inside the size
        // tolerance but not smaller keeps its "Verified" verdict and may be saved as a copy — it
        // just may not overwrite the user's original.
        val strictlySmaller = input.sourceSize > 0L && input.outputSize in 1 until input.sourceSize

        val replacementSafe = when (input.mode) {
            BatchQualityMode.REMUX_ONLY -> remuxVerified
            BatchQualityMode.PERCEPTUAL_LOSSLESS -> perceptuallyLosslessVerified && outputWithinTolerance && strictlySmaller
            else -> playable
        }

        val blockReason = when {
            !playable -> "output did not pass playability verification"
            input.mode == BatchQualityMode.REMUX_ONLY && !criticalFieldsComplete -> "stream-copy verification was incomplete"
            input.mode == BatchQualityMode.REMUX_ONLY && !videoMatches -> "remux output changed resolution"
            input.mode == BatchQualityMode.REMUX_ONLY && fpsComparison != VerificationTransitionStatus.MATCH -> "remux output changed FPS"
            input.mode == BatchQualityMode.REMUX_ONLY && !videoCodecMatches -> "remux output changed video codec"
            input.mode == BatchQualityMode.REMUX_ONLY && !audioCodecMatches -> "remux output changed audio codec"
            input.mode == BatchQualityMode.REMUX_ONLY && !audioBitratePass -> "remux output changed audio bitrate"
            input.mode == BatchQualityMode.REMUX_ONLY && !(hdrMatches && standardMatches && rangeMatches) -> "remux output changed HDR/color metadata"
            input.mode == BatchQualityMode.PERCEPTUAL_LOSSLESS && !criticalFieldsComplete -> "perceptually lossless verification is unverified because critical fields were not exposed"
            input.mode == BatchQualityMode.PERCEPTUAL_LOSSLESS && !videoMatches -> "perceptually lossless output changed resolution"
            input.mode == BatchQualityMode.PERCEPTUAL_LOSSLESS && fpsComparison != VerificationTransitionStatus.MATCH -> "perceptually lossless output changed FPS"
            input.mode == BatchQualityMode.PERCEPTUAL_LOSSLESS && !bitratePass -> "perceptually lossless output bitrate fell below the verified safety threshold"
            input.mode == BatchQualityMode.PERCEPTUAL_LOSSLESS && !audioBitratePass -> "perceptually lossless output audio bitrate fell below the verified safety threshold"
            input.mode == BatchQualityMode.PERCEPTUAL_LOSSLESS && !(hdrMatches && standardMatches && rangeMatches) -> "perceptually lossless output lost HDR/color metadata"
            input.mode == BatchQualityMode.PERCEPTUAL_LOSSLESS && !outputWithinTolerance -> "perceptually lossless output exceeded the allowed size growth tolerance"
            input.mode == BatchQualityMode.PERCEPTUAL_LOSSLESS && !strictlySmaller -> "perceptually lossless output is not smaller than the source, so replacing the original is blocked"
            else -> null
        }

        val verdict = when (input.mode) {
            BatchQualityMode.REMUX_ONLY -> if (remuxVerified) "Remux Verified" else "Remux Verification Failed"
            BatchQualityMode.PERCEPTUAL_LOSSLESS -> when {
                perceptuallyLosslessVerified && outputWithinTolerance -> "Perceptually Lossless Verified"
                !criticalFieldsComplete -> "Perceptually Lossless Unverified"
                else -> "Perceptually Lossless Verification Failed"
            }
            BatchQualityMode.HIGH_QUALITY -> "High Quality (lossy mode)"
            BatchQualityMode.STORAGE_SAVER -> "Storage Saver (lossy mode)"
        }

        return OutputVerificationReport(
            verdict = verdict,
            playability = if (playable) "opens" else "failed",
            video = "${dimensionLabel(input.source.width, input.source.height)} -> ${dimensionLabel(input.outputFileProbe.width, input.outputFileProbe.height)} ${statusSuffix(videoMatches)}",
            fps = OutputVerificationFormatter.transition(
                fpsLabel(input.source.frameRate),
                fpsLabel(input.outputFileProbe.fps),
                fpsComparison
            ),
            videoBitrate = "${bitrateLabel(sourceVideoBitrate)} -> ${bitrateLabel(effectiveOutputVideoBitrate)}${if (videoBitrateMeasured) " (measured from size)" else ""}${if (input.mode == BatchQualityMode.PERCEPTUAL_LOSSLESS) " (floor ${bitrateLabel(minPerceptualVideoBitrate)})" else ""}",
            videoCodec = "${codecLabel(input.sourceTrackProbe.videoCodec)} -> ${codecLabel(input.outputTrackProbe.videoCodec)} ${statusSuffix(videoCodecMatches)}",
            audioCodec = "${codecLabel(input.sourceTrackProbe.audioCodec)} -> ${codecLabel(input.outputTrackProbe.audioCodec)} ${statusSuffix(audioCodecMatches)}",
            audioDetails = "${sampleRateLabel(input.sourceTrackProbe.audioSampleRate)}/${channelLabel(input.sourceTrackProbe.audioChannelCount)} -> ${sampleRateLabel(input.outputTrackProbe.audioSampleRate)}/${channelLabel(input.outputTrackProbe.audioChannelCount)} ${statusSuffix(audioShapeMatches)}",
            audioBitrate = "${bitrateLabel(input.sourceTrackProbe.audioBitrate)} -> ${bitrateLabel(effectiveOutputAudioBitrate)}${if (audioLooksStreamCopied) " (stream copied)" else ""} ${statusSuffix(audioBitratePass)}",
            hdr = "${input.sourceTrackProbe.hdrLabel} -> ${input.outputTrackProbe.hdrLabel} ${statusSuffix(hdrMatches)}",
            colorStandard = "${colorStandardLabel(input.sourceTrackProbe.colorStandard)} -> ${colorStandardLabel(input.outputTrackProbe.colorStandard)} ${statusSuffix(standardMatches)}",
            colorRange = "${colorRangeLabel(input.sourceTrackProbe.colorRange)} -> ${colorRangeLabel(input.outputTrackProbe.colorRange)} ${statusSuffix(rangeMatches)}",
            mediaStoreDate = dateVerificationLabel(
                input.privacyMode.removeDate,
                input.sourceMetadata.dateSource?.startsWith("MediaStore") == true,
                input.outputMetadata.dateSource?.startsWith("MediaStore") == true
            ),
            mp4Date = dateVerificationLabel(
                input.privacyMode.removeDate,
                input.sourceMetadata.rawDateTag != null,
                input.outputMetadata.rawDateTag != null
            ),
            location = locationVerificationLabel(input.privacyMode.removeLocation, input.sourceMetadata.hasLocation, input.outputMetadata.hasLocation),
            rotation = "${rotationLabel(input.sourceMetadata.rotationDegrees)} -> ${rotationLabel(input.outputFileProbe.rotationDegrees)} ${statusSuffix(rotationMatches)}",
            fileSize = "${formatFileSize(input.sourceSize)} -> ${formatFileSize(input.outputSize)}",
            replacementSafe = replacementSafe,
            replacementBlockReason = blockReason,
            criticalFieldsComplete = criticalFieldsComplete,
            verified = when (input.mode) {
                BatchQualityMode.REMUX_ONLY -> remuxVerified
                BatchQualityMode.PERCEPTUAL_LOSSLESS -> perceptuallyLosslessVerified && outputWithinTolerance
                else -> playable
            }
        )
    }

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

    // Also used by encode planning to detect HDR/color and codec on the source before encoding.
    internal fun probeTracks(context: Context, uri: Uri): TrackProbe {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            readTrackProbe(extractor)
        } catch (_: Exception) {
            TrackProbe(null, null, 0, 0, null, null, null, null, null)
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
            TrackProbe(null, null, 0, 0, null, null, null, null, null)
        } finally {
            extractor.release()
        }
    }

    private fun readTrackProbe(extractor: MediaExtractor): TrackProbe {
        var videoCodec: String? = null
        var audioCodec: String? = null
        var videoBitrate = 0
        var audioBitrate = 0
        var colorTransfer: Int? = null
        var colorStandard: Int? = null
        var colorRange: Int? = null
        var audioChannels: Int? = null
        var audioSampleRate: Int? = null
        var videoFrameRate = 0f

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            when {
                mime?.startsWith("video/") == true -> {
                    if (videoCodec == null) videoCodec = mime
                    videoBitrate = format.intOrNull(MediaFormat.KEY_BIT_RATE) ?: videoBitrate
                    colorTransfer = format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER) ?: colorTransfer
                    colorStandard = format.intOrNull(MediaFormat.KEY_COLOR_STANDARD) ?: colorStandard
                    colorRange = format.intOrNull(MediaFormat.KEY_COLOR_RANGE) ?: colorRange
                    if (videoFrameRate <= 0f) videoFrameRate = format.frameRateOrZero()
                }
                mime?.startsWith("audio/") == true -> {
                    if (audioCodec == null) audioCodec = mime
                    audioBitrate = format.intOrNull(MediaFormat.KEY_BIT_RATE) ?: audioBitrate
                    audioChannels = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: audioChannels
                    audioSampleRate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: audioSampleRate
                }
            }
        }

        return TrackProbe(
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            videoBitrate = videoBitrate,
            audioBitrate = audioBitrate,
            colorTransfer = colorTransfer,
            colorStandard = colorStandard,
            colorRange = colorRange,
            audioChannelCount = audioChannels,
            audioSampleRate = audioSampleRate,
            videoFrameRate = videoFrameRate
        )
    }

    // Treat missing/invalid dimensions as a failed match so replacement never proceeds on unverified geometry.
    internal fun sameDimensions(sourceWidth: Int, sourceHeight: Int, outputWidth: Int, outputHeight: Int): Boolean {
        if (sourceWidth <= 0 || sourceHeight <= 0 || outputWidth <= 0 || outputHeight <= 0) return false
        return sourceWidth == outputWidth && sourceHeight == outputHeight
    }

    private fun codecsMatch(left: String?, right: String?): Boolean {
        if (left == null && right == null) return true
        if (left == null || right == null) return false
        return left.equals(right, ignoreCase = true)
    }

    private fun colorMatches(left: Int?, right: Int?): Boolean {
        if (left == null && right == null) return true
        if (left == null || right == null) return false
        return left == right
    }

    private fun optionalExactMatch(left: Int?, right: Int?): Boolean {
        if (left == null && right == null) return true
        if (left == null || right == null) return false
        return left == right
    }

    private fun dimensionLabel(width: Int, height: Int): String {
        return if (width > 0 && height > 0) "${width}x${height}" else "not exposed"
    }

    private fun fpsLabel(fps: Float): String {
        return if (fps > 0f) String.format(Locale.US, "%.1f", fps) else "not exposed"
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

    private fun sampleRateLabel(sampleRate: Int?): String {
        return sampleRate?.let { "${it}Hz" } ?: "not exposed"
    }

    private fun channelLabel(channels: Int?): String {
        return channels?.let { "${it}ch" } ?: "not exposed"
    }

    private fun colorStandardLabel(value: Int?): String {
        return value?.toString() ?: "not exposed"
    }

    private fun colorRangeLabel(value: Int?): String {
        return value?.toString() ?: "not exposed"
    }

    private fun dateVerificationLabel(removedByPrivacy: Boolean, sourcePresent: Boolean, outputPresent: Boolean): String {
        return when {
            removedByPrivacy -> "omitted by privacy setting"
            !sourcePresent -> "not exposed"
            outputPresent -> "verified"
            else -> "unverified"
        }
    }

    private fun locationVerificationLabel(removedByPrivacy: Boolean, sourcePresent: Boolean, outputPresent: Boolean): String {
        return when {
            removedByPrivacy -> "omitted by privacy setting"
            !sourcePresent -> "not required"
            outputPresent -> "verified"
            else -> "unverified"
        }
    }

    private fun statusSuffix(ok: Boolean): String = if (ok) "ok" else "warn"

    private fun MediaFormat.intOrNull(key: String): Int? {
        return if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
    }

    // KEY_FRAME_RATE may be stored as an int or a float depending on the container/framework path.
    private fun MediaFormat.frameRateOrZero(): Float {
        if (!containsKey(MediaFormat.KEY_FRAME_RATE)) return 0f
        return runCatching { getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
            .recoverCatching { getFloat(MediaFormat.KEY_FRAME_RATE) }
            .getOrDefault(0f)
    }
}
