package com.skyler.musicpresence.core.prefs

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var discordToken: String
        get() = prefs.getString(KEY_DISCORD_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_DISCORD_TOKEN, value.trim()).apply()

    var applicationId: String
        get() = prefs.getString(KEY_APPLICATION_ID, DEFAULT_APP_ID).orEmpty()
        set(value) = prefs.edit().putString(KEY_APPLICATION_ID, value.trim()).apply()

    var showArtwork: Boolean
        get() = prefs.getBoolean(KEY_SHOW_ARTWORK, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_ARTWORK, value).apply()

    var showButton: Boolean
        get() = prefs.getBoolean(KEY_SHOW_BUTTON, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_BUTTON, value).apply()

    var showTimestamps: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TIMESTAMPS, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_TIMESTAMPS, value).apply()

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_ENABLED, value).apply()

    companion object {
        private const val PREFS_NAME = "music_presence_prefs"
        private const val KEY_DISCORD_TOKEN = "discord_token"
        private const val KEY_APPLICATION_ID = "application_id"
        private const val KEY_SHOW_ARTWORK = "show_artwork"
        private const val KEY_SHOW_BUTTON = "show_button"
        private const val KEY_SHOW_TIMESTAMPS = "show_timestamps"
        private const val KEY_SERVICE_ENABLED = "service_enabled"

        // Musicalified Discord application. Required for artwork + buttons;
        // users can override or clear it in the UI.
        const val DEFAULT_APP_ID = "1562285738404478996"
    }
}
