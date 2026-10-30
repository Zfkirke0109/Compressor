package compress.joshattic.us.quality

import compress.joshattic.us.JsonText
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPOutputStream

/** Immutable ownership: a later batch cannot redirect an in-flight window's evidence. */
data class FrameTraceRequest(
    val directory: File,
    val jobId: String,
    val attemptId: String,
    val stage: String,
    val provenance: Map<String, Any?> = emptyMap()
) {
    fun at(stage: String, fields: Map<String, Any?> = emptyMap()) =
        copy(stage = stage, provenance = provenance + fields)
}

/** Identifies a measurement policy, NOT a subjective transparency calibration. */
object ScientificPolicy {
    const val EPOCH = "sdr-v0-complete-20261002"
    const val MODEL = "vmaf_v0.6.1"
    const val MODEL_SHA256 = "5950d61fa1f861bd45d8149d80539ed9f3376cfc2495b8f0fa8e9f57cb131ee3"
    const val P5_METHOD = "sorted[floor((n-1)*0.05)]"
    const val VIEWING_CONDITION = "uncalibrated-native-display-raster"
}

/**
 * Optional research evidence. Stores hashes, timestamps and scalar scores only; never pixels.
 * One bounded buffer per window; atomic publication excludes partial writes from ZIP exports.
 * Trace failures and caps are reported as incomplete evidence and never change a verdict.
 */
