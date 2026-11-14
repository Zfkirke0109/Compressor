package compress.joshattic.us

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * [GalleryPublication] against this device's own MediaStore: a save is counted only when the
 * provider published exactly one row and the item read back whole, and a save that is not counted
 * leaves no row behind, pending or visible.
 *
 * Every byte written here is made by the test (a fixed pseudo-random pattern, not a video and not
 * user media), under a test-owned name in Movies/PLLabTest. Each row the test inserts is deleted
 * when it ends, whatever the outcome. Nothing reads, changes or deletes any other item.
 *
 *   scripts/device/run-device-checks.sh CLIP.mp4   (runs with the default classes; needs no clip)
 */
@RunWith(AndroidJUnit4::class)
@RequiresApi(Build.VERSION_CODES.R)
class GalleryPublicationDeviceTest {

    private val resolver: ContentResolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private val inserted = mutableListOf<String>()
    private val bytes = ByteArray(5 * (1 shl 20) + 333) { (it * 131 + 17).toByte() }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun values(): ContentValues = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "PLLAB_TEST_publication_${UUID.randomUUID()}.mp4")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.IS_PENDING, 1)
        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/PLLabTest")
    }

    /** The device's gallery, recording each row inserted so that [cleanUp] can remove it. */
    private open inner class Recording : GalleryPublication.Gallery {
        private val real = MediaStoreGallery(resolver, values())
        override val hasPendingRows: Boolean get() = real.hasPendingRows
        override fun insert(): String? = real.insert()?.also { inserted += it }
        override fun openForWriting(uri: String): OutputStream? = real.openForWriting(uri)
        override fun publish(uri: String): Int = real.publish(uri)
        override fun openForReading(uri: String): InputStream? = real.openForReading(uri)
        override fun delete(uri: String) = real.delete(uri)
    }

    @After
    fun cleanUp() {
        inserted.forEach { uri -> runCatching { resolver.delete(Uri.parse(uri), null, null) } }
        inserted.forEach { uri -> assertFalse("a test row was left behind", rowExists(uri)) }
    }

    /** Whether the row exists at all, pending or published. */
    private fun rowExists(uri: String): Boolean {
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE) }
        return resolver.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { it.count > 0 } ?: false
    }

    private fun isPending(uri: String): Boolean? {
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE) }
        return resolver.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns.IS_PENDING), args, null)?.use {
            if (it.moveToFirst()) it.getInt(0) != 0 else null
        }
    }

    private fun assumePendingRows() = assumeTrue("needs Android 11 or later (pending-row queries)", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)

    @Test
    fun aRealSaveIsPublishedAsOneRowAndReadsBackWhole() {
        assumePendingRows()
        val result = GalleryPublication.save({ ByteArrayInputStream(bytes) }, Recording(), expectedSha256 = sha(bytes))
        assertTrue("$result", result is GalleryPublication.Result.Published)
        val published = result as GalleryPublication.Result.Published
        assertEquals(GalleryPublication.Content(bytes.size.toLong(), sha(bytes)), published.content)
        assertEquals("the row is no longer pending", false, isPending(published.uri))
        // An independent read, outside the code under test.
        val again = resolver.openInputStream(Uri.parse(published.uri))!!.use { GalleryPublication.digest(it) }
        assertEquals(published.content, again)
        println("gallery-publication: published bytes=${published.content.bytes} rowPending=false readBack=match")
    }

    @Test
    fun aPublishThatChangesNoRowLeavesNoRowBehind() {
        assumePendingRows()
        val gallery = object : Recording() {
            override fun publish(uri: String): Int = 0
        }
        val result = GalleryPublication.save({ ByteArrayInputStream(bytes) }, gallery)
        assertTrue("$result", result is GalleryPublication.Result.Failed)
        assertEquals(GalleryPublication.Stage.PUBLISH, (result as GalleryPublication.Result.Failed).stage)
        assertFalse(result.cleanupFailed)
        assertEquals(1, inserted.size)
        assertFalse("the pending row was removed", rowExists(inserted.single()))
        println("gallery-publication: publish0 -> ${result.stage}; rowRemoved=true")
    }

    @Test
    fun aReadBackThatDiffersLeavesNoRowBehind() {
        assumePendingRows()
        val gallery = object : Recording() {
            override fun openForReading(uri: String): InputStream? {
                val real = super.openForReading(uri) ?: return null
                // The same length with one byte changed: a length check alone would pass it.
                val read = real.use { it.readBytes() }
                read[read.size / 3] = (read[read.size / 3] + 1).toByte()
                return ByteArrayInputStream(read)
            }
        }
        val result = GalleryPublication.save({ ByteArrayInputStream(bytes) }, gallery)
        assertEquals(GalleryPublication.Stage.MISMATCH, (result as GalleryPublication.Result.Failed).stage)
        assertFalse("the published row was removed", rowExists(inserted.single()))
        println("gallery-publication: sameLengthMismatch -> ${result.stage}; rowRemoved=true")
    }

    @Test
    fun aWriteThatStopsPartWayLeavesNoRowBehind() {
        assumePendingRows()
        val gallery = object : Recording() {
            override fun openForWriting(uri: String): OutputStream? {
                val real = super.openForWriting(uri) ?: return null
                return object : OutputStream() {
                    var written = 0L
                    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        if (written + len > 2_000_000) throw java.io.IOException("simulated: no space")
                        real.write(b, off, len); written += len
                    }
                    override fun close() = real.close()
                }
            }
        }
        val result = GalleryPublication.save({ ByteArrayInputStream(bytes) }, gallery)
        assertEquals(GalleryPublication.Stage.WRITE, (result as GalleryPublication.Result.Failed).stage)
        assertFalse("the partly written row was removed", rowExists(inserted.single()))
        println("gallery-publication: partialWrite -> ${result.stage}; rowRemoved=true")
    }

    @Test
    fun bytesOtherThanTheExpectedOnesAreNeverPublished() {
        assumePendingRows()
        val result = GalleryPublication.save({ ByteArrayInputStream(bytes) }, Recording(), expectedSha256 = sha(bytes.copyOf(10)))
        assertEquals(GalleryPublication.Stage.SOURCE_CHANGED, (result as GalleryPublication.Result.Failed).stage)
        assertFalse("the unpublished row was removed", rowExists(inserted.single()))
        println("gallery-publication: sourceChanged -> ${result.stage}; rowRemoved=true")
    }
}
