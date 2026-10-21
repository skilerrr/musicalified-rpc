package com.skyler.musicpresence.core.youtube

import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class YouTubeMatch(
    val videoId: String,
    val thumbnailUrl: String,
    val watchUrl: String
)

/**
 * Resolves a YouTube video id from title + channel by querying the public results page
 * (no API key, no OAuth). Used only when the media session does not expose a video id -
 * YouTube's own player usually does not, YouTube Music often does.
 */
class YouTubeResolver(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) {
    private val cache = LruCache<String, YouTubeMatch>(100)

    private val videoIdPattern = Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"")

    suspend fun resolve(title: String, channel: String): YouTubeMatch? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        val key = "${title.trim().lowercase()}|${channel.trim().lowercase()}"
        cache.get(key)?.let { return@withContext it }

        val query = listOf(title, channel).filter { it.isNotBlank() }.joinToString(" ")
        val url = "https://www.youtube.com/results?search_query=" +
            URLEncoder.encode(query, StandardCharsets.UTF_8.name()) + "&hl=en&gl=US"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val html = response.body?.string().orEmpty()
                val videoId = videoIdPattern.find(html)?.groupValues?.getOrNull(1)
                    ?: return@withContext null
                val match = YouTubeMatch(
                    videoId = videoId,
                    thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                    watchUrl = "https://youtu.be/$videoId"
                )
                cache.put(key, match)
                match
            }
        } catch (e: Exception) {
            null
        }
    }

    /** oEmbed lookup - cheap way to confirm a video id is playable. */
    suspend fun titleFor(videoId: String): String? = withContext(Dispatchers.IO) {
        val url = "https://www.youtube.com/oembed?url=https://youtu.be/$videoId&format=json"
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                JSONObject(response.body?.string().orEmpty()).optString("title").ifBlank { null }
            }
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
