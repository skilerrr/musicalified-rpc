package com.skyler.musicpresence.core.runtime

import android.util.Log
import com.skyler.musicpresence.core.discord.DiscordGateway
import com.skyler.musicpresence.core.discord.GatewayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide presence engine. Owns the gateway so the connection survives
 * Activity restarts and does not depend on the notification listener binding.
 */
object PresenceRuntime {

    enum class State { OFF, CONNECTING, READY, FAILED }

    /** One-line gateway summary for the in-app diagnostics dump. */
    fun describe(): String = state.name + (detail?.let { " - " + it } ?: "")

    data class Snapshot(
        val state: State,
        val detail: String?,
        val track: String?,
        val logs: List<String>
    )

    private const val TAG = "PresenceRuntime"
    private const val MAX_LOG_LINES = 14

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()
    private val logLines = Collections.synchronizedList(ArrayList<String>())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    private var state: State = State.OFF

    @Volatile
    private var detail: String? = null

    @Volatile
    private var track: String? = null

    @Volatile
    private var activeToken: String = ""

    /** Latest YouTube media id seen in a notification, paired with its title. */
    @Volatile
    var mediaVideoId: String? = null

    @Volatile
    var mediaVideoTitle: String? = null

    fun rememberVideoId(title: String?, videoId: String?) {
        if (videoId.isNullOrBlank()) return
        mediaVideoTitle = title
        mediaVideoId = videoId
    }

    fun videoIdFor(title: String): String? {
        val id = mediaVideoId ?: return null
        val known = mediaVideoTitle ?: return id
        val a = known.trim().lowercase()
        val b = title.trim().lowercase()
        return if (a == b || a.startsWith(b.take(12)) || b.startsWith(a.take(12))) id else null
    }

    @Volatile
    private var gateway: DiscordGateway? = null

    private fun gatewayOrCreate(): DiscordGateway {
        gateway?.let { return it }
        synchronized(this) {
            gateway?.let { return it }
            val created = DiscordGateway(
                scope = scope,
                onStateChanged = { newState, info -> onGatewayState(newState, info) },
                log = { line -> log(line) }
            )
            gateway = created
            return created
        }
    }

    fun snapshot(): Snapshot = Snapshot(state, detail, track, logLines.toList().takeLast(8))

    fun addListener(listener: (Snapshot) -> Unit) {
        listeners.add(listener)
        listener(snapshot())
    }

    fun removeListener(listener: (Snapshot) -> Unit) {
        listeners.remove(listener)
    }

    fun isReady(): Boolean = state == State.READY

    /** Connect only when needed; safe to call from Activity and Service. */
    fun ensureConnected(token: String, enabled: Boolean) {
        if (!enabled) {
            if (state != State.OFF) disconnect("Service disabled in app")
            return
        }
        if (token.isBlank()) {
            if (state != State.OFF) disconnect("No token saved")
            return
        }
        val gw = gatewayOrCreate()
        if (activeToken == token && gw.isReady()) return
        activeToken = token
        gw.connect(token)
    }

    /** Force a brand new session (new token, or user tapped Reconnect). */
    fun forceReconnect(token: String) {
        if (token.isBlank()) {
            log("Reconnect aborted - token is empty")
            onGatewayState(GatewayState.FAILED, "No token saved")
            return
        }
        activeToken = token
        val gw = gatewayOrCreate()
        gw.disconnect()
        log("Forcing new gateway session")
        gw.connect(token)
    }

    fun disconnect(reason: String? = null) {
        reason?.let { log(it) }
        activeToken = ""
        gateway?.disconnect()
        state = State.OFF
        detail = reason
        notifyListeners()
    }

    fun updatePresence(json: String) {
        gateway?.sendPresence(json)
    }

    fun clearPresence() {
        gateway?.clearPresence()
    }

    fun setTrack(nowPlaying: String?) {
        track = nowPlaying
        notifyListeners()
    }

    fun log(line: String) {
        val stamped = "${timeFormat.format(Date())}  $line"
        Log.d(TAG, line)
        synchronized(logLines) {
            logLines.add(stamped)
            while (logLines.size > MAX_LOG_LINES) logLines.removeAt(0)
        }
        notifyListeners()
    }

    private fun onGatewayState(newState: GatewayState, info: String?) {
        state = when (newState) {
            GatewayState.READY -> State.READY
            GatewayState.CONNECTING -> State.CONNECTING
            GatewayState.FAILED -> State.FAILED
            GatewayState.DISCONNECTED -> State.OFF
        }
        if (info != null) detail = info
        if (newState == GatewayState.READY) detail = null
        if (newState == GatewayState.DISCONNECTED) track = null
        notifyListeners()
    }

    private fun notifyListeners() {
        val snap = snapshot()
        for (listener in listeners) {
            runCatching { listener(snap) }
        }
    }
}
