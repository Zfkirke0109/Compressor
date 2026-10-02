package compress.joshattic.us

import java.io.File

/** Evidence-aware adapter. The existing aggregate profile store is retained. */
class LearningEvidenceStore(
    private val delegate: SmartPerceptualProfileEngine.ProfileStore,
    private val journal: File,
    private val onEvent: (Map<String, Any?>) -> Unit = {}
) : SmartPerceptualProfileEngine.ProfileStore by delegate {
    // Introduced separately from its production wiring so regression tests demonstrate duplicate
    // observations in the old unconditional-write behavior before the reservation fix.
    override fun writeObservation(key: String, value: String, observation: LearningObservation?): Boolean {
        delegate.write(key, value)
        return true
    }
}
