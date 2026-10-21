package com.skyler.musicpresence.core.discord

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

enum class GatewayState { DISCONNECTED, CONNECTING, READY, FAILED }

/**
 * Minimal Discord Gateway (v10) presence client.
 * One socket, one heartbeat loop, event driven, no polling.
 */
class DiscordGateway(
    private val scope: CoroutineScope,
    private val onStateChanged: (GatewayState, String?) -> Unit,
    private val log: (String) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var socket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private val sequence = AtomicInteger(-1)

    private var currentToken: String? = null
    private var generation = 0
    private var attempt = 0

    @Volatile
    private var ready = false

    @Volatile
    private var shuttingDown = false

    fun isReady(): Boolean = ready

    fun connect(token: String) {
        if (token.isBlank()) {
            log("No token saved - cannot connect")
            return
        }
        if (ready && currentToken == token) return

        closeSocket(clearPresence = false)
        currentToken = token
        shuttingDown = false
        ready = false
        attempt = 0
        sequence.set(-1)
        openSocket()
    }

    fun disconnect() {
        shuttingDown = true
        ready = false
        reconnectJob?.cancel()
        reconnectJob = null
        closeSocket(clearPresence = true)
        currentToken = null
        onStateChanged(GatewayState.DISCONNECTED, "Stopped by user")
    }

    fun sendPresence(json: String) {
        if (!ready) {
            log("Presence skipped - gateway not READY yet")
            return
        }
        socket?.send(json)
    }

    fun clearPresence() {
        if (!ready) return
        socket?.send(DiscordPayloads.createClearPresence())
    }

    private fun openSocket() {
        val token = currentToken ?: return
        val gen = ++generation
        onStateChanged(GatewayState.CONNECTING, "Opening gateway socket")

        val request = Request.Builder()
            .url(GATEWAY_URL)
            .header("User-Agent", "Discord-Android/215000")
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (gen != generation) return
                log("Socket open (HTTP ${response.code})")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (gen != generation) return
                handleMessage(text, token)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                if (gen != generation) return
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (gen != generation) return
                ready = false
                heartbeatJob?.cancel()
                log("Socket closed: $code ${describeClose(code)} $reason".trim())
                if (shuttingDown) {
                    onStateChanged(GatewayState.DISCONNECTED, null)
                } else {
                    scheduleReconnect(code, reason)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (gen != generation) return
                ready = false
                heartbeatJob?.cancel()
                log("Socket error: ${t.message ?: t.javaClass.simpleName}")
                if (shuttingDown) {
                    onStateChanged(GatewayState.DISCONNECTED, t.message)
                } else {
                    scheduleReconnect(-1, t.message ?: "")
                }
            }
        })
    }

    private fun handleMessage(raw: String, token: String) {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val op = json.optInt("op", -1)
        if (json.has("s") && !json.isNull("s")) sequence.set(json.optInt("s", -1))

        when (op) {
            10 -> {
                val interval = json.optJSONObject("d")?.optLong("heartbeat_interval", 41250L) ?: 41250L
                log("Hello received (heartbeat ${interval}ms)")
                startHeartbeat(interval)
                socket?.send(DiscordPayloads.createIdentify(token))
                log("Identify sent")
            }
            0 -> {
                when (json.optString("t", "")) {
                    "READY" -> {
                        ready = true
                        attempt = 0
                        log("READY - gateway authenticated")
                        onStateChanged(GatewayState.READY, null)
                    }
                    "RESUMED" -> {
                        ready = true
                        log("Session resumed")
                        onStateChanged(GatewayState.READY, null)
                    }
                }
            }
            1 -> sendHeartbeat()
            7 -> {
                log("Gateway asked to reconnect")
                socket?.close(4000, "reconnect requested")
            }
            9 -> {
                log("Invalid session - re-identifying in 2s")
                scope.launch {
                    delay(2000)
                    socket?.send(DiscordPayloads.createIdentify(token))
                }
            }
            11 -> Unit
        }
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                if (ready) sendHeartbeat()
            }
        }
    }

    private fun sendHeartbeat() {
        val seq = sequence.get().takeIf { it >= 0 }
        socket?.send(DiscordPayloads.createHeartbeat(seq))
    }

    private fun closeSocket(clearPresence: Boolean) {
        if (clearPresence) {
            runCatching { socket?.send(DiscordPayloads.createClearPresence()) }
        }
        heartbeatJob?.cancel()
        heartbeatJob = null
        generation++
        runCatching { socket?.close(1000, "client shutdown") }
        socket = null
    }

    private fun scheduleReconnect(code: Int, reason: String) {
        if (code == 4004) {
            onStateChanged(GatewayState.FAILED, "4004 authentication failed - token invalid")
            log("FATAL: token rejected (4004). Fix token in app, then tap Reconnect.")
            return
        }
        if (code == 4013 || code == 4014) {
            onStateChanged(GatewayState.FAILED, "Closed ${code}: ${describeClose(code)}")
            return
        }
        attempt++
        val backoff = (5_000L * attempt).coerceAtMost(60_000L)
        onStateChanged(GatewayState.CONNECTING, "Reconnecting in ${backoff / 1000}s")
        log("Reconnect attempt $attempt in ${backoff / 1000}s (last: $code $reason)")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoff)
            if (!shuttingDown && !ready) openSocket()
        }
    }

    private fun describeClose(code: Int): String = when (code) {
        1000 -> "normal closure"
        1001 -> "going away"
        4000 -> "unknown error"
        4001 -> "unknown opcode"
        4002 -> "decode error"
        4003 -> "not authenticated"
        4004 -> "authentication failed"
        4005 -> "already authenticated"
        4007 -> "invalid seq"
        4008 -> "rate limited"
        4009 -> "session timed out"
        4010 -> "invalid shard"
        4011 -> "sharding required"
        4013 -> "invalid intents"
        4014 -> "disallowed intents"
        else -> ""
    }

    companion object {
        private const val GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"
    }
}
