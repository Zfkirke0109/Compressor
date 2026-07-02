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
        encoderMode: EncoderMode = EncoderMode.NOT_EXPOSED
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

        val remuxOnly = modeLabel == "Remux only"
        val originalMode = modeLabel == "Original"
        val videoMatches = sameDimensions(source.originalWidth, source.originalHeight, outputProbe.width, outputProbe.height)
        val fpsComparison = OutputVerificationFormatter.fpsComparison(source.originalFps, outputProbe.fps)
        val remuxFpsBlockReason = OutputVerificationFormatter.remuxFpsBlockReason(source.originalFps, outputProbe.fps)
        val fpsMatches = fpsComparison != VerificationComparison.WARN
        val videoCodecMatches = codecsMatch(sourceTracks.videoCodec, outputTracks.videoCodec)
        val audioCodecMatches = codecsMatch(sourceTracks.audioCodec, outputTracks.audioCodec)
        val hdrMatches = sourceTracks.hdrLabel == "not exposed" || outputTracks.hdrLabel == "not exposed" || sourceTracks.hdrLabel == outputTracks.hdrLabel
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
        val originalVbrWeakFps = originalMode &&
            encoderMode == EncoderMode.VBR_FALLBACK &&
            source.originalFps > 0f &&
            outputProbe.fps <= 0f
        val originalVbrMaterialReduction = originalMode &&
            encoderMode == EncoderMode.VBR_FALLBACK &&
            source.originalSize >= 200L * 1024L * 1024L &&
            outputFile.length() > 0L &&
            outputFile.length().toDouble() / source.originalSize.toDouble() < 0.80

        val replacementSafe = playable && when {
            remuxOnly -> videoMatches && fpsMatches && remuxFpsBlockReason == null && videoCodecMatches && audioCodecMatches && hdrMatches
            originalMode -> videoMatches && fpsMatches && !originalVbrWeakFps && !originalVbrMaterialReduction
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
            originalMode && !videoMatches -> "Original output changed resolution"
            originalMode && !fpsMatches -> "Original output changed FPS"
            originalVbrWeakFps -> "Original VBR fallback could not verify output FPS"
            originalVbrMaterialReduction -> "Original VBR fallback output looked materially reduced"
            else -> null
        }

        val sourceDateText = if (source.metadataSnapshot.hasDate && !privacyMode.removeDate) "source date" else "not required"
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
                fpsLabel(source.originalFps),
                fpsLabel(outputProbe.fps),
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
            date = when {
                privacyMode.removeDate -> "omitted by privacy setting"
                outputMetadata.hasDate -> "verified"
                source.metadataSnapshot.hasDate -> "$sourceDateText will be restored where Android allows"
                else -> "not exposed"
            },
            location = when {
                privacyMode.removeLocation -> "omitted by privacy setting"
                outputMetadata.hasLocation -> "verified"
                source.metadataSnapshot.hasLocation -> "$sourceLocationText will be restored where Android allows"
                else -> "not exposed"
            },
            rotation = "${rotationLabel(source.metadataSnapshot.rotationDegrees)} -> ${rotationLabel(outputProbe.rotationDegrees)}",
            fileSize = "${formatFileSize(source.originalSize)} -> ${formatFileSize(outputFile.length())}",
            replacementSafe = replacementSafe,
            replacementBlockReason = blockReason
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
        val audioBitrate: Int,
        val hdrLabel: String
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
            TrackProbe(null, null, 0, "not exposed")
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
            TrackProbe(null, null, 0, "not exposed")
        } finally {
            extractor.release()
        }
    }

    private fun readTrackProbe(extractor: MediaExtractor): TrackProbe {
        var videoCodec: String? = null
        var audioCodec: String? = null
        var audioBitrate = 0
        var colorTransfer: Int? = null
        var colorStandard: Int? = null

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            when {
                mime?.startsWith("video/") == true -> {
                    if (videoCodec == null) videoCodec = mime
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

        return TrackProbe(videoCodec, audioCodec, audioBitrate, hdrLabel)
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

    private fun MediaFormat.intOrNull(key: String): Int? {
        return if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
    }
}
