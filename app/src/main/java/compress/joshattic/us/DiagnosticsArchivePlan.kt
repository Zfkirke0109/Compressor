package compress.joshattic.us

/**
 * Pure decisions behind the one-ZIP diagnostics export: which runs a scope covers, what the
 * archive is called, where each file goes inside it, and what the manifest says.
 *
 * Why one archive. Every debugging round so far has meant two separate multi-megabyte text
 * files, one a concatenation of every session ever recorded and the other a concatenation of
 * every decision log plus the device log buffer. The reader then had to split them apart again
 * by batch id and guess which crash report belonged to which run. The archive keeps the raw
 * files as they are on the device, one directory per run, with a manifest that says what is
 * inside and which build produced it. Plain `java.util.zip`: no native library, no new
 * dependency.
 */
object DiagnosticsArchivePlan {

    enum class Scope(val label: String, val suffix: String) {
        /** The newest run only, with the crash reports written since it started. */
        CURRENT_RUN("Current run", ""),

        /** The run before the newest, same rule. */
        PREVIOUS_RUN("Previous run", ""),

        /** Every retained run, plus every crash report. */
        ALL_RUNS("All retained runs", "AllRuns"),

        /** Everything: all runs, all crash reports, the device log and the learned profiles. */
        EVERYTHING("Everything", "Everything");
    }

    /** A run directory on the device. [startedAtMs] comes from the batch id ("batch_<epochMs>"). */
    data class Run(val batchId: String, val startedAtMs: Long)

    /** Newest run first; batch ids that carry no epoch sort last. */
    fun sortedNewestFirst(batchIds: Collection<String>): List<Run> =
        batchIds.map { Run(it, startedAtOf(it)) }.sortedByDescending { it.startedAtMs }

    /**
     * Directory names under `diagnostics/` that are runs: batches, and the scorer self-checks
     * (`selfcheck_<epochMs>`, DiagLog only). The b165 export carried no self-check because only
     * `batch_` names were collected.
     */
    val RUN_PREFIXES: List<String> = listOf("batch_", "selfcheck_")

    fun isRunDirectoryName(name: String): Boolean = RUN_PREFIXES.any { name.startsWith(it) }

    fun startedAtOf(batchId: String): Long =
        RUN_PREFIXES.firstNotNullOfOrNull { prefix ->
            if (batchId.startsWith(prefix)) batchId.removePrefix(prefix).toLongOrNull() else null
        } ?: -1L

    fun isBatch(run: Run): Boolean = run.batchId.startsWith("batch_")

    /** The batch runs only, newest first: what "current" and "previous" count. */
    fun batchesNewestFirst(newestFirst: List<Run>): List<Run> = newestFirst.filter(::isBatch)

    /**
     * Which runs the scope covers. A single-run scope picks a BATCH (the newest, or the one
     * before it) and adds the scorer self-checks that ran between the batch before it and the
     * batch after it, so a self-check run just before or just after a batch travels with it.
     * The batch comes first; the file name and the crash-report window are taken from it.
     * Empty when the scope has nothing to say.
     */
    fun runsFor(scope: Scope, newestFirst: List<Run>): List<Run> {
        if (scope == Scope.ALL_RUNS || scope == Scope.EVERYTHING) return newestFirst
        val batches = batchesNewestFirst(newestFirst)
        val index = if (scope == Scope.CURRENT_RUN) 0 else 1
        val batch = batches.getOrNull(index) ?: return emptyList()
        val after = batches.getOrNull(index - 1)?.startedAtMs ?: Long.MAX_VALUE
        val before = batches.getOrNull(index + 1)?.startedAtMs ?: Long.MIN_VALUE
        val selfChecks = newestFirst.filter { !isBatch(it) && it.startedAtMs > before && it.startedAtMs < after }
        return listOf(batch) + selfChecks
    }

    /**
     * Crash reports a scope includes. A single-run scope keeps the reports written from that run's
     * start until the next run started (or now); the rest keep them all. A crash report's name
     * carries its epoch (CrashReportPlan.fileName).
     */
    fun crashReportsFor(
        scope: Scope,
        runs: List<Run>,
        newestFirst: List<Run>,
        crashReportNames: Collection<String>
    ): List<String> {
        val reports = crashReportNames.filter(CrashReportPlan::isReportName).sorted()
        if (scope == Scope.ALL_RUNS || scope == Scope.EVERYTHING) return reports
        val run = runs.firstOrNull(::isBatch) ?: return emptyList()
        val next = batchesNewestFirst(newestFirst).lastOrNull { it.startedAtMs > run.startedAtMs }?.startedAtMs ?: Long.MAX_VALUE
        return reports.filter { name ->
            val at = crashEpochOf(name)
            at >= run.startedAtMs && at < next
        }
    }

    fun crashEpochOf(reportName: String): Long =
        reportName.removePrefix("crash-").removeSuffix(".log").toLongOrNull() ?: -1L

    /**
     * `Compressor-v<version>-<timestamp>-<scope>.zip`, where the scope part is the batch id for a
     * single-run scope. Examples:
     *   Compressor-v1.6.163-20260924-132315-AllRuns.zip
     *   Compressor-v1.6.163-20260924-132315-batch_1790270611232.zip
     */
    fun fileName(versionName: String, timestamp: String, scope: Scope, runs: List<Run>): String {
        val scopePart = when (scope) {
            Scope.CURRENT_RUN, Scope.PREVIOUS_RUN -> runs.firstOrNull()?.batchId ?: "NoRun"
            else -> scope.suffix
        }
        return DiagnosticsExportPlan.sanitizeFileNamePart("Compressor-v$versionName-$timestamp-$scopePart") + ".zip"
    }

    /** Paths inside the archive. */
    fun runEntryPath(batchId: String, fileName: String): String = "runs/$batchId/$fileName"
    fun crashEntryPath(reportName: String): String = "crashes/$reportName"
    const val LOGCAT_ENTRY = "device/logcat.txt"
    const val LEARNED_PROFILES_ENTRY = "device/learned_profiles.json"
    const val MANIFEST_ENTRY = "manifest.json"

    /** Whether the scope carries the device log buffer and the learned profiles. */
    fun includesDeviceLog(scope: Scope): Boolean = true
    fun includesLearnedProfiles(scope: Scope): Boolean = scope == Scope.EVERYTHING

    /** One line of the manifest's inventory. */
    data class Entry(val path: String, val bytes: Long)

    /**
     * The manifest. Every value is a technical identifier or a count; no file names or paths
     * from the user's library appear anywhere in the archive (the session records already carry
     * only hashed job ids).
     */
    fun manifest(
        identity: Map<String, Any?>,
        exportedAt: String,
        scope: Scope,
        currentBatchId: String?,
        previousBatchId: String?,
        includedRuns: List<Run>,
        entries: List<Entry>
    ): String {
        val root = linkedMapOf<String, Any?>()
        root["manifestVersion"] = 1
        root["app"] = "Compressor"
        identity.forEach { (k, v) -> root[k] = v }
        root["exportedAt"] = exportedAt
        root["scope"] = scope.name
        root["scopeLabel"] = scope.label
        root["currentBatchId"] = currentBatchId
        root["previousBatchId"] = previousBatchId
        root["runs"] = includedRuns.map { linkedMapOf("batchId" to it.batchId, "startedAtMs" to it.startedAtMs) }
        root["files"] = entries.map { linkedMapOf("path" to it.path, "bytes" to it.bytes) }
        return JsonText.render(root)
    }
}
