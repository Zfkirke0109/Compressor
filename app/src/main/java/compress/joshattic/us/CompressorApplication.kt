package compress.joshattic.us

import android.app.Application

/**
 * Installs [CrashRecorder] before any activity or service code runs, including in a process that
 * the platform starts only to host [BatchForegroundService]. Installing it from an activity would
 * miss those crashes.
 */
class CompressorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashRecorder.install(this)
        // Why did the last process end? Native crashes, ANRs and system kills bypass
        // CrashRecorder entirely; the platform keeps the reason and this writes it down.
        Thread({ ProcessExitRecorder.recordPreviousExits(this) }, "exit-reasons").start()
    }
}
