#!/usr/bin/env python3
"""
Fixed Compressor Phase 1 metadata preservation applier.

Run from the repository root in Codespaces:

  python3 apply_phase1_metadata_PERMANENT_FIXED.py
  ./gradlew :app:assembleDebug
  git status
  git add .
  git commit -m "Implement Phase 1 metadata preservation"
  git push -u origin feature-phase1_metadata-preservation

This script is intentionally self-contained and uses triple-quoted replacement
blocks so it does not create the unterminated-string error from the previous file.
"""

from pathlib import Path

ROOT = Path.cwd()


def die(msg: str) -> None:
    raise SystemExit(f"\nERROR: {msg}\n")


def read(path: str) -> str:
    file_path = ROOT / path
    if not file_path.exists():
        die(f"Missing expected file: {path}. Run this from the Compressor repo root.")
    return file_path.read_text()


def write(path: str, content: str) -> None:
    file_path = ROOT / path
    file_path.parent.mkdir(parents=True, exist_ok=True)
    file_path.write_text(content)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        die(f"Could not find expected block for: {label}")
    return text.replace(old, new, 1)


print("Applying fixed Phase 1 metadata preservation changes...")

# gradle/libs.versions.toml
libs = read("gradle/libs.versions.toml")
if 'exifinterface = ' not in libs:
    libs = replace_once(
        libs,
        'shizuku = "13.1.5"\n',
        'shizuku = "13.1.5"\nexifinterface = "1.3.7"\n',
        "add exifinterface version",
    )
if "androidx-exifinterface" not in libs:
    libs = replace_once(
        libs,
        'androidx-media3-common = { group = "androidx.media3", name = "media3-common", version.ref = "media3" }\n',
        'androidx-media3-common = { group = "androidx.media3", name = "media3-common", version.ref = "media3" }\n'
        'androidx-exifinterface = { group = "androidx.exifinterface", name = "exifinterface", version.ref = "exifinterface" }\n',
        "add exifinterface library",
    )
write("gradle/libs.versions.toml", libs)

# app/build.gradle.kts
gradle = read("app/build.gradle.kts")
gradle = gradle.replace("versionCode = 24", "versionCode = 25")
gradle = gradle.replace('versionName = "1.5.9"', 'versionName = "1.6.0"')
if "libs.androidx.exifinterface" not in gradle:
    gradle = replace_once(
        gradle,
        "    implementation(libs.androidx.compose.animation)\n",
        "    implementation(libs.androidx.compose.animation)\n    implementation(libs.androidx.exifinterface)\n",
        "add exifinterface dependency",
    )
write("app/build.gradle.kts", gradle)

