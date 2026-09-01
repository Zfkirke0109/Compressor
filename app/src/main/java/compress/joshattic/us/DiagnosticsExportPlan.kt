package compress.joshattic.us

import java.util.Locale

/**
 * Pure decisions behind on-device diagnostics export: what to name a file, what to ask `logcat`
 * for, and what Termux can actually be asked to do.
 *
 * Getting captures off this device has been the slowest part of every debugging round, and the
 * reason is structural rather than incidental. `DiagnosticsRecorder` writes to the app's private
 * files directory, which `adb` cannot read across Android users — so for a Samsung Secure Folder
 * run (user 150) there is no adb path to the data at all. Logcat crosses that boundary, but the
 * device caps `logcat -G` at 5 MiB and a 219-job batch overruns the buffer: the capture uploaded
 * for batch_1786670702437 had already lost its `session_start` record, so neither the build nor
 * the learned-state fingerprint could be confirmed.
 *
 * Both problems disappear if the app exports its own diagnostics, because an app can always read
 * its own files and its own log entries. Nothing here needs adb, root, Shizuku, or a PC.
 *
 * No Android dependencies, so every rule below is unit-testable.
 */
object DiagnosticsExportPlan {

    /** Public destination for exports. Subdirectory of Downloads, not the media collections. */
    const val EXPORT_SUBDIRECTORY = "Compressor"

    /**
     * Tags carrying Compressor's own diagnostics. `CompressorDiag` is the structured JSONL stream;
     * the rest are the human-readable decision logs that never reach the JSONL — most importantly
     * `CompressorVerification`, which is the only place the per-check verification detail line is
     * written.
     */
    val DIAGNOSTIC_TAGS = listOf(
        "CompressorDiag",
        "CompressorVerification",
        "CompressorProbe",
        "CompressorLearning",
        "CompressorBatch",
        "CompressorEncoderPlan",
        "CompressorCodecCaps",
        // The pixel scorer's own tag. Omitting it silently cost a whole diagnosis: 13 of the 27
        // ladders in batch_1788254475481 ended in "not time-alignable", and PtsAligner had
        // written the reason for every one of them — under this tag, which the export did not
        // collect. The capture therefore could not say whether the probe pipeline had failed to
        // line the streams up or the encode really had dropped frames.
        "VmafPairScorer"
    )

    /**
     * Filename for an export. Timestamped so repeated exports never overwrite each other — an
     * earlier capture is evidence, and silently replacing it would destroy the ability to compare
     * two runs.
     *
     * [batchId] is included when known so a file can be matched to its run without opening it.
     * Any character outside `[A-Za-z0-9._-]` is replaced, because the value reaches MediaStore's
     * DISPLAY_NAME and a stray separator would be rejected or reinterpreted as a path.
     */
    fun exportFileName(prefix: String, batchId: String?, timestamp: String): String {
        val parts = listOfNotNull(prefix, batchId?.takeIf { it.isNotBlank() }, timestamp)
        return parts.joinToString("-") { sanitizeFileNamePart(it) } + ".txt"
    }

    /** Conservative filename sanitiser; collapses runs of illegal characters to a single "_". */
    fun sanitizeFileNamePart(value: String): String {
        val cleaned = StringBuilder()
        var lastWasUnderscore = false
        for (ch in value) {
            val ok = ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-'
            if (ok) {
                cleaned.append(ch)
                lastWasUnderscore = false
            } else if (!lastWasUnderscore) {
                cleaned.append('_')
                lastWasUnderscore = true
            }
        }
        return cleaned.toString().trim('_', '.', '-').ifEmpty { "export" }
    }

    /**
     * Argv for a one-shot dump of THIS process's diagnostic lines.
     *
     * `-d` dumps and exits rather than streaming, so the caller cannot hang waiting for EOF.
     *
     * Kept as the narrower fallback for [logcatDumpArgsAllProcesses], which explains why the
     * cross-process form is the one an export should normally ask for.
     */
    fun logcatDumpArgs(pid: Int, tags: List<String> = DIAGNOSTIC_TAGS): List<String> {
        require(pid > 0) { "pid must be positive, was $pid" }
        return dumpArgs(tags) { add("--pid=$pid") }
    }

