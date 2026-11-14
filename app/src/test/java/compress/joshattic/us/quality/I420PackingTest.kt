package compress.joshattic.us.quality

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class I420PackingTest {
    private fun planes(pixel: Int, direct: Boolean, readOnly: Boolean = false): List<I420Packing.Plane> =
        listOf(2 to 4, 1 to 2, 1 to 2).mapIndexed { index, (w, h) ->
            val stride = (w + 2) * pixel + 3
            val offset = 3 + stride + pixel
            val limit = offset + (h - 1) * stride + (w - 1) * pixel + 1
            val buffer = if (direct) ByteBuffer.allocateDirect(limit) else ByteBuffer.allocate(limit)
            for (y in 0 until h) for (x in 0 until w) {
                buffer.put(offset + y * stride + x * pixel, ((if (index == 0) 1 else 9 + (index - 1) * 2) + y * w + x).toByte())
            }
            buffer.position(3)
            I420Packing.Plane(if (readOnly) buffer.asReadOnlyBuffer() else buffer, stride, pixel, 1, 1)
        }

    @Test fun knownPixelsSurviveEveryRotationStrideAndBufferKind() {
        val expected = mapOf(
            0 to listOf(1,2,3,4,5,6,7,8,9,10,11,12),
            90 to listOf(7,5,3,1,8,6,4,2,10,9,12,11),
            180 to listOf(8,7,6,5,4,3,2,1,10,9,12,11),
            270 to listOf(2,4,6,8,1,3,5,7,9,10,11,12))
        for (pixel in 1..3) for (direct in listOf(false, true)) for (readOnly in listOf(false, true)) {
            for ((rotation, values) in expected) {
                assertArrayEquals(values.map { it.toByte() }.toByteArray(), I420Packing.pack(planes(pixel, direct, readOnly), 2, 4, rotation))
            }
        }
    }

    @Test fun inputPositionsLimitsAndBytesAreUnchanged() {
        val planes = planes(2, true)
        val before = planes.map { p -> ByteArray(p.buffer.limit()).also { p.buffer.duplicate().apply { position(0) }.get(it) } }
        for (rotation in listOf(0, 90, 180, 270)) I420Packing.pack(planes, 2, 4, rotation)
        planes.forEachIndexed { index, p ->
            assertEquals(3, p.buffer.position())
            assertEquals(before[index].size, p.buffer.limit())
            val after = ByteArray(p.buffer.limit()).also { p.buffer.duplicate().apply { position(0) }.get(it) }
            assertArrayEquals(before[index], after)
        }
    }

    @Test fun overlappingNv21ChromaViewsKeepUAndVInTheCorrectOrder() {
        val y = ByteBuffer.allocateDirect(8).apply { put(byteArrayOf(1,2,3,4,5,6,7,8)); position(0) }
        val vu = ByteBuffer.allocateDirect(4).apply { put(byteArrayOf(11,9,12,10)); position(0) }
        val planes = listOf(I420Packing.Plane(y, 2, 1),
            I420Packing.Plane(vu.asReadOnlyBuffer().apply { position(1) }, 2, 2),
            I420Packing.Plane(vu.asReadOnlyBuffer(), 2, 2))
        assertArrayEquals(byteArrayOf(7,5,3,1,8,6,4,2,10,9,12,11), I420Packing.pack(planes, 2, 4, 90))
    }

    @Test fun aTruncatedLastChromaRowFailsBeforeWritingAnyDestination() {
        val planes = planes(2, true)
        planes.last().buffer.limit(planes.last().buffer.limit() - 1)
        val destination = ByteArray(12) { 55 }
        try { I420Packing.packPortable(planes, 2, 4, 0, destination); fail("Missing chroma must be rejected") }
        catch (_: IllegalArgumentException) { assertArrayEquals(ByteArray(12) { 55 }, destination) }
    }

    @Test fun invalidAndOverflowingGeometryAndRotationFailClosed() {
        for ((w, h, rotation) in listOf(Triple(0, 4, 0), Triple(3, 4, 0), Triple(2, 4, 45),
                Triple(Int.MAX_VALUE - 1, Int.MAX_VALUE - 1, 0))) {
            try { I420Packing.pack(planes(1, false), w, h, rotation); fail("Invalid layout must fail") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
