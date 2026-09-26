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
