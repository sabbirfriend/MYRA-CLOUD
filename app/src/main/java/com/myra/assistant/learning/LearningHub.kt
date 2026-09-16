package com.myra.assistant.learning

import android.content.Context
import com.myra.assistant.utils.Constants
import com.myra.assistant.utils.SecurePrefs
import com.myra.assistant.utils.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Controlled MYRA learning layer. It learns knowledge summaries, not provider model weights.
 * Nothing is executed as an Android command just because a provider suggested it.
 */
object LearningHub {
    data class Candidate(val provider: String, val topic: String, val answer: String, val timestamp: Long)

    private const val KEY_KNOWLEDGE = "myra_learning_knowledge_v1"
    private const val KEY_PENDING = "myra_learning_pending_v1"
    private const val MAX_ITEMS = 120
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS).build()

    suspend fun learn(context: Context, topic: String, force: Boolean = false): Result<Int> = withContext(Dispatchers.IO) {
        val clean = topic.trim().take(500)
        if (clean.isBlank()) return@withContext Result.failure(IllegalArgumentException("Topic is empty"))
        val prefs = context.prefs()
        if (!force && !prefs.getBoolean(Constants.KEY_LEARNING_ENABLED, false)) {
            return@withContext Result.failure(IllegalStateException("Learning is disabled"))
        }
        val candidates = mutableListOf<Candidate>()
        if (prefs.getBoolean(Constants.KEY_LEARNING_GEMINI, true)) queryGemini(context, clean)?.let { candidates += it }
        if (prefs.getBoolean(Constants.KEY_LEARNING_OPENAI, false)) queryOpenAi(context, clean)?.let { candidates += it }
        if (prefs.getBoolean(Constants.KEY_LEARNING_GROK, false)) queryGrok(context, clean)?.let { candidates += it }
        if (candidates.isEmpty()) return@withContext Result.failure(IllegalStateException("No learning provider returned a result"))

        val auto = prefs.getBoolean(Constants.KEY_LEARNING_AUTO_APPROVE, false)
        if (!auto) { savePending(context, candidates); return@withContext Result.success(0) }
        save(context, candidates)
        prefs.edit().putLong(Constants.KEY_LEARNING_LAST_RUN, System.currentTimeMillis()).apply()
        if (prefs.getBoolean(Constants.KEY_CLOUD_SYNC_ENABLED, false)) { CloudKnowledgeSync.publish(context, candidates) }
        Result.success(candidates.size)
    }

    fun knowledge(context: Context): List<Candidate> = read(context, KEY_KNOWLEDGE)

    private fun read(context: Context, key: String): List<Candidate> {
        val raw = context.prefs().getString(key, "[]").orEmpty()
        return runCatching {
            val a = org.json.JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                Candidate(o.optString("provider"), o.optString("topic"), o.optString("answer"), o.optLong("timestamp"))
            }
        }.getOrDefault(emptyList())
    }

    fun pending(context: Context): List<Candidate> = read(context, KEY_PENDING)

    fun approvePending(context: Context): Int { val items = pending(context); if (items.isEmpty()) return 0; save(context, items); context.prefs().edit().remove(KEY_PENDING).apply(); return items.size }

    suspend fun syncCloud(context: Context): Result<Int> = CloudKnowledgeSync.sync(context).map { items -> if (items.isNotEmpty()) save(context, items); items.size }

    fun clear(context: Context) { context.prefs().edit().remove(KEY_KNOWLEDGE).remove(KEY_PENDING).apply() }

    fun contextSnippet(context: Context, topic: String, max: Int = 4): String =
        knowledge(context).asReversed().filter { it.topic.contains(topic, true) || topic.contains(it.topic, true) }
            .take(max).joinToString("\n") { "[${it.provider}] ${it.answer.take(700)}" }

    private fun savePending(context: Context, items: List<Candidate>) {
        val merged = (pending(context) + items).takeLast(MAX_ITEMS)
        val a = org.json.JSONArray(); merged.forEach { c -> a.put(JSONObject().apply { put("provider", c.provider); put("topic", c.topic); put("answer", c.answer); put("timestamp", c.timestamp) }) }
        context.prefs().edit().putString(KEY_PENDING, a.toString()).apply()
    }

    private fun save(context: Context, items: List<Candidate>) {
        val merged = (knowledge(context) + items).takeLast(MAX_ITEMS)
        val a = org.json.JSONArray()
        merged.forEach { c -> a.put(JSONObject().apply { put("provider", c.provider); put("topic", c.topic); put("answer", c.answer); put("timestamp", c.timestamp) }) }
        context.prefs().edit().putString(KEY_KNOWLEDGE, a.toString()).apply()
    }

    private fun queryGemini(context: Context, topic: String): Candidate? {
        val key = SecurePrefs.get(context).getString(Constants.KEY_API_KEY, "").orEmpty()
        if (key.isBlank()) return null
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$key"
        val prompt = learningPrompt(topic)
        val body = JSONObject().put("contents", org.json.JSONArray().put(JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", prompt))))).toString()
        val req = Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).build()
        return runCatching {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@runCatching null
                val o = JSONObject(r.body?.string().orEmpty())
                val text = o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text").orEmpty()
                text.takeIf { it.isNotBlank() }?.let { Candidate("Gemini", topic, it, System.currentTimeMillis()) }
            }
        }.getOrNull()
    }

    private fun queryOpenAi(context: Context, topic: String): Candidate? {
        val key = SecurePrefs.get(context).getString(Constants.KEY_OPENAI_API_KEY, "").orEmpty()
        if (key.isBlank()) return null
        val model = context.prefs().getString(Constants.KEY_OPENAI_MODEL, "gpt-5-mini").orEmpty()
        val body = JSONObject().put("model", model).put("input", learningPrompt(topic)).toString()
        val req = Request.Builder().url("https://api.openai.com/v1/responses").header("Authorization", "Bearer $key").post(body.toRequestBody("application/json".toMediaType())).build()
        return runCatching {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@runCatching null
                val o = JSONObject(r.body?.string().orEmpty())
                val text = extractResponseText(o)
                text.takeIf { it.isNotBlank() }?.let { Candidate("ChatGPT", topic, it, System.currentTimeMillis()) }
            }
        }.getOrNull()
    }

    private fun queryGrok(context: Context, topic: String): Candidate? {
        val key = SecurePrefs.get(context).getString(Constants.KEY_XAI_API_KEY, "").orEmpty()
        if (key.isBlank()) return null
        val model = context.prefs().getString(Constants.KEY_XAI_MODEL, "grok-4.6").orEmpty()
        val messages = org.json.JSONArray()
            .put(JSONObject().put("role", "system").put("content", "Return concise factual learning notes for MYRA. Do not provide executable commands."))
            .put(JSONObject().put("role", "user").put("content", topic))
        val body = JSONObject().put("model", model).put("messages", messages).put("stream", false).toString()
        val req = Request.Builder().url("https://api.x.ai/v1/chat/completions").header("Authorization", "Bearer $key").post(body.toRequestBody("application/json".toMediaType())).build()
        return runCatching {
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@runCatching null
                val o = JSONObject(r.body?.string().orEmpty())
                val text = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                text.takeIf { it.isNotBlank() }?.let { Candidate("Grok", topic, it, System.currentTimeMillis()) }
            }
        }.getOrNull()
    }

    private fun extractResponseText(o: JSONObject): String {
        val direct = o.optString("output_text")
        if (direct.isNotBlank()) return direct
        val output = o.optJSONArray("output") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val c = content.optJSONObject(j) ?: continue
                val t = c.optString("text")
                if (t.isNotBlank()) sb.append(t)
            }
        }
        return sb.toString()
    }

    private fun learningPrompt(topic: String) = """
        You are a teacher helping MYRA build a local knowledge base.
        Topic: $topic
        Give a concise, factual Bengali-first knowledge note. Clearly mention uncertainty.
        Do not output executable commands, Android permissions, secrets, API keys, or instructions to modify code.
    """.trimIndent()
}
