package com.photosync.uploader

import android.app.AlertDialog
import android.content.ContentValues
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), ServerConnectionControls.Listener, LocalServerInfoView.Listener {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()
    private lateinit var status: TextView
    private lateinit var serverStatus: TextView
    private lateinit var serverUrlInput: EditText
    private lateinit var homeServerAddress: TextView
    private lateinit var sentFilesContainer: LinearLayout
    private lateinit var receivedFilesContainer: LinearLayout
    private lateinit var mainScroll: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("photosync", MODE_PRIVATE) }
    private val deviceIdentity by lazy { DeviceIdentity(this) }
    private val thumbnailCache by lazy { ThumbnailCache(cacheDir) }
    private val localServer by lazy { (application as PhotoSyncApplication).localServer }
    private var socket: WebSocket? = null
    private var backendServerUrl = ""
    private var started = false
    private var connectionEnabled = true
    private var discoveryInProgress = false
    private val activeProgressRows = mutableMapOf<String, View>()
    private val activeReceiveTransfers = mutableMapOf<String, String>()
    private val receiveRefreshRunnable = object : Runnable {
        override fun run() {
            if (!started) return
            refreshLists()
            handler.postDelayed(this, 3000)
        }
    }

    private val picker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) uris.forEach { upload(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.statusText)
        serverStatus = findViewById(R.id.serverStatusText)
        serverUrlInput = findViewById(R.id.serverUrlInput)
        sentFilesContainer = findViewById(R.id.sentFilesContainer)
        receivedFilesContainer = findViewById(R.id.receivedFilesContainer)
        mainScroll = findViewById(R.id.mainScroll)
        homeServerAddress = findViewById(R.id.homeServerAddress)

        val savedServer = prefs.getString("server_url", "")?.trim()?.removeSuffix("/") ?: ""
        backendServerUrl = prefs.getString("backend_server_url", "")?.trim()?.removeSuffix("/") ?: ""
        if (savedServer.isNotBlank() && !isLocalServerUrl(savedServer)) backendServerUrl = savedServer
        serverUrlInput.setText(savedServer)
        applyThemeColor()

        findViewById<Button>(R.id.saveServerButton).setOnClickListener {
            val url = serverUrlInput.text.toString().trim().removeSuffix("/")
            if (url.isBlank()) {
                status.text = "Enter a server URL or use Find Server"
                return@setOnClickListener
            }
            saveAndConnect(url)
        }
        findViewById<Button>(R.id.findServerButton).setOnClickListener { discoverServer() }
        findViewById<View>(R.id.sentCard).setOnClickListener { mainScroll.smoothScrollTo(0, sentFilesContainer.top) }
        findViewById<View>(R.id.receivedCard).setOnClickListener { mainScroll.smoothScrollTo(0, receivedFilesContainer.top) }
        findViewById<Button>(R.id.startEmbeddedButton).setOnClickListener { onEmbeddedStartRequested() }
        findViewById<Button>(R.id.stopEmbeddedButton).setOnClickListener { onEmbeddedStopRequested() }
        findViewById<Button>(R.id.connectServerButton).setOnClickListener { onConnectRequested() }
        findViewById<Button>(R.id.disconnectServerButton).setOnClickListener { onDisconnectRequested() }
        findViewById<Button>(R.id.webPairingButton).setOnClickListener { showWebPairingDialog() }
        findViewById<View>(R.id.sendFilesAction).setOnClickListener { picker.launch("*/*") }
        findViewById<View>(R.id.showPinButton).setOnClickListener { showWebPairingDialog() }
        findViewById<View>(R.id.copyAddressButton).setOnClickListener {
            val address = homeServerAddress.text.toString()
            if (address.isNotBlank() && !address.contains("Waiting")) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PhotoSync server", address))
                Toast.makeText(this, "Server address copied", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<View>(R.id.bottomSendButton).setOnClickListener { picker.launch("*/*") }
        findViewById<View>(R.id.clearHistoryButton).setOnClickListener { refreshLists() }
        refreshHomeServerSummary()
    }

    private fun refreshHomeServerSummary() {
        try {
            val localRunning = localServer.isRunning()
            val embeddedStatus = findViewById<TextView>(R.id.embeddedServerStatus)
            val embeddedAddress = findViewById<TextView>(R.id.embeddedAddressText)
            val otherStatus = findViewById<TextView>(R.id.otherServerStatus)
            val startButton = findViewById<Button>(R.id.startEmbeddedButton)
            val stopButton = findViewById<Button>(R.id.stopEmbeddedButton)
            val connectButton = findViewById<Button>(R.id.connectServerButton)
            val disconnectButton = findViewById<Button>(R.id.disconnectServerButton)
            if (localRunning) {
                val address = localServer.url() ?: "Waiting for network…"
                val pin = localServer.currentPin()
                homeServerAddress.text = address
                embeddedAddress.text = "Web address: $address"
                embeddedStatus.text = "Running • browsers can connect"
                findViewById<TextView>(R.id.showPinButton)?.text = "Pairing PIN: $pin"
                serverStatus.text = "Embedded server connected"
                startButton.isEnabled = false
                stopButton.isEnabled = true
                connectButton.isEnabled = false
                disconnectButton.isEnabled = false
                otherStatus.text = "Disabled while Embedded Server is running"
            } else if (backendServerUrl.isNotBlank()) {
                homeServerAddress.text = backendServerUrl
                embeddedAddress.text = "Web address: unavailable"
                embeddedStatus.text = "Stopped"
                findViewById<TextView>(R.id.showPinButton)?.text = "Pairing PIN: —"
                serverStatus.text = "PC server connected"
                startButton.isEnabled = true
                stopButton.isEnabled = false
                connectButton.isEnabled = true
                disconnectButton.isEnabled = true
                otherStatus.text = "Connected • $backendServerUrl"
            } else {
                homeServerAddress.text = "—"
                embeddedAddress.text = "Web address: unavailable"
                embeddedStatus.text = "Stopped"
                    findViewById<TextView>(R.id.showPinButton)?.text = "Pairing PIN: —"
                serverStatus.text = "No server selected"
                startButton.isEnabled = true
                stopButton.isEnabled = false
                connectButton.isEnabled = true
                disconnectButton.isEnabled = false
                otherStatus.text = "Not connected"
            }
        } catch (_: Throwable) {
            homeServerAddress.text = "—"
            findViewById<TextView>(R.id.showPinButton)?.text = "Pairing PIN: —"
            serverStatus.text = "No server selected"
        }
    }

    private fun showWebPairingDialog() {
        val appServer = localServer
        if (!appServer.isRunning()) {
            AlertDialog.Builder(this)
                .setTitle("Web PIN Pairing")
                .setMessage("Embedded server is stopped. Start it to let browsers connect to this phone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Start Server") { _, _ -> onEmbeddedStartRequested() }
                .show()
            return
        }

        val clients = appServer.webClients()
        val details = buildString {
            append("Web address: ").append(appServer.url() ?: "waiting for network…")
            append("\n\nPairing PIN: ").append(appServer.currentPin())
            append("\n\nConnected browsers: ").append(clients.size)
            clients.forEach { append("\n• ").append(it.ip).append("  (").append(it.id).append(")") }
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("Web PIN Pairing")
            .setMessage(details)
            .setNegativeButton("Close", null)
            .setNeutralButton("Refresh PIN") { _, _ ->
                appServer.refreshPin()
                showWebPairingDialog()
            }

        if (clients.isNotEmpty()) {
            builder.setPositiveButton("Disconnect All") { _, _ ->
                clients.forEach { appServer.disconnectWebClient(it.id) }
                showWebPairingDialog()
            }
        } else {
            builder.setPositiveButton("Stop Server") { _, _ -> onEmbeddedStopRequested() }
        }
        builder.show()
    }

    override fun onStart() {
        super.onStart()
        started = true
        connectionEnabled = true
        applyThemeColor()
        // Server activation is now explicit: the user chooses Embedded or PC server.
        handler.removeCallbacks(receiveRefreshRunnable)
        handler.postDelayed(receiveRefreshRunnable, 3000)
        refreshHomeServerSummary()
    }

    override fun onStop() {
        started = false
        handler.removeCallbacks(receiveRefreshRunnable)
        socket?.close(1000, "App stopped")
        socket = null
        serverStatus.text = "● Server: Disconnected"
        super.onStop()
    }

    override fun onEmbeddedStartRequested() {
        try {
            connectionEnabled = true
            socket?.close(1000, "Embedded server selected")
            socket = null
            backendServerUrl = ""
            prefs.edit().remove("backend_server_url").apply()
            val appServer = localServer
            Thread {
                val startedOk = appServer.start()
                val url = appServer.url()
                runOnUiThread {
                    if (startedOk && !url.isNullOrBlank()) {
                        prefs.edit().putString("server_url", url).apply()
                        serverUrlInput.setText(url)
                        serverStatus.text = "Embedded server connected"
                        status.text = "Embedded server active ✓"
                        refreshHomeServerSummary()
                        handler.postDelayed({
                            if (started && localServer.isRunning()) refreshLists()
                        }, 500)
                    } else {
                        serverStatus.text = "● Local Server: Not running"
                        status.text = "Unable to start embedded server"
                        refreshHomeServerSummary()
                    }
                }
            }.start()
        } catch (t: Throwable) {
            serverStatus.text = "● Local Server: Not running"
            status.text = "Unable to start embedded server"
            refreshHomeServerSummary()
        }
    }

    override fun onEmbeddedStopRequested() {
        try {
            socket?.close(1000, "Embedded server stopped")
            socket = null
            localServer.stop()
            prefs.edit().remove("server_url").remove("backend_server_url").apply()
            backendServerUrl = ""
            serverUrlInput.setText("")
            serverStatus.text = "No server selected"
            status.text = "Embedded server stopped"
            refreshHomeServerSummary()
        } catch (_: Throwable) {
            serverStatus.text = "● Local Server: Stopped"
        }
    }

    override fun onConnectRequested() {
        try {
            connectionEnabled = true
            localServer.stop()
            val url = serverUrlInput.text?.toString()?.trim()?.removeSuffix("/") ?: ""
            if (url.isBlank()) {
                discoverServer()
                return
            }
            saveAndConnect(url)
        } catch (t: Throwable) {
            // Manual connection must never crash the Activity. Keep the URL field
            // isolated from server startup/network failures and report the error.
            serverStatus.text = "● Server: Connection failed"
            status.text = "Invalid or unavailable server URL"
        }
    }

    override fun onDisconnectRequested() {
        connectionEnabled = false
        try { localServer.stop() } catch (_: Throwable) { }
        socket?.close(1000, "User disconnected")
        socket = null
        prefs.edit().remove("server_url").remove("backend_server_url").apply()
        backendServerUrl = ""
        serverUrlInput.setText("")
        serverStatus.text = "● Server: Disconnected"
        status.text = "Disconnected"
    }

    private fun currentServerUrl(): String =
        prefs.getString("server_url", serverUrlInput.text.toString().trim().removeSuffix("/"))
            ?.trim()?.removeSuffix("/") ?: ""

    private fun isLocalServerUrl(url: String): Boolean {
        val normalized = url.trim().removeSuffix("/")
        if (normalized.isBlank()) return false
        return try {
            val parsed = java.net.URI(if (normalized.contains("://")) normalized else "http://$normalized")
            val port = if (parsed.port == -1) 80 else parsed.port
            val localHost = localServer.localIpv4()
            port == 18000 && (
                parsed.host == "localhost" ||
                parsed.host == "127.0.0.1" ||
                parsed.host == "0.0.0.0" ||
                (localHost != null && parsed.host == localHost)
            )
        } catch (_: Exception) { false }
    }

    private fun connectSavedOrDiscover() {
        val saved = currentServerUrl()
        if (saved.isNotBlank() && isLocalServerUrl(saved)) {
            selectEmbeddedServer(saved)
            if (backendServerUrl.isNotBlank()) reconnectSocket(backendServerUrl)
            else status.text = "Android local server connected ✓"
            return
        }
        if (saved.isNotBlank()) {
            reconnectSocket()
            refreshLists()
            status.text = "Using saved server"
            return
        }
        serverStatus.text = "● Server: Searching LAN…"
        discoverServer()
    }

    private fun selectEmbeddedServer(url: String) {
        connectionEnabled = true
        val normalized = url.trim().removeSuffix("/")
        socket?.close(1000, "Embedded server selected")
        socket = null
        prefs.edit().putString("server_url", normalized).apply()
        serverUrlInput.setText(normalized)
        serverStatus.text = "● Local Server: Checking…"
        status.text = "Checking Android local server…"
        Thread {
            val running = try { localServer.isRunning() } catch (_: Throwable) { false }
            runOnUiThread {
                if (!started || !connectionEnabled) return@runOnUiThread
                if (running) {
                    serverStatus.text = "● Local Server: Connected"
                    status.text = "Android local server ready ✓"
                } else {
                    serverStatus.text = "● Local Server: Starting…"
                    status.text = "Waiting for Android local server…"
                }
            }
        }.start()
    }

    private fun applyThemeColor() {
        val raw = prefs.getString("theme_color", "#BDA4FF") ?: "#BDA4FF"
        try {
            val color = Color.parseColor(raw)
            findViewById<View>(R.id.menuButton)?.backgroundTintList = ColorStateList.valueOf(color)
            findViewById<Button>(R.id.findServerButton)?.backgroundTintList = ColorStateList.valueOf(color)
            findViewById<Button>(R.id.saveServerButton)?.backgroundTintList = ColorStateList.valueOf(color)
        } catch (_: Exception) { }
    }

    private fun saveAndConnect(url: String) {
        try {
            val normalized = url.trim().removeSuffix("/")
            if (normalized.isBlank()) return
            connectionEnabled = true
            if (isLocalServerUrl(normalized)) {
                selectEmbeddedServer(normalized)
                if (backendServerUrl.isNotBlank()) reconnectSocket(backendServerUrl)
                else status.text = "Android local server connected ✓"
                return
            }
            backendServerUrl = normalized
            prefs.edit()
                .putString("server_url", normalized)
                .putString("backend_server_url", normalized)
                .apply()
            serverUrlInput.setText(normalized)
            status.text = "Connecting…"
            reconnectSocket(normalized)
            refreshLists()
        } catch (t: Throwable) {
            serverStatus.text = "● Server: Connection failed"
            status.text = "Invalid or unavailable server URL"
        }
    }

    private fun wsUrl(baseUrl: String = currentServerUrl()): String {
        val base = baseUrl.trim().removeSuffix("/")
        val id = URLEncoder.encode(deviceIdentity.id, "UTF-8")
        return when {
            base.startsWith("https://") -> "wss://${base.removePrefix("https://")}/ws?device_id=$id"
            base.startsWith("http://") -> "ws://${base.removePrefix("http://")}/ws?device_id=$id"
            else -> "ws://$base/ws?device_id=$id"
        }
    }

    private fun requestBuilder(url: String): Request.Builder =
        Request.Builder().url(url).header("X-PhotoSync-Device-ID", deviceIdentity.id)

    private fun reconnectSocket(serverUrl: String = backendServerUrl.ifBlank { currentServerUrl() }) {
        socket?.close(1000, "Reconnect")
        socket = null
        if (started && connectionEnabled && serverUrl.isNotBlank() && !isLocalServerUrl(serverUrl)) connectSocket(serverUrl)
    }

    private fun connectSocket(serverUrl: String = backendServerUrl.ifBlank { currentServerUrl() }) {
        val base = serverUrl.trim().removeSuffix("/")
        if (!started || !connectionEnabled || base.isBlank() || isLocalServerUrl(base)) return
        socket?.cancel()
        serverStatus.text = "● Server: Connecting…"
        socket = client.newWebSocket(requestBuilder(wsUrl(base)).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                runOnUiThread {
                    if (!connectionEnabled) { webSocket.close(1000, "User disconnected"); return@runOnUiThread }
                    serverStatus.text = "● Server: Connected"
                    status.text = "Connected ✓"
                    refreshLists()
                    refreshHomeServerSummary()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val data = JSONObject(text)
                    when (data.optString("type")) {
                        "upload_progress" -> {
                            if (data.optString("source") != "web") return
                            val transferId = data.optString("transfer_id", data.optString("filename"))
                            val filename = data.optString("filename", "Receiving file")
                            val percent = data.optInt("percent", 0).coerceIn(0, 100)
                            runOnUiThread {
                                if (percent >= 100) removeProgressRow(transferId)
                                activeReceiveTransfers[transferId] = filename
                                if (percent < 100) {
                                    val row = ensureProgressRow(receivedFilesContainer, transferId, "Receiving $filename")
                                    row.second.progress = percent
                                }
                            }
                        }
                        "file_uploaded" -> {
                            val source = data.optString("source", "unknown")
                            val targetDevice = data.optString("device_id", "")
                            if (targetDevice.isNotBlank() && targetDevice != deviceIdentity.id) return
                            val transferId = data.optString("transfer_id", "")
                            runOnUiThread {
                                if (transferId.isNotBlank()) {
                                    activeReceiveTransfers.remove(transferId)
                                    removeProgressRow(transferId)
                                }
                                if (source == "app") addFile(data, sentFilesContainer, "No files sent from this app yet")
                                else addFile(data, receivedFilesContainer, "No files received from web yet")
                            }
                        }
                    }
                } catch (_: Exception) { }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread {
                    if (socket === webSocket) socket = null
                    serverStatus.text = "● Server: Disconnected"
                }
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                runOnUiThread {
                    if (socket === webSocket) socket = null
                    serverStatus.text = "● Server: Disconnected"
                    if (started && connectionEnabled) status.text = "Connection failed — retrying…"
                }
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!started || !connectionEnabled) return
        handler.postDelayed({ if (started && connectionEnabled && currentServerUrl().isNotBlank()) connectSocket() }, 2000)
    }

    private fun localBroadcastAddresses(): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        try {
            result.add(InetAddress.getByName("255.255.255.255"))
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val address = interfaceAddress.address
                    val broadcast = interfaceAddress.broadcast
                    if (address is Inet4Address && broadcast != null) result.add(broadcast)
                }
            }
        } catch (_: Exception) { }
        return result.distinctBy { it.hostAddress }
    }

    private fun discoverServer(forLocalServer: Boolean = false) {
        if (!started || !connectionEnabled || discoveryInProgress) return
        discoveryInProgress = true
        serverStatus.text = "● Server: Searching LAN…"
        Thread {
            var foundUrl: String? = null
            try {
                DatagramSocket().use { udp ->
                    udp.broadcast = true
                    udp.soTimeout = 600
                    val token = "PHOTOSYNC_DISCOVER_V1".toByteArray(Charsets.UTF_8)
                    for (address in localBroadcastAddresses()) {
                        try { udp.send(DatagramPacket(token, token.size, address, 8001)) } catch (_: Exception) { }
                    }
                    val buffer = ByteArray(1024)
                    val deadline = System.currentTimeMillis() + 2500
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            val response = DatagramPacket(buffer, buffer.size)
                            udp.receive(response)
                            val data = JSONObject(String(response.data, 0, response.length, Charsets.UTF_8))
                            if (data.optString("service") == "PHOTOSYNC") {
                                foundUrl = "http://${response.address.hostAddress}:${data.optInt("port", 8000)}"
                                break
                            }
                        } catch (_: java.net.SocketTimeoutException) { }
                    }
                }
            } catch (_: Exception) { }
            runOnUiThread {
                discoveryInProgress = false
                if (!started || !connectionEnabled) return@runOnUiThread
                val discoveredUrl = foundUrl
                if (discoveredUrl != null) {
                    if (forLocalServer) {
                        backendServerUrl = discoveredUrl
                        prefs.edit().putString("backend_server_url", discoveredUrl).apply()
                        reconnectSocket(discoveredUrl)
                        status.text = "Local server ready; PC server connected ✓"
                    } else {
                        saveAndConnect(discoveredUrl)
                        status.text = "Server found automatically ✓"
                    }
                } else {
                    serverStatus.text = "● Server: Not found"
                    status.text = "No external PC server found — enter URL or tap Find Server"
                }
            }
        }.start()
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return "file_${System.currentTimeMillis()}"
    }

    private fun ensureProgressRow(container: LinearLayout, key: String, label: String): Pair<View, ProgressBar> {
        val existing = activeProgressRows[key]
        if (existing != null) {
            val bar = existing.findViewWithTag<ProgressBar>("progress_bar")
            if (bar != null) return existing to bar
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0xFF172235.toInt())
        }
        val text = TextView(this).apply { this.text = label; textSize = 13f }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            tag = "progress_bar"; max = 100; progress = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(6) }
        }
        row.addView(text); row.addView(bar)
        activeProgressRows[key] = row
        container.addView(row, 0)
        return row to bar
    }

    private fun removeProgressRow(key: String) {
        val row = activeProgressRows.remove(key) ?: return
        (row.parent as? ViewGroup)?.removeView(row)
    }

    private fun upload(uri: Uri) {
        // App -> PC transfer always uses the external backend. The Android
        // embedded server may remain selected as the browser/local-server URL.
        val serverUrl = backendServerUrl.ifBlank {
            val current = currentServerUrl()
            if (isLocalServerUrl(current)) "" else current
        }
        if (serverUrl.isBlank() || isLocalServerUrl(serverUrl)) {
            runOnUiThread { status.text = "Connect to the external PC server first" }
            return
        }

        val originalName = displayName(uri)
        val progressKey = "app_${System.nanoTime()}"
        runOnUiThread { ensureProgressRow(sentFilesContainer, progressKey, "Sending $originalName") }

        Thread {
            try {
                runOnUiThread { status.text = "Sending $originalName…" }
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                val body = UriStreamRequestBody(contentResolver, uri, mime.toMediaType()) { sent, total ->
                    val percent = if (total > 0L) ((sent * 100L) / total).toInt().coerceIn(0, 100) else 0
                    handler.post {
                        activeProgressRows[progressKey]
                            ?.findViewWithTag<ProgressBar>("progress_bar")
                            ?.progress = percent
                    }
                }
                val request = requestBuilder(
                    "$serverUrl/upload-stream?source=app&filename=${URLEncoder.encode(originalName, "UTF-8")}"
                ).post(body).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                }
                runOnUiThread {
                    removeProgressRow(progressKey)
                    status.text = "Sent ✓ $originalName"
                }
                refreshLists()
            } catch (e: Exception) {
                runOnUiThread {
                    removeProgressRow(progressKey)
                    status.text = "Send failed: ${e.message}"
                }
            }
        }.start()
    }

    private fun refreshLists() {
        loadFiles("app", sentFilesContainer, "No files sent from this app yet")
        loadFiles("received", receivedFilesContainer, "No files received from web yet")
    }

    private fun loadFiles(source: String, container: LinearLayout, emptyText: String) {
        val serverUrl = currentServerUrl()
        if (serverUrl.isBlank()) return
        Thread {
            try {
                val request = requestBuilder("$serverUrl/files?source=$source").get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val json = response.body?.string() ?: "[]"
                    runOnUiThread {
                        if (!started) return@runOnUiThread
                        renderFiles(JSONArray(json), container, emptyText)
                        if (source == "received") reconcileReceiveProgress(JSONArray(json))
                    }
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun reconcileReceiveProgress(files: JSONArray) {
        if (activeReceiveTransfers.isEmpty()) return
        val completedNames = HashSet<String>()
        for (i in 0 until files.length()) files.optJSONObject(i)?.optString("filename", "")?.takeIf { it.isNotBlank() }?.let(completedNames::add)
        val completed = activeReceiveTransfers.filterValues { completedNames.contains(it) }.keys.toList()
        for (id in completed) { activeReceiveTransfers.remove(id); removeProgressRow(id) }
    }

    private fun addFile(item: JSONObject, container: LinearLayout, emptyText: String) {
        val stored = item.optString("stored_filename", "")
        if (stored.isBlank() || findRow(container, stored) != null) return
        removeEmptyMessage(container, emptyText)
        container.addView(createFileRow(item).apply { tag = stored }, 0)
    }

    private fun findRow(container: LinearLayout, stored: String): View? {
        for (i in 0 until container.childCount) if (container.getChildAt(i).tag == stored) return container.getChildAt(i)
        return null
    }

    private fun removeEmptyMessage(container: LinearLayout, emptyText: String) {
        for (i in container.childCount - 1 downTo 0) {
            val child = container.getChildAt(i)
            if (child is TextView && child.tag == "__empty__" && child.text == emptyText) container.removeViewAt(i)
        }
    }

    private fun renderFiles(files: JSONArray, container: LinearLayout, emptyText: String) {
        val incoming = LinkedHashMap<String, JSONObject>()
        for (i in 0 until files.length()) files.optJSONObject(i)?.let { item -> item.optString("stored_filename", "").takeIf { it.isNotBlank() }?.let { incoming[it] = item } }
        val existingRows = mutableMapOf<String, View>()
        for (i in container.childCount - 1 downTo 0) {
            val child = container.getChildAt(i); val stored = child.tag as? String
            if (stored != null && stored != "__empty__") { existingRows[stored] = child; if (!incoming.containsKey(stored)) container.removeViewAt(i) }
        }
        if (incoming.isEmpty()) {
            if (existingRows.isEmpty() && container.childCount == 0) container.addView(TextView(this).apply { text = emptyText; tag = "__empty__"; setPadding(0, dp(8), 0, dp(8)) })
            return
        }
        removeEmptyMessage(container, emptyText)
        for ((stored, item) in incoming) if (existingRows[stored] == null) container.addView(createFileRow(item).apply { tag = stored }, 0)
    }

    private fun createFileRow(item: JSONObject): LinearLayout {
        val name = item.optString("filename", "file")
        val path = item.optString("url", "")
        val size = item.optLong("size", 0L)
        val type = item.optString("type", "file")
        val mime = item.optString("content_type", "application/octet-stream")
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { if (type == "image") showImagePreview(path, name, mime) else downloadFile(path, name, mime) }
        }
        if (type == "image") {
            val fullUrl = buildFileUrl(path)
            val image = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)); scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF2B2B2B.toInt()); contentDescription = name; tag = "thumbnail:$fullUrl" }
            row.addView(image); loadThumbnail(fullUrl, image)
        } else row.addView(TextView(this).apply { text = "📄"; textSize = 30f; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)) })
        row.addView(TextView(this).apply { text = "$name\n${formatSize(size)}"; textSize = 14f; setPadding(dp(12), 0, dp(8), 0); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        return row
    }

    private fun buildFileUrl(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = currentServerUrl(); if (base.isBlank()) return path
        return "$base${if (path.startsWith("/")) path else "/$path"}"
    }

    private fun loadThumbnail(url: String, image: ImageView) {
        Thread {
            val cached = thumbnailCache.get(url)
            if (cached != null) { runOnUiThread { if (image.tag == "thumbnail:$url" && image.parent != null) image.setImageBitmap(cached) }; return@Thread }
            try {
                client.newCall(requestBuilder(url).get().build()).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val bytes = response.body?.bytes() ?: return@use
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 4 }) ?: return@use
                    thumbnailCache.put(url, bitmap)
                    runOnUiThread { if (image.tag == "thumbnail:$url" && image.parent != null) image.setImageBitmap(bitmap) }
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun showImagePreview(path: String, name: String, mime: String) {
        if (currentServerUrl().isBlank() || path.isBlank()) return
        val fullUrl = buildFileUrl(path)
        val imageView = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(8), dp(8), dp(8), dp(8)); minimumHeight = dp(220); contentDescription = name }
        val dialog = AlertDialog.Builder(this).setTitle(name).setView(imageView).setNegativeButton("Close", null).setPositiveButton("Download") { _, _ -> downloadFile(path, name, mime) }.create()
        dialog.show()
        Thread {
            try { client.newCall(requestBuilder(fullUrl).get().build()).execute().use { response -> if (response.isSuccessful) { val bytes = response.body?.bytes(); val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }; if (bitmap != null) runOnUiThread { if (dialog.isShowing) imageView.setImageBitmap(bitmap) } } } } catch (_: Exception) { }
        }.start()
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return "${bytes / 1024} KB"
        return "${bytes / (1024 * 1024)} MB"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun downloadFile(path: String, name: String, mime: String) {
        if (currentServerUrl().isBlank() || path.isBlank()) { Toast.makeText(this, "Server not connected", Toast.LENGTH_SHORT).show(); return }
        val fullUrl = buildFileUrl(path); val progressKey = "download_${System.nanoTime()}"
        runOnUiThread { ensureProgressRow(receivedFilesContainer, progressKey, "Downloading $name") }
        Thread {
            try {
                client.newCall(requestBuilder(fullUrl).get().build()).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("Empty file"); val total = body.contentLength(); var received = 0L
                    val values = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" }); put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS); put(MediaStore.Downloads.IS_PENDING, 1) }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Cannot create download")
                    try {
                        contentResolver.openOutputStream(uri).use { output -> requireNotNull(output) { "Cannot open download" }; body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) { val read = input.read(buffer); if (read <= 0) break; output!!.write(buffer, 0, read); received += read; if (total > 0) handler.post { activeProgressRows[progressKey]?.findViewWithTag<ProgressBar>("progress_bar")?.progress = ((received * 100L) / total).toInt().coerceIn(0, 100) } }
                        } }
                        contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                        runOnUiThread { Toast.makeText(this, "Downloaded: $name", Toast.LENGTH_SHORT).show() }
                    } catch (e: Exception) { contentResolver.delete(uri, null, null); throw e }
                }
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show() } }
            finally { runOnUiThread { removeProgressRow(progressKey) } }
        }.start()
    }

    private class UriStreamRequestBody(
        private val resolver: android.content.ContentResolver,
        private val uri: Uri,
        private val mediaType: MediaType,
        private val onProgress: (Long, Long) -> Unit
    ) : RequestBody() {
        override fun contentType(): MediaType = mediaType

        override fun contentLength(): Long {
            return try {
                resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getLong(0).takeIf { it >= 0L } ?: -1L else -1L
                } ?: -1L
            } catch (_: Exception) {
                -1L
            }
        }

        override fun writeTo(sink: BufferedSink) {
            val total = contentLength()
            var written = 0L
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to open file" }
                val buffer = ByteArray(256 * 1024)
                var nextProgress = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    sink.write(buffer, 0, read)
                    written += read
                    if (written >= nextProgress || (total > 0L && written >= total)) {
                        onProgress(written, total)
                        nextProgress = written + 1024L * 1024L
                    }
                }
                if (total >= 0L && written != total) {
                    throw java.io.EOFException("File changed or ended early during upload ($written/$total bytes)")
                }
            }
        }
    }
}
