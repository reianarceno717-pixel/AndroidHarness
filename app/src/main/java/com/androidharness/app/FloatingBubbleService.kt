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

        val bubble = TextView(this).apply {
            text = "⚡"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0F172A"))
                setStroke((2 * resources.displayMetrics.density).toInt(), Color.parseColor("#38BDF8"))
            }
            elevation = 16f
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
            text = "⚡ AndroidHarness Assistant"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 15f
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
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

        overlayView = root
        windowManager.addView(root, overlayParams)
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
        messageBox.text = "You: $prompt\n\nAI Team working..."
        scope.launch {
            val reply = withContext(Dispatchers.IO) {
                queryLocalOrRemoteModel(prompt)
            }
            messageBox.text = "You: $prompt\n\nAI: $reply"
            speakText(reply)
        }
    }

    private fun queryLocalOrRemoteModel(prompt: String): String {
        return try {
            val port = LaptopConnectionActivity.activePort
            val url = java.net.URL("http://127.0.0.1:$port/api/chat")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 15000

            val json = org.json.JSONObject().put("prompt", prompt).toString()
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val resObj = org.json.JSONObject(response)
            resObj.optString("reply", "Done.")
        } catch (_: Exception) {
            "I'm here to help! Connect to Wi-Fi/Hotspot or open AndroidHarness to run larger coding tasks."
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
