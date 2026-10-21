package com.skyler.musicpresence.core.discord

import android.util.LruCache
import com.skyler.musicpresence.core.runtime.PresenceRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Registers remote artwork with the Discord application so the client will render it.
 *
 * Discord does not load arbitrary https URLs for activity assets - an unregistered URL
 * shows the grey question-mark placeholder. The asset has to be registered first:
 *
 *   POST /applications/{app_id}/external-assets  {"urls": ["https://..."]}
 *   -> [{"external_asset_path": "external/xxxx/..."}]
 *
 * and the presence then references it as "mp:external/xxxx/...". This endpoint accepts a
 * user token (that is how the reference implementations do it), not just a bot token.
 */
class ExternalAssetClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()
) {
    private val cache = LruCache<String, String>(100)

    suspend fun register(appId: String, token: String, imageUrl: String): String? =
        withContext(Dispatchers.IO) {
            if (appId.isBlank() || token.isBlank() || imageUrl.isBlank()) return@withContext null
            cache.get(imageUrl)?.let { return@withContext it }

            val body = JSONObject()
                .put("urls", JSONArray().put(imageUrl))
                .toString()

            val request = Request.Builder()
                .url("https://discord.com/api/v10/applications/$appId/external-assets")
                .header("Authorization", token)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        PresenceRuntime.log("artwork register failed HTTP ${response.code}")
                        return@withContext null
                    }
                    val path = JSONArray(text).optJSONObject(0)
                        ?.optString("external_asset_path")
                        .orEmpty()
                    if (path.isBlank()) return@withContext null

                    val reference = if (path.startsWith("mp:")) path else "mp:$path"
                    cache.put(imageUrl, reference)
                    reference
                }
            } catch (e: Exception) {
                PresenceRuntime.log("artwork register error: ${e.javaClass.simpleName}")
                null
            }
        }
}
