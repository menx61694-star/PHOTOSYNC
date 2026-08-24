package com.photosync.uploader

// Stable file transfer + LAN discovery + live receive/preview + transfer progress
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
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private lateinit var status: TextView
    private lateinit var serverStatus: TextView
    private lateinit var serverUrlInput: EditText
    private lateinit var sentFilesContainer: LinearLayout
    private lateinit var receivedFilesContainer: LinearLayout
    private lateinit var mainScroll: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("photosync", MODE_PRIVATE) }
    private val deviceIdentity by lazy { DeviceIdentity(this) }
    private val thumbnailCache by lazy { ThumbnailCache(cacheDir) }
    private var socket: WebSocket? = null
    private var started = false
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
        serverUrlInput.setText(prefs.getString("server_url", ""))
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
        findViewById<Button>(R.id.selectButton).setOnClickListener { picker.launch("*/*") }
        findViewById<LinearLayout>(R.id.sentCard).setOnClickListener { mainScroll.smoothScrollTo(0, sentFilesContainer.top) }
        findViewById<LinearLayout>(R.id.receivedCard).setOnClickListener { mainScroll.smoothScrollTo(0, receivedFilesContainer.top) }
    }

    override fun onStart() {
        super.onStart()
        started = true
        applyThemeColor()
        discoverServer()
        handler.removeCallbacks(receiveRefreshRunnable)
        handler.postDelayed(receiveRefreshRunnable, 3000)
    }

    override fun onStop() {
        started = false
        handler.removeCallbacks(receiveRefreshRunnable)
        socket?.close(1000, "App stopped")
        socket = null
        serverStatus.text = "● Server: Disconnected"
        super.onStop()
    }

    private fun currentServerUrl(): String =
        prefs.getString("server_url", serverUrlInput.text.toString().trim().removeSuffix("/"))
            ?.trim()?.removeSuffix("/") ?: ""

    private fun applyThemeColor() {
        val raw = prefs.getString("theme_color", "#BDA4FF") ?: "#BDA4FF"
        try {
            val color = Color.parseColor(raw)
            findViewById<View>(R.id.menuButton)?.backgroundTintList = ColorStateList.valueOf(color)
            findViewById<Button>(R.id.selectButton)?.backgroundTintList = ColorStateList.valueOf(color)
            findViewById<Button>(R.id.findServerButton)?.backgroundTintList = ColorStateList.valueOf(color)
            findViewById<Button>(R.id.saveServerButton)?.backgroundTintList = ColorStateList.valueOf(color)
        } catch (_: Exception) { }
    }

    private fun saveAndConnect(url: String) {
        prefs.edit().putString("server_url", url).apply()
        serverUrlInput.setText(url)
        status.text = "Server saved ✓"
        reconnectSocket()
        refreshLists()
    }

    private fun wsUrl(): String {
        val base = currentServerUrl()
        val id = java.net.URLEncoder.encode(deviceIdentity.id, "UTF-8")
        return when {
            base.startsWith("https://") -> "wss://${base.removePrefix("https://")}/ws?device_id=$id"
            base.startsWith("http://") -> "ws://${base.removePrefix("http://")}/ws?device_id=$id"
            else -> "ws://$base/ws?device_id=$id"
        }
    }

    private fun requestBuilder(url: String): Request.Builder =
        Request.Builder().url(url).header("X-PhotoSync-Device-ID", deviceIdentity.id)

    private fun reconnectSocket() {
        socket?.close(1000, "Reconnect")
        socket = null
        if (started && currentServerUrl().isNotBlank()) connectSocket()
    }

    private fun connectSocket() {
        if (!started || currentServerUrl().isBlank()) return
        socket?.cancel()
        serverStatus.text = "● Server: Connecting…"
        socket = client.newWebSocket(requestBuilder(wsUrl()).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                runOnUiThread {
                    serverStatus.text = "● Server: Connected"
                    refreshLists()
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
                                if (percent >= 100) {
                                    activeReceiveTransfers[transferId] = filename
                                    removeProgressRow(transferId)
                                } else {
                                    activeReceiveTransfers[transferId] = filename
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
                                if (source == "app") {
                                    addFile(data, sentFilesContainer, "No files sent from this app yet")
                                } else {
                                    addFile(data, receivedFilesContainer, "No files received from web yet")
                                }
                            }
                        }
                    }
                } catch (_: Exception) { }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread { serverStatus.text = "● Server: Disconnected" }
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                runOnUiThread { serverStatus.text = "● Server: Disconnected" }
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!started) return
        handler.postDelayed({ if (started) connectSocket() }, 2000)
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

    private fun discoverServer() {
        if (!started || discoveryInProgress) return
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
                if (!started) return@runOnUiThread
                if (foundUrl != null) {
                    saveAndConnect(foundUrl!!)
                    status.text = "Server found automatically ✓"
                } else if (currentServerUrl().isNotBlank()) {
                    status.text = "LAN server not found; trying saved server…"
                    connectSocket()
                    refreshLists()
                } else {
                    serverStatus.text = "● Server: Not found"
                    status.text = "No server found — tap Find Server"
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

    private fun prepareUpload(uri: Uri, originalName: String): File {
        val ext = originalName.substringAfterLast('.', "bin").replace(Regex("[^A-Za-z0-9]"), "")
        val temp = File(cacheDir, "upload_${System.currentTimeMillis()}.$ext")
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open file" }
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        return temp
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
        val text = TextView(this).apply {
            this.text = label
            textSize = 13f
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            tag = "progress_bar"
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(6) }
        }
        row.addView(text)
        row.addView(bar)
        activeProgressRows[key] = row
        container.addView(row, 0)
        return row to bar
    }

    private fun removeProgressRow(key: String) {
        val row = activeProgressRows.remove(key) ?: return
        (row.parent as? ViewGroup)?.removeView(row)
    }

    private fun upload(uri: Uri) {
        val serverUrl = currentServerUrl()
        if (serverUrl.isBlank()) {
            runOnUiThread { status.text = "Find a server first" }
            return
        }
        val originalName = displayName(uri)
        val progressKey = "app_${System.nanoTime()}"
        runOnUiThread { ensureProgressRow(sentFilesContainer, progressKey, "Sending $originalName") }
        Thread {
            var temp: File? = null
            try {
                runOnUiThread { status.text = "Preparing $originalName…" }
                temp = prepareUpload(uri, originalName)
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                val progressBody = ProgressFileRequestBody(temp, mime.toMediaType()) { sent, total ->
                    val percent = if (total > 0L) ((sent * 100L) / total).toInt().coerceIn(0, 100) else 0
                    handler.post {
                        val row = activeProgressRows[progressKey]
                        val bar = row?.findViewWithTag<ProgressBar>("progress_bar")
                        if (bar != null) bar.progress = percent
                    }
                }
                val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", originalName, progressBody)
                    .addFormDataPart("source", "app")
                    .addFormDataPart("device_id", deviceIdentity.id)
                    .build()
                val request = requestBuilder("$serverUrl/upload").post(multipart).build()
                runOnUiThread { status.text = "Sending $originalName…" }
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
            } finally {
                temp?.delete()
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
        for (i in 0 until files.length()) {
            val item = files.optJSONObject(i) ?: continue
            val name = item.optString("filename", "")
            if (name.isNotBlank()) completedNames.add(name)
        }
        val completedTransfers = activeReceiveTransfers.filterValues { completedNames.contains(it) }.keys.toList()
        for (transferId in completedTransfers) {
            activeReceiveTransfers.remove(transferId)
            removeProgressRow(transferId)
        }
    }

    private fun addFile(item: JSONObject, container: LinearLayout, emptyText: String) {
        val stored = item.optString("stored_filename", "")
        if (stored.isBlank()) return
        if (findRow(container, stored) != null) return
        removeEmptyMessage(container, emptyText)
        val row = createFileRow(item)
        row.tag = stored
        container.addView(row, 0)
    }

    private fun findRow(container: LinearLayout, stored: String): View? {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child.tag == stored) return child
        }
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
        for (i in 0 until files.length()) {
            val item = files.optJSONObject(i) ?: continue
            val stored = item.optString("stored_filename", "")
            if (stored.isNotBlank()) incoming[stored] = item
        }

        val existingRows = mutableMapOf<String, View>()
        for (i in container.childCount - 1 downTo 0) {
            val child = container.getChildAt(i)
            val stored = child.tag as? String
            if (stored != null && stored != "__empty__") {
                existingRows[stored] = child
                if (!incoming.containsKey(stored)) container.removeViewAt(i)
            }
        }

        if (incoming.isEmpty()) {
            if (existingRows.isEmpty() && container.childCount == 0) {
                container.addView(TextView(this).apply {
                    text = emptyText
                    tag = "__empty__"
                    setPadding(0, dp(8), 0, dp(8))
                })
            }
            return
        }

        removeEmptyMessage(container, emptyText)
        for ((stored, item) in incoming) {
            if (existingRows[stored] == null) {
                val row = createFileRow(item).apply { tag = stored }
                container.addView(row, 0)
            }
        }
    }

    private fun createFileRow(item: JSONObject): LinearLayout {
        val name = item.optString("filename", "file")
        val path = item.optString("url", "")
        val size = item.optLong("size", 0L)
        val type = item.optString("type", "file")
        val mime = item.optString("content_type", "application/octet-stream")
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener {
                if (type == "image") showImagePreview(path, name, mime) else downloadFile(path, name, mime)
            }
        }
        if (type == "image") {
            val fullUrl = buildFileUrl(path)
            val image = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF2B2B2B.toInt())
                contentDescription = name
                tag = "thumbnail:$fullUrl"
            }
            row.addView(image)
            loadThumbnail(fullUrl, image)
        } else {
            row.addView(TextView(this).apply {
                text = "📄"
                textSize = 30f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
            })
        }
        row.addView(TextView(this).apply {
            text = "$name\n${formatSize(size)}"
            textSize = 14f
            setPadding(dp(12), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        return row
    }

    private fun buildFileUrl(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = currentServerUrl()
        if (base.isBlank()) return path
        return "$base${if (path.startsWith("/")) path else "/$path"}"
    }

    private fun loadThumbnail(url: String, image: ImageView) {
        Thread {
            val cached = thumbnailCache.get(url)
            if (cached != null) {
                runOnUiThread { if (image.tag == "thumbnail:$url" && image.parent != null) image.setImageBitmap(cached) }
                return@Thread
            }
            try {
                val request = requestBuilder(url).get().build()
                client.newCall(request).execute().use { response ->
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
        val base = currentServerUrl()
        if (base.isBlank() || path.isBlank()) return
        val fullUrl = buildFileUrl(path)
        val imageView = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            minimumHeight = dp(220)
            contentDescription = name
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(name)
            .setView(imageView)
            .setNegativeButton("Close", null)
            .setPositiveButton("Download") { _, _ -> downloadFile(path, name, mime) }
            .create()
        dialog.show()
        Thread {
            try {
                val request = requestBuilder(fullUrl).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val bytes = response.body?.bytes() ?: return@use
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options())
                    if (bitmap != null) runOnUiThread { if (dialog.isShowing) imageView.setImageBitmap(bitmap) }
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return "${bytes / 1024} KB"
        return "${bytes / (1024 * 1024)} MB"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun downloadFile(path: String, name: String, mime: String) {
        val base = currentServerUrl()
        if (base.isBlank() || path.isBlank()) {
            Toast.makeText(this, "Server not connected", Toast.LENGTH_SHORT).show()
            return
        }
        val fullUrl = buildFileUrl(path)
        val progressKey = "download_${System.nanoTime()}"
        runOnUiThread { ensureProgressRow(receivedFilesContainer, progressKey, "Downloading $name") }
        Thread {
            try {
                val request = requestBuilder(fullUrl).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("Empty file")
                    val total = body.contentLength()
                    var received = 0L
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Cannot create download")
                    try {
                        contentResolver.openOutputStream(uri).use { output ->
                            requireNotNull(output) { "Cannot open download" }
                            body.byteStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    output.write(buffer, 0, read)
                                    received += read
                                    if (total > 0) {
                                        val percent = ((received * 100L) / total).toInt().coerceIn(0, 100)
                                        handler.post {
                                            val row = activeProgressRows[progressKey]
                                            val bar = row?.findViewWithTag<ProgressBar>("progress_bar")
                                            if (bar != null) bar.progress = percent
                                        }
                                    }
                                }
                            }
                        }
                        contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                        runOnUiThread { Toast.makeText(this, "Downloaded: $name", Toast.LENGTH_SHORT).show() }
                    } catch (e: Exception) {
                        contentResolver.delete(uri, null, null)
                        throw e
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show() }
            } finally {
                runOnUiThread { removeProgressRow(progressKey) }
            }
        }.start()
    }

    private class ProgressFileRequestBody(
        private val file: File,
        private val mediaType: MediaType,
        private val onProgress: (written: Long, total: Long) -> Unit
    ) : RequestBody() {
        override fun contentType(): MediaType = mediaType
        override fun contentLength(): Long = file.length()
        override fun writeTo(sink: BufferedSink) {
            val total = contentLength()
            var written = 0L
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    sink.write(buffer, 0, read)
                    written += read
                    onProgress(written, total)
                }
            }
        }
    }
}
