package compress.joshattic.us

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes Compressor's own diagnostics to the public Downloads folder, and hands a live-capture
 * command to Termux.
 *
 * Exists because every previous debugging round lost time to transport rather than to analysis.
 * `DiagnosticsRecorder` writes into the app's private files directory, which adb cannot read
 * across Android users — from Samsung Secure Folder (user 150) there is no adb path to it at all —
 * and the logcat workaround is bounded by a 5 MiB buffer this device will not raise, so a 219-job
 * capture arrives with its `session_start` record already rolled off. An app reading its own files
 * and its own log entries has neither limitation and needs no adb, root or PC.
 *
 * Scope discipline: this module only ever reads Compressor's own data. The logcat dump is filtered
 * to this process id, which is both what the platform will grant without the privileged READ_LOGS
 * permission and what keeps other apps' output out of a file the user is about to share.
 *
 * All decisions live in [DiagnosticsExportPlan] (pure, unit-tested); this file is the IO.
 */
object DiagnosticsExporter {

    private const val TAG = "CompressorBatch"
    private const val TERMUX_PACKAGE = "com.termux"
    private const val TERMUX_RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
    private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"

    /** Outcome of an export attempt. Never reports success without a written file to point at. */
    sealed interface ExportResult {
        data class Written(val displayName: String, val bytes: Long, val uri: Uri?) : ExportResult
        data class Empty(val reason: String) : ExportResult
        data class Failed(val reason: String) : ExportResult
    }

    /**
     * Copy every recorded session into `Downloads/Compressor/`, newest first, as one concatenated
     * file. Concatenated rather than one-file-per-batch because the analysis tooling keys on
     * `batchId` and reads records in file order, so a single file stays directly usable while
     * remaining trivially splittable.
     */
    fun exportSessions(context: Context): ExportResult {
        val sessions = recordedSessionFiles(context)
        if (sessions.isEmpty()) {
            return ExportResult.Empty("No recorded sessions yet — run a batch first.")
        }
        val newestBatchId = sessions.first().parentFile?.name
        val content = buildString {
            for (file in sessions) {
                val text = runCatching { file.readText() }.getOrNull() ?: continue
                append(text)
                if (!text.endsWith("\n")) append('\n')
            }
        }
        if (content.isBlank()) {
            return ExportResult.Empty("Session files exist but are empty.")
        }
        return write(
            context,
            DiagnosticsExportPlan.exportFileName("compressor-sessions", newestBatchId, timestamp()),
            content
        )
    }

    /**
     * Dump this process's diagnostic log lines into `Downloads/Compressor/`.
     *
     * Complements the session export rather than duplicating it: the JSONL carries the structured
     * per-job records, while logcat additionally carries the human-readable decision lines that
     * are never written to it — including the `CompressorVerification` per-check detail line.
     */
    fun exportLogcat(context: Context): ExportResult {
        val pid = Process.myPid()
        val args = DiagnosticsExportPlan.logcatDumpArgs(pid)
        val output = runCatching {
            val process = ProcessBuilder(args).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            text
        }.getOrElse {
            Log.w(TAG, "logcat export failed", it)
            return ExportResult.Failed("Could not read this app's log: ${it.message ?: it::class.java.simpleName}")
        }
        if (output.isBlank()) {
            return ExportResult.Empty(
                "This app's log buffer holds no diagnostic lines yet. Run a batch, then export " +
                    "before the buffer rolls over."
            )
        }
        return write(
            context,
            DiagnosticsExportPlan.exportFileName("compressor-logcat", null, timestamp()),
            output
        )
    }

    /** Every `session.jsonl` the recorder has written, newest first. */
    private fun recordedSessionFiles(context: Context): List<File> {
        val root = File(context.filesDir, "diagnostics")
        if (!root.isDirectory) return emptyList()
        return root.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { File(it, "session.jsonl").takeIf { f -> f.isFile && f.length() > 0 } }
            .sortedByDescending { it.lastModified() }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    /**
     * Write to public Downloads. Uses MediaStore on Q+ (no storage permission needed, and the
     * only route that works under scoped storage); falls back to a direct file below Q, where the
     * legacy public directory is still writable.
     */
    private fun write(context: Context, displayName: String, content: String): ExportResult {
        val bytes = content.toByteArray().size.toLong()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeViaMediaStore(context, displayName, content, bytes)
        } else {
            writeViaLegacyFile(displayName, content, bytes)
        }
    }

