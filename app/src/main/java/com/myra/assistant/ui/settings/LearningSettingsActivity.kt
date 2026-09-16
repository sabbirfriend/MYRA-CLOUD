package com.myra.assistant.ui.settings

import android.os.Bundle
import android.graphics.Color
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.myra.assistant.auth.MyraAuthManager
import com.myra.assistant.learning.LearningHub
import com.myra.assistant.learning.LearningScheduler
import com.myra.assistant.utils.Constants
import com.myra.assistant.utils.SecurePrefs
import com.myra.assistant.utils.prefs
import kotlinx.coroutines.*

class LearningSettingsActivity : AppCompatActivity() {
    private lateinit var enabled: Switch
    private lateinit var gemini: Switch
    private lateinit var openai: Switch
    private lateinit var grok: Switch
    private lateinit var autoApprove: Switch
    private lateinit var topic: EditText
    private lateinit var status: TextView
    private lateinit var cloudSync: Switch
    private lateinit var cloudUrl: EditText
    private lateinit var cloudToken: EditText
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); buildUi(); load() }
    private fun buildUi() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28,28,28,28); setBackgroundColor(Color.rgb(3,8,19)) }
        fun title(t:String)=TextView(this).apply{ text=t; textColor=Color.WHITE; textSize=22f; setPadding(0,0,0,20) }
        box.addView(title("MYRA Learning Core"))
        status=TextView(this).apply{textColor=Color.CYAN; textSize=13f}; box.addView(status)
        enabled=Switch(this).apply{text="Enable background learning"; setTextColor(Color.WHITE)}; box.addView(enabled)
        gemini=Switch(this).apply{text="Learn from Gemini"; setTextColor(Color.WHITE)}; box.addView(gemini)
        openai=Switch(this).apply{text="Learn from ChatGPT"; setTextColor(Color.WHITE)}; box.addView(openai)
        grok=Switch(this).apply{text="Learn from Grok"; setTextColor(Color.WHITE)}; box.addView(grok)
        autoApprove=Switch(this).apply{text="Auto-approve factual notes"; setTextColor(Color.WHITE)}; box.addView(autoApprove)
        cloudSync=Switch(this).apply{text="Sync approved knowledge to MYRA Cloud"; setTextColor(Color.WHITE)}; box.addView(cloudSync)
        cloudUrl=EditText(this).apply{hint="Cloud knowledge API URL (optional)"; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE); inputType=1}; box.addView(cloudUrl)
        cloudToken=EditText(this).apply{hint="Cloud session token is managed by Google Login"; setHintTextColor(Color.GRAY); setTextColor(Color.LTGRAY); isEnabled=false}; box.addView(cloudToken)
        topic=EditText(this).apply{hint="Learning topic"; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE)}; box.addView(topic)
        val openaiKey=EditText(this).apply{hint="OpenAI API key (optional)"; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE); inputType=0x81}; box.addView(openaiKey)
        val xaiKey=EditText(this).apply{hint="xAI/Grok API key (optional)"; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE); inputType=0x81}; box.addView(xaiKey)
        val save=Button(this).apply{text="SAVE & APPLY"}; box.addView(save)
        val learn=Button(this).apply{text="LEARN NOW"}; box.addView(learn)
        val approve=Button(this).apply{text="APPROVE PENDING NOTES"}; box.addView(approve)
        val clear=Button(this).apply{text="CLEAR LEARNED KNOWLEDGE"}; box.addView(clear)
        save.setOnClickListener {
            prefs().edit().putBoolean(Constants.KEY_LEARNING_ENABLED,enabled.isChecked).putBoolean(Constants.KEY_LEARNING_GEMINI,gemini.isChecked).putBoolean(Constants.KEY_LEARNING_OPENAI,openai.isChecked).putBoolean(Constants.KEY_LEARNING_GROK,grok.isChecked).putBoolean(Constants.KEY_LEARNING_AUTO_APPROVE,autoApprove.isChecked).putString("learning_topic",topic.text.toString().trim()).putBoolean(Constants.KEY_CLOUD_SYNC_ENABLED,cloudSync.isChecked).putString(Constants.KEY_CLOUD_KNOWLEDGE_URL,cloudUrl.text.toString().trim()).apply()
            SecurePrefs.get(this).edit().putString(Constants.KEY_OPENAI_API_KEY,openaiKey.text.toString().trim()).putString(Constants.KEY_XAI_API_KEY,xaiKey.text.toString().trim()).apply()
            LearningScheduler.apply(this); status.text="Learning settings applied"; Toast.makeText(this,"MYRA Learning updated",Toast.LENGTH_SHORT).show()
        }
        learn.setOnClickListener { scope.launch { status.text="Learning…"; val r=withContext(Dispatchers.IO){LearningHub.learn(this@LearningSettingsActivity,topic.text.toString(),true)}; status.text=r.fold({"Learned ${it} knowledge note(s)"},{"Learning failed: ${it.message}"}) } }
        val sync=Button(this).apply{text="SYNC CLOUD KNOWLEDGE"}; box.addView(sync)
        sync.setOnClickListener { scope.launch { status.text="Syncing…"; val r=LearningHub.syncCloud(this@LearningSettingsActivity); status.text=r.fold({"Synced $it shared note(s)"},{"Cloud sync failed: ${it.message}"}) } }
        approve.setOnClickListener { val n=LearningHub.approvePending(this); status.text="Approved $n pending note(s)" }
        clear.setOnClickListener { LearningHub.clear(this); status.text="Learned knowledge cleared" }
        setContentView(ScrollView(this).apply{ addView(box) })
    }
    private fun load(){ val p=prefs(); enabled.isChecked=p.getBoolean(Constants.KEY_LEARNING_ENABLED,false); gemini.isChecked=p.getBoolean(Constants.KEY_LEARNING_GEMINI,true); openai.isChecked=p.getBoolean(Constants.KEY_LEARNING_OPENAI,false); grok.isChecked=p.getBoolean(Constants.KEY_LEARNING_GROK,false); autoApprove.isChecked=p.getBoolean(Constants.KEY_LEARNING_AUTO_APPROVE,false); cloudSync.isChecked=p.getBoolean(Constants.KEY_CLOUD_SYNC_ENABLED,false); cloudUrl.setText(p.getString(Constants.KEY_CLOUD_KNOWLEDGE_URL,"")); cloudToken.setText(if (MyraAuthManager.isSignedIn(this)) "Google account connected: ${MyraAuthManager.email(this).orEmpty()}" else "Not signed in") topic.setText(p.getString("learning_topic","AI, Android, technology and MYRA capabilities")); status.text="Saved: ${LearningHub.knowledge(this).size} • Pending: ${LearningHub.pending(this).size}" }
    override fun onDestroy(){scope.cancel();super.onDestroy()}
}
