package compress.joshattic.us

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Puts back the user's settings a device test changed and could not put back itself, its process
 * having died before its @After ran (TestOwnedSettings). The safe-copy harness and
 * run-device-checks.sh run it in an instrumentation run of its own after the fallback test, so it
 * runs whatever became of that test's process. It passes when nothing was pending, or when every
 * pending value was put back and read back from disk; it changes nothing else.
 */
@RunWith(AndroidJUnit4::class)
class TestOwnedSettingsDeviceTest {

    @Test
    fun theUsersSettingsAreTheirsAgain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val restored = TestOwnedSettings.restore(context)
        println(
            "pllab-settings restored=${if (restored.isEmpty()) "nothing-pending" else restored.toString()} " +
                "saferRungRetry=${EncoderExperiments.isSaferRungRetryEnabled(context)}"
        )
        assertFalse("the record of the user's settings is still there", TestOwnedSettings.record(context).exists())
    }
}
