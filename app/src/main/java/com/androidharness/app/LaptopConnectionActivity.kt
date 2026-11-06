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
import com.androidharness.app.llm.HarnessProvider
import com.androidharness.app.llm.RequestOptions
import com.androidharness.app.llm.StreamEvent
import kotlinx.coroutines.runBlocking
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
        private val chatHistoryLock = Any()
        private val bridgeChatBusy = AtomicBoolean(false)

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
                    executor.execute {
                        while (!socket.isClosed) {
                            try {
                                val client = socket.accept()
                                try {
                                    executor.execute { handleClient(client, workspaceDir) }
                                } catch (_: RejectedExecutionException) {
                                    // Close immediately when the bounded worker queue is full.
                                    runCatching { client.close() }
                                }
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

        private fun isBridgeInternalPath(relativePath: String): Boolean =
            relativePath.split('/', '\\\\').any { segment ->
                segment == ".androidharness-chat-history.json" ||
                    segment.endsWith(".androidharness-chat-history.json.next")
            }

        private fun handleClient(client: Socket, workspaceDir: File) {
            client.use { sock ->
                sock.soTimeout = 60_000
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
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val fullPath = parts[1]
                val path = fullPath.substringBefore("?")
                val query = fullPath.substringAfter("?", "")

                var contentLength = 0
                var contentLengthSeen = false
                var unsupportedTransferEncoding = false
                var authorization: String? = null
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
                    when {
                        header.lowercase().startsWith("content-length:") -> {
                            if (contentLengthSeen) {
                                writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Duplicate Content-Length header"}""")
                                return
                            }
                            contentLengthSeen = true
                            contentLength = header.substringAfter(":").trim().toIntOrNull() ?: -1
                        }
                        header.lowercase().startsWith("transfer-encoding:") ->
                            unsupportedTransferEncoding = true
                        header.lowercase().startsWith("authorization:") ->
                            authorization = header.substringAfter(":").trim()
                    }
                }
                if (!headersComplete) {
                    writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"HTTP headers are incomplete or exceed the 32 KB limit"}""")
                    return
                }

                if (unsupportedTransferEncoding) {
                    writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Transfer-Encoding is not supported by this bridge"}""")
                    return
                }
                if (contentLength < 0 || contentLength > 2 * 1024 * 1024) {
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
                        val files = JSONArray()
                        workspaceDir.walkTopDown().maxDepth(4).forEach { file ->
                            if (file.isFile && file.name != ".androidharness-chat-history.json" &&
                                !file.name.endsWith(".androidharness-chat-history.json.next")) {
                                val rel = file.relativeTo(workspaceDir).path
                                files.put(
                                    JSONObject()
                                        .put("path", rel)
                                        .put("size", file.length())
                                )
                            }
                        }
                        val json = JSONObject().put("files", files)
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
                        if (isBridgeInternalPath(relPath) || !target.path.startsWith(workspaceDir.canonicalPath + File.separator) || !target.isFile) {
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
                        if (relPath.isEmpty() || isBridgeInternalPath(relPath) || !target.path.startsWith(workspaceDir.canonicalPath + File.separator)) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Invalid path"}""")
                        } else {
                            target.parentFile?.mkdirs()
                            target.writeText(content)
                            writeHttpResponse(sock, 200, "application/json; charset=utf-8", """{"ok":true}""")
                        }
                    }
                    method == "GET" && path == "/api/chat/status" -> {
                        val historyFile = File(workspaceDir, ".androidharness-chat-history.json")
                        val messageCount = synchronized(chatHistoryLock) {
                            runCatching {
                                if (historyFile.isFile) JSONArray(historyFile.readText()).length() else 0
                            }.getOrDefault(0)
                        }
                        val status = JSONObject()
                            .put("ok", true)
                            .put("busy", bridgeChatBusy.get())
                            .put("messageCount", messageCount)
                        writeHttpResponse(sock, 200, "application/json; charset=utf-8", status.toString())
                    }
                    method == "GET" && path == "/api/chat/history" -> {
                        val historyFile = File(workspaceDir, ".androidharness-chat-history.json")
                        val history = synchronized(chatHistoryLock) {
                            runCatching { if (historyFile.isFile) JSONArray(historyFile.readText()) else JSONArray() }
                                .getOrDefault(JSONArray())
                        }
                        writeHttpResponse(sock, 200, "application/json; charset=utf-8", JSONObject().put("messages", history).toString())
                    }
                    method == "POST" && path == "/api/chat" -> {
                        val payload = JSONObject(body.ifBlank { "{}" })
                        val prompt = payload.optString("prompt").trim()
                        val model = payload.optString("model").ifBlank { HarnessProvider.DEFAULT_MODEL }
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
                            val historyFile = File(workspaceDir, ".androidharness-chat-history.json")
                            val conversation = synchronized(chatHistoryLock) {
                                val history = runCatching {
                                    if (historyFile.isFile) JSONArray(historyFile.readText()) else JSONArray()
                                }.getOrDefault(JSONArray())
                                history.put(JSONObject().put("role", "user").put("content", prompt))
                                while (history.length() > 40) history.remove(0)
                                val temp = File(historyFile.parentFile, historyFile.name + ".next")
                                temp.writeText(history.toString())
                                if (!temp.renameTo(historyFile)) {
                                    temp.delete()
                                    historyFile.writeText(history.toString())
                                }
                                buildList {
                                    for (i in 0 until history.length()) {
                                        val item = history.optJSONObject(i) ?: continue
                                        val text = item.optString("content").take(32_000)
                                        val role = when (item.optString("role").lowercase()) {
                                            "assistant" -> Role.ASSISTANT
                                            "user" -> Role.USER
                                            else -> continue
                                        }
                                        if (text.isNotBlank()) add(ChatMessage(role = role, text = text))
                                    }
                                }
                            }
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
                                    systemPrompt = "You are AndroidHarness running live on the user's Android phone and connected to its paired remote interfaces. Continue the shared conversation naturally.",
                                    messages = conversation,
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
                            val answer = if (reply.isNotEmpty()) reply.toString() else (errorMsg ?: "No response")
                            val responseHistory = synchronized(chatHistoryLock) {
                                val latest = runCatching {
                                    if (historyFile.isFile) JSONArray(historyFile.readText()) else JSONArray()
                                }.getOrDefault(JSONArray())
                                if (reply.isNotEmpty()) {
                                    latest.put(JSONObject().put("role", "assistant").put("content", answer))
                                    while (latest.length() > 40) latest.remove(0)
                                    val temp = File(historyFile.parentFile, historyFile.name + ".next")
                                    temp.writeText(latest.toString())
                                    if (!temp.renameTo(historyFile)) {
                                        temp.delete()
                                        historyFile.writeText(latest.toString())
                                    }
                                }
                                latest
                            }
                            val replyPayload = JSONObject()
                                .put("ok", errorMsg == null || reply.isNotEmpty())
                                .put("model", activeModel)
                                .put("thinking", thoughts.toString())
                                .put("reply", answer)
                                .put("messages", responseHistory)
                            writeHttpResponse(sock, if (replyPayload.optBoolean("ok")) 200 else 502, "application/json; charset=utf-8", replyPayload.toString())
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
