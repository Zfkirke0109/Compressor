package compress.joshattic.us

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.ForwardingExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.amr.AmrExtractor
import androidx.media3.extractor.ts.AdtsExtractor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A DataSource.Factory that counts what Media3's asset loader reads from the source, so an
 * export failure can say whether the INPUT side was still delivering. See [ExportInputProbeReport]
 * for why. Wrapping is the only way in: Transformer does not expose its ExoPlayer, and the
 * Media3 debug trace starts after the extractor.
 *
 * Costs two atomic increments per read call; the loader reads in 64 KiB units, so this is nothing
 * next to the decode.
 *
 * It also watches the extractors (see [SourceParseFailure]): the first error Media3's loader will
 * never recover from is kept in [parseFailure] and handed to [onFatalParse], because in an export
 * Media3 itself never reports it.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class ExportInputProbe(private val upstream: DataSource.Factory) : DataSource.Factory {

    private val opens = AtomicInteger()
    private val closes = AtomicInteger()
    private val bytes = AtomicLong()
    private val readsEntered = AtomicLong()
    private val readsExited = AtomicLong()

    @Volatile private var lastOpenPosition = -1L
    @Volatile private var lastOpenLength = -1L
    @Volatile private var lastOpenAtMs = 0L
    @Volatile private var lastByteAtMs = 0L
    @Volatile private var lastCloseAtMs = 0L
    @Volatile private var lastReadResult = 0

    /** The first read error the loader will not recover from; see [SourceParseFailure]. */
    @Volatile var parseFailure: SourceParseFailure? = null
        private set

    /** Read errors Media3 retries by itself. Counted only, for the capture. */
    private val retriedReadErrors = AtomicInteger()

    /**
     * Called once, on the loader thread, with the first [parseFailure]. The export's owner posts
     * from here to its own thread; nothing Media3 does is changed by it.
     */
    @Volatile var onFatalParse: ((SourceParseFailure) -> Unit)? = null

    fun report(): ExportInputProbeReport = ExportInputProbeReport(
        opens = opens.get(),
        closes = closes.get(),
        bytes = bytes.get(),
        readsEntered = readsEntered.get(),
        readsExited = readsExited.get(),
        lastOpenPosition = lastOpenPosition,
        lastOpenLength = lastOpenLength,
        lastOpenAtMs = lastOpenAtMs,
        lastByteAtMs = lastByteAtMs,
        lastCloseAtMs = lastCloseAtMs,
        lastReadResult = lastReadResult,
        parseFailure = parseFailure?.describe(),
        retriedReadErrors = retriedReadErrors.get()
    )

    /** Compact state plus the one-line diagnosis, for a log line at the moment of failure. */
    fun snapshot(): String {
        val now = System.currentTimeMillis()
        val r = report()
        return "${r.compact(now)} ${r.diagnosis(now)}"
    }

    override fun createDataSource(): DataSource = Probed(upstream.createDataSource())

    /**
     * A media source factory that reads through this probe and is otherwise the one Transformer
     * builds for itself (ExoPlayerAssetLoader.Factory.createMediaSourceFactory in Media3 1.11,
     * read from the bytecode): constant-bitrate seeking for ADTS and AMR, and clipping inside the
     * media period. The clipping mode decides which frame a clipped probe starts on, so it must
     * not change. Media3 adds MP4 SEF parsing only for slow-motion flattening, which this app
     * never requests.
     */
    fun mediaSourceFactory(): MediaSource.Factory {
        val extractors = DefaultExtractorsFactory()
            .setAdtsExtractorFlags(AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
            .setAmrExtractorFlags(AmrExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
        return DefaultMediaSourceFactory(this, Watched(extractors)).setEnableClippingInMediaPeriod(true)
    }

    private fun noteReadError(extractor: String, t: Throwable, position: Long) {
        if (!SourceParseFailure.isFatalToLoader(t)) {
            retriedReadErrors.incrementAndGet()
            return
        }
        val failure = SourceParseFailure.from(extractor, t, position)
        synchronized(this) {
            if (parseFailure != null) return
            parseFailure = failure
        }
        onFatalParse?.invoke(failure)
    }

    /**
     * Media3's own forwarding types, so the setters DefaultMediaSourceFactory calls (subtitle
     * parsing, text transcoding, GOP sample dependencies; it discards their return values) and
     * the URI-ordered `createExtractors(uri, headers)` still reach DefaultExtractorsFactory. The
     * JPEG and HEIF flags it sets only on a DefaultExtractorsFactory apply to image items alone.
     */
    private inner class Watched(delegate: ExtractorsFactory) : ForwardingExtractorsFactory(delegate) {
        override fun createExtractors(): Array<Extractor> = super.createExtractors().map(::watch).toTypedArray()

        override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
            super.createExtractors(uri, responseHeaders).map(::watch).toTypedArray()

        private fun watch(extractor: Extractor): Extractor = WatchedExtractor(extractor)
    }

    /** Only `read` is observed; every other call, and the error itself, pass through unchanged. */
    private inner class WatchedExtractor(delegate: Extractor) : ForwardingExtractor(delegate) {
        private val name = delegate.underlyingImplementation.javaClass.simpleName

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
            try {
                return super.read(input, seekPosition)
            } catch (t: Throwable) {
                runCatching { noteReadError(name, t, input.position) }
                throw t
            }
        }
    }

    private inner class Probed(private val delegate: DataSource) : DataSource {
        override fun addTransferListener(transferListener: TransferListener) =
            delegate.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            opens.incrementAndGet()
            lastOpenPosition = dataSpec.position
            lastOpenLength = dataSpec.length
            lastOpenAtMs = System.currentTimeMillis()
            return delegate.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readsEntered.incrementAndGet()
            try {
                val n = delegate.read(buffer, offset, length)
                lastReadResult = n
                if (n > 0) {
                    bytes.addAndGet(n.toLong())
                    lastByteAtMs = System.currentTimeMillis()
                }
                return n
            } finally {
                readsExited.incrementAndGet()
            }
        }

        override fun getUri(): Uri? = delegate.uri

        override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

        override fun close() {
            closes.incrementAndGet()
            lastCloseAtMs = System.currentTimeMillis()
            delegate.close()
        }
    }
}
