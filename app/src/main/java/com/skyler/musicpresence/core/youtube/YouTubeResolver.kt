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
 *
 * Escapes are built from character codes (QUOTE / BACKSLASH) so no literal backslashes appear
 * in this file: the payload we parse is JSON embedded in HTML and the source stays readable.
 */
class YouTubeResolver(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) {
    private val cache = LruCache<String, YouTubeMatch>(100)

    suspend fun resolve(title: String, channel: String): YouTubeMatch? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        val key = title.trim().lowercase() + "|" + channel.trim().lowercase()
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

                // The first hit is often a reaction, a lyric re-upload, or a different song with
                // a similar title. Pick the candidate whose own title overlaps ours the most.
                val scored = candidates.map { it to score(it, title, channel) }
                val best = scored.maxByOrNull { it.second }
                val videoId = if (best != null && best.second >= MIN_SCORE) {
                    best.first.videoId
                } else {
                    candidates.first().videoId
                }

                val match = YouTubeMatch(
                    videoId = videoId,
                    thumbnailUrl = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg",
                    watchUrl = "https://youtu.be/" + videoId
                )
                cache.put(key, match)
                match
            }
        } catch (e: Exception) {
            null
        }
    }

    private data class Candidate(val videoId: String, val title: String, val owner: String)

    /** Each result lives inside one videoRenderer object: split on it, then read its fields. */
    private fun candidates(html: String): List<Candidate> {
        val out = ArrayList<Candidate>()
        var index = html.indexOf(VIDEO_RENDERER)
        while (index >= 0 && out.size < MAX_CANDIDATES) {
            val nextIndex = html.indexOf(VIDEO_RENDERER, index + VIDEO_RENDERER.length)
            val end = if (nextIndex >= 0) nextIndex else minOf(html.length, index + 8000)
            val chunk = html.substring(index, end)
            val videoId = videoIdFrom(chunk)
            if (videoId != null) {
                out.add(
                    Candidate(
                        videoId = videoId,
                        title = fieldText(chunk, TITLE_MARKER),
                        owner = fieldText(chunk, OWNER_MARKER)
                    )
                )
            }
            index = nextIndex
        }
        return out
    }

    private fun videoIdFrom(chunk: String): String? {
        val at = chunk.indexOf(VIDEO_ID_MARKER)
        if (at < 0) return null
        val start = at + VIDEO_ID_MARKER.length
        val stop = chunk.indexOf(QUOTE_CHAR, start)
        if (stop < 0) return null
        val id = chunk.substring(start, stop)
        return if (id.length == 11) id else null
    }

    /** Reads the JSON string value that follows [marker]; escape sequences are kept verbatim. */
    private fun fieldText(chunk: String, marker: String): String {
        val at = chunk.indexOf(marker)
        if (at < 0) return ""
        val builder = StringBuilder()
        var i = at + marker.length
        while (i < chunk.length) {
            val c = chunk[i]
            if (c == BACKSLASH && i + 1 < chunk.length) {
                builder.append(c).append(chunk[i + 1])
                i += 2
                continue
            }
            if (c == QUOTE_CHAR) break
            builder.append(c)
            i++
        }
        return unescape(builder.toString())
    }

    /** 0..120: share of our title tokens found in the candidate, plus a channel-match bonus. */
    private fun score(candidate: Candidate, title: String, channel: String): Int {
        val wanted = tokens(title)
        if (wanted.isEmpty()) return 0
        val found = tokens(candidate.title)
        val overlap = wanted.count { word ->
            found.any { it == word || it.contains(word) || word.contains(it) }
        }
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
        .replace(NON_WORD, " ")
        .split(' ')
        .filter { it.length > 2 && it !in STOP_WORDS }

    private fun unescape(value: String): String = value
        .replace(BACKSLASH_S + "u0026", "&")
        .replace(BACKSLASH_S + QUOTE_S, QUOTE_S)
        .replace(BACKSLASH_S + "/", "/")

    /** oEmbed lookup - cheap way to confirm a video id is playable. */
    suspend fun titleFor(videoId: String): String? = withContext(Dispatchers.IO) {
        val url = "https://www.youtube.com/oembed?url=https://youtu.be/" + videoId + "&format=json"
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

        const val MAX_CANDIDATES = 15

        /** Below this overlap the page probably has nothing better than its first hit. */
        const val MIN_SCORE = 45

        val QUOTE_CHAR: Char = 34.toChar()
        val BACKSLASH: Char = 92.toChar()
        val QUOTE_S: String = QUOTE_CHAR.toString()
        val BACKSLASH_S: String = BACKSLASH.toString()

        val VIDEO_RENDERER: String = QUOTE_S + "videoRenderer" + QUOTE_S + ":"
        val VIDEO_ID_MARKER: String = QUOTE_S + "videoId" + QUOTE_S + ":" + QUOTE_S
        val TITLE_MARKER: String =
            QUOTE_S + "title" + QUOTE_S + ":{" + QUOTE_S + "runs" + QUOTE_S + ":[{" +
                QUOTE_S + "text" + QUOTE_S + ":" + QUOTE_S
        val OWNER_MARKER: String =
            QUOTE_S + "ownerText" + QUOTE_S + ":{" + QUOTE_S + "runs" + QUOTE_S + ":[{" +
                QUOTE_S + "text" + QUOTE_S + ":" + QUOTE_S

        val NON_WORD: Regex = Regex("[^a-z0-9 ]")

        /** Noise that shows up in nearly every YouTube title, useless for matching. */
        val STOP_WORDS = setOf(
            "the", "and", "official", "video", "music", "audio", "lyric", "lyrics",
            "with", "from", "full", "song", "feat", "ft", "hd", "hq", "4k", "mv",
            "yang", "dan", "untuk", "dengan", "dari", "ini", "itu", "aku", "kamu"
        )
    }
}
