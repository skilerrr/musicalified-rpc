package com.skyler.musicpresence.core.itunes

import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class iTunesSearchResult(
    val artworkUrl: String,
    val trackViewUrl: String
)

class iTunesSearchClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
) {
    // Cache up to 100 resolved songs in memory (instant lookup when repeating songs)
    private val cache = LruCache<String, iTunesSearchResult>(100)

    suspend fun searchTrack(title: String, artist: String): iTunesSearchResult? = withContext(Dispatchers.IO) {
        val cacheKey = "${title.trim().lowercase()} - ${artist.trim().lowercase()}"
        cache.get(cacheKey)?.let { return@withContext it }

        try {
            val query = "$title $artist"
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            val url = "https://itunes.apple.com/search?term=$encodedQuery&entity=song&limit=1"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MusicPresenceAndroid/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val resultCount = json.optInt("resultCount", 0)
                if (resultCount == 0) return@withContext null

                val firstResult = json.getJSONArray("results").getJSONObject(0)
                val rawArtwork = firstResult.optString("artworkUrl100", "")
                val trackViewUrl = firstResult.optString("trackViewUrl", "")

                // Convert 100x100 thumbnail to crisp 1000x1000 HD cover
                val highResArtwork = if (rawArtwork.isNotEmpty()) {
                    rawArtwork.replace("100x100bb.jpg", "1000x1000bb.jpg")
                } else {
                    ""
                }

                if (highResArtwork.isNotEmpty() || trackViewUrl.isNotEmpty()) {
                    val result = iTunesSearchResult(
                        artworkUrl = highResArtwork,
                        trackViewUrl = trackViewUrl
                    )
                    cache.put(cacheKey, result)
                    return@withContext result
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }
}
