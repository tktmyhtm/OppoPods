package moe.chenxy.oppopods.utils.miuiStrongToast.data

import android.content.Intent
import android.content.SharedPreferences

data class NotificationSettings(
    val showConnectionNotification: Boolean = OppoPodsPrefsKey.DEFAULT_SHOW_CONNECTION_NOTIFICATION,
    val notificationIslandStyle: Boolean = OppoPodsPrefsKey.DEFAULT_NOTIFICATION_ISLAND_STYLE
) {
    val showNotificationAsIsland: Boolean
        get() = showConnectionNotification && notificationIslandStyle

    fun putExtras(intent: Intent) {
        intent.putExtra(OppoPodsPrefsKey.SHOW_CONNECTION_NOTIFICATION, showConnectionNotification)
        intent.putExtra(OppoPodsPrefsKey.NOTIFICATION_ISLAND_STYLE, notificationIslandStyle)
    }

    companion object {
        fun fromPrefs(prefs: SharedPreferences): NotificationSettings {
            return NotificationSettings(
                showConnectionNotification = prefs.getBoolean(
                    OppoPodsPrefsKey.SHOW_CONNECTION_NOTIFICATION,
                    OppoPodsPrefsKey.DEFAULT_SHOW_CONNECTION_NOTIFICATION
                ),
                notificationIslandStyle = prefs.getBoolean(
                    OppoPodsPrefsKey.NOTIFICATION_ISLAND_STYLE,
                    OppoPodsPrefsKey.DEFAULT_NOTIFICATION_ISLAND_STYLE
                )
            )
        }

        fun fromIntent(intent: Intent?, fallback: NotificationSettings): NotificationSettings {
            if (intent == null) return fallback
            return NotificationSettings(
                showConnectionNotification = intent.getBooleanExtra(
                    OppoPodsPrefsKey.SHOW_CONNECTION_NOTIFICATION,
                    fallback.showConnectionNotification
                ),
                notificationIslandStyle = intent.getBooleanExtra(
                    OppoPodsPrefsKey.NOTIFICATION_ISLAND_STYLE,
                    fallback.notificationIslandStyle
                )
            )
        }
    }
}