# New metadata snapshot helper.
write("app/src/main/java/compress/joshattic/us/VideoMetadataSnapshot.kt", r"""package compress.joshattic.us

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class VideoMetadataSnapshot(
    val dateTakenMs: Long? = null,
    val dateModifiedSeconds: Long? = null,
    val dateAddedSeconds: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val rawLocationTag: String? = null,
    val rotationDegrees: Int? = null,
    val relativePath: String? = null
) {
    val hasDate: Boolean
        get() = dateTakenMs != null || dateModifiedSeconds != null || dateAddedSeconds != null

    val hasLocation: Boolean
        get() = latitude != null && longitude != null

    val hasRawLocation: Boolean
        get() = !rawLocationTag.isNullOrBlank()
}

data class MetadataRestoreReport(
    val dateAttempted: Boolean,
    val dateRestoredToMediaStore: Boolean,
    val dateRestoredToExif: Boolean,
    val locationAttempted: Boolean,
    val locationRestoredToMediaStore: Boolean,
    val locationRestoredToExif: Boolean,
    val sourceHadRawLocation: Boolean,
    val notes: List<String> = emptyList()
) {
    val dateRestored: Boolean
        get() = dateRestoredToMediaStore || dateRestoredToExif

    val locationRestored: Boolean
        get() = locationRestoredToMediaStore || locationRestoredToExif

    fun summary(): String {
        val parts = mutableListOf<String>()

        parts += when {
            !dateAttempted -> "date: no source date exposed"
            dateRestored -> "date: restored"
            else -> "date: restore best-effort"
        }

        parts += when {
            !locationAttempted && !sourceHadRawLocation -> "location: none in source"
            locationRestored -> "location: restored"
            sourceHadRawLocation -> "location: detected; remux attempted"
            else -> "location: best-effort"
        }

        if (notes.isNotEmpty()) parts += notes.joinToString("; ")
        return parts.joinToString(" • ")
    }
}

object VideoMetadataPreserver {
    fun capture(
        context: Context,
        uri: Uri,
        retriever: MediaMetadataRetriever? = null
    ): VideoMetadataSnapshot {
        var snapshot = VideoMetadataSnapshot()

        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    snapshot = snapshot.copy(
                        dateTakenMs = cursor.longOrNull(MediaStore.MediaColumns.DATE_TAKEN),
                        dateModifiedSeconds = cursor.longOrNull(MediaStore.MediaColumns.DATE_MODIFIED),
                        dateAddedSeconds = cursor.longOrNull(MediaStore.MediaColumns.DATE_ADDED),
                        relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            cursor.stringOrNull(MediaStore.MediaColumns.RELATIVE_PATH)
                        } else {
                            null
                        }
                    )
                }
            }
        }

        val rawLocation = runCatching {
            retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
        }.getOrNull()

        val parsedLocation = parseIso6709Location(rawLocation)

        val rotation = runCatching {
            retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
        }.getOrNull()

        return snapshot.copy(
            latitude = parsedLocation?.first,
            longitude = parsedLocation?.second,
            rawLocationTag = rawLocation,
            rotationDegrees = rotation
        )
    }

    fun restoreAfterReplacement(
        context: Context,
        sourceUri: Uri,
        snapshot: VideoMetadataSnapshot,
        replacedFilePath: String?
    ): MetadataRestoreReport {
        val notes = mutableListOf<String>()

        val values = ContentValues().apply {
            snapshot.dateTakenMs?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
            snapshot.dateModifiedSeconds?.let { put(MediaStore.MediaColumns.DATE_MODIFIED, it) }
            snapshot.dateAddedSeconds?.let { put(MediaStore.MediaColumns.DATE_ADDED, it) }

            if (snapshot.hasLocation) {
                put("latitude", snapshot.latitude)
                put("longitude", snapshot.longitude)
            }
        }

        val mediaStoreUpdated = if (values.size() > 0) {
            val targetUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { MediaStore.getMediaUri(context, sourceUri) }.getOrNull() ?: sourceUri
            } else {
                sourceUri
            }

            runCatching {
                context.contentResolver.update(targetUri, values, null, null)
            }.getOrElse {
                notes += "MediaStore update failed: ${it.javaClass.simpleName}"
                0
            } > 0
        } else {
            false
        }

        val exifUpdated = if (!replacedFilePath.isNullOrBlank()) {
            restoreExifBestEffort(File(replacedFilePath), snapshot, notes)
        } else {
            false
        }

        return MetadataRestoreReport(
            dateAttempted = snapshot.hasDate,
            dateRestoredToMediaStore = snapshot.hasDate && mediaStoreUpdated,
            dateRestoredToExif = snapshot.hasDate && exifUpdated,
            locationAttempted = snapshot.hasLocation,
            locationRestoredToMediaStore = snapshot.hasLocation && mediaStoreUpdated,
            locationRestoredToExif = snapshot.hasLocation && exifUpdated,
            sourceHadRawLocation = snapshot.hasRawLocation,
            notes = notes
        )
    }

    fun applyToNewGalleryValues(values: ContentValues, snapshot: VideoMetadataSnapshot) {
        snapshot.dateTakenMs?.let {
            values.put(MediaStore.MediaColumns.DATE_TAKEN, it)
            values.put(MediaStore.Video.Media.DATE_ADDED, it / 1000L)
        }

        snapshot.dateModifiedSeconds?.let {
            values.put(MediaStore.Video.Media.DATE_MODIFIED, it)
        }

        if (snapshot.hasLocation) {
            values.put("latitude", snapshot.latitude)
            values.put("longitude", snapshot.longitude)
        }
    }

    private fun restoreExifBestEffort(
        file: File,
        snapshot: VideoMetadataSnapshot,
        notes: MutableList<String>
    ): Boolean {
        if (!file.exists() || file.length() <= 0L) return false

        return runCatching {
            val exif = ExifInterface(file.absolutePath)

            snapshot.dateTakenMs?.let { dateTaken ->
                val formatted = formatExifDate(dateTaken)
                exif.setAttribute(ExifInterface.TAG_DATETIME, formatted)
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, formatted)
                exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, formatted)
            }

            if (snapshot.hasLocation) {
                exif.setLatLong(snapshot.latitude!!, snapshot.longitude!!)
            }

            exif.saveAttributes()
            true
        }.getOrElse {
            notes += "Exif restore skipped: ${it.javaClass.simpleName}"
            false
        }
    }

    private fun formatExifDate(timeMs: Long): String {
        return SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(timeMs))
    }

    private fun parseIso6709Location(value: String?): Pair<Double, Double>? {
        if (value.isNullOrBlank()) return null

        val normalized = value.trim().removeSuffix("/")
        val match = Regex("^([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)")
            .find(normalized)
            ?: return null

        val latitude = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: return null
        val longitude = match.groupValues.getOrNull(2)?.toDoubleOrNull() ?: return null

        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return latitude to longitude
    }

    private fun Cursor.longOrNull(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else null
    }

    private fun Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }
}
""")

