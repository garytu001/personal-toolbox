package com.local.personaltoolbox.accessibility.ads

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.os.Build
import android.provider.Settings

/** Enables Android's per-app media-session API. No notification payloads are read, stored or uploaded. */
class MediaControlAccess : NotificationListenerService() {
    companion object {
        fun isGranted(context: Context): Boolean {
            val component = ComponentName(context, MediaControlAccess::class.java)
            return if (Build.VERSION.SDK_INT >= 27) {
                context.getSystemService(android.app.NotificationManager::class.java)
                    .isNotificationListenerAccessGranted(component)
            } else {
                Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                    .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
            }
        }
    }
}
