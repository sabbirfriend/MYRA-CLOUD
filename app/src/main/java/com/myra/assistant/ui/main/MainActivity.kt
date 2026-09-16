package com.myra.assistant.ui.main

import android.app.ActivityManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.*
import android.os.storage.StorageManager
import android.os.StatFs
import android.view.WindowManager
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.myra.assistant.R
import com.myra.assistant.auth.LoginActivity
import com.myra.assistant.auth.MyraAuthManager
import com.myra.assistant.ai.AutonomousAgent
import com.myra.assistant.ai.SmartAgentEngine
import com.myra.assistant.ai.LearningEngine
import com.myra.assistant.ai.ConversationMemory
import com.myra.assistant.ai.AgentOrchestrator
import com.myra.assistant.learning.LearningHub
import com.myra.assistant.offline.ModelDownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.myra.assistant.security.BiometricManager
import com.myra.assistant.service.AccessibilityHelperService
import com.myra.assistant.services.ForegroundVoiceService
import com.myra.assistant.ui.settings.SettingsActivity
import com.myra.assistant.ui.main.AppsActivity
import com.myra.assistant.ui.main.ChatsActivity
import com.myra.assistant.ui.main.VoiceSettingsActivity
import com.myra.assistant.utils.Constants
import com.myra.assistant.utils.Logger
import com.myra.assistant.utils.PermissionUtils
import com.myra.assistant.utils.TorchController
import com.myra.assistant.apps.AppLauncher
import android.net.Uri
import android.app.Activity
import com.myra.assistant.utils.prefs
import com.myra.assistant.viewmodel.MainViewModel
import com.myra.assistant.voice.VoiceStateManager
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private val TAG = "MAIN"
    private val viewModel: MainViewModel by viewModels()

    private lateinit var orbView: OrbAnimationView
    private lateinit var waveformView: WaveformView
    private lateinit var statusText: TextView
    private lateinit var micButton: ImageButton
    private lateinit var chatRecycler: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var batteryText: TextView
    private lateinit var ramText: TextView
    private lateinit var timeText: TextView

    private val timeHandler = Handler(Looper.getMainLooper())
    private val timeRunnable = object : Runnable {
        override fun run() {
            updateSystemInfo()
            timeHandler.postDelayed(this, 1000)
        }
    }

    private val responseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra("text") ?: return
            addBotMessage(text)
        }
    }

    companion object {
        const val PERM_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.TRANSPARENT
        setContentView(R.layout.activity_main)

        initViews()
        updateSystemInfo()
        updateConnectivityLabel()
        timeHandler.post(timeRunnable)

        if (!MyraAuthManager.isSignedIn(this)) {
            startActivityForResult(Intent(this, LoginActivity::class.java), 8401)
        } else {
            authenticateAndSetup()
        }
    }

    private fun authenticateAndSetup() {
        BiometricManager.authenticate(
            this,
            onSuccess = { setupAssistant() },
            onError = { setupAssistant() },
            onFallback = { setupAssistant() }
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 8401) {
            if (MyraAuthManager.isSignedIn(this)) authenticateAndSetup() else finish()
        }
    }

    private fun setupAssistant() {
        SmartAgentEngine.start(this)
        LearningEngine.initialize(this)
        ConversationMemory.initialize(this)
        // Restore the authenticated identity into the shared runtime bridge before modules start.
        MyraAuthManager.restoreState(this)
        if (prefs().getBoolean(Constants.KEY_CLOUD_SYNC_ENABLED, false) && prefs().getString(Constants.KEY_CLOUD_KNOWLEDGE_URL, "").orEmpty().isNotBlank()) {
            CoroutineScope(Dispatchers.IO).launch { LearningHub.syncCloud(this@MainActivity) }
        }
        AgentOrchestrator.start(this)
        autoPrepareOfflineModel()
        if (prefs().getBoolean(Constants.KEY_AUTONOMOUS_MODE, false)) {
            AutonomousAgent.start(this)
        }
        observeViewModel()
        observeVoiceState()
        if (PermissionUtils.hasMicPermission(this)) {
            startVoiceService()
            startMyraOverlayIfAllowed()
        } else {
            PermissionUtils.requestMissing(this, PERM_CODE)
            addBotMessage("🎙️ Microphone permission is required for MYRA voice.")
        }
        checkAccessibility()
    }


    private fun startMyraOverlayIfAllowed() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
            android.provider.Settings.canDrawOverlays(this)) {
            try {
                startService(Intent(this, com.myra.assistant.service.MyraOverlayService::class.java).apply {
                    action = Constants.ACTION_SHOW_OVERLAY
                })
            } catch (e: Exception) {
                Logger.e(TAG, "Overlay start failed: ${e.message}")
            }
        }
    }

    private fun autoPrepareOfflineModel() {
        val spec = ModelDownloadManager.recommended(this)
        if (spec == null) {
            statusText.text = "Offline AI: device memory too low for the supported models"
            return
        }
        if (ModelDownloadManager.isComplete(this, spec)) {
            statusText.text = "MYRA Offline AI ready • ${spec.name}"
            return
        }
        if (!ModelDownloadManager.isAutoDownloadEnabled(this)) return
        if (!ModelDownloadManager.hasNetwork(this)) {
            statusText.text = "Offline AI waiting for internet to download ${ModelDownloadManager.format(spec.bytes)}"
            return
        }
        val free = StatFs(filesDir.absolutePath).availableBytes
        if (free < spec.bytes + 700L * 1024L * 1024L) {
            statusText.text = "Need ${ModelDownloadManager.format(spec.bytes + 700L * 1024L * 1024L)} free storage for Offline AI"
            return
        }
        showModelDownloadDialog(spec)
    }

    private fun showModelDownloadDialog(spec: ModelDownloadManager.ModelSpec) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 8)
        }
        val info = TextView(this).apply {
            text = "${spec.name}\n${ModelDownloadManager.format(spec.bytes)} • on-device • one-time download"
            textSize = 14f
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            isIndeterminate = false
        }
        val detail = TextView(this).apply { text = "Preparing…"; textSize = 13f }
        box.addView(info)
        box.addView(progress, LinearLayout.LayoutParams(-1, 28))
        box.addView(detail)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("MYRA AI Brain")
            .setView(box)
            .setNegativeButton("Later") { d, _ -> d.dismiss() }
            .create()
        dialog.setOnShowListener { dialog.setCanceledOnTouchOutside(false) }
        dialog.show()

        CoroutineScope(Dispatchers.IO).launch {
            val result = ModelDownloadManager.download(this@MainActivity, spec) { state ->
                runOnUiThread {
                    val percent = if (state.total > 0) ((state.downloaded * 1000L) / state.total).toInt().coerceIn(0, 1000) else 0
                    progress.progress = percent
                    val speed = ModelDownloadManager.format(state.speedBytesPerSec) + "/s"
                    detail.text = "${ModelDownloadManager.format(state.downloaded)} / ${ModelDownloadManager.format(state.total)} • $speed"
                }
            }
            runOnUiThread {
                result.onSuccess {
                    progress.progress = 1000
                    detail.text = "✓ MYRA Offline AI is ready"
                    statusText.text = "Offline AI ready • ${spec.name}"
                    Handler(Looper.getMainLooper()).postDelayed({ if (dialog.isShowing) dialog.dismiss() }, 1200)
                }.onFailure { error ->
                    detail.text = "Download failed: ${error.message ?: "unknown error"}"
                    statusText.text = "Offline AI download failed — retry from Settings"
                }
            }
        }
    }

    private fun initViews() {
        orbView     = findViewById(R.id.orbView)
        waveformView = findViewById(R.id.waveformView)
        statusText  = findViewById(R.id.statusText)
        micButton   = findViewById(R.id.micButton)
        chatRecycler = findViewById(R.id.chatRecycler)
        batteryText = findViewById(R.id.batteryText)
        ramText     = findViewById(R.id.ramText)
        timeText    = findViewById(R.id.timeText)

        chatAdapter = ChatAdapter()
        chatRecycler.layoutManager = LinearLayoutManager(this).also { it.stackFromEnd = true }
        chatRecycler.adapter = chatAdapter

        micButton.setOnClickListener {
            val svc = ForegroundVoiceService.instance
            if (svc == null) {
                if (startVoiceService()) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        ForegroundVoiceService.instance?.startListeningNow()
                    }, 500L)
                }
            } else {
                svc.startListeningNow()
                statusText.text = "Listening… speak now"
            }
        }

        micButton.setOnLongClickListener {
            ForegroundVoiceService.instance?.stopListeningNow()
            statusText.text = "Say Hey MYRA"
            true
        }

        findViewById<ImageButton>(R.id.settingsBtn).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.quickYoutubeBtn).setOnClickListener { openYouTube() }
        findViewById<Button>(R.id.quickCameraBtn).setOnClickListener {
            try {
                startActivity(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE))
            } catch (_: Exception) {
                Toast.makeText(this, "Camera app not available", Toast.LENGTH_SHORT).show()
            }
        }
        val torchButton = findViewById<Button>(R.id.quickTorchBtn)
        TorchController.syncFromHardware(this)
        refreshTorchButton(torchButton)
        torchButton.setOnClickListener {
            val target = !TorchController.isOn()
            if (TorchController.set(this, target)) {
                refreshTorchButton(torchButton)
                statusText.text = if (target) "Torch ON" else "Torch OFF"
            } else Toast.makeText(this, "Flashlight unavailable or camera permission is required", Toast.LENGTH_SHORT).show()
        }

        findViewById<TextView>(R.id.navHome).setOnClickListener {
            pulseNav(it)
        }
        findViewById<TextView>(R.id.navChats).setOnClickListener {
            pulseNav(it)
            startActivity(Intent(this, ChatsActivity::class.java))
        }
        findViewById<TextView>(R.id.navVoice).setOnClickListener {
            pulseNav(it)
            startActivity(Intent(this, VoiceSettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.navApps).setOnClickListener {
            pulseNav(it)
            startActivity(Intent(this, AppsActivity::class.java))
        }
        findViewById<TextView>(R.id.navMore).setOnClickListener {
            pulseNav(it)
            startActivity(Intent(this, ModulesActivity::class.java))
        }
    }

    private fun refreshTorchButton(button: Button) {
        button.text = if (TorchController.isOn()) "☼ Torch ON" else "☼ Torch OFF"
    }

    private fun openYouTube() {
        try {
            if (AppLauncher.launch(this, "YouTube")) {
                statusText.text = "YouTube opened"
                return
            }
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")))
            statusText.text = "YouTube opened in browser"
        } catch (_: Exception) {
            Toast.makeText(this, "YouTube could not be opened", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pulseNav(view: android.view.View) {
        view.animate().scaleX(1.08f).scaleY(1.08f).setDuration(90).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }.start()
    }

    private fun observeVoiceState() {
        VoiceStateManager.state.observe(this) { state ->
            when (state) {
                Constants.STATE_LISTENING -> {
                    orbView.setListening()
                    micButton.setImageResource(R.drawable.ic_mic_on)
                    statusText.setTextColor(0xFF00E5FF.toInt())
                }
                Constants.STATE_THINKING -> {
                    orbView.setThinking()
                    statusText.setTextColor(0xFFD500F9.toInt())
                }
                Constants.STATE_SPEAKING -> {
                    orbView.setSpeaking()
                    micButton.setImageResource(R.drawable.ic_mic_off)
                    statusText.setTextColor(0xFFFF1744.toInt())
                }
                else -> {
                    orbView.setIdle()
                    micButton.setImageResource(R.drawable.ic_mic_off)
                    statusText.setTextColor(0xFFFF1744.toInt())
                }
            }
        }
        VoiceStateManager.amplitude.observe(this) { amp ->
            waveformView.updateAmplitude(amp)
            orbView.setAmplitude(amp)
        }
        VoiceStateManager.statusMessage.observe(this) { msg ->
            statusText.text = msg
        }
    }

    private fun observeViewModel() {
        viewModel.aiResponse.observe(this) { text ->
            if (!text.isNullOrBlank()) addBotMessage(text)
        }
    }

    private fun startVoiceService(): Boolean {
        if (ForegroundVoiceService.startIfReady(this)) {
            Logger.d(TAG, "Voice service started")
            addBotMessage("🎙️ MYRA is ready. Say ‘Hey MYRA’. ")
            return true
        }
        addBotMessage("⚠️ Microphone permission is required before starting MYRA voice.")
        return false
    }

    private fun checkAccessibility() {
        if (!AccessibilityHelperService.isEnabled(this)) {
            addBotMessage("⚠️ Enable Accessibility Service for app control. Settings → Accessibility.")
        }
    }

    fun addUserMessage(text: String) = runOnUiThread {
        chatAdapter.addMessage(ChatMessage(text, true))
        chatRecycler.scrollToPosition(chatAdapter.itemCount - 1)
    }

    fun addBotMessage(text: String) = runOnUiThread {
        chatAdapter.addMessage(ChatMessage(text, false))
        chatRecycler.scrollToPosition(chatAdapter.itemCount - 1)
    }

    private fun updateConnectivityLabel() {
        val online = try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val n = cm.activeNetwork
            val caps = n?.let { cm.getNetworkCapabilities(it) }
            caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) { false }
        findViewById<TextView>(R.id.aiModeText)?.text = if (online) "AI ASSISTANT • ONLINE" else "AI ASSISTANT • OFFLINE"
    }

    private fun updateSystemInfo() {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        timeText.text = sdf.format(Date())
        val bm = getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        batteryText.text = "${bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0}%"
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        ramText.text = "${(mi.totalMem - mi.availMem) / 1048576}MB"
    }

    override fun onResume() {
        super.onResume()
        updateConnectivityLabel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(responseReceiver, IntentFilter("MYRA_RESPONSE"), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(responseReceiver, IntentFilter("MYRA_RESPONSE"))
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(responseReceiver) } catch (e: Exception) { android.util.Log.d("MYRA_Main", "Response receiver already unregistered", e) }
    }

    override fun onDestroy() {
        SmartAgentEngine.stop()
        AgentOrchestrator.stop()
        ConversationMemory.persist(this)
        timeHandler.removeCallbacks(timeRunnable)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_CODE && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startVoiceService()
        }
    }
}