# New MP4 remux helper.
write("app/src/main/java/compress/joshattic/us/Mp4MetadataRemuxer.kt", r"""package compress.joshattic.us

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
""")

# Patch BatchCompressorViewModel.kt with triple-quoted strings to avoid syntax breakage.
vm_path = "app/src/main/java/compress/joshattic/us/BatchCompressorViewModel.kt"
vm = read(vm_path)

vm = replace_once(
    vm,
    """    val durationMs: Long,
    val isAlreadyCompressed: Boolean = false,
""",
    """    val durationMs: Long,
    val metadataSnapshot: VideoMetadataSnapshot = VideoMetadataSnapshot(),
    val isAlreadyCompressed: Boolean = false,
""",
    "BatchVideoItem metadataSnapshot",
)

vm = replace_once(
    vm,
    """            val quality = qualityFromLabel(_uiState.value.qualityPreset)
            val items = distinct.mapNotNull { uri ->
""",
    """            val quality = qualityFromLabel(_uiState.value.qualityPreset)
            val codec = codecFromLabel(_uiState.value.codecOption)
            val frameRate = frameRateFromLabel(_uiState.value.frameRateOption)
            val items = distinct.mapNotNull { uri ->
""",
    "loadUris estimator variables",
)

vm = vm.replace(
    "item.copy(targetOutputSize = estimateOutputSize(item, quality))",
    "item.copy(targetOutputSize = estimateOutputSize(item, quality, codec, frameRate))",
    1,
)

vm = replace_once(
    vm,
    """                val method = when {
                    writable -> "Android writable document"
                    shizukuReady -> "Shizuku path fallback"
                    path == null -> "No direct path; use in-app picker for best replacement"
                    else -> "Needs Shizuku permission or writable document access"
                }
                "${item.originalName}: $method"
""",
    """                val method = when {
                    writable -> "Android writable document"
                    shizukuReady -> "Shizuku path fallback"
                    path == null -> "No direct path; use in-app picker for best replacement"
                    else -> "Needs Shizuku permission or writable document access"
                }
                val metadata = when {
                    item.metadataSnapshot.hasDate && item.metadataSnapshot.hasLocation -> "date + location detected"
                    item.metadataSnapshot.hasDate -> "date detected"
                    item.metadataSnapshot.hasLocation -> "location detected"
                    else -> "no source date/location exposed"
                }
                "${item.originalName}: $method • $metadata"
""",
    "test access metadata status",
)

