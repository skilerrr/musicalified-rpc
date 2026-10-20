package com.skyler.musicpresence

import android.app.Application
import android.util.Log

class MusicPresenceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("MusicPresenceApp", "Application initialized")
    }
}
