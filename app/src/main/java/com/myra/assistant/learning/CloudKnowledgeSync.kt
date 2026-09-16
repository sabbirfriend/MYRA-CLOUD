package com.myra.assistant.learning

import android.content.Context
import com.myra.assistant.auth.MyraAuthManager
import com.myra.assistant.utils.Constants
import com.myra.assistant.utils.SecurePrefs
import com.myra.assistant.utils.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Optional cloud sync for approved, non-private MYRA knowledge only. */
object CloudKnowledgeSync {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    private fun base(context: Context): String = context.prefs().getString(Constants.KEY_CLOUD_KNOWLEDGE_URL, "").orEmpty().trim().trimEnd('/')
    private fun userId(context: Context): String = MyraAuthManager.userId(context)
    private fun auth(context: Context): String = SecurePrefs.get(context).getString(Constants.KEY_MYRA_SESSION_TOKEN, "").orEmpty()

    suspend fun publish(context: Context, items: List<LearningHub.Candidate>): Result<Int> = withContext(Dispatchers.IO) {
        val url = base(context); if (url.isBlank() || items.isEmpty()) return@withContext Result.success(0)
        require(MyraAuthManager.isSignedIn(context)) { "Sign in with Google is required for cloud sync" }
        val safe = items.take(20).map { JSONObject().put("topic", it.topic.take(300)).put("answer", it.answer.take(4000)).put("provider", it.provider.take(40)).put("timestamp", it.timestamp) }
        val body = JSONObject().put("user_id", userId(context)).put("items", JSONArray(safe)).toString()
        request(context, "/v1/knowledge/publish", body).map { JSONObject(it).optInt("accepted", safe.size) }
    }

    suspend fun sync(context: Context, limit: Int = 50): Result<List<LearningHub.Candidate>> = withContext(Dispatchers.IO) {
        val url = base(context); if (url.isBlank()) return@withContext Result.success(emptyList())
        require(MyraAuthManager.isSignedIn(context)) { "Sign in with Google is required for cloud sync" }
        request(context, "/v1/knowledge/sync?limit=${limit.coerceIn(1,100)}", null).map { raw ->
            val arr = JSONObject(raw).optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i -> val o=arr.optJSONObject(i) ?: return@mapNotNull null; LearningHub.Candidate(o.optString("provider","Cloud"),o.optString("topic"),o.optString("answer"),o.optLong("timestamp")) }
        }
    }

    private fun request(context: Context, path: String, payload: String?): Result<String> = runCatching {
        val b = Request.Builder().url(base(context) + path).header("X-MYRA-User", userId(context))
        val token = auth(context); if (token.isNotBlank()) b.header("Authorization", "Bearer $token")
        if (payload != null) b.post(payload.toRequestBody("application/json".toMediaType())) else b.get()
        client.newCall(b.build()).execute().use { r -> if (!r.isSuccessful) error("Cloud sync HTTP ${r.code}"); r.body?.string().orEmpty() }
    }
}
