package com.androidharness.tools

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.androidharness.notifications.HarnessNotificationListener
import com.androidharness.notifications.NotificationStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the phone has been told recently.
 *
 * Notification access is not a runtime permission - there is no dialog
 * to show, only a page in Settings the owner has to visit. Android
 * offers no way to ask. So the tool checks whether it is on and, when
 * it is not, says exactly where to go rather than reporting an empty
 * list, which would be the same confident lie the clipboard read was
 * careful to avoid.
 *
 * This is the most clearly untrusted source in the app: every word in
 * it was written by somebody else, often by software trying to get
 * attention. Reading it taints the run.
 */
class NotificationsTool(private val context: Context) : NativeTool {

    override val name = "get_notifications"
    override val description =
        "Read the notifications the phone has received recently, newest first, " +
            "with the app that sent each one."
    override val inputSchema = """
        {
          "type": "object",
          "properties": {
            "limit": {"type": "integer", "minimum": 1, "maximum": 25},
            "include_ongoing": {"type": "boolean"}
          },
          "required": []
        }
    """.trimIndent()
    override val risk = "sensitive_read"
    override val returnsUntrustedContent = true

    override fun run(payloadJson: String): String {
        if (!enabled()) {
            return NativeTool.error(
                "Harness is not allowed to see notifications. Turn it on under " +
                    "Settings > Notifications > Device & app notifications (or " +
                    "Special app access > Notification access) and try again. " +
                    "Android provides no way for an app to ask for this.",
                "notification_access_off",
                retryable = true,
            )
        }

        val arguments = NativeTool.argumentsOf(payloadJson)
        val limit = arguments.optInt("limit", 10).coerceIn(1, ToolFormatting.MAX_NOTIFICATIONS)
        val includeOngoing = arguments.optBoolean("include_ongoing", false)

        val notes = NotificationStore.shared.recent(limit, includeOngoing)
        val items = JSONArray()
        notes.forEach {
            items.put(
                JSONObject()
                    .put("app", it.appLabel)
                    .put("title", it.title)
                    .put("text", it.text)
                    .put("posted_at", it.postedAt),
            )
        }
        return NativeTool.ok(
            ToolFormatting.describeNotifications(notes, System.currentTimeMillis()),
            JSONObject().put("notifications", items).put("count", notes.size),
        )
    }

    private fun enabled(): Boolean {
        val listeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        val mine = ComponentName(context, HarnessNotificationListener::class.java)
        return listeners.split(":").any {
            ComponentName.unflattenFromString(it) == mine
        }
    }
}
