package com.skyler.musicpresence.core.media

import android.content.Intent
import android.service.notification.NotificationListenerService
import androidx.core.content.ContextCompat
import com.skyler.musicpresence.core.runtime.PresenceRuntime

/**
 * Android system hook. Android binds to this service when the user grants
 * Notification Access in Settings. It acts as the authorized component
 * for MediaSessionManager queries and pings AppleMediaListenerService.
 */
class AppleNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        PresenceRuntime.log("Notification access active")
        try {
            val intent = Intent(this, AppleMediaListenerService::class.java).apply {
                action = AppleMediaListenerService.ACTION_RESCAN_SESSIONS
            }
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            PresenceRuntime.log("Listener trigger notice: ${e.message}")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        PresenceRuntime.log("Notification access disconnected")
    }
}
