package com.androidharness.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.llm.HarnessProvider
import com.androidharness.app.llm.RequestOptions
import com.androidharness.app.llm.SmartAutoRouter
import com.androidharness.app.llm.StreamEvent
import com.androidharness.app.qrcode.QrBitmapGenerator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runCatching
import kotlinx.coroutines.flow.first
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
    private lateinit var qrImage: ImageView
    private lateinit var shortUrlText: TextView
    private lateinit var connectCodeText: TextView
    private lateinit var usbCopyButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_laptop_connection)

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        statusText = findViewById(R.id.laptop_status)
        addressText = findViewById(R.id.laptop_ip)
        enableButton = findViewById(R.id.laptop_enable_button)
        copyButton = findViewById(R.id.laptop_copy_button)
        qrImage = findViewById(R.id.laptop_qr)
        shortUrlText = findViewById(R.id.laptop_ip)
        connectCodeText = findViewById(R.id.laptop_connect_code)
        usbCopyButton = findViewById(R.id.laptop_usb_copy)

        ensureServerRunning(applicationContext)

        copyButton.setOnClickListener {
            val address = addressText.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("laptop_address", address))
            Toast.makeText(this, R.string.laptop_copied, Toast.LENGTH_SHORT).show()
        }

        usbCopyButton.setOnClickListener {
            val command = "adb reverse tcp:$activePort tcp:$activePort"
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("usb_bridge_command", command))
            Toast.makeText(this, R.string.laptop_usb_copied, Toast.LENGTH_SHORT).show()
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
        advertiseService(applicationContext)
        val detected = detectBestIpAddress()
        if (detected != null) {
            activateBridgeUi(detected.first)
        }
    }

    override fun onPause() {
        super.onPause()
        stopAdvertising(applicationContext)
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
        val tokenUrl = "http://$ip:$activePort/?token=$bridgeToken"
        val code = currentConnectCode()
        statusText.text = getString(R.string.laptop_status_tethering_on)
        statusText.setTextColor(Color.parseColor("#15803D"))
        isTetheringEnabled = true
        enableButton.text = getString(R.string.laptop_disable_tethering)
        copyButton.isEnabled = true
        // Short URL only: the long token link is never shown or typed.
        // It lives inside the QR code so scanning pairs instantly.
        addressText.text = "http://$ip:$activePort"
        connectCodeText.text = code
        // Zero-typing connect: the laptop or second phone scans this and is
        // instantly paired; no 100-character URL is ever typed by a human.
        runCatching {
            val qrSize = (240 * resources.displayMetrics.density).toInt()
            qrImage.setImageBitmap(QrBitmapGenerator.createQrBitmap(tokenUrl, qrSize))
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

    override fun onDestroy() {
        stopAdvertising(applicationContext)
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var serverSocket: ServerSocket? = null

        @Volatile
        var activePort: Int = 8080
            private set

        private val executor = Executors.newCachedThreadPool()

        // A per-installation bridge secret. The paired URL is shown only on the phone.
        private val bridgeToken: String by lazy {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }

        /** Short connect code: rotating, shown on the phone, typed on the laptop instead of the token. */
        @Volatile private var pairingCode: String = ""
        @Volatile private var pairingExpiresAt: Long = 0L

        @Synchronized
        fun currentConnectCode(): String {
            val now = System.currentTimeMillis()
            if (pairingCode.isEmpty() || now >= pairingExpiresAt) {
                pairingCode = (100000 + SecureRandom().nextInt(900000)).toString()
                pairingExpiresAt = now + 15 * 60_000L
            }
            return pairingCode
        }

        /** Cached app context so request handlers can read theme/settings. */
        @Volatile private var appContextRef: Context? = null

        /** mDNS announcement so the laptop can discover this phone as an mDNS "GOD-AI" service. */
        @Volatile private var nsdManager: NsdManager? = null
        @Volatile private var nsdRegistered = false
        @Volatile private var advertisedPort = 0

        private val nsdListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) { nsdRegistered = true }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { nsdRegistered = false }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) { nsdRegistered = false }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { nsdRegistered = false }
        }

        @Synchronized
        fun advertiseService(context: Context) {
            if (nsdRegistered && advertisedPort == activePort) return
            val socketPort = activePort
            runCatching {
                val manager = context.getSystemService(NsdManager::class.java) ?: return
                if (nsdRegistered && nsdManager != null) {
                    runCatching { manager.unregisterService(nsdListener) }
                    nsdRegistered = false
                }
                val serviceInfo = NsdServiceInfo().apply {
                    serviceName = "GOD-AI Virtual Phone"
                    serviceType = "_god-ai._tcp"
                    port = socketPort
                }
                manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, nsdListener)
                nsdManager = manager
                advertisedPort = socketPort
            }
        }

        @Synchronized
        fun stopAdvertising(context: Context) {
            val manager = nsdManager ?: return
            if (!nsdRegistered) return
            runCatching { manager.unregisterService(nsdListener) }
        }

        @Synchronized
        fun ensureServerRunning(appContext: Context) {
            if (serverSocket != null && serverSocket?.isClosed == false) {
                appContextRef = appContext.applicationContext
                return
            }
            appContextRef = appContext.applicationContext
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

        private fun handleClient(client: Socket, workspaceDir: File) {
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

                // Pairing: the short code shown on the phone is exchanged for the
                // bridge token, so nobody ever has to type the long link.
                if (path == "/api/pair" && method == "POST") {
                    val payload = JSONObject(body.ifBlank { "{}" })
                    val code = payload.optString("code").trim()
                    if (code == pairingCode && System.currentTimeMillis() < pairingExpiresAt) {
                        val json = JSONObject().put("ok", true).put("token", bridgeToken).put("app", "GOD-AI")
                        writeHttpResponse(sock, 200, "application/json; charset=utf-8", json.toString())
                    } else {
                        // Small delay blunts brute-force code guessing without hurting UX.
                        Thread.sleep(400)
                        writeHttpResponse(sock, 200, "application/json; charset=utf-8", """{"ok":false,"error":"Wrong or expired connect code"}""")
                    }
                    return
                }

                if (path.startsWith("/api/") && authorization != "Bearer $bridgeToken") {
                    writeHttpResponse(sock, 401, "application/json; charset=utf-8", """{"error":"Unauthorized. Open the paired link shown in AndroidHarness."}""")
                    return
                }

                when {
                    method == "GET" && path == "/api/ping" -> {
                        val json = JSONObject()
                            .put("ok", true)
                            .put("app", "GOD-AI")
                            .put("full_name", "AndroidHarness · GOD-AI")
                            .put("model", HarnessProvider.DEFAULT_MODEL)
                            .put("port", activePort)
                            .put("workspace", workspaceDir.absolutePath)
                        writeHttpResponse(sock, 200, "application/json; charset=utf-8", json.toString())
                    }
                    method == "GET" && path == "/api/models" -> {
                        val json = JSONObject()
                            .put("smart_routing", true)
                            .put("active", HarnessProvider.DEFAULT_MODEL)
                        val models = JSONArray()
                        HarnessProvider.pooledModels.forEach { entry ->
                            val item = JSONObject().put("model", entry.id)
                            entry.note?.let { item.put("note", it) }
                            entry.free?.let { item.put("free", it) }
                            models.put(item)
                        }
                        json.put("models", models)
                        writeHttpResponse(sock, 200, "application/json; charset=utf-8", json.toString())
                    }
                    method == "GET" && path == "/api/theme" -> {
                        // Mirror the phone's actual GOD-AI theme so the browser is a
                        // 1:1 face of the app, not a generic dark website.
                        val saved = runBlocking {
                            runCatching {
                                appContextRef?.let { appContext ->
                                    (appContext as? HarnessApp)?.container?.settings?.settings?.first()
                                }
                            }.getOrNull()
                        }
                        writeHttpResponse(sock, 200, "application/json; charset=utf-8",
                            jsonTheme(saved?.themeMode, saved?.colorPalette).toString())
                    }
                    method == "GET" && path == "/api/files" -> {
                        val files = JSONArray()
                        workspaceDir.walkTopDown().maxDepth(4).forEach { file ->
                            if (file.isFile) {
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
                        if (prompt.isEmpty()) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Empty prompt"}""")
                            return
                        }
                        val reply = StringBuilder()
                        val thoughts = StringBuilder()
                        var errorMsg: String? = null
                        var activeModel: String? = null
                        runBlocking {
                            // Instant smart routing: the bridge picks the best free
                            // model for this message (vision/code/quick) on the fly.
                            val cfg = smartRoutedConfig(prompt,
                                payload.optString("model").takeIf { it.isNotBlank() })
                            activeModel = cfg.model
                            runCatching {
                                HarnessProvider.create().streamChat(
                                    config = cfg,
                                    apiKey = HarnessProvider.KEYLESS,
                                    systemPrompt = "You are GOD-AI running live on the user's Android phone, connected to their browser over the local bridge. Answer directly and helpfully.",
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
                            }.onFailure { errorMsg = errorMsg ?: it.message }
                        }
                        val res = JSONObject()
                            .put("ok", errorMsg == null || reply.isNotEmpty())
                            .put("model", activeModel ?: HarnessProvider.DEFAULT_MODEL)
                            .put("thinking", thoughts.toString())
                            .put("reply", if (reply.isNotEmpty()) reply.toString() else (errorMsg ?: "No response"))
                        writeHttpResponse(sock, 200, "application/json; charset=utf-8", res.toString())
                    }
                    method == "POST" && path == "/api/chat/stream" -> {
                        val payload = JSONObject(body.ifBlank { "{}" })
                        val prompt = payload.optString("prompt").trim()
                        if (prompt.isEmpty()) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Empty prompt"}""")
                            return
                        }
                        // Server-sent events: the browser chat streams token by
                        // token, exactly like GOD-AI's native chat does.
                        writeSseHeaders(sock)
                        var failure: String? = null
                        runBlocking {
                            runCatching {
                                val cfg = smartRoutedConfig(prompt,
                                    payload.optString("model").takeIf { it.isNotBlank() })
                                sseSend(sock, "model", JSONObject().put("model", cfg.model).toString())
                                HarnessProvider.create().streamChat(
                                    config = cfg,
                                    apiKey = HarnessProvider.KEYLESS,
                                    systemPrompt = "You are GOD-AI running live on the user's Android phone, connected to their browser over the local bridge. Answer directly and helpfully.",
                                    messages = listOf(ChatMessage(role = Role.USER, text = prompt)),
                                    tools = emptyList(),
                                    options = RequestOptions(),
                                ).collect { event ->
                                    when (event) {
                                        is StreamEvent.TextDelta -> if (event.text.isNotEmpty()) {
                                            sseSend(sock, "delta", JSONObject().put("text", event.text).toString())
                                        }
                                        is StreamEvent.ThinkingDelta -> if (event.text.isNotEmpty()) {
                                            sseSend(sock, "thinking", JSONObject().put("text", event.text).toString())
                                        }
                                        is StreamEvent.ProviderChanged ->
                                            sseSend(sock, "model", JSONObject().put("model", event.config.model).toString())
                                        is StreamEvent.Failure ->
                                            sseSend(sock, "error", JSONObject().put("message", event.message).toString())
                                        else -> {}
                                    }
                                }
                            }.onFailure { failure = it.message }
                        }
                        sseSend(sock, "done", JSONObject().put("message", failure).toString())
                    }
                    else -> {
                        writeHttpResponse(sock, 200, "text/html; charset=utf-8", LAPTOP_WEB_HTML)
                    }
                }
            }
        }

        /** Best free model for the given prompt via instant smart auto-switching. */
        private fun smartRoutedConfig(prompt: String, requestedModel: String?): com.androidharness.app.llm.ProviderConfig {
            requestedModel?.let { return HarnessProvider.config.copy(model = it) }
            return SmartAutoRouter.promptRoute(
                prompt = prompt,
                turnHasImages = false,
                current = HarnessProvider.config,
                catalogs = mapOf(HarnessProvider.ID to HarnessProvider.pooledModels),
            ).config
        }

        private fun writeSseHeaders(sock: Socket) {
            val out = sock.getOutputStream()
            val headers = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n"
            out.write(headers.toByteArray(Charsets.UTF_8))
            out.flush()
        }

        private fun sseSend(sock: Socket, event: String, json: String) {
            val out = sock.getOutputStream()
            out.write("event: ".toByteArray(Charsets.UTF_8))
            out.write(event.toByteArray(Charsets.UTF_8))
            out.write("\r\ndata: ".toByteArray(Charsets.UTF_8))
            out.write(json.toByteArray(Charsets.UTF_8))
            out.write("\r\n\r\n".toByteArray(Charsets.UTF_8))
            out.flush()
        }

        /** Live theme data for the browser mirror of GOD-AI's native UI. */
        private fun jsonTheme(
            themeMode: com.androidharness.app.data.ThemeMode?,
            palette: com.androidharness.app.data.ThemePalette?,
        ): JSONObject {
            val mode = themeMode ?: com.androidharness.app.data.ThemeMode.SYSTEM
            return JSONObject()
                .put("dark", mode == com.androidharness.app.data.ThemeMode.DARK || mode == com.androidharness.app.data.ThemeMode.AMOLED)
                .put("system", mode == com.androidharness.app.data.ThemeMode.SYSTEM)
                .put("amoled", mode == com.androidharness.app.data.ThemeMode.AMOLED)
                .put("palette", (palette ?: com.androidharness.app.data.ThemePalette.BLUE).name)
        }

        private fun writeHttpResponse(sock: Socket, status: Int, contentType: String, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val statusText = if (status == 200) "OK" else "Error"
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

        private const val LAPTOP_WEB_HTML = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover" />
<title>GOD-AI</title>
<style>
:root {
  --md-primary:#0B57D0; --md-on-primary:#FFFFFF; --md-primary-container:#D4E3FF; --md-on-primary-container:#001A41;
  --md-background:#F9F9FF; --md-on-background:#1A1B20; --md-surface:#FAFAFC; --md-on-surface:#191C20;
  --md-surface-container:#EDEEF4; --md-surface-container-low:#F3F4FA; --md-surface-container-high:#E7E9EF;
  --md-surface-container-highest:#E2E2E9; --md-on-surface-variant:#44474E; --md-outline:#75777F;
  --md-outline-variant:#C4C6D0; --md-secondary-container:#D9E2F9; --md-on-secondary-container:#121C2B;
  color-scheme: light dark;
}
* { box-sizing: border-box; }
html, body { height: 100%; }
body { margin:0; font-family: 'Google Sans Text','Roboto',system-ui,-apple-system,sans-serif;
  background: var(--md-background); color: var(--md-on-background); display:flex; flex-direction:column; height:100%; overflow:hidden; }
/* Top app bar: identical structure to the native GOD-AI header */
.app-bar { display:flex; align-items:center; gap:4px; padding:8px 8px 8px 12px; background:var(--md-surface);
  border-bottom:1px solid var(--md-outline-variant); min-height:56px; }
.app-bar .avatar { width:34px; height:34px; border-radius:17px; background:var(--md-primary-container);
  color:var(--md-on-primary-container); display:flex; align-items:center; justify-content:center; font-weight:700; font-size:15px; }
.app-bar .titles { flex:1; min-width:0; }
.app-bar .title { font-weight:500; font-size:16px; color:var(--md-on-surface); }
.app-bar .subtitle { font-size:11px; color:var(--md-on-surface-variant); display:flex; gap:6px; align-items:center; }
.chip { padding:2px 8px; border-radius:8px; background:var(--md-secondary-container); color:var(--md-on-secondary-container);
  font-size:10.5px; font-weight:600; letter-spacing:.02em; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; max-width:220px; }
.icon-btn { width:40px; height:40px; border-radius:20px; border:none; background:transparent; color:var(--md-on-surface-variant);
  font-size:18px; cursor:pointer; display:flex; align-items:center; justify-content:center; }
.icon-btn:hover { background:var(--md-surface-container); }
/* Desktop: chat + workspace side by side, like the native split layout */
main { flex:1; display:flex; min-height:0; }
#godai-chat-pane { flex:1; display:flex; flex-direction:column; min-width:0; min-height:0; }
#workspace-pane { width:360px; border-left:1px solid var(--md-outline-variant); display:flex; flex-direction:column; background:var(--md-surface); }
.ws-header { padding:10px 14px; font-size:11px; font-weight:600; letter-spacing:.08em; text-transform:uppercase;
  color:var(--md-on-surface-variant); border-bottom:1px solid var(--md-outline-variant); display:flex; justify-content:space-between; align-items:center; }
#file-list { flex:0 0 34%; overflow:auto; border-bottom:1px solid var(--md-outline-variant); padding:6px; }
.file-item { padding:8px 10px; border-radius:10px; cursor:pointer; font-family: 'Roboto Mono',monospace; font-size:12.5px; color:var(--md-on-surface-variant); }
.file-item:hover { background:var(--md-surface-container); }
.file-item.active { background:var(--md-secondary-container); color:var(--md-on-secondary-container); }
#editor { flex:1; width:100%; border:none; resize:none; outline:none; padding:12px; font-family:'Roboto Mono',monospace; font-size:12.5px;
  background:var(--md-surface); color:var(--md-on-surface); }
