package compress.joshattic.us

import androidx.test.ext.junit.runners.AndroidJUnit4
import compress.joshattic.us.quality.I420Packing
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class FramePackingDeviceTest {
    @Test fun nativePackingMatchesPortablePixelsAtPhoneResolutions() {
        assertTrue("The packaged native fast path must load", I420Packing.nativeAvailable)
        for ((width, height) in listOf(34 to 66, 480 to 848, 1080 to 1920, 2160 to 3840)) {
            val planes = (0..2).map { p ->
                val w = if (p == 0) width else width / 2
                val h = if (p == 0) height else height / 2
                val pixel = if (p == 0) 1 else 2
                val stride = (w + 2) * pixel + 17
                val offset = 3 + stride + pixel
                val length = offset + (h - 1) * stride + (w - 1) * pixel + 1
                val buffer = ByteBuffer.allocateDirect(length)
                for (i in 0 until length) buffer.put(i, (i * 31 + p * 79).toByte())
                buffer.position(3)
                I420Packing.Plane(buffer.asReadOnlyBuffer(), stride, pixel, 1, 1)
            }
            for (rotation in listOf(0, 90, 180, 270)) {
                assertArrayEquals("$width x $height rotation $rotation",
                    I420Packing.packPortable(planes, width, height, rotation), I420Packing.pack(planes, width, height, rotation))
            }
        }
    }

    @Test fun nestedLearningEvidenceStaysStructuredWithAndroidJson() {
        val fields = mapOf("observation" to mapOf("kind" to "VISUAL_PASS", "windowIds" to listOf("10-20"),
            "details" to mapOf("ratio" to 0.65)), "effectApplied" to true, "absent" to null)
        val json = JSONObject()
        DiagnosticsRecorder.putFields(json, fields)
        val decoded = JSONObject(json.toString())
        assertEquals("VISUAL_PASS", decoded.getJSONObject("observation").getString("kind"))
        assertEquals("10-20", decoded.getJSONObject("observation").getJSONArray("windowIds").getString(0))
        assertEquals(0.65, decoded.getJSONObject("observation").getJSONObject("details").getDouble("ratio"), 0.0)
        assertTrue(decoded.getBoolean("effectApplied")); assertTrue(decoded.isNull("absent"))
    }
}
