package com.androidharness.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.androidharness.permissions.Permissions
import org.json.JSONObject

/**
 * Placing a phone call.
 *
 * Irreversible in the way that matters: the other phone rings, and
 * whoever is holding it knows. So, like SMS, every call stops for an
 * approval bound to this exact number.
 *
 * Emergency numbers are refused deliberately. Android will not let an
 * app dial them with ACTION_CALL anyway, and failing with an
 * explanation beats failing silently at the one moment when silence is
 * worst.
 */
class CallTool(private val context: Context) : NativeTool {

    override val name = "place_call"
    override val description =
        "Call a phone number from this phone. The call starts immediately and the " +
            "other person's phone rings."
    override val inputSchema = """
        {
          "type": "object",
          "properties": {
            "to": {"type": "string", "minLength": 3, "maxLength": 20}
          },
          "required": ["to"]
        }
    """.trimIndent()
    override val risk = "external_side_effect"

    override fun run(payloadJson: String): String {
        val number = when (
            val parsed = Messaging.number(NativeTool.argumentsOf(payloadJson).optString("to"))
        ) {
            is Messaging.Number.Rejected -> return NativeTool.error(parsed.summary, parsed.code)
            is Messaging.Number.Dialable -> parsed.value
        }

        if (Messaging.isEmergency(number)) {
            return NativeTool.error(
                "Android does not allow an app to dial emergency numbers. " +
                    "Dial $number yourself, now.",
                "emergency_number",
            )
        }

        val outcome = Permissions.ensure(context, listOf(Manifest.permission.CALL_PHONE))
        if (outcome != Permissions.Outcome.GRANTED) {
            return NativeTool.error(
                Permissions.explain(outcome, "make calls"),
                Permissions.code(outcome),
                retryable = outcome == Permissions.Outcome.CANNOT_ASK,
            )
        }

        val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            NativeTool.ok("Calling $number now.", JSONObject().put("to", number))
        } catch (error: SecurityException) {
            NativeTool.error("Android refused the call: ${error.message}", "call_refused")
        } catch (error: Exception) {
            NativeTool.error(
                "Nothing on this phone could place the call: ${error.message}",
                "no_dialer",
            )
        }
    }
}
