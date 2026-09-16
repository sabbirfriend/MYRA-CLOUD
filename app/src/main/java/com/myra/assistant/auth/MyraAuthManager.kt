package com.myra.assistant.auth

import android.content.Context
import android.util.Base64
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
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** Google identity + MYRA cloud session. Google ID tokens are never stored as app sessions. */
object MyraAuthManager {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    data class Session(val userId: String, val email: String?, val displayName: String?, val isAdmin: Boolean)

    fun isSignedIn(context: Context): Boolean = SecurePrefs.get(context).getString(Constants.KEY_MYRA_SESSION_TOKEN, "").orEmpty().isNotBlank() && userId(context).isNotBlank()
    fun userId(context: Context): String = prefs(context).getString(Constants.KEY_CLOUD_USER_ID, "").orEmpty()
    fun email(context: Context): String? = SecurePrefs.get(context).getString(Constants.KEY_MYRA_EMAIL, null)
    fun displayName(context: Context): String = prefs(context).getString(Constants.KEY_MYRA_DISPLAY_NAME, "").orEmpty()
    fun isAdmin(context: Context): Boolean = prefs(context).getBoolean(Constants.KEY_MYRA_IS_ADMIN, false)

    fun secureNonce(): String { val bytes=ByteArray(32); SecureRandom().nextBytes(bytes); return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING) }

    fun restoreState(context: Context) {
        val signed = isSignedIn(context)
        AuthStateBridge.publish(AuthStateBridge.State(signed, userId(context), email(context).orEmpty(), displayName(context), isAdmin(context)))
    }

    suspend fun exchangeGoogleToken(context: Context, googleIdToken: String): Result<Session> = withContext(Dispatchers.IO) {
        runCatching {
            val base=prefs(context).getString(Constants.KEY_CLOUD_KNOWLEDGE_URL, "").orEmpty().trim().trimEnd('/')
            require(base.isNotBlank()) { "MYRA Cloud URL is not configured" }
            val body=JSONObject().put("id_token", googleIdToken).toString()
            val req=Request.Builder().url("$base/v1/auth/google").post(body.toRequestBody("application/json".toMediaType())).build()
            client.newCall(req).execute().use { response ->
                val raw=response.body?.string().orEmpty()
                if(!response.isSuccessful) error(JSONObject(raw.ifBlank { "{}" }).optString("error", "Google authentication failed"))
                val json=JSONObject(raw)
                val session=json.optString("session_token").takeIf{it.isNotBlank()} ?: error("Cloud did not return a session")
                val uid=json.optString("user_id").takeIf{it.isNotBlank()} ?: error("Cloud did not return user id")
                val mail=json.optString("email").takeIf{it.isNotBlank()}
                val name=json.optString("display_name").takeIf{it.isNotBlank()}
                val admin=json.optBoolean("is_admin",false)
                SecurePrefs.get(context).edit().putString(Constants.KEY_MYRA_SESSION_TOKEN,session).putString(Constants.KEY_MYRA_EMAIL,mail).apply()
                prefs(context).edit().putString(Constants.KEY_CLOUD_USER_ID,uid).putString(Constants.KEY_MYRA_DISPLAY_NAME,name.orEmpty()).putBoolean(Constants.KEY_MYRA_IS_ADMIN,admin).apply()
                AuthStateBridge.publish(AuthStateBridge.State(true,uid,mail.orEmpty(),name.orEmpty(),admin))
                Session(uid,mail,name,admin)
            }
        }
    }

    fun signOut(context: Context) {
        SecurePrefs.get(context).edit().remove(Constants.KEY_MYRA_SESSION_TOKEN).remove(Constants.KEY_MYRA_EMAIL).apply()
        prefs(context).edit().remove(Constants.KEY_CLOUD_USER_ID).remove(Constants.KEY_MYRA_IS_ADMIN).remove(Constants.KEY_MYRA_DISPLAY_NAME).apply()
        AuthStateBridge.publish(AuthStateBridge.State(false))
    }
    private fun prefs(context: Context)=context.prefs()
}
