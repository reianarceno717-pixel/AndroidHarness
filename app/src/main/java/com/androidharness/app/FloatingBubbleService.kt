package com.androidharness.app

import android.annotation.SuppressLint
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

class FloatingBubbleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager

    private var bubbleView: View? = null
    private var overlayView: View? = null

    private var bubbleParams: WindowManager.LayoutParams? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private val conversationHistory = mutableListOf<Pair<String, String>>()
    private var assistantBusy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeService = this

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        initTts()
        createBubbleView()
    }

    private fun initTts() {
        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                isTtsReady = true
            }
        }
    }

    private fun speakText(text: String) {
        if (!isTtsReady || tts == null) return
        val clean = text.replace(Regex("[*#`_]"), "").take(450)
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "bubble-speech")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createBubbleView() {
        val sizeDp = 56
        val sizePx = (sizeDp * resources.displayMetrics.density).toInt()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        bubbleParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 200
        }

        val bubble = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding((8 * resources.displayMetrics.density).toInt(), (8 * resources.displayMetrics.density).toInt(),
                (8 * resources.displayMetrics.density).toInt(), (8 * resources.displayMetrics.density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0F172A"))
                setStroke((2 * resources.displayMetrics.density).toInt(), Color.parseColor("#38BDF8"))
            }
            elevation = 16f
            contentDescription = "AndroidHarness assistant"
        }

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        bubble.setOnTouchListener { _, event ->
            val params = bubbleParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(bubble, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val diffX = abs(event.rawX - touchX)
                    val diffY = abs(event.rawY - touchY)
                    if (diffX < 15 && diffY < 15) {
                        toggleOverlay()
                    }
                    true
                }
                else -> false
            }
        }

        bubbleView = bubble
        windowManager.addView(bubble, bubbleParams)
    }

    private fun toggleOverlay() {
        if (overlayView != null) {
            hideOverlay()
        } else {
            showOverlay()
        }
    }

    private fun hideOverlay() {
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
        }
    }

    private fun showOverlay() {
        if (overlayView != null) return

        val width = (resources.displayMetrics.widthPixels * 0.90).toInt().coerceAtMost(
            (420 * resources.displayMetrics.density).toInt()
        )
        val height = (resources.displayMetrics.heightPixels * 0.55).toInt().coerceAtMost(
            (520 * resources.displayMetrics.density).toInt()
        )

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        overlayParams = WindowManager.LayoutParams(
            width,
            height,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val density = resources.displayMetrics.density
        val dp = { value: Int -> (value * density).toInt() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#0F172A"))
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            elevation = 24f
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "AndroidHarness Assistant  ·  drag to move"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 14f
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        var dragStartX = 0
        var dragStartY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        title.setOnTouchListener { _, event ->
            val params = overlayParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = params.x
                    dragStartY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = dragStartX + (event.rawX - touchStartX).toInt()
                    params.y = dragStartY + (event.rawY - touchStartY).toInt()
                    windowManager.updateViewLayout(root, params)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        val closeBtn = Button(this).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setOnClickListener { hideOverlay() }
        }
        header.addView(title)
        header.addView(closeBtn)
        root.addView(header)

        // Chat display area
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { topMargin = dp(8); bottomMargin = dp(8) }
        }
        val messageBox = TextView(this).apply {
            text = "Ask anything with voice or text, or tap 'What's on screen?' to analyze whatever you are viewing!"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 13f
            setLineSpacing(0f, 1.35f)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#1E293B"))
            }
        }
        scroll.addView(messageBox)
        root.addView(scroll)

        // Actions row
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        val screenBtn = Button(this).apply {
            text = "📱 What's on screen?"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#334155"))
            }
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                val prompt = if (!clip.isNullOrBlank()) {
                    "Here is the text currently copied from my screen:\n$clip\nPlease summarize and explain this."
                } else {
                    "What's on my screen? Please provide a helpful answer or guide for the current task."
                }
                askAssistant(prompt, messageBox)
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(6)
            }
        }

        val voiceBtn = Button(this).apply {
            text = "🎙️ Voice"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#2563EB"))
            }
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                startVoiceInput(messageBox)
            }
        }
        actionsRow.addView(screenBtn)
        actionsRow.addView(voiceBtn)
        root.addView(actionsRow)

        // Input row
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(this).apply {
            hint = "Ask or type task..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 13f
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#1E293B"))
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(6)
            }
        }
        val sendBtn = Button(this).apply {
            text = "Send"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#2563EB"))
            }
            setOnClickListener {
                val q = input.text.toString().trim()
                if (q.isNotEmpty()) {
                    input.setText("")
                    askAssistant(q, messageBox)
                }
            }
        }
        inputRow.addView(input)
        inputRow.addView(sendBtn)
        root.addView(inputRow)

        val resizeHandle = TextView(this).apply {
            text = "↘ Resize"
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(4), dp(2))
        }
        var resizeStartX = 0f
        var resizeStartY = 0f
        var resizeInitialWidth = width
        var resizeInitialHeight = height
        resizeHandle.setOnTouchListener { _, event ->
            val params = overlayParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    resizeStartX = event.rawX
                    resizeStartY = event.rawY
                    resizeInitialWidth = params.width
                    resizeInitialHeight = params.height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val minWidth = dp(260)
                    val minHeight = dp(240)
                    val maxWidth = (resources.displayMetrics.widthPixels * 0.96f).toInt()
                    val maxHeight = (resources.displayMetrics.heightPixels * 0.85f).toInt()
                    params.width = (resizeInitialWidth + event.rawX - resizeStartX).toInt().coerceIn(minWidth, maxWidth)
                    params.height = (resizeInitialHeight + event.rawY - resizeStartY).toInt().coerceIn(minHeight, maxHeight)
                    windowManager.updateViewLayout(root, params)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        root.addView(resizeHandle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(28)
        ))

        overlayView = root
        windowManager.addView(root, overlayParams)
        scope.launch {
            val shared = withContext(Dispatchers.IO) { loadSharedChatHistory() }
            if (shared != null) {
                conversationHistory.clear()
                for (i in 0 until shared.length()) {
                    val item = shared.optJSONObject(i) ?: continue
                    val role = item.optString("role")
                    val text = item.optString("content")
                    if ((role == "user" || role == "assistant") && text.isNotBlank()) {
                        conversationHistory += role to text
                    }
                }
                messageBox.text = conversationHistory.takeLast(8).joinToString("\\n\\n") { (role, text) ->
                    "${if (role == "user") "You" else "AI"}: $text"
                }.ifBlank { "Ask anything with voice or text, or tap 'What's on screen?' to analyze whatever you are viewing!" }
            }
        }
    }

    private fun loadSharedChatHistory(): org.json.JSONArray? {
        return runCatching {
            val url = java.net.URL("http://127.0.0.1:${LaptopConnectionActivity.activePort}/api/chat/history")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", LaptopConnectionActivity.localBridgeAuthorization())
            conn.connectTimeout = 4000
            conn.readTimeout = 5000
            if (conn.responseCode !in 200..299) return@runCatching null
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            org.json.JSONObject(response).optJSONArray("messages")
        }.getOrNull()
    }

    private fun startVoiceInput(messageBox: TextView) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition not available on this device", Toast.LENGTH_SHORT).show()
            return
        }
        messageBox.text = "🎙️ Listening... Speak now!"
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    messageBox.text = "Voice recognition error ($error). You can type below."
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        askAssistant(text, messageBox)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            }
            startListening(intent)
        }
    }

    private fun askAssistant(prompt: String, messageBox: TextView) {
        if (assistantBusy) {
            messageBox.text = "AI Team is still working on your previous message. Please wait."
            return
        }
        if (prompt.isBlank()) return
        assistantBusy = true
        messageBox.text = "You: $prompt\n\nAI Team working..."
        scope.launch {
            try {
                conversationHistory += "user" to prompt
                val reply = withContext(Dispatchers.IO) {
                    queryLocalOrRemoteModel(prompt, conversationHistory.toList())
                }
                messageBox.text = conversationHistory.takeLast(8).joinToString("\n\n") { (role, text) ->
                    "${if (role == "user") "You" else "AI"}: $text"
                }
                speakText(reply)
            } finally {
                assistantBusy = false
            }
        }
    }

    private fun queryLocalOrRemoteModel(prompt: String, history: List<Pair<String, String>>): String {
        return try {
            val port = LaptopConnectionActivity.activePort
            val url = java.net.URL("http://127.0.0.1:$port/api/chat")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", LaptopConnectionActivity.localBridgeAuthorization())
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 120000

            val messages = org.json.JSONArray()
            history.forEach { (role, text) ->
                messages.put(org.json.JSONObject().put("role", role).put("content", text))
            }
            val json = org.json.JSONObject().put("prompt", prompt).put("messages", messages).toString()
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }

            val responseStream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val response = responseStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (conn.responseCode !in 200..299) return "AndroidHarness bridge error (HTTP ${conn.responseCode}): ${response.take(240)}"
            val resObj = org.json.JSONObject(response)
            resObj.optJSONArray("messages")?.let { shared ->
                conversationHistory.clear()
                for (i in 0 until shared.length()) {
                    val item = shared.optJSONObject(i) ?: continue
                    val role = item.optString("role")
                    val text = item.optString("content")
                    if ((role == "user" || role == "assistant") && text.isNotBlank()) {
                        conversationHistory += role to text
                    }
                }
            }
            resObj.optString("reply", "Done.")
        } catch (e: Exception) {
            "AndroidHarness connection error: " + (e.message ?: e.javaClass.simpleName) +
                ". Check that the phone bridge is running and try again."
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeService = null
        scope.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        hideOverlay()
        bubbleView?.let {
            windowManager.removeView(it)
            bubbleView = null
        }
    }

    companion object {
        @Volatile
        private var activeService: FloatingBubbleService? = null

        fun isRunning(): Boolean = activeService != null

        fun start(context: Context) {
            if (Settings.canDrawOverlays(context)) {
                context.startService(Intent(context, FloatingBubbleService::class.java))
            } else {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
        }
    }
}
