package com.skyler.musicpresence.core.discord

import org.json.JSONArray
import org.json.JSONObject

object DiscordPayloads {

    fun createHeartbeat(sequence: Int?): String {
        val json = JSONObject()
        json.put("op", 1)
        json.put("d", sequence ?: JSONObject.NULL)
        return json.toString()
    }

    fun createIdentify(token: String): String {
        val root = JSONObject()
        root.put("op", 2)

        val d = JSONObject()
        d.put("token", token)

        val properties = JSONObject()
        properties.put("os", "Android")
        properties.put("browser", "Discord Android")
        properties.put("device", "Android")

        d.put("properties", properties)
        root.put("d", d)
        return root.toString()
    }

    fun createPresenceUpdate(
        applicationId: String,
        title: String,
        artist: String,
        album: String,
        startTimestamp: Long?,
        endTimestamp: Long?,
        artworkUrl: String?,
        songUrl: String?,
        showButtons: Boolean
    ): String {
        val root = JSONObject()
        root.put("op", 3)

        val d = JSONObject()
        d.put("since", System.currentTimeMillis())
        d.put("status", "online")
        d.put("afk", false)

        val activity = JSONObject()
        activity.put("name", "Apple Music")
        activity.put("type", 2) // 2 = Listening to
        activity.put("details", title)
        activity.put("state", artist)
        activity.put("application_id", applicationId)

        if (startTimestamp != null || endTimestamp != null) {
            val timestamps = JSONObject()
            if (startTimestamp != null) timestamps.put("start", startTimestamp)
            if (endTimestamp != null) timestamps.put("end", endTimestamp)
            activity.put("timestamps", timestamps)
        }

        val assets = JSONObject()
        assets.put("large_image", artworkUrl?.takeIf { it.isNotBlank() } ?: "apple_music")
        assets.put("large_text", album.ifBlank { "Apple Music" })
        assets.put("small_image", "apple_music")
        assets.put("small_text", "Apple Music")
        activity.put("assets", assets)

        if (showButtons && !songUrl.isNullOrBlank()) {
            val buttons = JSONArray()
            buttons.put("Listen on Apple Music")
            activity.put("buttons", buttons)

            val metadata = JSONObject()
            val buttonUrls = JSONArray()
            buttonUrls.put(songUrl)
            metadata.put("button_urls", buttonUrls)
            activity.put("metadata", metadata)
        }

        val activities = JSONArray()
        activities.put(activity)
        d.put("activities", activities)

        root.put("d", d)
        return root.toString()
    }

    fun createClearPresence(): String {
        val root = JSONObject()
        root.put("op", 3)

        val d = JSONObject()
        d.put("since", 0)
        d.put("status", "online")
        d.put("afk", false)
        d.put("activities", JSONArray())

        root.put("d", d)
        return root.toString()
    }
}
