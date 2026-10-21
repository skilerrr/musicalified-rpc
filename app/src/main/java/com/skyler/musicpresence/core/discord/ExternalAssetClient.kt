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

data class AssetRegistration(
    val reference: String?,
    val httpCode: Int,
    val detail: String
)

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

    /** Verbose variant: used by the in-app "Test Cover" button so failures are readable. */
    suspend fun registerVerbose(appId: String, token: String, imageUrl: String): AssetRegistration =
        withContext(Dispatchers.IO) {
            if (appId.isBlank()) return@withContext AssetRegistration(null, 0, "no application id")
            if (token.isBlank()) return@withContext AssetRegistration(null, 0, "no token")
            cache.get(imageUrl)?.let { return@withContext AssetRegistration(it, 200, "cached") }

            val body = JSONObject()
                .put("urls", JSONArray().put(imageUrl))
                .toString()

            val request = Request.Builder()
                .url("https://discord.com/api/v10/applications/$appId/external-assets")
                .header("Authorization", token)
                .header("User-Agent", "Discord-Android/2470")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext AssetRegistration(null, response.code, text.take(300))
                    }
                    val path = JSONArray(text).optJSONObject(0)
                        ?.optString("external_asset_path")
                        .orEmpty()
                    if (path.isBlank()) {
                        return@withContext AssetRegistration(null, response.code, text.take(300))
                    }
                    val reference = if (path.startsWith("mp:")) path else "mp:$path"
                    cache.put(imageUrl, reference)
                    AssetRegistration(reference, response.code, "ok")
                }
            } catch (e: Exception) {
                AssetRegistration(null, -1, "${e.javaClass.simpleName}: ${e.message}")
            }
        }

    suspend fun register(appId: String, token: String, imageUrl: String): String? {
        if (imageUrl.isBlank()) return null
        val result = registerVerbose(appId, token, imageUrl)
        if (result.reference == null) {
            PresenceRuntime.log("artwork HTTP ${result.httpCode}: ${result.detail.take(140)}")
        }
        return result.reference
    }
}