/* Messages: same bubble anatomy as the native app (user right, GOD-AI left) */
.messages { flex:1; overflow-y:auto; padding:16px 16px 8px; display:flex; flex-direction:column; gap:8px; scroll-behavior:smooth; }
.bubble { max-width:min(78%,720px); padding:10px 16px; border-radius:20px; font-size:14px; line-height:1.5; white-space:pre-wrap; word-wrap:break-word; }
.bubble.user { align-self:flex-end; background:var(--md-primary-container); color:var(--md-on-primary-container);
  border-bottom-right-radius:6px; }
.bubble.ai { align-self:flex-start; background:var(--md-surface-container-low); color:var(--md-on-surface);
  border-bottom-left-radius:6px; }
.bubble.ai code { background:var(--md-surface-container-highest); border-radius:6px; padding:1px 6px; font-family:'Roboto Mono',monospace; font-size:12.5px; }
.bubble.ai pre { background:var(--md-surface-container); color:var(--md-on-surface); border-radius:12px; padding:12px;
  overflow-x:auto; font-family:'Roboto Mono',monospace; font-size:12.5px; line-height:1.45; margin:8px 0; }
.bubble.ai a { color:var(--md-primary); }
.route-chip { align-self:flex-start; font-size:10.5px; color:var(--md-on-surface-variant); background:transparent;
  border:1px solid var(--md-outline-variant); border-radius:8px; padding:2px 8px; margin-left:12px; }
