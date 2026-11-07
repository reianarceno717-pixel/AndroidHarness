package com.androidharness.app

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import com.androidharness.app.data.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class AppRuntimeState(
    val ready: Boolean = false,
    val visible: Boolean = false,
    val runInBackground: Boolean = false,
    val notificationsEnabled: Boolean = false,
) {
    val canRun: Boolean get() = ready && (visible || runInBackground)
}

/** Tracks the whole app, including secondary activities, floating bubble overlay, and LAN bridge. */
internal class AppRuntimePolicy(private val context: Context, private val settings: SettingsRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val visible = MutableStateFlow(false)
    private val mutable = MutableStateFlow(AppRuntimeState())
    val state = mutable.asStateFlow()
    private val started = mutableSetOf<Activity>()
    private var leaving: Job? = null

    init {
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started += activity
                leaving?.cancel()
                visible.value = true
            }
            override fun onActivityStopped(activity: Activity) {
                started -= activity
                if (started.isNotEmpty()) return
                // Activity replacement, rotation, or brief app switching must not interrupt an active agent.
                leaving = scope.launch { delay(1500); if (started.isEmpty()) visible.value = false }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun isBackgroundAllowed(savedRunInBackground: Boolean, shown: Boolean): Boolean =
        savedRunInBackground || shown || FloatingBubbleService.isRunning() || LaptopConnectionActivity.isServerRunning()

    suspend fun canRun(): Boolean = isBackgroundAllowed(settings.settings.first().runInBackground, visible.value)

    suspend fun <T> whileAllowed(block: suspend () -> T): T = coroutineScope {
        if (!canRun()) throw BackgroundExecutionDisabled()
        val guard = launch {
            combine(settings.settings, visible) { saved, shown ->
                isBackgroundAllowed(saved.runInBackground, shown)
            }.first { !it }
            throw BackgroundExecutionDisabled()
        }
        try { block() } finally { guard.cancel() }
    }

    fun start(onBackgroundDisabled: suspend () -> Unit, onAvailable: suspend () -> Unit) {
        scope.launch {
            combine(settings.settings, visible) { saved, shown ->
                AppRuntimeState(
                    ready = true,
                    visible = shown,
                    runInBackground = isBackgroundAllowed(saved.runInBackground, shown),
                    notificationsEnabled = saved.notificationsEnabled,
                )
            }.distinctUntilChanged().collect { next ->
                val previous = mutable.value
                mutable.value = next
                AppNotifications.applyPreference(context, next.notificationsEnabled)
                if (!next.canRun) {
                    withContext(Dispatchers.IO) { onBackgroundDisabled() }
                } else {
                    if (!previous.canRun || next.visible && !previous.visible) onAvailable()
                    if (next.runInBackground) {
                        runCatching {
                            if (RuntimeNotifier.active.value || FloatingBubbleService.isRunning() || LaptopConnectionActivity.isServerRunning()) {
                                AgentService.acquireKeepalive(context)
                            } else if (!previous.runInBackground || !previous.ready || next.visible) {
                                AgentService.startPersistent(context)
                            }
                        }
                    } else if (!FloatingBubbleService.isRunning() && !LaptopConnectionActivity.isServerRunning()) {
                        AgentService.stop(context)
                    }
                }
            }
        }
    }
}

/** One gate for all optional app alerts; foreground-service notices remain owned by Android. */
internal object AppNotifications {
    private val lock = Any()
    fun enabled(context: Context): Boolean =
        (context.applicationContext as? HarnessApp)?.container?.runtimePolicy?.state?.value?.notificationsEnabled == true

    fun post(context: Context, id: Int, notification: Notification, tag: String? = null) = synchronized(lock) {
        if (enabled(context)) context.getSystemService(NotificationManager::class.java)?.notify(tag, id, notification)
    }

    fun applyPreference(context: Context, enabled: Boolean) = synchronized(lock) {
        if (!enabled) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return@synchronized
            manager.activeNotifications.filter { it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE == 0 }
                .forEach { manager.cancel(it.tag, it.id) }
        }
    }
}

internal class BackgroundExecutionDisabled : IllegalStateException("Background running is off. Open AndroidHarness to continue.")
