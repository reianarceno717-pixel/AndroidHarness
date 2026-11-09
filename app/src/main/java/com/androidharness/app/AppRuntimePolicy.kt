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

data class AppRuntimeState(
    val ready: Boolean = false,
    val visible: Boolean = false,
    val runInBackground: Boolean = false,
    val generationActive: Boolean = false,
    val notificationsEnabled: Boolean = false,
) {
    /** A live generation keeps the app runnable even with the background preference off. */
    val canRun: Boolean get() = ready && (visible || runInBackground || generationActive)
    val serviceWanted: Boolean get() = runInBackground || generationActive
}

/** Tracks the whole app, including secondary activities, rather than an individual chat. */
class AppRuntimePolicy(private val context: Context, private val settings: SettingsRepository) {    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val visible = MutableStateFlow(false)
    private val generationActive = MutableStateFlow(false)
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
                // Activity replacement and rotation must not interrupt an active agent.
                leaving = scope.launch { delay(700); if (started.isEmpty()) visible.value = false }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /**
     * A live generation holds the background allowance: the run keeps
     * generating while the app is minimized, under the agent foreground
     * service and its persistent notification, even if the user never
     * turned on the "Run in background" preference. The hold is released
     * when the last generation/task finishes.
     */
    fun holdGeneration(active: Boolean) {
        generationActive.value = active
    }

    suspend fun canRun(): Boolean =
        settings.settings.first().runInBackground || visible.value || generationActive.value

    suspend fun <T> whileAllowed(block: suspend () -> T): T = coroutineScope {
        if (!canRun()) throw BackgroundExecutionDisabled()
        val guard = launch {
            combine(settings.settings, visible) { saved, shown -> saved.runInBackground || shown }.first { !it }
            throw BackgroundExecutionDisabled()
        }
        try { block() } finally { guard.cancel() }
    }

    fun start(onBackgroundDisabled: suspend () -> Unit, onAvailable: suspend () -> Unit) {
        scope.launch {
            combine(settings.settings, visible, generationActive) { saved, shown, generating ->
                AppRuntimeState(true, shown, saved.runInBackground, generating, saved.notificationsEnabled)
            }.distinctUntilChanged().collect { next ->
                val previous = mutable.value
                mutable.value = next
                AppNotifications.applyPreference(context, next.notificationsEnabled)
                if (!next.canRun) {
                    withContext(Dispatchers.IO) { onBackgroundDisabled() }
                } else {
                    if (!previous.canRun || next.visible && !previous.visible) onAvailable()
                    // The foreground service (and its persistent notification) backs
                    // both the background preference and any live generation hold.
                    if (next.serviceWanted && (!previous.serviceWanted || !previous.ready)) {
                        runCatching {
                            if (RuntimeNotifier.active.value || next.generationActive) AgentService.acquireKeepalive(context)
                            else if (next.visible) AgentService.startPersistent(context)
                        }
                    } else if (!next.serviceWanted) AgentService.stop(context)
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
