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
                val candidates = candidates(html)
                if (candidates.isEmpty()) return@withContext null

                // First result is often a reaction, a lyric re-upload or a completely different
                // song with a similar title. Pick the candidate whose own title overlaps most.
                val scored = candidates.map { it to score(it, title, channel) }
                val best = scored.maxByOrNull { it.second }
                val videoId = when {
                    best != null && best.second >= MIN_SCORE -> best.first.videoId
                    else -> candidates.first().videoId
                }

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

    private data class Candidate(val videoId: String, val title: String, val owner: String)

    /** Each search result lives inside one `videoRenderer` object: split on it and read the ids. */
    private fun candidates(html: String): List<Candidate> {
        val out = ArrayList<Candidate>()
        var index = html.indexOf("\"videoRenderer\":")
        while (index >= 0 && out.size < 15) {
            val nextIndex = html.indexOf("\"videoRenderer\":", index + 16)
            val end = if (nextIndex >= 0) nextIndex else minOf(html.length, index + 8000)
            val chunk = html.substring(index, end)
            val videoId = videoIdPattern.find(chunk)?.groupValues?.getOrNull(1)
            if (videoId != null) {
                out.add(
                    Candidate(
                        videoId = videoId,
                        title = textAfter(chunk, TITLE_MARKER),
                        owner = textAfter(chunk, OWNER_MARKER)
                    )
                )
            }
            index = nextIndex
        }
        return out
    }

    /** 0..100: how much of the wanted title appears in the candidate's own title. */
    private fun score(candidate: Candidate, title: String, channel: String): Int {
        val wanted = tokens(title)
        if (wanted.isEmpty()) return 0
        val found = tokens(candidate.title)
        val overlap = wanted.count { word -> found.any { it == word || it.contains(word) || word.contains(it) } }
        var score = (overlap * 100) / wanted.size

        val wantedChannel = tokens(channel)
        if (wantedChannel.isNotEmpty()) {
            val foundOwner = tokens(candidate.owner)
            if (foundOwner.any { owner -> wantedChannel.any { owner.contains(it) || it.contains(owner) } }) {
                score += 20
            }
        }
        return score
    }

    private fun tokens(value: String): List<String> = value
        .lowercase()
        .replace(Regex("[^a-z0-9 ]"), " ")
        .split(' ')
        .filter { it.length > 2 && it !in STOP_WORDS }

    /** Reads the JSON string value that follows [marker], honouring backslash escapes. */
    private fun textAfter(chunk: String, marker: String): String {
        val at = chunk.indexOf(marker)
        if (at < 0) return ""
        val builder = StringBuilder()
        var i = at + marker.length
        while (i < chunk.length) {
            val c = chunk[i]
            if (c == '\' && i + 1 < chunk.length) {
                builder.append(c).append(chunk[i + 1])
                i += 2
                continue
            }
            if (c == '"') break
            builder.append(c)
            i++
        }
        return unescape(builder.toString())
    }

    private fun unescape(value: String): String = value
        .replace("\\u0026", "&")
        .replace("\\\"", "\"")
        .replace("\\/", "/")

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

        const val TITLE_MARKER = "\"title\":{\"runs\":[{\"text\":\""
        const val OWNER_MARKER = "\"ownerText\":{\"runs\":[{\"text\":\""

        /** Below this overlap the search page probably has nothing better than its first hit. */
        const val MIN_SCORE = 45

        /** Noise that appears in nearly every YouTube title, useless for matching. */
        val STOP_WORDS = setOf(
            "the", "and", "official", "video", "music", "audio", "lyric", "lyrics",
            "with", "from", "full", "song", "feat", "ft", "hd", "hq", "4k", "mv",
            "yang", "dan", "untuk", "dengan", "dari", "ini", "itu", "aku", "kamu"
        )
    }
}
