package compress.joshattic.us

import android.content.Context
import android.media.MediaCodecInfo
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
        val rotationDegrees: Int?,
        val frameCount: Int = 0
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
        val videoFrameRate: Float = 0f,
        val hdrStaticInfoDigest: String? = null,
        val hdr10PlusInfoDigest: String? = null,
        val videoProfile: Int? = null
    ) {
        val hdrMetadataPresent: Boolean
            get() = hdrStaticInfoDigest != null || hdr10PlusInfoDigest != null

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
        val privacyMode: MetadataPrivacyMode,
        // Video frame count of the source when the platform exposes it (API 28+); 0 = unknown.
        val sourceFrameCount: Int = 0,
        // When a PL encode's target was justified by on-device pixel probes, the bitrate floor
        // the verifier enforces is the pixel-proven one (with encoder-undershoot tolerance
        // already applied by the caller), not the class-level inference floor. Sampled pixel
        // certification of the final output remains mandatory for such encodes (enforced in
        // the batch pipeline); this field only stops the structural floor from rejecting an
        // encode the pixels already justified. Null = classic behavior, byte-identical.
        val pixelProvenVideoBitrateFloor: Int? = null
    )

    fun verify(
        context: Context,
        source: BatchVideoItem,
        outputFile: File,
        modeLabel: String,
        privacyMode: MetadataPrivacyMode,
        pixelProvenVideoBitrateFloor: Int? = null
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
                privacyMode = privacyMode,
                sourceFrameCount = readSourceFrameCount(context, source.sourceUri),
                pixelProvenVideoBitrateFloor = pixelProvenVideoBitrateFloor
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
        // VFR sources (e.g. Samsung Super Steady) expose a nominal capture rate through the
        // retriever (60) but an averaged rate through the extractor (~57). Comparing a nominal
        // source value against a measured output value falsely fails FPS verification on a
        // byte-identical stream copy — so when BOTH sides expose the extractor's track frame
        // rate, compare those (same measurement method on both files).
        val sameMethodFps = input.sourceTrackProbe.videoFrameRate > 0f &&
            input.outputTrackProbe.videoFrameRate > 0f
        val fpsSourceShown = if (sameMethodFps) input.sourceTrackProbe.videoFrameRate else input.source.frameRate
        val fpsOutputShown = if (sameMethodFps) input.outputTrackProbe.videoFrameRate else input.outputFileProbe.fps
        val fpsComparison = OutputVerificationFormatter.fpsComparison(
            fpsSourceShown,
            fpsOutputShown
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
        val colorComparison = compareColorTransition(
            input.mode,
            input.sourceTrackProbe,
            input.outputTrackProbe
        )
        val hdrMatches = colorComparison.transferMatches &&
            colorComparison.hdrMetadataMatches &&
            colorComparison.bitDepthMatches &&
            colorComparison.profileMatches
        val standardMatches = colorComparison.standardMatches
        val rangeMatches = colorComparison.rangeMatches
        // Duration parity closes the truncated-output hole: a 5-second output of a 60-second
        // source must never pass Perceptually Lossless or Remux verification, no matter how good
        // its per-field metadata looks (a truncated file is also "strictly smaller", so without
        // this check it could even qualify for replacement).
        val durationMatches = input.source.durationMs <= 0L ||
            input.outputFileProbe.durationMs <= 0L ||
            abs(input.outputFileProbe.durationMs - input.source.durationMs) <=
            maxOf(500L, (input.source.durationMs * 0.02).toLong())

        // Frame-count parity (when the platform exposes it on both files) catches dropped or
        // duplicated frames that metadata-level FPS tolerance cannot see.
        val frameCountMatches = input.sourceFrameCount <= 0 ||
            input.outputFileProbe.frameCount <= 0 ||
            abs(input.outputFileProbe.frameCount - input.sourceFrameCount) <=
            maxOf(2, (input.sourceFrameCount * 0.01).toInt())

        val rotationMatches = sameDisplayOrientation(
            mode = input.mode,
            sourceWidth = input.source.width,
            sourceHeight = input.source.height,
            displayDimensionsMatch = videoMatches,
            sourceRotationDegrees = input.sourceMetadata.rotationDegrees,
            outputRotationDegrees = input.outputFileProbe.rotationDegrees
        )
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

        // Codec-aware floor: the output track's codec decides how many bits perceptual quality needs.
        // For a same-codec stream copy this is byte-identical; for a cross-codec PL encode (H.264 ->
        // HEVC) it lowers the source-codec-calibrated absolute floor so an efficient valid output is
        // not falsely rejected. The ratio floor still guards perceptual quality.
        val minPerceptualVideoBitrate = input.pixelProvenVideoBitrateFloor
            ?: BatchQualityBitratePolicy.perceptualLosslessBitrateFloor(
                input.source,
                input.outputTrackProbe.videoCodec
            )
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
            (!(input.source.isHdr ||
                (input.mode == BatchQualityMode.PERCEPTUAL_LOSSLESS && colorComparison.sourceHasHdrRisk)
                ) || (
                input.outputTrackProbe.colorTransfer != null &&
                    input.outputTrackProbe.colorStandard != null &&
                    input.outputTrackProbe.colorRange != null
                ))

        val perceptuallyLosslessVerified = playable &&
            criticalFieldsComplete &&
            durationMatches &&
            frameCountMatches &&
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
            durationMatches &&
            frameCountMatches &&
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
            !durationMatches -> "output duration differs from the source (possible truncation)"
            !frameCountMatches -> "output video frame count differs from the source"
            input.mode == BatchQualityMode.REMUX_ONLY && !criticalFieldsComplete -> "stream-copy verification was incomplete"
            input.mode == BatchQualityMode.REMUX_ONLY && !videoMatches -> "remux output changed resolution"
            input.mode == BatchQualityMode.REMUX_ONLY && !rotationMatches -> "remux output changed display orientation"
            input.mode == BatchQualityMode.REMUX_ONLY && fpsComparison != VerificationTransitionStatus.MATCH -> "remux output changed FPS"
            input.mode == BatchQualityMode.REMUX_ONLY && !videoCodecMatches -> "remux output changed video codec"
            input.mode == BatchQualityMode.REMUX_ONLY && !audioCodecMatches -> "remux output changed audio codec"
            input.mode == BatchQualityMode.REMUX_ONLY && !audioBitratePass -> "remux output changed audio bitrate"
            input.mode == BatchQualityMode.REMUX_ONLY && !(hdrMatches && standardMatches && rangeMatches) -> "remux output changed HDR/color metadata"
            input.mode == BatchQualityMode.PERCEPTUAL_LOSSLESS && !criticalFieldsComplete -> "perceptually lossless verification is unverified because critical fields were not exposed"
            input.mode == BatchQualityMode.PERCEPTUAL_LOSSLESS && !videoMatches -> "perceptually lossless output changed resolution"
            input.mode == BatchQualityMode.PERCEPTUAL_LOSSLESS && !rotationMatches -> "perceptually lossless output changed display orientation"
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
                fpsLabel(fpsSourceShown),
                fpsLabel(fpsOutputShown),
                fpsComparison
            ),
            videoBitrate = "${bitrateLabel(sourceVideoBitrate)} -> ${bitrateLabel(effectiveOutputVideoBitrate)}${if (videoBitrateMeasured) " (measured from size)" else ""}${if (input.mode == BatchQualityMode.PERCEPTUAL_LOSSLESS) " (floor ${bitrateLabel(minPerceptualVideoBitrate)})" else ""}",
            videoCodec = "${codecLabel(input.sourceTrackProbe.videoCodec)} -> ${codecLabel(input.outputTrackProbe.videoCodec)} ${statusSuffix(videoCodecMatches)}",
            audioCodec = "${codecLabel(input.sourceTrackProbe.audioCodec)} -> ${codecLabel(input.outputTrackProbe.audioCodec)} ${statusSuffix(audioCodecMatches)}",
            audioDetails = "${sampleRateLabel(input.sourceTrackProbe.audioSampleRate)}/${channelLabel(input.sourceTrackProbe.audioChannelCount)} -> ${sampleRateLabel(input.outputTrackProbe.audioSampleRate)}/${channelLabel(input.outputTrackProbe.audioChannelCount)} ${statusSuffix(audioShapeMatches)}",
            audioBitrate = "${bitrateLabel(input.sourceTrackProbe.audioBitrate)} -> ${bitrateLabel(effectiveOutputAudioBitrate)}${if (audioLooksStreamCopied) " (stream copied)" else ""} ${statusSuffix(audioBitratePass)}",
            hdr = "${input.sourceTrackProbe.hdrLabel} -> ${input.outputTrackProbe.hdrLabel}" +
                if (colorComparison.basis == ColorMatchBasis.MEDIA3_ASSUMED_SDR) {
                    " (Media3 assumed SDR default) ${statusSuffix(hdrMatches)}"
                } else {
                    " ${statusSuffix(hdrMatches)}"
                },
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
            },
            durationParity = "${input.source.durationMs}ms -> ${input.outputFileProbe.durationMs}ms ${statusSuffix(durationMatches)}" +
                if (input.sourceFrameCount > 0 && input.outputFileProbe.frameCount > 0) {
                    "; frames ${input.sourceFrameCount} -> ${input.outputFileProbe.frameCount} ${statusSuffix(frameCountMatches)}"
                } else {
                    "; frames not exposed"
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
                rotationDegrees = rotation,
                frameCount = readFrameCount(retriever)
            )
        } catch (_: Exception) {
            FileProbe(0, 0, 0f, 0L, null)
        } finally {
            runCatching { retriever.release() }
        }
    }

    // METADATA_KEY_VIDEO_FRAME_COUNT is available on API 28+; 0 means "not exposed".
    private fun readFrameCount(retriever: MediaMetadataRetriever): Int {
        if (android.os.Build.VERSION.SDK_INT < 28) return 0
        return runCatching {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
        }.getOrDefault(0)
    }

    // Source-side frame count for parity checking, probed from the source Uri.
    private fun readSourceFrameCount(context: Context, uri: Uri): Int {
        if (android.os.Build.VERSION.SDK_INT < 28) return 0
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            readFrameCount(retriever)
        } catch (_: Exception) {
            0
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
        var hdrStaticInfoDigest: String? = null
        var hdr10PlusInfoDigest: String? = null
        var videoProfile: Int? = null

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
                    videoProfile = format.intOrNull(MediaFormat.KEY_PROFILE) ?: videoProfile
                    hdrStaticInfoDigest =
                        format.byteBufferDigest(MediaFormat.KEY_HDR_STATIC_INFO) ?: hdrStaticInfoDigest
                    hdr10PlusInfoDigest =
                        format.byteBufferDigest(MediaFormat.KEY_HDR10_PLUS_INFO) ?: hdr10PlusInfoDigest
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
            videoFrameRate = videoFrameRate,
            hdrStaticInfoDigest = hdrStaticInfoDigest,
            hdr10PlusInfoDigest = hdr10PlusInfoDigest,
            videoProfile = videoProfile
        )
    }

    // Both probes normalize 90/270-degree rotation before reaching this check, so an exact ordered
    // pair is display-space parity. Keep this strict: no tolerance, scaling, cropping, or raw edge
    // transposition is accepted here.
    internal fun sameDimensions(sourceWidth: Int, sourceHeight: Int, outputWidth: Int, outputHeight: Int): Boolean {
        if (sourceWidth <= 0 || sourceHeight <= 0 || outputWidth <= 0 || outputHeight <= 0) return false
        return sourceWidth == outputWidth && sourceHeight == outputHeight
    }

    /**
     * Media3 landscape-encodes portrait video by default and writes a compensating 90/270-degree
     * orientation hint. Raw rotation equality is therefore not required for a real portrait
     * transcode, but the display-normalized dimensions must match exactly. Stream copies remain
     * strict because a remux must preserve the original orientation metadata unchanged.
     */
    internal fun sameDisplayOrientation(
        mode: BatchQualityMode,
        sourceWidth: Int,
        sourceHeight: Int,
        displayDimensionsMatch: Boolean,
        sourceRotationDegrees: Int?,
        outputRotationDegrees: Int?
    ): Boolean {
        if (!displayDimensionsMatch) return false
        val sourceRotation = sourceRotationDegrees ?: 0
        val outputRotation = outputRotationDegrees ?: 0
        if (sourceRotation !in VALID_ROTATIONS || outputRotation !in VALID_ROTATIONS) return false
        if (sourceRotation == outputRotation) return true
        if (mode != BatchQualityMode.PERCEPTUAL_LOSSLESS || sourceHeight <= sourceWidth) return false

        // The only non-exact representation accepted is Media3's default for a coded portrait
        // source: landscape-coded output with a compensating quarter-turn hint. Never accept 0/180
        // as an alternative output hint, or an arbitrary quarter-turn delta from a rotated source.
        return sourceRotation == 0 && (outputRotation == 90 || outputRotation == 270)
    }

    private val VALID_ROTATIONS = setOf(0, 90, 180, 270)

    private fun codecsMatch(left: String?, right: String?): Boolean {
        if (left == null && right == null) return true
        if (left == null || right == null) return false
        return left.equals(right, ignoreCase = true)
    }

    internal enum class ColorMatchBasis {
        EXACT,
        BOTH_NOT_EXPOSED,
        MEDIA3_ASSUMED_SDR,
        MISMATCH
    }

    internal data class ColorTransitionComparison(
        val transferMatches: Boolean,
        val standardMatches: Boolean,
        val rangeMatches: Boolean,
        val hdrMetadataMatches: Boolean,
        val bitDepthMatches: Boolean,
        val profileMatches: Boolean,
        val sourceHasHdrRisk: Boolean,
        val basis: ColorMatchBasis
    ) {
        val matches: Boolean
            get() = transferMatches && standardMatches && rangeMatches && hdrMetadataMatches &&
                bitDepthMatches && profileMatches
    }

    /**
     * Media3 normalizes absent/invalid input color metadata to its canonical SDR BT.709 limited
     * representation before encoding. Accept that specific, asymmetric transition only for a
     * Perceptually Lossless source with no HDR, wide-color, non-default-range, static-HDR, or
     * high-bit-depth profile evidence. An absent source field is never a general wildcard, and a
     * remux remains exact because a stream copy must not rewrite container color metadata.
     */
    internal fun compareColorTransition(
        mode: BatchQualityMode,
        source: TrackProbe,
        output: TrackProbe
    ): ColorTransitionComparison {
        val sourceHasHdrRisk = source.hasHdrOrNonDefaultColorRisk()
        val allowMedia3SdrDefault = mode == BatchQualityMode.PERCEPTUAL_LOSSLESS && !sourceHasHdrRisk
        val transfer = compareColorField(
            source.colorTransfer,
            output.colorTransfer,
            MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            allowMedia3SdrDefault
        )
        val standard = compareColorField(
            source.colorStandard,
            output.colorStandard,
            MediaFormat.COLOR_STANDARD_BT709,
            allowMedia3SdrDefault
        )
        val range = compareColorField(
            source.colorRange,
            output.colorRange,
            MediaFormat.COLOR_RANGE_LIMITED,
            allowMedia3SdrDefault
        )
        val staticHdrMatches = source.hdrStaticInfoDigest == output.hdrStaticInfoDigest
        // KEY_HDR10_PLUS_INFO does not prove that all per-frame dynamic metadata survived a
        // transcode. A stream copy can compare the exposed payload; a real encode fails closed.
        val hdr10PlusMatches = if (mode == BatchQualityMode.REMUX_ONLY) {
            source.hdr10PlusInfoDigest == output.hdr10PlusInfoDigest
        } else {
            source.hdr10PlusInfoDigest == null && output.hdr10PlusInfoDigest == null
        }
        val hdrMetadataMatches = staticHdrMatches && hdr10PlusMatches
        val sourceRequiresTenBit = source.requiresTenBitOutput()
        val bitDepthMatches = !sourceRequiresTenBit ||
            profileSupportsTenBit(output.videoCodec, output.videoProfile)
        val profileMatches = mode != BatchQualityMode.REMUX_ONLY ||
            source.videoProfile == output.videoProfile
        val bases = listOf(transfer.second, standard.second, range.second)
        val basis = when {
            !hdrMetadataMatches || !bitDepthMatches || !profileMatches ||
                bases.any { it == ColorMatchBasis.MISMATCH } -> ColorMatchBasis.MISMATCH
            bases.any { it == ColorMatchBasis.MEDIA3_ASSUMED_SDR } -> ColorMatchBasis.MEDIA3_ASSUMED_SDR
            bases.all { it == ColorMatchBasis.BOTH_NOT_EXPOSED } -> ColorMatchBasis.BOTH_NOT_EXPOSED
            else -> ColorMatchBasis.EXACT
        }
        return ColorTransitionComparison(
            transferMatches = transfer.first,
            standardMatches = standard.first,
            rangeMatches = range.first,
            hdrMetadataMatches = hdrMetadataMatches,
            bitDepthMatches = bitDepthMatches,
            profileMatches = profileMatches,
            sourceHasHdrRisk = sourceHasHdrRisk,
            basis = basis
        )
    }

    private fun compareColorField(
        source: Int?,
        output: Int?,
        media3SdrDefault: Int,
        allowMedia3SdrDefault: Boolean
    ): Pair<Boolean, ColorMatchBasis> = when {
        source != null && source == output -> true to ColorMatchBasis.EXACT
        source == null && output == null -> true to ColorMatchBasis.BOTH_NOT_EXPOSED
        source == null && output == media3SdrDefault && allowMedia3SdrDefault ->
            true to ColorMatchBasis.MEDIA3_ASSUMED_SDR
        else -> false to ColorMatchBasis.MISMATCH
    }

    private fun TrackProbe.hasHdrOrNonDefaultColorRisk(): Boolean {
        if (hdrMetadataPresent || videoCodec == MimeTypes.VIDEO_DOLBY_VISION) return true
        if (colorTransfer != null && colorTransfer != MediaFormat.COLOR_TRANSFER_SDR_VIDEO) return true
        if (colorStandard != null && colorStandard != MediaFormat.COLOR_STANDARD_BT709) return true
        if (colorRange != null && colorRange != MediaFormat.COLOR_RANGE_LIMITED) return true
        return profileSupportsTenBit(videoCodec, videoProfile)
    }

    private fun TrackProbe.requiresTenBitOutput(): Boolean =
        videoCodec == MimeTypes.VIDEO_DOLBY_VISION ||
            colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
            colorTransfer == MediaFormat.COLOR_TRANSFER_HLG ||
            colorStandard == MediaFormat.COLOR_STANDARD_BT2020 ||
            hdrMetadataPresent ||
            profileSupportsTenBit(videoCodec, videoProfile)

    private fun profileSupportsTenBit(mime: String?, profile: Int?): Boolean {
        if (mime == MimeTypes.VIDEO_DOLBY_VISION) return true
        if (profile == null) return false
        return when (mime) {
            MimeTypes.VIDEO_H264 -> profile in setOf(
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10,
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422,
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444
            )
            MimeTypes.VIDEO_H265 -> profile in setOf(
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
            )
            MimeTypes.VIDEO_VP9 -> profile in setOf(
                MediaCodecInfo.CodecProfileLevel.VP9Profile2,
                MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR,
                MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus,
                MediaCodecInfo.CodecProfileLevel.VP9Profile3,
                MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR,
                MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR10Plus
            )
            MimeTypes.VIDEO_AV1 -> profile in setOf(
                MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10,
                MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10,
                MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus
            )
            else -> false
        }
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

    private fun MediaFormat.byteBufferDigest(key: String): String? {
        if (!containsKey(key)) return null
        return runCatching {
            val copy = getByteBuffer(key)?.duplicate() ?: return@runCatching null
            val bytes = ByteArray(copy.remaining())
            copy.get(bytes)
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    // KEY_FRAME_RATE may be stored as an int or a float depending on the container/framework path.
    private fun MediaFormat.frameRateOrZero(): Float {
        if (!containsKey(MediaFormat.KEY_FRAME_RATE)) return 0f
        return runCatching { getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
            .recoverCatching { getFloat(MediaFormat.KEY_FRAME_RATE) }
            .getOrDefault(0f)
    }
}