vm = vm.replace(
    "targetOutputSize = estimateOutputSize(item, quality),",
    "targetOutputSize = estimateOutputSize(item, quality, codec, frameRate),",
    1,
)

vm = vm.replace(
    """targetOutputSize = estimateOutputSize(it, quality),
                        message = "Compressing: 0 MB / est ${formatFileSize(estimateOutputSize(it, quality))}""",
    """targetOutputSize = estimateOutputSize(it, quality, codec, frameRate),
                        message = "Compressing: 0 MB / est ${formatFileSize(estimateOutputSize(it, quality, codec, frameRate))}""",
    1,
)

vm = replace_once(
    vm,
    """                    val outputFile = compressOne(context, item, index, quality, frameRate, preferredMime)
                    val outputUri = Uri.fromFile(outputFile)
                    val outputSize = outputFile.length()

                    updateItem(index) {
""",
    """                    val encodedFile = compressOne(context, item, index, quality, frameRate, preferredMime)
                    val remuxResult = withContext(Dispatchers.IO) {
                        Mp4MetadataRemuxer.remuxWithSourceMetadata(context, encodedFile, item.metadataSnapshot)
                    }
                    val outputFile = remuxResult.outputFile
                    val outputUri = Uri.fromFile(outputFile)
                    val outputSize = outputFile.length()

                    updateItem(index) {
""",
    "remux after encode",
)

vm = replace_once(
    vm,
    """message = "${quality.label}: ${formatFileSize(it.originalSize)} → ${formatFileSize(outputSize)} • ${it.originalWidth}x${it.originalHeight} • ${plannedFps ?: it.originalFps.toInt()}fps • $codecLabel"
""",
    """message = "${quality.label}: ${formatFileSize(it.originalSize)} → ${formatFileSize(outputSize)} • ${it.originalWidth}x${it.originalHeight} • ${plannedFps ?: it.originalFps.toInt()}fps • $codecLabel • ${remuxResult.message}"
""",
    "remux message",
)

vm = replace_once(
    vm,
    "val replacement = replaceOriginalSafely(context, item, outputFile, _uiState.value.useShizukuFallback)",
    "val replacement = replaceOriginalSafely(context, item, outputFile, _uiState.value.useShizukuFallback, quality)",
    "replacement call",
)

vm = replace_once(
    vm,
    "val savedUri = saveFileToGallery(context, file, item.compressedName())",
    "val savedUri = saveFileToGallery(context, file, item.compressedName(), item.metadataSnapshot)",
    "save all copies metadata",
)

vm = replace_once(
    vm,
    """it.copy(statusMessage = "Saved $saved compressed cop${if (saved == 1) "y" else "ies"} to Movies/Compressor.")""",
    """it.copy(statusMessage = "Saved $saved compressed cop${if (saved == 1) "y" else "ies"} to Movies/Compressor with best-effort source date/location metadata.")""",
    "save all copies message",
)

vm = replace_once(
    vm,
    """        var audioBitrate = 0

        try {
""",
    """        var audioBitrate = 0
        var metadataSnapshot = VideoMetadataSnapshot()

        try {
""",
    "metadata variable",
)

vm = replace_once(
    vm,
    """            audioBitrate = getAudioBitrate(context, uri)
            retriever.setDataSource(context, uri)
            width = retriever.extractMetadata""",
    """            audioBitrate = getAudioBitrate(context, uri)
            retriever.setDataSource(context, uri)
            metadataSnapshot = VideoMetadataPreserver.capture(context, uri, retriever)
            width = retriever.extractMetadata""",
    "capture metadata",
)

