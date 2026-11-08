package com.androidharness.app

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class FloatingBubbleService : Service(), TextToSpeech.OnInitListener {

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var bubbleView: View? = null
    private var overlayView: View? = null
    private var overlayWebView: WebView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var edgeSnapAnimator: ValueAnimator? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Persisted position & dimensions across open/minimize cycles
    private var savedBubbleX: Int? = null
    private var savedBubbleY: Int? = null
    private var savedOverlayX: Int? = null
    private var savedOverlayY: Int? = null
    private var savedOverlayWidth: Int? = null
    private var savedOverlayHeight: Int? = null
    private var isMaximizedOverlay: Boolean = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeService = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        tts = TextToSpeech(this, this)
        acquireBubbleKeepalive()
        LaptopConnectionActivity.ensureServerRunning(applicationContext)
        showFloatingBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireBubbleKeepalive()
        LaptopConnectionActivity.ensureServerRunning(applicationContext)
        if (bubbleView == null) {
            showFloatingBubble()
        }
        return START_STICKY
    }

    private fun acquireBubbleKeepalive() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (wakeLock == null && pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "harness:floating_bubble").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(12L * 60 * 60 * 1000)
            }
        }
        runCatching {
            AgentService.acquireKeepalive(applicationContext)
            (applicationContext as? HarnessApp)?.container?.runManager?.acquireBackgroundKeepalive()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            ttsReady = true
        }
    }

    private fun speakText(text: String) {
        if (!ttsReady) return
        val clean = text.replace(Regex("[`*_#>-]"), " ").trim().take(480)
        if (clean.isNotEmpty()) {
            tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "bubble_utt")
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun safeScreenBounds(): Rect {
        val dm = resources.displayMetrics
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            val b = metrics.bounds
            return Rect(
                insets.left + dp(6),
                insets.top + dp(6),
                (b.width() - insets.right - dp(6)).coerceAtLeast(insets.left + dp(240)),
                (b.height() - insets.bottom - dp(6)).coerceAtLeast(insets.top + dp(300)),
            )
        }
        return Rect(
            dp(6),
            dp(28),
            (dm.widthPixels - dp(6)).coerceAtLeast(dp(240)),
            (dm.heightPixels - dp(48)).coerceAtLeast(dp(300)),
        )
    }

    /**
     * Loads the actual AndroidHarness / GOD-AI application launcher icon so the floating bubble
     * and window header use the exact same brand mark as the installed app.
     */
    private fun loadAppLauncherDrawable(): Drawable? {
        return runCatching {
            packageManager.getApplicationIcon(applicationInfo)
        }.getOrNull()
            ?: ContextCompat.getDrawable(this, R.mipmap.ic_launcher)
            ?: ContextCompat.getDrawable(this, R.drawable.ic_launcher_foreground)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingBubble() {
        if (bubbleView != null) return

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val bounds = safeScreenBounds()
        val sizePx = dp(58)

        bubbleParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (savedBubbleX ?: (bounds.right - sizePx)).coerceIn(bounds.left, (bounds.right - sizePx).coerceAtLeast(bounds.left))
            y = (savedBubbleY ?: (bounds.top + dp(140))).coerceIn(bounds.top, (bounds.bottom - sizePx).coerceAtLeast(bounds.top))
        }

        val bubbleContainer = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#111318"))
                setStroke(dp(2), Color.parseColor("#A8C7FA"))
            }
            elevation = dp(12).toFloat()
            clipToOutline = true
        }

        val iconView = ImageView(this).apply {
            setImageDrawable(loadAppLauncherDrawable())
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = dp(5)
            setPadding(pad, pad, pad, pad)
        }
        bubbleContainer.addView(
            iconView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )

        val badgeDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#4ADE80"))
                setStroke(dp(2), Color.parseColor("#111318"))
            }
        }
        bubbleContainer.addView(
            badgeDot,
            FrameLayout.LayoutParams(dp(13), dp(13)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = dp(3)
                bottomMargin = dp(3)
            },
        )

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
                    savedBubbleX = params.x
                    savedBubbleY = params.y
                    runCatching { windowManager.updateViewLayout(bubbleContainer, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val diffX = kotlin.math.abs(event.rawX - touchX)
                    val diffY = kotlin.math.abs(event.rawY - touchY)
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
                savedBubbleX = params.x
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

    /**
     * In-process JavaScript bridge exposed as `window.HarnessNative` inside the overlay WebView.
     * Allows 0ms synchronous/in-memory state sync and chat execution against AppContainer
     * without depending on loopback socket roundtrips.
     */
    private inner class AndroidHarnessJsBridge {
        @JavascriptInterface
        fun request(method: String, pathAndQuery: String, body: String): String {
            return LaptopConnectionActivity.handleDirectApi(applicationContext, method, pathAndQuery, body)
        }

        @JavascriptInterface
        fun startVoice() {
            mainHandler.post { startVoiceInput() }
        }

        @JavascriptInterface
        fun minimizeOverlay() {
            mainHandler.post { hideOverlay() }
        }

        @JavascriptInterface
        fun openNativeApp() {
            mainHandler.post {
                val launch = Intent(this@FloatingBubbleService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                hideOverlay()
                startActivity(launch)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun showOverlay() {
        if (overlayView != null) return
        acquireBubbleKeepalive()
        LaptopConnectionActivity.ensureServerRunning(applicationContext)

        val bounds = safeScreenBounds()
        val usableWidth = (bounds.right - bounds.left).coerceAtLeast(dp(280))
        val usableHeight = (bounds.bottom - bounds.top).coerceAtLeast(dp(360))

        val defaultWidth = (usableWidth * 0.94).toInt().coerceIn(dp(290), dp(480).coerceAtMost(usableWidth))
        val defaultHeight = (usableHeight * 0.78).toInt().coerceIn(dp(360), dp(680).coerceAtMost(usableHeight))

        val width = (savedOverlayWidth ?: defaultWidth).coerceIn(dp(270), usableWidth)
        val height = (savedOverlayHeight ?: defaultHeight).coerceIn(dp(340), usableHeight)

        val headerHeightPx = dp(36)
        val footerHeightPx = dp(22)
        val webViewHeightPx = (height - headerHeightPx - footerHeightPx).coerceAtLeast(dp(260))

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
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#111318"))
                setStroke(dp(1), Color.parseColor("#44474E"))
            }
            elevation = dp(18).toFloat()
            clipToOutline = true
        }

        // Sleek Messenger Chat-Head Window Titlebar (Drag to move, expand/shrink, open native app, minimize)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0C0E13"))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, headerHeightPx)
        }

        val headerLogo = ImageView(this).apply {
            setImageDrawable(loadAppLauncherDrawable())
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                rightMargin = dp(7)
            }
        }

        val liveDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#4ADE80"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                rightMargin = dp(6)
            }
        }

        val title = TextView(this).apply {
            text = "AndroidHarness"
            setTextColor(Color.parseColor("#E2E2E9"))
            textSize = 12.5f
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        fun makeTitleBarIconBtn(symbol: String, tintHex: String = "#C4C6D0", onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = symbol
                textSize = 13f
                setTextColor(Color.parseColor(tintHex))
                gravity = Gravity.CENTER
                setPadding(dp(7), dp(2), dp(7), dp(2))
                setOnClickListener { onClick() }
            }
        }

        // Explicit pixel-height WebView hosting the Complete 1:1 Native-Parity AndroidHarness Web Client
        val webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#111318"))
            isFocusable = true
            isFocusableInTouchMode = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(AndroidHarnessJsBridge(), "HarnessNative")
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                webViewHeightPx,
            )
            val html = LaptopConnectionActivity.buildWebClientHtml(
                token = LaptopConnectionActivity.pairedToken,
                overlayMode = true,
            )
            loadDataWithBaseURL(
                "http://127.0.0.1:${LaptopConnectionActivity.activePort}/",
                html,
                "text/html",
                "UTF-8",
                null,
            )
            onResume()
            resumeTimers()
        }
        overlayWebView = webView

        val sizeToggleBtn = makeTitleBarIconBtn("⤢", "#A8C7FA") {
            val p = overlayParams ?: return@makeTitleBarIconBtn
            val b = safeScreenBounds()
            val uw = (b.right - b.left).coerceAtLeast(dp(280))
            val uh = (b.bottom - b.top).coerceAtLeast(dp(360))
            isMaximizedOverlay = !isMaximizedOverlay
            if (isMaximizedOverlay) {
                p.width = (uw * 0.98).toInt()
                p.height = (uh * 0.92).toInt()
                p.x = b.left + (uw - p.width) / 2
                p.y = b.top + (uh - p.height) / 2
            } else {
                p.width = defaultWidth
                p.height = defaultHeight
                p.x = b.left + (uw - p.width) / 2
                p.y = b.top + (uh - p.height) / 3
            }
            savedOverlayWidth = p.width
            savedOverlayHeight = p.height
            savedOverlayX = p.x
            savedOverlayY = p.y
            webView.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (p.height - headerHeightPx - footerHeightPx).coerceAtLeast(dp(240)),
            )
            runCatching { windowManager.updateViewLayout(root, p) }
        }

        val openAppBtn = makeTitleBarIconBtn("↗", "#A8C7FA") {
            val launch = Intent(this@FloatingBubbleService, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            hideOverlay()
            startActivity(launch)
        }

        val minimizeBtn = makeTitleBarIconBtn("—", "#C4C6D0") {
            hideOverlay()
        }

        val closeBtn = makeTitleBarIconBtn("✕", "#FFB4AB") {
            hideOverlay()
        }

        // Dragging the header moves the floating window smoothly within screen bounds
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        header.setOnTouchListener { _, ev ->
            val p = overlayParams ?: return@setOnTouchListener false
            val b = safeScreenBounds()
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x
                    startY = p.y
                    touchX = ev.rawX
                    touchY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxX = (b.right - p.width).coerceAtLeast(b.left)
                    val maxY = (b.bottom - p.height).coerceAtLeast(b.top)
                    p.x = (startX + (ev.rawX - touchX).toInt()).coerceIn(b.left, maxX)
                    p.y = (startY + (ev.rawY - touchY).toInt()).coerceIn(b.top, maxY)
                    savedOverlayX = p.x
                    savedOverlayY = p.y
                    runCatching { windowManager.updateViewLayout(root, p) }
                    true
                }
                else -> false
            }
        }

        header.addView(headerLogo)
        header.addView(liveDot)
        header.addView(title)
        header.addView(sizeToggleBtn)
        header.addView(openAppBtn)
        header.addView(minimizeBtn)
        header.addView(closeBtn)
        root.addView(header)
        root.addView(webView)

        // Bottom resize bar so the user can freely drag-resize the Messenger-style overlay
        val footerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(10), dp(2), dp(8), dp(2))
            setBackgroundColor(Color.parseColor("#0C0E13"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, footerHeightPx)
        }
        val resizeHint = TextView(this).apply {
            text = "GOD-AI · Smart Auto-Switch · Drag ◢ to resize"
            textSize = 9.5f
            setTextColor(Color.parseColor("#8E9099"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val resizeGrip = TextView(this).apply {
            text = "◢"
            textSize = 13f
            setTextColor(Color.parseColor("#A8C7FA"))
            setPadding(dp(10), 0, dp(4), 0)
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
                    val maxW = (b.right - p.x).coerceAtLeast(dp(270))
                    val maxH = (b.bottom - p.y).coerceAtLeast(dp(340))
                    p.width = (startW + (ev.rawX - resizeTouchX).toInt()).coerceIn(dp(270), maxW)
                    p.height = (startH + (ev.rawY - resizeTouchY).toInt()).coerceIn(dp(340), maxH)
                    savedOverlayWidth = p.width
                    savedOverlayHeight = p.height
                    webView.layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (p.height - headerHeightPx - footerHeightPx).coerceAtLeast(dp(240)),
                    )
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
        Toast.makeText(this, "Listening… Speak your prompt", Toast.LENGTH_SHORT).show()
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

    /** Routes voice prompts directly into the shared AppContainer session via the in-process bridge. */
    private fun dispatchOverlayPrompt(prompt: String) {
        scope.launch {
            val reply = withContext(Dispatchers.IO) {
                val payload = org.json.JSONObject()
                    .put("prompt", prompt)
                    .put("async", false)
                    .toString()
                val resJson = LaptopConnectionActivity.handleDirectApi(applicationContext, "POST", "/api/chat", payload)
                runCatching { org.json.JSONObject(resJson).optString("reply", "") }.getOrDefault("")
            }
            overlayWebView?.evaluateJavascript("if(window.syncState) window.syncState();", null)
            if (reply.isNotBlank()) {
                speakText(reply)
            }
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
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock = null
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
