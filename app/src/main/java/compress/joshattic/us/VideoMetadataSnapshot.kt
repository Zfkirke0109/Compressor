package compress.joshattic.us

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

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
    private const val MP4_LOCATION_SCAN_CHUNK_BYTES = 16 * 1024 * 1024

    fun capture(
        context: Context,
        uri: Uri,
        retriever: MediaMetadataRetriever? = null
    ): VideoMetadataSnapshot {
        var snapshot = VideoMetadataSnapshot()
        var mediaStoreLocation: Pair<Double, Double>? = null

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
                    mediaStoreLocation = cursor.locationOrNull()
                }
            }
        }

        val retrieverLocation = runCatching {
            retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
        }.getOrNull()

        val scannedLocation = scanMp4TextLocation(context, uri)
        val parsedLocation = parseIso6709Location(retrieverLocation)
            ?: scannedLocation?.let { parseIso6709Location(it) }
            ?: mediaStoreLocation

        val rotation = runCatching {
            retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
        }.getOrNull()

        return snapshot.copy(
            latitude = parsedLocation?.first,
            longitude = parsedLocation?.second,
            rawLocationTag = retrieverLocation ?: scannedLocation,
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

    private fun scanMp4TextLocation(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                val statSize = descriptor.statSize
                FileInputStream(descriptor.fileDescriptor).use { stream ->
                    val channel = stream.channel
                    val chunks = mutableListOf<ByteArray>()
                    val firstSize = if (statSize > 0L) min(MP4_LOCATION_SCAN_CHUNK_BYTES.toLong(), statSize).toInt() else MP4_LOCATION_SCAN_CHUNK_BYTES
                    chunks += readChunk(channel, 0L, firstSize)
                    if (statSize > MP4_LOCATION_SCAN_CHUNK_BYTES) {
                        val tailStart = max(0L, statSize - MP4_LOCATION_SCAN_CHUNK_BYTES)
                        chunks += readChunk(channel, tailStart, MP4_LOCATION_SCAN_CHUNK_BYTES)
                    }
                    chunks.asSequence()
                        .mapNotNull { bytes -> findIso6709LocationInText(bytes.toString(StandardCharsets.ISO_8859_1)) }
                        .firstOrNull()
                }
            }
        }.getOrNull()
    }

    private fun readChunk(channel: java.nio.channels.FileChannel, position: Long, maxBytes: Int): ByteArray {
        if (maxBytes <= 0) return ByteArray(0)
        channel.position(position)
        val buffer = ByteBuffer.allocate(maxBytes)
        val bytesRead = channel.read(buffer)
        if (bytesRead <= 0) return ByteArray(0)
        val output = ByteArray(bytesRead)
        buffer.flip()
        buffer.get(output)
        return output
    }

    private fun findIso6709LocationInText(text: String): String? {
        val pattern = Regex("([+-](?:[0-8]?\\d(?:\\.\\d+)?|90(?:\\.0+)?))([+-](?:(?:0?\\d?\\d|1[0-7]\\d)(?:\\.\\d+)?|180(?:\\.0+)?))(?:[+-]\\d+(?:\\.\\d+)?)?/?")
        return pattern.findAll(text)
            .map { it.value }
            .firstOrNull { parseIso6709Location(it) != null }
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

    private fun Cursor.doubleOrNull(column: String): Double? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getDouble(index) else null
    }

    private fun Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun Cursor.locationOrNull(): Pair<Double, Double>? {
        val latitude = doubleOrNull("latitude") ?: doubleOrNull("GPSLatitude") ?: doubleOrNull("gps_latitude")
        val longitude = doubleOrNull("longitude") ?: doubleOrNull("GPSLongitude") ?: doubleOrNull("gps_longitude")
        if (latitude == null || longitude == null) return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return latitude to longitude
    }
}