internal class FrameEvidenceTrace(
    private val request: FrameTraceRequest,
    private val window: ScoreWindow,
    private val width: Int,
    private val height: Int,
    private val measurement: Map<String, Any?> = emptyMap()
) {
    companion object {
        const val MAX_PAIRS = 4096
        fun sha256(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size): String =
            MessageDigest.getInstance("SHA-256").run {
                update(bytes, offset, count)
                digest().joinToString("") { "%02x".format(it) }
            }
        internal fun planeHashes(data: ByteArray, width: Int, height: Int): Map<String, String> {
            val y = width * height
            require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0 && data.size == y * 3 / 2)
            return linkedMapOf("i420" to sha256(data), "y" to sha256(data, 0, y),
                "u" to sha256(data, y, y / 4), "v" to sha256(data, y + y / 4, y / 4))
        }
    }

    val id: String = UUID.randomUUID().toString()
    private val rows = ArrayList<Map<String, Any?>>()
    private var attempted = 0
    private var written = false

    fun pair(ref: I420Frame, dist: I420Frame, refNormalizedUs: Long, distNormalizedUs: Long, context: Boolean) {
        val index = attempted++
        if (rows.size >= MAX_PAIRS) return
        // PSNR is a diagnostic control, not another arbitrary acceptance gate.
        var squaredError = 0.0
        val ySize = width * height
        for (i in 0 until ySize) {
            val delta = (ref.data[i].toInt() and 255) - (dist.data[i].toInt() and 255)
            squaredError += delta.toDouble() * delta
        }
        val mse = squaredError / ySize
        rows += linkedMapOf(
            "type" to "frame", "pairIndex" to index,
            "sourcePtsUs" to ref.ptsUs, "candidatePtsUs" to dist.ptsUs,
            "sourceNormalizedPtsUs" to refNormalizedUs, "candidateNormalizedPtsUs" to distNormalizedUs,
            "skewUs" to refNormalizedUs - distNormalizedUs,
            "context" to context, "scored" to !context,
            "sourceHash" to planeHashes(ref.data, width, height),
            "candidateHash" to planeHashes(dist.data, width, height),
            "mseY" to mse, "psnrY" to if (mse == 0.0) null else 10.0 * kotlin.math.log10(65025.0 / mse),
            "psnrYInfinite" to (mse == 0.0),
            "sceneCut" to null, "encoderFrameType" to null, "gopPosition" to null
        )
    }

    fun finish(v0: DoubleArray?, v1: DoubleArray?, cambi: DoubleArray?, outcome: String,
               diagnostics: Map<String, Any?> = emptyMap()): File? {
        if (written) return null
        written = true
        val complete = rows.size == attempted && attempted > 0 && v0 != null && v0.size == attempted &&
            v0.all { it.isFinite() && it >= 0.0 } && outcome == "SCORED"
        fun metric(scores: DoubleArray?, i: Int): Double? =
            scores?.takeIf { it.size == attempted }?.getOrNull(i)?.takeIf { it.isFinite() && it >= 0.0 }
        val header = linkedMapOf<String, Any?>(
            "type" to "header", "schemaVersion" to 1, "traceId" to id,
            "jobId" to request.jobId, "attemptId" to request.attemptId, "stage" to request.stage,
            "windowId" to "${window.startUs}-${window.endUs}",
            "policyEpoch" to ScientificPolicy.EPOCH, "model" to ScientificPolicy.MODEL,
            "modelJsonSha256" to ScientificPolicy.MODEL_SHA256,
            "phoneTransform" to false, "pixelFormat" to "display-normalized-i420-8bit",
            "width" to width, "height" to height, "p5Method" to ScientificPolicy.P5_METHOD,
            "aggregation" to "arithmetic-per-window;every-required-window-must-pass",
            "meanGate" to QualityProbePolicy.WINDOW_MEAN_MIN, "p5Gate" to QualityProbePolicy.WINDOW_P5_MIN,
            "minGate" to QualityProbePolicy.WINDOW_MIN_MIN, "minFrames" to QualityProbePolicy.MIN_COMPARED_FRAMES_PER_WINDOW,
            "viewingCondition" to ScientificPolicy.VIEWING_CONDITION,
            "startUs" to window.startUs, "endUs" to window.endUs, "distStartUs" to window.distStartUs,
            "alignFirstFrames" to window.alignFirstFrames, "leadInUs" to window.leadInUs,
            "contextUs" to window.contextUs, "provenance" to request.provenance,
            "measurement" to measurement
        )
        val values = if (complete) rows.indices.filter { rows[it]["scored"] == true }.map { v0!![it] } else emptyList()
        val sorted = values.sorted()
        val mean = values.takeIf { it.isNotEmpty() }?.average()
        val p5 = sorted.takeIf { it.isNotEmpty() }?.get(((sorted.size - 1) * 0.05).toInt())
        val min = sorted.firstOrNull()
        val passed = complete && values.size >= QualityProbePolicy.MIN_COMPARED_FRAMES_PER_WINDOW &&
            mean!! >= QualityProbePolicy.WINDOW_MEAN_MIN && p5!! >= QualityProbePolicy.WINDOW_P5_MIN && min!! >= QualityProbePolicy.WINDOW_MIN_MIN
        request.directory.mkdirs()
        val destination = File(request.directory, "$id.jsonl.gz")
        val staging = File(request.directory, "$id.tmp")
        try {
            GZIPOutputStream(staging.outputStream().buffered()).bufferedWriter(Charsets.UTF_8).use { out ->
                fun line(value: Map<String, Any?>) {
                    out.write(JsonText.render(value).replace("\n", "")); out.newLine()
                }
                line(header)
                rows.forEachIndexed { i, row -> line(row + mapOf(
                    "vmafV0" to metric(v0, i), "vmafV1" to metric(v1, i), "cambi" to metric(cambi, i)
                )) }
                line(linkedMapOf("type" to "summary", "complete" to complete, "outcome" to outcome,
                    "recordedPairs" to rows.size, "fedPairs" to attempted, "scoredFrames" to values.size,
                    "mean" to mean, "p5" to p5, "min" to min, "windowGatePassed" to passed,
                    "diagnostics" to diagnostics))
            }
            check(staging.renameTo(destination)) { "cannot publish frame trace" }
            return destination
        } finally {
            staging.delete()
            rows.clear()
        }
    }
}
