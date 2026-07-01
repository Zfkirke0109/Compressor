package compress.joshattic.us

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Optional Shizuku/Sui integration guardrails.
 *
 * Normal Android storage behavior stays the default. This bridge is used only
 * when the user enables Shizuku fallback and Shizuku/Sui is running with app
 * permission granted.
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
        return runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    }

    fun hasPermission(): Boolean {
        return runCatching {
            isBinderAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    fun canRequestPermission(): Boolean {
        return runCatching {
            isBinderAvailable() && !Shizuku.shouldShowRequestPermissionRationale()
        }.getOrDefault(false)
    }

    fun requestPermission() {
        runCatching {
            if (isBinderAvailable() && !hasPermission()) {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        }
    }

    fun backendLabel(): String {
        return runCatching {
            val uid = Shizuku.getUid()
            when (uid) {
                0 -> "Shizuku root"
                2000 -> "Shizuku shell"
                else -> "Shizuku uid $uid"
            }
        }.getOrDefault("Shizuku")
    }

    fun copyFileWithShizuku(sourcePath: String, targetPath: String): Boolean {
        if (!hasPermission()) return false
        val command = "cp ${shellQuote(sourcePath)} ${shellQuote(targetPath)} && sync"
        return runCatching {
            val process = startShellProcess(command) ?: return@runCatching false
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val exit = process.waitFor()
            exit == 0 && stderr.isBlank() && stdout.length >= 0
        }.getOrDefault(false)
    }

    /**
     * Shizuku 13.1.5 keeps process creation private, so direct calls fail Kotlin
     * compilation. Keep the app build-safe by looking it up reflectively and
     * falling back to normal Android storage behavior if it is unavailable.
     */
    private fun startShellProcess(command: String): Process? {
        return runCatching {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(
                null,
                arrayOf("sh", "-c", command),
                null as Array<String>?,
                null as String?
            ) as? Process
        }.getOrNull()
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
