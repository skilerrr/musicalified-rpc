package com.skyler.musicpresence.core.itunes

import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit

data class iTunesSearchResult(
    val artworkUrl: String,
    val trackViewUrl: String,
    val matchedArtist: String,
    val matchedTrack: String,
    val country: String
)

/**
 * iTunes Search API lookup with storefront fallback and match scoring.
 *
 * Two things the naive version got wrong:
 *  - the API defaults to the US storefront, so most Indonesian releases simply are not there
 *    (`resultCount: 0` for a song that is clearly on Apple Music). We try the device country
 *    first, then ID, then US.
 *  - `limit=1` returns whatever Apple thinks is most relevant, which can be a completely
 *    different artist. We ask for a batch and score every hit against our own metadata.
 *
 * Regex escapes are built from a character code (BACKSLASH) so this file carries no literal
 * backslash sequences.
 */
class iTunesSearchClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
) {
    private val cache = LruCache<String, iTunesSearchResult>(100)

    /** Storefronts to try, in order: the phone's own country first, then the usual suspects. */
    private val storefronts: List<String> by lazy {
        val device = runCatching { Locale.getDefault().country.uppercase(Locale.ROOT) }
            .getOrDefault("")
        listOf(device, "ID", "US")
            .filter { it.length == 2 }
            .distinct()
    }

    suspend fun searchTrack(
        title: String,
        artist: String,
        album: String = ""
    ): iTunesSearchResult? = withContext(Dispatchers.IO) {
        val cacheKey = listOf(title, artist, album).joinToString("|") { it.trim().lowercase() }
        cache.get(cacheKey)?.let { return@withContext it }

        for (country in storefronts) {
            val hit = searchInStore(country, title, artist, album)
            if (hit != null) {
                cache.put(cacheKey, hit)
                return@withContext hit
            }
        }
        null
    }

    private fun searchInStore(
        country: String,
        title: String,
        artist: String,
        album: String
    ): iTunesSearchResult? {
        return try {
            val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            val url = "https://itunes.apple.com/search?term=" + encodedQuery +
                "&entity=song&media=music&limit=15&country=" + country

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MusicPresenceAndroid/1.2")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                if (json.optInt("resultCount", 0) == 0) return null

                val results = json.getJSONArray("results")
                var best: iTunesSearchResult? = null
                var bestScore = Int.MIN_VALUE

                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val rawArtwork = item.optString("artworkUrl100", "")
                    if (rawArtwork.isBlank()) continue

                    val candidateScore = score(item, title, artist, album)
                    if (candidateScore > bestScore) {
                        bestScore = candidateScore
                        best = iTunesSearchResult(
                            // 100x100 thumbnail -> crisp 1000x1000 cover
                            artworkUrl = rawArtwork.replace("100x100bb.jpg", "1000x1000bb.jpg"),
                            trackViewUrl = item.optString("trackViewUrl", ""),
                            matchedArtist = item.optString("artistName", ""),
                            matchedTrack = item.optString("trackName", ""),
                            country = country
                        )
                    }
                }

                // Nothing matched closely enough: no cover beats the wrong cover.
                if (best != null && bestScore >= MIN_SCORE) best else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Weighted freshness of a candidate: artist identity first, then title, then album. */
    private fun score(item: JSONObject, title: String, artist: String, album: String): Int {
        var score = 0
        val wantedArtist = normalise(artist)
        val wantedTitle = normalise(title)
        val wantedAlbum = normalise(album)
        val foundArtist = normalise(item.optString("artistName"))
        val foundTitle = normalise(item.optString("trackName"))

        if (wantedArtist.isNotBlank()) {
            score += when {
                foundArtist == wantedArtist -> 60
                foundArtist.contains(wantedArtist) || wantedArtist.contains(foundArtist) -> 35
                else -> -25
            }
        }

        if (wantedTitle.isNotBlank()) {
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

        // Karaoke / instrumental / tribute re-releases share the title but not the cover.
        val noisy = foundTitle + " " + foundArtist
        if (NOISE_TERMS.any { noisy.contains(it) }) score -= 40

        return score
    }

    private fun normalise(value: String): String = value
        .lowercase()
        .replace(PARENTHESIS, " ")
        .replace(BRACKET, " ")
        .replace(NON_WORD, " ")
        .replace(WHITESPACE, " ")
        .trim()

    private companion object {
        const val MIN_SCORE = 40

        val BACKSLASH: String = 92.toChar().toString()
        val PARENTHESIS: Regex = Regex(BACKSLASH + "([^)]*" + BACKSLASH + ")")
        val BRACKET: Regex = Regex(BACKSLASH + "[[^]]*]")
        val WHITESPACE: Regex = Regex(BACKSLASH + "s+")
        val NON_WORD: Regex = Regex("[^a-z0-9 ]")

        val NOISE_TERMS = listOf("karaoke", "instrumental", "tribute", "made famous", "in the style of")
    }
}
