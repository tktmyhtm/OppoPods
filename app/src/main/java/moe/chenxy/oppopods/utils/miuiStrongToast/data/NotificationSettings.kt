package moe.chenxy.oppopods.utils.miuiStrongToast.data

import android.content.Intent
import android.content.SharedPreferences

data class NotificationSettings(
    val showConnectionBatteryIsland: Boolean = OppoPodsPrefsKey.DEFAULT_SHOW_CONNECTION_BATTERY_ISLAND,
    val showConnectionPopup: Boolean = OppoPodsPrefsKey.DEFAULT_SHOW_CONNECTION_POPUP,
    val connectionPopupDismissSeconds: Int = OppoPodsPrefsKey.DEFAULT_CONNECTION_POPUP_DISMISS_SECONDS,
    val showConnectionNotification: Boolean = OppoPodsPrefsKey.DEFAULT_SHOW_CONNECTION_NOTIFICATION,
    val notificationIslandStyle: Boolean = OppoPodsPrefsKey.DEFAULT_NOTIFICATION_ISLAND_STYLE
) {
    val showNotificationAsIsland: Boolean
        get() = showConnectionNotification && notificationIslandStyle

    fun putExtras(intent: Intent) {
        intent.putExtra(OppoPodsPrefsKey.SHOW_CONNECTION_BATTERY_ISLAND, showConnectionBatteryIsland)
        intent.putExtra(OppoPodsPrefsKey.SHOW_CONNECTION_POPUP, showConnectionPopup)
        intent.putExtra(OppoPodsPrefsKey.CONNECTION_POPUP_DISMISS_SECONDS, connectionPopupDismissSeconds)
        intent.putExtra(OppoPodsPrefsKey.SHOW_CONNECTION_NOTIFICATION, showConnectionNotification)
        intent.putExtra(OppoPodsPrefsKey.NOTIFICATION_ISLAND_STYLE, notificationIslandStyle)
    }

    companion object {
        fun fromPrefs(prefs: SharedPreferences): NotificationSettings {
            return NotificationSettings(
                showConnectionBatteryIsland = prefs.getBoolean(
                    OppoPodsPrefsKey.SHOW_CONNECTION_BATTERY_ISLAND,
                    OppoPodsPrefsKey.DEFAULT_SHOW_CONNECTION_BATTERY_ISLAND
                ),
                showConnectionPopup = prefs.getBoolean(
                    OppoPodsPrefsKey.SHOW_CONNECTION_POPUP,
                    OppoPodsPrefsKey.DEFAULT_SHOW_CONNECTION_POPUP
                ),
                connectionPopupDismissSeconds = prefs.getInt(
                    OppoPodsPrefsKey.CONNECTION_POPUP_DISMISS_SECONDS,
                    OppoPodsPrefsKey.DEFAULT_CONNECTION_POPUP_DISMISS_SECONDS
                ).normalizedPopupDismissSeconds(),
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
                showConnectionBatteryIsland = intent.getBooleanExtra(
                    OppoPodsPrefsKey.SHOW_CONNECTION_BATTERY_ISLAND,
                    fallback.showConnectionBatteryIsland
                ),
                showConnectionPopup = intent.getBooleanExtra(
                    OppoPodsPrefsKey.SHOW_CONNECTION_POPUP,
                    fallback.showConnectionPopup
                ),
                connectionPopupDismissSeconds = intent.getIntExtra(
                    OppoPodsPrefsKey.CONNECTION_POPUP_DISMISS_SECONDS,
                    fallback.connectionPopupDismissSeconds
                ).normalizedPopupDismissSeconds(),
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

        private fun Int.normalizedPopupDismissSeconds(): Int {
            return if (this in OppoPodsPrefsKey.CONNECTION_POPUP_DISMISS_SECOND_OPTIONS) {
                this
            } else {
                OppoPodsPrefsKey.DEFAULT_CONNECTION_POPUP_DISMISS_SECONDS
            }
        }
    }
}
