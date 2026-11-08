package com.androidharness.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.wifi.WifiManager
import android.os.PowerManager
import java.io.ByteArrayOutputStream
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
import com.androidharness.app.qrcode.QrBitmapGenerator
import android.widget.ImageView
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
    private lateinit var shortLinkText: TextView
    private lateinit var qrImageView: ImageView
    private lateinit var usbCableButton: Button
    private lateinit var enableButton: Button
    private lateinit var copyButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_laptop_connection)

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        statusText = findViewById(R.id.laptop_status)
        addressText = findViewById(R.id.laptop_ip)
        shortLinkText = findViewById(R.id.laptop_short_link) ?: TextView(this)
        qrImageView = findViewById(R.id.laptop_qr_image) ?: ImageView(this)
        usbCableButton = findViewById(R.id.laptop_usb_cable_button) ?: Button(this)
        enableButton = findViewById(R.id.laptop_enable_button)
        copyButton = findViewById(R.id.laptop_copy_button)

        usbCableButton.setOnClickListener {
            startUsbTethering()
        }

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
        val shortUrl = "http://$ip:$activePort/c"
        statusText.text = getString(R.string.laptop_status_tethering_on)
        statusText.setTextColor(Color.parseColor("#15803D"))
        isTetheringEnabled = true
        enableButton.text = getString(R.string.laptop_disable_tethering)
        copyButton.isEnabled = true
        addressText.text = url
        shortLinkText.text = "Short link: $shortUrl"

        // Generate instant QR Code for zero-typing connect on laptop or 2nd phone
        runCatching {
            val qrBitmap = QrBitmapGenerator.createQrBitmap(url, sizePx = 480)
            qrImageView.setImageBitmap(qrBitmap)
        }
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

        @Volatile
        private var bridgeWakeLock: PowerManager.WakeLock? = null

        @Volatile
        private var bridgeWifiLock: WifiManager.WifiLock? = null

        @Volatile
        private var cachedIconBytes: ByteArray? = null

        @Volatile
        private var cachedIconDataUri: String? = null

        val pairedToken: String
            get() = bridgeToken

        fun isServerRunning(): Boolean = serverSocket?.isClosed == false

        fun localOverlayUrl(context: Context): String {
            ensureServerRunning(context)
            return "http://127.0.0.1:$activePort/?token=$bridgeToken&overlay=1"
        }

        fun buildWebClientHtml(token: String = bridgeToken, overlayMode: Boolean = false): String {
            val injectedBoot = "<script>window.__HARNESS_BOOT_TOKEN__ = \"" +
                token.replace("\"", "\\\"") +
                "\"; window.__HARNESS_OVERLAY__ = " +
                (if (overlayMode) "true" else "false") + ";</script>"
            return LAPTOP_WEB_HTML.replace("<!--__HARNESS_BOOT__-->", injectedBoot)
        }

        fun getAppIconPngBytes(context: Context): ByteArray? {
            cachedIconBytes?.let { return it }
            return runCatching {
                val drawable = context.packageManager.getApplicationIcon(context.applicationInfo)
                val size = 96
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, size, size)
                drawable.draw(canvas)
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                val bytes = out.toByteArray()
                cachedIconBytes = bytes
                cachedIconDataUri = "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                bytes
            }.getOrNull()
        }

        fun getAppIconDataUri(context: Context): String {
            cachedIconDataUri?.let { return it }
            getAppIconPngBytes(context)
            return cachedIconDataUri ?: ""
        }

        @Synchronized
        private fun acquireBridgeLocks(appContext: Context) {
            runCatching {
                val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (bridgeWakeLock == null && pm != null) {
                    bridgeWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "harness:lan_bridge").apply {
                        setReferenceCounted(false)
                    }
                }
                if (bridgeWakeLock?.isHeld == false) {
                    bridgeWakeLock?.acquire(12L * 60 * 60 * 1000)
                }
            }
            runCatching {
                val wm = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (bridgeWifiLock == null && wm != null) {
                    @Suppress("DEPRECATION")
                    val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    } else {
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    }
                    bridgeWifiLock = wm.createWifiLock(mode, "harness:lan_wifi").apply {
                        setReferenceCounted(false)
                    }
                }
                if (bridgeWifiLock?.isHeld == false) {
                    bridgeWifiLock?.acquire()
                }
            }
            runCatching {
                AgentService.acquireKeepalive(appContext.applicationContext)
                (appContext.applicationContext as? HarnessApp)?.container?.runManager?.acquireBackgroundKeepalive()
            }
        }

        @Synchronized
        fun ensureServerRunning(appContext: Context) {
            appContextRef = appContext.applicationContext
            acquireBridgeLocks(appContext.applicationContext)
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

                if (method == "GET" && path == "/api/app-icon.png") {
                    val png = appContextRef?.let { getAppIconPngBytes(it) }
                    if (png != null) {
                        val headers = "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${png.size}\r\nCache-Control: public, max-age=3600\r\nConnection: close\r\n\r\n"
                        val out = sock.getOutputStream()
                        out.write(headers.toByteArray(Charsets.UTF_8))
                        out.write(png)
                        out.flush()
                        return
                    }
                }

                val queryToken = query.split("&").firstOrNull { it.startsWith("token=") }?.substringAfter("token=").orEmpty()
                // Instant short link redirect: http://<ip>:8765/c redirects automatically to the authorized UI with cookie/token
                if (method == "GET" && (path == "/c" || path == "/connect" || path == "/god-ai")) {
                    val redirectUrl = "/?token=$bridgeToken"
                    val headers = "HTTP/1.1 302 Found\r\nLocation: $redirectUrl\r\nSet-Cookie: godai_token=$bridgeToken; Path=/; SameSite=Lax\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    sock.getOutputStream().write(headers.toByteArray(Charsets.UTF_8))
                    sock.getOutputStream().flush()
                    return
                }
                if (path.startsWith("/api/") && authorization != "Bearer $bridgeToken" && queryToken != bridgeToken) {
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

                        method == "GET" && (path == "/api/state" || path == "/api/status") -> {
                            if (container == null) {
                                val fallbackJson = JSONObject()
                                    .put("ok", true)
                                    .put("sessionId", "default")
                                    .put("sessionTitle", "AndroidHarness")
                                    .put("appIconDataUri", appContextRef?.let { getAppIconDataUri(it) } ?: "")
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

                                val toolResultByCallId = HashMap<String, ChatMessage>()
                                val subagentAssistByCallId = HashMap<String, ChatMessage>()
                                val taskCallIds = HashSet<String>()
                                msgs.forEach { m ->
                                    m.toolCalls.forEach { tc ->
                                        if (tc.name == "task" || tc.id.startsWith("team-")) {
                                            taskCallIds.add(tc.id)
                                        }
                                    }
                                    val tcid = m.toolCallId
                                    if (!tcid.isNullOrBlank()) {
                                        if (m.role == Role.TOOL) toolResultByCallId[tcid] = m
                                        else if (m.role == Role.ASSISTANT) subagentAssistByCallId[tcid] = m
                                    }
                                }
                                val runningCallIds = live.runningCalls.map { it.id }.toSet()

                                val msgArr = JSONArray()
                                msgs.takeLast(120).forEach { m ->
                                    if (m.toolCallId != null && m.role == Role.ASSISTANT) return@forEach
                                    if (m.role == Role.TOOL && m.toolCallId != null && m.toolCallId in taskCallIds) return@forEach

                                    val subagentsArr = JSONArray()
                                    val otherToolsArr = JSONArray()
                                    m.toolCalls.forEach { tc ->
                                        val parsedArgs = runCatching { JSONObject(tc.argumentsJson) }.getOrNull()
                                        val resMsg = toolResultByCallId[tc.id] ?: subagentAssistByCallId[tc.id]
                                        val isRunningCall = tc.id in runningCallIds || (live.running && resMsg == null && tc.id.startsWith("team-"))
                                        if (tc.name == "task" || tc.id.startsWith("team-")) {
                                            val stepsArr = JSONArray()
                                            live.subagentSteps[tc.id].orEmpty().forEach { stepsArr.put(it) }
                                            subagentsArr.put(
                                                JSONObject()
                                                    .put("id", tc.id)
                                                    .put("name", parsedArgs?.optString("name")?.ifBlank { "Developer Specialist" } ?: "Developer Specialist")
                                                    .put("model", parsedArgs?.optString("model") ?: activeProvider.model)
                                                    .put("prompt", parsedArgs?.optString("prompt") ?: "")
                                                    .put("running", isRunningCall)
                                                    .put("isError", resMsg?.isError ?: false)
                                                    .put("result", resMsg?.text ?: "")
                                                    .put("steps", stepsArr)
                                            )
                                        } else {
                                            otherToolsArr.put(
                                                JSONObject()
                                                    .put("id", tc.id)
                                                    .put("name", tc.name)
                                                    .put("args", tc.argumentsJson.take(240))
                                                    .put("running", isRunningCall)
                                                    .put("isError", resMsg?.isError ?: false)
                                                    .put("result", resMsg?.text?.take(600) ?: "")
                                            )
                                        }
                                    }

                                    msgArr.put(
                                        JSONObject()
                                            .put("id", m.id ?: "")
                                            .put("role", m.role.name)
                                            .put("text", m.text)
                                            .put("thinking", m.thinking)
                                            .put("thinkingMs", m.thinkingMs)
                                            .put("toolName", m.toolName ?: "")
                                            .put("isError", m.isError)
                                            .put("createdAt", m.createdAt)
                                            .put("model", activeProvider.model)
                                            .put("subagents", subagentsArr)
                                            .put("tools", otherToolsArr)
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

                                settingsObj.put("runInBackground", prefs.runInBackground)
                                JSONObject()
                                    .put("ok", true)
                                    .put("sessionId", sid)
                                    .put("sessionTitle", currentSession?.title ?: "AndroidHarness")
                                    .put("appIconDataUri", appContextRef?.let { getAppIconDataUri(it) } ?: "")
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
                                if (payload.has("runInBackground")) {
                                    container.settings.setRunInBackground(payload.optBoolean("runInBackground"))
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

                        method == "POST" && (path == "/api/approval" || path == "/api/control") -> {
                            if (container != null) {
                                val payload = JSONObject(body.ifBlank { "{}" })
                                val action = payload.optString("action")
                                runBlocking {
                                    val sid = resolveSharedSessionId(container)
                                    when (action) {
                                        "stop" -> container.runManager.stop(sid)
                                        "approve" -> container.runManager.approve(sid, payload.optBoolean("remember", false))
                                        "approve_always" -> container.runManager.approve(sid, true)
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
                                            .put("name", rel)
                                            .put("path", rel)
                                            .put("isDir", false)
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
                                .firstOrNull { it.firstOrNull() == "path" || it.firstOrNull() == "name" }
                                ?.getOrNull(1)
                                ?.let { URLDecoder.decode(it, "UTF-8") }
                                .orEmpty()
                            val target = File(workspaceDir, relPath).canonicalFile
                            if (!target.path.startsWith(workspaceDir.canonicalPath + File.separator) || !target.isFile) {
                                writeHttpResponse(sock, 404, "application/json; charset=utf-8", """{"error":"File not found"}""")
                            } else {
                                val json = JSONObject()
                                    .put("name", relPath)
                                    .put("path", relPath)
                                    .put("content", target.readText())
                                writeHttpResponse(sock, 200, "application/json; charset=utf-8", json.toString())
                            }
                        }

                        method == "POST" && path == "/api/file" -> {
                            val payload = JSONObject(body.ifBlank { "{}" })
                            val relPath = payload.optString("name", payload.optString("path")).trim().removePrefix("/")
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

                            val specialistId = payload.optString("specialistId").trim()
                            if (container != null) {
                                appContextRef?.let { acquireBridgeLocks(it) }
                                // Route through the real native AppContainer, RunManager, AgentEngine, and shared SessionRepository
                                val resultJson = runBlocking {
                                    val specialists = PersistentSpecialistTeam.mergePreservingConfigured(container.settings.settings.first().chatAgents)
                                    val chosenSpec = specialists.firstOrNull { it.id == specialistId }
                                    val effectivePrompt = if (chosenSpec != null) {
                                        "[Specialist Role: ${chosenSpec.name} — ${chosenSpec.description}]\n${chosenSpec.instructions}\n\nUser request: $prompt"
                                    } else prompt
                                    if (requestedProviderId.isNotEmpty() || requestedModel.isNotEmpty()) {
                                        val currentPrefs = container.settings.settings.first()
                                        val pid: String = requestedProviderId.ifBlank { currentPrefs.activeProviderId ?: HarnessProvider.ID }
                                        val mdl: String? = requestedModel.ifBlank { currentPrefs.activeModel ?: HarnessProvider.DEFAULT_MODEL }
                                        container.settings.setActiveSelection(pid, mdl)
                                    }
                                    val sid = payload.optString("sessionId").trim().takeIf { it.isNotEmpty() }
                                        ?: resolveSharedSessionId(container)
                                    container.settings.setLastActiveSessionId(sid)
                                    val prefs = container.settings.settings.first()
                                    val (providerConfig, apiKey) = resolveActiveProvider(container)
                                    val mode = if (requestedMode == "PLAN") AgentMode.PLAN else AgentMode.ACT

                                    if (container.runManager.isRunning(sid)) {
                                        container.runManager.inject(sid, effectivePrompt)
                                        return@runBlocking JSONObject()
                                            .put("ok", true)
                                            .put("queued", true)
                                            .put("sessionId", sid)
                                            .put("model", providerConfig.model)
                                            .put("reply", "Message queued into active run.")
                                    }

                                    val startedSid = container.runManager.startRun(
                                        sessionId = sid,
                                        text = effectivePrompt,
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
                            val isOverlay = query.contains("overlay=1")
                            val html = buildWebClientHtml(
                                token = if (queryToken == bridgeToken) bridgeToken else "",
                                overlayMode = isOverlay,
                            )
                            writeHttpResponse(sock, 200, "text/html; charset=utf-8", html)
                        }
                    }
                } catch (e: Exception) {
                    val err = JSONObject().put("error", e.message ?: "Internal bridge error")
                    writeHttpResponse(sock, 500, "application/json; charset=utf-8", err.toString())
                }
            }
        }

        fun handleDirectApi(context: Context, method: String, pathAndQuery: String, body: String): String {
            ensureServerRunning(context)
            return try {
                val cleanPath = if (pathAndQuery.startsWith("/")) pathAndQuery else "/$pathAndQuery"
                val url = java.net.URL("http://127.0.0.1:$activePort$cleanPath")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = method.uppercase()
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $bridgeToken")
                conn.connectTimeout = 5_000
                conn.readTimeout = 45_000
                if (method.uppercase() == "POST" && body.isNotEmpty()) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader()?.use { it.readText() } ?: """{"ok":false}"""
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: "Bridge error").toString()
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
        private const val LAPTOP_WEB_HTML = """<!doctype html><html lang="en"><head><meta charset="utf-8" /><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover" /><title>AndroidHarness</title><link rel="icon" href="/api/app-icon.png" /><link rel="apple-touch-icon" href="/api/app-icon.png" /><!--__HARNESS_BOOT__--><style>
  :root {
    color-scheme: dark;
    --bg: #111318;
    --surface-lowest: #0C0E13;
    --surface-low: #191C21;
    --surface: #1D2025;
    --surface-high: #272A30;
    --surface-highest: #32353B;
    --primary: #A8C7FA;
    --on-primary: #062168;
    --primary-container: #0B57D0;
    --on-primary-container: #D1E4FF;
    --secondary: #BCC7DC;
    --secondary-container: #3C4758;
    --on-secondary-container: #D8E3F9;
    --on-surface: #E2E2E9;
    --on-surface-variant: #C4C6D0;
    --outline: #8E9099;
    --outline-variant: #44474E;
    --error: #FFB4AB;
    --error-container: #93000A;
    --success: #4ADE80;
  }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  html, body {
    margin: 0; padding: 0; width: 100%; height: 100%;
    font-family: 'Google Sans', Roboto, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
    background: var(--bg); color: var(--on-surface);
    display: flex; flex-direction: column; overflow: hidden;
  }
  /* Native MainHeader */
  .main-header {
    display: flex; align-items: center; gap: 6px;
    min-height: 52px; padding: 6px 10px;
    background: var(--bg); border-bottom: 1px solid rgba(68,71,78,0.45);
    flex-shrink: 0; z-index: 20;
  }
  body.overlay-mode .main-header { min-height: 44px; padding: 4px 8px; }
  .icon-btn {
    width: 38px; height: 38px; border-radius: 50%; border: none;
    background: transparent; color: var(--on-surface);
    display: inline-flex; align-items: center; justify-content: center;
    cursor: pointer; flex-shrink: 0; transition: background 0.15s;
  }
  body.overlay-mode .icon-btn { width: 32px; height: 32px; }
  .icon-btn:hover, .icon-btn:active { background: var(--surface-high); }
  .icon-btn.active-toggle { background: var(--primary-container); color: var(--on-primary-container); }
  .header-center {
    flex: 1; min-width: 0; display: flex; align-items: center; gap: 6px; overflow: hidden;
  }
  .model-picker-pill {
    display: inline-flex; align-items: center; gap: 6px;
    max-width: 100%; padding: 6px 12px; border-radius: 999px;
    background: var(--surface-low); border: 1px solid var(--outline-variant);
    color: var(--on-surface); font-size: 13px; font-weight: 600;
    cursor: pointer; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  }
  body.overlay-mode .model-picker-pill { padding: 4px 9px; font-size: 11.5px; }
  .model-picker-pill .model-sub { color: var(--primary); font-weight: 500; overflow: hidden; text-overflow: ellipsis; }
  .thinking-pill {
    display: inline-flex; align-items: center; gap: 4px;
    padding: 5px 9px; border-radius: 999px;
    background: var(--surface-low); border: 1px solid var(--outline-variant);
    color: var(--on-surface-variant); font-size: 11px; font-weight: 600;
    cursor: pointer; flex-shrink: 0;
  }
  .thinking-pill.on { background: rgba(11,87,208,0.28); border-color: var(--primary); color: var(--primary); }

  /* Reconnect / Status Banner */
  .reconnect-bar {
    display: none; align-items: center; justify-content: space-between;
    padding: 6px 12px; background: var(--error-container); color: #FFDAD6;
    font-size: 12px; flex-shrink: 0;
  }
  .reconnect-bar.show { display: flex; }

  /* Slide-out Navigation Drawer (1:1 with native AndroidHarness Drawer) */
  .drawer-backdrop {
    position: fixed; inset: 0; background: rgba(0,0,0,0.62);
    opacity: 0; pointer-events: none; transition: opacity 0.2s ease; z-index: 90;
  }
  .drawer-backdrop.open { opacity: 1; pointer-events: auto; }
  .nav-drawer {
    position: fixed; top: 0; bottom: 0; left: 0;
    width: min(310px, 86vw); background: var(--surface-lowest);
    border-right: 1px solid var(--outline-variant);
    transform: translateX(-102%); transition: transform 0.22s cubic-bezier(0.2, 0, 0, 1);
    z-index: 100; display: flex; flex-direction: column; overflow: hidden;
  }
  .nav-drawer.open { transform: translateX(0); }
  .drawer-header {
    display: flex; align-items: center; gap: 10px;
    padding: 14px 16px 10px; border-bottom: 1px solid rgba(68,71,78,0.4);
  }
  .app-logo-img {
    width: 34px; height: 34px; border-radius: 10px; object-fit: cover;
    background: var(--primary-container); flex-shrink: 0;
  }
  .drawer-actions {
    display: grid; grid-template-columns: 1fr 1fr; gap: 8px; padding: 12px 14px 8px;
  }
  .btn-primary-tonal {
    display: inline-flex; align-items: center; justify-content: center; gap: 6px;
    padding: 9px 12px; border-radius: 12px; border: 1px solid rgba(168,199,250,0.25);
    background: var(--primary-container); color: var(--on-primary-container);
    font-size: 12.5px; font-weight: 600; cursor: pointer;
  }
  .btn-secondary-tonal {
    display: inline-flex; align-items: center; justify-content: center; gap: 6px;
    padding: 9px 12px; border-radius: 12px; border: 1px solid var(--outline-variant);
    background: var(--surface); color: var(--on-surface);
    font-size: 12.5px; font-weight: 600; cursor: pointer;
  }
  .drawer-scroll { flex: 1; overflow-y: auto; padding: 6px 10px 14px; }
  .drawer-section-label {
    font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.06em;
    color: var(--on-surface-variant); padding: 10px 8px 6px;
    display: flex; align-items: center; justify-content: space-between;
  }
  .team-mini-item {
    display: flex; align-items: center; gap: 9px; padding: 7px 9px;
    border-radius: 10px; cursor: pointer; margin-bottom: 3px;
    color: var(--on-surface); font-size: 12.5px;
  }
  .team-mini-item:hover, .team-mini-item.active { background: var(--surface-high); }
  .team-mini-badge {
    width: 26px; height: 26px; border-radius: 8px;
    background: rgba(11,87,208,0.35); color: var(--primary);
    display: inline-flex; align-items: center; justify-content: center;
    font-size: 13px; flex-shrink: 0;
  }
  .session-row {
    display: flex; align-items: center; justify-content: space-between; gap: 8px;
    padding: 9px 10px; border-radius: 10px; cursor: pointer; margin-bottom: 3px;
    color: var(--on-surface-variant); font-size: 13px;
  }
  .session-row:hover { background: var(--surface); color: var(--on-surface); }
  .session-row.active { background: var(--surface-high); color: var(--primary); font-weight: 600; }
  .drawer-footer {
    display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px;
    padding: 10px 12px; border-top: 1px solid rgba(68,71,78,0.45);
    background: var(--surface-low);
  }
  .drawer-foot-btn {
    padding: 8px 6px; border-radius: 10px; border: 1px solid var(--outline-variant);
    background: var(--surface); color: var(--on-surface); font-size: 11.5px; font-weight: 600;
    cursor: pointer; text-align: center;
  }

  /* Main Chat Stream & Native EmptyState */
  .chat-viewport {
    flex: 1; min-height: 0; overflow-y: auto; padding: 14px 14px 10px;
    display: flex; flex-direction: column; gap: 14px;
  }
  body.overlay-mode .chat-viewport { padding: 10px 10px 6px; gap: 10px; }
  .empty-state-wrap {
    margin: auto; width: 100%; max-width: 480px;
    display: flex; flex-direction: column; align-items: center; text-align: center;
    padding: 18px 8px;
  }
  .empty-logo {
    width: 56px; height: 56px; border-radius: 16px; margin-bottom: 14px;
    box-shadow: 0 6px 24px rgba(11,87,208,0.35); object-fit: cover;
  }
  body.overlay-mode .empty-logo { width: 42px; height: 42px; border-radius: 12px; margin-bottom: 8px; }
  .empty-headline {
    font-size: 22px; font-weight: 600; color: var(--on-surface); margin: 0 0 8px;
    letter-spacing: -0.2px;
  }
  body.overlay-mode .empty-headline { font-size: 17px; margin-bottom: 4px; }
  .empty-sub {
    font-size: 13.5px; color: var(--on-surface-variant); margin: 0 0 18px; line-height: 1.45;
  }
  body.overlay-mode .empty-sub { font-size: 12px; margin-bottom: 12px; }
  .suggestion-list {
    width: 100%; background: var(--surface-low); border: 1px solid rgba(68,71,78,0.5);
    border-radius: 16px; overflow: hidden; text-align: left;
  }
  .suggestion-item {
    display: flex; align-items: center; gap: 12px; padding: 12px 14px;
    color: var(--on-surface); font-size: 13.5px; cursor: pointer;
    border-bottom: 1px solid rgba(68,71,78,0.35); background: transparent;
    width: 100%; border-left: none; border-right: none; border-top: none; text-align: left;
  }
  body.overlay-mode .suggestion-item { padding: 9px 11px; font-size: 12px; }
  .suggestion-item:last-child { border-bottom: none; }
  .suggestion-item:hover { background: var(--surface-high); }
  .suggestion-item .sug-icon { color: var(--primary); font-size: 15px; flex-shrink: 0; }
  .suggestion-item .sug-text { flex: 1; }
  .suggestion-item .sug-arrow { color: var(--outline); font-size: 14px; }

  /* Developer Team Horizontal Quick Bar in EmptyState */
  .dev-team-strip {
    display: flex; gap: 6px; width: 100%; overflow-x: auto; padding-bottom: 10px; margin-bottom: 6px;
  }
  .dev-pill {
    display: inline-flex; align-items: center; gap: 6px; padding: 6px 10px;
    border-radius: 999px; background: var(--surface); border: 1px solid var(--outline-variant);
    color: var(--on-surface); font-size: 11.5px; font-weight: 500; white-space: nowrap; cursor: pointer;
  }
  .dev-pill.active { background: var(--primary-container); border-color: var(--primary); color: var(--on-primary-container); }

  /* Native Bubbles & Turn Layout */
  .msg-row { display: flex; flex-direction: column; width: 100%; }
  .msg-user {
    align-self: flex-end; max-width: 85%;
    background: var(--primary-container); color: var(--on-primary-container);
    padding: 10px 14px; border-radius: 18px 18px 4px 18px;
    font-size: 14px; line-height: 1.48; white-space: pre-wrap; word-break: break-word;
  }
  body.overlay-mode .msg-user { font-size: 13px; padding: 8px 12px; }
  .msg-assistant {
    align-self: flex-start; width: 100%; max-width: 100%;
    color: var(--on-surface); font-size: 14px; line-height: 1.55;
    display: flex; flex-direction: column; gap: 8px;
  }
  body.overlay-mode .msg-assistant { font-size: 13px; }
  .assistant-header {
    display: flex; align-items: center; gap: 7px; font-size: 11.5px; color: var(--on-surface-variant); font-weight: 600;
  }
  .assistant-header img { width: 18px; height: 18px; border-radius: 5px; }

  /* Native ThinkingBlock */
  .thinking-card {
    background: var(--surface-low); border: 1px solid rgba(68,71,78,0.55);
    border-radius: 12px; overflow: hidden;
  }
  .thinking-summary {
    display: flex; align-items: center; justify-content: space-between;
    padding: 8px 12px; font-size: 12px; color: var(--on-surface-variant);
    cursor: pointer; user-select: none; font-weight: 500;
  }
  .thinking-body {
    display: none; padding: 8px 12px 10px; border-top: 1px solid rgba(68,71,78,0.35);
    font-size: 12px; color: var(--on-surface-variant); white-space: pre-wrap;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace; max-height: 220px; overflow-y: auto;
  }
  .thinking-card.open .thinking-body { display: block; }

  /* Native SubagentPagerCard (6-Developer AI Specialist Team Pager Card) */
  .subagent-pager-card {
    background: var(--surface-low); border: 1px solid rgba(68,71,78,0.65);
    border-radius: 16px; overflow: hidden; width: 100%;
  }
  .subagent-pager-head {
    display: flex; align-items: center; gap: 10px; padding: 9px 12px;
    border-bottom: 1px solid rgba(68,71,78,0.4);
  }
  .subagent-icon-box {
    width: 30px; height: 30px; border-radius: 9px; background: var(--surface-high);
    color: var(--primary); display: inline-flex; align-items: center; justify-content: center;
    font-size: 14px; flex-shrink: 0;
  }
  .subagent-tabs {
    display: flex; gap: 6px; padding: 8px 10px; overflow-x: auto;
    border-bottom: 1px solid rgba(68,71,78,0.35); background: rgba(12,14,19,0.4);
  }
  .subagent-tab {
    display: inline-flex; align-items: center; gap: 5px;
    padding: 5px 10px; border-radius: 999px; font-size: 11.5px; font-weight: 600;
    background: var(--surface); color: var(--on-surface-variant);
    border: 1px solid transparent; cursor: pointer; white-space: nowrap;
  }
  .subagent-tab.active {
    background: rgba(11,87,208,0.28); border-color: var(--primary); color: var(--on-surface);
  }
  .subagent-page-body {
    padding: 10px 12px; font-size: 12.5px; line-height: 1.48; color: var(--on-surface);
    max-height: 210px; overflow-y: auto; white-space: pre-wrap;
  }
  .subagent-meta {
    font-size: 11px; color: var(--primary); font-family: monospace; margin-bottom: 4px;
  }

  /* Markdown Code Blocks */
  .code-block {
    background: var(--surface-lowest); border: 1px solid var(--outline-variant);
    border-radius: 12px; overflow: hidden; margin: 6px 0;
  }
  .code-head {
    display: flex; align-items: center; justify-content: space-between;
    padding: 6px 10px; background: var(--surface-low); border-bottom: 1px solid rgba(68,71,78,0.4);
    font-size: 11px; color: var(--on-surface-variant); font-family: monospace;
  }
  .code-pre {
    margin: 0; padding: 10px 12px; overflow-x: auto; font-size: 12px;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace; color: #E2E8F0;
  }

  /* Live Progress & Blocking Prompt Cards */
  .live-status-strip {
    display: none; align-items: center; justify-content: space-between; gap: 8px;
    margin: 0 12px 6px; padding: 8px 12px; border-radius: 12px;
    background: var(--surface); border: 1px solid rgba(168,199,250,0.35);
    font-size: 12px; color: var(--primary); flex-shrink: 0;
  }
  .live-status-strip.active { display: flex; }
  .pulse-dot {
    width: 8px; height: 8px; border-radius: 50%; background: var(--primary);
    animation: pulseAnim 1.2s infinite ease-in-out; display: inline-block;
  }
  @keyframes pulseAnim { 0%, 100% { opacity: 0.35; transform: scale(0.9); } 50% { opacity: 1; transform: scale(1.15); } }

  .approval-card {
    display: none; margin: 0 12px 8px; padding: 12px; border-radius: 14px;
    background: #1B2234; border: 1px solid var(--primary); font-size: 12.5px; flex-shrink: 0;
  }
  .approval-card.active { display: block; }

  /* Native MessageComposer Capsule */
  .composer-outer {
    padding: 6px 12px 10px; background: var(--bg); flex-shrink: 0;
  }
  body.overlay-mode .composer-outer { padding: 4px 8px 6px; }
  .composer-capsule {
    background: var(--surface-low); border: 1px solid var(--outline-variant);
    border-radius: 24px; padding: 8px 10px 8px 14px;
    display: flex; flex-direction: column; gap: 6px;
    box-shadow: 0 2px 12px rgba(0,0,0,0.25);
  }
  .composer-top-pills {
    display: flex; align-items: center; gap: 6px; overflow-x: auto;
  }
  .mini-pill {
    display: inline-flex; align-items: center; gap: 4px;
    padding: 3px 9px; border-radius: 999px; font-size: 11px; font-weight: 600;
    background: var(--surface-high); color: var(--on-surface-variant);
    border: 1px solid transparent; cursor: pointer; white-space: nowrap;
  }
  .mini-pill.active { background: rgba(11,87,208,0.3); border-color: var(--primary); color: var(--primary); }
  .composer-input-row {
    display: flex; align-items: flex-end; gap: 6px;
  }
  .composer-textarea {
    flex: 1; background: transparent; border: none; outline: none;
    color: var(--on-surface); font-size: 14.5px; line-height: 1.4;
    resize: none; max-height: 120px; min-height: 24px; padding: 4px 0;
    font-family: inherit;
  }
  body.overlay-mode .composer-textarea { font-size: 13px; max-height: 76px; }
  .composer-textarea::placeholder { color: var(--outline); }
  .send-circle-btn {
    width: 36px; height: 36px; border-radius: 50%; border: none;
    background: var(--primary); color: var(--on-primary);
    display: inline-flex; align-items: center; justify-content: center;
    cursor: pointer; flex-shrink: 0; font-weight: 700; font-size: 16px;
  }
  .send-circle-btn.stop { background: var(--error); color: #690005; }

  /* Native-Parity Modal Bottom Sheets (Workspace Files, Models, Team, Settings) */
  .sheet-backdrop {
    position: fixed; inset: 0; background: rgba(0,0,0,0.65);
    display: none; align-items: flex-end; justify-content: center; z-index: 120;
  }
  .sheet-backdrop.open { display: flex; }
  .modal-sheet {
    width: 100%; max-width: 680px; height: 84vh;
    background: var(--surface-low); border-top: 1px solid var(--outline-variant);
    border-radius: 22px 22px 0 0; display: flex; flex-direction: column; overflow: hidden;
  }
  body.overlay-mode .modal-sheet { height: 92vh; }
  .sheet-header {
    display: flex; align-items: center; justify-content: space-between;
    padding: 12px 16px; border-bottom: 1px solid rgba(68,71,78,0.45);
  }
  .sheet-title { font-size: 15px; font-weight: 600; color: var(--on-surface); }
  .sheet-body { flex: 1; overflow-y: auto; padding: 12px 14px; display: flex; flex-direction: column; gap: 10px; }

  /* Workspace File Manager & Editor inside Sheet */
  .workspace-split { display: flex; flex: 1; min-height: 0; gap: 10px; flex-direction: column; }
  @media (min-width: 640px) { .workspace-split { flex-direction: row; } }
  .file-list-pane {
    max-height: 190px; overflow-y: auto; background: var(--surface-lowest);
    border: 1px solid var(--outline-variant); border-radius: 12px; padding: 6px;
  }
  @media (min-width: 640px) { .file-list-pane { width: 230px; max-height: none; } }
  .file-entry {
    display: flex; align-items: center; justify-content: space-between;
    padding: 7px 9px; border-radius: 8px; font-size: 12px; font-family: monospace;
    color: var(--on-surface-variant); cursor: pointer; word-break: break-all;
  }
  .file-entry:hover, .file-entry.active { background: var(--surface-high); color: var(--primary); }
  .editor-pane { flex: 1; display: flex; flex-direction: column; gap: 8px; min-height: 220px; }
  .code-editor-input {
    flex: 1; width: 100%; background: var(--surface-lowest); color: #E2E8F0;
    border: 1px solid var(--outline-variant); border-radius: 12px; padding: 10px;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; resize: none;
  }
  .card-item {
    background: var(--surface); border: 1px solid rgba(68,71,78,0.55);
    border-radius: 14px; padding: 12px;
  }
</style></head><body>

  <!-- Slide-out Navigation Drawer (Matches Native Phone App) -->
  <div class="drawer-backdrop" id="drawerBackdrop" onclick="toggleDrawer(false)"></div>
  <aside class="nav-drawer" id="navDrawer" aria-label="Navigation Drawer">
    <div class="drawer-header">
      <img id="drawerLogo" class="app-logo-img" src="/api/app-icon.png" alt="AndroidHarness" />
      <div style="min-width:0;flex:1;">
        <div style="font-weight:700;font-size:15px;color:var(--on-surface);">AndroidHarness</div>
        <div id="drawerWorkspacePath" style="font-size:11px;color:var(--on-surface-variant);font-family:monospace;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">Workspace ready</div>
      </div>
      <button class="icon-btn" onclick="toggleDrawer(false)" aria-label="Close drawer">✕</button>
    </div>
    <div class="drawer-actions">
      <button class="btn-primary-tonal" onclick="createNewSession('', 'CODE')">＋ New Code</button>
      <button class="btn-secondary-tonal" onclick="createNewSession('', 'CHAT')">💬 New Chat</button>
    </div>
    <div class="drawer-scroll">
      <div class="drawer-section-label">
        <span>AI Specialist Dev Team (6)</span>
        <button class="mini-pill" onclick="openSheet('teamSheet');toggleDrawer(false);">Manage</button>
      </div>
      <div id="drawerTeamList"></div>

      <div class="drawer-section-label" style="margin-top:8px;">
        <span>Conversations</span>
        <span id="drawerSessionCount" style="font-weight:400;">0</span>
      </div>
      <div id="drawerSessionList"></div>
    </div>
    <div class="drawer-footer">
      <button class="drawer-foot-btn" onclick="openSheet('filesSheet');toggleDrawer(false);">📁 Files</button>
      <button class="drawer-foot-btn" onclick="openSheet('modelsSheet');toggleDrawer(false);">⚡ Models</button>
      <button class="drawer-foot-btn" onclick="openSheet('settingsSheet');toggleDrawer(false);">⚙️ Settings</button>
    </div>
  </aside>

  <!-- Native MainHeader -->
  <header class="main-header">
    <button class="icon-btn" onclick="toggleDrawer(true)" aria-label="Open navigation">
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="18" x2="21" y2="18"/></svg>
    </button>
    <div class="header-center">
      <button class="model-picker-pill" id="headerModelBtn" onclick="openSheet('modelsSheet')" title="Switch AI Provider & Model">
        <span id="headerSessionLabel">AndroidHarness</span>
        <span style="color:var(--outline);">·</span>
        <span class="model-sub" id="headerModelLabel">Loading…</span>
        <span style="font-size:10px;color:var(--outline);">▾</span>
      </button>
      <button class="thinking-pill" id="headerThinkingBtn" onclick="cycleThinkingLevel()" title="Cycle Thinking Effort">
        🧠 <span id="headerThinkingLabel">OFF</span>
      </button>
    </div>
    <button class="icon-btn" onclick="openSheet('filesSheet')" title="Workspace Files" aria-label="Workspace files">
      <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
    </button>
    <button class="icon-btn" id="headerVoiceToggle" onclick="toggleAlwaysSpeak()" title="1-Tap Auto-Speak Replies" aria-label="Toggle voice output">
      🔊
    </button>
    <button class="icon-btn" onclick="openSheet('settingsSheet')" title="Settings & AI Specialist Team" aria-label="Settings">
      <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
    </button>
  </header>

  <div class="reconnect-bar" id="reconnectBar">
    <span id="reconnectMsg">Reconnecting to AndroidHarness background service…</span>
    <button class="mini-pill" onclick="syncState()">Retry Now</button>
  </div>

  <!-- Main Chat Viewport -->
  <main class="chat-viewport" id="chatViewport"></main>

  <!-- Live Run Progress & Blocking Approval/Question Cards -->
  <div class="live-status-strip" id="liveStatusStrip">
    <div style="display:flex;align-items:center;gap:8px;min-width:0;">
      <span class="pulse-dot"></span>
      <span id="liveStatusText" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">Thinking…</span>
    </div>
    <button class="mini-pill" style="background:var(--error-container);color:#FFDAD6;" onclick="controlRun('stop')">■ Stop</button>
  </div>
  <div class="approval-card" id="approvalCard"></div>

  <!-- Native MessageComposer Capsule -->
  <div class="composer-outer">
    <div class="composer-capsule">
      <div class="composer-top-pills">
        <button class="mini-pill active" id="modeActPill" onclick="setAgentMode('ACT')">⚡ ACT</button>
        <button class="mini-pill" id="modePlanPill" onclick="setAgentMode('PLAN')">🧭 PLAN</button>
        <button class="mini-pill" id="specialistSelectorPill" onclick="openSheet('teamSheet')">👥 Team: All 6 Devs ▾</button>
        <button class="mini-pill" onclick="openSheet('filesSheet')">📁 Workspace</button>
      </div>
      <div class="composer-input-row">
        <textarea class="composer-textarea" id="promptInput" rows="1" placeholder="Ask AndroidHarness or build with your 6-Developer AI Team…"></textarea>
        <button class="icon-btn" id="micBtn" onclick="toggleVoiceInput()" title="Voice Dictation" aria-label="Voice input">🎙️</button>
        <button class="send-circle-btn" id="sendBtn" onclick="handleSendOrStop()" aria-label="Send message">↑</button>
      </div>
    </div>
  </div>

  <!-- Sheet 1: Workspace Files & Code Editor -->
  <div class="sheet-backdrop" id="filesSheet" onclick="if(event.target===this)closeSheets()">
    <div class="modal-sheet">
      <div class="sheet-header">
        <div>
          <div class="sheet-title">📁 Workspace File Explorer & Code Editor</div>
          <div id="sheetWorkspacePath" style="font-size:11px;color:var(--on-surface-variant);font-family:monospace;"></div>
        </div>
        <div style="display:flex;gap:6px;">
          <button class="mini-pill" onclick="promptNewFile()">＋ New File</button>
          <button class="mini-pill" onclick="refreshFiles()">↻ Refresh</button>
          <button class="icon-btn" onclick="closeSheets()">✕</button>
        </div>
      </div>
      <div class="sheet-body">
        <div class="workspace-split">
          <div class="file-list-pane" id="fileListPane">Loading workspace files…</div>
          <div class="editor-pane">
            <div style="display:flex;align-items:center;justify-content:space-between;gap:8px;">
              <span id="editingFileName" style="font-size:12px;font-family:monospace;color:var(--primary);">Select a file to inspect or edit</span>
              <button class="btn-primary-tonal" style="padding:6px 12px;" onclick="saveActiveFile()">💾 Save Changes</button>
            </div>
            <textarea class="code-editor-input" id="fileEditorContent" placeholder="File contents will appear here…"></textarea>
          </div>
        </div>
      </div>
    </div>
  </div>

  <!-- Sheet 2: AI Providers & Models -->
  <div class="sheet-backdrop" id="modelsSheet" onclick="if(event.target===this)closeSheets()">
    <div class="modal-sheet">
      <div class="sheet-header">
        <div class="sheet-title">⚡ AI Providers, Free Models & Quota Status</div>
        <button class="icon-btn" onclick="closeSheets()">✕</button>
      </div>
      <div class="sheet-body" id="modelsSheetBody"></div>
    </div>
  </div>

  <!-- Sheet 3: 6-Developer AI Specialist Team -->
  <div class="sheet-backdrop" id="teamSheet" onclick="if(event.target===this)closeSheets()">
    <div class="modal-sheet">
      <div class="sheet-header">
        <div class="sheet-title">⚡ Instant Smart Auto-Switch (Vision · Code · Fast Free)</div>
        <button class="icon-btn" onclick="closeSheets()">✕</button>
      </div>
      <div class="sheet-body" id="teamSheetBody"></div>
    </div>
  </div>

  <!-- Sheet 4: App & Runtime Settings -->
  <div class="sheet-backdrop" id="settingsSheet" onclick="if(event.target===this)closeSheets()">
    <div class="modal-sheet">
      <div class="sheet-header">
        <div class="sheet-title">⚙️ AndroidHarness Settings & Background Keepalive</div>
        <button class="icon-btn" onclick="closeSheets()">✕</button>
      </div>
      <div class="sheet-body" id="settingsSheetBody"></div>
    </div>
  </div>

<script>
  const urlParams = new URLSearchParams(window.location.search);
  if (urlParams.get('overlay') === '1' || window.__HARNESS_OVERLAY__ === true) {
    document.body.classList.add('overlay-mode');
  }
  const urlToken = urlParams.get('token');
  if (urlToken) {
    localStorage.setItem('harness_bridge_token', urlToken);
  } else if (window.__HARNESS_BOOT_TOKEN__) {
    localStorage.setItem('harness_bridge_token', window.__HARNESS_BOOT_TOKEN__);
  }

  let appState = null;
  let currentMode = 'ACT';
  let selectedSpecialistId = '';
  let currentEditingFile = '';
  let subagentActiveTabByMsg = {};
  let expandedThinkingIds = {};
  let lastSpokenText = '';
  let isSyncing = false;

  function getAuthToken() {
    return window.__HARNESS_BOOT_TOKEN__ || localStorage.getItem('harness_bridge_token') || '';
  }

  async function apiFetch(path, options = {}) {
    const method = (options.method || 'GET').toUpperCase();
    const bodyStr = options.body || '';
    // Fast 0ms in-process path when running inside FloatingBubbleService WebView
    if (window.HarnessNative && typeof window.HarnessNative.request === 'function') {
      try {
        const raw = window.HarnessNative.request(method, path, bodyStr);
        if (raw) {
          const parsed = JSON.parse(raw);
          document.getElementById('reconnectBar').classList.remove('show');
          return parsed;
        }
      } catch (e) {
        console.warn('HarnessNative fallback to HTTP:', e);
      }
    }
    const headers = Object.assign({ 'Content-Type': 'application/json' }, options.headers || {});
    const token = getAuthToken();
    if (token) headers['Authorization'] = 'Bearer ' + token;
    const res = await fetch(path, Object.assign({}, options, { headers }));
    if (res.status === 401) {
      const entered = prompt('Enter the pairing token shown on your phone in AndroidHarness → Connect Device:');
      if (entered) {
        localStorage.setItem('harness_bridge_token', entered.trim());
        return apiFetch(path, options);
      }
      throw new Error('Unauthorized (401)');
    }
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
    document.getElementById('reconnectBar').classList.remove('show');
    return data;
  }

  function escapeHtml(str) {
    return String(str || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function formatRichText(raw) {
    if (!raw) return '';
    const parts = String(raw).split(/```(\w*)\n([\s\S]*?)```/g);
    let html = '';
    for (let i = 0; i < parts.length; i += 3) {
      const prose = escapeHtml(parts[i]);
      html += prose.replace(/`([^`\n]+)`/g, '<code style="background:var(--surface-high);padding:1px 5px;border-radius:5px;font-family:monospace;font-size:12px;">${'$'}1</code>');
      if (i + 2 < parts.length) {
        const lang = escapeHtml(parts[i + 1] || 'code');
        const code = parts[i + 2];
        const encoded = encodeURIComponent(code);
        html += `<div class="code-block"><div class="code-head"><span>${'$'}{lang}</span><div style="display:flex;gap:6px;"><button class="mini-pill" onclick="copyCodeBlock(decodeURIComponent('${'$'}{encoded}'))">Copy</button><button class="mini-pill" onclick="applyCodeToWorkspace(decodeURIComponent('${'$'}{encoded}'))">Save to File</button></div></div><pre class="code-pre">${'$'}{escapeHtml(code)}</pre></div>`;
      }
    }
    return html;
  }

  function copyCodeBlock(text) {
    if (navigator.clipboard) navigator.clipboard.writeText(text);
  }

  async function applyCodeToWorkspace(code) {
    const filename = prompt('Save code block to workspace file path:', currentEditingFile || 'src/Output.kt');
    if (!filename) return;
    await apiFetch('/api/file', {
      method: 'POST',
      body: JSON.stringify({ name: filename.trim(), content: code })
    });
    currentEditingFile = filename.trim();
    openSheet('filesSheet');
    await refreshFiles();
    await loadFile(currentEditingFile);
  }

  function toggleDrawer(open) {
    document.getElementById('navDrawer').classList.toggle('open', !!open);
    document.getElementById('drawerBackdrop').classList.toggle('open', !!open);
  }

  function openSheet(id) {
    closeSheets();
    const el = document.getElementById(id);
    if (el) el.classList.add('open');
    if (id === 'filesSheet') refreshFiles();
  }

  function closeSheets() {
    document.querySelectorAll('.sheet-backdrop').forEach(el => el.classList.remove('open'));
  }

  function setAgentMode(mode) {
    currentMode = mode;
    document.getElementById('modeActPill').classList.toggle('active', mode === 'ACT');
    document.getElementById('modePlanPill').classList.toggle('active', mode === 'PLAN');
  }

  function selectSpecialistRole(id, name) {
    selectedSpecialistId = id || '';
    const label = id ? ('👤 ' + name + ' ▾') : '👥 Team: All 6 Devs ▾';
    document.getElementById('specialistSelectorPill').textContent = label;
    closeSheets();
    renderChatViewport();
  }

  function switchSubagentTab(msgKey, idx) {
    subagentActiveTabByMsg[msgKey] = idx;
    renderChatViewport();
  }

  function toggleThinkingCard(id) {
    expandedThinkingIds[id] = !expandedThinkingIds[id];
    renderChatViewport();
  }

  function renderSubagentPagerCard(msgKey, subagents) {
    if (!subagents || !subagents.length) return '';
    const runningCount = subagents.filter(s => s.running).length;
    const doneCount = subagents.filter(s => !s.running && !s.isError).length;
    const activeIdx = Math.min(subagentActiveTabByMsg[msgKey] || 0, subagents.length - 1);
    const activeSub = subagents[activeIdx] || subagents[0];
    const headerTitle = runningCount > 0
      ? `Running ${'$'}{runningCount} of ${'$'}{subagents.length} developer specialists`
      : `Spawned ${'$'}{subagents.length} developer specialists`;
    const headerSub = runningCount > 0 ? 'Collaborating in parallel' : `${'$'}{doneCount} completed`;

    const tabsHtml = subagents.map((s, idx) => {
      const icon = s.running ? '⏳' : (s.isError ? '⚠️' : '✓');
      return `<button class="subagent-tab ${'$'}{idx === activeIdx ? 'active' : ''}" onclick="switchSubagentTab('${'$'}{msgKey}', ${'$'}{idx})"><span>${'$'}{icon}</span><span>${'$'}{escapeHtml(s.name)}</span></button>`;
    }).join('');

    const stepsText = (activeSub.steps && activeSub.steps.length)
      ? activeSub.steps.map(st => '• ' + escapeHtml(st)).join('\n') + '\n\n'
      : '';
    const bodyText = escapeHtml(activeSub.result || (activeSub.running ? 'Generating specialist blueprint…' : 'Completed'));

    return `<div class="subagent-pager-card">
      <div class="subagent-pager-head">
        <div class="subagent-icon-box">✨</div>
        <div style="flex:1;min-width:0;">
          <div style="font-size:12.5px;font-weight:600;color:var(--on-surface);">${'$'}{headerTitle}</div>
          <div style="font-size:11px;color:var(--on-surface-variant);">${'$'}{headerSub}</div>
        </div>
      </div>
      <div class="subagent-tabs">${'$'}{tabsHtml}</div>
      <div class="subagent-page-body"><div class="subagent-meta">${'$'}{escapeHtml(activeSub.name)} · ${'$'}{escapeHtml(activeSub.model || '')}</div>${'$'}{stepsText}${'$'}{bodyText}</div>
    </div>`;
  }

  function renderChatViewport() {
    if (!appState) return;
    const viewport = document.getElementById('chatViewport');
    const wasNearBottom = viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight < 120;
    const msgs = appState.messages || [];
    const live = appState.live || {};
    const iconSrc = appState.appIconDataUri || '/api/app-icon.png';
    const team = appState.team || [];

    if (msgs.length === 0 && !live.running) {
      const teamPills = `<div class="dev-team-strip">` +
        `<button class="dev-pill ${'$'}{!selectedSpecialistId ? 'active' : ''}" onclick="selectSpecialistRole('','All 6 Devs')">✨ All 6 Devs</button>` +
        team.map(sp => `<button class="dev-pill ${'$'}{selectedSpecialistId === sp.id ? 'active' : ''}" onclick="selectSpecialistRole('${'$'}{sp.id}','${'$'}{escapeHtml(sp.name)}')">👨‍💻 ${'$'}{escapeHtml(sp.name)}</button>`).join('') +
        `</div>`;

      const activeSpec = team.find(t => t.id === selectedSpecialistId);
      const headline = activeSpec ? activeSpec.name : 'What do you want to build?';
      const subtitle = activeSpec
        ? activeSpec.description
        : 'Type a prompt or upload an image. GOD-AI automatically chooses the optimal Vision, Coding, or Fast Free model.';
      const suggestions = (activeSpec && activeSpec.starters && activeSpec.starters.length)
        ? activeSpec.starters
        : [
            'Create a Python hello world and run it',
            'Explore my workspace and summarize it',
            'Build a responsive HTML landing page',
            'Audit architecture with the 6-Developer AI Team'
          ];

      viewport.innerHTML = `<div class="empty-state-wrap">
        <img class="empty-logo" src="${'$'}{iconSrc}" alt="AndroidHarness" />
        <h1 class="empty-headline">${'$'}{escapeHtml(headline)}</h1>
        <p class="empty-sub">${'$'}{escapeHtml(subtitle)}</p>
        ${'$'}{teamPills}
        <div class="suggestion-list">
          ${'$'}{suggestions.map(s => `<button class="suggestion-item" onclick="sendQuickPrompt('${'$'}{escapeHtml(s).replace(/'/g, "\\'")}')"><span class="sug-icon">⚡</span><span class="sug-text">${'$'}{escapeHtml(s)}</span><span class="sug-arrow">→</span></button>`).join('')}
        </div>
      </div>`;
      return;
    }

    let html = '';
    msgs.forEach((m, idx) => {
      const msgKey = m.id || ('m-' + idx);
      if (m.role === 'USER') {
        html += `<div class="msg-row"><div class="msg-user">${'$'}{escapeHtml(m.text)}</div></div>`;
      } else if (m.role === 'ASSISTANT') {
        const isThinkOpen = !!expandedThinkingIds[msgKey];
        const thinkingHtml = m.thinking
          ? `<div class="thinking-card ${'$'}{isThinkOpen ? 'open' : ''}"><div class="thinking-summary" onclick="toggleThinkingCard('${'$'}{msgKey}')"><span>🧠 Thought process ${'$'}{m.thinkingMs ? '(' + Math.max(1, Math.round(m.thinkingMs / 1000)) + 's)' : ''}</span><span>${'$'}{isThinkOpen ? '▾' : '▸'}</span></div><div class="thinking-body">${'$'}{escapeHtml(m.thinking)}</div></div>`
          : '';
        const subagentHtml = renderSubagentPagerCard(msgKey, m.subagents || []);
        const toolsHtml = (m.tools && m.tools.length)
          ? `<div style="display:flex;flex-wrap:wrap;gap:6px;">` + m.tools.map(t => `<span class="mini-pill" title="${'$'}{escapeHtml(t.args)}">🔧 ${'$'}{escapeHtml(t.name)} ${'$'}{t.isError ? '⚠️' : '✓'}</span>`).join('') + `</div>`
          : '';
        html += `<div class="msg-row"><div class="msg-assistant">
          <div class="assistant-header"><img src="${'$'}{iconSrc}" alt="" /><span>AndroidHarness</span><span style="color:var(--outline);">·</span><span style="font-family:monospace;color:var(--primary);">${'$'}{escapeHtml(m.model || (appState.activeProvider && appState.activeProvider.model) || '')}</span></div>
          ${'$'}{thinkingHtml}
          ${'$'}{subagentHtml}
          ${'$'}{toolsHtml}
          ${'$'}{m.text ? `<div>${'$'}{formatRichText(m.text)}</div>` : ''}
        </div></div>`;
      } else if (m.role === 'TOOL' && m.text) {
        html += `<div class="msg-row"><div class="thinking-card"><div class="thinking-summary" onclick="this.parentElement.classList.toggle('open')"><span>🔧 Tool output: ${'$'}{escapeHtml(m.toolName || 'tool')}</span><span>▸</span></div><div class="thinking-body">${'$'}{escapeHtml(m.text)}</div></div></div>`;
      }
    });

    if (live.running && (live.streamingText || live.streamingThinking)) {
      html += `<div class="msg-row"><div class="msg-assistant" style="border-left:2px solid var(--primary);padding-left:10px;">
        <div class="assistant-header"><span class="pulse-dot"></span><span>Streaming live response…</span></div>
        ${'$'}{live.streamingThinking ? `<div class="thinking-card open"><div class="thinking-body" style="display:block;">${'$'}{escapeHtml(live.streamingThinking)}</div></div>` : ''}
        ${'$'}{live.streamingText ? `<div>${'$'}{formatRichText(live.streamingText)}</div>` : ''}
      </div></div>`;
    }

    viewport.innerHTML = html;
    if (wasNearBottom) viewport.scrollTop = viewport.scrollHeight;
  }

  function renderDrawerAndSheets() {
    if (!appState) return;
    const iconSrc = appState.appIconDataUri || '/api/app-icon.png';
    document.getElementById('drawerLogo').src = iconSrc;
    document.getElementById('drawerWorkspacePath').textContent = appState.workspace || '';
    document.getElementById('sheetWorkspacePath').textContent = appState.workspace || '';

    const activeProv = appState.activeProvider || {};
    document.getElementById('headerSessionLabel').textContent = appState.sessionTitle || 'AndroidHarness';
    document.getElementById('headerModelLabel').textContent = activeProv.model || 'Select Model';

    const settings = appState.settings || {};
    const tLevel = settings.thinkingLevel || 'OFF';
    const thinkBtn = document.getElementById('headerThinkingBtn');
    document.getElementById('headerThinkingLabel').textContent = tLevel;
    thinkBtn.classList.toggle('on', tLevel !== 'OFF');

    const voiceBtn = document.getElementById('headerVoiceToggle');
    voiceBtn.classList.toggle('active-toggle', !!settings.ttsAlwaysSpeak);

    const live = appState.live || {};
    const sendBtn = document.getElementById('sendBtn');
    if (live.running) {
      sendBtn.classList.add('stop');
      sendBtn.textContent = '■';
    } else {
      sendBtn.classList.remove('stop');
      sendBtn.textContent = '↑';
    }

    // Drawer Team
    const team = appState.team || [];
    document.getElementById('drawerTeamList').innerHTML = team.map(sp => `
      <div class="team-mini-item ${'$'}{selectedSpecialistId === sp.id ? 'active' : ''}" onclick="selectSpecialistRole('${'$'}{sp.id}','${'$'}{escapeHtml(sp.name)}');toggleDrawer(false);">
        <div class="team-mini-badge">👨‍💻</div>
        <div style="min-width:0;flex:1;">
          <div style="font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">${'$'}{escapeHtml(sp.name)}</div>
          <div style="font-size:10.5px;color:var(--on-surface-variant);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">${'$'}{escapeHtml(sp.model)}</div>
        </div>
      </div>
    `).join('');

    // Drawer Sessions
    const sessions = appState.sessions || [];
    document.getElementById('drawerSessionCount').textContent = String(sessions.length);
    document.getElementById('drawerSessionList').innerHTML = sessions.map(s => `
      <div class="session-row ${'$'}{s.active ? 'active' : ''}" onclick="selectSession('${'$'}{s.id}');toggleDrawer(false);">
        <span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">${'$'}{s.kind === 'CHAT' ? '💬 ' : '💻 '}${'$'}{escapeHtml(s.title)}</span>
        ${'$'}{s.active ? '<span style="font-size:10px;color:var(--primary);">●</span>' : ''}
      </div>
    `).join('');

    // Live strip & Approval / Question banner
    const liveStrip = document.getElementById('liveStatusStrip');
    if (live.running || live.retryStatus) {
      liveStrip.classList.add('active');
      document.getElementById('liveStatusText').textContent = live.retryStatus || live.actionText || 'GOD-AI thinking & generating in background…';
    } else {
      liveStrip.classList.remove('active');
    }

    const approvalCard = document.getElementById('approvalCard');
    if (live.pendingApproval) {
      approvalCard.classList.add('active');
      approvalCard.innerHTML = `<div style="font-weight:700;color:var(--primary);margin-bottom:4px;">Permission Required: ${'$'}{escapeHtml(live.pendingApproval.tool)}</div>
        <div style="color:var(--on-surface-variant);margin-bottom:8px;">${'$'}{escapeHtml(live.pendingApproval.description)}</div>
        <div style="display:flex;gap:8px;">
          <button class="btn-primary-tonal" onclick="controlRun('approve')">Approve</button>
          <button class="btn-secondary-tonal" onclick="controlRun('approve_always')">Always Allow</button>
          <button class="btn-secondary-tonal" style="color:var(--error);" onclick="controlRun('deny')">Deny</button>
        </div>`;
    } else if (live.pendingQuestion) {
      approvalCard.classList.add('active');
      const opts = (live.pendingQuestion.options || []).map(o =>
        `<button class="btn-secondary-tonal" onclick="answerPrompt('${'$'}{escapeHtml(o).replace(/'/g, "\\'")}')">${'$'}{escapeHtml(o)}</button>`
      ).join('');
      approvalCard.innerHTML = `<div style="font-weight:700;color:var(--primary);margin-bottom:6px;">Agent Question: ${'$'}{escapeHtml(live.pendingQuestion.question)}</div>
        <div style="display:flex;gap:6px;flex-wrap:wrap;">${'$'}{opts}</div>`;
    } else {
      approvalCard.classList.remove('active');
    }

    // Sheet 2: Providers & Models
    const providers = appState.providers || [];
    document.getElementById('modelsSheetBody').innerHTML = providers.map(p => {
      const isSelectedProv = activeProv.id === p.id;
      const modelsPills = (p.models || []).map(m => {
        const active = isSelectedProv && activeProv.model === m.id;
        return `<button class="mini-pill ${'$'}{active ? 'active' : ''}" style="padding:6px 10px;" onclick="selectProviderModel('${'$'}{p.id}','${'$'}{escapeHtml(m.id)}')">${'$'}{escapeHtml(m.id)}${'$'}{m.free ? ' · FREE' : ''}</button>`;
      }).join('');
      return `<div class="card-item" style="border-color:${'$'}{isSelectedProv ? 'var(--primary)' : 'rgba(68,71,78,0.55)'}">
        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:4px;">
          <strong style="font-size:14px;">${'$'}{escapeHtml(p.name)}</strong>
          <span class="mini-pill" style="color:${'$'}{p.hasKey ? 'var(--success)' : 'var(--on-surface-variant)'}">${'$'}{p.hasKey ? '✓ Ready' : 'Key Required'}</span>
        </div>
        <div style="font-size:11.5px;color:var(--on-surface-variant);margin-bottom:8px;">${'$'}{escapeHtml(p.rateLimitSummary || '')}</div>
        <div style="display:flex;flex-wrap:wrap;gap:6px;">${'$'}{modelsPills}</div>
      </div>`;
    }).join('');

    // Sheet 3: 6-Developer AI Specialist Team
    document.getElementById('teamSheetBody').innerHTML = `<div class="card-item" style="display:flex;justify-content:space-between;align-items:center;">
      <div>
        <div style="font-weight:700;">All 6 Developers (Parallel Multi-Agent Mode)</div>
        <div style="font-size:12px;color:var(--on-surface-variant);">Spawns all 6 specialized developers simultaneously on complex coding & architecture tasks.</div>
      </div>
      <button class="btn-primary-tonal" onclick="selectSpecialistRole('','All 6 Devs')">Use Full Team</button>
    </div>` + team.map(sp => `
      <div class="card-item">
        <div style="display:flex;justify-content:space-between;align-items:flex-start;gap:8px;">
          <div>
            <div style="font-weight:700;font-size:14px;color:var(--primary);">👨‍💻 ${'$'}{escapeHtml(sp.name)}</div>
            <div style="font-size:11.5px;color:var(--on-surface-variant);margin:2px 0 6px;">${'$'}{escapeHtml(sp.persona)}</div>
          </div>
          <div style="display:flex;gap:6px;">
            <button class="mini-pill active" onclick="selectSpecialistRole('${'$'}{sp.id}','${'$'}{escapeHtml(sp.name)}')">Focus Role</button>
            <button class="mini-pill" onclick="createNewSession('${'$'}{sp.id}','CHAT');closeSheets();">New Chat</button>
          </div>
        </div>
        <div style="font-size:12.5px;color:var(--on-surface);margin-bottom:6px;">${'$'}{escapeHtml(sp.description)}</div>
        <div style="font-size:11px;font-family:monospace;color:var(--on-surface-variant);">Model: ${'$'}{escapeHtml(sp.providerId)} · ${'$'}{escapeHtml(sp.model)}</div>
      </div>
    `).join('');

    // Sheet 4: Settings
    document.getElementById('settingsSheetBody').innerHTML = `
      <div class="card-item">
        <div style="font-weight:700;margin-bottom:6px;">🔋 Background Execution & Bridge Keepalive</div>
        <div style="font-size:12px;color:var(--on-surface-variant);margin-bottom:10px;">Keeps AI generation, foreground service WakeLock, and LAN bridge alive when switching apps or minimizing AndroidHarness.</div>
        <button class="btn-primary-tonal" onclick="updateSetting({runInBackground: !(appState.settings && appState.settings.runInBackground)})">
          ${'$'}{settings.runInBackground !== false ? '✓ Active in Background (Protected)' : 'Enable Background Execution'}
        </button>
      </div>
      <div class="card-item">
        <div style="font-weight:700;margin-bottom:6px;">🛡️ Tool Permission Mode</div>
        <div style="display:flex;gap:8px;">
          <button class="mini-pill ${'$'}{settings.permissionMode === 'AUTO' ? 'active' : ''}" style="padding:7px 14px;" onclick="updateSetting({permissionMode:'AUTO'})">AUTO (Autonomous)</button>
          <button class="mini-pill ${'$'}{settings.permissionMode === 'ASK' ? 'active' : ''}" style="padding:7px 14px;" onclick="updateSetting({permissionMode:'ASK'})">ASK (Confirm Edits)</button>
        </div>
      </div>
      <div class="card-item">
        <div style="font-weight:700;margin-bottom:6px;">🧠 Thinking / Reasoning Effort</div>
        <div style="display:flex;gap:6px;flex-wrap:wrap;">
          ${'$'}{['OFF','LOW','MEDIUM','HIGH','MAX'].map(lvl => `<button class="mini-pill ${'$'}{settings.thinkingLevel === lvl ? 'active' : ''}" style="padding:6px 12px;" onclick="updateSetting({thinkingLevel:'${'$'}{lvl}'})">${'$'}{lvl}</button>`).join('')}
        </div>
      </div>
      <div class="card-item">
        <div style="font-weight:700;margin-bottom:6px;">🔊 Voice & Memory</div>
        <div style="display:flex;gap:8px;flex-wrap:wrap;">
          <button class="mini-pill ${'$'}{settings.ttsAlwaysSpeak ? 'active' : ''}" style="padding:7px 12px;" onclick="toggleAlwaysSpeak()">🔊 Auto-Speak Replies: ${'$'}{settings.ttsAlwaysSpeak ? 'ON' : 'OFF'}</button>
          <button class="mini-pill ${'$'}{settings.memoryEnabled ? 'active' : ''}" style="padding:7px 12px;" onclick="updateSetting({memoryEnabled:!settings.memoryEnabled})">🧠 Persistent Memory: ${'$'}{settings.memoryEnabled ? 'ON' : 'OFF'}</button>
        </div>
      </div>
    `;
  }

  async function syncState() {
    if (isSyncing) return;
    isSyncing = true;
    try {
      const data = await apiFetch('/api/status');
      const prevMsgs = (appState && appState.messages) ? appState.messages.length : 0;
      appState = data;
      renderDrawerAndSheets();
      renderChatViewport();

      const msgs = data.messages || [];
      if (data.settings && data.settings.ttsAlwaysSpeak && msgs.length > prevMsgs) {
        const last = msgs[msgs.length - 1];
        if (last && last.role === 'ASSISTANT' && last.text && last.text !== lastSpokenText && window.speechSynthesis) {
          lastSpokenText = last.text;
          window.speechSynthesis.cancel();
          window.speechSynthesis.speak(new SpeechSynthesisUtterance(last.text.slice(0, 500)));
        }
      }
    } catch (e) {
      document.getElementById('reconnectBar').classList.add('show');
    } finally {
      isSyncing = false;
    }
  }

  async function sendQuickPrompt(text) {
    const input = document.getElementById('promptInput');
    input.value = text;
    await sendPrompt();
  }

  async function handleSendOrStop() {
    if (appState && appState.live && appState.live.running) {
      await controlRun('stop');
    } else {
      await sendPrompt();
    }
  }

  async function sendPrompt() {
    const input = document.getElementById('promptInput');
    const prompt = input.value.trim();
    if (!prompt) return;
    input.value = '';
    input.style.height = 'auto';
    await apiFetch('/api/chat', {
      method: 'POST',
      body: JSON.stringify({
        prompt,
        specialistId: selectedSpecialistId,
        mode: currentMode,
        async: true
      })
    });
    await syncState();
  }

  async function selectSession(sessionId) {
    await apiFetch('/api/session/select', {
      method: 'POST',
      body: JSON.stringify({ sessionId })
    });
    await syncState();
  }

  async function createNewSession(specialistId, kind) {
    await apiFetch('/api/session/new', {
      method: 'POST',
      body: JSON.stringify({ specialistId: specialistId || '', kind: kind || 'CODE' })
    });
    await syncState();
  }

  async function selectProviderModel(providerId, model) {
    await apiFetch('/api/model/select', {
      method: 'POST',
      body: JSON.stringify({ providerId, model })
    });
    closeSheets();
    await syncState();
  }

  async function updateSetting(patch) {
    await apiFetch('/api/settings', {
      method: 'POST',
      body: JSON.stringify(patch)
    });
    await syncState();
  }

  async function cycleThinkingLevel() {
    const order = ['OFF', 'LOW', 'MEDIUM', 'HIGH', 'MAX'];
    const cur = (appState && appState.settings && appState.settings.thinkingLevel) || 'OFF';
    const next = order[(order.indexOf(cur) + 1) % order.length];
    await updateSetting({ thinkingLevel: next });
  }

  async function toggleAlwaysSpeak() {
    const cur = !!(appState && appState.settings && appState.settings.ttsAlwaysSpeak);
    await updateSetting({ ttsAlwaysSpeak: !cur });
  }

  async function controlRun(action) {
    await apiFetch('/api/control', {
      method: 'POST',
      body: JSON.stringify({ action })
    });
    await syncState();
  }

  async function answerPrompt(answer) {
    await apiFetch('/api/control', {
      method: 'POST',
      body: JSON.stringify({ action: 'answer', answer })
    });
    await syncState();
  }

  async function refreshFiles() {
    const data = await apiFetch('/api/files');
    const pane = document.getElementById('fileListPane');
    const list = (data.files || []).filter(f => !f.isDir);
    if (!list.length) {
      pane.innerHTML = '<div style="padding:10px;font-size:12px;color:var(--on-surface-variant);">Workspace is empty. Click "＋ New File" to create one.</div>';
      return;
    }
    pane.innerHTML = list.map(f => `
      <div class="file-entry ${'$'}{currentEditingFile === f.name ? 'active' : ''}" onclick="loadFile('${'$'}{escapeHtml(f.name).replace(/'/g, "\\'")}')">
        <span>📄 ${'$'}{escapeHtml(f.name)}</span>
        <span style="color:var(--outline);font-size:10px;">${'$'}{f.size}B</span>
      </div>
    `).join('');
  }

  async function loadFile(name) {
    currentEditingFile = name;
    document.getElementById('editingFileName').textContent = name;
    const data = await apiFetch('/api/file?name=' + encodeURIComponent(name));
    document.getElementById('fileEditorContent').value = data.content || '';
    refreshFiles();
  }

  async function saveActiveFile() {
    const name = currentEditingFile || prompt('Enter file path to save:', 'main.kt');
    if (!name) return;
    currentEditingFile = name.trim();
    const content = document.getElementById('fileEditorContent').value;
    await apiFetch('/api/file', {
      method: 'POST',
      body: JSON.stringify({ name: currentEditingFile, content })
    });
    document.getElementById('editingFileName').textContent = currentEditingFile + ' (Saved ✓)';
    await refreshFiles();
  }

  async function promptNewFile() {
    const name = prompt('New file relative path (e.g. src/App.kt):');
    if (!name) return;
    currentEditingFile = name.trim();
    document.getElementById('editingFileName').textContent = currentEditingFile;
    document.getElementById('fileEditorContent').value = '';
    await saveActiveFile();
  }

  function toggleVoiceInput() {
    if (window.HarnessNative && typeof window.HarnessNative.startVoice === 'function') {
      window.HarnessNative.startVoice();
      return;
    }
    const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SR) {
      alert('Speech recognition is handled via the phone mic or supported browser.');
      return;
    }
    const rec = new SR();
    rec.lang = navigator.language || 'en-US';
    rec.onresult = (ev) => {
      const transcript = ev.results[0][0].transcript;
      const input = document.getElementById('promptInput');
      input.value = (input.value ? input.value + ' ' : '') + transcript;
    };
    rec.start();
  }

  const promptEl = document.getElementById('promptInput');
  promptEl.addEventListener('input', () => {
    promptEl.style.height = 'auto';
    promptEl.style.height = Math.min(promptEl.scrollHeight, 120) + 'px';
  });
  promptEl.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendPrompt();
    }
  });

  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) syncState();
  });

  syncState();
  setInterval(syncState, 1200);
</script></body></html>"""
    }
}
