package com.photosync.uploader

// Build trigger: LAN discovery/UI validation
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.wifi.DhcpInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private lateinit var status: TextView
    private lateinit var serverStatus: TextView
    private lateinit var serverUrlInput: EditText
    private lateinit var sentFilesContainer: LinearLayout
    private lateinit var receivedFilesContainer: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("photosync", MODE_PRIVATE) }
    private var socket: WebSocket? = null
    private var started = false
    private var discoveryInProgress = false

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
        serverUrlInput.setText(prefs.getString("server_url", ""))

        findViewById<Button>(R.id.saveServerButton).setOnClickListener {
            val url = serverUrlInput.text.toString().trim().removeSuffix("/")
            if (url.isBlank()) { status.text = "Enter a server URL or use Find Server"; return@setOnClickListener }
            saveAndConnect(url)
        }
        findViewById<Button>(R.id.findServerButton).setOnClickListener { discoverServer() }
        findViewById<Button>(R.id.selectButton).setOnClickListener { picker.launch("*/*") }
    }

    override fun onStart() {
        super.onStart()
        started = true
        discoverServer()
    }

    override fun onStop() {
        started = false
        socket?.close(1000, "App stopped")
        socket = null
        handler.removeCallbacksAndMessages(null)
        serverStatus.text = "● Server: Disconnected"
        super.onStop()
    }

    private fun currentServerUrl(): String = prefs.getString("server_url", serverUrlInput.text.toString().trim().removeSuffix("/"))?.trim()?.removeSuffix("/") ?: ""
    private fun saveAndConnect(url: String) { prefs.edit().putString("server_url", url).apply(); serverUrlInput.setText(url); status.text = "Server saved ✓"; reconnectSocket(); refreshLists() }
    private fun wsUrl(): String { val base = currentServerUrl(); return when { base.startsWith("https://") -> "wss://${base.removePrefix("https://")}/ws"; base.startsWith("http://") -> "ws://${base.removePrefix("http://")}/ws"; else -> "ws://$base/ws" } }
    private fun reconnectSocket() { socket?.close(1000, "Reconnect"); socket = null; if (started && currentServerUrl().isNotBlank()) connectSocket() }
    private fun connectSocket() {
        if (!started || currentServerUrl().isBlank()) return
        socket?.cancel(); serverStatus.text = "● Server: Connecting…"
        socket = client.newWebSocket(Request.Builder().url(wsUrl()).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) { runOnUiThread { serverStatus.text = "● Server: Connected"; refreshLists() } }
            override fun onMessage(webSocket: WebSocket, text: String) { try { val data = JSONObject(text); if (data.optString("type") != "file_uploaded") return; val source = data.optString("source", "unknown"); runOnUiThread { if (source == "app") addFile(data, sentFilesContainer, "No files sent from this app yet") else if (source == "web") addFile(data, receivedFilesContainer, "No files received from web yet") } } catch (_: Exception) {} }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { runOnUiThread { serverStatus.text = "● Server: Disconnected" }; scheduleReconnect() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) { runOnUiThread { serverStatus.text = "● Server: Disconnected" }; scheduleReconnect() }
        })
    }
    private fun scheduleReconnect() { if (!started) return; handler.removeCallbacksAndMessages(null); handler.postDelayed({ if (started) connectSocket() }, 2000) }

    private fun localBroadcastAddresses(): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        try {
            result.add(InetAddress.getByName("255.255.255.255"))
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcp: DhcpInfo = wifi.dhcpInfo
            if (dhcp.ipAddress != 0 && dhcp.netmask != 0) {
                val broadcast = (dhcp.ipAddress and dhcp.netmask.inv()) or dhcp.netmask
                val bytes = byteArrayOf(
                    (broadcast and 0xff).toByte(),
                    ((broadcast shr 8) and 0xff).toByte(),
                    ((broadcast shr 16) and 0xff).toByte(),
                    ((broadcast shr 24) and 0xff).toByte()
                )
                result.add(InetAddress.getByAddress(bytes))
            }
        } catch (_: Exception) {}
        return result.distinctBy { it.hostAddress }
    }

    private fun discoverServer() {
        if (!started || discoveryInProgress) return
        discoveryInProgress = true; serverStatus.text = "● Server: Searching LAN…"
        Thread {
            var foundUrl: String? = null
            try {
                DatagramSocket().use { udp ->
                    udp.broadcast = true; udp.soTimeout = 600
                    val token = "PHOTOSYNC_DISCOVER_V1".toByteArray(Charsets.UTF_8)
                    for (address in localBroadcastAddresses()) {
                        try { udp.send(DatagramPacket(token, token.size, address, 8001)) } catch (_: Exception) {}
                    }
                    val buffer = ByteArray(1024); val deadline = System.currentTimeMillis() + 2500
                    while (System.currentTimeMillis() < deadline) try {
                        val response = DatagramPacket(buffer, buffer.size); udp.receive(response)
                        val data = JSONObject(String(response.data, 0, response.length, Charsets.UTF_8))
                        if (data.optString("service") == "PHOTOSYNC") { foundUrl = "http://${response.address.hostAddress}:${data.optInt("port", 8000)}"; break }
                    } catch (_: java.net.SocketTimeoutException) {}
                }
            } catch (_: Exception) {}
            runOnUiThread {
                discoveryInProgress = false; if (!started) return@runOnUiThread
                if (foundUrl != null) { saveAndConnect(foundUrl!!); status.text = "Server found automatically ✓" }
                else if (currentServerUrl().isNotBlank()) { status.text = "LAN server not found; trying saved server…"; connectSocket(); refreshLists() }
                else { serverStatus.text = "● Server: Not found"; status.text = "No server found — tap Find Server" }
            }
        }.start()
    }

    private fun displayName(uri: Uri): String { contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) return c.getString(0) }; return "file_${System.currentTimeMillis()}" }
    private fun prepareUpload(uri: Uri, originalName: String): File { val ext = originalName.substringAfterLast('.', "bin").replace(Regex("[^A-Za-z0-9]"), ""); val temp = File(cacheDir, "upload_${System.currentTimeMillis()}.$ext"); contentResolver.openInputStream(uri).use { input -> requireNotNull(input) { "Unable to open file" }; temp.outputStream().use { output -> input.copyTo(output) } }; return temp }
    private fun upload(uri: Uri) {
        val serverUrl = currentServerUrl(); if (serverUrl.isBlank()) { runOnUiThread { status.text = "Find a server first" }; return }; val originalName = displayName(uri)
        Thread { var temp: File? = null; try { runOnUiThread { status.text = "Preparing $originalName…" }; temp = prepareUpload(uri, originalName); val mime = contentResolver.getType(uri) ?: "application/octet-stream"; val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", originalName, temp.asRequestBody(mime.toMediaType())).addFormDataPart("source", "app").build(); val request = Request.Builder().url("$serverUrl/upload").post(multipart).build(); runOnUiThread { status.text = "Sending $originalName…" }; client.newCall(request).execute().use { response -> if (!response.isSuccessful) error("HTTP ${response.code}") }; runOnUiThread { status.text = "Sent ✓ $originalName" }; refreshLists() } catch (e: Exception) { runOnUiThread { status.text = "Send failed: ${e.message}" } } finally { temp?.delete() } }.start()
    }
    private fun refreshLists() { loadFiles("app", sentFilesContainer, "No files sent from this app yet"); loadFiles("web", receivedFilesContainer, "No files received from web yet") }
    private fun loadFiles(source: String, container: LinearLayout, emptyText: String) { val serverUrl = currentServerUrl(); if (serverUrl.isBlank()) return; Thread { try { val request = Request.Builder().url("$serverUrl/files?source=$source").get().build(); client.newCall(request).execute().use { response -> if (!response.isSuccessful) return@use; val json = response.body?.string() ?: "[]"; runOnUiThread { renderFiles(JSONArray(json), container, emptyText) } } } catch (_: Exception) {} }.start() }
    private fun addFile(item: JSONObject, container: LinearLayout, emptyText: String) { val stored = item.optString("stored_filename", ""); if (stored.isBlank()) return; for (i in 0 until container.childCount) if (container.getChildAt(i).tag == stored) return; if (container.childCount == 1 && container.getChildAt(0) is TextView && (container.getChildAt(0) as TextView).text == emptyText) container.removeAllViews(); val row = createFileRow(item); row.tag = stored; container.addView(row, 0) }
    private fun renderFiles(files: JSONArray, container: LinearLayout, emptyText: String) { container.removeAllViews(); if (files.length() == 0) { container.addView(TextView(this).apply { text = emptyText; setPadding(0, dp(8), 0, dp(8)) }); return }; for (i in 0 until files.length()) { val item = files.getJSONObject(i); val row = createFileRow(item); row.tag = item.optString("stored_filename", ""); container.addView(row) } }
    private fun createFileRow(item: JSONObject): LinearLayout { val name = item.optString("filename", "file"); val url = item.optString("url", ""); val size = item.optLong("size", 0L); val type = item.optString("type", "file"); val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)); setOnClickListener { downloadFile(url, name, item.optString("content_type", "application/octet-stream")) } }; if (type == "image") { val image = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)); scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF2B2B2B.toInt()) }; row.addView(image); loadThumbnail(currentServerUrl() + url, image) } else row.addView(TextView(this).apply { text = "📄"; textSize = 30f; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)) }); row.addView(TextView(this).apply { text = "$name\n${formatSize(size)}"; textSize = 14f; setPadding(dp(12), 0, dp(8), 0); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }); return row }
    private fun loadThumbnail(url: String, image: ImageView) { Thread { try { val request = Request.Builder().url(url).build(); client.newCall(request).execute().use { response -> if (!response.isSuccessful) return@use; val bytes = response.body?.bytes() ?: return@use; val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size); if (bitmap != null) runOnUiThread { image.setImageBitmap(bitmap) } } } catch (_: Exception) {} }.start() }
    private fun formatSize(bytes: Long): String { if (bytes < 1024) return "$bytes B"; if (bytes < 1024 * 1024) return "${bytes / 1024} KB"; return "${bytes / (1024 * 1024)} MB" }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun downloadFile(path: String, name: String, mime: String) {
        val base = currentServerUrl(); if (base.isBlank() || path.isBlank()) { Toast.makeText(this, "Server not connected", Toast.LENGTH_SHORT).show(); return }
        val fullUrl = if (path.startsWith("http://") || path.startsWith("https://")) path else "$base${if (path.startsWith("/")) path else "/$path"}"
        Thread {
            try {
                val request = Request.Builder().url(fullUrl).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("Empty file")
                    val resolver = contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Cannot create download")
                    try {
                        resolver.openOutputStream(uri).use { output -> requireNotNull(output) { "Cannot open download" }; body.byteStream().use { input -> input.copyTo(output!!) } }
                        values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0); resolver.update(uri, values, null, null)
                        runOnUiThread { Toast.makeText(this, "Downloaded: $name", Toast.LENGTH_SHORT).show() }
                    } catch (e: Exception) {
                        resolver.delete(uri, null, null); throw e
                    }
                }
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show() } }
        }.start()
    }
}
