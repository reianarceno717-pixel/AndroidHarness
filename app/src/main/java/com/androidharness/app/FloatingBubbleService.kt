package com.androidharness.app

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.androidharness.app.ui.bubble.OverlayContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.math.abs

/**
 * Bubble-only crash guard. Compose dialogs (Material 3 bottom sheets inside
 * ChatScreen) cannot obtain a window token from an overlay service context;
 * without this guard the first opened sheet would kill the whole process with
 * a WindowManager.BadTokenException. While the GOD-AI floating window is up,
 * such a failure is demoted: the overlay closes and the real GOD-AI app is
 * brought forward, so the user continues in the identical full-screen UI.
 */
internal object OverlayGuard {
    @Volatile private var armed = false
    private var previous: Thread.UncaughtExceptionHandler? = null

    @Synchronized
    fun arm(context: Context) {
        if (armed) return
        previous = Thread.getDefaultUncaughtExceptionHandler()
        armed = true
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (isBubbleDialogFailure(throwable)) {
                android.util.Log.w("OverlayGuard", "Overlay cannot host a Compose dialog; falling back to the app", throwable)
                FloatingBubbleService.fallbackToApp(context)
            } else {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    @Synchronized
    fun disarm() {
        if (!armed) return
        Thread.setDefaultUncaughtExceptionHandler(previous)
        previous = null
        armed = false
    }

    private fun isBubbleDialogFailure(throwable: Throwable): Boolean {
        var cause: Throwable? = throwable
        while (cause != null) {
            if (cause is WindowManager.BadTokenException) return true
            cause = cause.cause
        }
        return false
    }
}

/**
 * The GOD-AI floating bubble. The expanded window is NOT a mini chat clone and
 * NOT a WebView: it hosts the app's real native Compose chat UI — the same
 * ChatScreen the app shows when opened normally, driven by the same
 * ChatViewModel/RunManager stack (identical bubbles, workspace, thinking,
 * tool cards; identical models). Like Messenger's chat heads, the window is
 * movable via its handle bar and closes when you tap outside.
 */
class FloatingBubbleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager

    private var bubbleView: View? = null
    private var overlayView: View? = null
    private var overlayOwner: OverlayLifecycleOwner? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeService = this
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createBubbleView()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createBubbleView() {
        val sizePx = (BUBBLE_SIZE_DP * resources.displayMetrics.density).toInt()
        bubbleView = TextView(this).apply {
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
        var touchStartX = 0f
        var touchStartY = 0f

        val bubbleParams = WindowManager.LayoutParams(
            sizePx, sizePx, overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 200
        }

        bubbleView!!.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = bubbleParams.x
                    initialY = bubbleParams.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    bubbleParams.x = initialX + (event.rawX - touchStartX).toInt()
                    bubbleParams.y = initialY + (event.rawY - touchStartY).toInt()
                    windowManager.updateViewLayout(view, bubbleParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(event.rawX - touchStartX) < 15 && abs(event.rawY - touchStartY) < 15) {
                        toggleOverlay()
                    }
                    true
                }
                else -> false
            }
        }
        windowManager.addView(bubbleView, bubbleParams)
    }

    private fun toggleOverlay() {
        if (overlayView != null) hideOverlay() else showOverlay()
    }

    internal fun hideOverlay() {
        OverlayGuard.disarm()
        overlayView?.let { view ->
            runCatching { windowManager.removeView(view) }
            overlayView = null
        }
        overlayOwner?.dispatchDestroyed()
        overlayOwner = null
    }

    private fun showOverlay() {
        if (overlayView != null) return
        val density = resources.displayMetrics.density
        val maxWidth = (420 * density).toInt()
        val maxHeight = (560 * density).toInt()
        val width = (resources.displayMetrics.widthPixels * 0.92f).toInt().coerceAtMost(maxWidth)
        val height = (resources.displayMetrics.heightPixels * 0.62f).toInt().coerceAtMost(maxHeight)

        val owner = OverlayLifecycleOwner()
        overlayOwner = owner

        val params = WindowManager.LayoutParams(
            width, height, overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            elevation = 24f
            setOnTouchListener { _, event ->
                // Tap outside the GOD-AI window dismisses it, like a chat head.
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    hideOverlay()
                    true
                } else false
            }
        }

        owner.dispatchCreated()
        OverlayGuard.arm(applicationContext)
        overlayParams = params
        overlayView = composeView
        windowManager.addView(composeView, params)
        composeView.setContent {
            OverlayContent(
                container = (applicationContext as HarnessApp).container,
                onDrag = { dx, dy -> moveWindow(dx, dy) },
                onClose = { hideOverlay() },
            )
        }
        owner.dispatchStarted()
        owner.dispatchResumed()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun moveWindow(dx: Float, dy: Float) {
        val params = overlayParams ?: return
        val view = overlayView ?: return
        params.x += dx.toInt()
        params.y += dy.toInt()
        windowManager.updateViewLayout(view, params)
    }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    @SuppressLint("ClickableViewAccessibility")
    override fun onDestroy() {
        super.onDestroy()
        activeService = null
        scope.cancel()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        hideOverlay()
    }

    companion object {
        private const val BUBBLE_SIZE_DP = 56

        @Volatile private var activeService: FloatingBubbleService? = null

        fun isRunning(): Boolean = activeService != null

        fun start(context: Context) {
            if (Settings.canDrawOverlays(context)) {
                context.startService(Intent(context, FloatingBubbleService::class.java))
            } else {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}"),
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
        }

        /** Dialog-host failure fallback: close the bubble, continue in the real app. */
        internal fun fallbackToApp(context: Context) {
            runCatching { OverlayGuard.disarm() }
            activeService?.hideOverlay()
            runCatching {
                val launch = Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
            }
        }
    }
}

/**
 * Lifecycle + ViewModelStore host for the ComposeView inside the overlay
 * window. The ViewModelStore is what keeps the bubble's [ChatViewModel] state
 * stable across window open/close, so the chat survives (same as the app).
 */
private class OverlayLifecycleOwner :
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun dispatchCreated() {
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun dispatchStarted() = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    fun dispatchResumed() = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    fun dispatchPaused() = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    fun dispatchStopped() = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)

    fun dispatchDestroyed() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}
