package com.example.aisecurity.ble

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class WatchNotificationService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        // We only care about user-facing notifications, not silent background services
        if (sbn.isOngoing || sbn.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) {
            return
        }

        try {
            val extras = sbn.notification.extras

            // Extract the App Name
            val pm = applicationContext.packageManager
            val appName = pm.getApplicationLabel(
                pm.getApplicationInfo(sbn.packageName, 0)
            ).toString()

            // Extract the actual message content
            val title = extras.getString(Notification.EXTRA_TITLE) ?: appName
            var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

            // Handle "BigText" like long WhatsApp messages
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            if (!bigText.isNullOrEmpty()) text = bigText

            if (text.isNotEmpty() && WatchManager.isConnected.value == true) {
                Log.d("NOTIF_SYNC", "Pushing to watch -> $title: $text")
                WatchManager.sendNotificationToWatch(title, text)
            }

        } catch (e: Exception) {
            Log.e("NOTIF_SYNC", "Failed to parse notification: ${e.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        // You could theoretically tell the watch to delete the card here,
        // but for now, we just let the user use the "Clear All" button on the watch.
    }
}