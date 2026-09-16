package com.myra.assistant.auth

import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.myra.assistant.R
import com.myra.assistant.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var status: TextView
    private lateinit var signIn: Button
    private lateinit var credentialManager: CredentialManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        credentialManager = CredentialManager.create(this)
        buildUi()
        if (MyraAuthManager.isSignedIn(this)) finish()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(48,48,48,48)
            setBackgroundColor(Color.rgb(3,8,19))
        }
        val title = TextView(this).apply { text = "MYRA"; textSize = 40f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        val subtitle = TextView(this).apply { text = "Sign in with Google to use MYRA Cloud"; textSize = 16f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER; setPadding(0,16,0,28) }
        signIn = Button(this).apply { text = "Continue with Google"; isAllCaps = false }
        status = TextView(this).apply { textSize = 13f; setTextColor(Color.CYAN); gravity = Gravity.CENTER; setPadding(0,24,0,0) }
        root.addView(title); root.addView(subtitle); root.addView(signIn); root.addView(status)
        setContentView(root)
        signIn.setOnClickListener { signInWithGoogle() }
    }

    private fun signInWithGoogle() {
        val webClientId = getString(R.string.google_web_client_id)
        if (webClientId.startsWith("YOUR_")) {
            status.text = "Google login setup incomplete: add your Web Client ID in res/values/strings.xml"
            return
        }
        signIn.isEnabled = false; status.text = "Opening Google…"
        scope.launch {
            try {
                val option = GetSignInWithGoogleOption.Builder(webClientId).setNonce(MyraAuthManager.secureNonce()).build()
                val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                val result = credentialManager.getCredential(this@LoginActivity, request)
                val credential = result.credential
                if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    error("Unexpected Google credential")
                }
                val google = GoogleIdTokenCredential.createFrom(credential.data)
                status.text = "Verifying account…"
                val session = MyraAuthManager.exchangeGoogleToken(this@LoginActivity, google.idToken).getOrThrow()
                status.text = "Signed in as ${session.displayName ?: session.email ?: "Google user"}"
                setResult(RESULT_OK); finish()
            } catch (e: GetCredentialException) {
                status.text = "Google sign-in cancelled or unavailable"
                signIn.isEnabled = true
            } catch (e: Exception) {
                status.text = e.message ?: "Google sign-in failed"
                signIn.isEnabled = true
            }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