vm = replace_once(
    vm,
    """            originalAudioBitrate = audioBitrate,
            originalFps = fps,
            durationMs = duration
        )
""",
    """            originalAudioBitrate = audioBitrate,
            originalFps = fps,
            durationMs = duration,
            metadataSnapshot = metadataSnapshot
        )
""",
    "return metadata",
)

vm = vm.replace(
    "val estimatedOutputSize = estimateOutputSize(item, quality)",
    "val estimatedOutputSize = estimateOutputSize(item, quality, codecFromMime(videoMimeType), frameRate)",
    1,
)

vm = replace_once(
    vm,
    """    private fun codecFromLabel(label: String): BatchCodecOption {
        return BatchCodecOption.entries.firstOrNull { it.label == label } ?: BatchCodecOption.AUTO
    }

    private fun outputFpsFor""",
    """    private fun codecFromLabel(label: String): BatchCodecOption {
        return BatchCodecOption.entries.firstOrNull { it.label == label } ?: BatchCodecOption.AUTO
    }

    private fun codecFromMime(mimeType: String): BatchCodecOption {
        return if (mimeType == MimeTypes.VIDEO_H265) BatchCodecOption.HEVC else BatchCodecOption.H264
    }

    private fun outputFpsFor""",
    "codecFromMime",
)

vm = replace_once(
    vm,
    """    private fun estimateOutputSize(item: BatchVideoItem, quality: BatchQualityPreset): Long {
        if (item.originalSize <= 0L) return 0L
        return (item.originalSize * quality.targetRatio).toLong().coerceAtLeast(1L)
    }
""",
    """    private fun estimateOutputSize(
        item: BatchVideoItem,
        quality: BatchQualityPreset,
        codec: BatchCodecOption,
        frameRate: BatchFrameRateOption
    ): Long {
        if (item.originalSize <= 0L) return 0L
        val durationSec = (item.durationMs / 1000.0).coerceAtLeast(1.0)
        val selectedMime = when (codec) {
            BatchCodecOption.HEVC -> MimeTypes.VIDEO_H265
            BatchCodecOption.H264 -> MimeTypes.VIDEO_H264
            BatchCodecOption.AUTO -> if (hasEncoder(MimeTypes.VIDEO_H265)) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
        }
        val videoBitrate = calculateVideoBitrate(item, quality, selectedMime)
        val audioBitrate = calculateAudioBitrate(item, quality)
        val outputFps = outputFpsFor(item, frameRate)?.toFloat() ?: item.originalFps.coerceAtLeast(1f)
        val fpsScale = (outputFps / item.originalFps.coerceAtLeast(1f)).coerceIn(0.35f, 1.0f)
        val estimatedBits = ((videoBitrate * fpsScale) + audioBitrate) * durationSec
        val containerOverhead = estimatedBits * 0.04
        return ((estimatedBits + containerOverhead) / 8.0).toLong().coerceAtLeast(1L)
    }
""",
    "better estimate function",
)

vm = replace_once(
    vm,
    """        useShizukuFallback: Boolean
    ): ReplacementResult = withContext(Dispatchers.IO) {
""",
    """        useShizukuFallback: Boolean,
        quality: BatchQualityPreset
    ): ReplacementResult = withContext(Dispatchers.IO) {
""",
    "replacement signature",
)