    /**
     * Argv for a dump of this *app's* diagnostic lines, across every process it has run in.
     *
     * The `--pid` form above cannot see the run that mattered most. When a batch crashes the
     * process, the export is necessarily taken from a new process with a new pid, so filtering on
     * it hides exactly the records that would explain the crash — which is what happened to the
     * 2026-09-01 High Quality captures: they came back with zero job records and no session
     * summary, not because nothing was logged, but because everything logged had a dead pid.
     *
     * Dropping `--pid` does not widen what this app can read. Since Jelly Bean the log daemon
     * serves an unprivileged reader only entries from its own UID, so other apps' output stays
     * invisible whether or not we ask for a pid; `--pid` was narrowing our own history, not
     * anyone else's privacy. The tag filter still keeps the file to Compressor's own diagnostics.
     */
    fun logcatDumpArgsAllProcesses(tags: List<String> = DIAGNOSTIC_TAGS): List<String> = dumpArgs(tags)

    private fun dumpArgs(tags: List<String>, extra: MutableList<String>.() -> Unit = {}): List<String> {
        require(tags.isNotEmpty()) { "at least one tag is required; an empty list would silence everything" }
        return buildList {
            add("logcat")
            add("-d")
            extra()
            add("-v")
            add("threadtime")
            tags.forEach { add("-s"); add("$it:V") }
        }
    }

    /** Whether Termux can be driven directly, and if not, precisely why. */
    enum class TermuxAvailability {
        /** Termux is installed and has granted RUN_COMMAND. A command can be dispatched. */
        CAN_RUN_COMMAND,

        /** Installed, but RUN_COMMAND is not granted, so only a manual hand-off is possible. */
        INSTALLED_WITHOUT_PERMISSION,

        /** Not installed. */
        NOT_INSTALLED
    }

    /**
     * What Termux integration is possible right now.
     *
     * Deliberately never reports more capability than can be verified. Even at
     * [TermuxAvailability.CAN_RUN_COMMAND], Termux additionally requires `allow-external-apps=true`
     * in `~/.termux/termux.properties`, and a caller cannot observe that setting — a dispatched
     * command is simply ignored when it is off. So dispatch is offered as "sent", never as
     * "started", and the manual hand-off stays available in every state.
     */
    fun termuxAvailability(installed: Boolean, runCommandGranted: Boolean): TermuxAvailability = when {
        !installed -> TermuxAvailability.NOT_INSTALLED
        runCommandGranted -> TermuxAvailability.CAN_RUN_COMMAND
        else -> TermuxAvailability.INSTALLED_WITHOUT_PERMISSION
    }

    /**
     * The shell command that captures this app's diagnostics from a Termux session over adb.
     *
     * Kept for the cases in-app export cannot serve: watching a batch live, or capturing from a
     * device profile other than the one the app is running in. Emits a `logcat -c` first so the
     * capture starts from a known-empty buffer, which is what the 5 MiB cap makes necessary.
     */
    fun termuxLogcatScript(
        packageName: String,
        outputPath: String = "~/storage/downloads/compressor-logcat.txt",
        tags: List<String> = DIAGNOSTIC_TAGS
    ): String {
        val tagArgs = tags.joinToString(" ") { "$it:V" }
        return buildString {
            appendLine("# Live capture of $packageName diagnostics. Start this BEFORE the batch.")
            appendLine("adb logcat -c")
            appendLine("adb logcat -v threadtime -s $tagArgs | tee $outputPath")
        }.trimEnd()
    }

    /** Compact human-readable byte size for export confirmations. */
    fun describeSize(bytes: Long): String = when {
        bytes < 0 -> "unknown"
        bytes < 1_024 -> "$bytes B"
        bytes < 1_024 * 1_024 -> String.format(Locale.US, "%.1f KB", bytes / 1_024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1_024.0 * 1_024.0))
    }
}
