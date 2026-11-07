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
import com.androidharness.app.data.ConversationConfig
import com.androidharness.app.data.ConversationKind
import com.androidharness.app.llm.effectiveApiKey
import com.androidharness.app.llm.HarnessProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import android.util.Base64
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
        @Volatile
        private var serverSocket: ServerSocket? = null

        @Volatile
        var activePort: Int = 8080
            private set

        // Bound concurrent bridge clients so a burst of LAN requests cannot
        // create an unbounded number of threads on the phone.
        private val executor = ThreadPoolExecutor(
            8, 8, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(32),
            ThreadPoolExecutor.AbortPolicy(),
        )
        private val bridgeChatBusy = AtomicBoolean(false)
        private const val REMOTE_SESSION_PREFS = "androidharness_remote_bridge"
        private const val REMOTE_SESSION_KEY = "shared_chat_session_id"

        /**
         * The remote browser is a client of the native chat engine, not a separate
         * provider-backed chatbot. Keep one app-managed CHAT session for all bridge clients.
         */
        private suspend fun getOrCreateRemoteSession(app: HarnessApp): String {
            val container = app.container
            val prefs = app.getSharedPreferences(REMOTE_SESSION_PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getString(REMOTE_SESSION_KEY, null)
            if (!existing.isNullOrBlank() && container.sessions.session(existing) != null) {
                return existing
            }
            val id = container.sessions.createSession(
                title = "AndroidHarness Remote",
                conversation = ConversationConfig(kind = ConversationKind.CHAT),
            )
            container.chatModels.initialize(id)
            prefs.edit().putString(REMOTE_SESSION_KEY, id).apply()
            return id
        }

        private fun remoteHistoryJson(messages: List<ChatMessage>): JSONArray = JSONArray().apply {
            messages.asSequence()
                .filter { it.role == com.androidharness.app.core.Role.USER ||
                    it.role == com.androidharness.app.core.Role.ASSISTANT }
                .takeLast(40)
                .forEach { message ->
                    put(JSONObject()
                        .put("role", message.role.name.lowercase())
                        .put("content", message.text))
                }
        }

        // A per-installation bridge secret. The paired URL is shown only on the phone.
        internal fun localBridgeAuthorization(): String = "Bearer $bridgeToken"

        private fun isBridgeAuthorized(authorization: String): Boolean =
            java.security.MessageDigest.isEqual(
                authorization.toByteArray(Charsets.UTF_8),
                "Bearer $bridgeToken".toByteArray(Charsets.UTF_8),
            )

        private val bridgeToken: String by lazy {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }

        @Synchronized
        fun ensureServerRunning(appContext: Context) {
            if (serverSocket != null && serverSocket?.isClosed == false) return
            val workspaceDir = File(appContext.filesDir, "workspace").apply { mkdirs() }
            for (port in 8080..8085) {
                try {
                    val socket = ServerSocket(port)
                    serverSocket = socket
                    activePort = port
                    Thread({
                        while (!socket.isClosed) {
                            try {
                                val client = socket.accept()
                                try {
                                    executor.execute { handleClient(client, workspaceDir) }
                                } catch (_: RejectedExecutionException) {
                                    // Tell the caller to retry instead of silently dropping a busy-bridge connection.
                                    runCatching {
                                        writeHttpResponse(
                                            client,
                                            503,
                                            "application/json; charset=utf-8",
                                            """{"error":"AndroidHarness bridge is busy; retry shortly"}""",
                                        )
                                        client.close()
                                    }
                                }
                            } catch (_: Exception) {
                                if (socket.isClosed) break
                            }
                        }
                    }, "AndroidHarness-Bridge-Accept").apply { isDaemon = true }.start()
                    break
                } catch (_: Exception) {
                }
            }
        }

        private fun isBridgeInternalPath(relativePath: String): Boolean =
            relativePath.split('/', '\\\\').any { segment ->
                segment == ".androidharness-chat-history.json" ||
                    segment.endsWith(".androidharness-chat-history.json.next")
            }

        private fun handleClient(client: Socket, workspaceDir: File) {
            client.use { sock ->
                sock.soTimeout = 150_000
                val input = sock.getInputStream()
                var httpLineTooLong = false
                fun readHttpLine(): String? {
                    val bytes = java.io.ByteArrayOutputStream()
                    httpLineTooLong = false
                    while (bytes.size() < 16 * 1024) {
                        val next = input.read()
                        if (next < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.ISO_8859_1.name())
                        if (next == 10) break
                        if (next != 13) bytes.write(next)
                    }
                    if (bytes.size() >= 16 * 1024) httpLineTooLong = true
                    return bytes.toString(Charsets.ISO_8859_1.name())
                }
                val requestLine = readHttpLine() ?: return
                if (httpLineTooLong) {
                    writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"HTTP request line exceeds the 16 KB limit"}""")
                    return
                }
                val parts = requestLine.split(" ")
                if (parts.size != 3 || parts.any { it.isBlank() } ||
                    (parts[2] != "HTTP/1.0" && parts[2] != "HTTP/1.1") ||
                    !parts[1].startsWith("/") || parts[1].contains('\\')) {
                    writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Malformed HTTP request line"}""")
                    return
                }
                val method = parts[0].uppercase()
                val fullPath = parts[1]
                val path = fullPath.substringBefore("?")
                val query = fullPath.substringAfter("?", "")

                var contentLength = 0
                var contentLengthSeen = false
                var invalidContentLength = false
                var unsupportedTransferEncoding = false
                var authorization: String? = null
                var authorizationSeen = false
                var malformedHeader = false
                var headerBytes = 0
                var headersComplete = false
                while (headerBytes <= 32 * 1024) {
                    val header = readHttpLine() ?: break
                    if (httpLineTooLong) break
                    headerBytes += header.length + 2
                    if (headerBytes > 32 * 1024) break
                    if (header.isEmpty()) {
                        headersComplete = true
                        break
                    }
                    val colonIndex = header.indexOf(':')
                    val headerName = if (colonIndex > 0) header.substring(0, colonIndex) else ""
                    val validHeaderName = headerName.isNotEmpty() &&
                        headerName.all { it.isLetterOrDigit() || it in "!#$%&'*+-.^_`|~" }
                    if (!validHeaderName) {
                        malformedHeader = true
                        continue
                    }
                    when {
                        headerName.equals("content-length", ignoreCase = true) -> {
                            if (contentLengthSeen) {
                                writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Duplicate Content-Length header"}""")
                                return
                            }
                            contentLengthSeen = true
                            val rawLength = header.substringAfter(":").trim()
                            val parsedLength = rawLength.toIntOrNull()
                            contentLength = parsedLength ?: 0
                            invalidContentLength = parsedLength == null || rawLength.any { !it.isDigit() }
                        }
                        headerName.equals("transfer-encoding", ignoreCase = true) ->
                            unsupportedTransferEncoding = true
                        headerName.equals("authorization", ignoreCase = true) -> {
                            if (authorizationSeen) {
                                writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Duplicate Authorization header"}""")
                                return
                            }
                            authorizationSeen = true
                            authorization = header.substringAfter(":").trim()
                        }
                        else -> Unit
                    }
                }
                if (!headersComplete) {
                    writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"HTTP headers are incomplete or exceed the 32 KB limit"}""")
                    return
                }

                if (malformedHeader) {
                    writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Malformed HTTP header"}""")
                    return
                }
                if (unsupportedTransferEncoding) {
                    writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Transfer-Encoding is not supported by this bridge"}""")
                    return
                }
                if (invalidContentLength) {
                    writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Invalid Content-Length header"}""")
                    return
                }
                if (contentLength > 2 * 1024 * 1024) {
                    writeHttpResponse(sock, 413, "application/json; charset=utf-8", """{"error":"Request body exceeds the 2 MB limit"}""")
                    return
                }

                val body = if (contentLength > 0) {
                    val buf = ByteArray(contentLength)
                    var read = 0
                    while (read < buf.size) {
                        val count = input.read(buf, read, buf.size - read)
                        if (count <= 0) break
                        read += count
                    }
                    if (read != contentLength) {
                        writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Request body ended before Content-Length bytes were received"}""")
                        return
                    }
                    String(buf, 0, read, Charsets.UTF_8)
                } else ""

                if (path.startsWith("/api/") && !isBridgeAuthorized(authorization)) {
                    writeHttpResponse(sock, 401, "application/json; charset=utf-8", """{"error":"Unauthorized. Open the paired link shown in AndroidHarness."}""")
                    return
                }

                when {
                    method !in setOf("GET", "POST") -> {
                        writeHttpResponse(sock, 405, "application/json; charset=utf-8", """{"error":"Method not allowed. Use GET or POST."}""")
                    }
                    method == "GET" && path == "/api/ping" -> {
                        val json = JSONObject()
                            .put("ok", true)
                            .put("app", "AndroidHarness Laptop Studio")
                            .put("model", HarnessProvider.DEFAULT_MODEL)
                            .put("port", activePort)
                            .put("workspace", workspaceDir.absolutePath)
                        writeHttpResponse(sock, 200, "application/json; charset=utf-8", json.toString())
                    }
                    method == "GET" && path == "/api/files" -> {
                        val visibleFiles = workspaceDir.walkTopDown().maxDepth(4)
                            .filter { file ->
                                file.isFile && file.name != ".androidharness-chat-history.json" &&
                                    !file.name.endsWith(".androidharness-chat-history.json.next")
                            }
                            .take(501)
                            .toList()
                        val files = JSONArray()
                        visibleFiles.take(500).forEach { file ->
                            val rel = file.relativeTo(workspaceDir).path
                            files.put(
                                JSONObject()
                                    .put("path", rel)
                                    .put("size", file.length())
                            )
                        }
                        val json = JSONObject()
                            .put("files", files)
                            .put("truncated", visibleFiles.size > 500)
                        writeHttpResponse(sock, 200, "application/json; charset=utf-8", json.toString())
                    }
                    method == "GET" && path == "/api/file" -> {
                        val relPath = runCatching {
                            query.split("&")
                                .map { it.split("=", limit = 2) }
                                .firstOrNull { it.firstOrNull() == "path" }
                                ?.getOrNull(1)
                                ?.let { URLDecoder.decode(it, "UTF-8") }
                                .orEmpty()
                        }.getOrNull()
                        val target = relPath?.let { runCatching { File(workspaceDir, it).canonicalFile }.getOrNull() }
                        when {
                            relPath.isNullOrBlank() || target == null || isBridgeInternalPath(relPath) ||
                                !target.path.startsWith(workspaceDir.canonicalPath + File.separator) || !target.isFile -> {
                                writeHttpResponse(sock, 404, "application/json; charset=utf-8", """{"error":"File not found"}""")
                            }
                            target.length() > 2 * 1024 * 1024 -> {
                                writeHttpResponse(sock, 413, "application/json; charset=utf-8", """{"error":"File exceeds the 2 MB preview limit"}""")
                            }
                            else -> {
                                runCatching {
                                    JSONObject()
                                        .put("path", relPath)
                                        .put("content", target.readText(Charsets.UTF_8))
                                }.onSuccess { json ->
                                    writeHttpResponse(sock, 200, "application/json; charset=utf-8", json.toString())
                                }.onFailure {
                                    writeHttpResponse(sock, 500, "application/json; charset=utf-8", """{"error":"Unable to read file"}""")
                                }
                            }
                        }
                    }
                    method == "POST" && path == "/api/file" -> {
                        val payload = try {
                            JSONObject(body.ifBlank { "{}" })
                        } catch (_: org.json.JSONException) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Malformed JSON request body"}""")
                            return
                        }
                        val relPath = payload.optString("path").trim().removePrefix("/")
                        val contentValue = payload.opt("content")
                        val content = contentValue as? String
                        val target = runCatching { File(workspaceDir, relPath).canonicalFile }.getOrNull()
                        when {
                            relPath.isEmpty() || relPath.length > 1024 ||
                                relPath.split('/', '\\\\').any { it == "." || it == ".." || it.isBlank() } ||
                                isBridgeInternalPath(relPath) || target == null ||
                                !target.path.startsWith(workspaceDir.canonicalPath + File.separator) ||
                                content == null -> {
                                writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Invalid file path or content"}""")
                            }
                            content.toByteArray(Charsets.UTF_8).size > 2 * 1024 * 1024 -> {
                                writeHttpResponse(sock, 413, "application/json; charset=utf-8", """{"error":"File content exceeds the 2 MB limit"}""")
                            }
                            target.isDirectory -> {
                                writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Target path is a directory"}""")
                            }
                            else -> {
                                val parent = target.parentFile
                                if (parent == null || (!parent.exists() && !parent.mkdirs()) || !parent.isDirectory) {
                                    writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Unable to create target directory"}""")
                                } else {
                                    runCatching { target.writeText(content, Charsets.UTF_8) }
                                        .onSuccess {
                                            writeHttpResponse(sock, 200, "application/json; charset=utf-8", """{"ok":true}""")
                                        }
                                        .onFailure {
                                            writeHttpResponse(sock, 500, "application/json; charset=utf-8", """{"error":"Unable to save file"}""")
                                        }
                                }
                            }
                        }
                    }
                    method == "GET" && path == "/api/chat/status" -> {
                        val app = appContext.applicationContext as? HarnessApp
                        if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                        } else {
                            val status = runCatching {
                                runBlocking {
                                    val sessionId = getOrCreateRemoteSession(app)
                                    val container = app.container
                                    val messages = container.sessions.messages(sessionId)
                                    JSONObject()
                                        .put("ok", true)
                                        .put("busy", bridgeChatBusy.get() || container.runManager.isRunning(sessionId))
                                        .put("messageCount", messages.count {
                                            it.role == com.androidharness.app.core.Role.USER ||
                                                it.role == com.androidharness.app.core.Role.ASSISTANT
                                        })
                                        .put("sessionId", sessionId)
                                }
                            }.getOrElse {
                                JSONObject().put("ok", false).put("error", it.message ?: "Unable to read remote chat status")
                            }
                            writeHttpResponse(sock, if (status.optBoolean("ok")) 200 else 500,
                                "application/json; charset=utf-8", status.toString())
                        }
                    }
                    method == "GET" && path == "/api/chat/history" -> {
                        val app = appContext.applicationContext as? HarnessApp
                        if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                        } else {
                            val history = runCatching {
                                runBlocking {
                                    val sessionId = getOrCreateRemoteSession(app)
                                    remoteHistoryJson(app.container.sessions.messages(sessionId))
                                }
                            }.getOrElse {
                                writeHttpResponse(sock, 500, "application/json; charset=utf-8", """{"error":"Unable to load shared chat history"}""")
                                return
                            }
                            writeHttpResponse(sock, 200, "application/json; charset=utf-8",
                                JSONObject().put("messages", history).toString())
                        }
                    }
                    method == "POST" && path == "/api/chat" -> {
                        val payload = try {
                            JSONObject(body.ifBlank { "{}" })
                        } catch (_: org.json.JSONException) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Malformed JSON request body"}""")
                            return
                        }
                        val prompt = payload.optString("prompt").trim()
                        if (prompt.isEmpty()) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Empty prompt"}""")
                            return
                        }
                        if (prompt.length > 32_000) {
                            writeHttpResponse(sock, 413, "application/json; charset=utf-8", """{"error":"Prompt exceeds the 32,000 character limit"}""")
                            return
                        }
                        if (!bridgeChatBusy.compareAndSet(false, true)) {
                            writeHttpResponse(sock, 409, "application/json; charset=utf-8", """{"error":"A remote assistant response is already in progress"}""")
                            return
                        }
                        try {
                            val app = appContext.applicationContext as? HarnessApp
                            if (app == null) {
                                writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                                return
                            }
                            val result = runCatching {
                                runBlocking {
                                    val container = app.container
                                    val sessionId = getOrCreateRemoteSession(app)
                                    val modelState = container.chatModels.observe(sessionId).first()
                                    check(modelState.error == null) {
                                        modelState.error ?: "Unable to load the remote chat model configuration"
                                    }
                                    val choice = modelState.selection.active
                                        ?: error("No active AI model is configured in AndroidHarness")
                                    val provider = modelState.providers.firstOrNull { it.id == choice.providerId }
                                        ?: error("The selected AI provider is no longer available")
                                    val config = provider.copy(model = choice.model)
                                    val apiKey = if (provider.id == HarnessProvider.ID) {
                                        container.providers.harnessApiKey(modelState.providers)
                                    } else {
                                        provider.effectiveApiKey(container.providers.apiKey(provider.id))
                                    }
                                    check(apiKey.isNotBlank()) {
                                        "The selected provider has no API key configured"
                                    }
                                    val workspace = container.workspace.forSession(sessionId)
                                    container.runManager.startRun(
                                        sessionId = sessionId,
                                        text = prompt,
                                        imageRefs = emptyList(),
                                        config = config,
                                        apiKey = apiKey,
                                        permissionMode = modelState.settings.permissionMode,
                                        mode = com.androidharness.app.agent.AgentMode.ACT,
                                        maxOutputTokens = modelState.settings.maxOutputTokens,
                                        maxContextTokens = modelState.settings.maxContextTokens,
                                        thinking = modelState.selection.thinking,
                                        maxIterations = modelState.settings.maxIterations,
                                        workspaceOverride = workspace,
                                        notifyOnFinish = false,
                                        onlyIfIdle = true,
                                        allowModelFallback = modelState.settings.sameModelAcrossChats,
                                    )
                                    val completed = withTimeout(110_000L) {
                                        container.runManager.live(sessionId).first { !it.running }
                                    }
                                    val messages = container.sessions.messages(sessionId)
                                    val history = remoteHistoryJson(messages)
                                    val reply = messages.lastOrNull {
                                        it.role == com.androidharness.app.core.Role.ASSISTANT &&
                                            (completed.turnId == null || it.turnId == completed.turnId)
                                    }?.text.orEmpty()
                                    val activeModel = container.runManager.controls.flow(sessionId).value.provider?.model
                                        ?: config.model
                                    JSONObject()
                                        .put("ok", completed.error == null && reply.isNotBlank())
                                        .put("model", activeModel)
                                        .put("reply", reply.ifBlank { completed.error ?: "No response was generated." })
                                        .put("error", completed.error)
                                        .put("messages", history)
                                        .put("sessionId", sessionId)
                                }
                            }
                            if (result.isSuccess) {
                                val response = result.getOrThrow()
                                writeHttpResponse(sock, if (response.optBoolean("ok")) 200 else 502,
                                    "application/json; charset=utf-8", response.toString())
                            } else {
                                val error = result.exceptionOrNull()
                                val status = when (error) {
                                    is kotlinx.coroutines.TimeoutCancellationException -> 504
                                    is com.androidharness.app.agent.RunBusyException -> 409
                                    else -> 502
                                }
                                val message = error?.message ?: "AndroidHarness agent execution failed"
                                writeHttpResponse(sock, status, "application/json; charset=utf-8",
                                    JSONObject().put("ok", false).put("error", message).toString())
                            }
                        } finally {
                            bridgeChatBusy.set(false)
                        }
                    }
                    else -> {
                        if (path.startsWith("/api/")) {
                            writeHttpResponse(sock, 404, "application/json; charset=utf-8", """{"error":"API endpoint not found"}""")
                        } else {
                            writeHttpResponse(sock, 200, "text/html; charset=utf-8", LAPTOP_WEB_HTML)
                        }
                    }
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
                405 -> "Method Not Allowed"
                409 -> "Conflict"
                413 -> "Payload Too Large"
                502 -> "Bad Gateway"
                503 -> "Service Unavailable"
                504 -> "Gateway Timeout"
                else -> "Error"
            }
            val headers = "HTTP/1.1 $status $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "X-Frame-Options: DENY\r\n" +
                "Referrer-Policy: no-referrer\r\n" +
                "Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; connect-src 'self' https: wss:; img-src 'self' data: blob:; media-src 'self' blob:;\r\n" +
                "Connection: close\r\n\r\n"
            val out = sock.getOutputStream()
            out.write(headers.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        }

        private const val LAPTOP_WEB_HTML = """<!doctype html><html lang="en"><head><meta charset="utf-8" /><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no" /><title>AndroidHarness · Virtual Phone & Laptop Studio</title><style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { margin: 0; font-family: system-ui, -apple-system, sans-serif; background: #0B1120; color: #F8FAFC; display: flex; flex-direction: column; height: 100vh; }
  header { display: flex; align-items: center; justify-content: space-between; padding: 12px 18px; background: #0F172A; border-bottom: 1px solid #1E293B; }
  .brand { font-weight: 700; font-size: 15px; display: flex; align-items: center; gap: 8px; }
  .dot { width: 9px; height: 9px; border-radius: 50%; background: #10B981; display: inline-block; }
  .meta { font-family: monospace; font-size: 11px; color: #94A3B8; }
  .mobile-tabs { display: none; background: #0F172A; border-bottom: 1px solid #1E293B; }
  .tab-btn { flex: 1; padding: 10px 4px; font-size: 12px; background: transparent; color: #94A3B8; border: none; border-bottom: 2px solid transparent; cursor: pointer; text-align: center; font-weight: 500; }
  .tab-btn.active { color: #38BDF8; border-bottom-color: #38BDF8; font-weight: 700; }
  main { flex: 1; display: grid; grid-template-columns: 260px 1fr 400px; min-height: 0; }
  .panel { border-right: 1px solid #1E293B; display: flex; flex-direction: column; min-height: 0; }
  .panel-header { padding: 9px 12px; font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.05em; color: #94A3B8; border-bottom: 1px solid #1E293B; display: flex; justify-content: space-between; align-items: center; }
  .file-list { flex: 1; overflow-y: auto; padding: 8px; }
  .file-item { padding: 8px 10px; border-radius: 6px; cursor: pointer; font-family: monospace; font-size: 13px; color: #CBD5E1; }
  .file-item:hover, .file-item.active { background: #1E293B; color: #38BDF8; }
  .editor-wrap { flex: 1; display: flex; flex-direction: column; min-height: 0; }
  textarea#editor { flex: 1; width: 100%; background: #0B1120; color: #E2E8F0; border: none; padding: 14px; font-family: monospace; font-size: 13px; line-height: 1.5; resize: none; outline: none; }
  .chat-messages { flex: 1; overflow-y: auto; padding: 12px; display: flex; flex-direction: column; gap: 10px; }
  .msg { padding: 10px 12px; border-radius: 8px; font-size: 13px; line-height: 1.45; white-space: pre-wrap; }
  .msg.user { background: #1E293B; align-self: flex-end; max-width: 88%; }
  .msg.ai { background: #0F172A; border: 1px solid #1E293B; align-self: flex-start; width: 100%; }
  .chat-input { display: flex; gap: 6px; padding: 10px; border-top: 1px solid #1E293B; background: #0F172A; align-items: center; }
  input, button { font-family: inherit; font-size: 13px; border-radius: 6px; }
  input { flex: 1; background: #1E293B; color: #F8FAFC; border: 1px solid #334155; padding: 9px 12px; outline: none; }
  button { background: #2563EB; color: #FFF; border: none; padding: 8px 12px; font-weight: 600; cursor: pointer; }
  button:hover { background: #1D4ED8; }
  .btn-secondary { background: #334155; }
  .btn-secondary:hover { background: #475569; }
  @media (max-width: 768px) {
    header { padding: 8px 12px; }
    .meta { display: none; }
    .mobile-tabs { display: flex; }
    main { display: flex; flex-direction: column; }
    .panel { display: none; border-right: none; }
    .panel.tab-active { display: flex; flex: 1; }
  }
</style></head><body>
<header>
  <div class="brand"><span class="dot"></span> AndroidHarness Virtual Studio</div>
  <div class="meta" id="status-meta">Multi-Gateway AI Active · Zero-Install Second Phone Access</div>
</header>
<nav class="mobile-tabs" id="mobile-tabs">
  <button class="tab-btn active" id="btn-tab-chat" onclick="switchMobileTab('chat')">💬 Chat &amp; Voice</button>
  <button class="tab-btn" id="btn-tab-files" onclick="switchMobileTab('files')">📁 Files</button>
  <button class="tab-btn" id="btn-tab-editor" onclick="switchMobileTab('editor')">📝 Editor</button>
</nav>
<main>
  <section class="panel" id="panel-files">
    <div class="panel-header"><span>Phone Workspace</span><button onclick="createNewFile()">+ File</button></div>
    <div class="file-list" id="file-list"></div>
  </section>
  <section class="panel editor-wrap" id="panel-editor">
    <div class="panel-header"><span id="current-file">Select or create file</span><button onclick="saveCurrentFile()">Save</button></div>
    <textarea id="editor" placeholder="Select a workspace file or ask the AI to code..."></textarea>
  </section>
  <section class="panel tab-active" id="panel-chat" style="border-right:none">
    <div class="panel-header">
      <span>AI Specialist Team</span>
      <div style="display:flex;gap:6px;align-items:center">
        <button class="btn-secondary" id="read-btn" style="padding:4px 8px;font-size:11px" onclick="toggleAutoSpeech()">🔊 Auto Read: ON</button>
        <span id="model-tag" style="color:#38BDF8;font-size:11px">Multi-Model</span>
      </div>
    </div>
    <div class="chat-messages" id="chat-box">
      <div class="msg ai">Connected virtually to AndroidHarness! You can speak or chat with the AI model team and edit phone workspace files right from this browser without installing anything.</div>
    </div>
    <div class="chat-input">
      <button class="btn-secondary" id="voice-btn" style="padding:8px 10px" onclick="toggleVoice()" title="Speak with microphone">🎙️</button>
      <input id="prompt-input" placeholder="Ask AI team or edit workspace..." onkeydown="if(event.key==='Enter')sendPrompt()" />
      <button onclick="sendPrompt()">Send</button>
    </div>
  </section>
</main>
<script>
const bridgeToken = new URLSearchParams(location.search).get("token") || "";
if (bridgeToken) {
  sessionStorage.setItem("androidHarnessBridgeToken", bridgeToken);
  history.replaceState(null, "", location.pathname);
}
const sessionToken = sessionStorage.getItem("androidHarnessBridgeToken") || "";
async function apiFetch(url, options = {}) {
  const headers = new Headers(options.headers || {});
  headers.set("Authorization", "Bearer " + sessionToken);
  return fetch(url, { ...options, headers });
}
let activeFilePath = "";
let chatHistory = [];
try { chatHistory = JSON.parse(sessionStorage.getItem("androidHarnessChatHistory") || "[]"); } catch (_) { chatHistory = []; }
let autoVoiceRead = true;
let isRecognizing = false;

function switchMobileTab(tab) {
  document.querySelectorAll(".tab-btn").forEach(b => b.classList.remove("active"));
  document.querySelectorAll(".panel").forEach(p => p.classList.remove("tab-active"));
  document.getElementById("btn-tab-" + tab).classList.add("active");
  document.getElementById("panel-" + tab).classList.add("tab-active");
}

function toggleAutoSpeech() {
  autoVoiceRead = !autoVoiceRead;
  document.getElementById("read-btn").textContent = autoVoiceRead ? "🔊 Auto Read: ON" : "🔇 Auto Read: OFF";
  if (!autoVoiceRead && window.speechSynthesis) window.speechSynthesis.cancel();
}

function toggleVoice() {
  const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!SpeechRecognition) {
    alert("Speech recognition not supported in this browser. Please type your message.");
    return;
  }
  const vbtn = document.getElementById("voice-btn");
  if (isRecognizing) return;
  const rec = new SpeechRecognition();
  rec.lang = "en-US";
  vbtn.textContent = "🔴";
  isRecognizing = true;
  rec.onresult = (e) => {
    const transcript = e.results[0][0].transcript;
    document.getElementById("prompt-input").value = transcript;
    vbtn.textContent = "🎙️";
    isRecognizing = false;
    sendPrompt();
  };
  rec.onerror = () => { vbtn.textContent = "🎙️"; isRecognizing = false; };
  rec.onend = () => { vbtn.textContent = "🎙️"; isRecognizing = false; };
  rec.start();
}

async function loadChatHistory() {
  try {
    const res = await apiFetch("/api/chat/history");
    if (!res.ok) return;
    const data = await res.json();
    chatHistory = Array.isArray(data.messages) ? data.messages : [];
    sessionStorage.setItem("androidHarnessChatHistory", JSON.stringify(chatHistory));
    const box = document.getElementById("chat-box");
    box.innerHTML = "";
    chatHistory.forEach(m => {
      const node = document.createElement("div");
      node.className = "msg " + (m.role === "user" ? "user" : "ai");
      node.textContent = m.content || "";
      box.appendChild(node);
    });
    box.scrollTop = box.scrollHeight;
  } catch (_) { /* Keep the current UI if shared history is temporarily unavailable. */ }
}

async function loadFiles() {
  const res = await apiFetch("/api/files");
  const data = await res.json();
  const list = document.getElementById("file-list");
  list.innerHTML = "";
  if (!data.files || data.files.length === 0) {
    list.innerHTML = "<div style='padding:10px;color:#64748B;font-size:12px'>No files yet. Click + File to create one.</div>";
    return;
  }
  data.files.forEach(f => {
    const div = document.createElement("div");
    div.className = "file-item" + (f.path === activeFilePath ? " active" : "");
    div.textContent = f.path + " (" + f.size + "B)";
    div.onclick = () => { openFile(f.path); if (window.innerWidth <= 768) switchMobileTab("editor"); };
    list.appendChild(div);
  });
}

async function openFile(path) {
  activeFilePath = path;
  document.getElementById("current-file").textContent = path;
  const res = await apiFetch("/api/file?path=" + encodeURIComponent(path));
  const data = await res.json();
  document.getElementById("editor").value = data.content || "";
  loadFiles();
}

async function createNewFile() {
  const name = prompt("Enter file name (e.g. index.html):", "index.html");
  if (!name) return;
  activeFilePath = name;
  document.getElementById("current-file").textContent = name;
  document.getElementById("editor").value = "<!doctype html>\n<html>\n<body>\n  <h1>Hello from AndroidHarness</h1>\n</body>\n</html>";
  await saveCurrentFile();
  if (window.innerWidth <= 768) switchMobileTab("editor");
}

async function saveCurrentFile() {
  if (!activeFilePath) { activeFilePath = "index.html"; document.getElementById("current-file").textContent = activeFilePath; }
  await apiFetch("/api/file", {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({ path: activeFilePath, content: document.getElementById("editor").value })
  });
  loadFiles();
}

let bridgeRefreshInFlight = false;
let bridgeAssistantBusy = false;
async function refreshBridgeStatus() {
  const label = document.getElementById("status-meta");
  if (!label || !sessionToken || bridgeRefreshInFlight) return;
  bridgeRefreshInFlight = true;
  try {
    const res = await apiFetch("/api/chat/status");
    const data = await res.json();
    if (!res.ok || !data.ok) throw new Error(data.error || "Status unavailable");
    bridgeAssistantBusy = !!data.busy;
    const sendButton = document.querySelector(".chat-input button:last-child");
    if (sendButton && !chatRequestInFlight) sendButton.disabled = bridgeAssistantBusy;
    label.textContent = bridgeAssistantBusy
      ? "AndroidHarness · Assistant responding · " + data.messageCount + " messages"
      : "AndroidHarness · Connected · " + data.messageCount + " messages";
    await refreshSharedChatHistory();
  } catch (_) {
    label.textContent = "AndroidHarness · Connection status unavailable";
  } finally {
    bridgeRefreshInFlight = false;
  }
}
let chatRequestInFlight = false;
async function refreshSharedChatHistory() {
  if (chatRequestInFlight) return;
  try {
    const res = await apiFetch("/api/chat/history");
    if (!res.ok) return;
    const data = await res.json();
    const latest = Array.isArray(data.messages) ? data.messages : [];
    if (JSON.stringify(latest) === JSON.stringify(chatHistory)) return;
    chatHistory = latest;
    sessionStorage.setItem("androidHarnessChatHistory", JSON.stringify(chatHistory));
    const box = document.getElementById("chat-box");
    if (!box) return;
    box.innerHTML = "";
    chatHistory.forEach(m => {
      const node = document.createElement("div");
      node.className = "msg " + (m.role === "user" ? "user" : "ai");
      node.textContent = m.content || "";
      box.appendChild(node);
    });
    box.scrollTop = box.scrollHeight;
  } catch (_) { /* Preserve the current conversation during temporary bridge failures. */ }
}
async function sendPrompt() {
  if (chatRequestInFlight || bridgeAssistantBusy) return;
  const input = document.getElementById("prompt-input");
  const text = input.value.trim();
  if (!text) return;
  chatRequestInFlight = true;
  const sendButton = document.querySelector(".chat-input button:last-child");
  if (sendButton) {
    sendButton.disabled = true;
    sendButton.textContent = "Working…";
  }
  input.value = "";
  const box = document.getElementById("chat-box");
  const u = document.createElement("div");
  u.className = "msg user";
  u.textContent = text;
  box.appendChild(u);
  const a = document.createElement("div");
  a.className = "msg ai";
  a.textContent = "AI Team working...";
  box.appendChild(a);
  box.scrollTop = box.scrollHeight;
  try {
    const res = await apiFetch("/api/chat", {
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({ prompt: text, messages: [...chatHistory, { role: "user", content: text }] })
    });
    const data = await res.json();
    document.getElementById("model-tag").textContent = data.model || "kilo-auto/free";
    const replyText = data.reply || "Done.";
    a.textContent = replyText;
    if (Array.isArray(data.messages)) {
      chatHistory = data.messages;
      sessionStorage.setItem("androidHarnessChatHistory", JSON.stringify(chatHistory));
    }
    if (autoVoiceRead && window.speechSynthesis) {
      window.speechSynthesis.cancel();
      const cleanSpeech = replyText.replace(/[*#`_]/g, "").slice(0, 450);
      const utter = new SpeechSynthesisUtterance(cleanSpeech);
      window.speechSynthesis.speak(utter);
    }
  } catch (e) {
    a.textContent = "Error communicating with phone: " + e.message;
  } finally {
    chatRequestInFlight = false;
    if (sendButton) {
      sendButton.disabled = bridgeAssistantBusy;
      sendButton.textContent = "Send";
    }
  }
  box.scrollTop = box.scrollHeight;
}
loadFiles();
loadChatHistory();
refreshBridgeStatus();
setInterval(refreshBridgeStatus, 5000);
</script></body></html>"""
    }
}
