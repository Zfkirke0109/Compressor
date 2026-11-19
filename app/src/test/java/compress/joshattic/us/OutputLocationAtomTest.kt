package compress.joshattic.us

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

class OutputLocationAtomTest {
    private fun box(type: String, payload: ByteArray = byteArrayOf()): ByteArray =
        ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size)
            .put(type.toByteArray(Charsets.ISO_8859_1)).put(payload).array()

    private fun scan(bytes: ByteArray): Boolean {
        val file = File.createTempFile("location-atom", ".mp4")
        return try {
            file.writeBytes(bytes)
            file.inputStream().use { VideoMetadataPreserver.outputHasLocationAtom(it.channel) }
        } finally {
            file.delete()
        }
    }

    @Test
    fun recognizedLocationAtomIncludesLowPrecisionAndUnparsedCoordinates() {
        for (text in listOf("+1.0-2.0/", "+0+0/", "unparsed", "")) {
            val location = box("©xyz", text.toByteArray())
            assertTrue(scan(box("moov", box("udta", location))))
            assertTrue(scan(box("moov", box("meta", ByteArray(4) + box("ilst", location)))))
            assertTrue(scan(box("moov", box("meta", box("ilst", location)))))
        }
    }

    @Test
    fun completeOutputSkipsSamplePayloadsAndUnrelatedMetadata() {
        val movie = box("moov", box("trak", box("mdia")) + box("udta", box("name", "title".toByteArray())))
        assertFalse(scan(box("ftyp") + box("mdat", "©xyz".toByteArray()) + movie))
    }

    @Test
    fun threeGppLocationAndQuickTimeLocationKeyAreDetectedWithoutParsingCoordinates() {
        fun keys(name: String): ByteArray {
            val entry = box("mdta", name.toByteArray())
            val table = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1) + entry
            return box("moov", box("udta", box("meta", ByteArray(4) + box("keys", table))))
        }
        assertTrue(scan(box("moov", box("udta", box("loci", byteArrayOf(0, 0, 0, 0))))))
        assertTrue(scan(keys("com.apple.quicktime.location.ISO6709")))
        assertFalse(scan(keys("com.apple.quicktime.title")))
    }

    @Test
    fun incompleteUnsupportedAndDeepLayoutsCannotConfirmRemoval() {
        val deep = (0 until 20).fold(box("name")) { contents, _ -> box("udta", contents) }
        val invalid = listOf(
            box("ftyp"),
            box("moov") + byteArrayOf(0, 0, 0),
            box("moov", box("udta", byteArrayOf(1, 2, 3, 4))),
            box("moov", box("udta", box("name").copyOf(7))),
            box("moov", box("meta", byteArrayOf(0, 0))),
            box("moov", box("meta", ByteArray(4) + box("keys", byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1)))),
            box("moov", ByteBuffer.allocate(8).putInt(1).put("udta".toByteArray()).array()),
            box("moov", deep)
        )
        for (bytes in invalid) assertThrows(IllegalArgumentException::class.java) { scan(bytes) }
    }
}
