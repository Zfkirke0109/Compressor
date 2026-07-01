package compress.joshattic.us

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Optional Shizuku/Sui integration guardrails.
 *
 * Compressor must continue to work normally without Shizuku. Use this helper
 * before any privileged file operation, and keep the regular Android Storage
 * Access Framework / MediaStore path as the default path.
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

    fun isBinderAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    fun hasPermission(): Boolean {
        return try {
            !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    fun canRequestPermission(): Boolean {
        return try {
            isBinderAvailable() && !Shizuku.isPreV11() && !Shizuku.shouldShowRequestPermissionRationale()
        } catch (_: Exception) {
            false
        }
    }

    fun requestPermission() {
        if (canRequestPermission()) {
            Shizuku.requestPermission(REQUEST_CODE)
        }
    }

    fun backendLabel(): String {
        return try {
            when (Shizuku.getUid()) {
                0 -> "Sui/root"
                2000 -> "Shizuku/ADB shell"
                else -> "Shizuku UID ${Shizuku.getUid()}"
            }
        } catch (_: Exception) {
            "Unavailable"
        }
    }

    fun copyFileWithShizuku(sourcePath: String, targetPath: String): Boolean {
        if (!hasPermission()) return false
        return try {
            val command = "cp ${shellQuote(sourcePath)} ${shellQuote(targetPath)} && chmod 0644 ${shellQuote(targetPath)}"
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
