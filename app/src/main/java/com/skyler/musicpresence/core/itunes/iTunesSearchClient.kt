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

    suspend fun searchTrack(
        title: String,
        artist: String,
        album: String = ""
    ): iTunesSearchResult? = withContext(Dispatchers.IO) {
        val cacheKey = listOf(title, artist, album).joinToString("|") { it.trim().lowercase() }
        cache.get(cacheKey)?.let { return@withContext it }

        try {
            val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            val url =
                "https://itunes.apple.com/search?term=$encodedQuery&entity=song&media=music&limit=15"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MusicPresenceAndroid/1.1")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val resultCount = json.optInt("resultCount", 0)
                if (resultCount == 0) return@withContext null

                val results = json.getJSONArray("results")
                var best: iTunesSearchResult? = null
                var bestScore = Int.MIN_VALUE

                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val rawArtwork = item.optString("artworkUrl100", "")
                    if (rawArtwork.isBlank()) continue

                    val score = score(item, title, artist, album)
                    if (score > bestScore) {
                        bestScore = score
                        best = iTunesSearchResult(
                            // Convert 100x100 thumbnail to crisp 1000x1000 HD cover
                            artworkUrl = rawArtwork.replace("100x100bb.jpg", "1000x1000bb.jpg"),
                            trackViewUrl = item.optString("trackViewUrl", "")
                        )
                    }
                }

                // Nothing matched closely enough: no cover beats the wrong cover.
                if (best != null && bestScore >= MIN_SCORE) {
                    cache.put(cacheKey, best)
                    return@withContext best
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }

    /** Weighted freshness of a candidate: artist identity matters most, then title, then album. */
    private fun score(item: JSONObject, title: String, artist: String, album: String): Int {
        var score = 0
        val wantedArtist = normalise(artist)
        val wantedTitle = normalise(title)
        val wantedAlbum = normalise(album)

        if (wantedArtist.isNotBlank()) {
            val foundArtist = normalise(item.optString("artistName"))
            score += when {
                foundArtist == wantedArtist -> 60
                foundArtist.contains(wantedArtist) || wantedArtist.contains(foundArtist) -> 35
                else -> -25
            }
        }

        if (wantedTitle.isNotBlank()) {
            val foundTitle = normalise(item.optString("trackName"))
            score += when {
                foundTitle == wantedTitle -> 50
                foundTitle.contains(wantedTitle) || wantedTitle.contains(foundTitle) -> 25
                else -> -10
            }
        }

        if (wantedAlbum.isNotBlank()) {
            val foundAlbum = normalise(item.optString("collectionName"))
            score += when {
                foundAlbum == wantedAlbum -> 15
                foundAlbum.contains(wantedAlbum) || wantedAlbum.contains(foundAlbum) -> 8
                else -> 0
            }
        }
        return score
    }

    private fun normalise(value: String): String = value
        .lowercase()
        .replace(Regex("\\([^)]*\\)"), " ")
        .replace(Regex("\\[[^]]*]"), " ")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private companion object {
        const val MIN_SCORE = 40
    }
}
