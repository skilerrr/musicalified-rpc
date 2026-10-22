package com.skyler.musicpresence.core.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.skyler.musicpresence.core.prefs.AppPreferences

/**
 * Brings the presence service back after a reboot, an app update, or an OEM "task removed"
 * kill. Aggressive ROMs (MIUI/HyperOS, ColorOS, One UI) stop foreground services when the
 * task is swiped away; without this the Discord presence stays gone until the app is opened
 * again by hand.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in TRIGGERS) return

        val prefs = AppPreferences(context)
        if (!prefs.isServiceEnabled) return
        if (prefs.discordToken.isBlank()) return

        val serviceIntent = Intent(context, AppleMediaListenerService::class.java).apply {
            this.action = AppleMediaListenerService.ACTION_RESCAN_SESSIONS
        }
        runCatching { ContextCompat.startForegroundService(context, serviceIntent) }
    }

    private companion object {
        val TRIGGERS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
