package compress.joshattic.us

import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale

/**
 * An error the extractor raised while Media3 was reading the source for an export. Recorded
 * because in an export Media3 never reports it itself.
 *
 * Why (Media3 1.11, read from the bytecode). When `Extractor.read` throws, the loader asks
 * DefaultLoadErrorHandlingPolicy whether to retry. For a ParserException the answer is no, so the
 * loader stops for good and keeps the error until something calls `maybeThrowError()`. ExoPlayer
 * only does that for a renderer that is not ready, and the Transformer's asset-loader renderers
 * always report ready. So the export does not fail. It goes idle until the muxer watchdog ends it
 * 120 s later as ERROR_CODE_MUXING_TIMEOUT, or the probe's own 60 s timeout does.
 *
 * The six sources that stalled in batch_1790370196005 fit this exactly. Every snapshot shows the
 * input closed and never reopened right after a 5-byte read: an H.264 NAL length plus its header
 * byte, which is where Mp4Extractor throws "Invalid NAL length". The full encode of one file
 * stopped at the same 152.4 MB in two separate runs, so the stop is decided by the file's bytes.
 *
 * [isFatalToLoader] is Media3's own non-retriable rule (DefaultLoadErrorHandlingPolicy.
 * isAnyCauseNonRetriable, plus Loader wrapping a non-I/O exception in UnexpectedLoaderException).
 * An error it accepts is one the loader never recovers from during an export, so acting on it
 * early changes only how long the failure takes, not whether it happens. Anything else Media3
 * retries by itself, and it is only counted.
 */
data class SourceParseFailure(
    /** Simple class name of the extractor that threw, e.g. Mp4Extractor. */
    val extractor: String,
    val errorClass: String,
    val message: String?,
    /** The extractor input's byte position when the error was thrown. */
    val inputPosition: Long
) {
    fun describe(): String =
        errorClass + (message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "") +
            " at byte ${String.format(Locale.US, "%,d", inputPosition)} ($extractor)"

    companion object {
        /**
         * How long an export may go without progress after its loader has stopped for good before
         * it is ended. A full-file export cannot complete: its renderers wait for samples that will
         * never be loaded, so once the samples already buffered are encoded it stops moving. A
         * clipped probe can complete, when the error lies past the clip end and every sample it
         * needs was loaded; it keeps moving until it does, so it is never cut off by this. The
         * grace is a sixth of the probe timeout and a twelfth of the muxer watchdog it replaces.
         */
        const val EXPORT_GRACE_MS = 10_000L

        /** How often a stopped loader's export is checked for progress. */
        const val STALL_POLL_MS = 1_000L

        /** The reason prefix a probe rung and a full encode report, so callers can match it. */
        const val REASON_PREFIX = "source parse failed:"

        @androidx.annotation.OptIn(UnstableApi::class)
        fun isFatalToLoader(t: Throwable): Boolean {
            // Loader wraps anything that is not an IOException (and OutOfMemoryError) in
            // UnexpectedLoaderException, which the policy never retries. Other Errors are
            // rethrown on the playback thread and are not the loader's to classify.
            if (t !is IOException) return t is RuntimeException || t is OutOfMemoryError
            var cause: Throwable? = t
            while (cause != null) {
                if (cause is ParserException || cause is FileNotFoundException) return true
                if (cause is DataSourceException && cause.reason == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) return true
                cause = cause.cause
            }
            return false
        }

        /**
         * True when [t] (an export error Media3 did report, typically during preparation) was
         * caused by a ParserException, so it is the same failure as a recorded [SourceParseFailure].
         */
        fun hasParserCause(t: Throwable): Boolean {
            var cause: Throwable? = t
            while (cause != null) {
                if (cause is ParserException) return true
                cause = cause.cause
            }
            return false
        }

        fun from(extractor: String, t: Throwable, inputPosition: Long) = SourceParseFailure(
            extractor = extractor,
            errorClass = t.javaClass.simpleName,
            message = t.message?.take(160),
            inputPosition = inputPosition
        )
    }
}

/**
 * The full encode's counterpart of a probe rung's `source parse failed` reason. Thrown into the
 * encode's coroutine instead of letting the export idle until the watchdog; see [SourceParseFailure].
 */
class SourceParseException(val failure: SourceParseFailure) : IOException(
    "The encoder's file reader stopped on data it could not parse " +
        "(${failure.describe()}), so this file could not be re-encoded."
)

/**
 * Decides when an export whose loader has stopped for good has also stopped moving: no change in
 * its activity signal (output bytes plus reported progress, both non-decreasing) for [graceMs].
 * Pure, with the clock passed in, so the rule is unit-tested.
 */
class ExportStallMeter(private val graceMs: Long = SourceParseFailure.EXPORT_GRACE_MS) {
    private var last: Long? = null
    private var changedAtMs = 0L

    /** Records [activity] at [nowMs]; true once it has not changed for [graceMs]. */
    fun stalled(activity: Long, nowMs: Long): Boolean {
        if (activity != last) {
            last = activity
            changedAtMs = nowMs
        }
        return nowMs - changedAtMs >= graceMs
    }
}