.think { align-self:flex-start; max-width:min(78%,720px); font-size:12px; color:var(--md-on-surface-variant); }
.think summary { cursor:pointer; padding:4px 12px; border-radius:12px; background:var(--md-surface-container); }
.typing { align-self:flex-start; display:flex; gap:4px; padding:14px 16px; border-radius:20px; }
.typing span { width:6px; height:6px; border-radius:3px; background:var(--md-primary); animation:pulse 1.2s infinite ease-in-out; }
.typing span:nth-child(2){ animation-delay:.15s } .typing span:nth-child(3){ animation-delay:.3s }
@keyframes pulse { 0%,60%,100%{ opacity:.25; transform:translateY(0) } 30%{ opacity:1; transform:translateY(-3px) } }
/* Composer: pill input + circular send FAB, mirrors MessageComposer */
.composer { display:flex; align-items:flex-end; gap:8px; padding:8px 10px calc(10px + env(safe-area-inset-bottom)); background:var(--md-surface); }
.input-pill { flex:1; display:flex; align-items:center; background:var(--md-surface-container-high); border-radius:26px; padding:2px 6px 2px 16px; }
#prompt-input { flex:1; border:none; background:transparent; outline:none; font-size:14.5px; padding:10px 6px 12px 0;
  color:var(--md-on-surface); font-family:inherit; }
