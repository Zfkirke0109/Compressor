package compress.joshattic.us.quality

import compress.joshattic.us.DiagnosticsArchivePlan
import java.io.File
import java.io.OutputStream

/** Content-addressed encoded files make an exact candidate reproducible. Never saves decoded frames. */
internal object ScoringCandidateEvidence {
    const val MAX_RETAINED_BYTES_PER_RUN = 512L * 1024 * 1024
    fun attach(request: FrameTraceRequest?, file: File): FrameTraceRequest? {
        if (request == null) return null
        val hash = file.inputStream().use {
            DiagnosticsArchivePlan.copyPayload("candidate", it, object : OutputStream() {
                override fun write(b: Int) {}
                override fun write(b: ByteArray, off: Int, len: Int) {}
            })
        }
        var retained = false
        var reason = "disabled"
        if (request.provenance["retainEncodedCandidates"] == true) {
            val dir = File(request.directory.parentFile, "candidates").apply { mkdirs() }
            val target = File(dir, "${hash.sha256}.mp4")
            val used = dir.listFiles().orEmpty().filter { it.name.endsWith(".mp4") }.sumOf { it.length() }
            if (!target.exists() && used + hash.bytes <= MAX_RETAINED_BYTES_PER_RUN) {
                val pending = File.createTempFile("candidate_", ".tmp", dir)
                try {
                    file.copyTo(pending, overwrite = true)
                    check(pending.renameTo(target))
                } finally { pending.delete() }
            }
            retained = target.isFile
            reason = if (retained) "retained" else "512MiB-per-run-cap"
        }
        return request.copy(provenance = request.provenance + mapOf(
            "candidateSha256" to hash.sha256, "candidateBytes" to hash.bytes,
            "candidateRetained" to retained, "candidateRetentionReason" to reason))
    }
}
