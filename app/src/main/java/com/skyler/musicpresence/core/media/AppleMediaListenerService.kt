package com.skyler.musicpresence.core.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.skyler.musicpresence.R
import com.skyler.musicpresence.core.discord.DiscordPayloads
import com.skyler.musicpresence.core.itunes.iTunesSearchClient
import com.skyler.musicpresence.core.discord.ExternalAssetClient
import com.skyler.musicpresence.core.prefs.AppPreferences
import com.skyler.musicpresence.core.runtime.PresenceRuntime
import com.skyler.musicpresence.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plain foreground service (0% polling): it only reacts to MediaSession callbacks
 * for Apple Music and forwards them to the Discord gateway.
 */
class AppleMediaListenerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var prefs: AppPreferences
    private val itunesClient = iTunesSearchClient()
    private val externalAssetClient = ExternalAssetClient()

    private var activeController: MediaController? = null
    private var debounceJob: Job? = null
    private var lastKey: String = ""

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { sessions -> checkSessions(sessions) }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = handleMediaChange()
        override fun onMetadataChanged(metadata: MediaMetadata?) = handleMediaChange()
        override fun onSessionDestroyed() {
            PresenceRuntime.log("Apple Music session closed")
            clearPlaying()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

        createNotificationChannel()
        promoteToForeground()

        PresenceRuntime.log("Presence service started")
        PresenceRuntime.ensureConnected(prefs.discordToken, prefs.isServiceEnabled)
        registerSessionListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FORCE_RECONNECT -> {
                PresenceRuntime.forceReconnect(prefs.discordToken)
                scanSessions()
            }
            ACTION_CONFIG_CHANGED -> {
                PresenceRuntime.ensureConnected(prefs.discordToken, prefs.isServiceEnabled)
                scanSessions()
            }
            ACTION_STOP_SERVICE -> stopSelf()
            else -> scanSessions()
        }
        return START_STICKY
    }

    private fun registerSessionListener() {
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(
                sessionsListener,
                ComponentName(this, AppleNotificationListener::class.java)
            )
            scanSessions()
        } catch (e: Exception) {
            PresenceRuntime.log("Need notification access to read media sessions")
        }
    }

    private fun scanSessions() {
        try {
            val sessions = mediaSessionManager.getActiveSessions(
                ComponentName(this, AppleNotificationListener::class.java)
            )
            checkSessions(sessions)
        } catch (e: Exception) {
            PresenceRuntime.log("Session scan blocked: ${e.javaClass.simpleName}")
        }
    }

    private fun checkSessions(sessions: List<MediaController>?) {
        val apple = sessions?.firstOrNull { it.packageName in SUPPORTED_PACKAGES }
        if (apple == null) {
            if (activeController != null) {
                unbindActive()
                clearPlaying()
            }
            return
        }
        if (activeController?.sessionToken != apple.sessionToken) {
            unbindActive()
            activeController = apple
            apple.registerCallback(mediaCallback, null)
            PresenceRuntime.log("Hooked ${apple.packageName}")
        }
        handleMediaChange()
    }

    private fun handleMediaChange() {
        val controller = activeController ?: return
        val playback = controller.playbackState
        val meta = controller.metadata

        val state = playback?.state ?: PlaybackState.STATE_NONE
        if (state != PlaybackState.STATE_PLAYING || meta == null) {
            clearPlaying()
            return
        }

        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim()
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.trim().orEmpty()
        val album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim().orEmpty()
        val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val position = playback?.position ?: -1L

        if (title.isBlank()) {
            clearPlaying()
            return
        }

        val key = "$title\u0000$artist\u0000$album"
        if (key == lastKey && debounceJob?.isActive == true) return
        lastKey = key

        debounceJob?.cancel()
        debounceJob = scope.launch(Dispatchers.IO) {
            delay(1200) // debounce rapid skips so Discord is not spammed

            val appId = prefs.applicationId.takeIf { it.isNotBlank() }
            val source = sourceFor(activeController?.packageName.orEmpty())

            val search = if (appId != null && prefs.showArtwork && source.usesITunes) {
                itunesClient.searchTrack(title, artist)
            } else null

            val now = System.currentTimeMillis()
            val startTs = if (prefs.showTimestamps && position >= 0) now - position else null
            val endTs = if (prefs.showTimestamps && duration > 0) (startTs ?: now) + duration else null

            // Discord refuses unregistered remote URLs for assets - they render as the
            // grey question mark. Register the iTunes cover first, then use "mp:...".
            val artwork = if (prefs.showArtwork) search?.artworkUrl else null
            val artworkRef = artwork?.let { url ->
                externalAssetClient.register(appId.orEmpty(), prefs.discordToken, url)
            } ?: artwork

            PresenceRuntime.log("artwork ref: ${artworkRef ?: "none"}")

            val buttons = buildList {
                if (appId != null && prefs.showButton) {
                    add("Get This Status" to REPO_URL)
                }
            }

            val payload = DiscordPayloads.createPresenceUpdate(
                title = title,
                artist = artist,
                album = album,
                startTimestamp = startTs,
                endTimestamp = endTs,
                artworkUrl = artworkRef,
                buttons = buttons,
                applicationId = appId,
                activityName = source.label,
                activityType = source.type
            )

            PresenceRuntime.updatePresence(payload)
            val label = if (artist.isNotBlank()) "$title \u2022 $artist" else title
            PresenceRuntime.setTrack(label)
            PresenceRuntime.log("Presence sent: $label")

            withContext(Dispatchers.Main) { updateNotification(label) }
        }
    }

    private fun clearPlaying() {
        debounceJob?.cancel()
        lastKey = ""
        PresenceRuntime.clearPresence()
        PresenceRuntime.setTrack(null)
        updateNotification(null)
    }

    private fun unbindActive() {
        runCatching { activeController?.unregisterCallback(mediaCallback) }
        activeController = null
    }

    private fun promoteToForeground() {
        try {
            val note = buildNotification(null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, note)
            }
        } catch (e: Exception) {
            PresenceRuntime.log("Foreground promotion failed: ${e.message}")
        }
    }

    private fun updateNotification(track: String?) {
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(track))
        }
    }

    private fun buildNotification(track: String?): Notification {
        val launchIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val text = track?.let { "Playing: $it" } ?: "Waiting for Apple Music playback"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_apple_music)
            .setContentTitle("Musicalified \u2022 RPC")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(launchIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playback Presence",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live Apple Music rich presence status"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsListener) }
        unbindActive()
        debounceJob?.cancel()
        scope.cancel()
        PresenceRuntime.log("Presence service stopped")
    }

    companion object {
        const val CHANNEL_ID = "musicalified_presence"
        const val NOTIFICATION_ID = 54321

        const val ACTION_CONFIG_CHANGED = "com.skyler.musicpresence.CONFIG_CHANGED"
        const val ACTION_FORCE_RECONNECT = "com.skyler.musicpresence.FORCE_RECONNECT"
        const val ACTION_RESCAN_SESSIONS = "com.skyler.musicpresence.RESCAN"
        const val ACTION_STOP_SERVICE = "com.skyler.musicpresence.STOP"

        const val REPO_URL = "https://github.com/skilerrr/musicalified-rpc"

        val SUPPORTED_PACKAGES = setOf(
            "com.apple.android.music",
            "com.apple.android.music.classical",
            "com.google.android.apps.youtube.music",
            "com.google.android.youtube"
        )

        /**
         * Per-app presentation: activity label + activity type
         * (2 = Listening to, 3 = Watching) + whether iTunes can resolve artwork.
         */
        data class Source(val label: String, val type: Int, val usesITunes: Boolean)

        fun sourceFor(packageName: String): Source = when {
            packageName.contains("classical") ->
                Source("Apple Music Classical", 2, true)
            packageName.contains("apple.android.music") ->
                Source("Apple Music", 2, true)
            packageName.contains("youtube.music") ->
                Source("YouTube Music", 2, false)
            packageName.contains("youtube") ->
                Source("YouTube", 3, false)
            else -> Source("Apple Music", 2, false)
        }
    }
}