#prompt-input::placeholder { color:var(--md-on-surface-variant); }
.mic-btn { width:40px; height:40px; border-radius:20px; border:none; background:transparent; color:var(--md-on-surface-variant);
  font-size:17px; cursor:pointer; }
.send-btn { width:44px; height:44px; border-radius:22px; border:none; background:var(--md-primary); color:var(--md-on-primary);
  font-size:18px; cursor:pointer; display:flex; align-items:center; justify-content:center; }
.send-btn:disabled { background:var(--md-surface-container-highest); color:var(--md-on-surface-variant); cursor:default; }
/* Mobile: bottom tabs */
.bottom-tabs { display:none; border-top:1px solid var(--md-outline-variant); background:var(--md-surface); }
.bottom-tabs button { flex:1; background:none; border:none; padding:12px 0 14px; font-size:12px; color:var(--md-on-surface-variant);
  display:flex; flex-direction:column; align-items:center; gap:2px; cursor:pointer; }
.bottom-tabs button.active { color:var(--md-primary); font-weight:600; }
.bottom-tabs .tab-pri { font-size:18px; }
@media (max-width: 900px) {
  #workspace-pane { display:none; width:auto; }
  .bottom-tabs { display:flex; }
  main.ws-active #workspace-pane { display:flex; }
  main.ws-active #godai-chat-pane { display:none; }
  .messages { padding:12px 10px 6px; }
  .bubble { max-width:86%; }
}
/* Pair gate: asks for the 6-digit code instead of any long URL */
#pair-gate { position:fixed; inset:0; background:var(--md-background); display:flex; align-items:center; justify-content:center; z-index:50; }
.pair-card { width:min(420px,92vw); background:var(--md-surface-container-low); border-radius:28px; padding:28px 24px;
  text-align:center; box-shadow:0 8px 32px rgba(0,0,0,.25); }
.pair-card h1 { font-size:18px; margin:4px 0 2px; font-weight:600; }
.pair-card .tagline { font-size:12.5px; color:var(--md-on-surface-variant); margin-bottom:18px; line-height:1.5; }
.pair-dot { width:52px; height:52px; border-radius:15px; margin:0 auto 2px; background:var(--md-primary); color:#fff;
  font-weight:700; font-size:20px; display:flex; align-items:center; justify-content:center; }
#pair-code { width:190px; margin:0 auto 8px; display:block; font-family:'Roboto Mono',monospace; font-size:22px; letter-spacing:.35em;
  text-align:center; padding:12px 0 12px .35em; border-radius:16px; border:1px solid var(--md-outline-variant);
  background:var(--md-surface); color:var(--md-on-surface); }
#pair-error { min-height:18px; font-size:12px; color:#BA1A1A; margin-bottom:8px; }
.pair-go { border:none; border-radius:22px; background:var(--md-primary); color:var(--md-on-primary); font-size:14px;
  font-weight:600; padding:12px 28px; cursor:pointer; }
</style>
</head>
<body>
<div id="pair-gate">
  <div class="pair-card">
    <div class="pair-dot">G</div>
    <h1>GOD-AI on your phone</h1>
    <div class="tagline">Scan the QR code on GOD-AI to pair instantly,<br/>or type the 6-digit connect code.</div>
    <input id="pair-code" autocomplete="off" inputmode="numeric" maxlength="6" placeholder="______" />
    <div id="pair-error"></div>
    <button class="pair-go" onclick="pairWithCode()">Connect</button>
  </div>
</div>

<header class="app-bar">
  <div class="avatar">G</div>
  <div class="titles">
    <div class="title">GOD-AI</div>
    <div class="subtitle" id="godai-subtitle"><span class="chip" id="model-chip">Smart routed</span></div>
  </div>
  <button class="icon-btn" id="auto-read-btn" onclick="toggleAutoSpeech()" title="Read replies aloud">&#128266;</button>
  <button class="icon-btn" onclick="toggleMobileWorkspace()" title="Workspace">&#128193;</button>
</header>
<main id="godai-main">
  <section id="godai-chat-pane">
    <div class="messages" id="chat-box">
      <div class="bubble ai">GOD-AI is live on your phone. I look the same here as I do on the app — same chat, same workspace, same models, streamed straight from the phone.</div>
    </div>
    <div class="composer">
      <div class="input-pill">
        <input id="prompt-input" placeholder="Message GOD-AI" onkeydown="if(event.key==='Enter')sendPrompt()" />
        <button class="mic-btn" id="voice-btn" onclick="toggleVoice()" title="Voice input">&#127908;</button>
      </div>
      <button class="send-btn" id="send-btn" onclick="sendPrompt()" title="Send">&#10148;</button>
    </div>
  </section>
  <aside id="workspace-pane">
    <div class="ws-header"><span>Workspace</span><button class="icon-btn" onclick="createNewFile()" title="New file">&#65291;</button></div>
    <div id="file-list"></div>
    <div class="ws-header"><span id="current-file">Select a file</span><button class="icon-btn" onclick="saveCurrentFile()" title="Save">&#128190;</button></div>
    <textarea id="editor" spellcheck="false" placeholder="Open a workspace file, or ask GOD-AI to code..." disabled></textarea>
  </aside>
</main>
<nav class="bottom-tabs" id="bottom-tabs">
  <button id="tab-chat" class="active" onclick="switchMobileTab('chat')"><span class="tab-pri">&#128172;</span>GOD-AI</button>
  <button id="tab-ws" onclick="switchMobileTab('ws')"><span class="tab-pri">&#128193;</span>Workspace</button>
