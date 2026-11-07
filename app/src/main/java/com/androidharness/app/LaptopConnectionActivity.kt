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
                    method == "GET" && path == "/api/settings" -> {
                        val app = appContext.applicationContext as? HarnessApp
                        if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                        } else {
                            val result = runCatching {
                                runBlocking {
                                    val settings = app.container.settings.settings.first()
                                    JSONObject().put("ok", true)
                                        .put("runInBackground", settings.runInBackground)
                                        .put("notificationsEnabled", settings.notificationsEnabled)
                                        .put("keepAlive", settings.keepAlive)
                                        .put("liveUpdatesEnabled", settings.liveUpdatesEnabled)
                                        .put("repoMapEnabled", settings.repoMapEnabled)
                                        .put("showLiveCode", settings.showLiveCode)
                                        .put("memoryEnabled", settings.memoryEnabled)
                                        .put("runTipsEnabled", settings.runTipsEnabled)
                                        .put("allowScreenshots", settings.allowScreenshots)
                                        .put("themeMode", settings.themeMode.name)
                                }
                            }
                            if (result.isSuccess) writeHttpResponse(sock, 200, "application/json; charset=utf-8", result.getOrThrow().toString())
                            else writeHttpResponse(sock, 500, "application/json; charset=utf-8",
                                JSONObject().put("ok", false).put("error", result.exceptionOrNull()?.message ?: "Unable to load settings").toString())
                        }
                    }
                    method == "GET" && path == "/api/skills" -> {
                        val app = appContext.applicationContext as? HarnessApp
                        if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                        } else {
                            val result = runCatching {
                                val items = JSONArray().apply {
                                    app.container.skills.list().forEach { skill ->
                                        put(JSONObject().put("name", skill.name).put("description", skill.description)
                                            .put("category", skill.category).put("source", skill.source.name)
                                            .put("enabled", skill.enabled))
                                    }
                                }
                                JSONObject().put("ok", true).put("skills", items)
                            }
                            if (result.isSuccess) writeHttpResponse(sock, 200, "application/json; charset=utf-8", result.getOrThrow().toString())
                            else writeHttpResponse(sock, 500, "application/json; charset=utf-8", JSONObject().put("ok", false).put("error", result.exceptionOrNull()?.message ?: "Unable to load skills").toString())
                        }
                    }
                    method == "GET" && path == "/api/skill" -> {
                        val name = queryValue(query, "name").trim()
                        val app = appContext.applicationContext as? HarnessApp
                        if (name.isBlank()) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"A skill name is required"}""")
                        } else if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                        } else {
                            val result = app.container.skills.view(name).map { it.content.take(65536) }
                            if (result.isSuccess) writeHttpResponse(sock, 200, "application/json; charset=utf-8",
                                JSONObject().put("ok", true).put("name", name).put("content", result.getOrThrow()).toString())
                            else writeHttpResponse(sock, 404, "application/json; charset=utf-8",
                                JSONObject().put("ok", false).put("error", result.exceptionOrNull()?.message ?: "Skill not found").toString())
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
                    method == "POST" && path == "/api/settings/toggle" -> {
                        val payload = try { JSONObject(body.ifBlank { "{}" }) }
                        catch (_: org.json.JSONException) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Malformed JSON request body"}""")
                            return
                        }
                        val key = payload.optString("key").trim()
                        if (!payload.has("enabled") || key !in setOf("runInBackground", "notificationsEnabled", "keepAlive",
                                "liveUpdatesEnabled", "repoMapEnabled", "showLiveCode", "memoryEnabled",
                                "runTipsEnabled", "allowScreenshots")) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Unsupported setting or missing boolean value"}""")
                        } else {
                            val app = appContext.applicationContext as? HarnessApp
                            if (app == null) {
                                writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                            } else {
                                val enabled = payload.optBoolean("enabled")
                                val result = runCatching {
                                    runBlocking {
                                        val settings = app.container.settings
                                        when (key) {
                                            "runInBackground" -> settings.setRunInBackground(enabled)
                                            "notificationsEnabled" -> settings.setNotificationsEnabled(enabled)
                                            "keepAlive" -> settings.setKeepAlive(enabled)
                                            "liveUpdatesEnabled" -> settings.setLiveUpdatesEnabled(enabled)
                                            "repoMapEnabled" -> settings.setRepoMapEnabled(enabled)
                                            "showLiveCode" -> settings.setShowLiveCode(enabled)
                                            "memoryEnabled" -> settings.setMemoryEnabled(enabled)
                                            "runTipsEnabled" -> settings.setRunTipsEnabled(enabled)
                                            "allowScreenshots" -> settings.setAllowScreenshots(enabled)
                                        }
                                    }
                                    JSONObject().put("ok", true).put("key", key).put("enabled", enabled)
                                }
                                if (result.isSuccess) writeHttpResponse(sock, 200, "application/json; charset=utf-8", result.getOrThrow().toString())
                                else writeHttpResponse(sock, 409, "application/json; charset=utf-8",
                                    JSONObject().put("ok", false).put("error", result.exceptionOrNull()?.message ?: "Unable to update setting").toString())
                            }
                        }
                    }
                    method == "POST" && path == "/api/skills/toggle" -> {
                        val payload = try { JSONObject(body.ifBlank { "{}" }) }
                        catch (_: org.json.JSONException) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Malformed JSON request body"}""")
                            return
                        }
                        val name = payload.optString("name").trim()
                        val enabled = payload.optBoolean("enabled")
                        val app = appContext.applicationContext as? HarnessApp
                        if (name.isBlank()) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"A skill name is required"}""")
                        } else if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                        } else {
                            val result = runCatching {
                                check(app.container.skills.list().any { it.name == name }) { "This skill is no longer available." }
                                runBlocking { app.container.settings.setSkillEnabled(name, enabled) }
                                JSONObject().put("ok", true).put("name", name).put("enabled", enabled)
                            }
                            if (result.isSuccess) writeHttpResponse(sock, 200, "application/json; charset=utf-8", result.getOrThrow().toString())
                            else writeHttpResponse(sock, 409, "application/json; charset=utf-8",
                                JSONObject().put("ok", false).put("error", result.exceptionOrNull()?.message ?: "Unable to change skill").toString())
                        }
                    }
                    method == "POST" && path == "/api/skills/delete" -> {
                        val payload = try { JSONObject(body.ifBlank { "{}" }) }
                        catch (_: org.json.JSONException) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Malformed JSON request body"}""")
                            return
                        }
                        val name = payload.optString("name").trim()
                        val app = appContext.applicationContext as? HarnessApp
                        if (name.isBlank()) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"A skill name is required"}""")
                        } else if (app == null) {
                            writeHttpResponse(sock, 503, "application/json; charset=utf-8", """{"error":"AndroidHarness application is unavailable"}""")
                        } else {
                            val result = runCatching {
                                val skill = app.container.skills.list().firstOrNull { it.name == name }
                                    ?: error("This skill is no longer available.")
                                check(skill.source == com.androidharness.app.skills.SkillSource.USER) {
                                    "Only user-created skills can be deleted remotely."
                                }
                                check(app.container.skills.deleteUser(name)) { "Unable to delete this user skill." }
                                JSONObject().put("ok", true).put("name", name)
                            }
                            if (result.isSuccess) writeHttpResponse(sock, 200, "application/json; charset=utf-8", result.getOrThrow().toString())
                            else writeHttpResponse(sock, 409, "application/json; charset=utf-8",
                                JSONObject().put("ok", false).put("error", result.exceptionOrNull()?.message ?: "Unable to delete skill").toString())
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

        private val LAPTOP_WEB_HTML = VirtualShell.html
    }
}
