package compress.joshattic.us

/**
 * What the export's input path had done by the time something went wrong. Pure, so the wording
 * of each diagnosis is unit-tested; [ExportInputProbe] fills it in from the live data source.
 *
 * Written for the two 30-minute H.264 sources that end every batch with
 * ERROR_CODE_MUXING_TIMEOUT. Their Media3 traces in b165 show the decoder, frame processor,
 * encoder and muxer all idle with nothing in flight: the asset loader simply stopped delivering
 * samples 216 s and 47 s into the files. The interleave hypothesis is refuted (audio and video are
 * within 166-208 ms of each other in storage order). What is left is the input side, which the
 * trace does not cover: is a read blocked, did the loader stop asking, or did the source report an
 * early end? Each has a different fix, and this report tells them apart.
 *
 * b167 answered it: every stall closed the input right after a 5-byte read and never reopened it,
 * which is Mp4Extractor rejecting a NAL length. The extractor error itself is now recorded
 * ([parseFailure], from SourceParseFailure) and named first.
 */
data class ExportInputProbeReport(
    val opens: Int,
    val closes: Int,
    val bytes: Long,
    val readsEntered: Long,
    val readsExited: Long,
    val lastOpenPosition: Long,
    val lastOpenLength: Long,
    val lastOpenAtMs: Long,
    val lastByteAtMs: Long,
    val lastCloseAtMs: Long,
    /** The last value `read` returned: bytes, 0, or -1 for end of input. */
    val lastReadResult: Int,
    /** The extractor error the loader will not recover from, if one was thrown (SourceParseFailure). */
    val parseFailure: String? = null,
    /** Extractor read errors Media3 retried by itself. */
    val retriedReadErrors: Int = 0
) {
    val isOpen: Boolean get() = opens > closes
    val readInFlight: Boolean get() = readsEntered > readsExited

    fun compact(nowMs: Long): String {
        fun ago(t: Long) = if (t <= 0L) "never" else "${(nowMs - t).coerceAtLeast(0L)}ms ago"
        return "input[opens=$opens,closes=$closes,open=$isOpen,bytes=${"%.1f".format(java.util.Locale.US, bytes / 1e6)}MB," +
            "lastOpenPos=$lastOpenPosition,lastOpenLen=$lastOpenLength,reads=$readsEntered,readInFlight=$readInFlight," +
            "lastRead=$lastReadResult,lastByte=${ago(lastByteAtMs)},lastOpen=${ago(lastOpenAtMs)},lastClose=${ago(lastCloseAtMs)}" +
            (if (retriedReadErrors > 0) ",retriedReadErrors=$retriedReadErrors" else "") + "]"
    }

    /** One sentence naming where the input path stopped. */
    fun diagnosis(nowMs: Long): String = when {
        // Checked first: when the extractor threw, every other symptom below (input closed, no
        // read pending) is a consequence of it, and naming the symptom hides the cause.
        parseFailure != null -> "the extractor threw $parseFailure; Media3's loader does not retry that " +
            "and the export does not report it, so the input stopped here"
        opens == 0 -> "the source was never opened"
        readInFlight -> "a read of the source has been blocked for ${(nowMs - lastByteAtMs.coerceAtLeast(lastOpenAtMs)).coerceAtLeast(0L)} ms: " +
            "an I/O stall in the content provider, not in Media3"
        lastReadResult == -1 -> "the source reported end of input after $bytes bytes from position $lastOpenPosition: " +
            "the file ended earlier than its sample table says, or the provider truncated it"
        isOpen -> "the source is open, no read is pending and the last byte arrived ${(nowMs - lastByteAtMs).coerceAtLeast(0L)} ms ago: " +
            "the loader stopped asking for bytes (load control or player state), not the file"
        else -> "the source was closed ${(nowMs - lastCloseAtMs).coerceAtLeast(0L)} ms ago and not reopened: " +
            "the extractor took that for the end of the stream, or the player stopped loading"
    }
}
