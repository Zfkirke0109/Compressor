package compress.joshattic.us

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class EncoderExperimentMigrationTest {
    @Test
    fun changingBFramesBeforeFirstPlanPreservesTheLegacyLearningBaseline() {
        val prefs = preferences(mutableMapOf("max_b_frames" to 2))
        EncoderExperiments.setBFramesEnabled(prefs, false)
        assertEquals(";bf0", EncoderExperiments.learningKeySuffixForPreferences(prefs))
        EncoderExperiments.setBFramesEnabled(prefs, true)
        assertEquals("", EncoderExperiments.learningKeySuffixForPreferences(prefs))
    }

    @Test
    fun enablingBFramesBeforeFirstPlanPreservesTheLegacyOffBaseline() {
        val prefs = preferences(mutableMapOf())
        EncoderExperiments.setBFramesEnabled(prefs, true)
        assertEquals(";bf2", EncoderExperiments.learningKeySuffixForPreferences(prefs))
        EncoderExperiments.setBFramesEnabled(prefs, false)
        assertEquals("", EncoderExperiments.learningKeySuffixForPreferences(prefs))
    }

    @Test
    fun changingTheSettingNeverRepinsAnExistingBaseline() {
        val prefs = preferences(mutableMapOf("max_b_frames" to 0, "learning_baseline_b_frames" to 2))
        EncoderExperiments.setBFramesEnabled(prefs, false)
        assertEquals(";bf0", EncoderExperiments.learningKeySuffixForPreferences(prefs))
        EncoderExperiments.setBFramesEnabled(prefs, true)
        assertEquals("", EncoderExperiments.learningKeySuffixForPreferences(prefs))
    }

    // Only the Android preference storage boundary is replaced; the real migration and key
    // selection execute above. Apply updates atomically, as SharedPreferences does in memory.
    private fun preferences(values: MutableMap<String, Any>): SharedPreferences {
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "contains" -> values.containsKey(args!![0] as String)
                "getInt", "getBoolean" -> values[args!![0] as String] ?: args[1]
                "edit" -> {
                    val pending = mutableMapOf<String, Any>()
                    Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                        arrayOf(SharedPreferences.Editor::class.java)) { editor, operation, arguments ->
                        when (operation.name) {
                            "putInt", "putBoolean" -> {
                                pending[arguments!![0] as String] = arguments[1]
                                editor
                            }
                            "apply" -> { values.putAll(pending); null }
                            else -> error("Unexpected preference editor call: ${operation.name}")
                        }
                    }
                }
                else -> error("Unexpected preference call: ${method.name}")
            }
        } as SharedPreferences
    }
}
