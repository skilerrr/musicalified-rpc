package com.skyler.musicpresence.core.discord

import org.json.JSONArray
import org.json.JSONObject

object DiscordPayloads {

    private const val MAX_FIELD = 128

    private fun String.cap() = if (length > MAX_FIELD) substring(0, MAX_FIELD - 1) + "\u2026" else this

    fun createHeartbeat(sequence: Int?): String {
        val root = JSONObject()
        root.put("op", 1)
        root.put("d", sequence ?: JSONObject.NULL)
        return root.toString()
    }

    /**
     * Identify for a user (non-bot) session. No `intents` field - bots only.
     * capabilities/compress/largeThreshold mirror what a real client sends.
     */
    fun createIdentify(token: String): String {
        val d = JSONObject()
        d.put("token", token)
        d.put("capabilities", 65)
        d.put("compress", false)
        d.put("largeThreshold", 100)

        val properties = JSONObject()
        properties.put("os", "Android")
        properties.put("browser", "Discord Android")
        properties.put("device", "Android")
        d.put("properties", properties)

        val root = JSONObject()
        root.put("op", 2)
        root.put("d", d)
        return root.toString()
    }

    fun createPresenceUpdate(
        title: String,
        artist: String,
        album: String,
        startTimestamp: Long?,
        endTimestamp: Long?,
        artworkUrl: String?,
        songUrl: String?,
        showButtons: Boolean,
        applicationId: String?
    ): String {
        val activity = JSONObject()
        activity.put("name", "Apple Music")
        activity.put("type", 2) // 2 = Listening to
        activity.put("details", title.cap())
        if (artist.isNotBlank()) activity.put("state", artist.cap())

        if (startTimestamp != null) {
            val timestamps = JSONObject()
            timestamps.put("start", startTimestamp)
            if (endTimestamp != null && endTimestamp > startTimestamp) {
                timestamps.put("end", endTimestamp)
            }
            activity.put("timestamps", timestamps)
        }

        val appId = applicationId?.trim().orEmpty()
        val hasApp = appId.isNotEmpty()
        if (hasApp) activity.put("application_id", appId)

        // Assets + buttons only resolve against a real Discord application.
        if (hasApp) {
            if (!artworkUrl.isNullOrBlank()) {
                val assets = JSONObject()
                assets.put("large_image", artworkUrl)
                assets.put("large_text", album.ifBlank { "Apple Music" }.cap())
                activity.put("assets", assets)
            }
            if (showButtons && !songUrl.isNullOrBlank()) {
                val buttons = JSONArray()
                buttons.put("Listen on Apple Music")
                activity.put("buttons", buttons)

                val metadata = JSONObject()
                val urls = JSONArray()
                urls.put(songUrl)
                metadata.put("button_urls", urls)
                activity.put("metadata", metadata)
            }
        }

        val activities = JSONArray()
        activities.put(activity)

        val d = JSONObject()
        d.put("since", 0)
        d.put("status", "online")
        d.put("afk", false)
        d.put("activities", activities)

        val root = JSONObject()
        root.put("op", 3)
        root.put("d", d)
        return root.toString()
    }

    fun createClearPresence(): String {
        val d = JSONObject()
        d.put("since", 0)
        d.put("status", "online")
        d.put("afk", false)
        d.put("activities", JSONArray())

        val root = JSONObject()
        root.put("op", 3)
        root.put("d", d)
        return root.toString()
    }
}
