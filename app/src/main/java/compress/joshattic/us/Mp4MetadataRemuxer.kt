package compress.joshattic.us

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

data class Mp4MetadataRemuxResult(
    val outputFile: File,
    val didRemux: Boolean,
    val locationWritten: Boolean,
    val message: String
)

object Mp4MetadataRemuxer {
    private const val DEFAULT_BUFFER_SIZE = 4 * 1024 * 1024

    fun remuxWithSourceMetadata(
        context: Context,
        encodedFile: File,
        snapshot: VideoMetadataSnapshot
    ): Mp4MetadataRemuxResult {
        if (!encodedFile.exists() || encodedFile.length() <= 0L) {
            return Mp4MetadataRemuxResult(
                encodedFile,
                didRemux = false,
                locationWritten = false,
                message = "metadata remux skipped: encoded file missing"
            )
        }

        val needsLocation = snapshot.hasLocation
        val needsRotation = snapshot.rotationDegrees != null

        if (!needsLocation && !needsRotation) {
            return Mp4MetadataRemuxResult(
                encodedFile,
                didRemux = false,
                locationWritten = false,
                message = "metadata remux skipped: no source location/rotation exposed"
            )
        }

        val parent = encodedFile.parentFile ?: context.cacheDir
        val tempFile = File(parent, encodedFile.nameWithoutExtension + "_remux.mp4")
        if (tempFile.exists()) tempFile.delete()

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        return try {
            extractor.setDataSource(encodedFile.absolutePath)

            muxer = MediaMuxer(
                tempFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            val trackMap = mutableMapOf<Int, Int>()
            var maxBufferSize = DEFAULT_BUFFER_SIZE

            for (trackIndex in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()

                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue

                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxBufferSize = maxOf(
                        maxBufferSize,
                        format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) + 1024
                    )
                }

                val outputTrackIndex = muxer.addTrack(format)
                trackMap[trackIndex] = outputTrackIndex
            }

            if (trackMap.isEmpty()) {
                return Mp4MetadataRemuxResult(
                    encodedFile,
                    didRemux = false,
                    locationWritten = false,
                    message = "metadata remux skipped: no muxable tracks"
                )
            }

            snapshot.rotationDegrees?.let { rotation ->
                if (rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270) {
                    muxer.setOrientationHint(rotation)
                }
            }

            var wroteLocation = false
            if (snapshot.hasLocation) {
                val lat = snapshot.latitude!!
                val lon = snapshot.longitude!!
                if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                    muxer.setLocation(lat.toFloat(), lon.toFloat())
                    wroteLocation = true
                }
            }

            muxer.start()
            muxerStarted = true

            trackMap.keys.forEach { extractor.selectTrack(it) }

            val buffer = ByteBuffer.allocateDirect(maxBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                val inputTrackIndex = extractor.sampleTrackIndex
                if (inputTrackIndex < 0) break

                val outputTrackIndex = trackMap[inputTrackIndex]
                if (outputTrackIndex == null) {
                    extractor.advance()
                    continue
                }

                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                bufferInfo.set(
                    0,
                    sampleSize,
                    extractor.sampleTime,
                    extractor.sampleFlags
                )

                muxer.writeSampleData(outputTrackIndex, buffer, bufferInfo)
                extractor.advance()
            }

            runCatching { muxer.stop() }
            muxerStarted = false
            muxer.release()
            muxer = null
            extractor.release()

            if (tempFile.exists() && tempFile.length() > 0L) {
                val replaced = replaceFile(tempFile, encodedFile)
                val out = if (replaced) encodedFile else tempFile
                Mp4MetadataRemuxResult(
                    outputFile = out,
                    didRemux = true,
                    locationWritten = wroteLocation,
                    message = if (wroteLocation) {
                        "metadata remux complete: source location written"
                    } else {
                        "metadata remux complete"
                    }
                )
            } else {
                Mp4MetadataRemuxResult(
                    encodedFile,
                    didRemux = false,
                    locationWritten = false,
                    message = "metadata remux failed: empty remux output"
                )
            }
        } catch (e: Exception) {
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
            runCatching { tempFile.delete() }

            Mp4MetadataRemuxResult(
                encodedFile,
                didRemux = false,
                locationWritten = false,
                message = "metadata remux failed: ${e.javaClass.simpleName}"
            )
        }
    }

    private fun replaceFile(source: File, target: File): Boolean {
        return runCatching {
            if (!source.exists() || source.length() <= 0L) return false

            val backup = File(target.parentFile, target.name + ".bak")
            if (backup.exists()) backup.delete()

            val renamedOriginal = target.renameTo(backup)
            if (!renamedOriginal) return false

            val renamedNew = source.renameTo(target)
            if (!renamedNew) {
                backup.renameTo(target)
                return false
            }

            backup.delete()
            true
        }.getOrDefault(false)
    }
}
