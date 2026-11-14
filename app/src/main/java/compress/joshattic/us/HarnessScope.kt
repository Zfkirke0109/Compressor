package compress.joshattic.us

import java.util.concurrent.ConcurrentHashMap

/**
 * What a device-harness batch runs under. The app's own view model has none (null): its batches
 * learn into the user's profile and its start scans the user's rollback copies. A batch an
 * instrumented test starts with a scope instead:
 *
 *  - learns into [learningStore], which reads the user's learned state and keeps every write to
 *    itself, and journals into its own file, so a test's encodes never change what the user's next
 *    batch is planned from, and the test still starts from the state a user's batch would;
 *  - does not scan or settle the user's rollback copies when it is created;
 *  - says so in its record (`testHarnessRun` in the batch settings), as a candidate veto does.
 *
 * [runLabel] names the harness run: letters, digits, `-` and `_` only, because it becomes part of
 * a file name.
 */
internal class HarnessScope(val runLabel: String, val learningStore: SmartPerceptualProfileEngine.ProfileStore) {
    init {
        require(runLabel.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "a harness run label is letters, digits, - and _ only" }
    }
}

/**
 * Reads [base]; keeps every write to itself. [snapshot] is the user's state with this store's
 * writes over it; [clear] forgets only this store's writes. Nothing reaches [base].
 */
internal class OverlayProfileStore(private val base: SmartPerceptualProfileEngine.ProfileStore) : SmartPerceptualProfileEngine.ProfileStore {
    private val written = ConcurrentHashMap<String, String>()

    /** What this store holds that the base does not: every write made through it. */
    val writes: Map<String, String> get() = written.toMap()

    override fun read(key: String): String? = written[key] ?: base.read(key)
    override fun write(key: String, value: String) { written[key] = value }
    override fun writeDurably(key: String, value: String): Boolean { written[key] = value; return true }
    override fun writeObservation(key: String, value: String, observation: LearningObservation?): Boolean { written[key] = value; return true }
    override fun snapshot(): Map<String, String> = base.snapshot() + written
    override fun clear() = written.clear()
}
