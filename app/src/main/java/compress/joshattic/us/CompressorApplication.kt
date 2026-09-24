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
    }
}
