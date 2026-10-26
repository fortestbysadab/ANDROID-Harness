package com.androidharness.tools

import android.Manifest
import android.content.Context
import android.telephony.SmsManager
import com.androidharness.permissions.Permissions
import org.json.JSONObject

/**
 * Sending a text message.
 *
 * The first tool in the project that does something the owner cannot
 * take back, and the reason the approval machinery was built before
 * any of these tools existed. Declared `external_side_effect`, so the
 * policy stops every call and binds the approval to a hash of this
 * exact recipient and this exact text - approving "tell Rafi I am
 * late" cannot become "tell everyone".
 *
 * It is also denied outright in an unattended run. A scheduled job that
 * decides to message somebody at three in the morning with nobody to
 * check it is not a feature.
 */
class SmsTool(private val context: Context) : NativeTool {

    override val name = "send_sms"
    override val description =
        "Send a text message to a phone number. Always ask the owner to confirm " +
            "before using this; the message cannot be unsent."
    override val inputSchema = """
        {
          "type": "object",
          "properties": {
            "to": {"type": "string", "minLength": 3, "maxLength": 20},
            "text": {"type": "string", "minLength": 1, "maxLength": 1600}
          },
          "required": ["to", "text"]
        }
    """.trimIndent()
    override val risk = "external_side_effect"
    override val checksArguments = true
    override val timeoutSeconds = 60.0

    /** Refuse an impossible message before the owner is asked about it. */
    override fun check(payloadJson: String): String? {
        val arguments = NativeTool.argumentsOf(payloadJson)
        (Messaging.number(arguments.optString("to")) as? Messaging.Number.Rejected)?.let {
            return NativeTool.error(it.summary, it.code)
        }
        (Messaging.message(arguments.optString("text")) as? Messaging.Message.Rejected)?.let {
            return NativeTool.error(it.summary, it.code)
        }
        return null
    }

    override fun run(payloadJson: String): String {
        val arguments = NativeTool.argumentsOf(payloadJson)

        // Checked again rather than trusted: the gate is the only
        // caller today, but a handler that relies on having been
        // vetted elsewhere is one refactor away from not being.
        val number = when (val parsed = Messaging.number(arguments.optString("to"))) {
            is Messaging.Number.Rejected -> return NativeTool.error(parsed.summary, parsed.code)
            is Messaging.Number.Dialable -> parsed.value
        }
        val message = when (val parsed = Messaging.message(arguments.optString("text"))) {
            is Messaging.Message.Rejected -> return NativeTool.error(parsed.summary, parsed.code)
            is Messaging.Message.Sendable -> parsed
        }

        val outcome = Permissions.ensure(context, listOf(Manifest.permission.SEND_SMS))
        if (outcome != Permissions.Outcome.GRANTED) {
            return NativeTool.error(
                Permissions.explain(outcome, "send SMS"),
                Permissions.code(outcome),
                retryable = outcome == Permissions.Outcome.CANNOT_ASK,
            )
        }

        val manager = runCatching { context.getSystemService(SmsManager::class.java) }.getOrNull()
            ?: return NativeTool.error(
                "This device has no SMS service - it may have no SIM, or be a " +
                    "tablet without a modem.",
                "no_sms_service",
            )

        return try {
            // Split by the platform rather than by our own estimate:
            // the radio knows which alphabet the text will be sent in,
            // and that decides where the boundaries fall.
            val parts = manager.divideMessage(message.text)
            if (parts.size > 1) {
                manager.sendMultipartTextMessage(number, null, parts, null, null)
            } else {
                manager.sendTextMessage(number, null, message.text, null, null)
            }
            NativeTool.ok(
                "Sent to $number" + if (parts.size > 1) " as ${parts.size} messages." else ".",
                JSONObject().put("to", number).put("parts", parts.size),
            )
        } catch (error: SecurityException) {
            NativeTool.error("Android refused the send: ${error.message}", "sms_refused")
        } catch (error: IllegalArgumentException) {
            NativeTool.error("The SMS service rejected it: ${error.message}", "sms_rejected")
        }
    }
}
