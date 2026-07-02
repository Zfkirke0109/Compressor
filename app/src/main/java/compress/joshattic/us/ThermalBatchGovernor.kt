package compress.joshattic.us

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

enum class ThermalBatchMode(val label: String) {
    CONSERVATIVE("Conservative"),
    BALANCED("Balanced"),
    FAST("Fast")
}

data class ThermalBatchSnapshot(
    val mode: ThermalBatchMode,
    val thermalLabel: String,
    val batteryPercent: Int?,
    val isCharging: Boolean,
    val shouldPause: Boolean,
    val shouldSlowDown: Boolean,
    val preItemDelayMs: Long,
    val postItemDelayMs: Long,
    val summary: String
)

object ThermalBatchGovernor {
    fun modeFromLabel(label: String): ThermalBatchMode {
        return ThermalBatchMode.values().firstOrNull { it.label == label } ?: ThermalBatchMode.BALANCED
    }

    fun snapshot(context: Context, modeLabel: String, userCooldownSeconds: Int): ThermalBatchSnapshot {
        val mode = modeFromLabel(modeLabel)
        val thermalStatus = readThermalStatus(context)
        val thermalLabel = thermalStatusLabel(thermalStatus)
        val battery = readBattery(context)

        val batteryLow = battery.percent != null && battery.percent <= 20 && !battery.isCharging
        val batteryCritical = battery.percent != null && battery.percent <= 10 && !battery.isCharging

        val severeThermal = thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        val moderateThermal = thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE

        val shouldPause = severeThermal || batteryCritical
        val shouldSlowDown = shouldPause || moderateThermal || batteryLow

        val baseCooldown = when (mode) {
            ThermalBatchMode.CONSERVATIVE -> 20
            ThermalBatchMode.BALANCED -> 10
            ThermalBatchMode.FAST -> 0
        }

        val thermalExtra = when {
            severeThermal -> 60
            moderateThermal -> 25
            thermalStatus >= PowerManager.THERMAL_STATUS_LIGHT -> 10
            else -> 0
        }

        val batteryExtra = when {
            batteryCritical -> 60
            batteryLow -> 20
            else -> 0
        }

        val requestedCooldown = userCooldownSeconds.coerceIn(0, 60)
        val postDelaySeconds = (requestedCooldown + baseCooldown + thermalExtra + batteryExtra).coerceIn(0, 120)
        val preDelayMs = if (shouldSlowDown && !shouldPause) 5_000L else 0L

        val batteryText = battery.percent?.let { "$it%" } ?: "unknown"
        val summary = "Thermal: $thermalLabel • Battery: $batteryText${if (battery.isCharging) " charging" else ""} • Mode: ${mode.label} • Cooldown: ${postDelaySeconds}s"

        return ThermalBatchSnapshot(
            mode = mode,
            thermalLabel = thermalLabel,
            batteryPercent = battery.percent,
            isCharging = battery.isCharging,
            shouldPause = shouldPause,
            shouldSlowDown = shouldSlowDown,
            preItemDelayMs = preDelayMs,
            postItemDelayMs = postDelaySeconds * 1000L,
            summary = summary
        )
    }

    private fun readThermalStatus(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return PowerManager.THERMAL_STATUS_NONE
        return runCatching {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
        }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
    }

    private fun thermalStatusLabel(status: Int): String {
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "normal"
            PowerManager.THERMAL_STATUS_LIGHT -> "slightly warm"
            PowerManager.THERMAL_STATUS_MODERATE -> "warm"
            PowerManager.THERMAL_STATUS_SEVERE -> "hot"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown risk"
            else -> "unknown"
        }
    }

    private data class BatterySnapshot(val percent: Int?, val isCharging: Boolean)

    private fun readBattery(context: Context): BatterySnapshot {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt() else null

        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        return BatterySnapshot(percent, charging)
    }
}