    private fun writeViaMediaStore(
        context: Context,
        displayName: String,
        content: String,
        bytes: Long
    ): ExportResult {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + DiagnosticsExportPlan.EXPORT_SUBDIRECTORY
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = runCatching { context.contentResolver.insert(collection, values) }.getOrNull()
            ?: return ExportResult.Failed("Downloads is not writable on this device profile.")
        val wrote = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                ?: error("no output stream")
        }
        // Clear IS_PENDING regardless, so a partial write never leaves an invisible orphan row.
        runCatching {
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        }
        return wrote.fold(
            onSuccess = { ExportResult.Written(displayName, bytes, uri) },
            onFailure = {
                runCatching { context.contentResolver.delete(uri, null, null) }
                Log.w(TAG, "diagnostics export write failed", it)
                ExportResult.Failed("Could not write to Downloads: ${it.message ?: "unknown error"}")
            }
        )
    }

    private fun writeViaLegacyFile(displayName: String, content: String, bytes: Long): ExportResult {
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DiagnosticsExportPlan.EXPORT_SUBDIRECTORY
        )
        return runCatching {
            dir.mkdirs()
            File(dir, displayName).also { it.writeText(content) }
        }.fold(
            onSuccess = { ExportResult.Written(displayName, bytes, Uri.fromFile(it)) },
            onFailure = {
                Log.w(TAG, "legacy diagnostics export failed", it)
                ExportResult.Failed("Could not write to Downloads: ${it.message ?: "unknown error"}")
            }
        )
    }

    /** What Termux integration is possible right now on this device. */
    fun termuxAvailability(context: Context): DiagnosticsExportPlan.TermuxAvailability {
        val installed = runCatching {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        }.isSuccess
        val granted = context.checkSelfPermission(TERMUX_RUN_COMMAND_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        return DiagnosticsExportPlan.termuxAvailability(installed, granted)
    }

    /** The live-capture command for this app, for Termux or the clipboard. */
    fun termuxScript(context: Context): String =
        DiagnosticsExportPlan.termuxLogcatScript(context.packageName)

    /**
     * Ask Termux to start a live logcat capture.
     *
     * Reports only what can be verified. Termux also requires `allow-external-apps=true` in
     * `~/.termux/termux.properties`, which no API exposes; when it is off Termux discards the
     * command silently. So a successful dispatch is reported as *sent*, never as *started*, and
     * the caller is expected to keep the copy-to-clipboard route available in every case.
     */
    fun startTermuxCapture(context: Context): ExportResult {
        val availability = termuxAvailability(context)
        if (availability != DiagnosticsExportPlan.TermuxAvailability.CAN_RUN_COMMAND) {
            return ExportResult.Failed(
                when (availability) {
                    DiagnosticsExportPlan.TermuxAvailability.NOT_INSTALLED ->
                        "Termux is not installed."
                    else ->
                        "Termux has not granted RUN_COMMAND to Compressor. Use “Copy command” instead."
                }
            )
        }
        val intent = Intent(TERMUX_RUN_COMMAND_ACTION).apply {
            setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", termuxScript(context)))
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        }
        return runCatching { context.startService(intent) }.fold(
            onSuccess = { component ->
                if (component == null) {
                    ExportResult.Failed("Termux did not accept the command. Use “Copy command” instead.")
                } else {
                    ExportResult.Empty("Command sent to Termux — switch to Termux to confirm it started.")
                }
            },
            onFailure = {
                Log.w(TAG, "Termux dispatch failed", it)
                ExportResult.Failed("Termux refused the command: ${it.message ?: "unknown error"}")
            }
        )
    }

    /** Launch intent for Termux, or null when it is not installed. */
    fun termuxLaunchIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
}
