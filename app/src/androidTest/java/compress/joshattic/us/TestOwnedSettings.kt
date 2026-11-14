package compress.joshattic.us

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * The user's settings a device test changes, put back even when the test's process dies before
 * its @After runs. An ADB drop ends `am instrument`, and the test's process with it; so does a
 * crash.
 *
 * Before a test first changes a switch, the user's value is written to
 * `files/pllab/test-owned-settings.json` and synced to disk, and only then is the switch changed.
 * A later change, in the same run or in a run after a death, keeps that first record, so the value
 * put back is always the user's and never a test's. [restore] puts every recorded value back,
 * writes it to disk synchronously, reads it back, and only then deletes the record. A test that
 * changes a switch calls [restore] in @Before (for a run that died) and in @After; the safe-copy
 * harness and run-device-checks.sh also run [TestOwnedSettingsDeviceTest] in an instrumentation
 * run of its own after such a test, so that it runs whatever became of the test's process.
 *
 * The safer-rung retry switch is the only setting any device test changes. A record naming
 * anything else is refused and kept, never deleted.
 */
internal object TestOwnedSettings {

    const val SAFER_RUNG_RETRY = "safer_rung_retry"

    // EncoderExperiments' store. [setSaferRungRetry] checks it against the app's own reading.
    private const val PREFS = "encoder_experiments"
    private const val RECORD = "pllab/test-owned-settings.json"

    fun record(context: Context): File = File(context.filesDir, RECORD)

    /** Records the user's value durably (once), then sets the switch to [enabled]. */
    fun setSaferRungRetry(context: Context, enabled: Boolean) {
        val file = record(context)
        val saved = read(file)
        if (!saved.has(SAFER_RUNG_RETRY)) {
            saved.put(SAFER_RUNG_RETRY, EncoderExperiments.isSaferRungRetryEnabled(context))
            writeDurably(file, saved)
        }
        check(EncoderExperiments.setSaferRungRetryEnabled(context, enabled)) {
            "could not set the safer-rung retry switch: a batch holds the settings"
        }
        flush(context)
        check(prefs(context).getBoolean(SAFER_RUNG_RETRY, !enabled) == enabled) {
            "the safer-rung retry switch is not stored where this helper reads it; nothing could be put back"
        }
    }

    /**
     * Puts back every value a test recorded, and returns them (empty when nothing was pending).
     * The record is deleted only after each value reads back from disk as the user's.
     */
    fun restore(context: Context): Map<String, Boolean> {
        val file = record(context)
        if (!file.exists()) return emptyMap()
        val saved = read(file)
        val unknown = saved.keys().asSequence().filter { it != SAFER_RUNG_RETRY }.toList()
        check(unknown.isEmpty()) { "$file names settings this build does not know ($unknown); it is kept" }
        val restored = linkedMapOf<String, Boolean>()
        if (saved.has(SAFER_RUNG_RETRY)) {
            val value = saved.getBoolean(SAFER_RUNG_RETRY)
            check(EncoderExperiments.setSaferRungRetryEnabled(context, value)) {
                "could not put the safer-rung retry switch back to $value: a batch holds the settings; $file is kept"
            }
            restored[SAFER_RUNG_RETRY] = value
        }
        flush(context)
        restored.forEach { (key, value) ->
            check(prefs(context).getBoolean(key, !value) == value) { "$key did not read back as $value; $file is kept" }
        }
        check(file.delete()) { "could not delete $file after putting its settings back" }
        return restored
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // EncoderExperiments writes with apply(), which can still be in memory when a process dies.
    // commit() returns once the store as it now stands, that write included, is on disk.
    private fun flush(context: Context) {
        check(prefs(context).edit().commit()) { "could not write the settings to disk" }
    }

    private fun read(file: File): JSONObject = if (file.exists()) JSONObject(file.readText()) else JSONObject()

    private fun writeDurably(file: File, json: JSONObject) {
        val dir = checkNotNull(file.parentFile)
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.toString().toByteArray())
            out.fd.sync()
        }
        check(tmp.renameTo(file)) { "could not record the user's settings in $file" }
    }
}
