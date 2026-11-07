package com.androidharness.app

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
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
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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

/**
 * Phase 5, Bug 3 & Bug 4: Complete Messenger-Style Movable Floating AI Assistant.
 * - Uses the actual AndroidHarness application launcher icon (applicationInfo.loadIcon / R.mipmap.ic_launcher).
 * - Supports free dragging with smooth left/right screen-edge snapping and system bar / display cutout inset awareness.
 * - Opens a movable, resizable, minimizable, keyboard-aware overlay window hosting the complete AndroidHarness
 *   interface synchronized with the native AppContainer, RunManager, AgentEngine, and shared SessionRepository.
 */
class FloatingBubbleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager

    private var bubbleView: View? = null
    private var overlayView: View? = null
    private var overlayWebView: WebView? = null
    private var edgeSnapAnimator: ValueAnimator? = null

    private var bubbleParams: WindowManager.LayoutParams? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var savedOverlayX: Int? = null
    private var savedOverlayY: Int? = null
    private var savedOverlayWidth: Int? = null
    private var savedOverlayHeight: Int? = null

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
        LaptopConnectionActivity.ensureServerRunning(applicationContext)
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Computes usable screen area respecting status bars, navigation bars, and display cutouts. */
    private fun safeScreenBounds(): Rect {
        val dm = resources.displayMetrics
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return runCatching {
                val metrics = windowManager.currentWindowMetrics
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                Rect(
                    insets.left + dp(6),
                    insets.top + dp(6),
                    (metrics.bounds.width() - insets.right - dp(6)).coerceAtLeast(dp(120)),
                    (metrics.bounds.height() - insets.bottom - dp(6)).coerceAtLeast(dp(200)),
                )
            }.getOrDefault(Rect(dp(8), dp(32), dm.widthPixels - dp(8), dm.heightPixels - dp(48)))
        }
        return Rect(dp(8), dp(32), dm.widthPixels - dp(8), dm.heightPixels - dp(48))
    }

    /** Loads the real AndroidHarness launcher icon (Bug 4 fix: never substitute a generic emoji). */
    private fun loadAppLauncherDrawable() =
        runCatching { applicationInfo.loadIcon(packageManager) }.getOrNull()
            ?: runCatching { getDrawable(R.mipmap.ic_launcher) }.getOrNull()

    @SuppressLint("ClickableViewAccessibility")
    private fun createBubbleView() {
        val sizePx = dp(58)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val bounds = safeScreenBounds()
        bubbleParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = (bounds.top + dp(140)).coerceIn(bounds.top, (bounds.bottom - sizePx).coerceAtLeast(bounds.top))
        }

        val bubbleContainer = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0F172A"))
                setStroke(dp(2), Color.parseColor("#38BDF8"))
            }
            elevation = dp(8).toFloat()
            val pad = dp(5)
            setPadding(pad, pad, pad, pad)
        }

        val iconView = ImageView(this).apply {
            setImageDrawable(loadAppLauncherDrawable())
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "AndroidHarness Floating Assistant"
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            )
        }
        bubbleContainer.addView(iconView)

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        bubbleContainer.setOnTouchListener { _, event ->
            val params = bubbleParams ?: return@setOnTouchListener false
            val currentBounds = safeScreenBounds()
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    edgeSnapAnimator?.cancel()
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxX = (currentBounds.right - sizePx).coerceAtLeast(currentBounds.left)
                    val maxY = (currentBounds.bottom - sizePx).coerceAtLeast(currentBounds.top)
                    params.x = (initialX + (event.rawX - touchX).toInt()).coerceIn(currentBounds.left, maxX)
                    params.y = (initialY + (event.rawY - touchY).toInt()).coerceIn(currentBounds.top, maxY)
                    runCatching { windowManager.updateViewLayout(bubbleContainer, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val diffX = abs(event.rawX - touchX)
                    val diffY = abs(event.rawY - touchY)
                    if (diffX < dp(10) && diffY < dp(10)) {
                        toggleOverlay()
                    } else {
                        snapBubbleToNearestEdge(bubbleContainer, params, currentBounds, sizePx)
                    }
                    true
                }
                else -> false
            }
        }

        bubbleView = bubbleContainer
        windowManager.addView(bubbleContainer, bubbleParams)
    }

    private fun snapBubbleToNearestEdge(
        bubble: View,
        params: WindowManager.LayoutParams,
        bounds: Rect,
        sizePx: Int,
    ) {
        val midX = (bounds.left + bounds.right - sizePx) / 2
        val targetX = if (params.x <= midX) bounds.left else (bounds.right - sizePx).coerceAtLeast(bounds.left)
        edgeSnapAnimator?.cancel()
        edgeSnapAnimator = ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                params.x = anim.animatedValue as Int
                if (bubbleView != null) {
                    runCatching { windowManager.updateViewLayout(bubble, params) }
                }
            }
            start()
        }
    }

    private fun toggleOverlay() {
        if (overlayView != null) {
            hideOverlay()
        } else {
            showOverlay()
        }
    }

    private fun hideOverlay() {
        overlayParams?.let {
            savedOverlayX = it.x
            savedOverlayY = it.y
            savedOverlayWidth = it.width
            savedOverlayHeight = it.height
        }
        overlayWebView?.let { wv ->
            runCatching {
                wv.stopLoading()
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.destroy()
            }
        }
        overlayWebView = null
        overlayView?.let { view ->
            runCatching { windowManager.removeView(view) }
            overlayView = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun showOverlay() {
        if (overlayView != null) return
        LaptopConnectionActivity.ensureServerRunning(applicationContext)

        val bounds = safeScreenBounds()
        val usableWidth = (bounds.right - bounds.left).coerceAtLeast(dp(280))
        val usableHeight = (bounds.bottom - bounds.top).coerceAtLeast(dp(340))

        val defaultWidth = (usableWidth * 0.92).toInt().coerceIn(dp(280), dp(460).coerceAtMost(usableWidth))
        val defaultHeight = (usableHeight * 0.72).toInt().coerceIn(dp(340), dp(640).coerceAtMost(usableHeight))

        val width = (savedOverlayWidth ?: defaultWidth).coerceIn(dp(260), usableWidth)
        val height = (savedOverlayHeight ?: defaultHeight).coerceIn(dp(320), usableHeight)

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
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            x = (savedOverlayX ?: (bounds.left + (usableWidth - width) / 2))
                .coerceIn(bounds.left, (bounds.right - width).coerceAtLeast(bounds.left))
            y = (savedOverlayY ?: (bounds.top + (usableHeight - height) / 3))
                .coerceIn(bounds.top, (bounds.bottom - height).coerceAtLeast(bounds.top))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#090D16"))
                setStroke(dp(1), Color.parseColor("#38BDF8"))
            }
            elevation = dp(16).toFloat()
            clipToOutline = true
        }

        // Draggable Messenger-style Header Bar with real AndroidHarness launcher icon
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(8), dp(7))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
            }
        }

        val headerLogo = ImageView(this).apply {
            setImageDrawable(loadAppLauncherDrawable())
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                rightMargin = dp(8)
            }
        }

        val title = TextView(this).apply {
            text = "AndroidHarness · Drag to Move"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 13f
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val screenBtn = Button(this).apply {
            text = "Screen"
            textSize = 10f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#1E293B"))
            }
            setPadding(dp(8), dp(3), dp(8), dp(3))
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                val prompt = if (!clip.isNullOrBlank()) {
                    "Analyze this copied screen content:\n$clip"
                } else {
                    "Help me with what I am currently working on."
                }
                dispatchOverlayPrompt(prompt)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)).apply {
                rightMargin = dp(4)
            }
        }

        val voiceBtn = Button(this).apply {
            text = "Mic"
            textSize = 10f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#0284C7"))
            }
            setPadding(dp(8), dp(3), dp(8), dp(3))
            setOnClickListener { startVoiceInput() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)).apply {
                rightMargin = dp(4)
            }
        }

        val expandAppBtn = Button(this).apply {
            text = "Full"
            textSize = 10f
            setTextColor(Color.parseColor("#38BDF8"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setOnClickListener {
                val launch = Intent(this@FloatingBubbleService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                hideOverlay()
                startActivity(launch)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28))
        }

        val minimizeBtn = Button(this).apply {
            text = "—"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnClickListener { hideOverlay() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28))
        }

        header.addView(headerLogo)
        header.addView(title)
        header.addView(screenBtn)
        header.addView(voiceBtn)
        header.addView(expandAppBtn)
        header.addView(minimizeBtn)

        var headerStartX = 0
        var headerStartY = 0
        var headerTouchX = 0f
        var headerTouchY = 0f
        header.setOnTouchListener { _, ev ->
            val p = overlayParams ?: return@setOnTouchListener false
            val b = safeScreenBounds()
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    headerStartX = p.x
                    headerStartY = p.y
                    headerTouchX = ev.rawX
                    headerTouchY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = (headerStartX + (ev.rawX - headerTouchX).toInt())
                        .coerceIn(b.left, (b.right - p.width).coerceAtLeast(b.left))
                    p.y = (headerStartY + (ev.rawY - headerTouchY).toInt())
                        .coerceIn(b.top, (b.bottom - p.height).coerceAtLeast(b.top))
                    savedOverlayX = p.x
                    savedOverlayY = p.y
                    runCatching { windowManager.updateViewLayout(root, p) }
                    true
                }
                else -> false
            }
        }
        root.addView(header)

        // Full AndroidHarness WebView hosting the complete synchronized app face
        val webView = object : WebView(this) {
            override fun onCheckIsTextEditor(): Boolean = true
        }.apply {
            setBackgroundColor(Color.parseColor("#090D16"))
            isFocusable = true
            isFocusableInTouchMode = true
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            loadUrl(LaptopConnectionActivity.localOverlayUrl(applicationContext))
        }
        overlayWebView = webView
        root.addView(webView)

        // Bottom resize bar so the user can resize the Messenger-style overlay anywhere on screen
        val footerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(10), dp(3), dp(10), dp(3))
            setBackgroundColor(Color.parseColor("#0F172A"))
        }
        val resizeHint = TextView(this).apply {
            text = "Synced with AndroidHarness Engine · Drag ◢ to resize"
            textSize = 10f
            setTextColor(Color.parseColor("#64748B"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val resizeGrip = TextView(this).apply {
            text = "◢"
            textSize = 14f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(dp(10), dp(2), dp(4), dp(2))
        }

        var startW = 0
        var startH = 0
        var resizeTouchX = 0f
        var resizeTouchY = 0f
        resizeGrip.setOnTouchListener { _, ev ->
            val p = overlayParams ?: return@setOnTouchListener false
            val b = safeScreenBounds()
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startW = p.width
                    startH = p.height
                    resizeTouchX = ev.rawX
                    resizeTouchY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxW = (b.right - p.x).coerceAtLeast(dp(260))
                    val maxH = (b.bottom - p.y).coerceAtLeast(dp(320))
                    p.width = (startW + (ev.rawX - resizeTouchX).toInt()).coerceIn(dp(260), maxW)
                    p.height = (startH + (ev.rawY - resizeTouchY).toInt()).coerceIn(dp(320), maxH)
                    savedOverlayWidth = p.width
                    savedOverlayHeight = p.height
                    runCatching { windowManager.updateViewLayout(root, p) }
                    true
                }
                else -> false
            }
        }

        footerBar.addView(resizeHint)
        footerBar.addView(resizeGrip)
        root.addView(footerBar)

        overlayView = root
        windowManager.addView(root, overlayParams)
    }

    private fun startVoiceInput() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition not available on this device", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Listening... Speak your prompt", Toast.LENGTH_SHORT).show()
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    Toast.makeText(this@FloatingBubbleService, "Voice input error ($error)", Toast.LENGTH_SHORT).show()
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        dispatchOverlayPrompt(text)
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

    /** Routes voice or screen-context prompts through the authenticated local bridge connected to AppContainer. */
    private fun dispatchOverlayPrompt(prompt: String) {
        scope.launch {
            val reply = withContext(Dispatchers.IO) {
                queryLocalOrRemoteModel(prompt)
            }
            overlayWebView?.evaluateJavascript("if(window.syncState) window.syncState();", null)
            if (reply.isNotBlank()) {
                speakText(reply)
            }
        }
    }

    private fun queryLocalOrRemoteModel(prompt: String): String {
        return try {
            LaptopConnectionActivity.ensureServerRunning(applicationContext)
            val port = LaptopConnectionActivity.activePort
            val url = java.net.URL("http://127.0.0.1:$port/api/chat")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${LaptopConnectionActivity.pairedToken}")
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 45000

            val json = org.json.JSONObject()
                .put("prompt", prompt)
                .put("async", true)
                .toString()
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val resObj = org.json.JSONObject(response)
            resObj.optString("reply", "")
        } catch (_: Exception) {
            ""
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeService = null
        edgeSnapAnimator?.cancel()
        edgeSnapAnimator = null
        scope.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        hideOverlay()
        bubbleView?.let {
            runCatching { windowManager.removeView(it) }
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
