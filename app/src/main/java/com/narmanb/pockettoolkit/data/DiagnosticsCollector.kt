package com.narmanb.pockettoolkit.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.DisplayMetrics
import java.util.Locale

internal data class DiagnosticItem(val label: String, val value: String)
internal data class DiagnosticSection(val title: String, val items: List<DiagnosticItem>)

internal object DiagnosticsCollector {
    fun collect(context: Context): List<DiagnosticSection> {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val display = context.resources.displayMetrics
        val dataFs = StatFs(Environment.getDataDirectory().absolutePath)
        val externalFs = runCatching { StatFs(Environment.getExternalStorageDirectory().absolutePath) }.getOrNull()
        val glVersion = activityManager.deviceConfigurationInfo.reqGlEsVersion
        val vulkanVersion = context.packageManager.systemAvailableFeatures
            .firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
            ?.version

        return listOf(
            DiagnosticSection(
                "Device",
                listOf(
                    DiagnosticItem("Manufacturer", Build.MANUFACTURER.orUnknown()),
                    DiagnosticItem("Model", Build.MODEL.orUnknown()),
                    DiagnosticItem("Device", Build.DEVICE.orUnknown()),
                    DiagnosticItem("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
                    DiagnosticItem("Security patch", Build.VERSION.SECURITY_PATCH.orUnknown()),
                    DiagnosticItem("SoC", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.orUnknown() else "Not exposed by Android"),
                    DiagnosticItem("CPU ABIs", Build.SUPPORTED_ABIS.joinToString())
                )
            ),
            DiagnosticSection(
                "Graphics & display",
                listOf(
                    DiagnosticItem("OpenGL ES", glVersionString(glVersion)),
                    DiagnosticItem("Vulkan", vulkanVersion?.let(::vulkanVersionString) ?: "Not reported"),
                    DiagnosticItem("Resolution", "${display.widthPixels} × ${display.heightPixels}"),
                    DiagnosticItem("Density", "${display.densityDpi} dpi")
                )
            ),
            DiagnosticSection(
                "Memory & storage",
                buildList {
                    add(DiagnosticItem("RAM total", formatBytes(memoryInfo.totalMem)))
                    add(DiagnosticItem("RAM available", formatBytes(memoryInfo.availMem)))
                    add(DiagnosticItem("Internal total", formatBytes(dataFs.totalBytes)))
                    add(DiagnosticItem("Internal free", formatBytes(dataFs.availableBytes)))
                    externalFs?.let {
                        add(DiagnosticItem("Shared storage total", formatBytes(it.totalBytes)))
                        add(DiagnosticItem("Shared storage free", formatBytes(it.availableBytes)))
                    }
                }
            ),
            DiagnosticSection(
                "Battery",
                listOf(
                    DiagnosticItem("Level", batteryPercent(batteryIntent)),
                    DiagnosticItem("Temperature", batteryTemperature(batteryIntent)),
                    DiagnosticItem(
                        "Charge counter",
                        batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                            .takeIf { it != Int.MIN_VALUE }
                            ?.let { String.format(Locale.US, "%.0f mAh", it / 1000.0) }
                            ?: "Not exposed"
                    )
                )
            )
        )
    }

    private fun batteryPercent(intent: Intent?): String {
        if (intent == null) return "Unavailable"
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) "${(level * 100f / scale).toInt()}%" else "Unavailable"
    }

    private fun batteryTemperature(intent: Intent?): String {
        val tenthsC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (tenthsC == Int.MIN_VALUE) return "Not exposed"
        val c = tenthsC / 10.0
        val f = c * 9.0 / 5.0 + 32.0
        return String.format(Locale.US, "%.1f °C / %.1f °F", c, f)
    }

    private fun glVersionString(encoded: Int): String = "${encoded shr 16}.${encoded and 0xffff}"

    private fun vulkanVersionString(encoded: Int): String {
        val major = encoded ushr 22
        val minor = (encoded ushr 12) and 0x3ff
        val patch = encoded and 0xfff
        return "$major.$minor.$patch"
    }

    internal fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        do {
            value /= 1024.0
            unit++
        } while (value >= 1024.0 && unit < units.lastIndex)
        return String.format(Locale.US, "%.2f %s", value, units[unit])
    }

    private fun String?.orUnknown(): String = if (this.isNullOrBlank()) "Unknown" else this
}