</nav>
<script>
const GODAI_THEMES = {"ocean":{"light":{"primary":"#2D628B","surface":"#F7F9FF","onSurface":"#181C20","surfaceContainer":"#EBEEF3","surfaceContainerLow":"#F1F4F9","surfaceContainerLowest":"#FFFFFF","surfaceContainerHigh":"#E6E8EE","surfaceContainerHighest":"#E0E2E8","onSurfaceVariant":"#42474E","primaryContainer":"#CDE5FF","onPrimary":"#FFFFFF","onPrimaryContainer":"#001D32","primaryInverse":"#9ACBFA","outline":"#72787E","outlineVariant":"#C2C7CF","background":"#F7F9FF","onBackground":"#181C20","secondaryContainer":"#D4E4F6","onSecondaryContainer":"#0D1D2A"},"dark":{"primary":"#9ACBFA","surface":"#101418","onSurface":"#E0E2E8","surfaceContainer":"#1C2024","surfaceContainerLow":"#181C20","surfaceContainerLowest":"#0B0F12","surfaceContainerHigh":"#272A2E","surfaceContainerHighest":"#313539","onSurfaceVariant":"#C2C7CF","primaryContainer":"#0A4A72","onPrimary":"#003352","onPrimaryContainer":"#CDE5FF","primaryInverse":"#2D628B","outline":"#8C9198","outlineVariant":"#42474E","background":"#101418","onBackground":"#E0E2E8","secondaryContainer":"#394857","onSecondaryContainer":"#D4E4F6"}},"indigo":{"light":{"primary":"#545A92","surface":"#FBF8FF","onSurface":"#1B1B21","surfaceContainer":"#EFEDF4","surfaceContainerLow":"#F5F2FA","surfaceContainerLowest":"#FFFFFF","surfaceContainerHigh":"#EAE7EF","surfaceContainerHighest":"#E4E1E9","onSurfaceVariant":"#46464F","primaryContainer":"#E0E0FF","onPrimary":"#FFFFFF","onPrimaryContainer":"#0F144B","primaryInverse":"#BDC2FF","outline":"#777680","outlineVariant":"#C7C5D0","background":"#FBF8FF","onBackground":"#1B1B21","secondaryContainer":"#E1E0F9","onSecondaryContainer":"#191A2C"},"dark":{"primary":"#BDC2FF","surface":"#131318","onSurface":"#E4E1E9","surfaceContainer":"#1F1F25","surfaceContainerLow":"#1B1B21","surfaceContainerLowest":"#0E0E13","surfaceContainerHigh":"#2A292F","surfaceContainerHighest":"#34343A","onSurfaceVariant":"#C7C5D0","primaryContainer":"#3D4279","onPrimary":"#262B61","onPrimaryContainer":"#E0E0FF","primaryInverse":"#545A92","outline":"#91909A","outlineVariant":"#46464F","background":"#131318","onBackground":"#E4E1E9","secondaryContainer":"#444559","onSecondaryContainer":"#E1E0F9"}},"lavender":{"light":{"primary":"#69548D","surface":"#FEF7FF","onSurface":"#1D1B20","surfaceContainer":"#F3ECF4","surfaceContainerLow":"#F8F1F9","surfaceContainerLowest":"#FFFFFF","surfaceContainerHigh":"#EDE6EE","surfaceContainerHighest":"#E7E0E8","onSurfaceVariant":"#49454E","primaryContainer":"#EBDCFF","onPrimary":"#FFFFFF","onPrimaryContainer":"#240E45","primaryInverse":"#D4BBFC","outline":"#7A757F","outlineVariant":"#CBC4CF","background":"#FEF7FF","onBackground":"#1D1B20","secondaryContainer":"#EADEF7","onSecondaryContainer":"#1F182A"},"dark":{"primary":"#D4BBFC","surface":"#151218","onSurface":"#E7E0E8","surfaceContainer":"#211F24","surfaceContainerLow":"#1D1B20","surfaceContainerLowest":"#0F0D13","surfaceContainerHigh":"#2C292F","surfaceContainerHighest":"#37343A","onSurfaceVariant":"#CBC4CF","primaryContainer":"#503C74","onPrimary":"#39255B","onPrimaryContainer":"#EBDCFF","primaryInverse":"#69548D","outline":"#948E99","outlineVariant":"#49454E","background":"#151218","onBackground":"#E7E0E8","secondaryContainer":"#4B4358","onSecondaryContainer":"#EADEF7"}},"orchid":{"light":{"primary":"#7E4D7C","surface":"#FFF7FA","onSurface":"#201A1E","surfaceContainer":"#F7EBF1","surfaceContainerLow":"#FDF0F7","surfaceContainerLowest":"#FFFFFF","surfaceContainerHigh":"#F1E5EB","surfaceContainerHighest":"#EBDFE6","onSurfaceVariant":"#4E444B","primaryContainer":"#FFD6F8","onPrimary":"#FFFFFF","onPrimaryContainer":"#330834","primaryInverse":"#EFB4E9","outline":"#7F747C","outlineVariant":"#D1C3CC","background":"#FFF7FA","onBackground":"#201A1E","secondaryContainer":"#F6DBF0","onSecondaryContainer":"#261625"},"dark":{"primary":"#EFB4E9","surface":"#171216","onSurface":"#EBDFE6","surfaceContainer":"#241E22","surfaceContainerLow":"#201A1E","surfaceContainerLowest":"#120D11","surfaceContainerHigh":"#2E282D","surfaceContainerHighest":"#3A3338","onSurfaceVariant":"#D1C3CC","primaryContainer":"#643663","onPrimary":"#4B1F4B","onPrimaryContainer":"#FFD6F8","primaryInverse":"#7E4D7C","outline":"#9A8D96","outlineVariant":"#4E444B","background":"#171216","onBackground":"#EBDFE6","secondaryContainer":"#544151","onSecondaryContainer":"#F6DBF0"}},"rose":{"light":{"primary":"#8B4A62","surface":"#FFF8F8","onSurface":"#22191C","surfaceContainer":"#FAEAED","surfaceContainerLow":"#FFF0F2","surfaceContainerLowest":"#FFFFFF","surfaceContainerHigh":"#F4E4E7","surfaceContainerHighest":"#EFDFE2","onSurfaceVariant":"#514347","primaryContainer":"#FFD9E3","onPrimary":"#FFFFFF","onPrimaryContainer":"#39071E","primaryInverse":"#FFB0CA","outline":"#837377","outlineVariant":"#D5C2C6","background":"#FFF8F8","onBackground":"#22191C","secondaryContainer":"#FFD9E3","onSecondaryContainer":"#2B151D"},"dark":{"primary":"#FFB0CA","surface":"#191114","onSurface":"#EFDFE2","surfaceContainer":"#261D20","surfaceContainerLow":"#22191C","surfaceContainerLowest":"#140C0E","surfaceContainerHigh":"#31282A","surfaceContainerHighest":"#3C3235","onSurfaceVariant":"#D5C2C6","primaryContainer":"#6F334A","onPrimary":"#541D33","onPrimaryContainer":"#FFD9E3","primaryInverse":"#8B4A62","outline":"#9E8C90","outlineVariant":"#514347","background":"#191114","onBackground":"#EFDFE2","secondaryContainer":"#5A3F48","onSecondaryContainer":"#FFD9E3"}},"coral":{"light":{"primary":"#904B3D","surface":"#FFF8F6","onSurface":"#231918","surfaceContainer":"#FCEAE6","surfaceContainerLow":"#FFF0EE","surfaceContainerLowest":"#FFFFFF","surfaceContainerHigh":"#F7E4E1","surfaceContainerHighest":"#F1DFDB","onSurfaceVariant":"#534340","primaryContainer":"#FFDAD3","onPrimary":"#FFFFFF","onPrimaryContainer":"#3A0A03","primaryInverse":"#FFB4A5","outline":"#85736F","outlineVariant":"#D8C2BD","background":"#FFF8F6","onBackground":"#231918","secondaryContainer":"#FFDAD3","onSecondaryContainer":"#2C1511"},"dark":{"primary":"#FFB4A5","surface":"#1A1110","onSurface":"#F1DFDB","surfaceContainer":"#271D1B","surfaceContainerLow":"#231918","surfaceContainerLowest":"#140C0B","surfaceContainerHigh":"#322826","surfaceContainerHighest":"#3D3230","onSurfaceVariant":"#D8C2BD","primaryContainer":"#733428","onPrimary":"#561E14","onPrimaryContainer":"#FFDAD3","primaryInverse":"#904B3D","outline":"#A08C89","outlineVariant":"#534340","background":"#1A1110","onBackground":"#F1DFDB","secondaryContainer":"#5D3F39","onSecondaryContainer":"#FFDAD3"}},"amber":{"light":{"primary":"#805610","surface":"#FFF8F4","onSurface":"#211B13","surfaceContainer":"#F9ECDF","surfaceContainerLow":"#FFF1E5","surfaceContainerLowest":"#FFFFFF","surfaceContainerHigh":"#F3E6DA","surfaceContainerHighest":"#EDE0D4","onSurfaceVariant":"#4F4539","primaryContainer":"#FFDDB4","onPrimary":"#FFFFFF","onPrimaryContainer":"#291800","primaryInverse":"#F5BC6F","outline":"#817567","outlineVariant":"#D3C4B4","background":"#FFF8F4","onBackground":"#211B13","secondaryContainer":"#FBDEBC","onSecondaryContainer":"#271904"},"dark":{"primary":"#F5BC6F","surface":"#18120B","onSurface":"#EDE0D4","surfaceContainer":"#251F17","surfaceContainerLow":"#211B13","surfaceContainerLowest":"#130D07","surfaceContainerHigh":"#302921","surfaceContainerHighest":"#3B342B","onSurfaceVariant":"#D3C4B4","primaryContainer":"#633F00","onPrimary":"#452B00","onPrimaryContainer":"#FFDDB4","primaryInverse":"#805610","outline":"#9C8F80","outlineVariant":"#4F4539","background":"#18120B","onBackground":"#EDE0D4","secondaryContainer":"#56432B","onSecondaryContainer":"#FBDEBC"}},"lime":{"light":{"primary":"#536525","surface":"#FAFAEE","onSurface":"#1B1C15","surfaceContainer":"#EFEFE2","surfaceContainerLow":"#F4F4E8","surfaceContainerLowest":"#FFFFFF","surfaceContainerHigh":"#E9E9DD","surfaceContainerHighest":"#E3E3D7","onSurfaceVariant":"#45483C","primaryContainer":"#D5EC9D","onPrimary":"#FFFFFF","onPrimaryContainer":"#151F00","primaryInverse":"#B9CF83","outline":"#76786B","outlineVariant":"#C6C8B8","background":"#FAFAEE","onBackground":"#1B1C15","secondaryContainer":"#DFE6C5","onSecondaryContainer":"#181E09"},"dark":{"primary":"#B9CF83","surface":"#12140D","onSurface":"#E3E3D7","surfaceContainer":"#1F2019","surfaceContainerLow":"#1B1C15","surfaceContainerLowest":"#0D0F08","surfaceContainerHigh":"#292B23","surfaceContainerHighest":"#34362D","onSurfaceVariant":"#C6C8B8","primaryContainer":"#3B4D0F","onPrimary":"#273500","onPrimaryContainer":"#D5EC9D","primaryInverse":"#536525","outline":"#909284","outlineVariant":"#45483C","background":"#12140D","onBackground":"#E3E3D7","secondaryContainer":"#434A31","onSecondaryContainer":"#DFE6C5"}},"forest":{"light":{"primary":"#316A42","surface":"#F6FBF3","onSurface":"#181D18","surfaceContainer":"#EBEFE7","surfaceContainerLow":"#F0F5ED","surfaceContainerLowest":"#FFFFFF","surfaceContainerHigh":"#E5EAE2","surfaceContainerHighest":"#DFE4DC","onSurfaceVariant":"#414941","primaryContainer":"#B3F1BF","onPrimary":"#FFFFFF","onPrimaryContainer":"#00210C","primaryInverse":"#98D5A4","outline":"#717971","outlineVariant":"#C1C9BF","background":"#F6FBF3","onBackground":"#181D18","secondaryContainer":"#D2E8D3","onSecondaryContainer":"#0E1F12"},"dark":{"primary":"#98D5A4","surface":"#101510","onSurface":"#DFE4DC","surfaceContainer":"#1C211C","surfaceContainerLow":"#181D18","surfaceContainerLowest":"#0B0F0B","surfaceContainerHigh":"#262B26","surfaceContainerHighest":"#313631","onSurfaceVariant":"#C1C9BF","primaryContainer":"#16512C","onPrimary":"#00391A","onPrimaryContainer":"#B3F1BF","primaryInverse":"#316A42","outline":"#8B938A","outlineVariant":"#414941","background":"#101510","onBackground":"#DFE4DC","secondaryContainer":"#394B3C","onSecondaryContainer":"#D2E8D3"}},"teal":{"light":{"primary":"#006A64","surface":"#F4FBF9","onSurface":"#161D1C","surfaceContainer":"#E9EFED","surfaceContainerLow":"#EFF5F3","surfaceContainerLowest":"#FFFFFF","surfaceContainerHigh":"#E3E9E8","surfaceContainerHighest":"#DDE4E2","onSurfaceVariant":"#3F4947","primaryContainer":"#9DF2E9","onPrimary":"#FFFFFF","onPrimaryContainer":"#00201E","primaryInverse":"#81D5CD","outline":"#6F7977","outlineVariant":"#BEC9C7","background":"#F4FBF9","onBackground":"#161D1C","secondaryContainer":"#CCE8E4","onSecondaryContainer":"#051F1D"},"dark":{"primary":"#81D5CD","surface":"#0E1514","onSurface":"#DDE4E2","surfaceContainer":"#1A2120","surfaceContainerLow":"#161D1C","surfaceContainerLowest":"#090F0F","surfaceContainerHigh":"#252B2A","surfaceContainerHighest":"#2F3635","onSurfaceVariant":"#BEC9C7","primaryContainer":"#00504B","onPrimary":"#003734","onPrimaryContainer":"#9DF2E9","primaryInverse":"#006A64","outline":"#899391","outlineVariant":"#3F4947","background":"#0E1514","onBackground":"#DDE4E2","secondaryContainer":"#324B49","onSecondaryContainer":"#CCE8E4"}},"slate":{"light":{"primary":"#555F6A","surface":"#FBF9FA","onSurface":"#1B1C1D","surfaceContainer":"#EFEDEE","surfaceContainerLow":"#F5F3F4","surfaceContainerLowest":"#FFFFFF","surfaceContainerHigh":"#EAE7E9","surfaceContainerHighest":"#E4E2E3","onSurfaceVariant":"#474748","primaryContainer":"#D9E4F0","onPrimary":"#FFFFFF","onPrimaryContainer":"#131D26","primaryInverse":"#BDC8D4","outline":"#777778","outlineVariant":"#C8C6C7","background":"#FBF9FA","onBackground":"#1B1C1D","secondaryContainer":"#DEE3EB","onSecondaryContainer":"#171C22"},"dark":{"primary":"#BDC8D4","surface":"#131314","onSurface":"#E4E2E3","surfaceContainer":"#1F2021","surfaceContainerLow":"#1B1C1D","surfaceContainerLowest":"#0E0E0F","surfaceContainerHigh":"#292A2B","surfaceContainerHighest":"#343536","onSurfaceVariant":"#C8C6C7","primaryContainer":"#3E4852","onPrimary":"#27313B","onPrimaryContainer":"#D9E4F0","primaryInverse":"#555F6A","outline":"#919091","outlineVariant":"#474748","background":"#131314","onBackground":"#E4E2E3","secondaryContainer":"#42474E","onSecondaryContainer":"#DEE3EB"}},"blue":{"light":{"primary":"#0B57D0","surface":"#FAFAFC","onSurface":"#191C20","surfaceContainer":"#EDEEF4","surfaceContainerLow":"#F3F4FA","surfaceContainerLowest":"#FFFFFF","surfaceContainerHigh":"#E7E9EF","surfaceContainerHighest":"#E2E2E9","onSurfaceVariant":"#44474E","primaryContainer":"#D4E3FF","onPrimary":"#FFFFFF","onPrimaryContainer":"#001A41","primaryInverse":"#A8C7FA","outline":"#75777F","outlineVariant":"#C4C6D0","background":"#F9F9FF","onBackground":"#1A1B20","secondaryContainer":"#D9E2F9","onSecondaryContainer":"#121C2B"},"dark":{"primary":"#A8C7FA","surface":"#111318","onSurface":"#E2E2E9","surfaceContainer":"#1D2025","surfaceContainerLow":"#191C21","surfaceContainerLowest":"#0C0E13","surfaceContainerHigh":"#272A30","surfaceContainerHighest":"#32353B","onSurfaceVariant":"#C4C6D0","primaryContainer":"#0B57D0","onPrimary":"#062168","onPrimaryContainer":"#D1E4FF","primaryInverse":"#0B57D0","outline":"#8E9099","outlineVariant":"#44474E","background":"#111318","onBackground":"#E2E2E9","secondaryContainer":"#3C4758","onSecondaryContainer":"#D8E3F9"}}};
var PALETTE_DATA = GODAI_THEMES || {};
var bridgeToken = new URLSearchParams(location.search).get("token") || "";
if (bridgeToken) { sessionStorage.setItem("godAiBridgeToken", bridgeToken); history.replaceState(null, "", location.pathname); }
var sessionToken = sessionStorage.getItem("godAiBridgeToken") || "";
var activeFilePath = "";
var autoVoiceRead = true;
var isRecognizing = false;
var streamingDone = true;

