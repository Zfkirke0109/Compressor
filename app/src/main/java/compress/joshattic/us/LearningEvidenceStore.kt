package compress.joshattic.us

import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.UUID

/** Evidence-aware adapter. The existing aggregate profile store is retained. */
class LearningEvidenceStore(
    private val delegate: SmartPerceptualProfileEngine.ProfileStore,
    journal: File,
    // The journal's size bound. Only tests pass another value, to fill one without writing 32 MiB.
    private val maxBytes: Long = MAX_BYTES,
    private val onEvent: (Map<String, Any?>) -> Unit = {}
) : SmartPerceptualProfileEngine.ProfileStore by delegate {
    private val state = journalState(journal)

    /**
     * Reserve durably BEFORE changing the aggregate: a crash cannot double-train on replay.
     * RESERVED without APPLIED is explicitly uncertain, never a completed observation. The
     * before/after snapshots allow offline reconstruction and targeted recovery; existing v4
     * profiles are retained, with their legacy aggregate provenance rather than invented labels.
     *
     * APPLIED is recorded only once the aggregate write is itself durable. When the store cannot
     * confirm that, the record says APPLY_UNCONFIRMED and learning stops for this process: the
     * ledger never claims an update a restart could lose.
     *
     * Until its APPLIED is recorded a reservation is open, and a rotation carries every record
     * written for it as it was written (see [rotate]).
     */
    override fun writeObservation(key: String, value: String, observation: LearningObservation?): Boolean = synchronized(state) {
        val fields = linkedMapOf<String, Any?>("ledgerVersion" to 1,
            "eventId" to UUID.randomUUID().toString(), "timestampMs" to System.currentTimeMillis(),
            "evidenceId" to observation?.id, "profileKey" to key,
            "observation" to observation?.fields(), "before" to delegate.read(key), "proposedAfter" to value)
        if (observation == null || !observation.valid || !observation.trains) {
            append(fields + mapOf("ledgerEvent" to "IGNORED", "effectApplied" to false, "reason" to "unbound-incompatible-or-nonvisual-nonsize"))
            return@synchronized false
        }
        // A migrated S23 ledger must not suppress an S26 measurement merely because both
        // platforms call their encoder c2.qti.hevc.encoder. The persisted profile includes
        // manufacturer/model/SDK and configuration; preserve dedup within that profile.
        val scopedId = scopedId(JsonText.render(key), observation.id)
        if (scopedId in state.seen) {
            append(fields + mapOf("ledgerEvent" to "DUPLICATE", "effectApplied" to false))
            return@synchronized false
        }
        val reservation = append(fields + mapOf("ledgerEvent" to "RESERVED", "effectApplied" to null)) ?: return@synchronized false
        state.seen += scopedId
        state.open[scopedId] = mutableListOf(reservation)
        val durable = runCatching { delegate.writeDurably(key, value) }.getOrDefault(false)
        if (!durable) {
            // Still open afterwards: no effect is recorded, so the reservation stays uncertain.
            append(fields + mapOf("eventId" to UUID.randomUUID().toString(), "ledgerEvent" to "APPLY_UNCONFIRMED",
                "effectApplied" to null, "reason" to "the aggregate write was not confirmed on durable storage"))
                ?.let { state.open[scopedId]?.add(it) }
            state.writable = false
            // The caller is told what this process now reads back, which is what it will act on.
            return@synchronized runCatching { delegate.read(key) == value }.getOrDefault(false)
        }
        append(fields + mapOf("eventId" to UUID.randomUUID().toString(), "ledgerEvent" to "APPLIED", "effectApplied" to true, "after" to value))
            ?.let { state.open.remove(scopedId) }
        true
    }

    /**
     * The explicit reset. It is also the way out of every state in which the journal can no
     * longer be appended to: full beyond what a rotation can free, cut off mid-line by a killed
     * write, or left by a reset that never finished. A reset is the one operation that needs
     * nothing the journal holds, so such a journal is set aside as the archive and a new one is
     * started with this reset's own record. If that cannot be written either, nothing has
     * changed and the reset is refused.
     */
    override fun clear() = synchronized(state) {
        // The explicit reset operation retains its audit history; ordinary upgrades never call it.
        val started = mapOf("ledgerVersion" to 1, "ledgerEvent" to "RESET_STARTED", "timestampMs" to System.currentTimeMillis())
        check(append(started) != null || restartJournal(started)) {
            "Learning history could not record the reset; profiles were kept unchanged."
        }
        try {
            delegate.clear()
            check(append(mapOf("ledgerVersion" to 1, "ledgerEvent" to "RESET", "timestampMs" to System.currentTimeMillis())) != null) {
                "Profile reset completion could not be recorded; learning is paused."
            }
        } catch (e: Exception) {
            state.writable = false
            throw e
        }
        state.seen.clear()
        state.open.clear()
    }

    private fun line(fields: Map<String, Any?>): ByteArray = (JsonText.render(fields).replace("\n", "") + "\n").toByteArray()

    /** Appends [fields] as one line and returns the bytes written, or null when nothing was. */
    private fun append(fields: Map<String, Any?>): ByteArray? {
        val journal = state.journal
        val written = if (!state.writable) null else runCatching {
            val bytes = line(fields)
            if (journal.length() + bytes.size > maxBytes) rotate(bytes.size)
            journal.parentFile?.mkdirs()
            FileOutputStream(journal, true).use { out -> out.write(bytes); out.fd.sync() }
            bytes
        }.getOrNull()
        if (written == null) state.writable = false
        runCatching { onEvent(fields + mapOf("durablyJournaled" to (written != null))) }
        return written
    }

    /**
     * Make room in a full journal without forgetting what has already trained. The full file is
     * kept as the one archive, and the journal starts again with the reservations, so a repeated
     * measurement is refused after the rotation and after a restart exactly as before it. The
     * aggregate is not touched: this is not a reset.
     *
     * A reservation whose effect is recorded is carried as one short line. One that is still open
     * is carried as every record written for it, exactly as written. The journal can fill between
     * an observation's reservation and its effect; the effect is then recorded after the rotation
     * and must still be replayed against its reservation
     * (scripts/diagnostics/calibration_evidence.py). And a reservation that never had an effect
     * must still read as uncertain. A short line stands for an effect recorded before the
     * rotation: with one in its place the replay rejected the effect that followed, and took a
     * reservation without an effect for a completed one. The ROTATED record counts both:
     * carriedReservations is all of them, openReservations those carried whole.
     *
     * The new file is complete and synced before it replaces the old one in a single rename, so
     * there is a journal at every moment. Throws, leaving the journal as it was, when the
     * reservations alone would not fit: learning then pauses, as it did for any full journal.
     */
    private fun rotate(needed: Int) {
        val journal = state.journal
        val fresh = File(journal.parentFile, journal.name + ROTATING_SUFFIX)
        try {
            FileOutputStream(fresh, false).use { out ->
                out.write(line(mapOf("ledgerVersion" to 1, "ledgerEvent" to "ROTATED", "timestampMs" to System.currentTimeMillis(),
                    "carriedReservations" to state.seen.size, "openReservations" to state.open.size, "archivedBytes" to journal.length())))
                for (id in state.seen) if (id !in state.open) out.write(carried(id))
                for (records in state.open.values) records.forEach { out.write(it) }
                out.fd.sync()
            }
            check(fresh.length() + needed <= maxBytes) { "learning evidence journal full of live reservations" }
            journal.copyTo(File(journal.parentFile, journal.name + ARCHIVE_SUFFIX), overwrite = true)
            check(fresh.renameTo(journal)) { "learning evidence journal could not be rotated" }
        } finally {
            fresh.delete()
        }
    }

    /** Start a new journal whose first record is [first], keeping the old one as the archive. */
    private fun restartJournal(first: Map<String, Any?>): Boolean {
        val journal = state.journal
        val fresh = File(journal.parentFile, journal.name + ROTATING_SUFFIX)
        val ok = runCatching {
            journal.parentFile?.mkdirs()
            FileOutputStream(fresh, false).use { out ->
                out.write(line(first + mapOf("replacedJournalBytes" to journal.length())))
                out.fd.sync()
            }
            if (journal.isFile) journal.copyTo(File(journal.parentFile, journal.name + ARCHIVE_SUFFIX), overwrite = true)
            check(fresh.renameTo(journal)) { "learning evidence journal could not be replaced" }
            true
        }.getOrDefault(false)
        fresh.delete()
        if (ok) state.writable = true
        runCatching { onEvent(first + mapOf("durablyJournaled" to ok, "journalRestarted" to ok)) }
        return ok
    }

    private class JournalState(val journal: File) {
        /** Every reservation since the last reset: a measurement in here never trains again. */
        val seen = mutableSetOf<String>()

        /** The reservations with no APPLIED recorded, each with every record written for it, in order. */
        val open = linkedMapOf<String, MutableList<ByteArray>>()
        var writable = true

        init {
            // A rotation that never finished: the journal it was to replace is still the journal.
            runCatching { File(journal.parentFile, journal.name + ROTATING_SUFFIX).delete() }
            runCatching {
                var resetPending = false
                if (journal.exists()) {
                    require(journal.length() <= MAX_BYTES) { "learning evidence journal exceeds bound" }
                    journal.bufferedReader().useLines { lines -> lines.forEach { line ->
                        require(line.endsWith("}")) { "incomplete ledger tail; aggregate learning paused" }
                        if (line.contains("\"ledgerEvent\": \"RESERVED\"") || line.contains("\"ledgerEvent\": \"CARRIED\"")) {
                            val id = checkNotNull(idPattern.find(line)?.groupValues?.get(1))
                            val profileJson = checkNotNull(profilePattern.find(line)?.groupValues?.get(1))
                            val scope = scopedId(profileJson, id)
                            seen += scope
                            // A CARRIED line stands for a reservation whose effect was recorded.
                            if (line.contains("\"ledgerEvent\": \"RESERVED\"")) open[scope] = mutableListOf(record(line))
                        }
                        if (line.contains("\"ledgerEvent\": \"APPLY_UNCONFIRMED\"")) scopeOf(line)?.let { open[it]?.add(record(line)) }
                        if (line.contains("\"ledgerEvent\": \"APPLIED\"")) scopeOf(line)?.let { open.remove(it) }
                        if (line.contains("\"ledgerEvent\": \"RESET_STARTED\"")) resetPending = true
                        if (line.contains("\"ledgerEvent\": \"RESET\"")) {
                            seen.clear()
                            open.clear()
                            resetPending = false
                        }
                    } }
                }
                check(!resetPending) { "incomplete profile reset; aggregate learning paused" }
            }.onFailure { writable = false }
        }
    }

    companion object {
        const val MAX_BYTES = 32L * 1024 * 1024
        /** The previous full journal, or the one an explicit reset set aside. One is kept. */
        const val ARCHIVE_SUFFIX = ".archive"
        private const val ROTATING_SUFFIX = ".rotating"
        private val journals = mutableMapOf<File, WeakReference<JournalState>>()
        private val idPattern = Regex("\\\"evidenceId\\\": \\\"([0-9a-f]{64})\\\"")
        private val profilePattern = Regex(""""profileKey": ("(?:\\.|[^"\\])*")""")

        private fun scopedId(profileJson: String, evidenceId: String): String = "$profileJson\u0000$evidenceId"

        /** The reservation a record of [line] belongs to, or null when the line names none. */
        private fun scopeOf(line: String): String? {
            val id = idPattern.find(line)?.groupValues?.get(1) ?: return null
            val profileJson = profilePattern.find(line)?.groupValues?.get(1) ?: return null
            return scopedId(profileJson, id)
        }

        /** [line], read back from the journal, as the bytes it was written as. */
        private fun record(line: String): ByteArray = (line + "\n").toByteArray()

        /** A reservation carried into a rotated journal, in the shape the loader reads one in. */
        private fun carried(scopedId: String): ByteArray {
            val profileJson = scopedId.substringBeforeLast('\u0000')
            val evidenceId = scopedId.substringAfterLast('\u0000')
            return "{\"ledgerVersion\": 1, \"ledgerEvent\": \"CARRIED\", \"evidenceId\": \"$evidenceId\", \"profileKey\": $profileJson}\n".toByteArray()
        }

        private fun journalState(file: File): JournalState {
            val path = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile.normalize() }
            return synchronized(journals) {
                // Keep the same reservation set for every live adapter. Reload durable evidence
                // when there is no live adapter, as on a process restart.
                journals.entries.removeAll { it.value.get() == null }
                journals[path]?.get() ?: JournalState(path).also { journals[path] = WeakReference(it) }
            }
        }
    }
}
