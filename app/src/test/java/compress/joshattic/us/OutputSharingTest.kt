package compress.joshattic.us

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OutputSharingTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun box(type: String, payload: ByteArray = byteArrayOf()): ByteArray =
        ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size)
            .put(type.toByteArray(Charsets.ISO_8859_1)).put(payload).array()

    private fun output(bytes: ByteArray): OutputSharing.Output {
        val file = OutputSharing.newCacheFile(temporary.root, "_Compressed.mp4")
        file.writeBytes(bytes)
        return OutputSharing.Output(file, bytes.size.toLong(), "camera_Compressed.mp4")
    }

    private fun prepare(outputs: List<OutputSharing.Output>, removeLocation: Boolean) =
        OutputSharing.prepare(outputs, removeLocation) { VideoMetadataPreserver.outputHasLocationAtom(it.channel) }

    @Test
    fun switchingFromPreserveToRemoveBlocksTheWholeShareWhenOneOutputHasGps() {
        val clean = output(box("moov"))
        val withGps = output(box("moov", box("udta", box("©xyz", "+1-2/".toByteArray()))))
        val outputs = listOf(clean, withGps)
        assertEquals(outputs, (prepare(outputs, false) as OutputSharing.Result.Ready).outputs)
        assertTrue(prepare(outputs, true) is OutputSharing.Result.Blocked)
        assertEquals(listOf(clean), (prepare(listOf(clean), true) as OutputSharing.Result.Ready).outputs)
        assertEquals(2, temporary.root.listFiles()!!.size)
    }

    @Test
    fun failedInspectionAndChangedFilesCannotExposeAnyOutputs() {
        val clean = output(box("moov"))
        val malformed = output(box("moov") + byteArrayOf(0))
        assertTrue(prepare(listOf(clean, malformed), true) is OutputSharing.Result.Blocked)
        clean.file.writeBytes(box("moov", box("udta")))
        assertTrue(prepare(listOf(clean), false) is OutputSharing.Result.Blocked)
        malformed.file.delete()
        assertTrue(prepare(listOf(malformed), true) is OutputSharing.Result.Blocked)
        assertTrue(prepare(listOf(output(byteArrayOf())), true) is OutputSharing.Result.Blocked)
    }

    @Test
    fun laterRunsAndFallbacksCannotReplaceCheckedBytesAtAGrantedPath() {
        val original = output(box("moov"))
        val disclosed = (prepare(listOf(original), true) as OutputSharing.Result.Ready).outputs.single().file
        assertEquals(original.file, disclosed)
        for (suffix in listOf("_Compressed.mp4", "_Remuxed.mp4", "_HighQuality_Compressed.mp4", "_remux.mp4", ".mp4")) {
            val next = OutputSharing.newCacheFile(temporary.root, suffix)
            assertNotEquals(disclosed, next)
            next.writeBytes(box("moov", box("loci")))
        }
        assertFalse(disclosed.inputStream().use { VideoMetadataPreserver.outputHasLocationAtom(it.channel) })
        disclosed.delete()
        val retry = output(box("moov", box("loci")))
        assertNotEquals(disclosed, retry.file)
        assertFalse(disclosed.exists())
    }
}
