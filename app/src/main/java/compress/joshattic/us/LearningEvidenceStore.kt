package compress.joshattic.us

import java.io.File

/**
 * Evidence-aware adapter. The existing aggregate profile store is retained.
 *
 * Each aggregate update must be backed by one valid, trainable [LearningObservation], and the
 * observation's id (source, configuration, windows, stage, encoder, policy) may move the aggregate
 * once only. The id is reserved in an append-only JSONL journal before the aggregate is written,
 * and the journal is read back on construction, so a repeat of the same measurement (a rerun, a
 * retry, a new build, or a conflicting label for the same operating point) is kept out of the
 * aggregate across process restarts as well as within one. Writes without an observation, with an
 * invalid one (unbound source id, other policy epoch, no windows) or with one that does not train
 * (pipeline, colour, audio, parser, insufficient) never change the aggregate.
 */
class LearningEvidenceStore(
    private val delegate: SmartPerceptualProfileEngine.ProfileStore,
    private val journal: File,
    private val onEvent: (Map<String, Any?>) -> Unit = {}
) : SmartPerceptualProfileEngine.ProfileStore by delegate {

    private val lock = Any()
    private val reserved: MutableSet<String> by lazy { readJournalIds() }

    override fun writeObservation(key: String, value: String, observation: LearningObservation?): Boolean {
        val refusal = when {
            observation == null -> "no_observation"
            !observation.valid -> "invalid_observation"
            !observation.trains -> "non_training_kind"
            else -> null
        }
        if (refusal != null) {
            emit("refused", key, observation, refusal)
            return false
        }
        val id = observation!!.id
        synchronized(lock) {
            if (id in reserved) {
                emit("duplicate", key, observation, "observation_already_applied")
                return false
            }
            // Reserve first: a crash between the two writes leaves the aggregate one update short,
            // never one update counted twice.
            journal.parentFile?.mkdirs()
            journal.appendText(line(id, key, observation) + "\n")
            reserved += id
            delegate.write(key, value)
        }
        emit("applied", key, observation, null)
        return true
    }

    private fun line(id: String, key: String, observation: LearningObservation): String =
        JsonText.render(linkedMapOf<String, Any?>("id" to id, "key" to key) + observation.fields(), 0)
            .replace("\n", "")

    private fun readJournalIds(): MutableSet<String> {
        if (!journal.isFile) return mutableSetOf()
        val idPattern = Regex("^\\{\\s*\"id\":\\s*\"([0-9a-f]{64})\"")
        return journal.readLines().mapNotNullTo(mutableSetOf()) { idPattern.find(it)?.groupValues?.get(1) }
    }

    private fun emit(result: String, key: String, observation: LearningObservation?, reason: String?) {
        runCatching {
            onEvent(linkedMapOf("type" to "learning_observation", "result" to result, "key" to key,
                "observationId" to observation?.id, "kind" to observation?.kind?.name, "reason" to reason))
        }
    }
}
