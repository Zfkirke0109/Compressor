package compress.joshattic.us

import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Evidence-aware adapter. The existing aggregate profile store is retained. */
class LearningEvidenceStore(
    private val delegate: SmartPerceptualProfileEngine.ProfileStore,
    private val journal: File,
    private val onEvent: (Map<String, Any?>) -> Unit = {}
) : SmartPerceptualProfileEngine.ProfileStore by delegate {
    private val seen = mutableSetOf<String>()
    private var writable = true
    private val idPattern = Regex("\\\"evidenceId\\\": \\\"([0-9a-f]{64})\\\"")
    init {
        runCatching {
            if (journal.exists()) {
                require(journal.length() <= MAX_BYTES) { "learning evidence journal exceeds bound" }
                journal.bufferedReader().useLines { lines -> lines.forEach { line ->
                    require(line.endsWith("}")) { "incomplete ledger tail; aggregate learning paused" }
                    if (line.contains("\"ledgerEvent\": \"RESERVED\"")) {
                        seen += checkNotNull(idPattern.find(line)?.groupValues?.get(1))
                    }
                    if (line.contains("\"ledgerEvent\": \"RESET\"")) seen.clear()
                } }
            }
        }.onFailure { writable = false }
    }

    /**
     * Reserve durably BEFORE changing the aggregate: a crash cannot double-train on replay.
     * RESERVED without APPLIED is explicitly uncertain, never a completed observation. The
     * before/after snapshots allow offline reconstruction and targeted recovery; existing v4
     * profiles are retained, with their legacy aggregate provenance rather than invented labels.
     */
    @Synchronized
    override fun writeObservation(key: String, value: String, observation: LearningObservation?): Boolean {
        val fields = linkedMapOf<String, Any?>("ledgerVersion" to 1,
            "eventId" to UUID.randomUUID().toString(), "timestampMs" to System.currentTimeMillis(),
            "evidenceId" to observation?.id, "profileKey" to key,
            "observation" to observation?.fields(), "before" to delegate.read(key), "proposedAfter" to value)
        if (observation == null || !observation.valid || !observation.trains) {
            append(fields + mapOf("ledgerEvent" to "IGNORED", "effectApplied" to false, "reason" to "unbound-incompatible-or-nonvisual-nonsize"))
            return false
        }
        if (observation.id in seen) {
            append(fields + mapOf("ledgerEvent" to "DUPLICATE", "effectApplied" to false))
            return false
        }
        if (!append(fields + mapOf("ledgerEvent" to "RESERVED", "effectApplied" to null))) return false
        seen += observation.id
        delegate.write(key, value)
        append(fields + mapOf("eventId" to UUID.randomUUID().toString(), "ledgerEvent" to "APPLIED", "effectApplied" to true, "after" to value))
        return true
    }

    override fun clear() {
        // The explicit reset operation retains its audit history; ordinary upgrades never call it.
        if (append(mapOf("ledgerVersion" to 1, "ledgerEvent" to "RESET", "timestampMs" to System.currentTimeMillis()))) {
            delegate.clear(); seen.clear()
        }
    }

    private fun append(fields: Map<String, Any?>): Boolean {
        val ok = writable && runCatching {
            val bytes = (JsonText.render(fields).replace("\n", "") + "\n").toByteArray()
            check(journal.length() + bytes.size <= MAX_BYTES) { "learning evidence journal full" }
            journal.parentFile?.mkdirs()
            FileOutputStream(journal, true).use { out -> out.write(bytes); out.fd.sync() }
            true
        }.getOrDefault(false)
        if (!ok) writable = false
        runCatching { onEvent(fields + mapOf("durablyJournaled" to ok)) }
        return ok
    }

    companion object {
        const val MAX_BYTES = 32L * 1024 * 1024
    }
}
