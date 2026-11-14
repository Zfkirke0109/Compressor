package compress.joshattic.us

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * A gallery copy counts only once exactly one row was published and the item reads back as the
 * bytes written. Each test drives a provider that fails one step and checks that the save is not
 * reported as done, and that the row it inserted is removed again.
 */
class GalleryPublicationTest {

    // Larger than the copy buffer, and not a multiple of it, so a copy that stops at a buffer
    // boundary or drops the tail cannot pass.
    private val bytes = ByteArray(3 * (1 shl 20) + 17) { (it * 31 + 7).toByte() }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val uri = "content://media/external/video/media/4242"

    /** A provider that does what each test tells it to, and records what was asked of it. */
    private class FakeGallery(override val hasPendingRows: Boolean = true) : GalleryPublication.Gallery {
        val calls = mutableListOf<String>()
        val stored = ByteArrayOutputStream()
        var insertResult: () -> String? = { "content://media/external/video/media/4242" }
        var writer: (OutputStream) -> OutputStream? = { it }
        var publishResult: () -> Int = { 1 }
        var reader: (ByteArray) -> InputStream? = { ByteArrayInputStream(it) }
        var deleteFails = false
        var closedWriter = false

        override fun insert(): String? { calls += "insert"; return insertResult() }

        override fun openForWriting(uri: String): OutputStream? {
            calls += "write"
            val sink = object : OutputStream() {
                override fun write(b: Int) = stored.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = stored.write(b, off, len)
                override fun close() { closedWriter = true }
            }
            return writer(sink)
        }

        override fun publish(uri: String): Int { calls += "publish"; return publishResult() }

        override fun openForReading(uri: String): InputStream? { calls += "read"; return reader(stored.toByteArray()) }

        override fun delete(uri: String) {
            calls += "delete"
            if (deleteFails) throw SecurityException("cannot delete /storage/emulated/0/Movies/Compressor/VID_private.mp4")
        }
    }

    private fun save(gallery: FakeGallery, expected: String? = null, source: () -> InputStream = { ByteArrayInputStream(bytes) }) =
        GalleryPublication.save(source, gallery, expected)

    private fun assertFailed(result: GalleryPublication.Result, stage: GalleryPublication.Stage): GalleryPublication.Result.Failed {
        assertTrue("expected a failed save at $stage, got $result", result is GalleryPublication.Result.Failed)
        val failed = result as GalleryPublication.Result.Failed
        assertEquals(stage, failed.stage)
        return failed
    }

    @Test
    fun aSaveIsPublishedOnlyAfterTheWholeCopyReadsBack() {
        val gallery = FakeGallery()
        val result = save(gallery, expected = sha(bytes))
        assertEquals(GalleryPublication.Result.Published(uri, GalleryPublication.Content(bytes.size.toLong(), sha(bytes))), result)
        assertEquals(listOf("insert", "write", "publish", "read"), gallery.calls)
        assertTrue(gallery.closedWriter)
        assertTrue(gallery.stored.toByteArray().contentEquals(bytes))
    }

    @Test
    fun theExpectedDigestIsComparedWithoutRegardToCase() {
        assertTrue(save(FakeGallery(), expected = sha(bytes).uppercase()) is GalleryPublication.Result.Published)
    }

    @Test
    fun aPublishThatChangesNoRowIsNotASave() {
        // The item stays pending: other apps, the gallery among them, do not see it.
        for (rows in listOf(0, 2, -1)) {
            val gallery = FakeGallery().apply { publishResult = { rows } }
            val failed = assertFailed(save(gallery), GalleryPublication.Stage.PUBLISH)
            assertTrue(failed.detail, failed.detail.contains("$rows rows"))
            assertFalse(failed.cleanupFailed)
            assertEquals(listOf("insert", "write", "publish", "delete"), gallery.calls)
        }
    }

    @Test
    fun aPublishThatThrowsIsNotASave() {
        val gallery = FakeGallery().apply { publishResult = { throw IllegalStateException("provider died") } }
        assertFailed(save(gallery), GalleryPublication.Stage.PUBLISH)
        assertEquals(listOf("insert", "write", "publish", "delete"), gallery.calls)
    }

