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
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
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
        val url = "http://$ip:$activePort"
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

        private val executor = Executors.newCachedThreadPool()

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
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) break
                    if (header.lowercase().startsWith("content-length:")) {
                        contentLength = header.substringAfter(":").trim().toIntOrNull() ?: 0
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

                when {
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
                        if (!target.path.startsWith(workspaceDir.canonicalPath) || !target.isFile) {
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
                        if (relPath.isEmpty() || !target.path.startsWith(workspaceDir.canonicalPath)) {
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
                        val model = payload.optString("model").ifBlank { HarnessProvider.DEFAULT_MODEL }
                        if (prompt.isEmpty()) {
                            writeHttpResponse(sock, 400, "application/json; charset=utf-8", """{"error":"Empty prompt"}""")
                            return
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
                                systemPrompt = "You are AndroidHarness running live on the user's Android phone and connected to their laptop browser over local bridge.",
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
                    else -> {
                        writeHttpResponse(sock, 200, "text/html; charset=utf-8", LAPTOP_WEB_HTML)
                    }
                }
            }
        }

        private fun writeHttpResponse(sock: Socket, status: Int, contentType: String, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val statusText = if (status == 200) "OK" else "Error"
            val headers = "HTTP/1.1 $status $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
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
<meta name="viewport" content="width=device-width,initial-scale=1" />
<title>AndroidHarness · Laptop Web Studio</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { margin: 0; font-family: system-ui, -apple-system, sans-serif; background: #0B1120; color: #F8FAFC; display: flex; flex-direction: column; height: 100vh; }
  header { display: flex; align-items: center; justify-content: space-between; padding: 12px 20px; background: #0F172A; border-bottom: 1px solid #1E293B; }
  .brand { font-weight: 700; font-size: 16px; display: flex; align-items: center; gap: 10px; }
  .dot { width: 9px; height: 9px; border-radius: 50%; background: #10B981; display: inline-block; }
  .meta { font-family: monospace; font-size: 12px; color: #94A3B8; }
  main { flex: 1; display: grid; grid-template-columns: 280px 1fr 420px; min-height: 0; }
  .panel { border-right: 1px solid #1E293B; display: flex; flex-direction: column; min-height: 0; }
  .panel-header { padding: 10px 14px; font-size: 12px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.05em; color: #94A3B8; border-bottom: 1px solid #1E293B; display: flex; justify-content: space-between; align-items: center; }
  .file-list { flex: 1; overflow-y: auto; padding: 8px; }
  .file-item { padding: 8px 10px; border-radius: 6px; cursor: pointer; font-family: monospace; font-size: 13px; color: #CBD5E1; }
  .file-item:hover, .file-item.active { background: #1E293B; color: #38BDF8; }
  .editor-wrap { flex: 1; display: flex; flex-direction: column; min-height: 0; }
  textarea#editor { flex: 1; width: 100%; background: #0B1120; color: #E2E8F0; border: none; padding: 16px; font-family: monospace; font-size: 13px; line-height: 1.5; resize: none; outline: none; }
  .chat-messages { flex: 1; overflow-y: auto; padding: 14px; display: flex; flex-direction: column; gap: 12px; }
  .msg { padding: 12px 14px; border-radius: 8px; font-size: 14px; line-height: 1.5; white-space: pre-wrap; }
  .msg.user { background: #1E293B; align-self: flex-end; max-width: 90%; }
  .msg.ai { background: #0F172A; border: 1px solid #1E293B; align-self: flex-start; width: 100%; }
  .chat-input { display: flex; gap: 8px; padding: 12px; border-top: 1px solid #1E293B; background: #0F172A; }
  input, button { font-family: inherit; font-size: 13px; border-radius: 6px; }
  input { flex: 1; background: #1E293B; color: #F8FAFC; border: 1px solid #334155; padding: 9px 12px; outline: none; }
  button { background: #2563EB; color: #FFF; border: none; padding: 8px 14px; font-weight: 600; cursor: pointer; }
  button:hover { background: #1D4ED8; }
</style>
</head>
<body>
<header>
  <div class="brand"><span class="dot"></span> AndroidHarness Laptop Studio</div>
  <div class="meta" id="status-meta">Connected to phone · Multi-Gateway Smart Routing Active</div>
</header>
<main>
  <section class="panel">
    <div class="panel-header"><span>Phone Workspace</span><button onclick="createNewFile()">+ File</button></div>
    <div class="file-list" id="file-list"></div>
  </section>
  <section class="panel editor-wrap">
    <div class="panel-header"><span id="current-file">Select or create a workspace file</span><button onclick="saveCurrentFile()">Save to Phone</button></div>
    <textarea id="editor" placeholder="Select a file on the left or ask the AI assistant on the right to generate code..."></textarea>
  </section>
  <section class="panel" style="border-right:none">
    <div class="panel-header"><span>Live Phone AI Agent</span><span id="model-tag">kilo-auto/free</span></div>
    <div class="chat-messages" id="chat-box">
      <div class="msg ai">Connected directly to your Android phone. Send any coding task or question below!</div>
    </div>
    <div class="chat-input">
      <input id="prompt-input" placeholder="Ask your phone AI to build or edit..." onkeydown="if(event.key==='Enter')sendPrompt()" />
      <button onclick="sendPrompt()">Send</button>
    </div>
  </section>
</main>
<script>
let activeFilePath = '';
async function loadFiles() {
  const res = await fetch('/api/files');
  const data = await res.json();
  const list = document.getElementById('file-list');
  list.innerHTML = '';
  if (!data.files || data.files.length === 0) {
    list.innerHTML = '<div style="padding:10px;color:#64748B;font-size:12px">No files yet. Click + File to create one.</div>';
    return;
  }
  data.files.forEach(f => {
    const div = document.createElement('div');
    div.className = 'file-item' + (f.path === activeFilePath ? ' active' : '');
    div.textContent = f.path + ' (' + f.size + 'B)';
    div.onclick = () => openFile(f.path);
    list.appendChild(div);
  });
}
async function openFile(path) {
  activeFilePath = path;
  document.getElementById('current-file').textContent = path;
  const res = await fetch('/api/file?path=' + encodeURIComponent(path));
  const data = await res.json();
  document.getElementById('editor').value = data.content || '';
  loadFiles();
}
async function createNewFile() {
  const name = prompt('Enter file name (e.g. index.html):', 'index.html');
  if (!name) return;
  activeFilePath = name;
  document.getElementById('current-file').textContent = name;
  document.getElementById('editor').value = '<!doctype html>\\n<html>\\n<body>\\n  <h1>Hello from AndroidHarness</h1>\\n</body>\\n</html>';
  await saveCurrentFile();
}
async function saveCurrentFile() {
  if (!activeFilePath) { activeFilePath = 'index.html'; document.getElementById('current-file').textContent = activeFilePath; }
  await fetch('/api/file', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({ path: activeFilePath, content: document.getElementById('editor').value })
  });
  loadFiles();
}
async function sendPrompt() {
  const input = document.getElementById('prompt-input');
  const text = input.value.trim();
  if (!text) return;
  input.value = '';
  const box = document.getElementById('chat-box');
  const u = document.createElement('div');
  u.className = 'msg user';
  u.textContent = text;
  box.appendChild(u);
  const a = document.createElement('div');
  a.className = 'msg ai';
  a.textContent = 'Working on it...';
  box.appendChild(a);
  box.scrollTop = box.scrollHeight;
  try {
    const res = await fetch('/api/chat', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({ prompt: text })
    });
    const data = await res.json();
    document.getElementById('model-tag').textContent = data.model || 'kilo-auto/free';
    a.textContent = data.reply || 'Done.';
  } catch (e) {
    a.textContent = 'Error communicating with phone: ' + e.message;
  }
  box.scrollTop = box.scrollHeight;
}
loadFiles();
</script>
</body>
</html>"""
    }
}
