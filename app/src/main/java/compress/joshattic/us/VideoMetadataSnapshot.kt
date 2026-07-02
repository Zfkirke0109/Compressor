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

data class VideoMetadataSnapshot(
    val dateTakenMs: Long? = null,
    val dateModifiedSeconds: Long? = null,
    val dateAddedSeconds: Long? = null,
    val rawDateTag: String? = null,
    val dateSource: String? = null,
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

    val dateDiagnosticLabel: String
        get() = dateSource ?: when {
            dateTakenMs != null -> "source date"
            dateModifiedSeconds != null -> "modified date"
            dateAddedSeconds != null -> "added date"
            else -> "date"
        }
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
    private const val MAX_LOCATION_ATOM_BYTES = 64 * 1024
    private const val MAX_MP4_SCAN_DEPTH = 16

    private val mp4ContainerTypes = setOf(
        "moov", "udta", "meta", "ilst", "trak", "mdia", "minf", "dinf", "stbl", "edts"
    )

    fun capture(
        context: Context,
        uri: Uri,
        retriever: MediaMetadataRetriever? = null
    ): VideoMetadataSnapshot {
        var snapshot = VideoMetadataSnapshot()
        var mediaStoreLocation: Pair<Double, Double>? = null
        val originalUri = requireOriginalMediaUri(uri)

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

        val retrieverDate = extractRetrieverDate(context, originalUri)
            ?: runCatching { retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE) }.getOrNull()
        val retrieverDateMs = parseRetrieverDateMs(retrieverDate)
        val existingDateSource = when {
            snapshot.dateTakenMs != null -> "MediaStore date"
            snapshot.dateModifiedSeconds != null -> "MediaStore modified"
            snapshot.dateAddedSeconds != null -> "MediaStore added"
            else -> null
        }
        val finalDateSource = existingDateSource ?: if (retrieverDateMs != null) "MP4/retriever date" else null

        val retrieverLocation = extractRetrieverLocation(context, originalUri)
            ?: runCatching { retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION) }.getOrNull()

        val atomLocation = readMp4LocationAtom(context, originalUri)
        val parsedLocation = parseIso6709Location(retrieverLocation)
            ?: mediaStoreLocation
            ?: atomLocation?.let { parseIso6709Location(it) }

        val rotation = runCatching {
            retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
        }.getOrNull()

        return snapshot.copy(
            dateTakenMs = snapshot.dateTakenMs ?: retrieverDateMs,
            rawDateTag = retrieverDate,
            dateSource = finalDateSource,
            latitude = parsedLocation?.first,
            longitude = parsedLocation?.second,
            rawLocationTag = retrieverLocation ?: atomLocation,
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

    private fun requireOriginalMediaUri(uri: Uri): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
        } else {
            uri
        }
    }

    private fun extractRetrieverDate(context: Context, uri: Uri): String? {
        return runCatching {
            val metadataRetriever = MediaMetadataRetriever()
            try {
                metadataRetriever.setDataSource(context, uri)
                metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            } finally {
                runCatching { metadataRetriever.release() }
            }
        }.getOrNull()
    }

    private fun extractRetrieverLocation(context: Context, uri: Uri): String? {
        return runCatching {
            val metadataRetriever = MediaMetadataRetriever()
            try {
                metadataRetriever.setDataSource(context, uri)
                metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
            } finally {
                runCatching { metadataRetriever.release() }
            }
        }.getOrNull()
    }

    private fun readMp4LocationAtom(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                val statSize = descriptor.statSize.takeIf { it > 0L } ?: return@use null
                FileInputStream(descriptor.fileDescriptor).use { stream ->
                    findLocationInBoxes(stream.channel, 0L, statSize, 0, emptyList())
                }
            }
        }.getOrNull()
    }

    private fun findLocationInBoxes(
        channel: java.nio.channels.FileChannel,
        start: Long,
        end: Long,
        depth: Int,
        path: List<String>
    ): String? {
        if (depth > MAX_MP4_SCAN_DEPTH || end <= start + 8L) return null

        var offset = start
        while (offset + 8L <= end) {
            val header = readChunk(channel, offset, 16)
            if (header.size < 8) return null

            val size32 = readUInt32(header, 0)
            val type = String(header, 4, 4, StandardCharsets.ISO_8859_1)
            var headerSize = 8L
            val boxSize = when (size32) {
                0L -> end - offset
                1L -> {
                    if (header.size < 16) return null
                    headerSize = 16L
                    readUInt64(header, 8)
                }
                else -> size32
            }

            if (boxSize < headerSize || offset + boxSize > end) return null

            val payloadStart = offset + headerSize
            val payloadEnd = offset + boxSize
            val currentPath = path + type

            val locationFromKnownAtom = when {
                isLocationBox(type) -> readLocationText(channel, payloadStart, payloadEnd)
                type == "data" && path.any { isLocationBox(it) } -> {
                    val dataTextStart = if (payloadStart + 8L < payloadEnd) payloadStart + 8L else payloadStart
                    readLocationText(channel, dataTextStart, payloadEnd)
                }
                else -> null
            }
            if (locationFromKnownAtom != null) return locationFromKnownAtom

            if (type in mp4ContainerTypes) {
                val childStart = if (type == "meta" && payloadStart + 4L < payloadEnd) payloadStart + 4L else payloadStart
                val nested = findLocationInBoxes(channel, childStart, payloadEnd, depth + 1, currentPath)
                if (nested != null) return nested
            }

            offset += boxSize
        }

        return null
    }

    private fun isLocationBox(type: String): Boolean {
        return type == "\u00A9xyz" || type.trim() == "xyz"
    }

    private fun readLocationText(
        channel: java.nio.channels.FileChannel,
        start: Long,
        end: Long
    ): String? {
        if (end <= start) return null
        val length = (end - start).coerceAtMost(MAX_LOCATION_ATOM_BYTES.toLong()).toInt()
        val text = String(readChunk(channel, start, length), StandardCharsets.ISO_8859_1)
        return findPreciseIso6709LocationInText(text)
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

    private fun readUInt32(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toLong() and 0xffL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
            (bytes[offset + 3].toLong() and 0xffL)
    }

    private fun readUInt64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xffL)
        }
        return value
    }

    private fun findPreciseIso6709LocationInText(text: String): String? {
        val pattern = Regex("([+-]\\d{1,2}\\.\\d{3,})([+-]\\d{1,3}\\.\\d{3,})(?:[+-]\\d+(?:\\.\\d+)?)?/?")
        return pattern.findAll(text)
            .map { it.value }
            .firstOrNull { parseIso6709Location(it) != null }
    }

    private fun parseRetrieverDateMs(value: String?): Long? {
        if (value.isNullOrBlank()) return null

        val raw = value.trim()
        val normalized = raw.replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")

        val utc = TimeZone.getTimeZone("UTC")
        val candidates = listOf(
            "yyyyMMdd'T'HHmmss.SSS'Z'" to utc,
            "yyyyMMdd'T'HHmmss'Z'" to utc,
            "yyyyMMdd'T'HHmmss.SSSZ" to null,
            "yyyyMMdd'T'HHmmssZ" to null,
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" to utc,
            "yyyy-MM-dd'T'HH:mm:ss'Z'" to utc,
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ" to null,
            "yyyy-MM-dd'T'HH:mm:ssZ" to null,
            "yyyy:MM:dd HH:mm:ss" to TimeZone.getDefault(),
            "yyyy-MM-dd HH:mm:ss" to TimeZone.getDefault()
        )

        for ((pattern, zone) in candidates) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    if (zone != null) timeZone = zone
                }.parse(normalized)?.time
            }.getOrNull()

            if (parsed != null && parsed > 0L) return parsed
        }

        return null
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
        if (latitude == 0.0 && longitude == 0.0) return null
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
        if (latitude == 0.0 && longitude == 0.0) return null
        return latitude to longitude
    }
}
