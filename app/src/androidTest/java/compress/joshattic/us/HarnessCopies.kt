package compress.joshattic.us

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.InputStream

/**
 * The device half of the safe-copy harness (scripts/device/safe-copy-harness.sh).
 *
 * The harness, outside the app, copies a personal original (A) to a verified baseline (B) in its run
 * folder and checks A and B before and after every case. This side never learns A's identity: it is
 * handed B's path, digest and length, and makes from B a disposable copy (C) that it owns, a new
 * MediaStore row in Movies/PLLabValidation/<run>/ written by this app and read back whole. Every
 * destructive step is preceded by [requireDisposable]: the URI must be in this run's allowlist, owned
 * by this app, in this run's folder, and hold the bytes the step expects. Anything else stops the
 * test. B is only ever read, through the shell, by path.
 */
internal class HarnessCopies(val context: Context, val runId: String) {

    init {
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "run id must be letters, digits, - and _" }
    }

    val relativePath = "Movies/PLLabValidation/$runId/"
    val runDir: File = File(context.filesDir, "pllab/$runId").apply { mkdirs() }
    /** Where the replacement cases keep their rollback copies: never the app's own recovery directory. */
    val recoveryDir: File = File(runDir, "recovery").apply { mkdirs() }
    private val allowlist = File(runDir, "allowlist.tsv")
    private val results = File(runDir, "results.jsonl")
    private val resolver = context.contentResolver

    /** A shell-readable file, read as the shell user. Only the harness's own folders are accepted. */
    fun readShellFile(path: String): InputStream {
        require(path.matches(Regex("[A-Za-z0-9/_.-]+")) && !path.contains("..")) { "unexpected characters in $path" }
        require(
            path.startsWith("/storage/emulated/0/Documents/PLLabValidation/$runId/") || path.startsWith("/data/local/tmp/pllab_")
        ) { "not a harness path: $path" }
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path")
        return ParcelFileDescriptor.AutoCloseInputStream(pfd)
    }

    private fun values(name: String) = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.IS_PENDING, 1)
        put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
    }

    /** Saves bytes to this run's folder as a new row, proven by GalleryPublication; null name collisions are the provider's. */
    fun publish(name: String, expectedSha256: String?, open: () -> InputStream): GalleryPublication.Result.Published {
        val result = GalleryPublication.save(open, MediaStoreGallery(resolver, values(name)), expectedSha256)
        assertTrue("could not make $name: $result", result is GalleryPublication.Result.Published)
        return result as GalleryPublication.Result.Published
    }

    /**
     * A disposable copy of [baselinePath] for [case]: published, read back as the baseline's
     * digest and length, and only then added to the allowlist.
     */
    fun createDisposableCopy(case: String, baselinePath: String, baselineSha256: String, baselineBytes: Long): Uri {
        val published = publish("PLLAB-C-$case-${System.currentTimeMillis()}.mp4", baselineSha256) { readShellFile(baselinePath) }
        assertEquals("the copy is not the baseline's length", baselineBytes, published.content.bytes)
        allowlist.appendText("${published.uri}\t$case\t${published.content.sha256}\t${published.content.bytes}\n")
        return Uri.parse(published.uri)
    }

    private fun allowlisted(uri: Uri): Boolean =
        allowlist.takeIf { it.isFile }?.readLines()?.any { it.substringBefore('\t') == uri.toString() } == true

    fun digest(uri: Uri): GalleryPublication.Content? =
        runCatching { resolver.openInputStream(uri)?.use { GalleryPublication.digest(it) } }.getOrNull()

    /**
     * Stops the test unless [uri] is a disposable copy of this run: allowlisted, owned by this app,
     * in this run's folder, and, when [expectedSha256] is given, holding exactly those bytes.
     */
    fun requireDisposable(uri: Uri, expectedSha256: String?) {
        assertTrue("$uri is not in this run's allowlist; stopping", allowlisted(uri))
        val args = Bundle().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        val (owner, path) = resolver.query(uri, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.RELATIVE_PATH), args, null)
            ?.use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null } ?: (null to null)
        assertEquals("$uri is not this app's own row; stopping", context.packageName, owner)
        assertEquals("$uri is not in this run's folder; stopping", relativePath, path)
        if (expectedSha256 != null) {
            assertEquals("$uri does not hold the bytes this step expects; stopping", expectedSha256, digest(uri)?.sha256)
        }
    }

    /** One case's verdict, for the harness to collect, and on the log for a capture. */
    fun record(case: String, verdict: String, fields: Map<String, Any?>) {
        val line = JSONObject().apply {
            put("runId", runId); put("case", case); put("verdict", verdict); put("atMs", System.currentTimeMillis())
            fields.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) }
        }.toString()
        results.appendText(line + "\n")
        println("pllab-result $line")
    }

    companion object {
        /** The harness's arguments, or a skipped test (reported incomplete) when they are absent. */
        fun args(vararg names: String): Map<String, String> {
            val bundle = InstrumentationRegistry.getArguments()
            val out = names.associateWith { bundle.getString(it).orEmpty() }
            assumeTrue("run through scripts/device/safe-copy-harness.sh (missing ${out.filterValues { it.isEmpty() }.keys})",
                out.values.all { it.isNotEmpty() })
            return out
        }

        fun sha256(bytes: ByteArray): String = ReplacementContentProof.sha256(bytes.inputStream())
    }
}
