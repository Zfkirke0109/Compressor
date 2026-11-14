package compress.joshattic.us

import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSourceException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException

/**
 * The classifier decides when an export may be ended early, so it must match Media3 1.11's own
 * rule exactly: DefaultLoadErrorHandlingPolicy.isAnyCauseNonRetriable (ParserException,
 * FileNotFoundException, CleartextNotPermittedException, UnexpectedLoaderException, or a
 * DataSourceException for a read position out of range, anywhere in the cause chain), plus the
 * loader wrapping every non-I/O exception in UnexpectedLoaderException. Anything Media3 retries
 * must stay Media3's to retry.
 */
class SourceParseFailureTest {

    @Test
    fun theB167FailureIsFatal() {
        // What Mp4Extractor throws for a negative NAL length: the six stalled b167 sources.
        assertTrue(SourceParseFailure.isFatalToLoader(ParserException.createForMalformedContainer("Invalid NAL length", null)))
    }

    @Test
    fun aParserExceptionAnywhereInTheCauseChainIsFatal() {
        val wrapped = IOException("outer", ParserException.createForMalformedContainer("Invalid NAL length", null))
        assertTrue(SourceParseFailure.isFatalToLoader(wrapped))
    }

    @Test
    fun theOtherNonRetriableIoErrorsAreFatal() {
        assertTrue(SourceParseFailure.isFatalToLoader(FileNotFoundException("gone")))
        assertTrue(
            SourceParseFailure.isFatalToLoader(
                DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            )
        )
    }

    @Test
    fun errorsMedia3RetriesAreNotFatal() {
        // A truncated read, a transient provider error or an interrupted read (cancellation):
        // the loader retries all of these itself, so ending the export early would be wrong.
        assertFalse(SourceParseFailure.isFatalToLoader(EOFException()))
        assertFalse(SourceParseFailure.isFatalToLoader(IOException("transient")))
        assertFalse(SourceParseFailure.isFatalToLoader(InterruptedIOException()))
        assertFalse(SourceParseFailure.isFatalToLoader(DataSourceException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)))
    }

    @Test
    fun nonIoExceptionsAreFatalBecauseTheLoaderWrapsThem() {
        assertTrue(SourceParseFailure.isFatalToLoader(IllegalStateException("extractor bug")))
        assertTrue(SourceParseFailure.isFatalToLoader(ArrayIndexOutOfBoundsException()))
        assertTrue(SourceParseFailure.isFatalToLoader(OutOfMemoryError()))
    }

    @Test
    fun otherErrorsAreNotTheLoadersToClassify() {
        // Loader rethrows these on the playback thread; they are not load errors.
        assertFalse(SourceParseFailure.isFatalToLoader(StackOverflowError()))
    }

    @Test
    fun theDescriptionNamesTheErrorThePositionAndTheExtractor() {
        val f = SourceParseFailure.from(
            "Mp4Extractor",
            ParserException.createForMalformedContainer("Invalid NAL length", null),
            152_398_211L
        )
        // Media3's own message carries its flags ({contentIsMalformed=true, dataType=1}); kept.
        val text = f.describe()
        assertTrue(text, text.startsWith("ParserException: Invalid NAL length"))
        assertTrue(text, text.endsWith(" at byte 152,398,211 (Mp4Extractor)"))
        assertTrue(SourceParseException(f).message!!.contains(text))
    }

    @Test
    fun theGraceIsFarShorterThanTheTimeoutsItReplaces() {
        assertTrue(SourceParseFailure.EXPORT_GRACE_MS * 6 <= ExportWatchdogPolicy.PROBE_EXPORT_TIMEOUT_MS)
        assertTrue(SourceParseFailure.EXPORT_GRACE_MS * 12 <= ExportWatchdogPolicy.MAX_DELAY_BETWEEN_MUXER_SAMPLES_MS)
    }

    @Test
    fun aReportedExportErrorIsMatchedToTheParseFailureByItsCause() {
        val reported = RuntimeException("export", IOException("player", ParserException.createForMalformedContainer("x", null)))
        assertTrue(SourceParseFailure.hasParserCause(reported))
        assertFalse(SourceParseFailure.hasParserCause(RuntimeException("encoder", IllegalStateException())))
    }

    @Test
    fun anExportThatKeepsMovingIsNeverCutOff() {
        // A clipped probe whose parse error lay past the clip end: output keeps growing.
        val meter = ExportStallMeter(graceMs = 10_000L)
        var bytes = 0L
        for (t in 0L..60_000L step 1_000L) {
            bytes += 50_000L
            assertFalse("still moving at $t ms", meter.stalled(bytes, t))
        }
    }

    @Test
    fun anExportThatStopsMovingIsEndedAfterTheGrace() {
        val meter = ExportStallMeter(graceMs = 10_000L)
        assertFalse(meter.stalled(100L, 0L))
        assertFalse(meter.stalled(200L, 1_000L)) // the buffered samples are still being encoded
        assertFalse(meter.stalled(200L, 10_999L))
        assertTrue(meter.stalled(200L, 11_000L)) // ten seconds without a byte or a percent
    }
}
