package com.androidharness.app

import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.SettingsBackupPolicy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BackgroundSettingsTest {
    @Test fun `older backups preserve the existing enabled behavior`() {
        val saved = Json.decodeFromString<AppSettings>("{}")
        assertTrue(saved.runInBackground)
        assertTrue(saved.notificationsEnabled)
    }

    @Test fun `portable settings keep the switches independent`() {
        for (background in listOf(false, true)) for (notifications in listOf(false, true)) {
            val original = AppSettings(runInBackground = background, notificationsEnabled = notifications)
            val portable = SettingsBackupPolicy.exportSettings(original, emptySet())
            val restored = Json.decodeFromString<AppSettings>(Json.encodeToString(portable))
            assertEquals(background, restored.runInBackground)
            assertEquals(notifications, restored.notificationsEnabled)
        }
    }

    @Test fun `notifications do not decide whether agents can execute`() {
        for (notifications in listOf(false, true)) {
            assertFalse(AppRuntimeState(notificationsEnabled = notifications).canRun)
            assertFalse(AppRuntimeState(ready = true, notificationsEnabled = notifications).canRun)
            assertTrue(AppRuntimeState(ready = true, visible = true, notificationsEnabled = notifications).canRun)
            assertTrue(AppRuntimeState(ready = true, runInBackground = true, notificationsEnabled = notifications).canRun)
        }
    }

    @Test fun `a live generation keeps the run going in the background`() {
        // The auto keepalive: GOD-AI must keep generating when minimized even
        // though the persistent background preference stays off.
        assertTrue(AppRuntimeState(ready = true, generationActive = true).canRun)
        assertTrue(AppRuntimeState(ready = true, generationActive = true).serviceWanted)
        assertFalse(AppRuntimeState(ready = true, visible = true).serviceWanted)
    }
}
