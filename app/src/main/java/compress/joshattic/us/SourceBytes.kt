package compress.joshattic.us

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.Locale

/**
 * The raw bytes of a source around one offset, for the decision log. Used where an extractor
 * failed on the file's own data ([SourceParseFailure]), so a capture shows what is actually
 * stored there rather than only the parser's one-line complaint. Read-only and diagnostic.
 */
object SourceBytes {

    /** Bytes shown before and after the offset. */
    const val BEFORE = 32
    const val AFTER = 16

    fun hexAround(context: Context, uri: Uri, position: Long): String? = runCatching {
        val start = (position - BEFORE).coerceAtLeast(0L)
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                val buffer = ByteBuffer.allocate((position - start).toInt() + AFTER)
                var at = start
                while (buffer.hasRemaining()) {
                    val n = channel.read(buffer, at)
                    if (n <= 0) break
                    at += n
                }
                val bytes = ByteArray(buffer.position())
                buffer.flip()
                buffer.get(bytes)
                hexWindow(bytes, start, position)
            }
        }
    }.getOrNull()

    /**
     * Share of zero bytes above which the data around a parse failure is taken as missing, not
     * malformed. b168: every one of the six files Media3 could not read had only zeros at its
     * failure offset, and the platform copies of them held 2-16 % of the source's bytes, because
     * the platform extractor skips empty samples. A real bitstream is never 99 % zeros over 64 KiB.
     */
    const val DAMAGED_ZERO_FRACTION = 0.99

    /** Bytes read around the failure for [zeroFraction]. */
    const val ZERO_SCAN_SPAN = 64 * 1024

    /** Share of zero bytes in the [ZERO_SCAN_SPAN] bytes centred on [position], or null if unreadable. */
    fun zeroFraction(context: Context, uri: Uri, position: Long): Double? = runCatching {
        val start = (position - ZERO_SCAN_SPAN / 2).coerceAtLeast(0L)
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                val buffer = ByteBuffer.allocate(ZERO_SCAN_SPAN)
                var at = start
                while (buffer.hasRemaining()) {
                    val n = channel.read(buffer, at)
                    if (n <= 0) break
                    at += n
                }
                val bytes = ByteArray(buffer.position())
                buffer.flip()
                buffer.get(bytes)
                zeroFraction(bytes)
            }
        }
    }.getOrNull()

    fun zeroFraction(bytes: ByteArray): Double? =
        if (bytes.isEmpty()) null else bytes.count { it.toInt() == 0 }.toDouble() / bytes.size

    /**
     * `bytes[start..end): aa bb | cc dd` with `|` at [mark]: what was read up to the failure on
     * the left, the bytes after it on the right.
     */
    fun hexWindow(bytes: ByteArray, start: Long, mark: Long): String {
        val sb = StringBuilder()
        sb.append("bytes[").append(String.format(Locale.US, "%,d", start)).append("..")
            .append(String.format(Locale.US, "%,d", start + bytes.size)).append("):")
        bytes.forEachIndexed { i, b ->
            if (start + i == mark) sb.append(" |")
            sb.append(' ').append(String.format(Locale.US, "%02x", b.toInt() and 0xff))
        }
        if (start + bytes.size == mark) sb.append(" |")
        return sb.toString()
    }
}
