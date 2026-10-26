package com.androidharness.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.json.JSONObject

/**
 * The first native tool, and the proof of the whole arrangement: the
 * model asks, the gate decides, Kotlin reads a real Android API, and
 * the answer comes back as data rather than as a sentence the model
 * invented.
 *
 * Read-only and about the device itself, so it needs no approval. The
 * predecessor needed Termux:API for this; here it is a system call.
 */
class BatteryTool(private val context: Context) : NativeTool {

    override val name = "get_battery_status"
    override val description =
        "Read this phone's battery level, whether it is charging, and its temperature."
    override val inputSchema = """{"type":"object","properties":{},"required":[]}"""
    override val risk = "read_only"

    override fun run(payloadJson: String): String {
        val status: Intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: return NativeTool.error("the battery service did not answer", "battery_unavailable")

        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) {
            return NativeTool.error("the battery level was not reported", "battery_unavailable")
        }
        val percent = level * 100 / scale

        val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = plugged != 0
        val source = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "mains"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "battery"
        }
        // Reported in tenths of a degree.
        val tenths = status.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val celsius = if (tenths == Int.MIN_VALUE) null else tenths / 10.0

        val data = JSONObject()
            .put("percent", percent)
            .put("charging", charging)
            .put("power_source", source)
        if (celsius != null) data.put("temperature_celsius", celsius)

        val summary = buildString {
            append("Battery at ").append(percent).append('%')
            append(if (charging) ", charging from $source" else ", not charging")
            if (celsius != null) append(", ").append(celsius).append("°C")
            append('.')
        }
        return NativeTool.ok(summary, data)
    }
}
