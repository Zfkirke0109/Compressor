package compress.joshattic.us.quality

import java.nio.ByteBuffer

/** Lossless packing/rotation only: no scaling, color conversion, or sample changes. */
internal object I420Packing {
    data class Plane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int,
                     val cropLeft: Int = 0, val cropTop: Int = 0)

    val nativeAvailable: Boolean = runCatching { System.loadLibrary("compressorvmaf") }.isSuccess

    fun pack(planes: List<Plane>, width: Int, height: Int, rotation: Int): ByteArray {
        val layout = layout(planes, width, height, rotation)
        val dest = ByteArray((width.toLong() * height * 3 / 2).toInt())
        // JNI copies cost more than they save on unrotated frames in the host controls.
        // Keep the native path for the measured quarter-turn transpose bottleneck.
        if ((rotation == 90 || rotation == 270) && nativeAvailable && planes.all { it.buffer.isDirect } &&
            packNative(planes.map { it.buffer }.toTypedArray(), layout, dest, width, height, rotation)) return dest
        packPortable(planes, width, height, rotation, dest)
        return dest
    }

    /** Kept as a portable reference/fallback and exercised against the native path on Android. */
    internal fun packPortable(planes: List<Plane>, width: Int, height: Int, rotation: Int,
                              destination: ByteArray? = null): ByteArray {
        val layout = layout(planes, width, height, rotation)
        val dest = destination ?: ByteArray((width.toLong() * height * 3 / 2).toInt())
        require(dest.size.toLong() == width.toLong() * height * 3 / 2)
        var offset = 0
        for (p in 0..2) {
            val w = if (p == 0) width else width / 2
            val h = if (p == 0) height else height / 2
            val source = planes[p].buffer.duplicate()
            val base = layout[p * 4]
            val stride = layout[p * 4 + 1]
            val pixel = layout[p * 4 + 2]
            val row = ByteArray((w - 1) * pixel + 1)
            for (y in 0 until h) {
                source.position(base + y * stride)
                source.get(row)
                if (rotation == 0 && pixel == 1) {
                    row.copyInto(dest, offset + y * w)
                } else for (x in 0 until w) {
                    val index = when (rotation) {
                        90 -> x * h + h - 1 - y
                        180 -> (h - 1 - y) * w + w - 1 - x
                        270 -> (w - 1 - x) * h + y
                        else -> y * w + x
                    }
                    dest[offset + index] = row[x * pixel]
                }
            }
            offset += w * h
        }
        return dest
    }

    private fun layout(planes: List<Plane>, width: Int, height: Int, rotation: Int): IntArray {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        require(width.toLong() * height <= Int.MAX_VALUE.toLong() * 2 / 3)
        require(rotation in listOf(0, 90, 180, 270) && planes.size == 3)
        return IntArray(12).also { result ->
            for (p in 0..2) {
                val plane = planes[p]
                val w = if (p == 0) width else width / 2
                val h = if (p == 0) height else height / 2
                require(plane.rowStride > 0 && plane.pixelStride > 0 && plane.cropLeft >= 0 && plane.cropTop >= 0)
                val rowBytes = (w - 1L) * plane.pixelStride + 1L
                val start = plane.buffer.position().toLong() + plane.cropTop.toLong() * plane.rowStride + plane.cropLeft.toLong() * plane.pixelStride
                require(rowBytes <= plane.rowStride && start <= plane.buffer.limit()) {
                    "Decoder plane crop is outside its buffer"
                }
                val end = start + (h - 1L) * plane.rowStride + rowBytes
                require(end <= plane.buffer.limit()) {
                    "Decoder plane does not contain the cropped frame"
                }
                result[p * 4] = start.toInt()
                result[p * 4 + 1] = plane.rowStride
                result[p * 4 + 2] = plane.pixelStride
                result[p * 4 + 3] = plane.buffer.limit()
            }
        }
    }

    private external fun packNative(planes: Array<ByteBuffer>, layout: IntArray, dest: ByteArray,
                                    width: Int, height: Int, rotation: Int): Boolean
}
