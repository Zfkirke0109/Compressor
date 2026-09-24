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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
 * by UID (all Compressor processes) and by tag, which is what the platform will grant without the
 * privileged READ_LOGS permission; without `--pid` we capture records from previous processes
 * including a batch that crashed, which was the missing context. Other apps' output stays invisible
 * regardless — since Jelly Bean the log daemon serves an unprivileged reader only its own UID's
 * entries. The tag filter keeps the file to Compressor's own diagnostics.
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
     * Dump this app's diagnostic log lines into `Downloads/Compressor/`.
     *
     * Complements the session export rather than duplicating it: the JSONL carries the structured
     * per-job records, while logcat additionally carries the human-readable decision lines that
     * are never written to it — including the `CompressorVerification` per-check detail line.
     */
    fun exportLogcat(context: Context): ExportResult {
        // The app's OWN decision logs come first, because they are the only copy that cannot be
        // evicted. batch_1788273016134 ran 07:30-08:0x and was exported at 08:08; the buffer held
        // nothing older than 08:07:57, so all 64 jobs survived in session.jsonl while every
        // decision line behind them was gone. Logcat is still appended -- it carries lines from
        // outside a batch, and from builds predating DiagLog -- but it is no longer the only copy.
        val decisions = recordedDecisionLogs(context)
        // Crash reports come before everything else. They are the one record a crash leaves that
        // neither the session JSONL nor the decision log can hold, since both stop at the moment
        // the process dies. See CrashRecorder.
        val crashes = CrashRecorder.recordedReports(context)
        // Ask for every process this app has run in, not just the live one: a batch that crashed
        // logged its records under a pid that no longer exists, and those are the records worth
        // exporting. Falls back to the pid-scoped form only if the broader dump comes back empty,
        // so this can never return less than it did before.
        val logcat = readLogcat(DiagnosticsExportPlan.logcatDumpArgsAllProcesses())
            .let { broad ->
                if (broad != null && broad.isNotBlank()) broad
                else readLogcat(DiagnosticsExportPlan.logcatDumpArgs(Process.myPid())) ?: broad
            }
        if (crashes.isEmpty() && decisions.isEmpty() && logcat == null) {
            return ExportResult.Failed("Could not read this app's log.")
        }
        val output = buildString {
            for (file in crashes) {
                val text = runCatching { file.readText() }.getOrNull() ?: continue
                if (text.isBlank()) continue
                append("===== crash: ${file.name} =====\n")
                append(text)
                if (!text.endsWith("\n")) append('\n')
            }
            for (file in decisions) {
                val text = runCatching { file.readText() }.getOrNull() ?: continue
                if (text.isBlank()) continue
                append("===== decisions: ${file.parentFile?.name} =====\n")
                append(text)
                if (!text.endsWith("\n")) append('\n')
            }
            if (!logcat.isNullOrBlank()) {
                append("===== logcat (device buffer, may be truncated) =====\n")
                append(logcat)
            }
        }
        if (output.isBlank()) {
            return ExportResult.Empty(
                "No diagnostic lines yet. Run a batch, then export."
            )
        }
        return write(
            context,
            DiagnosticsExportPlan.exportFileName("compressor-logcat", null, timestamp()),
            output
        )
    }

    /**
     * One ZIP for a scope (see [DiagnosticsArchivePlan]): the raw `session.jsonl` and
     * `decisions.log` of each included run under `runs/<batchId>/`, the crash and process-exit
     * reports under `crashes/`, the device log buffer under `device/`, and a `manifest.json` that
     * says which build and device produced it and what is inside. Built in the cache directory,
     * then streamed into Downloads, so a failed write never leaves a half archive there.
     */
    fun exportArchive(context: Context, scope: DiagnosticsArchivePlan.Scope): ExportResult {
        val runs = DiagnosticsArchivePlan.sortedNewestFirst(DiagnosticsRecorder.runDirectories(context).map { it.name })
        val included = DiagnosticsArchivePlan.runsFor(scope, runs)
        if (included.isEmpty() && scope != DiagnosticsArchivePlan.Scope.EVERYTHING) {
            return ExportResult.Empty(
                when (scope) {
                    DiagnosticsArchivePlan.Scope.PREVIOUS_RUN -> "There is no run before the current one yet."
                    else -> "No recorded runs yet — run a batch first."
                }
            )
        }
        val identity = DiagnosticsRecorder.identityMap(context)
        val versionName = identity["appVersionName"]?.toString() ?: "unknown"
        val stamp = timestamp()
        val displayName = DiagnosticsArchivePlan.fileName(versionName, stamp, scope, included)
        val crashDir = File(context.filesDir, "diagnostics/${CrashReportPlan.DIRECTORY}")
        val crashNames = crashDir.list()?.toList().orEmpty()
        val crashes = DiagnosticsArchivePlan.crashReportsFor(scope, included, runs, crashNames)
        val logcat = readLogcat(DiagnosticsExportPlan.logcatDumpArgsAllProcesses())
            ?.takeIf { it.isNotBlank() }
        val learned = if (DiagnosticsArchivePlan.includesLearnedProfiles(scope)) {
            runCatching {
                SmartPerceptualProfileEngine(
                    SmartPerceptualProfileEngine.SharedPreferencesProfileStore(context)
                ).snapshotLearnedState()
            }.getOrNull()
        } else null

        val staging = File(context.cacheDir, "diagnostics_export").apply { mkdirs() }
        val archive = File(staging, displayName)
        val entries = mutableListOf<DiagnosticsArchivePlan.Entry>()
        val built = runCatching {
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                fun add(path: String, file: File) {
                    if (!file.isFile || file.length() == 0L) return
                    zip.putNextEntry(ZipEntry(path).apply { time = file.lastModified() })
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    entries += DiagnosticsArchivePlan.Entry(path, file.length())
                }
                fun addText(path: String, text: String) {
                    val bytes = text.toByteArray()
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                    entries += DiagnosticsArchivePlan.Entry(path, bytes.size.toLong())
                }
                for (run in included) {
                    val dir = File(context.filesDir, "diagnostics/${run.batchId}")
                    add(DiagnosticsArchivePlan.runEntryPath(run.batchId, "session.jsonl"), File(dir, "session.jsonl"))
                    add(DiagnosticsArchivePlan.runEntryPath(run.batchId, "decisions.log"), File(dir, "decisions.log"))
                }
                for (name in crashes) add(DiagnosticsArchivePlan.crashEntryPath(name), File(crashDir, name))
                if (logcat != null) addText(DiagnosticsArchivePlan.LOGCAT_ENTRY, logcat)
                if (learned != null) {
                    addText(DiagnosticsArchivePlan.LEARNED_PROFILES_ENTRY, JsonText.render(learned))
                }
                // The manifest goes last so it can list everything above it, sizes included.
                addText(
                    DiagnosticsArchivePlan.MANIFEST_ENTRY,
                    DiagnosticsArchivePlan.manifest(
                        identity = identity,
                        exportedAt = stamp,
                        scope = scope,
                        currentBatchId = runs.firstOrNull()?.batchId,
                        previousBatchId = runs.getOrNull(1)?.batchId,
                        includedRuns = included,
                        entries = entries.toList()
                    )
                )
            }
        }
        if (built.isFailure) {
            runCatching { archive.delete() }
            Log.w(TAG, "diagnostics archive build failed", built.exceptionOrNull())
            return ExportResult.Failed("Could not build the archive: ${built.exceptionOrNull()?.message ?: "unknown error"}")
        }
        return try {
            writeFile(context, displayName, "application/zip", archive)
        } finally {
            runCatching { archive.delete() }
        }
    }

    /** Every batch's durable decision log, newest first. See [DiagLog]. */
    private fun recordedDecisionLogs(context: Context): List<File> {
        val root = File(context.filesDir, "diagnostics")
        if (!root.isDirectory) return emptyList()
        return root.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { File(it, "decisions.log").takeIf { f -> f.isFile && f.length() > 0 } }
            .sortedByDescending { it.lastModified() }
    }

    /** Runs one `logcat` dump; null means the dump itself failed, "" means it produced nothing. */
    private fun readLogcat(args: List<String>): String? = runCatching {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        text
    }.getOrElse {
        Log.w(TAG, "logcat dump failed for $args", it)
        null
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

    /** Streams [source] into Downloads under [displayName] with the given MIME type. */
    private fun writeFile(context: Context, displayName: String, mimeType: String, source: File): ExportResult {
        val bytes = source.length()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
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
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { it.copyTo(out) }
                } ?: error("no output stream")
            }
            runCatching {
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
            return wrote.fold(
                onSuccess = { ExportResult.Written(displayName, bytes, uri) },
                onFailure = {
                    runCatching { context.contentResolver.delete(uri, null, null) }
                    Log.w(TAG, "diagnostics archive write failed", it)
                    ExportResult.Failed("Could not write to Downloads: ${it.message ?: "unknown error"}")
                }
            )
        }
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DiagnosticsExportPlan.EXPORT_SUBDIRECTORY
        )
        return runCatching {
            dir.mkdirs()
            File(dir, displayName).also { target -> source.inputStream().use { it.copyTo(target.outputStream()) } }
        }.fold(
            onSuccess = { ExportResult.Written(displayName, bytes, Uri.fromFile(it)) },
            onFailure = {
                Log.w(TAG, "legacy diagnostics archive export failed", it)
                ExportResult.Failed("Could not write to Downloads: ${it.message ?: "unknown error"}")
            }
        )
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