function $(id) { return document.getElementById(id); }
function gate() { $("pair-gate").style.display = sessionToken ? "none" : "flex"; }
async function apiFetch(url, options) {
  options = options || {};
  var headers = new Headers(options.headers || {});
  headers.set("Authorization", "Bearer " + sessionToken);
  return fetch(url, Object.assign({}, options, { headers: headers }));
}
async function pairWithCode() {
  var code = ($("pair-code").value || "").trim();
  $("pair-error").textContent = "";
  if (!code) { $("pair-error").textContent = "Type the 6-digit code shown on the phone."; return; }
  var res = await fetch("/api/pair", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ code: code }) }).catch(function () { return null; });
  if (!res) { $("pair-error").textContent = "Cannot reach the phone."; return; }
  var data = await res.json().catch(function () { return { ok: false }; });
  if (data.ok && data.token) {
    sessionStorage.setItem("godAiBridgeToken", data.token);
    sessionToken = data.token;
    gate();
    initApp();
  } else {
    $("pair-error").textContent = data.error || "Wrong code — check GOD-AI on the phone.";
  }
}
async function applyTheme() {
  try {
    var res = await apiFetch("/api/theme");
    if (!res || !res.ok) return;
    var theme = await res.json();
    var pal = (PALETTE_DATA[(theme.palette || "blue").toLowerCase()] || PALETTE_DATA.blue)[theme.dark ? "dark" : "light"];
    if (!pal) return;
    var root = document.documentElement.style;
    var map = { "--md-primary": pal.primary, "--md-on-primary": pal.onPrimary, "--md-primary-container": pal.primaryContainer,
      "--md-on-primary-container": pal.onPrimaryContainer, "--md-background": pal.background, "--md-on-background": pal.onBackground,
      "--md-surface": pal.surface, "--md-on-surface": pal.onSurface, "--md-surface-container": pal.surfaceContainer,
      "--md-surface-container-low": pal.surfaceContainerLow, "--md-surface-container-high": pal.surfaceContainerHigh,
      "--md-surface-container-highest": pal.surfaceContainerHighest, "--md-on-surface-variant": pal.onSurfaceVariant,
      "--md-outline": pal.outline, "--md-outline-variant": pal.outlineVariant,
      "--md-secondary-container": pal.secondaryContainer, "--md-on-secondary-container": pal.onSecondaryContainer };
    for (var k in map) if (map[k]) root.setProperty(k, map[k]);
  } catch (e) { /* keep default tokens */ }
}
function toggleAutoSpeech() {
  autoVoiceRead = !autoVoiceRead;
  $("auto-read-btn").textContent = autoVoiceRead ? "\u{1F50A}" : "\u{1F507}";
  if (!autoVoiceRead && window.speechSynthesis) window.speechSynthesis.cancel();
}
function toggleMobileWorkspace() {
  var main = $("godai-main");
  if (window.innerWidth > 900) return;
  main.classList.toggle("ws-active");
  $("tab-chat").classList.toggle("active", !main.classList.contains("ws-active"));
  $("tab-ws").classList.toggle("active", main.classList.contains("ws-active"));
}
function switchMobileTab(tab) {
  var main = $("godai-main");
  main.classList.toggle("ws-active", tab === "ws");
  $("tab-chat").classList.toggle("active", tab !== "ws");
  $("tab-ws").classList.toggle("active", tab === "ws");
}
function toggleVoice() {
  var SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!SR) { alert("Speech recognition not supported in this browser — type instead."); return; }
  if (isRecognizing) return;
  var rec = new SR();
  rec.lang = navigator.language || "en-US";
  var vbtn = $("voice-btn");
  vbtn.textContent = "\u25B4";
  isRecognizing = true;
  rec.onresult = function (e) { var t = e.results[0][0].transcript; $("prompt-input").value = t; isRecognizing = false; vbtn.textContent = "\u{1F3A8}"; sendPrompt(); };
  rec.onerror = function () { isRecognizing = false; vbtn.textContent = "\u{1F3A8}"; };
  rec.onend = function () { isRecognizing = false; vbtn.textContent = "\u{1F3A8}"; };
  rec.start();
}
async function loadFiles() {
  try {
    var res = await apiFetch("/api/files");
    if (!res || !res.ok) return;
    var data = await res.json();
    var list = $("file-list");
    list.innerHTML = "";
    if (!data.files || !data.files.length) {
      list.innerHTML = "<div style='padding:10px;color:var(--md-on-surface-variant);font-size:12px'>Workspace is empty. Tap + to create a file.</div>";
      return;
    }
    data.files.forEach(function (f) {
      var div = document.createElement("div");
      div.className = "file-item" + (f.path === activeFilePath ? " active" : "");
      div.textContent = f.path + "  (" + f.size + " B)";
      div.onclick = function () { openFile(f.path); };
      list.appendChild(div);
    });
  } catch (e) {}
}
async function openFile(path) {
  var res = await apiFetch("/api/file?path=" + encodeURIComponent(path));
  if (!res || !res.ok) return;
  var data = await res.json();
  activeFilePath = path;
  $("current-file").textContent = path;
  var ed = $("editor");
  ed.value = data.content || "";
  ed.disabled = false;
  loadFiles();
}
async function createNewFile() {
  var name = prompt("New file name:", "index.html");
  if (!name) return;
  activeFilePath = name;
  $("current-file").textContent = name;
  var ed = $("editor");
  ed.value = "";
  ed.disabled = false;
  await saveCurrentFile();
}
async function saveCurrentFile() {
  if (!activeFilePath) return;
  await apiFetch("/api/file", { method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ path: activeFilePath, content: $("editor").value }) });
  loadFiles();
}
function renderAI(text) {
  // Lightweight GOD-AI markdown: fenced code, inline code, bold/italic, links.
  var out = text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  out = out.replace(/```([a-zA-Z0-9_+-]*)\n([\s\S]*?)```/g, function (m, lang, code) {
    return "<pre><code>" + code.replace(/\n$/, "") + "</code></pre>";
  });
  out = out.replace(/`([^`\n]+)`/g, "<code>" + "$1" + "</code>");
  out = out.replace(/\*\*([^*\n]+)\*\*/g, "<b>" + "$1" + "</b>");
  out = out.replace(/(^|[^*])\*([^*\n]+)\*(?!\*)/g, "$1<i>" + "$2" + "</i>");
  out = out.replace(/(https?:\/\/[^ \n)]+)/g, "<a href='" + "$1" + "' target='_blank' rel='noopener'>" + "$1" + "</a>");
  return out;
}
function bubble(cls, text) {
  var b = document.createElement("div");
  b.className = "bubble " + cls;
  b.innerHTML = cls === "ai" ? renderAI(text) : null;
  if (cls !== "ai") b.textContent = text;
  $("chat-box").appendChild(b);
  $("chat-box").scrollTop = $("chat-box").scrollHeight;
  return b;
}
function speak(text) {
  if (!autoVoiceRead || !window.speechSynthesis) return;
  window.speechSynthesis.cancel();
  var utter = new SpeechSynthesisUtterance(text.replace(/[*#`_]/g, "").slice(0, 450));
  window.speechSynthesis.speak(utter);
}
async function sendPrompt() {
  var input = $("prompt-input");
  var text = (input.value || "").trim();
  if (!text || !streamingDone) return;
  streamingDone = false;
  input.value = "";
  bubble("user", text);
  var ai = bubble("ai", "");
  var thinkEl = null;
  var typing = document.createElement("div");
  typing.className = "typing";
  typing.innerHTML = "<span></span><span></span><span></span>";
  $("chat-box").appendChild(typing);
  $("chat-box").scrollTop = $("chat-box").scrollHeight;
  var raw = "";
  var think = "";
  try {
    var res = await apiFetch("/api/chat/stream", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ prompt: text }) });
    var reader = res.body.getReader();
    var decoder = new TextDecoder();
    var buffer = "";
    for (;;) {
      var r = await reader.read();
      if (r.done) break;
      buffer += decoder.decode(r.value, { stream: true });
      var events = buffer.split("\n\n");
      buffer = events.pop();
      for (var i = 0; i < events.length; i++) {
        var block = events[i];
        var m = block.match(/^event: (\w+)/m);
        var d = block.match(/^data: (.*)$/m);
        if (!m || !d) continue;
        var payload = {};
        try { payload = JSON.parse(d[1]); } catch (e2) { continue; }
        if (m[1] === "model" && payload.model) { $("model-chip").textContent = payload.model; }
        else if (m[1] === "thinking" && payload.text) {
          think += payload.text;
          if (!thinkEl) {
            typing.remove();
            thinkEl = document.createElement("details");
            thinkEl.className = "think";
            thinkEl.innerHTML = "<summary>Thinking</summary><div class='think-body' style='padding:6px 12px;font-style:italic'></div>";
            $("chat-box").appendChild(thinkEl);
          }
          thinkEl.querySelector(".think-body").textContent = think;
          $("chat-box").scrollTop = $("chat-box").scrollHeight;
        }
        else if (m[1] === "delta" && payload.text) {
          raw += payload.text;
          ai.innerHTML = renderAI(raw);
        }
        else if (m[1] === "error" && payload.message) { raw = raw || ""; ai.innerHTML = renderAI(raw + "\n\n" + payload.message); }
      }
    }
  } catch (e) { ai.innerHTML = renderAI(raw || ("Bridge error: " + e.message)); }
  typing.remove();
  var chip = document.createElement("div");
  chip.className = "route-chip";
  chip.textContent = $("model-chip").textContent;
  $("chat-box").appendChild(chip);
  $("chat-box").scrollTop = $("chat-box").scrollHeight;
  if (raw) speak(raw);
  streamingDone = true;
}
function initApp() {
  gate();
  applyTheme();
  loadFiles();
  apiFetch("/api/models").then(function (r) { return r ? r.json() : null; }).then(function (d) {
    if (d && d.models && d.models.length) { $("model-chip").textContent = "Smart routed · " + (d.active || d.models[0].model); }
  }).catch(function () {});
}
initApp();
</script>
</body>
</html>
"""
    }
}
