package com.androidharness.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Receives notifications as they are posted.
 *
 * This is not a runtime permission. The owner has to turn it on in
 * Settings under Notification access, and Android gives no way to
 * prompt for it - so the tool detects that it is off and says exactly
 * where to go.
 *
 * Nothing is persisted; see NotificationStore for why.
 */
class HarnessNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn ?: return
        val extras = posted.notification?.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (
            extras?.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
            )?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        NotificationStore.shared.remember(
            Note(
                key = posted.key,
                packageName = posted.packageName,
                appLabel = label(posted.packageName),
                title = title,
                text = text,
                postedAt = posted.postTime,
                ongoing = posted.isOngoing,
            ),
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.key?.let { NotificationStore.shared.forget(it) }
    }

    override fun onListenerDisconnected() {
        // The history would be stale and misleading after a rebind.
        NotificationStore.shared.clear()
    }

    private fun label(packageName: String): String = runCatching {
        val manager = packageManager
        manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}
