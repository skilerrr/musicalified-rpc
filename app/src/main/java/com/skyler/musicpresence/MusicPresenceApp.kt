package com.skyler.musicpresence

import android.app.Application
import com.skyler.musicpresence.core.prefs.AppPreferences
import com.skyler.musicpresence.core.runtime.PresenceRuntime

class MusicPresenceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = AppPreferences(this)
        if (prefs.isServiceEnabled && prefs.discordToken.isNotBlank()) {
            PresenceRuntime.ensureConnected(prefs.discordToken, true)
        }
    }
}
