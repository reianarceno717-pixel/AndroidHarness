package com.androidharness.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.agent.AgentMode
import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.agent.ThinkingLevel
import com.androidharness.app.data.ConversationConfig
import com.androidharness.app.data.ConversationKind
import com.androidharness.app.data.PersistentSpecialistTeam
import com.androidharness.app.llm.FreeTierProviderCatalog
import kotlinx.coroutines.flow.first
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import com.androidharness.app.llm.HarnessProvider
import com.androidharness.app.llm.RequestOptions
import com.androidharness.app.llm.StreamEvent
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import android.util.Base64
import java.util.Collections
import java.util.concurrent.Executors

class LaptopConnectionActivity : AppCompatActivity() {

    private lateinit var connectivityManager: ConnectivityManager
    private var isTetheringEnabled = false
    private lateinit var statusText: TextView
    private lateinit var addressText: TextView
    private lateinit var enableButton: Button
    private lateinit var copyButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_laptop_connection)

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        statusText = findViewById(R.id.laptop_status)
        addressText = findViewById(R.id.laptop_ip)
        enableButton = findViewById(R.id.laptop_enable_button)
        copyButton = findViewById(R.id.laptop_copy_button)

        ensureServerRunning(applicationContext)

        copyButton.setOnClickListener {
            val address = addressText.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("laptop_address", address))
            Toast.makeText(this, R.string.laptop_copied, Toast.LENGTH_SHORT).show()
        }

        enableButton.setOnClickListener {
            if (isTetheringEnabled) {
                stopUsbTethering()
            } else {
                startUsbTethering()
            }
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        ensureServerRunning(applicationContext)
        val detected = detectBestIpAddress()
        if (detected != null) {
            activateBridgeUi(detected.first)
        }
    }

    private fun startUsbTethering() {
        ensureServerRunning(applicationContext)
        val reflectionSuccess = tryUsbTethering(true)
        val detected = detectBestIpAddress()

        if (reflectionSuccess || detected != null) {
            val ip = detected?.first ?: "192.168.42.1"
            activateBridgeUi(ip)
            if (!reflectionSuccess && detected?.second == false) {
                // Connected over Wi-Fi/LAN; also offer system USB tethering screen if user wants USB cable mode
                Toast.makeText(
                    this,
                    "Laptop bridge active at http://$ip:$activePort",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        // Direct reflection is blocked on unrooted Android builds and no LAN IP is active yet:
        // Open Android's native Hotspot & Tethering settings so the user can toggle USB tethering in 1 tap.
        val openedSettings = openSystemTetheringSettings()
        val fallbackIp = "192.168.42.1"
        activateBridgeUi(fallbackIp)
        if (!openedSettings) {
            statusText.text = getString(R.string.laptop_status_unavailable)
            statusText.setTextColor(Color.parseColor("#B91C1C"))
        }
    }

    private fun activateBridgeUi(ip: String) {
        val url = "http://$ip:$activePort/?token=$bridgeToken"
        statusText.text = getString(R.string.laptop_status_tethering_on)
        statusText.setTextColor(Color.parseColor("#15803D"))
        isTetheringEnabled = true
        enableButton.text = getString(R.string.laptop_disable_tethering)
        copyButton.isEnabled = true
        addressText.text = url
    }

    private fun stopUsbTethering() {
        tryUsbTethering(false)
        isTetheringEnabled = false
        enableButton.text = getString(R.string.laptop_enable_tethering)
        addressText.text = getString(R.string.laptop_address_placeholder)
        copyButton.isEnabled = false
        statusText.text = getString(R.string.laptop_status_tethering_off)
        statusText.setTextColor(Color.parseColor("#444444"))
    }

    private fun openSystemTetheringSettings(): Boolean {
        val intents = listOf(
            Intent("android.settings.TETHER_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun tryUsbTethering(enable: Boolean): Boolean {
        return try {
            val method = connectivityManager::class.java.getMethod(
                if (enable) "startUsbTethering" else "stopUsbTethering"
            )
            method.isAccessible = true
            method.invoke(connectivityManager) as Boolean
        } catch (_: Exception) {
            try {
                val wm = connectivityManager::class.java.getMethod(
                    "setUsbTethering",
                    Boolean::class.javaPrimitiveType
                )
                wm.isAccessible = true
                (wm.invoke(connectivityManager, enable) as? Int) == 0
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Scans active network interfaces for USB tethering (rndis/usb/ncm -> true)
     * or Wi-Fi / Hotspot LAN (wlan/ap/swlan -> false).
     */
    private fun detectBestIpAddress(): Pair<String, Boolean>? {
        return try {
            var usbCandidate: String? = null
            var lanCandidate: String? = null
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name.lowercase()
                val addresses = Collections.list(ni.inetAddresses)
                for (addr in addresses) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    val ip = addr.hostAddress ?: continue
                    val isUsb = name.startsWith("rndis") ||
                        name.startsWith("usb") ||
                        name.startsWith("ncm") ||
                        name.startsWith("eth") ||
                        ip.startsWith("192.168.42.") ||
                        ip.startsWith("192.168.43.")
                    if (isUsb) {
                        usbCandidate = ip
                    } else if (
                        name.startsWith("wlan") ||
                        name.startsWith("ap") ||
                        name.startsWith("swlan") ||
                        ip.startsWith("192.168.") ||
                        ip.startsWith("10.") ||
                        ip.startsWith("172.")
                    ) {
                        if (lanCandidate == null) lanCandidate = ip
                    }
                }
            }
            when {
                usbCandidate != null -> usbCandidate to true
                lanCandidate != null -> lanCandidate to false
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun refresh() {
        val detected = detectBestIpAddress()
        if (detected != null) {
            activateBridgeUi(detected.first)
        } else {
            addressText.text = getString(R.string.laptop_address_placeholder)
            statusText.text = getString(R.string.laptop_status_tethering_off)
            statusText.setTextColor(Color.parseColor("#444444"))
            isTetheringEnabled = false
            enableButton.text = getString(R.string.laptop_enable_tethering)
            copyButton.isEnabled = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateUi(enabled: Boolean) {
        isTetheringEnabled = enabled
        enableButton.text = if (enabled) getString(R.string.laptop_disable_tethering) else getString(R.string.laptop_enable_tethering)
        copyButton.isEnabled = enabled
    }


    companion object {
        private const val MAX_REQUEST_BODY_BYTES = 512_000

        @Volatile
        private var serverSocket: ServerSocket? = null

        @Volatile
        private var appContextRef: Context? = null

        @Volatile
        var activePort: Int = 8080
            private set

        // Bounded worker pool (Phase 4 requirement: bounded worker resources)
        private val executor = ThreadPoolExecutor(
            2,
            16,
            60L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(64),
            ThreadPoolExecutor.DiscardOldestPolicy(),
        )

        // A per-installation bridge secret. The paired URL is shown only on the phone.
        private val bridgeToken: String by lazy {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }

        internal val pairedToken: String
            get() = bridgeToken

        /** Local loopback URL used by FloatingBubbleService overlay WebView to render the full app face. */
        fun localOverlayUrl(appContext: Context): String {
            ensureServerRunning(appContext)
            return "http://127.0.0.1:$activePort/?token=$bridgeToken&overlay=1"
        }

        @Synchronized
        fun ensureServerRunning(appContext: Context) {
            appContextRef = appContext.applicationContext
            if (serverSocket != null && serverSocket?.isClosed == false) return
            val workspaceDir = (appContext.getExternalFilesDir(null) ?: appContext.filesDir)
                .resolve("workspace")
                .apply { mkdirs() }
            for (port in 8080..8085) {
                try {
                    val socket = ServerSocket(port)
                    serverSocket = socket
                    activePort = port
                    executor.execute {
                        while (!socket.isClosed) {
                            try {
                                val client = socket.accept()
                                executor.execute { handleClient(client, workspaceDir) }
                            } catch (_: Exception) {
                                if (socket.isClosed) break
                            }
                        }
                    }
                    break
                } catch (_: Exception) {
                }
            }
        }

        private fun containerOrNull(): AppContainer? =
            (appContextRef as? HarnessApp)?.container

        private suspend fun resolveSharedSessionId(container: AppContainer): String {
            container.settings.ensureSpecialistTeam()
            val prefs = container.settings.settings.first()
            val allSessions = container.sessions.sessions.first()
            val existing = prefs.lastActiveSessionId?.takeIf { id -> allSessions.any { it.id == id } }
                ?: allSessions.firstOrNull()?.id
            if (existing != null) {
                if (prefs.lastActiveSessionId != existing) {
                    container.settings.setLastActiveSessionId(existing)
                }
                return existing
            }
            val projectId = container.workspace.currentProjectOnce().id
            val created = container.sessions.createSession("Shared Assistant", projectId)
            container.settings.setLastActiveSessionId(created)
            return created
        }

        private suspend fun resolveActiveProvider(container: AppContainer): Pair<com.androidharness.app.llm.ProviderConfig, String> {
            val prefs = container.settings.settings.first()
            val list = container.providers.providers.first()
            val chosen = list.firstOrNull { it.id == prefs.activeProviderId && it.enabled }
                ?: list.firstOrNull { it.enabled }
                ?: HarnessProvider.config
            val effectiveModel = prefs.activeModel?.takeIf { it.isNotBlank() } ?: chosen.model
            val config = container.providers.resolveHarnessConfig(chosen.copy(model = effectiveModel))
            val key = if (config.id == HarnessProvider.ID) {
                container.providers.harnessApiKey(list)
            } else {
                container.providers.apiKeys(config.id).keys.firstOrNull { it.enabled }?.value?.takeIf { it.isNotBlank() }
                    ?: container.providers.apiKey(config.id).orEmpty()
            }
            return config to key
        }

        private fun handleClient(client: Socket, fallbackWorkspaceDir: File) {
            client.use { sock ->
                sock.soTimeout = 60_000
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val fullPath = parts[1]
                val path = fullPath.substringBefore("?")
                val query = fullPath.substringAfter("?", "")

                var contentLength = 0
                var authorization: String? = null
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) break
                    when {
                        header.lowercase().startsWith("content-length:") ->
                            contentLength = header.substringAfter(":").trim().toIntOrNull() ?: 0
                        header.lowercase().startsWith("authorization:") ->
                            authorization = header.substringAfter(":").trim()
                    }
                }

                if (contentLength < 0 || contentLength > MAX_REQUEST_BODY_BYTES) {
                    writeHttpResponse(sock, 413, "application/json; charset=utf-8", """{"error":"Request payload exceeds 512 KB limit"}""")
                    return
                }

                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(buf, read, contentLength - read)
                        if (n <= 0) break
                        read += n
                    }
                    String(buf, 0, read)
                } else ""

                if (path.startsWith("/api/") && authorization != "Bearer $bridgeToken") {
                    writeHttpResponse(sock, 401, "application/json; charset=utf-8", """{"error":"Unauthorized. Open the paired link shown in AndroidHarness."}""")
                    return
                }

                val container = containerOrNull()
                val workspaceDir = container?.workspace?.appPrivateRoot ?: fallbackWorkspaceDir

                try {
                    when {
                        method == "GET" && path == "/api/ping" -> {
                            val activeModel = if (container != null) {
                                runBlocking { resolveActiveProvider(container).first.model }
                            } else HarnessProvider.DEFAULT_MODEL
                            val json = JSONObject()
                                .put("ok", true)
                                .put("app", "AndroidHarness Complete Web & Overlay Client")
                                .put("model", activeModel)
                                .put("port", activePort)
                                .put("workspace", workspaceDir.absolutePath)
                                .put("engineConnected", container != null)
                            writeHttpResponse(sock, 200, "application/json; charset=utf-8", json.toString())
                        }

                        method == "GET" && path == "/api/state" -> {
                            if (container == null) {
                                val fallbackJson = JSONObject()
                                    .put("ok", true)
                                    .put("sessionId", "default")
                                    .put("sessionTitle", "Shared Assistant")
                                    .put("messages", JSONArray())
                                    .put("live", JSONObject().put("running", false))
                                writeHttpResponse(sock, 200, "application/json; charset=utf-8", fallbackJson.toString())
                                return
                            }
                            val stateJson = runBlocking {
                                val sid = resolveSharedSessionId(container)
                                val prefs = container.settings.settings.first()
                                val allSessions = container.sessions.sessions.first()
                                val currentSession = allSessions.firstOrNull { it.id == sid }
                                val msgs = container.sessions.messages(sid)
                                val live = container.runManager.live(sid).value
                                val (activeProvider, _) = resolveActiveProvider(container)
                                val allProviders = container.providers.providers.first()
                                val catalogs = container.providers.catalogs.first()

                                val msgArr = JSONArray()
                                msgs.takeLast(120).forEach { m ->
                                    if (m.toolCallId != null && m.role == Role.ASSISTANT) return@forEach
                                    msgArr.put(
                                        JSONObject()
                                            .put("id", m.id ?: "")
                                            .put("role", m.role.name)
                                            .put("text", m.text)
                                            .put("thinking", m.thinking)
                                            .put("toolName", m.toolName ?: "")
                                            .put("isError", m.isError)
                                            .put("createdAt", m.createdAt)
                                    )
                                }

                                val sessArr = JSONArray()
                                allSessions.take(40).forEach { s ->
                                    sessArr.put(
                                        JSONObject()
                                            .put("id", s.id)
                                            .put("title", s.title)
                                            .put("updatedAt", s.updatedAt)
                                            .put("active", s.id == sid)
                                    )
                                }

                                val provArr = JSONArray()
                                allProviders.forEach { p ->
                                    val hasKey = p.id == HarnessProvider.ID ||
                                        container.providers.apiKeys(p.id).keys.any { it.enabled && it.value.isNotBlank() }
                                    val models = JSONArray()
                                    val entries = when {
                                        p.id == HarnessProvider.ID -> HarnessProvider.pooledModels
                                        !catalogs[p.id].isNullOrEmpty() -> catalogs[p.id].orEmpty()
                                        else -> FreeTierProviderCatalog.forBaseUrl(p.baseUrl)?.freeModels.orEmpty()
                                    }
                                    entries.take(40).forEach { me ->
                                        models.put(
                                            JSONObject()
                                                .put("id", me.id)
                                                .put("reasoning", me.reasoning ?: false)
                                                .put("free", me.free ?: (p.id == HarnessProvider.ID))
                                                .put("note", me.note ?: "")
                                        )
                                    }
                                    if (models.length() == 0 && p.model.isNotBlank()) {
                                        models.put(JSONObject().put("id", p.model).put("reasoning", false).put("free", false).put("note", ""))
                                    }
                                    val freeTierInfo = FreeTierProviderCatalog.forBaseUrl(p.baseUrl)
                                    provArr.put(
                                        JSONObject()
                                            .put("id", p.id)
                                            .put("name", p.name)
                                            .put("type", p.type.name)
                                            .put("baseUrl", p.baseUrl)
                                            .put("model", p.model)
                                            .put("enabled", p.enabled)
                                            .put("hasKey", hasKey)
                                            .put("rateLimitSummary", freeTierInfo?.rateLimitSummary ?: if (p.id == HarnessProvider.ID) "Keyless pooled free gateway · auto-fallback enabled" else "Standard API tier")
                                            .put("models", models)
                                    )
                                }

                                val teamArr = JSONArray()
                                val specialists = PersistentSpecialistTeam.mergePreservingConfigured(
                                    existing = prefs.chatAgents,
                                    fallbackProviderId = activeProvider.id,
                                    fallbackModel = activeProvider.model,
                                )
                                specialists.forEach { sp ->
                                    teamArr.put(
                                        JSONObject()
                                            .put("id", sp.id)
                                            .put("name", sp.name)
                                            .put("avatar", sp.avatar)
                                            .put("description", sp.description)
                                            .put("persona", sp.persona)
                                            .put("instructions", sp.instructions)
                                            .put("providerId", sp.model?.providerId ?: activeProvider.id)
                                            .put("model", sp.model?.model ?: activeProvider.model)
                                            .put("sharedHistory", sp.sharedHistory)
                                    )
                                }

                                val liveObj = JSONObject()
                                    .put("running", live.running)
                                    .put("streamingText", live.streamingText ?: "")
                                    .put("streamingThinking", live.streamingThinking ?: "")
                                    .put("actionText", container.runManager.actionText(live) ?: "")
                                    .put("error", live.error ?: "")
                                    .put("retryStatus", live.retryStatus ?: "")
                                    .put("pendingApproval", live.pendingApproval?.let {
                                        JSONObject()
                                            .put("tool", it.call.name)
                                            .put("description", it.toolDescription)
                                    } ?: JSONObject.NULL)
                                    .put("pendingQuestion", live.pendingQuestion?.let {
                                        JSONObject()
                                            .put("question", it.question)
                                            .put("options", JSONArray(it.options))
                                    } ?: JSONObject.NULL)

                                val settingsObj = JSONObject()
                                    .put("permissionMode", prefs.permissionMode.name)
                                    .put("thinkingLevel", prefs.thinkingLevel.name)
                                    .put("ttsAlwaysSpeak", prefs.ttsAlwaysSpeak)
                                    .put("ttsSpeed", prefs.ttsSpeed.toDouble())
                                    .put("voiceEngine", prefs.voiceEngine)
                                    .put("memoryEnabled", prefs.memoryEnabled)
                                    .put("repoMapEnabled", prefs.repoMapEnabled)
                                    .put("fallbackEnabled", prefs.fallbackPolicy.enabled)

                                JSONObject()
                                    .put("ok", true)
                                    .put("sessionId", sid)
                                    .put("sessionTitle", currentSession?.title ?: "Shared Assistant")
                                    .put("messages", msgArr)
                                    .put("sessions", sessArr)
                                    .put("live", liveObj)
                                    .put("activeProvider", JSONObject()
                                        .put("id", activeProvider.id)
                                        .put("name", activeProvider.name)
                                        .put("model", activeProvider.model)
                                        .put("type", activeProvider.type.name))
                                    .put("providers", provArr)
                                    .put("team", teamArr)
                                    .put("settings", settingsObj)
                                    .put("workspace", workspaceDir.absolutePath)
                            }
                            writeHttpResponse(sock, 200, "application/json; charset=utf-8", stateJson.toString())
                        }

                        method == "POST" && path == "/api/session/select" -> {
                            val payload = JSONObject(body.ifBlank { "{}" })
                            val targetSid = payload.optString("sessionId").trim()
                            if (targetSid.isEmpty() || container == null) {
                                writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Invalid sessionId"}""")
                                return
                            }
                            runBlocking {
                                container.settings.setLastActiveSessionId(targetSid)
                            }
                            writeHttpResponse(sock, 200, "application/json; charset=utf-8", JSONObject().put("ok", true).put("sessionId", targetSid).toString())
                        }

                        method == "POST" && path == "/api/session/new" -> {
                            if (container == null) {
                                writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"AppContainer unavailable"}""")
                                return
                            }
                            val payload = JSONObject(body.ifBlank { "{}" })
                            val title = payload.optString("title").trim().ifBlank { "New Conversation" }
                            val specialistId = payload.optString("specialistId").trim()
                            val newSid = runBlocking {
                                val prefs = container.settings.settings.first()
                                val (activeProvider, _) = resolveActiveProvider(container)
                                val specialists = PersistentSpecialistTeam.mergePreservingConfigured(
                                    prefs.chatAgents, activeProvider.id, activeProvider.model
                                )
                                val chosenSpecialist = specialists.firstOrNull { it.id == specialistId }
                                val conv = if (chosenSpecialist != null) {
                                    ConversationConfig(kind = ConversationKind.CHAT, agent = chosenSpecialist)
                                } else {
                                    ConversationConfig(kind = ConversationKind.CODE)
                                }
                                val projectId = container.workspace.currentProjectOnce().id
                                val created = container.sessions.createSession(
                                    title = if (chosenSpecialist != null && title == "New Conversation") "${chosenSpecialist.name} Session" else title,
                                    projectId = projectId,
                                    conversation = conv,
                                )
                                container.settings.setLastActiveSessionId(created)
                                created
                            }
                            writeHttpResponse(sock, 200, "application/json; charset=utf-8", JSONObject().put("ok", true).put("sessionId", newSid).toString())
                        }

                        method == "POST" && path == "/api/model/select" -> {
                            if (container == null) {
                                writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"AppContainer unavailable"}""")
                                return
                            }
                            val payload = JSONObject(body.ifBlank { "{}" })
                            val providerId = payload.optString("providerId").trim().ifBlank { HarnessProvider.ID }
                            val model = payload.optString("model").trim().ifBlank { HarnessProvider.DEFAULT_MODEL }
                            runBlocking {
                                container.settings.setActiveSelection(providerId, model)
                            }
                            writeHttpResponse(sock, 200, "application/json; charset=utf-8", JSONObject().put("ok", true).put("providerId", providerId).put("model", model).toString())
                        }

                        method == "POST" && path == "/api/settings" -> {
                            if (container == null) {
                                writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"AppContainer unavailable"}""")
                                return
                            }
                            val payload = JSONObject(body.ifBlank { "{}" })
                            runBlocking {
                                payload.optString("permissionMode").takeIf { it.isNotBlank() }?.let { raw ->
                                    runCatching { PermissionMode.valueOf(raw) }.getOrNull()?.let { container.settings.setPermissionMode(it) }
                                }
                                payload.optString("thinkingLevel").takeIf { it.isNotBlank() }?.let { raw ->
                                    runCatching { ThinkingLevel.valueOf(raw) }.getOrNull()?.let { container.settings.setThinkingLevel(it) }
                                }
                                if (payload.has("ttsAlwaysSpeak")) {
                                    container.settings.setTtsAlwaysSpeak(payload.optBoolean("ttsAlwaysSpeak"))
                                }
                                if (payload.has("memoryEnabled")) {
                                    container.settings.setMemoryEnabled(payload.optBoolean("memoryEnabled"))
                                }
                                if (payload.has("repoMapEnabled")) {
                                    container.settings.setRepoMapEnabled(payload.optBoolean("repoMapEnabled"))
                                }
                            }
                            writeHttpResponse(sock, 200, "application/json; charset=utf-8", """{"ok":true}""")
                        }

                        method == "POST" && path == "/api/stop" -> {
                            if (container != null) {
                                runBlocking {
                                    val sid = resolveSharedSessionId(container)
                                    container.runManager.stop(sid)
                                }
                            }
                            writeHttpResponse(sock, 200, "application/json; charset=utf-8", """{"ok":true}""")
                        }

                        method == "POST" && path == "/api/approval" -> {
                            if (container != null) {
                                val payload = JSONObject(body.ifBlank { "{}" })
                                val action = payload.optString("action")
                                runBlocking {
                                    val sid = resolveSharedSessionId(container)
                                    when (action) {
                                        "approve" -> container.runManager.approve(sid, payload.optBoolean("remember", false))
                                        "deny" -> container.runManager.deny(sid)
                                        "answer" -> container.runManager.answerQuestion(sid, payload.optString("answer"))
                                    }
                                }
                            }
                            writeHttpResponse(sock, 200, "application/json; charset=utf-8", """{"ok":true}""")
                        }

                        method == "GET" && path == "/api/files" -> {
                            val files = JSONArray()
                            workspaceDir.walkTopDown().maxDepth(5).forEach { file ->
                                if (file.isFile) {
                                    val rel = file.relativeTo(workspaceDir).path
                                    files.put(
                                        JSONObject()
                                            .put("path", rel)
                                            .put("size", file.length())
                                    )
                                }
                            }
                            val json = JSONObject().put("files", files).put("workspace", workspaceDir.absolutePath)
                            writeHttpResponse(sock, 200, "application/json; charset=utf-8", json.toString())
                        }

                        method == "GET" && path == "/api/file" -> {
                            val relPath = query.split("&")
                                .map { it.split("=", limit = 2) }
                                .firstOrNull { it.firstOrNull() == "path" }
                                ?.getOrNull(1)
                                ?.let { URLDecoder.decode(it, "UTF-8") }
                                .orEmpty()
                            val target = File(workspaceDir, relPath).canonicalFile
                            if (!target.path.startsWith(workspaceDir.canonicalPath + File.separator) || !target.isFile) {
                                writeHttpResponse(sock, 404, "application/json; charset=utf-8", """{"error":"File not found"}""")
                            } else {
                                val json = JSONObject()
                                    .put("path", relPath)
                                    .put("content", target.readText())
                                writeHttpResponse(sock, 200, "application/json; charset=utf-8", json.toString())
                            }
                        }

                        method == "POST" && path == "/api/file" -> {
                            val payload = JSONObject(body.ifBlank { "{}" })
                            val relPath = payload.optString("path").trim().removePrefix("/")
                            val content = payload.optString("content")
                            val target = File(workspaceDir, relPath).canonicalFile
                            if (relPath.isEmpty() || !target.path.startsWith(workspaceDir.canonicalPath + File.separator)) {
                                writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Invalid path"}""")
                            } else {
                                target.parentFile?.mkdirs()
                                target.writeText(content)
                                writeHttpResponse(sock, 200, "application/json; charset=utf-8", """{"ok":true}""")
                            }
                        }

                        method == "POST" && path == "/api/chat" -> {
                            val payload = JSONObject(body.ifBlank { "{}" })
                            val prompt = payload.optString("prompt").trim()
                            val requestedModel = payload.optString("model").trim()
                            val requestedProviderId = payload.optString("providerId").trim()
                            val requestedMode = payload.optString("mode").trim().uppercase()
                            val asyncMode = payload.optBoolean("async", false)
                            if (prompt.isEmpty()) {
                                writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Empty prompt"}""")
                                return
                            }

                            if (container != null) {
                                // Route through the real native AppContainer, RunManager, AgentEngine, and shared SessionRepository
                                val resultJson = runBlocking {
                                    if (requestedProviderId.isNotEmpty() || requestedModel.isNotEmpty()) {
                                        val currentPrefs = container.settings.settings.first()
                                        val pid = requestedProviderId.ifBlank { currentPrefs.activeProviderId }
                                        val mdl = requestedModel.ifBlank { currentPrefs.activeModel }
                                        container.settings.setActiveSelection(pid, mdl)
                                    }
                                    val sid = payload.optString("sessionId").trim().takeIf { it.isNotEmpty() }
                                        ?: resolveSharedSessionId(container)
                                    container.settings.setLastActiveSessionId(sid)
                                    val prefs = container.settings.settings.first()
                                    val (providerConfig, apiKey) = resolveActiveProvider(container)
                                    val mode = if (requestedMode == "PLAN") AgentMode.PLAN else AgentMode.ACT

                                    if (container.runManager.isRunning(sid)) {
                                        container.runManager.inject(sid, prompt)
                                        return@runBlocking JSONObject()
                                            .put("ok", true)
                                            .put("queued", true)
                                            .put("sessionId", sid)
                                            .put("model", providerConfig.model)
                                            .put("reply", "Message queued into active run.")
                                    }

                                    val startedSid = container.runManager.startRun(
                                        sessionId = sid,
                                        text = prompt,
                                        imageRefs = emptyList(),
                                        config = providerConfig,
                                        apiKey = apiKey,
                                        permissionMode = prefs.permissionMode,
                                        mode = mode,
                                        maxOutputTokens = prefs.maxOutputTokens,
                                        maxContextTokens = prefs.maxContextTokens,
                                        thinking = prefs.thinkingLevel,
                                        maxIterations = prefs.maxIterations,
                                    )

                                    if (asyncMode) {
                                        return@runBlocking JSONObject()
                                            .put("ok", true)
                                            .put("started", true)
                                            .put("sessionId", startedSid)
                                            .put("model", providerConfig.model)
                                    }

                                    val deadline = System.currentTimeMillis() + 45_000L
                                    while (container.runManager.isRunning(startedSid) && System.currentTimeMillis() < deadline) {
                                        kotlinx.coroutines.delay(150)
                                    }
                                    val live = container.runManager.live(startedSid).value
                                    val latestAssistant = container.sessions.messages(startedSid)
                                        .lastOrNull { it.role == Role.ASSISTANT && it.toolCallId == null }
                                    val replyText = latestAssistant?.text?.ifBlank { live.streamingText.orEmpty() }
                                        ?: live.error
                                        ?: "Run in progress..."
                                    JSONObject()
                                        .put("ok", live.error == null)
                                        .put("sessionId", startedSid)
                                        .put("model", providerConfig.model)
                                        .put("thinking", latestAssistant?.thinking ?: live.streamingThinking.orEmpty())
                                        .put("reply", replyText)
                                }
                                writeHttpResponse(sock, 200, "application/json; charset=utf-8", resultJson.toString())
                            } else {
                                val model = requestedModel.ifBlank { HarnessProvider.DEFAULT_MODEL }
                                val reply = StringBuilder()
                                val thoughts = StringBuilder()
                                var activeModel = model
                                var errorMsg: String? = null
                                runBlocking {
                                    val provider = HarnessProvider.create()
                                    val cfg = HarnessProvider.config.copy(model = model)
                                    provider.streamChat(
                                        config = cfg,
                                        apiKey = HarnessProvider.KEYLESS,
                                        systemPrompt = "You are AndroidHarness running live on the user's Android phone.",
                                        messages = listOf(ChatMessage(role = Role.USER, text = prompt)),
                                        tools = emptyList(),
                                        options = RequestOptions(),
                                    ).collect { event ->
                                        when (event) {
                                            is StreamEvent.TextDelta -> reply.append(event.text)
                                            is StreamEvent.ThinkingDelta -> thoughts.append(event.text)
                                            is StreamEvent.ProviderChanged -> activeModel = event.config.model
                                            is StreamEvent.Failure -> errorMsg = event.message
                                            else -> {}
                                        }
                                    }
                                }
                                val res = JSONObject()
                                    .put("ok", errorMsg == null || reply.isNotEmpty())
                                    .put("model", activeModel)
                                    .put("thinking", thoughts.toString())
                                    .put("reply", if (reply.isNotEmpty()) reply.toString() else (errorMsg ?: "No response"))
                                writeHttpResponse(sock, 200, "application/json; charset=utf-8", res.toString())
                            }
                        }

                        else -> {
                            writeHttpResponse(sock, 200, "text/html; charset=utf-8", LAPTOP_WEB_HTML)
                        }
                    }
                } catch (e: Exception) {
                    val err = JSONObject().put("error", e.message ?: "Internal bridge error")
                    writeHttpResponse(sock, 500, "application/json; charset=utf-8", err.toString())
                }
            }
        }

        private fun writeHttpResponse(sock: Socket, status: Int, contentType: String, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val statusText = when (status) {
                200 -> "OK"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                404 -> "Not Found"
                413 -> "Payload Too Large"
                else -> "Error"
            }
            val headers = "HTTP/1.1 $status $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n"
            val out = sock.getOutputStream()
            out.write(headers.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        }


        /**
         * Phase 4 & Bug 2 + Phase 5 & Bug 3: Complete AndroidHarness Web & Overlay Client.
         * Parity Boundary: Provides full feature and view parity (Chat, Persistent 3-Specialist AI Team,
         * Shared Conversations, Provider/Model & Quota Status, Workspace File Manager/Editor, and Safe Settings)
         * against the native AndroidHarness AppContainer engine and shared SessionRepository state,
         * adapting responsively across phones, tablets, laptops, and the Messenger-style floating overlay WebView.
         */
        private const val LAPTOP_WEB_HTML = """<!doctype html><html lang="en"><head><meta charset="utf-8" /><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no" /><title>AndroidHarness · Complete Companion & Overlay App</title><style>
  :root { color-scheme: dark; --bg: #090D16; --surface: #0F172A; --surface2: #1E293B; --border: #26334D; --accent: #38BDF8; --accent2: #10B981; --text: #F8FAFC; --muted: #94A3B8; --danger: #EF4444; }
  * { box-sizing: border-box; }
  body { margin: 0; font-family: system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: var(--bg); color: var(--text); display: flex; flex-direction: column; height: 100vh; overflow: hidden; }
  header { display: flex; align-items: center; justify-content: space-between; padding: 10px 14px; background: var(--surface); border-bottom: 1px solid var(--border); gap: 8px; flex-shrink: 0; }
  body.overlay-mode header { padding: 6px 10px; }
  .brand { font-weight: 700; font-size: 14px; display: flex; align-items: center; gap: 8px; white-space: nowrap; }
  .brand-logo { width: 24px; height: 24px; border-radius: 7px; background: linear-gradient(135deg, #0284C7, #10B981); display: inline-flex; align-items: center; justify-content: center; font-weight: 800; font-size: 12px; color: #fff; box-shadow: 0 2px 8px rgba(16,185,129,0.3); }
  .status-pill { display: inline-flex; align-items: center; gap: 6px; padding: 3px 9px; border-radius: 999px; background: rgba(16,185,129,0.12); border: 1px solid rgba(16,185,129,0.35); font-size: 11px; color: #34D399; font-family: monospace; }
  .status-pill.offline { background: rgba(239,68,68,0.15); border-color: rgba(239,68,68,0.4); color: #F87171; }
  .dot { width: 7px; height: 7px; border-radius: 50%; background: currentColor; display: inline-block; }
  .nav-bar { display: flex; background: var(--surface); border-bottom: 1px solid var(--border); overflow-x: auto; flex-shrink: 0; }
  .nav-btn { flex: 1; min-width: 72px; padding: 9px 8px; font-size: 12px; font-weight: 600; color: var(--muted); background: transparent; border: none; border-bottom: 2px solid transparent; cursor: pointer; white-space: nowrap; text-align: center; }
  .nav-btn.active { color: var(--accent); border-bottom-color: var(--accent); background: rgba(56,189,248,0.06); }
  .reconnect-banner { display: none; background: #7F1D1D; color: #FECACA; padding: 6px 12px; font-size: 12px; text-align: center; border-bottom: 1px solid #DC2626; }
  .reconnect-banner.show { display: block; }
  .view-container { flex: 1; display: none; min-height: 0; overflow: hidden; }
  .view-container.active { display: flex; flex-direction: column; }
  .chat-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 6px; padding: 7px 12px; background: rgba(15,23,42,0.75); border-bottom: 1px solid var(--border); flex-wrap: wrap; }
  .chip { background: var(--surface2); color: var(--text); border: 1px solid var(--border); border-radius: 6px; padding: 4px 8px; font-size: 11px; cursor: pointer; }
  .chat-messages { flex: 1; overflow-y: auto; padding: 12px; display: flex; flex-direction: column; gap: 10px; }
  .msg { padding: 10px 12px; border-radius: 10px; font-size: 13px; line-height: 1.48; white-space: pre-wrap; word-break: break-word; max-width: 92%; }
  .msg.USER { background: #0369A1; color: #F8FAFC; align-self: flex-end; border-bottom-right-radius: 3px; }
  .msg.ASSISTANT { background: var(--surface2); color: var(--text); align-self: flex-start; border: 1px solid var(--border); border-bottom-left-radius: 3px; }
  .msg.SYSTEM, .msg.TOOL { background: rgba(30,41,59,0.55); color: var(--muted); font-family: monospace; font-size: 11px; align-self: stretch; max-width: 100%; }
  .msg.error { border-color: var(--danger); color: #FCA5A5; }
  .thinking-box { font-size: 11px; color: #94A3B8; border-left: 2px solid var(--accent); padding-left: 8px; margin-bottom: 6px; font-style: italic; }
  .live-bar { display: none; align-items: center; justify-content: space-between; gap: 8px; padding: 8px 12px; background: rgba(2,132,199,0.14); border-top: 1px solid rgba(56,189,248,0.3); font-size: 12px; color: #7DD3FC; }
  .live-bar.active { display: flex; }
  .prompt-card { display: none; margin: 8px 12px; padding: 10px 12px; background: #1E1B4B; border: 1px solid #6366F1; border-radius: 8px; font-size: 12px; }
  .prompt-card.active { display: block; }
  .chat-input-bar { padding: 9px 12px; background: var(--surface); border-top: 1px solid var(--border); display: flex; gap: 6px; align-items: center; }
  input[type=text], textarea, select { background: var(--bg); color: var(--text); border: 1px solid var(--border); border-radius: 7px; padding: 8px 10px; font-size: 13px; outline: none; }
  .chat-input-bar input { flex: 1; }
  button.btn { background: #0284C7; color: #fff; border: none; border-radius: 7px; padding: 8px 12px; font-size: 12px; font-weight: 600; cursor: pointer; }
  button.btn.secondary { background: var(--surface2); color: var(--text); border: 1px solid var(--border); }
  button.btn.danger { background: var(--danger); }
  .scroll-pane { flex: 1; overflow-y: auto; padding: 12px; display: flex; flex-direction: column; gap: 10px; }
  .card { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 12px; display: flex; flex-direction: column; gap: 6px; }
  .card-title { font-weight: 700; font-size: 14px; color: var(--text); display: flex; justify-content: space-between; align-items: center; }
  .card-sub { font-size: 12px; color: var(--muted); line-height: 1.4; }
  .badge { font-size: 10px; padding: 2px 7px; border-radius: 999px; background: rgba(56,189,248,0.15); color: var(--accent); font-family: monospace; }
  .files-layout { flex: 1; display: grid; grid-template-columns: 230px 1fr; min-height: 0; }
  .file-list { border-right: 1px solid var(--border); overflow-y: auto; padding: 8px; background: var(--surface); }
  .file-item { padding: 7px 9px; border-radius: 6px; cursor: pointer; font-family: monospace; font-size: 12px; color: #CBD5E1; word-break: break-all; }
  .file-item:hover, .file-item.active { background: var(--surface2); color: var(--accent); }
  .editor-pane { display: flex; flex-direction: column; min-height: 0; }
  .editor-header { padding: 8px 12px; background: var(--surface); border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; align-items: center; gap: 8px; }
  textarea#editor { flex: 1; width: 100%; border: none; border-radius: 0; font-family: monospace; font-size: 12px; line-height: 1.5; resize: none; padding: 12px; }
  @media (max-width: 760px) {
    .files-layout { grid-template-columns: 1fr; grid-template-rows: 150px 1fr; }
    .file-list { border-right: none; border-bottom: 1px solid var(--border); }
  }
</style></head><body>
<header>
  <div class="brand">
    <span class="brand-logo">AH</span>
    <span>AndroidHarness</span>
    <span id="sessionBadge" class="badge">Shared Session</span>
  </div>
  <div id="connPill" class="status-pill"><span class="dot"></span><span id="connText">Connecting...</span></div>
</header>
<div id="reconnectBanner" class="reconnect-banner">Connection interrupted — automatically reconnecting to AndroidHarness bridge...</div>
<nav class="nav-bar">
  <button class="nav-btn active" onclick="switchView('chat', this)">Chat</button>
  <button class="nav-btn" onclick="switchView('team', this)">AI Team (3)</button>
  <button class="nav-btn" onclick="switchView('sessions', this)">Chats</button>
  <button class="nav-btn" onclick="switchView('files', this)">Workspace</button>
  <button class="nav-btn" onclick="switchView('settings', this)">Models & Settings</button>
</nav>

<section id="view-chat" class="view-container active">
  <div class="chat-toolbar">
    <div style="display:flex;gap:6px;align-items:center;flex-wrap:wrap;">
      <select id="modeSelect" class="chip" title="Agent Execution Mode">
        <option value="ACT">Mode: ACT (Full Tools)</option>
        <option value="PLAN">Mode: PLAN (Read-Only)</option>
      </select>
      <span id="activeModelLabel" class="chip" onclick="switchView('settings', document.querySelectorAll('.nav-btn')[4])">Model: loading...</span>
    </div>
    <div style="display:flex;gap:6px;align-items:center;">
      <button class="chip" id="ttsToggleBtn" onclick="toggleAutoSpeak()">TTS: Off</button>
      <button class="chip" onclick="newSharedSession()">+ New Chat</button>
    </div>
  </div>
  <div id="approvalCard" class="prompt-card"></div>
  <div id="chatMessages" class="chat-messages"></div>
  <div id="liveBar" class="live-bar">
    <span id="liveStatusText">Working...</span>
    <button class="btn danger" style="padding:4px 10px;font-size:11px;" onclick="stopRun()">Stop</button>
  </div>
  <div class="chat-input-bar">
    <button class="btn secondary" id="voiceBtn" onclick="startVoiceInput()" title="Voice Input">Mic</button>
    <input type="text" id="promptInput" placeholder="Message AndroidHarness (synced with phone & bubble)..." onkeydown="if(event.key==='Enter')sendChat()" />
    <button class="btn" id="sendBtn" onclick="sendChat()">Send</button>
  </div>
</section>

<section id="view-team" class="view-container">
  <div class="scroll-pane">
    <div class="card" style="border-color:rgba(56,189,248,0.35);">
      <div class="card-title"><span>Persistent AI Specialist Team</span><span class="badge">Synchronized</span></div>
      <div class="card-sub">Your configured AI specialists persist across sessions, app restarts, the floating overlay, and remote browsers without duplicating roles.</div>
    </div>
    <div id="teamList" style="display:flex;flex-direction:column;gap:10px;"></div>
  </div>
</section>

<section id="view-sessions" class="view-container">
  <div class="scroll-pane">
    <div style="display:flex;justify-content:space-between;align-items:center;">
      <strong style="font-size:13px;">Shared Conversations (SessionRepository)</strong>
      <button class="btn" onclick="newSharedSession()">+ New Shared Chat</button>
    </div>
    <div id="sessionsList" style="display:flex;flex-direction:column;gap:8px;"></div>
  </div>
</section>

<section id="view-files" class="view-container">
  <div class="files-layout">
    <div class="file-list" id="fileList"></div>
    <div class="editor-pane">
      <div class="editor-header">
        <span id="currentFile" style="font-family:monospace;font-size:12px;color:var(--muted);">Select or create a workspace file</span>
        <div style="display:flex;gap:6px;">
          <button class="btn secondary" onclick="createNewFilePrompt()">+ New File</button>
          <button class="btn" onclick="saveFile()">Save</button>
        </div>
      </div>
      <textarea id="editor" placeholder="// Select a file on the left or click '+ New File' to edit files inside the AndroidHarness workspace..."></textarea>
    </div>
  </div>
</section>

<section id="view-settings" class="view-container">
  <div class="scroll-pane">
    <div class="card">
      <div class="card-title"><span>Active Provider & Model Selection</span><span class="badge">Live Engine</span></div>
      <div class="card-sub">Select any configured provider and model. API keys remain securely inside Android Keystore and are never exposed to the browser.</div>
      <div id="providersList" style="display:flex;flex-direction:column;gap:8px;margin-top:6px;"></div>
    </div>
    <div class="card">
      <div class="card-title"><span>Engine & Permission Settings</span></div>
      <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:6px;">
        <div>
          <label style="font-size:11px;color:var(--muted);display:block;margin-bottom:4px;">Permission Mode</label>
          <select id="permSelect" style="width:100%;" onchange="saveSettings()">
            <option value="CONFIRM_ALL">Confirm All</option>
            <option value="CONFIRM_RISKY">Confirm Risky</option>
            <option value="FULL_AUTO">Full Auto</option>
            <option value="FULL_ACCESS">Full Access</option>
          </select>
        </div>
        <div>
          <label style="font-size:11px;color:var(--muted);display:block;margin-bottom:4px;">Thinking Level</label>
          <select id="thinkingSelect" style="width:100%;" onchange="saveSettings()">
            <option value="OFF">Off</option>
            <option value="LOW">Low</option>
            <option value="MEDIUM">Medium</option>
            <option value="HIGH">High</option>
          </select>
        </div>
      </div>
    </div>
    <div class="card">
      <div class="card-title"><span>Architecture & Network Scope</span><span class="badge">Parity Boundary</span></div>
      <div class="card-sub">This Complete Web Client connects directly to AndroidHarness's native AppContainer, AgentEngine, RunManager, and SessionRepository over authenticated local HTTP (Wi-Fi LAN, Hotspot, USB Tethering, or local Overlay loopback).</div>
    </div>
  </div>
</section>

<script>
  const params = new URLSearchParams(window.location.search);
  const urlToken = params.get('token');
  if (params.get('overlay') === '1') {
    document.body.classList.add('overlay-mode');
  }
  if (urlToken) {
    sessionStorage.setItem('ah_bridge_token', urlToken);
    const cleanUrl = window.location.pathname + (params.get('overlay') === '1' ? '?overlay=1' : '');
    window.history.replaceState({}, document.title, cleanUrl);
  }
  const bridgeToken = sessionStorage.getItem('ah_bridge_token') || '';
  function apiFetch(url, options = {}) {
    const headers = Object.assign({}, options.headers || {}, {
      'Authorization': 'Bearer ' + bridgeToken
    });
    return fetch(url, Object.assign({}, options, { headers }));
  }

  let activePath = '';
  let lastRenderedSignature = '';
  let lastSpokenMessageId = '';
  let autoSpeak = false;
  let isRunning = false;

  function switchView(name, btn) {
    document.querySelectorAll('.view-container').forEach(el => el.classList.remove('active'));
    document.querySelectorAll('.nav-btn').forEach(el => el.classList.remove('active'));
    const target = document.getElementById('view-' + name);
    if (target) target.classList.add('active');
    if (btn) btn.classList.add('active');
    if (name === 'files') loadFiles();
  }

  function escapeHtml(s) {
    return String(s || '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
  }

  async function syncState() {
    try {
      const r = await apiFetch('/api/state');
      if (!r.ok) throw new Error('HTTP ' + r.status);
      const d = await r.json();
      document.getElementById('connPill').classList.remove('offline');
      document.getElementById('connText').textContent = (d.activeProvider && d.activeProvider.name ? d.activeProvider.name : 'Harness') + ' · ' + (d.activeProvider && d.activeProvider.model ? d.activeProvider.model : '');
      document.getElementById('reconnectBanner').classList.remove('show');
      document.getElementById('sessionBadge').textContent = d.sessionTitle || 'Shared Session';
      document.getElementById('activeModelLabel').textContent = 'Model: ' + (d.activeProvider && d.activeProvider.model ? d.activeProvider.model : 'default');

      if (d.settings) {
        autoSpeak = !!d.settings.ttsAlwaysSpeak;
        document.getElementById('ttsToggleBtn').textContent = 'TTS: ' + (autoSpeak ? 'On' : 'Off');
        const permEl = document.getElementById('permSelect');
        if (permEl && document.activeElement !== permEl) permEl.value = d.settings.permissionMode || 'CONFIRM_RISKY';
        const thinkEl = document.getElementById('thinkingSelect');
        if (thinkEl && document.activeElement !== thinkEl) thinkEl.value = d.settings.thinkingLevel || 'OFF';
      }

      isRunning = !!(d.live && d.live.running);
      const liveBar = document.getElementById('liveBar');
      if (isRunning) {
        liveBar.classList.add('active');
        document.getElementById('liveStatusText').textContent = d.live.actionText || d.live.retryStatus || 'Agent working...';
      } else {
        liveBar.classList.remove('active');
      }

      renderApprovalOrQuestion(d.live);
      renderMessages(d.messages || [], d.live || {});
      renderTeam(d.team || []);
      renderSessions(d.sessions || []);
      renderProviders(d.providers || [], d.activeProvider || {});
    } catch (e) {
      document.getElementById('connPill').classList.add('offline');
      document.getElementById('connText').textContent = 'Reconnecting...';
      document.getElementById('reconnectBanner').classList.add('show');
    }
  }

  function renderApprovalOrQuestion(live) {
    const card = document.getElementById('approvalCard');
    if (!live) { card.classList.remove('active'); return; }
    if (live.pendingApproval && live.pendingApproval.tool) {
      card.classList.add('active');
      card.innerHTML = '<strong>Approval Needed: ' + escapeHtml(live.pendingApproval.tool) + '</strong><div style="margin:4px 0;">' +
        escapeHtml(live.pendingApproval.description) + '</div><div style="display:flex;gap:6px;margin-top:6px;">' +
        '<button class="btn" onclick="sendApproval(\'approve\', true)">Approve</button>' +
        '<button class="btn danger" onclick="sendApproval(\'deny\', false)">Deny</button></div>';
    } else if (live.pendingQuestion && live.pendingQuestion.question) {
      card.classList.add('active');
      card.innerHTML = '<strong>Agent Question:</strong><div style="margin:4px 0;">' + escapeHtml(live.pendingQuestion.question) + '</div>';
    } else {
      card.classList.remove('active');
    }
  }

  async function sendApproval(action, remember) {
    await apiFetch('/api/approval', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({ action, remember }) });
    syncState();
  }

  function renderMessages(messages, live) {
    const sig = JSON.stringify({ m: messages.map(x => [x.id, x.text, x.thinking]), s: live.streamingText, t: live.streamingThinking, e: live.error });
    if (sig === lastRenderedSignature) return;
    lastRenderedSignature = sig;
    const box = document.getElementById('chatMessages');
    box.innerHTML = '';
    if (messages.length === 0 && !live.streamingText && !live.streamingThinking) {
      const welcome = document.createElement('div');
      welcome.className = 'msg ASSISTANT';
      welcome.textContent = 'AndroidHarness Complete Companion is synchronized with your phone session and 3-specialist AI team.';
      box.appendChild(welcome);
    }
    messages.forEach(m => {
      const div = document.createElement('div');
      div.className = 'msg ' + (m.role || 'ASSISTANT') + (m.isError ? ' error' : '');
      let html = '';
      if (m.thinking) {
        html += '<div class="thinking-box">' + escapeHtml(m.thinking) + '</div>';
      }
      if (m.toolName) {
        html += '<div style="font-size:10px;color:var(--accent);margin-bottom:3px;">Tool: ' + escapeHtml(m.toolName) + '</div>';
      }
      html += escapeHtml(m.text);
      div.innerHTML = html;
      box.appendChild(div);
    });
    if (live.streamingThinking || live.streamingText) {
      const streamDiv = document.createElement('div');
      streamDiv.className = 'msg ASSISTANT';
      let sHtml = '';
      if (live.streamingThinking) sHtml += '<div class="thinking-box">' + escapeHtml(live.streamingThinking) + '</div>';
      sHtml += escapeHtml(live.streamingText || 'Thinking...');
      streamDiv.innerHTML = sHtml;
      box.appendChild(streamDiv);
    }
    if (live.error) {
      const errDiv = document.createElement('div');
      errDiv.className = 'msg ASSISTANT error';
      errDiv.textContent = live.error;
      box.appendChild(errDiv);
    }
    box.scrollTop = box.scrollHeight;

    const lastAssistant = [...messages].reverse().find(x => x.role === 'ASSISTANT' && x.text);
    if (autoSpeak && lastAssistant && lastAssistant.id && lastAssistant.id !== lastSpokenMessageId && 'speechSynthesis' in window) {
      lastSpokenMessageId = lastAssistant.id;
      window.speechSynthesis.cancel();
      window.speechSynthesis.speak(new SpeechSynthesisUtterance(lastAssistant.text.slice(0, 600)));
    }
  }

  function renderTeam(team) {
    const list = document.getElementById('teamList');
    if (!list) return;
    list.innerHTML = team.map(sp =>
      '<div class="card">' +
        '<div class="card-title"><span>' + escapeHtml(sp.name) + '</span><span class="badge">' + escapeHtml(sp.model || 'Active Model') + '</span></div>' +
        '<div class="card-sub">' + escapeHtml(sp.description) + '</div>' +
        '<div style="font-size:11px;color:var(--muted);">Persona: ' + escapeHtml(sp.persona) + ' · Shared History: ' + (sp.sharedHistory ? 'Enabled' : 'Private') + '</div>' +
        '<div style="display:flex;gap:6px;margin-top:4px;">' +
          '<button class="btn" onclick="askSpecialist(\'' + escapeHtml(sp.name).replace(/'/g, '') + '\')">Consult in Shared Chat</button>' +
          '<button class="btn secondary" onclick="startSpecialistSession(\'' + escapeHtml(sp.id) + '\', \'' + escapeHtml(sp.name).replace(/'/g, '') + '\')">Dedicated Session</button>' +
        '</div>' +
      '</div>'
    ).join('');
  }

  function askSpecialist(name) {
    switchView('chat', document.querySelectorAll('.nav-btn')[0]);
    const input = document.getElementById('promptInput');
    input.value = '@' + name + ': ';
    input.focus();
  }

  async function startSpecialistSession(specialistId, name) {
    await apiFetch('/api/session/new', {
      method: 'POST',
      headers: {'Content-Type':'application/json'},
      body: JSON.stringify({ title: name + ' Session', specialistId })
    });
    switchView('chat', document.querySelectorAll('.nav-btn')[0]);
    syncState();
  }

  function renderSessions(sessions) {
    const list = document.getElementById('sessionsList');
    if (!list) return;
    list.innerHTML = sessions.map(s =>
      '<div class="card" style="cursor:pointer;border-color:' + (s.active ? 'var(--accent)' : 'var(--border)') + ';" onclick="selectSession(\'' + escapeHtml(s.id) + '\')">' +
        '<div class="card-title"><span>' + escapeHtml(s.title) + '</span>' + (s.active ? '<span class="badge">Active on Phone & Web</span>' : '') + '</div>' +
      '</div>'
    ).join('');
  }

  async function selectSession(sessionId) {
    await apiFetch('/api/session/select', {
      method: 'POST',
      headers: {'Content-Type':'application/json'},
      body: JSON.stringify({ sessionId })
    });
    switchView('chat', document.querySelectorAll('.nav-btn')[0]);
    syncState();
  }

  async function newSharedSession() {
    await apiFetch('/api/session/new', {
      method: 'POST',
      headers: {'Content-Type':'application/json'},
      body: JSON.stringify({ title: 'New Conversation' })
    });
    switchView('chat', document.querySelectorAll('.nav-btn')[0]);
    syncState();
  }

  function renderProviders(providers, activeProvider) {
    const list = document.getElementById('providersList');
    if (!list) return;
    list.innerHTML = providers.map(p => {
      const isSelected = p.id === activeProvider.id;
      const models = (p.models || []).map(m =>
        '<option value="' + escapeHtml(m.id) + '" ' + (isSelected && m.id === activeProvider.model ? 'selected' : '') + '>' + escapeHtml(m.id) + (m.free ? ' (Free)' : '') + '</option>'
      ).join('');
      return '<div class="card" style="border-color:' + (isSelected ? 'var(--accent)' : 'var(--border)') + ';">' +
        '<div class="card-title"><span>' + escapeHtml(p.name) + ' (' + escapeHtml(p.type) + ')</span><span class="badge">' + (p.hasKey ? 'Ready' : 'Key Required in Phone App') + '</span></div>' +
        '<div class="card-sub">' + escapeHtml(p.rateLimitSummary || '') + '</div>' +
        '<div style="display:flex;gap:6px;align-items:center;margin-top:4px;">' +
          '<select id="model-sel-' + escapeHtml(p.id) + '" style="flex:1;">' + models + '</select>' +
          '<button class="btn" onclick="selectProviderModel(\'' + escapeHtml(p.id) + '\')">Use Model</button>' +
        '</div>' +
      '</div>';
    }).join('');
  }

  async function selectProviderModel(providerId) {
    const sel = document.getElementById('model-sel-' + providerId);
    const model = sel ? sel.value : '';
    await apiFetch('/api/model/select', {
      method: 'POST',
      headers: {'Content-Type':'application/json'},
      body: JSON.stringify({ providerId, model })
    });
    syncState();
  }

  async function saveSettings() {
    const permissionMode = document.getElementById('permSelect').value;
    const thinkingLevel = document.getElementById('thinkingSelect').value;
    await apiFetch('/api/settings', {
      method: 'POST',
      headers: {'Content-Type':'application/json'},
      body: JSON.stringify({ permissionMode, thinkingLevel, ttsAlwaysSpeak: autoSpeak })
    });
    syncState();
  }

  async function toggleAutoSpeak() {
    autoSpeak = !autoSpeak;
    await apiFetch('/api/settings', {
      method: 'POST',
      headers: {'Content-Type':'application/json'},
      body: JSON.stringify({ ttsAlwaysSpeak: autoSpeak })
    });
    syncState();
  }

  async function sendChat() {
    const input = document.getElementById('promptInput');
    const prompt = input.value.trim();
    if (!prompt) return;
    input.value = '';
    const mode = document.getElementById('modeSelect').value || 'ACT';
    await apiFetch('/api/chat', {
      method: 'POST',
      headers: {'Content-Type':'application/json'},
      body: JSON.stringify({ prompt, mode, async: true })
    });
    syncState();
  }

  async function stopRun() {
    await apiFetch('/api/stop', { method: 'POST' });
    syncState();
  }

  function startVoiceInput() {
    const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SR) {
      document.getElementById('promptInput').placeholder = 'Browser SpeechRecognition unsupported on this device';
      return;
    }
    const rec = new SR();
    rec.lang = 'en-US';
    rec.onresult = (e) => {
      const transcript = e.results[0][0].transcript;
      document.getElementById('promptInput').value = transcript;
    };
    rec.start();
  }

  async function loadFiles() {
    const r = await apiFetch('/api/files');
    const d = await r.json();
    const list = document.getElementById('fileList');
    list.innerHTML = '';
    (d.files || []).forEach(f => {
      const div = document.createElement('div');
      div.className = 'file-item' + (f.path === activePath ? ' active' : '');
      div.textContent = f.path + ' (' + f.size + 'B)';
      div.onclick = () => openFile(f.path);
      list.appendChild(div);
    });
  }

  async function openFile(p) {
    activePath = p;
    document.getElementById('currentFile').textContent = p;
    const r = await apiFetch('/api/file?path=' + encodeURIComponent(p));
    const d = await r.json();
    document.getElementById('editor').value = d.content || '';
    loadFiles();
  }

  function createNewFilePrompt() {
    const name = prompt('Enter relative workspace file path (e.g. src/App.kt):');
    if (!name) return;
    activePath = name.trim().replace(/^\/+/, '');
    document.getElementById('currentFile').textContent = activePath;
    document.getElementById('editor').value = '';
  }

  async function saveFile() {
    if (!activePath) { createNewFilePrompt(); if (!activePath) return; }
    await apiFetch('/api/file', {
      method: 'POST',
      headers: {'Content-Type':'application/json'},
      body: JSON.stringify({ path: activePath, content: document.getElementById('editor').value })
    });
    loadFiles();
  }

  syncState();
  setInterval(() => syncState(), 1200);
</script></body></html>"""
    }
}
