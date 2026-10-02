package compress.joshattic.us

import android.media.MediaCodecInfo.CodecProfileLevel
import org.junit.Assert.*
import org.junit.Test

class EncoderInventoryScientificTest {
    @Test fun codecProfileNumbersAreNotGlobalBitDepthIdentifiers() {
        assertFalse(EncoderInventory.advertisesTenBit("video/avc", listOf(CodecProfileLevel.AVCProfileMain)))
        assertFalse(EncoderInventory.advertisesTenBit("video/x-vnd.on2.vp9", listOf(CodecProfileLevel.VP9Profile1)))
        assertTrue(EncoderInventory.advertisesTenBit("video/hevc", listOf(CodecProfileLevel.HEVCProfileMain10)))
    }
}
