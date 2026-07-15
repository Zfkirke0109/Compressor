package compress.joshattic.us.quality

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log

/** One decoded frame, display-normalized packed I420 (Y, then U, then V; no padding). */
class I420Frame(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val ptsUs: Long
)

/**
 * Synchronous MediaCodec video decoder that yields display-normalized I420 frames for a
 * PTS window. Rotation metadata (90/180/270) is applied to the pixel data so two files with
 * different coded orientations (e.g. a portrait source vs Media3's landscape-coded output
 * with a compensating rotation hint) compare in the same display space.
 *
 * Fail-closed: any decode irregularity throws; callers treat that as "no pixel evidence".
 */
class YuvFrameReader(
    private val context: Context,
    private val uri: Uri,
    private val startUs: Long,
    private val endUs: Long,
    private val onFrame: (I420Frame) -> Boolean // return false to stop early
) {
    companion object {
        private const val TAG = "YuvFrameReader"
        private const val DEQUEUE_TIMEOUT_US = 50_000L
        private const val MAX_DRY_LOOPS = 400 // ~20s of no progress -> abort

        /** Display-space dimensions (rotation applied) of the first video track. */
        fun displayGeometry(context: Context, uri: Uri): Triple<Int, Int, Int>? {
            val extractor = MediaExtractor()
            return try {
                extractor.setDataSource(context, uri, null)
                val track = selectVideoTrack(extractor) ?: return null
                val format = extractor.getTrackFormat(track)
                val w = format.getInteger(MediaFormat.KEY_WIDTH)
                val h = format.getInteger(MediaFormat.KEY_HEIGHT)
                val rot = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                    format.getInteger(MediaFormat.KEY_ROTATION)
                } else 0
                if (rot == 90 || rot == 270) Triple(h, w, rot) else Triple(w, h, rot)
            } catch (t: Throwable) {
                Log.w(TAG, "displayGeometry failed: ${t.message}")
                null
            } finally {
                runCatching { extractor.release() }
            }
        }

        private fun selectVideoTrack(extractor: MediaExtractor): Int? {
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) return i
            }
            return null
        }
    }

    /** Decodes the window; returns the number of frames delivered. */
    fun run(): Int {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var delivered = 0
        try {
            extractor.setDataSource(context, uri, null)
            val track = selectVideoTrack(extractor)
                ?: throw IllegalStateException("no video track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                format.getInteger(MediaFormat.KEY_ROTATION)
            } else 0
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var dryLoops = 0
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0 || extractor.sampleTime > endUs + 500_000L) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (++dryLoops > MAX_DRY_LOOPS) throw IllegalStateException("decoder stalled")
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> dryLoops = 0
                    else -> if (outIndex >= 0) {
                        dryLoops = 0
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val pts = info.presentationTimeUs
                        var keepGoing = true
                        if (info.size > 0 && pts >= startUs && pts < endUs) {
                            val image = codec.getOutputImage(outIndex)
                                ?: throw IllegalStateException("no output image")
                            val frame = imageToDisplayI420(image, rotation, pts)
                            codec.releaseOutputBuffer(outIndex, false)
                            delivered++
                            keepGoing = onFrame(frame)
                        } else {
                            codec.releaseOutputBuffer(outIndex, false)
                        }
                        if (eos || pts >= endUs || !keepGoing) outputDone = true
                    }
                }
            }
            return delivered
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun imageToDisplayI420(image: Image, rotation: Int, ptsUs: Long): I420Frame {
        val crop = image.cropRect
        val w = crop.width() and 1.inv()
        val h = crop.height() and 1.inv()
        val packed = ByteArray(w * h * 3 / 2)
        // Y
        copyPlane(image.planes[0], crop.left, crop.top, w, h, packed, 0, w)
        // U, V (chroma at half resolution)
        val cw = w / 2
        val ch = h / 2
        copyPlane(image.planes[1], crop.left / 2, crop.top / 2, cw, ch, packed, w * h, cw)
        copyPlane(image.planes[2], crop.left / 2, crop.top / 2, cw, ch, packed, w * h + cw * ch, cw)
        return when (rotation) {
            90 -> rotate(packed, w, h, clockwise = true, ptsUs)
            270 -> rotate(packed, w, h, clockwise = false, ptsUs)
            180 -> rotate180(packed, w, h, ptsUs)
            else -> I420Frame(packed, w, h, ptsUs)
        }
    }

    private fun copyPlane(
        plane: Image.Plane,
        cropLeft: Int,
        cropTop: Int,
        outW: Int,
        outH: Int,
        dest: ByteArray,
        destOffset: Int,
        destStride: Int
    ) {
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        val base = buf.position()
        for (row in 0 until outH) {
            val srcRow = base + (cropTop + row) * rowStride + cropLeft * pixStride
            var d = destOffset + row * destStride
            if (pixStride == 1) {
                val tmp = ByteArray(outW)
                buf.position(srcRow)
                buf.get(tmp, 0, outW)
                System.arraycopy(tmp, 0, dest, d, outW)
            } else {
                for (col in 0 until outW) {
                    dest[d++] = buf.get(srcRow + col * pixStride)
                }
            }
        }
        buf.position(base)
    }

    private fun rotate(src: ByteArray, w: Int, h: Int, clockwise: Boolean, ptsUs: Long): I420Frame {
        val out = ByteArray(src.size)
        rotatePlane(src, 0, w, h, out, 0, clockwise)
        val cw = w / 2
        val ch = h / 2
        rotatePlane(src, w * h, cw, ch, out, w * h, clockwise)
        rotatePlane(src, w * h + cw * ch, cw, ch, out, w * h + cw * ch, clockwise)
        return I420Frame(out, h, w, ptsUs)
    }

    private fun rotatePlane(
        src: ByteArray, srcOff: Int, w: Int, h: Int,
        dst: ByteArray, dstOff: Int, clockwise: Boolean
    ) {
        // Output plane is h x w.
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = src[srcOff + y * w + x]
                if (clockwise) {
                    dst[dstOff + x * h + (h - 1 - y)] = v
                } else {
                    dst[dstOff + (w - 1 - x) * h + y] = v
                }
            }
        }
    }

    private fun rotate180(src: ByteArray, w: Int, h: Int, ptsUs: Long): I420Frame {
        val out = ByteArray(src.size)
        flipPlane(src, 0, w, h, out, 0)
        val cw = w / 2
        val ch = h / 2
        flipPlane(src, w * h, cw, ch, out, w * h)
        flipPlane(src, w * h + cw * ch, cw, ch, out, w * h + cw * ch)
        return I420Frame(out, w, h, ptsUs)
    }

    private fun flipPlane(src: ByteArray, srcOff: Int, w: Int, h: Int, dst: ByteArray, dstOff: Int) {
        val n = w * h
        for (i in 0 until n) dst[dstOff + i] = src[srcOff + n - 1 - i]
    }
}
