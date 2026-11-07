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

        BridgeHostService.start(applicationContext)

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
        BridgeHostService.start(applicationContext)
        val detected = detectBestIpAddress()
        if (detected != null) {
            activateBridgeUi(detected.first)
        }
    }

    private fun startUsbTethering() {
        BridgeHostService.start(applicationContext)
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
         * Resolve the browser to the same session currently active in the native app.
         * The bridge's private CHAT session is only a fallback when the app has no
         * valid last-active session (for example, on a fresh installation).
         *
         * Resolve this on every request rather than caching it: opening another chat
         * in the Android UI must switch the remote browser to that same conversation.
         */
        private suspend fun getOrCreateRemoteSession(
            app: HarnessApp,
            requestedSessionId: String? = null,
        ): String {
            val container = app.container
            val requested = requestedSessionId?.trim().orEmpty()
            if (requested.isNotEmpty()) {
                check(container.sessions.session(requested) != null) {
                    "The selected AndroidHarness chat no longer exists. Refresh the session list."
                }
                return requested
            }
            val lastActiveId = container.settings.settings.first().lastActiveSessionId
            if (!lastActiveId.isNullOrBlank() && container.sessions.session(lastActiveId) != null) {
                return lastActiveId
            }

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

        private fun queryValue(query: String, key: String): String =
            query.split("&")
                .map { it.split("=", limit = 2) }
                .firstOrNull { it.firstOrNull() == key }
                ?.getOrNull(1)?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()

        private fun remoteHistoryJson(messages: List<ChatMessage>): JSONArray = JSONArray().apply {
            messages.asSequence()
                .filter { it.role == com.androidharness.app.core.Role.USER ||
                    it.role == com.androidharness.app.core.Role.ASSISTANT }
                .takeLast(40)
                .forEach { message ->
                    put(JSONObject()
                        .put("role", message.role.name.lowercase())
                        .put("content", message.text)
                        .put("isError", message.isError)
                        .put("id", message.id ?: ""))
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

        /** Stops accepting remote clients when the foreground host service is stopped. */
        @Synchronized
        fun stopServer() {
            runCatching { serverSocket?.close() }
            serverSocket = null
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
                    method == "GET" && path == "/api/projects" -> {
                        val app = appContext.applicationContext as? HarnessApp
                        if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                        } else {
                            val result = runCatching {
                                runBlocking {
                                    val projects = app.container.workspace.projects.first().take(200)
                                    val items = JSONArray()
                                    projects.forEach { project ->
                                        items.put(JSONObject()
                                            .put("id", project.id)
                                            .put("name", project.name)
                                            .put("kind", project.kind)
                                            .put("lastUsedAt", project.lastUsedAt))
                                    }
                                    JSONObject().put("ok", true).put("projects", items)
                                }
                            }
                            if (result.isSuccess) writeHttpResponse(sock, 200, "application/json; charset=utf-8", result.getOrThrow().toString())
                            else writeHttpResponse(sock, 500, "application/json; charset=utf-8",
                                JSONObject().put("error", result.exceptionOrNull()?.message ?: "Unable to load projects").toString())
                        }
                    }
                    method == "POST" && path == "/api/projects/rename" -> {
                        val payload = try { JSONObject(body.ifBlank { "{}" }) }
                        catch (_: org.json.JSONException) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Malformed JSON request body"}""")
                            return
                        }
                        val projectId = payload.optString("projectId").trim()
                        val name = payload.optString("name").trim()
                        val app = appContext.applicationContext as? HarnessApp
                        if (projectId.isBlank() || name.isBlank()) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"A project and non-empty name are required"}""")
                        } else if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                        } else {
                            val result = runCatching {
                                runBlocking {
                                    val project = app.container.workspace.projects.first().firstOrNull { it.id == projectId }
                                        ?: error("This project is no longer available.")
                                    check(project.kind != com.androidharness.app.workspace.WorkspaceManager.KIND_APP) {
                                        "The built-in app workspace cannot be renamed here."
                                    }
                                    app.container.workspace.renameProject(projectId, name)
                                    JSONObject().put("ok", true).put("projectId", projectId).put("name", name)
                                }
                            }
                            if (result.isSuccess) writeHttpResponse(sock, 200, "application/json; charset=utf-8", result.getOrThrow().toString())
                            else writeHttpResponse(sock, 409, "application/json; charset=utf-8",
                                JSONObject().put("ok", false).put("error", result.exceptionOrNull()?.message ?: "Unable to rename project").toString())
                        }
                    }
                    method == "POST" && path == "/api/projects/delete-preview" -> {
                        val payload = try { JSONObject(body.ifBlank { "{}" }) }
                        catch (_: org.json.JSONException) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Malformed JSON request body"}""")
                            return
                        }
                        val projectId = payload.optString("projectId").trim()
                        val app = appContext.applicationContext as? HarnessApp
                        if (projectId.isBlank()) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"A project is required"}""")
                        } else if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                        } else {
                            val result = runCatching {
                                runBlocking {
                                    val preview = app.container.projectDeletion.preview(projectId)
                                    JSONObject().put("ok", true).put("projectId", projectId)
                                        .put("name", preview.project.name).put("chatCount", preview.chatIds.size)
                                        .put("automationCount", preview.automationIds.size)
                                        .put("blocker", preview.blocker ?: "")
                                }
                            }
                            if (result.isSuccess) writeHttpResponse(sock, 200, "application/json; charset=utf-8", result.getOrThrow().toString())
                            else writeHttpResponse(sock, 409, "application/json; charset=utf-8",
                                JSONObject().put("ok", false).put("error", result.exceptionOrNull()?.message ?: "Unable to review project deletion").toString())
                        }
                    }
                    method == "POST" && path == "/api/projects/delete" -> {
                        val payload = try { JSONObject(body.ifBlank { "{}" }) }
                        catch (_: org.json.JSONException) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Malformed JSON request body"}""")
                            return
                        }
                        val projectId = payload.optString("projectId").trim()
                        val app = appContext.applicationContext as? HarnessApp
                        if (projectId.isBlank()) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"A project is required"}""")
                        } else if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                        } else {
                            val result = runCatching {
                                runBlocking {
                                    val deletion = app.container.projectDeletion
                                    val preview = deletion.preview(projectId)
                                    check(preview.blocker == null) { preview.blocker ?: "Project deletion is blocked." }
                                    deletion.delete(preview)
                                    JSONObject().put("ok", true).put("projectId", projectId)
                                        .put("deletedChats", preview.chatIds.size).put("deletedAutomations", preview.automationIds.size)
                                }
                            }
                            if (result.isSuccess) writeHttpResponse(sock, 200, "application/json; charset=utf-8", result.getOrThrow().toString())
                            else writeHttpResponse(sock, 409, "application/json; charset=utf-8",
                                JSONObject().put("ok", false).put("error", result.exceptionOrNull()?.message ?: "Unable to delete project").toString())
                        }
                    }
                    method == "GET" && path == "/api/sessions" -> {
                        val app = appContext.applicationContext as? HarnessApp
                        if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                        } else {
                            val result = runCatching {
                                runBlocking {
                                    val sessions = app.container.sessions.sessions.first().take(100)
                                    val items = JSONArray()
                                    sessions.forEach { session ->
                                        items.put(JSONObject()
                                            .put("id", session.id)
                                            .put("title", session.title)
                                            .put("updatedAt", session.updatedAt)
                                            .put("createdAt", session.createdAt)
                                            .put("projectId", session.projectId ?: ""))
                                    }
                                    JSONObject().put("ok", true).put("sessions", items)
                                }
                            }
                            if (result.isSuccess) writeHttpResponse(sock, 200, "application/json; charset=utf-8", result.getOrThrow().toString())
                            else writeHttpResponse(sock, 500, "application/json; charset=utf-8",
                                JSONObject().put("error", result.exceptionOrNull()?.message ?: "Unable to load chats").toString())
                        }
                    }
                    method == "POST" && path == "/api/chat/new" -> {
                        val payload = try {
                            JSONObject(body.ifBlank { "{}" })
                        } catch (_: org.json.JSONException) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Malformed JSON request body"}""")
                            return
                        }
                        val requestedTitle = payload.optString("title").trim().take(100)
                        val projectId = payload.optString("projectId").trim().takeIf { it.isNotEmpty() }
                        val app = appContext.applicationContext as? HarnessApp
                        if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                        } else {
                            val result = runCatching {
                                runBlocking {
                                    val container = app.container
                                    if (projectId != null) {
                                        check(container.workspace.projects.first().any { it.id == projectId }) {
                                            "The selected project is no longer available."
                                        }
                                    }
                                    val title = requestedTitle.ifBlank { "New remote chat" }
                                    val sessionId = container.sessions.createSession(
                                        title = title,
                                        projectId = projectId,
                                        conversation = ConversationConfig(kind = ConversationKind.CHAT),
                                    )
                                    container.chatModels.initialize(sessionId)
                                    JSONObject().put("ok", true).put("sessionId", sessionId).put("title", title)
                                        .put("projectId", projectId ?: "")
                                }
                            }
                            if (result.isSuccess) writeHttpResponse(sock, 200, "application/json; charset=utf-8", result.getOrThrow().toString())
                            else writeHttpResponse(sock, 409, "application/json; charset=utf-8",
                                JSONObject().put("ok", false).put("error", result.exceptionOrNull()?.message ?: "Unable to create chat").toString())
                        }
                    }
                    method == "GET" && path == "/api/ping" -> {
                        val app = appContext.applicationContext as? HarnessApp
                        if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                        } else {
                            val json = runCatching {
                                runBlocking {
                                    val sessionId = getOrCreateRemoteSession(app)
                                    val workspace = app.container.workspace.forSession(sessionId)
                                    JSONObject()
                                        .put("ok", true)
                                        .put("app", "AndroidHarness Laptop Studio")
                                        .put("model", HarnessProvider.DEFAULT_MODEL)
                                        .put("port", activePort)
                                        .put("sessionId", sessionId)
                                        .put("workspace", workspace.displayPath)
                                }
                            }.getOrElse {
                                JSONObject().put("ok", false).put("error", it.message ?: "Unable to resolve the active workspace")
                            }
                            writeHttpResponse(sock, if (json.optBoolean("ok")) 200 else 500,
                                "application/json; charset=utf-8", json.toString())
                        }
                    }
                    method == "GET" && path == "/api/files" -> {
                        val app = appContext.applicationContext as? HarnessApp
                        if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                        } else {
                            val json = runCatching {
                                runBlocking {
                                    val requestedSessionId = queryValue(query, "sessionId")
                                    val sessionId = getOrCreateRemoteSession(app, requestedSessionId)
                                    check(requestedSessionId.isBlank() || requestedSessionId == sessionId) {
                                        "The active AndroidHarness chat changed. Refresh the remote view."
                                    }
                                    val workspace = app.container.workspace.forSession(sessionId)
                                    val visibleFiles = workspace.walk(".")
                                        .filter { node ->
                                            node.isFile && !isBridgeInternalPath(node.relPath) &&
                                                node.relPath.split('/', '\\').size <= 4
                                        }
                                        .take(501)
                                        .toList()
                                    val files = JSONArray()
                                    visibleFiles.take(500).forEach { node ->
                                        files.put(JSONObject().put("path", node.relPath).put("size", node.length))
                                    }
                                    JSONObject().put("files", files).put("truncated", visibleFiles.size > 500)
                                        .put("sessionId", sessionId).put("workspace", workspace.displayPath)
                                }
                            }.getOrElse {
                                JSONObject().put("error", it.message ?: "Unable to list active workspace files")
                            }
                            writeHttpResponse(sock, if (json.has("files")) 200 else 500,
                                "application/json; charset=utf-8", json.toString())
                        }
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
                        val safePath = !relPath.isNullOrBlank() && !relPath.startsWith("/") &&
                            relPath.split('/', '\\').none { it.isBlank() || it == "." || it == ".." } &&
                            !isBridgeInternalPath(relPath)
                        if (!safePath) {
                            writeHttpResponse(sock, 404, "application/json; charset=utf-8", """{"error":"File not found"}""")
                        } else {
                            val app = appContext.applicationContext as? HarnessApp
                            if (app == null) {
                                writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                            } else {
                                val result = runCatching {
                                    runBlocking {
                                        val requestedSessionId = queryValue(query, "sessionId")
                                        val sessionId = getOrCreateRemoteSession(app, requestedSessionId)
                                        check(requestedSessionId.isBlank() || requestedSessionId == sessionId) {
                                            "The active AndroidHarness chat changed. Refresh the remote view."
                                        }
                                        val node = app.container.workspace.forSession(sessionId).resolve(relPath!!)
                                        check(node.exists && node.isFile) { "File not found" }
                                        check(node.length <= 2 * 1024 * 1024) { "File exceeds the 2 MB preview limit" }
                                        JSONObject().put("path", relPath).put("content", node.readText())
                                    }
                                }
                                if (result.isSuccess) {
                                    writeHttpResponse(sock, 200, "application/json; charset=utf-8", result.getOrThrow().toString())
                                } else {
                                    val error = result.exceptionOrNull()
                                    val status = if (error?.message?.contains("2 MB") == true) 413 else
                                        if (error?.message?.contains("File not found") == true) 404 else 500
                                    writeHttpResponse(sock, status, "application/json; charset=utf-8",
                                        JSONObject().put("error", error?.message ?: "Unable to read file").toString())
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
                        val relPath = payload.optString("path").trim()
                        val content = payload.opt("content") as? String
                        val safePath = relPath.isNotEmpty() && relPath.length <= 1024 &&
                            !relPath.startsWith("/") &&
                            relPath.split('/', '\\').none { it == "." || it == ".." || it.isBlank() } &&
                            !isBridgeInternalPath(relPath)
                        when {
                            !safePath || content == null -> {
                                writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Invalid file path or content"}""")
                            }
                            content.toByteArray(Charsets.UTF_8).size > 2 * 1024 * 1024 -> {
                                writeHttpResponse(sock, 413, "application/json; charset=utf-8", """{"error":"File content exceeds the 2 MB limit"}""")
                            }
                            else -> {
                                val app = appContext.applicationContext as? HarnessApp
                                if (app == null) {
                                    writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                                } else {
                                    val result = runCatching {
                                        runBlocking {
                                            val requestedSessionId = payload.optString("sessionId").trim()
                                            val sessionId = getOrCreateRemoteSession(app, requestedSessionId)
                                            check(requestedSessionId.isBlank() || requestedSessionId == sessionId) {
                                                "The active AndroidHarness chat changed. Refresh the remote view."
                                            }
                                            val node = app.container.workspace.forSession(sessionId).resolve(relPath)
                                            check(!node.isDirectory) { "Target path is a directory" }
                                            node.writeText(content)
                                        }
                                    }
                                    if (result.isSuccess) {
                                        writeHttpResponse(sock, 200, "application/json; charset=utf-8", """{"ok":true}""")
                                    } else {
                                        writeHttpResponse(sock, 500, "application/json; charset=utf-8",
                                            JSONObject().put("error", result.exceptionOrNull()?.message ?: "Unable to save file").toString())
                                    }
                                }
                            }
                        }
                    }
                    method == "GET" && path == "/api/models" -> {
                        val app = appContext.applicationContext as? HarnessApp
                        if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                        } else {
                            val result = runCatching {
                                runBlocking {
                                    val container = app.container
                                    val requestedSessionId = queryValue(query, "sessionId")
                                    val sessionId = getOrCreateRemoteSession(app, requestedSessionId)
                                    check(requestedSessionId.isBlank() || requestedSessionId == sessionId) {
                                        "The active AndroidHarness chat changed. Refresh the remote view."
                                    }
                                    val state = container.chatModels.observe(sessionId).first()
                                    check(state.error == null) { state.error ?: "Unable to load model selection" }
                                    val catalogs = container.providers.catalogs.first()
                                    val providersJson = JSONArray()
                                    state.providers.filter { it.id !in state.settings.disabledProviderIds }.forEach { provider ->
                                        val modelsJson = JSONArray()
                                        catalogs[provider.id].orEmpty().forEach { model ->
                                            modelsJson.put(JSONObject().put("id", model.id)
                                                .put("name", model.displayName ?: model.name ?: model.id))
                                        }
                                        providersJson.put(JSONObject().put("id", provider.id).put("name", provider.name)
                                            .put("model", provider.model).put("models", modelsJson))
                                    }
                                    val active = state.selection.active
                                    val permissionModes = JSONArray().apply {
                                        com.androidharness.app.agent.PermissionMode.values().forEach { mode ->
                                            put(JSONObject().put("id", mode.name).put("label", mode.label))
                                        }
                                    }
                                    JSONObject().put("ok", true).put("sessionId", sessionId).put("providers", providersJson)
                                        .put("selectedProviderId", active?.providerId ?: "").put("selectedModel", active?.model ?: "")
                                        .put("permissionMode", state.settings.permissionMode.name).put("permissionModes", permissionModes)
                                }
                            }
                            if (result.isSuccess) writeHttpResponse(sock, 200, "application/json; charset=utf-8", result.getOrThrow().toString())
                            else writeHttpResponse(sock, 409, "application/json; charset=utf-8",
                                JSONObject().put("ok", false).put("error", result.exceptionOrNull()?.message ?: "Unable to load models").toString())
                        }
                    }
                    method == "POST" && path == "/api/model" -> {
                        val payload = try {
                            JSONObject(body.ifBlank { "{}" })
                        } catch (_: org.json.JSONException) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Malformed JSON request body"}""")
                            return
                        }
                        val sessionId = payload.optString("sessionId").trim()
                        val providerId = payload.optString("providerId").trim()
                        val model = payload.optString("model").trim()
                        if (sessionId.isBlank() || providerId.isBlank() || providerId.length > 500 || model.length > 500) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"A valid session, provider and model are required"}""")
                            return
                        }
                        val app = appContext.applicationContext as? HarnessApp
                        if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                        } else {
                            val result = runCatching {
                                runBlocking {
                                    val container = app.container
                                    check(getOrCreateRemoteSession(app, sessionId) == sessionId) {
                                        "The active AndroidHarness chat changed. Refresh the remote view."
                                    }
                                    check(!container.runManager.isRunning(sessionId)) {
                                        "Wait for the current assistant response before changing models."
                                    }
                                    val state = container.chatModels.observe(sessionId).first()
                                    check(state.error == null) { state.error ?: "Unable to load model selection" }
                                    check(providerId !in state.settings.disabledProviderIds) {
                                        "This provider is disabled in AndroidHarness."
                                    }
                                    val provider = state.providers.firstOrNull { it.id == providerId }
                                        ?: error("This provider is unavailable in AndroidHarness.")
                                    val chosenModel = model.ifBlank { provider.model }
                                    container.chatModels.select(sessionId, com.androidharness.app.data.ChatModelTarget.ACTIVE,
                                        provider.id, chosenModel)
                                    JSONObject().put("ok", true).put("sessionId", sessionId)
                                        .put("provider", provider.name).put("model", chosenModel)
                                }
                            }
                            if (result.isSuccess) writeHttpResponse(sock, 200, "application/json; charset=utf-8", result.getOrThrow().toString())
                            else writeHttpResponse(sock, 409, "application/json; charset=utf-8",
                                JSONObject().put("ok", false).put("error", result.exceptionOrNull()?.message ?: "Unable to change model").toString())
                        }
                    }
                    method == "POST" && path == "/api/permission" -> {
                        val payload = try {
                            JSONObject(body.ifBlank { "{}" })
                        } catch (_: org.json.JSONException) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Malformed JSON request body"}""")
                            return
                        }
                        val sessionId = payload.optString("sessionId").trim()
                        val requestedMode = payload.optString("mode").trim()
                        if (sessionId.isBlank() || requestedMode.isBlank()) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"A session and permission mode are required"}""")
                            return
                        }
                        val app = appContext.applicationContext as? HarnessApp
                        if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                        } else {
                            val result = runCatching {
                                runBlocking {
                                    val container = app.container
                                    check(getOrCreateRemoteSession(app, sessionId) == sessionId) {
                                        "The active AndroidHarness chat changed. Refresh the remote view."
                                    }
                                    val mode = com.androidharness.app.agent.PermissionMode.values()
                                        .firstOrNull { it.name == requestedMode }
                                        ?: error("Unknown permission mode.")
                                    container.settings.setPermissionMode(mode)
                                    JSONObject().put("ok", true).put("sessionId", sessionId)
                                        .put("permissionMode", mode.name).put("label", mode.label)
                                }
                            }
                            if (result.isSuccess) writeHttpResponse(sock, 200, "application/json; charset=utf-8", result.getOrThrow().toString())
                            else writeHttpResponse(sock, 409, "application/json; charset=utf-8",
                                JSONObject().put("ok", false).put("error", result.exceptionOrNull()?.message ?: "Unable to change permission mode").toString())
                        }
                    }
                    method == "GET" && path == "/api/automations" -> {
                        val app = appContext.applicationContext as? HarnessApp
                        if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                        } else {
                            val result = runCatching {
                                runBlocking {
                                    val projectId = queryValue(query, "projectId").trim()
                                    val manager = app.container.automation
                                    val tasks = manager.repository.tasks.value.filter { projectId.isBlank() || it.projectId == projectId }
                                    val items = JSONArray().apply {
                                        tasks.forEach { task ->
                                            put(JSONObject().put("id", task.id).put("title", task.title)
                                                .put("projectId", task.projectId).put("projectName", task.projectName)
                                                .put("prompt", task.prompt).put("checkCommand", task.checkCommand)
                                                .put("schedule", task.schedule.name).put("enabled", task.enabled)
                                                .put("status", task.lastStatus.name).put("lastMessage", task.lastMessage ?: "")
                                                .put("lastRunAt", task.lastRunAt ?: JSONObject.NULL)
                                                .put("nextRunAt", task.nextRunAt ?: JSONObject.NULL)
                                                .put("hour", task.hour).put("minute", task.minute)
                                                .put("unmeteredOnly", task.unmeteredOnly)
                                                .put("active", task.activeRun != null))
                                        }
                                    }
                                    val taskIds = tasks.map { it.id }.toSet()
                                    val history = JSONArray().apply {
                                        manager.repository.history.value.filter { it.taskId in taskIds }.take(100).forEach { entry ->
                                            put(JSONObject().put("id", entry.id).put("taskId", entry.taskId)
                                                .put("title", entry.title).put("status", entry.status.name)
                                                .put("message", entry.message ?: "").put("sessionId", entry.sessionId ?: "")
                                                .put("startedAt", entry.startedAt).put("finishedAt", entry.finishedAt ?: JSONObject.NULL))
                                        }
                                    }
                                    JSONObject().put("ok", true).put("tasks", items).put("history", history)
                                }
                            }
                            if (result.isSuccess) writeHttpResponse(sock, 200, "application/json; charset=utf-8", result.getOrThrow().toString())
                            else writeHttpResponse(sock, 500, "application/json; charset=utf-8",
                                JSONObject().put("ok", false).put("error", result.exceptionOrNull()?.message ?: "Unable to load automations").toString())
                        }
                    }
                    method == "GET" && path == "/api/buildtest" -> {
                        val app = appContext.applicationContext as? HarnessApp
                        if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                        } else {
                            val result = runCatching {
                                runBlocking {
                                    val container = app.container
                                    val requestedSessionId = queryValue(query, "sessionId")
                                    val sessionId = getOrCreateRemoteSession(app, requestedSessionId)
                                    val session = container.sessions.session(sessionId) ?: error("The selected chat no longer exists.")
                                    val workspace = container.workspace.forTools(sessionId, session.projectId).first()
                                        ?: error("The selected chat workspace is unavailable.")
                                    val project = container.workspace.projectForTools(sessionId, session.projectId).first()
                                    val projectId = project?.id ?: session.projectId ?: error("The selected chat has no project workspace.")
                                    val commands = JSONArray()
                                    if (runCatching { workspace.resolve("gradlew").exists }.getOrDefault(false)) {
                                        listOf("Tests" to "./gradlew test", "Debug APK" to "./gradlew assembleDebug", "Lint" to "./gradlew lint")
                                            .forEach { (name, command) -> commands.put(JSONObject().put("name", name).put("command", command)) }
                                    }
                                    val packageFile = runCatching { workspace.resolve("package.json") }.getOrNull()
                                    if (packageFile?.exists == true && packageFile.length <= 1_000_000) {
                                        val scripts = runCatching { com.androidharness.app.core.ProjectLaunch.scripts(packageFile.readText()) }.getOrDefault(emptyList())
                                        scripts.filter { it !in setOf("dev", "start", "preview") }.forEach { script ->
                                            commands.put(JSONObject().put("name", "npm $script").put("command", com.androidharness.app.core.ProjectLaunch.npmCommand(script)))
                                        }
                                    }
                                    val check = container.projectChecks.results.value[projectId]
                                    val terminal = container.terminal.state.value
                                    JSONObject().put("ok", true).put("sessionId", sessionId).put("projectId", projectId)
                                        .put("projectName", project?.name ?: "Workspace").put("commands", commands)
                                        .put("check", check?.let { JSONObject().put("name", it.name).put("status", it.status.name).put("updatedAt", it.updatedAt) } ?: JSONObject.NULL)
                                        .put("terminalBusy", terminal.busy || terminal.stopping)
                                        .put("output", JSONArray().apply { terminal.lines.takeLast(300).forEach { put(it) } })
                                }
                            }
                            if (result.isSuccess) writeHttpResponse(sock, 200, "application/json; charset=utf-8", result.getOrThrow().toString())
                            else writeHttpResponse(sock, 409, "application/json; charset=utf-8", JSONObject().put("ok", false).put("error", result.exceptionOrNull()?.message ?: "Unable to load Build & Test").toString())
                        }
                    }
                    method == "GET" && path == "/api/terminal/status" -> {
                        val app = appContext.applicationContext as? HarnessApp
                        if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                        } else {
                            val result = runCatching {
                                runBlocking {
                                    val requestedSessionId = queryValue(query, "sessionId")
                                    val sessionId = getOrCreateRemoteSession(app, requestedSessionId)
                                    val state = app.container.terminal.state.value
                                    JSONObject()
                                        .put("ok", true)
                                        .put("sessionId", sessionId)
                                        .put("lines", JSONArray().apply { state.lines.takeLast(600).forEach { put(it) } })
                                        .put("cwd", state.cwd)
                                        .put("busy", state.busy)
                                        .put("stopping", state.stopping)
                                        .put("started", state.started)
                                        .put("backend", state.backendLabel)
                                        .put("lastExitCode", state.lastExitCode ?: JSONObject.NULL)
                                }
                            }
                            if (result.isSuccess) writeHttpResponse(sock, 200, "application/json; charset=utf-8", result.getOrThrow().toString())
                            else writeHttpResponse(sock, 409, "application/json; charset=utf-8",
                                JSONObject().put("ok", false).put("error", result.exceptionOrNull()?.message ?: "Unable to read terminal").toString())
                        }
                    }
                    method == "GET" && path == "/api/chat/status" -> {
                        val app = appContext.applicationContext as? HarnessApp
                        if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                        } else {
                            val status = runCatching {
                                runBlocking {
                                    val requestedSessionId = queryValue(query, "sessionId")
                                    val sessionId = getOrCreateRemoteSession(app, requestedSessionId)
                                    val container = app.container
                                    val messages = container.sessions.messages(sessionId)
                                    val live = container.runManager.live(sessionId).value
                                    val modelState = container.chatModels.observe(sessionId).first()
                                    val selectedModel = modelState.selection.active
                                    val selectedProvider = selectedModel?.let { choice ->
                                        modelState.providers.firstOrNull { it.id == choice.providerId }
                                    }
                                    val statusJson = JSONObject()
                                        .put("ok", true)
                                        .put("busy", container.runManager.isRunning(sessionId))
                                        .put("provider", selectedProvider?.name ?: "No provider selected")
                                        .put("model", selectedModel?.model ?: "No model selected")
                                        .put("permissionMode", modelState.settings.permissionMode.name)
                                        .put("currentAction", live.currentToolAction ?: "")
                                        .put("retryStatus", live.retryStatus ?: "")
                                        .put("error", live.error ?: "")
                                        .put("streaming", !live.streamingText.isNullOrBlank())
                                        .put("streamingText", live.streamingText.orEmpty().takeLast(12_000))
                                        .put("streamingThinking", live.streamingThinking.orEmpty().takeLast(4_000))
                                        .put("runningCalls", JSONArray().apply {
                                            live.runningCalls.take(12).forEach { call ->
                                                put(JSONObject().put("id", call.id).put("name", call.name))
                                            }
                                        })
                                        .put("specialists", JSONArray().apply {
                                            live.subagentSteps.entries.take(8).forEach { (id, steps) ->
                                                put(JSONObject().put("id", id).put("steps", JSONArray().apply {
                                                    steps.takeLast(5).forEach { put(it.take(500)) }
                                                }))
                                            }
                                        })
                                        .put("messageCount", messages.count {
                                            it.role == com.androidharness.app.core.Role.USER ||
                                                it.role == com.androidharness.app.core.Role.ASSISTANT
                                        })
                                        .put("sessionId", sessionId)
                                        .put("sessionTitle", container.sessions.session(sessionId)?.title ?: "Active chat")
                                    val turnId = live.turnId
                                    live.pendingApproval?.let { pending ->
                                        if (turnId != null) {
                                            statusJson.put("pendingApproval", JSONObject()
                                                .put("sessionId", sessionId)
                                                .put("turnId", turnId)
                                                .put("callId", pending.call.id)
                                                .put("fingerprint", com.androidharness.app.agent.RemotePromptIdentity.approval(pending))
                                                .put("tool", pending.call.name)
                                                .put("description", pending.toolDescription)
                                                .put("arguments", pending.call.argumentsJson)
                                                .put("diffPreview", pending.diffPreview))
                                        }
                                    }
                                    live.pendingQuestion?.let { pending ->
                                        if (turnId != null) {
                                            statusJson.put("pendingQuestion", JSONObject()
                                                .put("sessionId", sessionId)
                                                .put("turnId", turnId)
                                                .put("callId", pending.callId)
                                                .put("fingerprint", com.androidharness.app.agent.RemotePromptIdentity.question(pending))
                                                .put("question", pending.question)
                                                .put("options", JSONArray().apply { pending.options.forEach { option -> put(option) } })
                                                .put("multiSelect", pending.multiSelect))
                                        }
                                    }
                                    statusJson
                                }
                            }.getOrElse {
                                JSONObject().put("ok", false).put("error", it.message ?: "Unable to read remote chat status")
                            }
                            writeHttpResponse(sock, if (status.optBoolean("ok")) 200 else 500,
                                "application/json; charset=utf-8", status.toString())
                        }
                    }
                    method == "POST" && path == "/api/chat/approval" -> {
                        val payload = try {
                            JSONObject(body.ifBlank { "{}" })
                        } catch (_: org.json.JSONException) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Malformed JSON request body"}""")
                            return
                        }
                        val sessionId = payload.optString("sessionId").trim()
                        val turnId = payload.optString("turnId").trim()
                        val callId = payload.optString("callId").trim()
                        val fingerprint = payload.optString("fingerprint").trim()
                        if (sessionId.isEmpty() || turnId.isEmpty() || callId.isEmpty() || fingerprint.isEmpty() || !payload.has("allow") || payload.opt("allow") !is Boolean) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"sessionId, turnId, callId, fingerprint, and a boolean allow value are required"}""")
                            return
                        }
                        val app = appContext.applicationContext as? HarnessApp
                        if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                            return
                        }
                        val accepted = runCatching {
                            runBlocking {
                                if (app.container.sessions.session(sessionId) == null) false
                                else app.container.runManager.respondToRemoteApproval(
                                    sessionId, turnId, callId, payload.getBoolean("allow"), fingerprint,
                                )
                            }
                        }.getOrDefault(false)
                        if (accepted) {
                            writeHttpResponse(sock, 200, "application/json; charset=utf-8", """{"ok":true}""")
                        } else {
                            writeHttpResponse(sock, 409, "application/json; charset=utf-8", """{"ok":false,"error":"This approval is no longer pending. Refresh the session and review the current request."}""")
                        }
                    }
                    method == "POST" && path == "/api/chat/question" -> {
                        val payload = try {
                            JSONObject(body.ifBlank { "{}" })
                        } catch (_: org.json.JSONException) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Malformed JSON request body"}""")
                            return
                        }
                        val sessionId = payload.optString("sessionId").trim()
                        val turnId = payload.optString("turnId").trim()
                        val callId = payload.optString("callId").trim()
                        val fingerprint = payload.optString("fingerprint").trim()
                        val answer = payload.optString("answer")
                        if (sessionId.isEmpty() || turnId.isEmpty() || callId.isEmpty() || fingerprint.isEmpty() || answer.isBlank()) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"sessionId, turnId, callId, fingerprint, and a non-empty answer are required"}""")
                            return
                        }
                        if (answer.length > 8_000) {
                            writeHttpResponse(sock, 413, "application/json; charset=utf-8", """{"error":"Answer exceeds the 8,000 character limit"}""")
                            return
                        }
                        val app = appContext.applicationContext as? HarnessApp
                        if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                            return
                        }
                        val accepted = runCatching {
                            runBlocking {
                                if (app.container.sessions.session(sessionId) == null) false
                                else app.container.runManager.respondToRemoteQuestion(sessionId, turnId, callId, answer, fingerprint)
                            }
                        }.getOrDefault(false)
                        if (accepted) {
                            writeHttpResponse(sock, 200, "application/json; charset=utf-8", """{"ok":true}""")
                        } else {
                            writeHttpResponse(sock, 409, "application/json; charset=utf-8", """{"ok":false,"error":"This question is no longer pending. Refresh the session and review the current request."}""")
                        }
                    }
                    method == "GET" && path == "/api/chat/history" -> {
                        val app = appContext.applicationContext as? HarnessApp
                        if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                        } else {
                            val history = runCatching {
                                runBlocking {
                                    val requestedSessionId = queryValue(query, "sessionId")
                                    val sessionId = getOrCreateRemoteSession(app, requestedSessionId)
                                    check(requestedSessionId.isBlank() || requestedSessionId == sessionId) {
                                        "The active AndroidHarness chat changed. Refresh the remote view."
                                    }
                                    JSONObject().put("sessionId", sessionId)
                                        .put("messages", remoteHistoryJson(app.container.sessions.messages(sessionId)))
                                }
                            }.getOrElse {
                                writeHttpResponse(sock, 500, "application/json; charset=utf-8", """{"error":"Unable to load shared chat history"}""")
                                return
                            }
                            writeHttpResponse(sock, 200, "application/json; charset=utf-8",
                                history.toString())
                        }
                    }
                    method == "POST" && path == "/api/automations/action" -> {
                        val payload = try { JSONObject(body.ifBlank { "{}" }) }
                        catch (_: org.json.JSONException) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Malformed JSON request body"}""")
                            return
                        }
                        val taskId = payload.optString("taskId").trim()
                        val projectId = payload.optString("projectId").trim()
                        val action = payload.optString("action").trim().lowercase()
                        val app = appContext.applicationContext as? HarnessApp
                        if (taskId.isBlank() || action !in setOf("run", "pause", "resume", "cancel", "delete")) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"A valid automation and action are required"}""")
                        } else if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                        } else {
                            val result = runCatching {
                                runBlocking {
                                    val manager = app.container.automation
                                    val task = manager.repository.task(taskId) ?: error("This automation no longer exists.")
                                    check(projectId.isBlank() || task.projectId == projectId) {
                                        "This automation does not belong to the selected project."
                                    }
                                    when (action) {
                                        "run" -> manager.runNow(taskId)
                                        "pause" -> manager.pause(taskId)
                                        "resume" -> manager.resumeSchedule(taskId)
                                        "cancel" -> manager.cancel(taskId)
                                        "delete" -> manager.delete(taskId)
                                    }
                                    JSONObject().put("ok", true).put("taskId", taskId).put("action", action)
                                }
                            }
                            if (result.isSuccess) writeHttpResponse(sock, 200, "application/json; charset=utf-8", result.getOrThrow().toString())
                            else writeHttpResponse(sock, 409, "application/json; charset=utf-8",
                                JSONObject().put("ok", false).put("error", result.exceptionOrNull()?.message ?: "Unable to update automation").toString())
                        }
                    }
                    method == "POST" && path == "/api/buildtest/run" -> {
                        val payload = try { JSONObject(body.ifBlank { "{}" }) }
                        catch (_: org.json.JSONException) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Malformed JSON request body"}""")
                            return
                        }
                        val requestedSessionId = payload.optString("sessionId").trim()
                        val requestedName = payload.optString("name").trim().take(128)
                        val requestedCommand = payload.optString("command").trim()
                        if (requestedSessionId.isBlank() || requestedName.isBlank() || requestedCommand.isBlank()) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"A session, check name and command are required"}""")
                            return
                        }
                        val app = appContext.applicationContext as? HarnessApp
                        if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                        } else {
                            val result = runCatching {
                                runBlocking {
                                    val container = app.container
                                    val sessionId = getOrCreateRemoteSession(app, requestedSessionId)
                                    check(sessionId == requestedSessionId) { "The selected chat changed. Refresh Build & Test." }
                                    val session = container.sessions.session(sessionId) ?: error("The selected chat no longer exists.")
                                    val workspace = container.workspace.forTools(sessionId, session.projectId).first()
                                        ?: error("The selected chat workspace is unavailable.")
                                    val project = container.workspace.projectForTools(sessionId, session.projectId).first()
                                    val projectId = project?.id ?: session.projectId ?: error("The selected chat has no project workspace.")
                                    val allowed = mutableListOf<Pair<String, String>>()
                                    if (runCatching { workspace.resolve("gradlew").exists }.getOrDefault(false)) {
                                        allowed += listOf("Tests" to "./gradlew test", "Debug APK" to "./gradlew assembleDebug", "Lint" to "./gradlew lint")
                                    }
                                    val packageFile = runCatching { workspace.resolve("package.json") }.getOrNull()
                                    if (packageFile?.exists == true && packageFile.length <= 1_000_000) {
                                        val scripts = runCatching { com.androidharness.app.core.ProjectLaunch.scripts(packageFile.readText()) }.getOrDefault(emptyList())
                                        scripts.filter { it !in setOf("dev", "start", "preview") }.forEach { script ->
                                            allowed.add("npm $script" to com.androidharness.app.core.ProjectLaunch.npmCommand(script))
                                        }
                                    }
                                    check(allowed.any { it.first == requestedName && it.second == requestedCommand }) {
                                        "That build command is not available for the selected project. Refresh the list."
                                    }
                                    val root = (workspace as? com.androidharness.app.workspace.SshFs)?.root
                                        ?: workspace.shellRoot?.absolutePath ?: error("The selected workspace has no shell directory.")
                                    val quotedRoot = "'" + root.replace("'", "'\\''") + "'"
                                    val shellCommand = "cd " + quotedRoot + " && " + requestedCommand
                                    val terminal = container.terminal
                                    check(!terminal.state.value.busy && !terminal.state.value.stopping) {
                                        "The shared AndroidHarness terminal is already running a command."
                                    }
                                    terminal.useWorkspace(workspace)
                                    terminal.clear()
                                    terminal.send(shellCommand, workspace, sessionId, projectId)
                                    container.projectChecks.track(projectId, requestedName, shellCommand)
                                    JSONObject().put("ok", true).put("accepted", true).put("sessionId", sessionId).put("projectId", projectId)
                                }
                            }
                            if (result.isSuccess) writeHttpResponse(sock, 202, "application/json; charset=utf-8", result.getOrThrow().toString())
                            else writeHttpResponse(sock, 409, "application/json; charset=utf-8", JSONObject().put("ok", false).put("error", result.exceptionOrNull()?.message ?: "Unable to start build check").toString())
                        }
                    }
                    method == "POST" && path == "/api/terminal/stop" -> {
                        val payload = try {
                            JSONObject(body.ifBlank { "{}" })
                        } catch (_: org.json.JSONException) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Malformed JSON request body"}""")
                            return
                        }
                        val requestedSessionId = payload.optString("sessionId").trim()
                        val app = appContext.applicationContext as? HarnessApp
                        if (requestedSessionId.isBlank()) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"A chat session is required"}""")
                        } else if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                        } else {
                            val result = runCatching {
                                runBlocking {
                                    val sessionId = getOrCreateRemoteSession(app, requestedSessionId)
                                    check(sessionId == requestedSessionId) { "The selected chat changed. Refresh the remote terminal." }
                                    app.container.terminal.interrupt()
                                    JSONObject().put("ok", true).put("sessionId", sessionId)
                                }
                            }
                            if (result.isSuccess) writeHttpResponse(sock, 202, "application/json; charset=utf-8", result.getOrThrow().toString())
                            else writeHttpResponse(sock, 409, "application/json; charset=utf-8",
                                JSONObject().put("ok", false).put("error", result.exceptionOrNull()?.message ?: "Unable to stop terminal command").toString())
                        }
                    }
                    method == "POST" && path == "/api/terminal/command" -> {
                        val payload = try {
                            JSONObject(body.ifBlank { "{}" })
                        } catch (_: org.json.JSONException) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Malformed JSON request body"}""")
                            return
                        }
                        val command = payload.optString("command").trimEnd()
                        val requestedSessionId = payload.optString("sessionId").trim()
                        if (command.isBlank() || requestedSessionId.isBlank()) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"A chat session and terminal command are required"}""")
                            return
                        }
                        if (command.length > 8_000) {
                            writeHttpResponse(sock, 413, "application/json; charset=utf-8", """{"error":"Terminal command exceeds the 8,000 character limit"}""")
                            return
                        }
                        val app = appContext.applicationContext as? HarnessApp
                        if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                        } else {
                            val result = runCatching {
                                runBlocking {
                                    val container = app.container
                                    val sessionId = getOrCreateRemoteSession(app, requestedSessionId)
                                    check(sessionId == requestedSessionId) { "The selected chat changed. Refresh the remote terminal." }
                                    val session = container.sessions.session(sessionId)
                                        ?: error("The selected chat no longer exists.")
                                    val terminal = container.terminal
                                    check(!terminal.state.value.busy && !terminal.state.value.stopping) {
                                        "The shared AndroidHarness terminal is already running a command."
                                    }
                                    val workspace = container.workspace.forTools(sessionId, session.projectId).first()
                                        ?: error("The selected chat workspace is unavailable.")
                                    terminal.send(command, workspace, sessionId, session.projectId)
                                    JSONObject().put("ok", true).put("accepted", true).put("sessionId", sessionId)
                                }
                            }
                            if (result.isSuccess) writeHttpResponse(sock, 202, "application/json; charset=utf-8", result.getOrThrow().toString())
                            else writeHttpResponse(sock, 409, "application/json; charset=utf-8",
                                JSONObject().put("ok", false).put("error", result.exceptionOrNull()?.message ?: "Unable to run terminal command").toString())
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
                                    val requestedSessionId = payload.optString("sessionId").trim()
                                    val sessionId = getOrCreateRemoteSession(app, requestedSessionId)
                                    check(requestedSessionId.isBlank() || requestedSessionId == sessionId) {
                                        "The active AndroidHarness chat changed. Refresh the remote view before sending."
                                    }
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
  main { flex: 1; display: grid; grid-template-columns: 180px minmax(200px,1fr) minmax(260px,330px) minmax(260px,320px) minmax(240px,280px) minmax(240px,280px); min-height: 0; }
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
  .msg.error { border-color: #7F1D1D; background: #2A1117; color: #FECACA; }
  .chat-input { display: flex; gap: 6px; padding: 10px; border-top: 1px solid #1E293B; background: #0F172A; align-items: center; }
  input, button { font-family: inherit; font-size: 13px; border-radius: 6px; }
  input { flex: 1; background: #1E293B; color: #F8FAFC; border: 1px solid #334155; padding: 9px 12px; outline: none; }
  button { background: #2563EB; color: #FFF; border: none; padding: 8px 12px; font-weight: 600; cursor: pointer; }
  button:hover { background: #1D4ED8; }
  .btn-secondary { background: #334155; }
  .btn-secondary:hover { background: #475569; }
  .decision-card { margin: 0 12px 10px; padding: 12px; border: 1px solid #D97706; border-radius: 10px; background: #1C1917; color: #F8FAFC; }
  .decision-title { font-size: 13px; font-weight: 700; color: #FBBF24; margin-bottom: 7px; }
  .decision-copy { font-size: 12px; line-height: 1.5; white-space: pre-wrap; overflow-wrap: anywhere; color: #E2E8F0; }
  .decision-details { margin-top: 8px; font-size: 11px; color: #CBD5E1; }
  .decision-details pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 180px; overflow: auto; background: #0B1120; padding: 8px; border-radius: 6px; }
  .decision-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
  .decision-actions button { flex: 1; min-width: 90px; }
  .decision-options { display: grid; gap: 7px; margin-top: 10px; font-size: 12px; }
  .decision-options label { display: flex; gap: 8px; align-items: flex-start; }
  .decision-options input { flex: 0 0 auto; margin-top: 2px; }
  .decision-answer { width: 100%; min-height: 70px; margin-top: 10px; resize: vertical; background: #0B1120; color: #F8FAFC; border: 1px solid #475569; border-radius: 6px; padding: 9px; font: inherit; }
  @media (max-width: 1400px) {
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
  <div style="display:flex;align-items:center;gap:10px;min-width:0">
    <select id="project-select" aria-label="Project for new chat" style="max-width:150px;background:#1E293B;color:#F8FAFC;border:1px solid #334155;border-radius:6px;padding:7px;font-size:12px">
      <option value="">Default workspace</option>
    </select>
    <select id="session-select" aria-label="AndroidHarness chat session" onchange="switchRemoteSession(this.value)" style="max-width:190px;background:#1E293B;color:#F8FAFC;border:1px solid #334155;border-radius:6px;padding:7px;font-size:12px">
      <option value="">Loading chats…</option>
    </select>
    <button class="btn-secondary" onclick="createRemoteChat()" style="padding:7px 9px">+ Chat</button>
    <button class="btn-secondary" id="project-rename" onclick="renameRemoteProject()" style="padding:7px 8px" disabled>Rename</button>
    <button class="btn-secondary" id="project-delete" onclick="deleteRemoteProject()" style="padding:7px 8px" disabled>Delete</button>
    <div class="meta" id="status-meta">Phone-hosted AndroidHarness runtime</div>
  </div>
</header>
<nav class="mobile-tabs" id="mobile-tabs">
  <button class="tab-btn active" id="btn-tab-chat" onclick="switchMobileTab('chat')">💬 Chat &amp; Voice</button>
  <button class="tab-btn" id="btn-tab-files" onclick="switchMobileTab('files')">📁 Files</button>
  <button class="tab-btn" id="btn-tab-editor" onclick="switchMobileTab('editor')">📝 Editor</button>
  <button class="tab-btn" id="btn-tab-terminal" data-tab="terminal" onclick="switchMobileTab(this.dataset.tab)">⌘ Terminal</button>
  <button class="tab-btn" id="btn-tab-buildtest" data-tab="buildtest" onclick="switchMobileTab(this.dataset.tab)">✓ Build/Test</button>
  <button class="tab-btn" id="btn-tab-automations" data-tab="automations" onclick="switchMobileTab(this.dataset.tab)">◷ Automations</button>
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
  <section class="panel" id="panel-automations" style="border-right:none">
    <div class="panel-header"><span>Automations</span><span id="automation-count">Loading…</span></div>
    <div id="automation-list" style="flex:1;min-height:0;overflow:auto;padding:9px;display:flex;flex-direction:column;gap:8px"></div>
    <div style="padding:8px;border-top:1px solid #1E293B;color:#94A3B8;font-size:10px">Controls existing phone-scheduled tasks. Create and edit schedules in the native AndroidHarness automation editor.</div>
  </section>
  <section class="panel" id="panel-buildtest" style="border-right:none">
    <div class="panel-header"><span>Build &amp; Test</span><span id="buildtest-status">Loading…</span></div>
    <div id="buildtest-project" style="padding:9px 10px;color:#CBD5E1;font-size:12px;border-bottom:1px solid #1E293B">Selected project</div>
    <div id="buildtest-check" style="padding:10px;border-bottom:1px solid #1E293B;color:#94A3B8;font-size:12px">No checks recorded yet.</div>
    <pre id="buildtest-output" style="max-height:180px;overflow:auto;margin:0;padding:9px;border-bottom:1px solid #1E293B;background:#050914;color:#D1FAE5;font:10px/1.4 monospace;white-space:pre-wrap;overflow-wrap:anywhere">Build output will appear here.</pre>
    <div id="buildtest-commands" style="flex:1;min-height:0;overflow:auto;padding:9px;display:flex;flex-direction:column;gap:8px"></div>
    <div style="padding:8px;border-top:1px solid #1E293B;color:#94A3B8;font-size:10px">Uses the project checks already available in the native AndroidHarness Build &amp; Test screen. Results are shared with the phone.</div>
  </section>
  <section class="panel" id="panel-terminal" style="border-right:none">
    <div class="panel-header"><span>Phone Terminal</span><span id="terminal-meta">Connecting…</span></div>
    <div id="terminal-cwd" style="padding:7px 10px;color:#94A3B8;font:11px monospace">Workspace shell</div>
    <pre id="terminal-output" style="flex:1;min-height:0;overflow:auto;margin:0;padding:10px;background:#050914;color:#D1FAE5;font:12px/1.5 monospace;white-space:pre-wrap;overflow-wrap:anywhere">Terminal output will appear here.</pre>
    <div style="padding:8px;border-top:1px solid #1E293B;background:#0F172A">
      <textarea id="terminal-command" rows="3" maxlength="8000" placeholder="Run a command on the phone workspace…" style="width:100%;resize:vertical;background:#1E293B;color:#F8FAFC;border:1px solid #334155;border-radius:6px;padding:9px;font:12px monospace"></textarea>
      <div style="display:flex;gap:7px;margin-top:7px"><button id="terminal-run" onclick="sendRemoteTerminalCommand()" style="flex:1">Run command</button><button class="btn-secondary" id="terminal-stop" onclick="stopRemoteTerminalCommand()" disabled>Stop</button></div>
      <div style="font-size:10px;color:#94A3B8;margin-top:6px">Runs on the phone using AndroidHarness configured shell and access tier. Shared with native terminal.</div>
    </div>
  </section>
  <section class="panel tab-active" id="panel-chat" style="border-right:none">
    <div class="panel-header">
      <span>AI Specialist Team</span>
      <div style="display:flex;gap:6px;align-items:center">
        <button class="btn-secondary" id="read-btn" style="padding:4px 8px;font-size:11px" onclick="toggleAutoSpeech()">🔊 Auto Read: ON</button>
        <span id="model-tag" style="color:#38BDF8;font-size:11px">Loading model…</span>
      </div>
    </div>
    <div class="model-controls" style="display:flex;gap:8px;padding:8px 10px;border-bottom:1px solid #1E293B;background:#0F172A">
      <select id="provider-select" aria-label="AI provider" onchange="onRemoteProviderChanged()" style="min-width:0;flex:1;background:#1E293B;color:#F8FAFC;border:1px solid #334155;border-radius:6px;padding:7px;font-size:12px"></select>
      <select id="model-select" aria-label="AI model" onchange="saveRemoteModel()" style="min-width:0;flex:1.3;background:#1E293B;color:#F8FAFC;border:1px solid #334155;border-radius:6px;padding:7px;font-size:12px"></select>
      <select id="permission-select" aria-label="Tool permission mode" onchange="saveRemotePermissionMode()" style="min-width:0;flex:1;background:#1E293B;color:#F8FAFC;border:1px solid #334155;border-radius:6px;padding:7px;font-size:12px"></select>
    </div>
    <div id="execution-status" style="display:none;padding:9px 12px;border-bottom:1px solid #1E293B;background:#111827" aria-live="polite"></div>
    <div class="chat-messages" id="chat-box">
      <div class="msg ai">Connected virtually to AndroidHarness! You can speak or chat with the AI model team and edit phone workspace files right from this browser without installing anything.</div>
    </div>
    <div id="pending-controls" aria-live="polite"></div>
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
let remoteProviders = [];
let remotePermissionModes = [];
let permissionSaveInFlight = false;
let modelSaveInFlight = false;
let bridgeSessionId = sessionStorage.getItem("androidHarnessBridgeSessionId") || "";
function fillModelSelect(providerId, selectedModel) {
  const provider = remoteProviders.find(item => item.id === providerId);
  const select = document.getElementById("model-select");
  select.replaceChildren();
  const models = provider && Array.isArray(provider.models) ? provider.models : [];
  const options = models.length ? models.slice() : [{ id: provider ? provider.model : selectedModel, name: provider ? provider.model : selectedModel }];
  if (selectedModel && !options.some(item => item.id === selectedModel)) options.unshift({ id: selectedModel, name: selectedModel });
  options.forEach(item => {
    const option = document.createElement("option");
    option.value = item.id;
    option.textContent = item.name || item.id;
    select.appendChild(option);
  });
  if (selectedModel) select.value = selectedModel;
  select.disabled = !provider || modelSaveInFlight;
}
async function loadRemoteModels() {
  if (!bridgeSessionId || modelSaveInFlight) return;
  try {
    const requestedSessionId = bridgeSessionId;
    const res = await apiFetch("/api/models?sessionId=" + encodeURIComponent(requestedSessionId));
    const data = await res.json();
    if (!res.ok || requestedSessionId !== bridgeSessionId) return;
    remoteProviders = Array.isArray(data.providers) ? data.providers : [];
    remotePermissionModes = Array.isArray(data.permissionModes) ? data.permissionModes : [];
    const permissionSelect = document.getElementById("permission-select");
    permissionSelect.replaceChildren();
    remotePermissionModes.forEach(mode => {
      const option = document.createElement("option");
      option.value = mode.id;
      option.textContent = mode.label || mode.id;
      permissionSelect.appendChild(option);
    });
    permissionSelect.value = data.permissionMode || "";
    permissionSelect.disabled = permissionSaveInFlight || remotePermissionModes.length === 0;
    const providerSelect = document.getElementById("provider-select");
    providerSelect.replaceChildren();
    remoteProviders.forEach(provider => {
      const option = document.createElement("option");
      option.value = provider.id;
      option.textContent = provider.name;
      providerSelect.appendChild(option);
    });
    providerSelect.value = data.selectedProviderId || "";
    fillModelSelect(providerSelect.value, data.selectedModel || "");
    const selected = remoteProviders.find(item => item.id === providerSelect.value);
    document.getElementById("model-tag").textContent = selected
      ? selected.name + " · " + (data.selectedModel || selected.model) : "No model selected";
    providerSelect.disabled = remoteProviders.length === 0;
  } catch (_) { /* Keep last valid model selection during brief disconnects. */ }
}
async function saveRemotePermissionMode() {
  if (!bridgeSessionId || permissionSaveInFlight) return;
  const select = document.getElementById("permission-select");
  const requestedMode = select.value;
  if (!requestedMode) return;
  permissionSaveInFlight = true;
  select.disabled = true;
  try {
    const res = await apiFetch("/api/permission", {
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({sessionId: bridgeSessionId, mode: requestedMode})
    });
    const data = await res.json();
    if (!res.ok || data.ok === false) throw new Error(data.error || "Unable to change permission mode");
    select.value = data.permissionMode;
  } catch (error) {
    await loadRemoteModels();
    const status = document.getElementById("execution-status");
    status.style.display = "block";
    status.textContent = "Permission setting not changed: " + error.message;
  } finally {
    permissionSaveInFlight = false;
    select.disabled = remotePermissionModes.length === 0;
  }
}
async function onRemoteProviderChanged() {
  const provider = remoteProviders.find(item => item.id === document.getElementById("provider-select").value);
  if (!provider) return;
  fillModelSelect(provider.id, provider.model);
  await saveRemoteModel();
}
async function saveRemoteModel() {
  if (modelSaveInFlight || !bridgeSessionId) return;
  const providerId = document.getElementById("provider-select").value;
  const model = document.getElementById("model-select").value;
  if (!providerId || !model) return;
  modelSaveInFlight = true;
  document.getElementById("provider-select").disabled = true;
  document.getElementById("model-select").disabled = true;
  try {
    const res = await apiFetch("/api/model", {
      method: "POST", headers: {"Content-Type":"application/json"},
      body: JSON.stringify({ sessionId: bridgeSessionId, providerId, model })
    });
    const data = await res.json();
    if (!res.ok || !data.ok) throw new Error(data.error || "Unable to change model");
    document.getElementById("model-tag").textContent = data.provider + " · " + data.model;
  } catch (error) {
    document.getElementById("status-meta").textContent = "Model selection not saved · " + error.message;
    await loadRemoteModels();
  } finally {
    modelSaveInFlight = false;
    document.getElementById("provider-select").disabled = remoteProviders.length === 0;
    document.getElementById("model-select").disabled = remoteProviders.length === 0;
  }
}
try { chatHistory = JSON.parse(sessionStorage.getItem("androidHarnessChatHistory") || "[]"); } catch (_) { chatHistory = []; }
function resetRemoteChatForSession(sessionId) {
  bridgeSessionId = sessionId || "";
  sessionStorage.setItem("androidHarnessBridgeSessionId", bridgeSessionId);
  chatHistory = [];
  sessionStorage.setItem("androidHarnessChatHistory", "[]");
  clearPendingControls();
  remoteProviders = [];
  document.getElementById("provider-select").replaceChildren();
  document.getElementById("model-select").replaceChildren();
  document.getElementById("permission-select").replaceChildren();
  remotePermissionModes = [];
  document.getElementById("model-tag").textContent = "Loading model…";
  activeFilePath = "";
  const editor = document.getElementById("editor");
  if (editor) editor.value = "";
  const currentFile = document.getElementById("current-file");
  if (currentFile) currentFile.textContent = "Select or create file";
  if (typeof loadFiles === "function") loadFiles().catch(() => {});
  const box = document.getElementById("chat-box");
  if (box) {
    box.replaceChildren();
    const notice = document.createElement("div");
    notice.className = "msg ai";
    notice.textContent = "Switched to the active AndroidHarness conversation.";
    box.appendChild(notice);
  }
}
let autoVoiceRead = true;
let isRecognizing = false;

let terminalRefreshInFlight = false;
let terminalCommandInFlight = false;
let lastTerminalSnapshot = "";
async function refreshRemoteTerminal() {
  if (!bridgeSessionId || terminalRefreshInFlight || !sessionToken) return;
  terminalRefreshInFlight = true;
  try {
    const requestedSessionId = bridgeSessionId;
    const res = await apiFetch("/api/terminal/status?sessionId=" + encodeURIComponent(requestedSessionId));
    const data = await res.json();
    if (!res.ok || !data.ok || requestedSessionId !== bridgeSessionId) throw new Error(data.error || "Terminal unavailable");
    const output = document.getElementById("terminal-output");
    const snapshot = Array.isArray(data.lines) ? data.lines.join("\n") : "";
    if (snapshot !== lastTerminalSnapshot) {
      const nearBottom = output.scrollHeight - output.scrollTop - output.clientHeight < 80;
      output.textContent = snapshot || "Terminal ready. Enter a command below.";
      if (nearBottom) output.scrollTop = output.scrollHeight;
      lastTerminalSnapshot = snapshot;
    }
    document.getElementById("terminal-cwd").textContent = data.cwd || "Workspace shell";
    document.getElementById("terminal-meta").textContent = (data.busy ? "Running" : data.started ? "Ready" : "Idle") + " · " + (data.backend || "Normal") + (data.lastExitCode === null ? "" : " · exit " + data.lastExitCode);
    document.getElementById("terminal-run").disabled = !!data.busy || !!data.stopping || terminalCommandInFlight;
    document.getElementById("terminal-stop").disabled = !data.busy || !!data.stopping;
  } catch (error) { document.getElementById("terminal-meta").textContent = error.message || "Terminal unavailable"; }
  finally { terminalRefreshInFlight = false; }
}
async function stopRemoteTerminalCommand() {
  if (!bridgeSessionId) return;
  const stop = document.getElementById("terminal-stop");
  stop.disabled = true;
  try {
    const res = await apiFetch("/api/terminal/stop", {
      method: "POST", headers: {"Content-Type":"application/json"},
      body: JSON.stringify({sessionId: bridgeSessionId})
    });
    const data = await res.json();
    if (!res.ok || !data.ok) throw new Error(data.error || "Unable to stop command");
    document.getElementById("terminal-meta").textContent = "Stopping…";
  } catch (error) {
    document.getElementById("terminal-meta").textContent = error.message || "Stop failed";
  } finally { await refreshRemoteTerminal(); }
}
async function sendRemoteTerminalCommand() {
  const input = document.getElementById("terminal-command");
  const command = input.value.trimEnd();
  if (!command || terminalCommandInFlight || !bridgeSessionId) return;
  terminalCommandInFlight = true;
  document.getElementById("terminal-run").disabled = true;
  try {
    const res = await apiFetch("/api/terminal/command", { method: "POST", headers: {"Content-Type":"application/json"}, body: JSON.stringify({sessionId: bridgeSessionId, command}) });
    const data = await res.json();
    if (!res.ok || !data.ok) throw new Error(data.error || "Command was not accepted");
    input.value = "";
  } catch (error) { document.getElementById("terminal-meta").textContent = error.message || "Command failed"; }
  finally { terminalCommandInFlight = false; await refreshRemoteTerminal(); }
}
let buildTestRefreshInFlight = false;
let buildTestRunInFlight = false;
async function refreshRemoteBuildTest() {
  if (!bridgeSessionId || buildTestRefreshInFlight || !sessionToken) return;
  buildTestRefreshInFlight = true;
  try {
    const requestedSessionId = bridgeSessionId;
    const res = await apiFetch("/api/buildtest?sessionId=" + encodeURIComponent(requestedSessionId));
    const data = await res.json();
    if (!res.ok || !data.ok || requestedSessionId !== bridgeSessionId) throw new Error(data.error || "Build & Test unavailable");
    document.getElementById("buildtest-project").textContent = data.projectName || "Workspace";
    const check = data.check;
    const checkHost = document.getElementById("buildtest-check");
    checkHost.textContent = check ? check.name + " · " + check.status + " · " + new Date(check.updatedAt).toLocaleString() : "No checks recorded for this project yet.";
    checkHost.style.color = check && check.status === "PASSED" ? "#86EFAC" : check && check.status === "FAILED" ? "#FCA5A5" : "#94A3B8";
    const host = document.getElementById("buildtest-commands");
    const commands = Array.isArray(data.commands) ? data.commands : [];
    const signature = JSON.stringify(commands.map(item => [item.name,item.command]));
    if (host.dataset.signature !== signature) {
      host.replaceChildren();
      host.dataset.signature = signature;
      if (!commands.length) { const empty = document.createElement("div"); empty.textContent = "No supported Gradle wrapper or npm scripts were detected in this project."; empty.style.cssText = "font-size:12px;color:#94A3B8;line-height:1.5"; host.appendChild(empty); }
      commands.forEach(item => {
        const card = document.createElement("div"); card.style.cssText = "padding:9px;border:1px solid #334155;border-radius:8px;background:#0F172A";
        const title = document.createElement("div"); title.textContent = item.name; title.style.cssText = "font-size:12px;font-weight:700;margin-bottom:5px";
        const code = document.createElement("code"); code.textContent = item.command; code.style.cssText = "display:block;color:#94A3B8;font-size:10px;overflow-wrap:anywhere;margin-bottom:8px";
        const run = document.createElement("button"); run.textContent = "Run"; run.disabled = !!data.terminalBusy || buildTestRunInFlight;
        run.onclick = () => runRemoteBuildCheck(item.name,item.command);
        card.append(title,code,run); host.appendChild(card);
      });
    } else { host.querySelectorAll("button").forEach(button => { button.disabled = !!data.terminalBusy || buildTestRunInFlight; }); }
    document.getElementById("buildtest-output").textContent = Array.isArray(data.output) && data.output.length ? data.output.join("\n") : "Build output will appear here.";
    document.getElementById("buildtest-status").textContent = data.terminalBusy ? "Running" : "Ready";
  } catch (error) { document.getElementById("buildtest-status").textContent = error.message || "Unavailable"; }
  finally { buildTestRefreshInFlight = false; }
}
async function runRemoteBuildCheck(name,command) {
  if (buildTestRunInFlight || !bridgeSessionId) return;
  buildTestRunInFlight = true;
  document.getElementById("buildtest-status").textContent = "Starting…";
  try {
    const res = await apiFetch("/api/buildtest/run", { method:"POST", headers:{"Content-Type":"application/json"}, body:JSON.stringify({sessionId:bridgeSessionId,name,command}) });
    const data = await res.json();
    if (!res.ok || !data.ok) throw new Error(data.error || "Build check was not accepted");
    document.getElementById("buildtest-status").textContent = "Running";
  } catch (error) { document.getElementById("buildtest-status").textContent = error.message || "Unable to start"; }
  finally { buildTestRunInFlight = false; await refreshRemoteBuildTest(); }
}
let automationRefreshInFlight = false;
async function loadRemoteAutomations() {
  if (automationRefreshInFlight || !sessionToken) return;
  automationRefreshInFlight = true;
  try {
    const projectId = document.getElementById("project-select")?.value || "";
    const res = await apiFetch("/api/automations" + (projectId ? "?projectId=" + encodeURIComponent(projectId) : ""));
    const data = await res.json();
    if (!res.ok || !data.ok) throw new Error(data.error || "Unable to load automations");
    const tasks = Array.isArray(data.tasks) ? data.tasks : [];
    document.getElementById("automation-count").textContent = tasks.length + " task(s)";
    const host = document.getElementById("automation-list"); host.replaceChildren();
    if (!tasks.length) { const empty = document.createElement("div"); empty.textContent = "No automations in this project yet."; empty.style.cssText = "font-size:12px;color:#94A3B8;line-height:1.5"; host.appendChild(empty); }
    tasks.forEach(task => {
      const card = document.createElement("div"); card.style.cssText = "padding:9px;border:1px solid #334155;border-radius:8px;background:#0F172A";
      const title = document.createElement("div"); title.textContent = task.title || "Untitled automation"; title.style.cssText = "font-size:12px;font-weight:700;overflow-wrap:anywhere";
      const meta = document.createElement("div"); meta.textContent = task.schedule + " · " + task.status + (task.enabled ? " · enabled" : " · paused"); meta.style.cssText = "font-size:10px;color:#7DD3FC;margin-top:4px";
      const detail = document.createElement("div"); detail.textContent = task.lastMessage || task.prompt || ""; detail.style.cssText = "font-size:11px;color:#94A3B8;line-height:1.4;white-space:pre-wrap;overflow-wrap:anywhere;margin-top:6px";
      const actions = document.createElement("div"); actions.style.cssText = "display:flex;flex-wrap:wrap;gap:5px;margin-top:8px";
      const addAction = (label,action,secondary) => { const button = document.createElement("button"); button.textContent = label; if (secondary) button.className = "btn-secondary"; button.style.cssText = "padding:6px 8px;font-size:10px"; button.onclick = () => runAutomationAction(task,action); actions.appendChild(button); };
      addAction("Run now","run",false);
      if (task.enabled) addAction("Pause","pause",true); else addAction("Resume","resume",true);
      if (task.active) addAction("Cancel","cancel",true);
      addAction("Delete","delete",true);
      card.append(title,meta,detail,actions); host.appendChild(card);
    });
  } catch (error) { document.getElementById("automation-count").textContent = "Unavailable"; }
  finally { automationRefreshInFlight = false; }
}
async function runAutomationAction(task,action) {
  if (action === "delete" && !confirm("Delete automation ‘" + task.title + "’?")) return;
  try {
    const res = await apiFetch("/api/automations/action", { method:"POST", headers:{"Content-Type":"application/json"}, body:JSON.stringify({taskId:task.id,projectId:task.projectId,action}) });
    const data = await res.json();
    if (!res.ok || !data.ok) throw new Error(data.error || "Automation action failed");
    await loadRemoteAutomations();
  } catch (error) { alert(error.message || "Automation action failed"); }
}
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
    if (!bridgeSessionId) return;
    const res = await apiFetch("/api/chat/history?sessionId=" + encodeURIComponent(bridgeSessionId));
    if (!res.ok) return;
    const data = await res.json();
    if (data.sessionId && data.sessionId !== bridgeSessionId) return;
    chatHistory = Array.isArray(data.messages) ? data.messages : [];
    sessionStorage.setItem("androidHarnessChatHistory", JSON.stringify(chatHistory));
    const box = document.getElementById("chat-box");
    box.innerHTML = "";
    chatHistory.forEach(m => {
      const node = document.createElement("div");
      node.className = "msg " + (m.role === "user" ? "user" : "ai") + (m.isError ? " error" : "");
      node.textContent = m.content || "";
      box.appendChild(node);
    });
    box.scrollTop = box.scrollHeight;
  } catch (_) { /* Keep the current UI if shared history is temporarily unavailable. */ }
}

async function loadFiles() {
  if (!bridgeSessionId) return;
  const res = await apiFetch("/api/files?sessionId=" + encodeURIComponent(bridgeSessionId));
  const data = await res.json();
  if (data.sessionId && data.sessionId !== bridgeSessionId) return;
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
  const requestedSessionId = bridgeSessionId;
  activeFilePath = path;
  document.getElementById("current-file").textContent = path;
  const res = await apiFetch("/api/file?path=" + encodeURIComponent(path) + "&sessionId=" + encodeURIComponent(requestedSessionId));
  const data = await res.json();
  if (requestedSessionId !== bridgeSessionId) return;
  if (!res.ok) throw new Error(data.error || "Unable to open file");
  if (!bridgeSessionId) return;
  document.getElementById("editor").value = data.content || "";
  loadFiles();
}

async function createNewFile() {
  const name = prompt("Enter file name (e.g. index.html):", "index.html");
  if (!name) return;
  if (!bridgeSessionId) { alert("Waiting for AndroidHarness to connect."); return; }
  activeFilePath = name;
  document.getElementById("current-file").textContent = name;
  document.getElementById("editor").value = "<!doctype html>\n<html>\n<body>\n  <h1>Hello from AndroidHarness</h1>\n</body>\n</html>";
  await saveCurrentFile();
  if (window.innerWidth <= 768) switchMobileTab("editor");
}

async function saveCurrentFile() {
  if (!activeFilePath) { activeFilePath = "index.html"; document.getElementById("current-file").textContent = activeFilePath; }
  const res = await apiFetch("/api/file", {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({ sessionId: bridgeSessionId, path: activeFilePath, content: document.getElementById("editor").value })
  });
  const data = await res.json();
  if (!res.ok || !data.ok) throw new Error(data.error || "Unable to save file");
  loadFiles();
}

let bridgeRefreshInFlight = false;
let bridgeAssistantBusy = false;
let pendingControlKey = "";
function clearPendingControls() {
  const host = document.getElementById("pending-controls");
  if (host) host.replaceChildren();
  pendingControlKey = "";
}
async function submitRemoteDecision(endpoint, payload) {
  const host = document.getElementById("pending-controls");
  if (host) host.querySelectorAll("button,input,textarea").forEach(el => { el.disabled = true; });
  try {
    const res = await apiFetch(endpoint, {
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify(payload)
    });
    const data = await res.json();
    if (!res.ok || !data.ok) throw new Error(data.error || "The response could not be submitted.");
    clearPendingControls();
    await refreshBridgeStatus();
  } catch (error) {
    const label = document.getElementById("status-meta");
    if (label) label.textContent = "Decision not submitted · " + error.message;
    if (host) host.querySelectorAll("button,input,textarea").forEach(el => { el.disabled = false; });
  }
}
function renderExecutionStatus(data) {
  const host = document.getElementById("execution-status");
  if (!host) return;
  host.replaceChildren();
  const active = !!data.busy;
  const specialists = Array.isArray(data.specialists) ? data.specialists : [];
  const errorText = typeof data.error === "string" ? data.error : "";
  const calls = Array.isArray(data.runningCalls) ? data.runningCalls : [];
  const action = data.retryStatus || data.currentAction || (calls.length ? "Running " + calls.length + " tool action(s)" : "");
  if (!active && !specialists.length && !action && !errorText) {
    host.style.display = "none";
    return;
  }
  host.style.display = "block";
  const heading = document.createElement("div");
  heading.style.cssText = "font-size:12px;font-weight:700;color:#7DD3FC;margin-bottom:6px";
  heading.textContent = data.retryStatus ? "AI team · " + data.retryStatus : active ? "AI team · Working" : errorText ? "AI team · Stopped" : "AI team · Recent activity";
  host.appendChild(heading);
  if (errorText) {
    const error = document.createElement("div");
    error.style.cssText = "font-size:12px;color:#FCA5A5;white-space:pre-wrap;overflow-wrap:anywhere";
    error.textContent = errorText;
    host.appendChild(error);
  }
  if (action && !data.retryStatus) {
    const line = document.createElement("div");
    line.style.cssText = "font-size:12px;color:#CBD5E1;margin-bottom:5px";
    line.textContent = action;
    host.appendChild(line);
  }
  calls.forEach(call => {
    const line = document.createElement("div");
    line.style.cssText = "font-size:11px;color:#A5B4FC;margin:3px 0";
    line.textContent = "Tool · " + (call.name || "Working");
    host.appendChild(line);
  });
  if (data.streamingText) {
    const preview = document.createElement("div");
    preview.style.cssText = "margin-top:7px;padding:8px;border:1px solid #334155;border-radius:7px;background:#0B1120;color:#E2E8F0;font-size:12px;line-height:1.5;white-space:pre-wrap;overflow-wrap:anywhere;max-height:220px;overflow:auto";
    preview.textContent = data.streamingText;
    host.appendChild(preview);
  } else if (data.streamingThinking) {
    const thinking = document.createElement("div");
    thinking.style.cssText = "margin-top:7px;color:#94A3B8;font-size:11px;white-space:pre-wrap;overflow-wrap:anywhere";
    thinking.textContent = "Thinking · " + data.streamingThinking;
    host.appendChild(thinking);
  }
  specialists.forEach((specialist, index) => {
    const card = document.createElement("div");
    card.style.cssText = "margin-top:6px;padding:7px 8px;border:1px solid #334155;border-radius:7px;background:#0B1120";
    const name = document.createElement("div");
    name.style.cssText = "font-size:11px;font-weight:650;color:#E2E8F0";
    name.textContent = "Specialist " + (index + 1);
    card.appendChild(name);
    const steps = Array.isArray(specialist.steps) ? specialist.steps : [];
    steps.forEach(step => {
      const detail = document.createElement("div");
      detail.style.cssText = "font-size:11px;color:#94A3B8;margin-top:3px;white-space:pre-wrap;overflow-wrap:anywhere";
      detail.textContent = step;
      card.appendChild(detail);
    });
    host.appendChild(card);
  });
}
function renderPendingControls(data) {
  const host = document.getElementById("pending-controls");
  if (!host) return;
  const approval = data.pendingApproval || null;
  const question = data.pendingQuestion || null;
  const pending = approval ? ["approval", approval] : question ? ["question", question] : null;
  if (!pending) { clearPendingControls(); return; }
  const kind = pending[0], item = pending[1];
  const key = kind + ":" + item.fingerprint + ":" + item.callId;
  if (key === pendingControlKey) return;
  pendingControlKey = key;
  host.replaceChildren();
  const card = document.createElement("div");
  card.className = "decision-card";
  const title = document.createElement("div");
  title.className = "decision-title";
  title.textContent = kind === "approval" ? "Approval required" : "Your answer is needed";
  card.appendChild(title);
  const copy = document.createElement("div");
  copy.className = "decision-copy";
  copy.textContent = kind === "approval"
    ? (item.description || ("AndroidHarness wants to run " + item.tool))
    : (item.question || "The assistant needs more information.");
  card.appendChild(copy);
  if (kind === "approval") {
    const details = document.createElement("details");
    details.className = "decision-details";
    const summary = document.createElement("summary");
    summary.textContent = "Review tool and arguments";
    const pre = document.createElement("pre");
    pre.textContent = (item.tool || "Tool") + "\n" + (item.arguments || "");
    if (item.diffPreview) pre.textContent += "\n\nProposed changes:\n" + item.diffPreview;
    details.append(summary, pre);
    card.appendChild(details);
    const actions = document.createElement("div");
    actions.className = "decision-actions";
    const deny = document.createElement("button");
    deny.className = "btn-secondary";
    deny.textContent = "Deny";
    deny.onclick = () => submitRemoteDecision("/api/chat/approval", {
      sessionId: item.sessionId, turnId: item.turnId, callId: item.callId, fingerprint: item.fingerprint, allow: false
    });
    const allow = document.createElement("button");
    allow.textContent = "Approve";
    allow.onclick = () => submitRemoteDecision("/api/chat/approval", {
      sessionId: item.sessionId, turnId: item.turnId, callId: item.callId, fingerprint: item.fingerprint, allow: true
    });
    actions.append(deny, allow);
    card.appendChild(actions);
  } else {
    const options = Array.isArray(item.options) ? item.options : [];
    if (options.length) {
      const list = document.createElement("div");
      list.className = "decision-options";
      options.forEach((option, index) => {
        const label = document.createElement("label");
        const input = document.createElement("input");
        input.type = item.multiSelect ? "checkbox" : "radio";
        input.name = "remote-question-option";
        input.value = option;
        const text = document.createElement("span");
        text.textContent = option;
        label.append(input, text);
        list.appendChild(label);
      });
      card.appendChild(list);
    }
    const answer = document.createElement("textarea");
    answer.className = "decision-answer";
    answer.placeholder = options.length ? "Or add details / explain your choice…" : "Type your answer…";
    answer.maxLength = 8000;
    card.appendChild(answer);
    const actions = document.createElement("div");
    actions.className = "decision-actions";
    const send = document.createElement("button");
    send.textContent = "Submit answer";
    send.onclick = () => {
      const selected = Array.from(card.querySelectorAll(".decision-options input:checked")).map(el => el.value);
      const typed = answer.value.trim();
      const response = [selected.join(item.multiSelect ? ", " : ""), typed].filter(Boolean).join("\n");
      if (!response) { answer.focus(); return; }
      submitRemoteDecision("/api/chat/question", {
        sessionId: item.sessionId, turnId: item.turnId, callId: item.callId, fingerprint: item.fingerprint, answer: response
      });
    };
    actions.appendChild(send);
    card.appendChild(actions);
  }
  host.appendChild(card);
}
function updateProjectActions() {
  const select = document.getElementById("project-select");
  const option = select && select.selectedOptions[0];
  const canManage = !!(select && select.value && option && option.dataset.kind !== "app");
  document.getElementById("project-rename").disabled = !canManage;
  document.getElementById("project-delete").disabled = !canManage;
}
async function loadRemoteProjects() {
  const select = document.getElementById("project-select");
  if (!select || !sessionToken) return;
  const previous = select.value;
  try {
    const res = await apiFetch("/api/projects");
    const data = await res.json();
    if (!res.ok || !Array.isArray(data.projects)) throw new Error(data.error || "Unable to load projects");
    select.replaceChildren();
    const defaultOption = document.createElement("option");
    defaultOption.value = "";
    defaultOption.textContent = "Default workspace";
    select.appendChild(defaultOption);
    data.projects.forEach(project => {
      const option = document.createElement("option");
      option.value = project.id;
      option.textContent = project.name || "Untitled project";
      option.dataset.kind = project.kind || "";
      select.appendChild(option);
    });
    if (data.projects.some(project => project.id === previous)) select.value = previous;
    updateProjectActions();
  } catch (_) { updateProjectActions(); }
}
async function renameRemoteProject() {
  const select = document.getElementById("project-select");
  const projectId = select.value;
  const current = select.selectedOptions[0]?.textContent || "";
  if (!projectId) return;
  const name = prompt("Rename this AndroidHarness project:", current);
  if (name === null || !name.trim()) return;
  try {
    const res = await apiFetch("/api/projects/rename", {
      method: "POST", headers: {"Content-Type":"application/json"},
      body: JSON.stringify({projectId, name})
    });
    const data = await res.json();
    if (!res.ok || !data.ok) throw new Error(data.error || "Unable to rename project");
    await loadRemoteProjects();
    select.value = projectId;
    updateProjectActions();
  } catch (error) { alert(error.message || "Unable to rename project"); }
}
async function deleteRemoteProject() {
  const select = document.getElementById("project-select");
  const projectId = select.value;
  if (!projectId) return;
  try {
    const previewRes = await apiFetch("/api/projects/delete-preview", {
      method: "POST", headers: {"Content-Type":"application/json"},
      body: JSON.stringify({projectId})
    });
    const preview = await previewRes.json();
    if (!previewRes.ok || !preview.ok) throw new Error(preview.error || "Unable to review project deletion");
    if (preview.blocker) { alert("This project cannot be deleted yet.\n\n" + preview.blocker); return; }
    const warning = "Delete project ‘" + preview.name + "’?\n\nThis also deletes " + preview.chatCount +
      " chat(s) and " + preview.automationCount + " related automation(s). This cannot be undone.";
    if (!confirm(warning)) return;
    const res = await apiFetch("/api/projects/delete", {
      method: "POST", headers: {"Content-Type":"application/json"},
      body: JSON.stringify({projectId})
    });
    const data = await res.json();
    if (!res.ok || !data.ok) throw new Error(data.error || "Unable to delete project");
    await loadRemoteProjects();
    await loadRemoteSessions();
    await Promise.all([loadRemoteModels(), loadChatHistory(), loadFiles(), refreshBridgeStatus()]);
    alert("Project deleted. " + (data.deletedChats || 0) + " chat(s) and " + (data.deletedAutomations || 0) + " automation(s) removed.");
  } catch (error) { alert(error.message || "Unable to delete project"); }
}
async function createRemoteChat() {
  const title = prompt("Name this AndroidHarness chat:", "New remote chat");
  if (title === null) return;
  const projectId = document.getElementById("project-select").value;
  try {
    const res = await apiFetch("/api/chat/new", {
      method: "POST",
      headers: {"Content-Type":"application/json"},
      body: JSON.stringify({title, projectId})
    });
    const data = await res.json();
    if (!res.ok || !data.ok) throw new Error(data.error || "Unable to create chat");
    resetRemoteChatForSession(data.sessionId);
    await loadRemoteSessions();
    const select = document.getElementById("session-select");
    if (select) select.value = data.sessionId;
    await Promise.all([loadRemoteModels(), loadChatHistory(), loadFiles()]);
    await refreshBridgeStatus();
  } catch (error) {
    alert(error.message || "Unable to create chat");
  }
}

async function loadRemoteSessions() {
  const select = document.getElementById("session-select");
  if (!select || !sessionToken) return;
  try {
    const res = await apiFetch("/api/sessions");
    const data = await res.json();
    if (!res.ok || !Array.isArray(data.sessions)) throw new Error(data.error || "Unable to load chats");
    const previous = bridgeSessionId;
    select.replaceChildren();
    data.sessions.forEach(session => {
      const option = document.createElement("option");
      option.value = session.id;
      const title = (session.title || "Untitled chat").trim();
      option.textContent = title.length > 42 ? title.slice(0, 39) + "…" : title;
      option.title = title;
      select.appendChild(option);
    });
    if (previous && data.sessions.some(session => session.id === previous)) select.value = previous;
    else if (data.sessions.length) {
      select.value = data.sessions[0].id;
      if (select.value !== bridgeSessionId) await switchRemoteSession(select.value);
    }
    select.disabled = data.sessions.length === 0;
  } catch (error) {
    select.replaceChildren();
    const option = document.createElement("option");
    option.value = "";
    option.textContent = "Chats unavailable";
    select.appendChild(option);
  }
}
async function switchRemoteSession(sessionId) {
  if (!sessionId || sessionId === bridgeSessionId) return;
  resetRemoteChatForSession(sessionId);
  lastTerminalSnapshot = "";
  const buildCommands = document.getElementById("buildtest-commands");
  if (buildCommands) delete buildCommands.dataset.signature;
  await Promise.all([loadRemoteModels(), loadChatHistory(), refreshRemoteTerminal(), refreshRemoteBuildTest()]);
  await refreshBridgeStatus();
  await loadFiles();
}

async function refreshBridgeStatus() {
  const label = document.getElementById("status-meta");
  if (!label || !sessionToken || bridgeRefreshInFlight) return;
  bridgeRefreshInFlight = true;
  try {
    const statusUrl = "/api/chat/status" + (bridgeSessionId ? "?sessionId=" + encodeURIComponent(bridgeSessionId) : "");
    const res = await apiFetch(statusUrl);
    const data = await res.json();
    if (!res.ok || !data.ok) throw new Error(data.error || "Status unavailable");
    bridgeAssistantBusy = !!data.busy;
    if (data.sessionId && data.sessionId !== bridgeSessionId) {
      resetRemoteChatForSession(data.sessionId);
    }
    renderExecutionStatus(data);
    renderPendingControls(data);
    const sendButton = document.querySelector(".chat-input button:last-child");
    if (sendButton && !chatRequestInFlight) sendButton.disabled = bridgeAssistantBusy;
    const activeTitle = data.sessionTitle || "Active chat";
    const modelTag = document.getElementById("model-tag");
    if (modelTag) modelTag.textContent = (data.provider || "Provider unavailable") + " · " + (data.model || "Model unavailable");
    await loadRemoteModels();
    label.textContent = bridgeAssistantBusy
      ? "AndroidHarness · " + activeTitle + " · Assistant responding"
      : "AndroidHarness · " + activeTitle + " · " + (data.permissionMode || "Connected");
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
    if (!bridgeSessionId) return;
    const res = await apiFetch("/api/chat/history?sessionId=" + encodeURIComponent(bridgeSessionId));
    if (!res.ok) return;
    const data = await res.json();
    if (data.sessionId && data.sessionId !== bridgeSessionId) return;
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
      body: JSON.stringify({ sessionId: bridgeSessionId, prompt: text, messages: [...chatHistory, { role: "user", content: text }] })
    });
    const data = await res.json();
    if (!res.ok || data.ok === false) {
      a.textContent = data.error || data.reply || ("Request failed (HTTP " + res.status + ")");
    } else {
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
document.getElementById("project-select").addEventListener("change", updateProjectActions);
document.getElementById("project-select").addEventListener("change", loadRemoteAutomations);
loadFiles();
loadChatHistory();
loadRemoteAutomations();
refreshRemoteTerminal();
refreshRemoteBuildTest();
refreshBridgeStatus().then(async () => {
  await Promise.all([loadRemoteSessions(), loadRemoteProjects()]);
});
setInterval(refreshBridgeStatus, 5000);
setInterval(refreshRemoteTerminal, 1800);
setInterval(refreshRemoteBuildTest, 2500);
setInterval(loadRemoteAutomations, 7000);
</script></body></html>"""
    }
}
