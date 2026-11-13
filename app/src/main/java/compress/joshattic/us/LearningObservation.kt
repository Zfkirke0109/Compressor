package compress.joshattic.us

import compress.joshattic.us.quality.ScientificPolicy
import java.security.MessageDigest

/** One measurement, not an independent population sample. Repeats remain in the ledger. */
data class LearningObservation(
    val sourceId: String,
    val sourceIdBasis: String,
    val configId: String,
    val windowIds: List<String>,
    val stage: String,
    val kind: Kind,
    val appCommit: String,
    val encoder: String,
    val policyEpoch: String = ScientificPolicy.EPOCH,
    val model: String = ScientificPolicy.MODEL,
    val details: Map<String, Any?> = emptyMap()
) {
    enum class Kind { VISUAL_PASS, VISUAL_REJECT, SIZE, PIPELINE, COLOR, AUDIO, PARSER, INSUFFICIENT, HUMAN }
    val trains: Boolean get() = kind in setOf(Kind.VISUAL_PASS, Kind.VISUAL_REJECT, Kind.SIZE)
    val valid: Boolean get() = sourceId.matches(Regex("[0-9a-f]{64}")) && configId.isNotBlank() && encoder.isNotBlank() &&
        policyEpoch == ScientificPolicy.EPOCH && (kind == Kind.SIZE || windowIds.isNotEmpty())

    /** Build and outcome do not create independent evidence about the same operating point. */
    val id: String get() {
        val canonical = JsonText.render(listOf(sourceIdBasis, sourceId, configId, windowIds.sorted().distinct(),
            stage, if (kind == Kind.SIZE) "size" else "visual", encoder, policyEpoch, model))
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun fields(): Map<String, Any?> = linkedMapOf("sourceId" to sourceId, "sourceIdBasis" to sourceIdBasis,
        "configId" to configId, "windowIds" to windowIds, "stage" to stage, "kind" to kind.name,
        "appCommit" to appCommit, "encoder" to encoder, "policyEpoch" to policyEpoch, "model" to model,
        "details" to details)
}
