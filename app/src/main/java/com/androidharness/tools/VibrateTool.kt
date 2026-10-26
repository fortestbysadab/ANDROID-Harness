package com.androidharness.tools

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.json.JSONObject

/**
 * A buzz. The smallest possible tool, kept because it is the only way
 * the agent can get attention without making a sound.
 *
 * `VIBRATE` is an install-time permission, so there is nothing to
 * prompt for - which is the point of this batch.
 */
class VibrateTool(private val context: Context) : NativeTool {

    override val name = "vibrate"
    override val description =
        "Vibrate the phone briefly to get the owner's attention. Duration is in " +
            "milliseconds and is capped at five seconds."
    override val inputSchema = """
        {
          "type": "object",
          "properties": {
            "milliseconds": {"type": "integer", "minimum": 1, "maximum": 60000}
          },
          "required": []
        }
    """.trimIndent()
    override val risk = "reversible"

    override fun run(payloadJson: String): String {
        val requested = ToolArguments.vibrationMillis(NativeTool.argumentsOf(payloadJson))
        val millis = when (requested) {
            is ToolArguments.Parsed.Invalid -> return NativeTool.error(requested.summary, requested.code)
            is ToolArguments.Parsed.Valid -> requested.value
        }

        val vibrator = vibrator()
            ?: return NativeTool.error("this device has no vibrator", "no_vibrator")
        if (!vibrator.hasVibrator()) {
            return NativeTool.error("this device has no vibrator", "no_vibrator")
        }

        vibrator.vibrate(
            VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE),
        )
        return NativeTool.ok(
            "Vibrated for $millis ms.",
            JSONObject().put("milliseconds", millis),
        )
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
}
