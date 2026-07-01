package compress.joshattic.us

import android.content.Context

/**
 * Optional Shizuku/Sui integration guardrails.
 *
 * The app must keep building and keep normal Android storage behavior even when
 * Shizuku artifacts are not bundled. This no-op adapter preserves the UI and
 * replace-original fallback call sites while avoiding a hard build dependency
 * on Shizuku AAR metadata. A future PR can re-enable the real bridge once the
 * project's AGP/SDK matrix is confirmed compatible.
 */
object ShizukuSupport {
    const val REQUEST_CODE = 23023

    fun isShizukuPackageInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun isBinderAvailable(): Boolean = false

    fun hasPermission(): Boolean = false

    fun canRequestPermission(): Boolean = false

    fun requestPermission() = Unit

    fun backendLabel(): String = "Disabled in this build"

    fun copyFileWithShizuku(sourcePath: String, targetPath: String): Boolean = false
}
