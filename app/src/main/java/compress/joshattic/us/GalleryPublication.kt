package compress.joshattic.us

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * A gallery copy counts only once it is published and reads back whole.
 *
 * `insert` returning a URI proves that a row exists, not that the file is in it. The write can stop
 * part way. The provider answers the publishing `update` with the number of rows it changed, and
 * zero leaves the item pending, which other apps do not see. A file that was written can still read
 * back short or different. Every gallery save in this app is a step something else rests on: a
 * recovered original lets the app delete its rollback copy, a backup lets a replacement truncate
 * the source, and a saved copy is what the user is told they now have. So a save is
 * [Result.Published] only when the whole source was written, exactly one row was published, and the
 * published item read back with the length and SHA-256 of the bytes written, which must also be the
 * bytes the caller named when it named any. Anything less is [Result.Failed]: the row this save
 * inserted is deleted again, and the first error is kept even when that cleanup fails too.
 *
 * A read-back is proof at that moment. Nothing here can stop another app, or the user, deleting
 * the item later.
 */
internal object GalleryPublication {
    /** A length and a SHA-256 (lowercase hex). */
    data class Content(val bytes: Long, val sha256: String)

    enum class Stage {
        /** The provider gave no row. Nothing was written. */
        INSERT,
        /** The row could not be opened, or the copy into it, or its close, failed. */
        WRITE,
        /** The source read now is not the bytes the caller expected: what would be saved is not what it means to save. */
        SOURCE_CHANGED,
        /** Clearing IS_PENDING threw, or changed a number of rows other than one. */
        PUBLISH,
        /** The published item could not be opened or read to its end. */
        READ_BACK,
        /** It was read to its end, and is not the bytes written: short, long, or the same length and different. */
        MISMATCH
    }

    sealed interface Result {
        data class Published(val uri: String, val content: Content) : Result
        data class Failed(val stage: Stage, val detail: String, val cleanupFailed: Boolean) : Result
    }

    /** The provider, as a save uses it. [MediaStoreGallery] is the device's. */
    interface Gallery {
        /** False before Android 10, which has no pending rows: there, a written row is published. */
        val hasPendingRows: Boolean

        /** A new row for the copy, pending where the platform has pending rows; null when none is given. */
        fun insert(): String?

        /** Opens [uri] for writing; null when the provider will not. The save closes it. */
        fun openForWriting(uri: String): OutputStream?

        /** Clears IS_PENDING on [uri] and answers with the number of rows the provider changed. */
        fun publish(uri: String): Int

        /** Opens [uri] for reading; null when it cannot be. The save closes it. */
        fun openForReading(uri: String): InputStream?

        fun delete(uri: String)
    }

    /**
     * Saves the bytes [openSource] yields as a new gallery item.
     *
     * @param expectedSha256 when given, the bytes read from the source must have this digest; a
     *   recovery copy is held to the digest it was staged with, a backup to the source's bound digest.
     */
    fun save(openSource: () -> InputStream, gallery: Gallery, expectedSha256: String? = null): Result {
        val uri = try {
            gallery.insert()
        } catch (e: Exception) {
            return Result.Failed(Stage.INSERT, describe(e), cleanupFailed = false)
        } ?: return Result.Failed(Stage.INSERT, "the provider gave no row", cleanupFailed = false)

        fun failed(stage: Stage, detail: String) =
            Result.Failed(stage, detail, cleanupFailed = runCatching { gallery.delete(uri) }.isFailure)

        val written = try {
            val output = gallery.openForWriting(uri) ?: return failed(Stage.WRITE, "the new row could not be opened for writing")
            output.use { out -> openSource().use { input -> copyDigesting(input, out) } }
        } catch (e: Exception) {
            return failed(Stage.WRITE, describe(e))
        }
        if (expectedSha256 != null && !written.sha256.equals(expectedSha256, ignoreCase = true)) {
            return failed(Stage.SOURCE_CHANGED, "the source read as ${written.bytes} bytes with a digest other than the one expected")
        }
        if (gallery.hasPendingRows) {
            val rows = try {
                gallery.publish(uri)
            } catch (e: Exception) {
                return failed(Stage.PUBLISH, describe(e))
            }
            if (rows != 1) return failed(Stage.PUBLISH, "the provider published $rows rows, not 1")
        }
        val readBack = try {
            gallery.openForReading(uri)?.use { digest(it) }
        } catch (e: Exception) {
            return failed(Stage.READ_BACK, describe(e))
        } ?: return failed(Stage.READ_BACK, "the published item could not be opened for reading")
        if (readBack != written) {
            return failed(
                Stage.MISMATCH,
                if (readBack.bytes != written.bytes) "it read back as ${readBack.bytes} bytes, not the ${written.bytes} written"
                else "it read back as the ${written.bytes} bytes written, with a different digest"
            )
        }
        return Result.Published(uri, readBack)
    }

    /** Copies [input] to [output] whole, and returns the length and digest of what was copied. */
    fun copyDigesting(input: InputStream, output: OutputStream): Content {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 20)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            md.update(buffer, 0, n)
            total += n
        }
        return Content(total, hex(md.digest()))
    }

    fun digest(input: InputStream): Content {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 20)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            md.update(buffer, 0, n)
            total += n
        }
        return Content(total, hex(md.digest()))
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    // The class only: a provider's message can carry the file's path or name.
    private fun describe(e: Exception) = e.javaClass.simpleName
}

/** The device's gallery: a new row in Movies/Compressor, pending until it is published. */
internal class MediaStoreGallery(
    private val resolver: ContentResolver,
    /** The new row's columns; IS_PENDING = 1 among them on Android 10 and later. */
    private val values: ContentValues
) : GalleryPublication.Gallery {
    override val hasPendingRows: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private val collection: Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    override fun insert(): String? = resolver.insert(collection, values)?.toString()

    override fun openForWriting(uri: String): OutputStream? = resolver.openOutputStream(Uri.parse(uri))

    override fun publish(uri: String): Int =
        resolver.update(Uri.parse(uri), ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)

    override fun openForReading(uri: String): InputStream? = resolver.openInputStream(Uri.parse(uri))

    override fun delete(uri: String) {
        resolver.delete(Uri.parse(uri), null, null)
    }
}

/** What the user is told after saving output copies: only a copy proven in the gallery counts as saved. */
internal object GallerySaveSummary {
    fun message(saved: Int, unconfirmed: Int, privacySummary: String): String {
        fun copies(n: Int) = if (n == 1) "1 output copy" else "$n output copies"
        val head = "Saved ${copies(saved)} to Movies/Compressor with $privacySummary."
        if (unconfirmed <= 0) return head
        val verb = if (unconfirmed == 1) "was" else "were"
        return "$head ${copies(unconfirmed).replaceFirstChar { it.uppercase() }} could not be confirmed in the gallery and $verb not counted; try saving again."
    }
}
