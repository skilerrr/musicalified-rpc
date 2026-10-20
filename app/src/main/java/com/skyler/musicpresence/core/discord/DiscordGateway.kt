package com.skyler.musicpresence.core.discord

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DiscordGateway(
    private val scope: CoroutineScope,
    private val onConnectionStateChanged: (Boolean) -> Unit = {}
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Indefinite for WebSocket
        .build()

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private val isConnected = AtomicBoolean(false)
    private val lastSequence = AtomicInteger(-1)
    private var currentToken: String? = null
    private var isIntentionalClose = false

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            Log.d(TAG, "Gateway socket opened")
        }

        override fun onMessage(ws: WebSocket, text: String) {
            handleGatewayMessage(text)
        }

        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "Gateway closing: $code / $reason")
            ws.close(code, reason)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "Gateway closed: $code / $reason")
            cleanupConnection()
            if (!isIntentionalClose && !currentToken.isNullOrBlank()) {
                scheduleReconnect()
            }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Gateway error: ${t.message}", t)
            cleanupConnection()
            if (!isIntentionalClose && !currentToken.isNullOrBlank()) {
                scheduleReconnect()
            }
        }
    }

    fun connect(token: String) {
        if (token.isBlank()) {
            Log.w(TAG, "Cannot connect: Token is empty")
            return
        }
        if (isConnected.get() && currentToken == token) {
            return
        }

        disconnect()
        currentToken = token
        isIntentionalClose = false

        val request = Request.Builder()
            .url(GATEWAY_URL)
            .header("User-Agent", "Discord-Android/200000")
            .build()

        webSocket = client.newWebSocket(request, wsListener)
    }

    fun disconnect() {
        isIntentionalClose = true
        clearPresence()
        cleanupConnection()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        currentToken = null
    }

    fun updatePresence(payload: String) {
        if (!isConnected.get()) {
            Log.w(TAG, "Presence update queued/skipped: Gateway not ready yet")
            return
        }
        webSocket?.send(payload)
    }

    fun clearPresence() {
        if (isConnected.get()) {
            webSocket?.send(DiscordPayloads.createClearPresence())
        }
    }

    private fun handleGatewayMessage(raw: String) {
        try {
            val json = JSONObject(raw)
            val op = json.optInt("op", -1)
            val s = json.optInt("s", -1)
            if (s >= 0) lastSequence.set(s)

            when (op) {
                10 -> { // Opcode 10 Hello
                    val d = json.getJSONObject("d")
                    val interval = d.getLong("heartbeat_interval")
                    Log.d(TAG, "Received Hello, interval: $interval ms")
                    startHeartbeat(interval)

                    // Immediately identify
                    currentToken?.let { token ->
                        val identify = DiscordPayloads.createIdentify(token)
                        webSocket?.send(identify)
                    }
                }
                0 -> { // Dispatch
                    val t = json.optString("t", "")
                    if (t == "READY") {
                        Log.i(TAG, "Gateway is READY! Identified successfully.")
                        isConnected.set(true)
                        onConnectionStateChanged(true)
                    }
                }
                1 -> { // Discord requesting immediate heartbeat
                    sendHeartbeat()
                }
                11 -> { // Heartbeat ACK
                    Log.v(TAG, "Heartbeat ACK received")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing gateway message: ${e.message}")
        }
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(intervalMs)
                sendHeartbeat()
            }
        }
    }

    private fun sendHeartbeat() {
        val seq = lastSequence.get().takeIf { it >= 0 }
        val payload = DiscordPayloads.createHeartbeat(seq)
        webSocket?.send(payload)
    }

    private fun cleanupConnection() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        val wasConnected = isConnected.getAndSet(false)
        if (wasConnected) {
            onConnectionStateChanged(false)
        }
    }

    private fun scheduleReconnect() {
        scope.launch(Dispatchers.IO) {
            delay(5000)
            currentToken?.let { token ->
                if (!isConnected.get()) {
                    Log.d(TAG, "Attempting reconnect to Gateway...")
                    connect(token)
                }
            }
        }
    }

    companion object {
        private const val TAG = "DiscordGateway"
        private const val GATEWAY_URL = "wss://gateway.discord.gg/?v=9&encoding=json"
    }
}
