package com.skyler.musicpresence.core.media

import android.app.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationCompat
import com.skyler.musicpresence.R
import com.skyler.musicpresence.core.discord.DiscordGateway
import com.skyler.musicpresence.core.discord.DiscordPayloads
import com.skyler.musicpresence.core.itunes.iTunesSearchClient
import com.skyler.musicpresence.core.prefs.AppPreferences
import com.skyler.musicpresence.ui.MainActivity
import kotlinx.coroutines.*

class AppleMediaListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var discordGateway: DiscordGateway
    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var prefs: AppPreferences
    private val itunesClient = iTunesSearchClient()

    private var appleMusicController: MediaController? = null
    private var updatePresenceJob: Job? = null
    private var lastTrackTitle: String = ""
    private var lastTrackArtist: String = ""

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            super.onPlaybackStateChanged(state)
            Log.d(TAG, "PlaybackState changed: ${state?.state}")
            handleMediaChange()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            super.onMetadataChanged(metadata)
            Log.d(TAG, "Metadata changed: ${metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)}")
            handleMediaChange()
        }

        override fun onSessionDestroyed() {
            super.onSessionDestroyed()
            Log.d(TAG, "Apple Music session destroyed")
            clearActiveTrack()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AppleMediaListenerService onCreate")
        prefs = AppPreferences(this)
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

        discordGateway = DiscordGateway(serviceScope) { isConnected ->
            updateNotification(isConnected)
        }

        createNotificationChannel()
        startForegroundServiceNotification()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "NotificationListener connected")

        // Auto-connect Discord gateway if token exists and service enabled
        val token = prefs.discordToken
        if (prefs.isServiceEnabled && token.isNotBlank()) {
            discordGateway.connect(token)
        }

        setupMediaSessions()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "NotificationListener disconnected")
        cleanupMediaController()
    }

    private fun setupMediaSessions() {
        val componentName = ComponentName(this, AppleMediaListenerService::class.java)
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(
                { sessions -> checkActiveSessions(sessions) },
                componentName
            )
            checkActiveSessions(mediaSessionManager.getActiveSessions(componentName))
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to get active sessions: ${e.message}")
        }
    }

    private fun checkActiveSessions(sessions: List<MediaController>?) {
        val appleSession = sessions?.firstOrNull { it.packageName == APPLE_MUSIC_PACKAGE }
        if (appleSession != null) {
            if (appleMusicController?.sessionToken != appleSession.sessionToken) {
                cleanupMediaController()
                Log.i(TAG, "Hooked to Apple Music session")
                appleMusicController = appleSession
                appleSession.registerCallback(mediaControllerCallback)
                handleMediaChange()
            }
        } else {
            if (appleMusicController != null) {
                Log.d(TAG, "Apple Music session gone")
                cleanupMediaController()
                clearActiveTrack()
            }
        }
    }

    private fun handleMediaChange() {
        val controller = appleMusicController ?: return
        val playbackState = controller.playbackState ?: return
        val metadata = controller.metadata ?: return

        val isPlaying = playbackState.state == PlaybackState.STATE_PLAYING

        if (!isPlaying) {
            clearActiveTrack()
            return
        }

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val position = playbackState.position

        if (title.isBlank() || artist.isBlank()) {
            clearActiveTrack()
            return
        }

        // Debounce update (1.2s) to prevent spamming Gateway during track scrubbing / rapid skip
        updatePresenceJob?.cancel()
        updatePresenceJob = serviceScope.launch(Dispatchers.IO) {
            delay(1200)

            val searchResult = if (prefs.showArtwork || prefs.showButton) {
                itunesClient.searchTrack(title, artist)
            } else null

            val now = System.currentTimeMillis()
            val startTimestamp = if (prefs.showTimestamps && position >= 0) now - position else null
            val endTimestamp = if (prefs.showTimestamps && duration > 0) startTimestamp?.plus(duration) else null

            val payload = DiscordPayloads.createPresenceUpdate(
                applicationId = prefs.applicationId,
                title = title,
                artist = artist,
                album = album,
                startTimestamp = startTimestamp,
                endTimestamp = endTimestamp,
                artworkUrl = if (prefs.showArtwork) searchResult?.artworkUrl else null,
                songUrl = if (prefs.showButton) searchResult?.trackViewUrl else null,
                showButtons = prefs.showButton
            )

            discordGateway.updatePresence(payload)
            lastTrackTitle = title
            lastTrackArtist = artist

            withContext(Dispatchers.Main) {
                updateNotification(true, "$title - $artist")
            }
        }
    }

    private fun clearActiveTrack() {
        updatePresenceJob?.cancel()
        lastTrackTitle = ""
        lastTrackArtist = ""
        discordGateway.clearPresence()
        updateNotification(true, null)
    }

    private fun cleanupMediaController() {
        appleMusicController?.unregisterCallback(mediaControllerCallback)
        appleMusicController = null
    }

    private fun startForegroundServiceNotification() {
        val notification = buildNotification(false, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(isConnected: Boolean, songInfo: String? = null) {
        val notification = buildNotification(isConnected, songInfo)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(isConnected: Boolean, songInfo: String?): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = when {
            !songInfo.isNullOrBlank() -> "Listening: $songInfo"
            isConnected -> "Ready & listening for playback"
            else -> "Connecting to Discord..."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Apple Music Presence")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_apple_music)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Presence Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Apple Music Rich Presence running in background"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "AppleMediaListenerService onDestroy")
        cleanupMediaController()
        discordGateway.disconnect()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "AppleMediaListener"
        private const val CHANNEL_ID = "apple_music_presence_channel"
        private const val NOTIFICATION_ID = 42069
        const val APPLE_MUSIC_PACKAGE = "com.apple.android.music"
    }
}