    @Test
    fun noRowMeansNothingWasWrittenAndNothingToRemove() {
        val none = FakeGallery().apply { insertResult = { null } }
        assertFailed(save(none), GalleryPublication.Stage.INSERT)
        assertEquals(listOf("insert"), none.calls)

        val throws = FakeGallery().apply { insertResult = { throw IllegalArgumentException("no volume") } }
        assertFailed(save(throws), GalleryPublication.Stage.INSERT)
        assertEquals(listOf("insert"), throws.calls)
    }

    @Test
    fun aRowThatCannotBeOpenedForWritingIsRemoved() {
        val gallery = FakeGallery().apply { writer = { null } }
        assertFailed(save(gallery), GalleryPublication.Stage.WRITE)
        assertEquals(listOf("insert", "write", "delete"), gallery.calls)
    }

    @Test
    fun aWriteThatStopsPartWayIsRemovedAndNeverPublished() {
        val gallery = FakeGallery().apply {
            writer = { sink ->
                object : OutputStream() {
                    var written = 0L
                    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        if (written + len > 1_500_000) throw IOException("ENOSPC")
                        sink.write(b, off, len); written += len
                    }
                    override fun close() = sink.close()
                }
            }
        }
        assertFailed(save(gallery), GalleryPublication.Stage.WRITE)
        assertEquals(listOf("insert", "write", "delete"), gallery.calls)
        assertTrue("the partial bytes were written before the failure", gallery.stored.size() in 1 until bytes.size)
    }

    @Test
    fun aSourceThatCannotBeReadToItsEndIsNotSaved() {
        val opensNothing = FakeGallery()
        assertFailed(save(opensNothing, source = { throw IOException("gone") }), GalleryPublication.Stage.WRITE)
        assertEquals(listOf("insert", "write", "delete"), opensNothing.calls)

        val failsPartWay = FakeGallery()
        val result = save(failsPartWay, source = {
            object : FilterInputStream(ByteArrayInputStream(bytes)) {
                var read = 0L
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (read > 2_000_000) throw IOException("EIO")
                    return super.read(b, off, len).also { if (it > 0) read += it }
                }
            }
        })
        assertFailed(result, GalleryPublication.Stage.WRITE)
        assertEquals(listOf("insert", "write", "delete"), failsPartWay.calls)
    }

    @Test
    fun aCloseThatFailsIsAFailedWrite() {
        // Many providers flush on close: a failed close can mean the bytes never landed.
        val gallery = FakeGallery().apply {
            writer = { sink ->
                object : OutputStream() {
                    override fun write(b: Int) = sink.write(b)
                    override fun write(b: ByteArray, off: Int, len: Int) = sink.write(b, off, len)
                    override fun close() = throw IOException("EIO on close")
                }
            }
        }
        assertFailed(save(gallery), GalleryPublication.Stage.WRITE)
        assertEquals(listOf("insert", "write", "delete"), gallery.calls)
    }

    @Test
    fun aPublishedItemThatCannotBeReadBackIsRemoved() {
        val unopenable = FakeGallery().apply { reader = { null } }
        assertFailed(save(unopenable), GalleryPublication.Stage.READ_BACK)
        assertEquals(listOf("insert", "write", "publish", "read", "delete"), unopenable.calls)

        val failsAfterClose = FakeGallery().apply {
            reader = { stored ->
                object : FilterInputStream(ByteArrayInputStream(stored)) {
                    var read = 0L
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (read > 1_000_000) throw IOException("EIO")
                        return super.read(b, off, len).also { if (it > 0) read += it }
                    }
                }
            }
        }
        assertFailed(save(failsAfterClose), GalleryPublication.Stage.READ_BACK)
        assertEquals(listOf("insert", "write", "publish", "read", "delete"), failsAfterClose.calls)
    }

    @Test
    fun aShortReadBackIsAMismatch() {
        val gallery = FakeGallery().apply { reader = { stored -> ByteArrayInputStream(stored, 0, stored.size - 1) } }
        val failed = assertFailed(save(gallery), GalleryPublication.Stage.MISMATCH)
        assertTrue(failed.detail, failed.detail.contains("${bytes.size - 1} bytes, not the ${bytes.size} written"))
        assertEquals(listOf("insert", "write", "publish", "read", "delete"), gallery.calls)
    }

    @Test
    fun theSameLengthWithDifferentBytesIsAMismatch() {
        // A length check alone would pass this one.
        val gallery = FakeGallery().apply {
            reader = { stored -> ByteArrayInputStream(stored.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }) }
        }
        val failed = assertFailed(save(gallery), GalleryPublication.Stage.MISMATCH)
        assertTrue(failed.detail, failed.detail.contains("with a different digest"))
        assertEquals(listOf("insert", "write", "publish", "read", "delete"), gallery.calls)
    }

    @Test
    fun bytesOtherThanTheOnesExpectedAreNeverPublished() {
        // A rollback copy is held to the digest it was staged with, a backup to the source's bound
        // digest: what was read is not what the caller means to save.
        val gallery = FakeGallery()
        val failed = assertFailed(save(gallery, expected = sha(bytes.copyOf(bytes.size - 1))), GalleryPublication.Stage.SOURCE_CHANGED)
        assertEquals(listOf("insert", "write", "delete"), gallery.calls)
        assertFalse(failed.detail, failed.detail.contains(sha(bytes)))
    }

    @Test
    fun aCleanupThatFailsKeepsTheFirstErrorAndSaysSo() {
        val gallery = FakeGallery().apply { publishResult = { 0 }; deleteFails = true }
        val failed = assertFailed(save(gallery), GalleryPublication.Stage.PUBLISH)
        assertTrue(failed.cleanupFailed)
        assertEquals(listOf("insert", "write", "publish", "delete"), gallery.calls)
    }

    @Test
    fun withoutPendingRowsAWrittenRowIsCheckedAsItIs() {
        // Before Android 10 there is no IS_PENDING to clear: the read-back is the proof.
        val gallery = FakeGallery(hasPendingRows = false)
        assertTrue(save(gallery) is GalleryPublication.Result.Published)
        assertEquals(listOf("insert", "write", "read"), gallery.calls)

        val short = FakeGallery(hasPendingRows = false).apply { reader = { stored -> ByteArrayInputStream(stored, 0, 10) } }
        assertFailed(save(short), GalleryPublication.Stage.MISMATCH)
        assertEquals(listOf("insert", "write", "read", "delete"), short.calls)
    }

    @Test
    fun aFailureIsDescribedByItsKindNeverByAProviderMessage() {
        // Provider messages can carry the file's path or name; diagnostics must not.
        val gallery = FakeGallery().apply { writer = { throw SecurityException("/storage/emulated/0/DCIM/Camera/VID_private.mp4") } }
        val failed = assertFailed(save(gallery), GalleryPublication.Stage.WRITE)
        assertEquals("SecurityException", failed.detail)
    }

    @Test
    fun everyGalleryPublishInTheAppIsRowCountedAndReadBack() {
        // The only places that clear IS_PENDING: MediaStoreGallery, whose publish GalleryPublication
        // holds to one row and a read-back, and the diagnostics export, which
        // DiagnosticsPublication holds to one row. A new save that clears it elsewhere bypasses both.
        val root = listOf("src/main/java", "app/src/main/java").map { java.io.File(it) }.first { it.isDirectory }
        val clears = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""IS_PENDING,\s*0\b""").containsMatchIn(it.readText()) }.map { it.name }.toSet()
        assertEquals(setOf("GalleryPublication.kt", "DiagnosticsExporter.kt"), clears)
        val exporter = root.walkTopDown().single { it.name == "DiagnosticsExporter.kt" }.readText()
        assertEquals(2, Regex("""DiagnosticsPublication\.writeAndPublish\(""").findAll(exporter).count())
    }

    @Test
    fun theUserIsToldOnlyAboutCopiesProvenInTheGallery() {
        assertEquals("Saved 1 output copy to Movies/Compressor with all metadata.", GallerySaveSummary.message(1, 0, "all metadata"))
        assertEquals("Saved 3 output copies to Movies/Compressor with all metadata.", GallerySaveSummary.message(3, 0, "all metadata"))
        val partly = GallerySaveSummary.message(2, 1, "all metadata")
        assertTrue(partly, partly.startsWith("Saved 2 output copies"))
        assertTrue(partly, partly.contains("1 output copy could not be confirmed in the gallery and was not counted"))
        val none = GallerySaveSummary.message(0, 2, "all metadata")
        assertTrue(none, none.startsWith("Saved 0 output copies"))
        assertTrue(none, none.contains("2 output copies could not be confirmed in the gallery and were not counted"))
    }
}
