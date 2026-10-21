package com.skyler.musicpresence.core.media

import android.app.Notification
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

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val pkg = sbn.packageName ?: return
        if (!pkg.contains("youtube")) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

        val candidates = buildList {
            extras.keySet().forEach { key ->
                when (val value = extras.get(key)) {
                    is CharSequence -> add(value.toString())
                    is String -> add(value)
                    else -> Unit
                }
            }
            notification.contentIntent?.toString()?.let { add(it) }
        }

        val videoId = candidates.firstNotNullOfOrNull { extractVideoId(it) }
        if (videoId != null) {
            PresenceRuntime.rememberVideoId(title, videoId)
        }
    }

    private fun extractVideoId(raw: String): String? {
        val patterns = listOf(
            Regex("""(?:v=|youtu\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})"""),
            Regex("""^([A-Za-z0-9_-]{11})$""")
        )
        return patterns.firstNotNullOfOrNull { it.find(raw)?.groupValues?.getOrNull(1) }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        PresenceRuntime.log("Notification access disconnected")
    }
}