vm = replace_once(
    vm,
    """        if (!compressedFile.exists() || compressedFile.length() <= 0) {
            return@withContext ReplacementResult(false, "Replacement skipped: compressed file was missing or empty.")
        }

        val directResult = runCatching {
""",
    """        if (!compressedFile.exists() || compressedFile.length() <= 0) {
            return@withContext ReplacementResult(false, "Replacement skipped: compressed file was missing or empty.")
        }

        if (shouldBlockOriginalOverwrite(item, compressedFile, quality)) {
            val savedUri = saveFileToGallery(context, compressedFile, item.compressedName(), item.metadataSnapshot)
            val ratioPercent = ((compressedFile.length().toDouble() / item.originalSize.toDouble()) * 100.0).toInt()
            return@withContext ReplacementResult(
                false,
                if (savedUri != null) {
                    "Original-mode overwrite blocked to protect quality: output was only $ratioPercent% of the source. A safe copy was saved instead."
                } else {
                    "Original-mode overwrite blocked to protect quality: output was only $ratioPercent% of the source, and saving a safe copy failed."
                }
            )
        }

        val directResult = runCatching {
""",
    "overwrite safety block",
)

vm = replace_once(
    vm,
    """        if (directResult.getOrDefault(false)) {
            return@withContext ReplacementResult(true, "Original replaced through Android writable document access. Name/folder are preserved because the same document was overwritten.")
        }
""",
    """        if (directResult.getOrDefault(false)) {
            val replacedPath = resolveFilesystemPath(context, item.sourceUri)
            val metadataReport = VideoMetadataPreserver.restoreAfterReplacement(
                context = context,
                sourceUri = item.sourceUri,
                snapshot = item.metadataSnapshot,
                replacedFilePath = replacedPath
            )
            return@withContext ReplacementResult(
                true,
                "Original replaced through Android writable document access. Name/folder preserved. ${metadataReport.summary()}"
            )
        }
""",
    "direct metadata restore",
)

vm = vm.replace(
    "val savedUri = saveFileToGallery(context, compressedFile, item.compressedName())",
    "val savedUri = saveFileToGallery(context, compressedFile, item.compressedName(), item.metadataSnapshot)",
)

vm = replace_once(
    vm,
    """            if (copied) {
                return@withContext ReplacementResult(true, "Original replaced with Shizuku path fallback. Original folder/path preserved.")
            }
""",
    """            if (copied) {
                val metadataReport = VideoMetadataPreserver.restoreAfterReplacement(
                    context = context,
                    sourceUri = item.sourceUri,
                    snapshot = item.metadataSnapshot,
                    replacedFilePath = targetPath
                )
                return@withContext ReplacementResult(
                    true,
                    "Original replaced with Shizuku path fallback. Original folder/path preserved. ${metadataReport.summary()}"
                )
            }
""",
    "fallback metadata restore",
)

vm = replace_once(
    vm,
    """    @Suppress("DEPRECATION")
    private fun resolveFilesystemPath""",
    """    private fun shouldBlockOriginalOverwrite(
        item: BatchVideoItem,
        compressedFile: File,
        quality: BatchQualityPreset
    ): Boolean {
        if (quality != BatchQualityPreset.ORIGINAL) return false
        if (item.originalSize < 200L * 1024L * 1024L) return false
        if (item.originalSize <= 0L || compressedFile.length() <= 0L) return false
        val outputRatio = compressedFile.length().toDouble() / item.originalSize.toDouble()
        return outputRatio <= 0.20
    }

    @Suppress("DEPRECATION")
    private fun resolveFilesystemPath""",
    "overwrite helper",
)

vm = replace_once(
    vm,
    """    private fun saveFileToGallery(context: Context, file: File, targetName: String): Uri? {""",
    """    private fun saveFileToGallery(
        context: Context,
        file: File,
        targetName: String,
        metadata: VideoMetadataSnapshot = VideoMetadataSnapshot()
    ): Uri? {""",
    "saveFileToGallery signature",
)

vm = replace_once(
    vm,
    """                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)""",
    """                if (metadata.hasDate) {
                    VideoMetadataPreserver.applyToNewGalleryValues(this, metadata)
                } else {
                    put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                    put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                }""",
    "saveFileToGallery date metadata",
)

write(vm_path, vm)

print("Fixed Phase 1 metadata preservation changes applied.")
print("Next command: ./gradlew :app:assembleDebug")
