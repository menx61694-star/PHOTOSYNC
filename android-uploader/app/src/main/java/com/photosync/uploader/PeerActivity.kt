package com.photosync.uploader

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class PeerActivity : AppCompatActivity() {
    companion object {
        private const val DISCOVERY_PORT = 47777
        private const val PROTOCOL = "PHOTOSYNC_PEER_V1"
        private const val DISCOVERY_INTERVAL_MS = 4000L
        private const val DISCOVERY_WINDOW_MS = 2500L
        private const val SOCKET_TIMEOUT_MS = 60000
    }

    private lateinit var status: TextView
    private lateinit var transferStatus: TextView
    private lateinit var devicesContainer: LinearLayout
    private lateinit var sentFilesContainer: LinearLayout
    private lateinit var receivedFilesContainer: LinearLayout
    private lateinit var progressBar: ProgressBar
    private val handler = Handler(Looper.getMainLooper())
    private val devices = ConcurrentHashMap<String, Peer>()
    private var running = false
    private var tcpServer: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var discoveryRunning = AtomicBoolean(false)

    private data class Peer(val name: String, val address: String, val port: Int)
    private var selectedPeer: Peer? = null

    private val picker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val peer = selectedPeer
        if (peer == null) {
            Toast.makeText(this, "Select a phone first", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        uris.forEach { sendFile(peer, it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_peer)
        status = findViewById(R.id.peerStatus)
        transferStatus = findViewById(R.id.peerTransferStatus)
        devicesContainer = findViewById(R.id.peerDevices)
        sentFilesContainer = findViewById(R.id.peerSentFiles)
        receivedFilesContainer = findViewById(R.id.peerReceivedFiles)
        progressBar = findViewById(R.id.peerProgress)

        showEmptyState(sentFilesContainer, "No files sent yet")
        showEmptyState(receivedFilesContainer, "No files received yet")

        findViewById<Button>(R.id.peerBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.peerRefresh).setOnClickListener { discover() }
        findViewById<Button>(R.id.peerSend).setOnClickListener {
            if (selectedPeer == null) Toast.makeText(this, "Select a phone first", Toast.LENGTH_SHORT).show()
            else picker.launch("*/*")
        }
    }

    override fun onStart() {
        super.onStart()
        running = true
        startServers()
        handler.postDelayed({ if (running) discover() }, 600)
        handler.postDelayed(discoveryLoop, DISCOVERY_INTERVAL_MS)
    }

    override fun onStop() {
        running = false
        handler.removeCallbacks(discoveryLoop)
        tcpServer?.close()
        udpSocket?.close()
        tcpServer = null
        udpSocket = null
        discoveryRunning.set(false)
        super.onStop()
    }

    private val discoveryLoop = object : Runnable {
        override fun run() {
            if (!running) return
            discover()
            handler.postDelayed(this, DISCOVERY_INTERVAL_MS)
        }
    }

    private fun startServers() {
        Thread {
            try {
                tcpServer = ServerSocket(0)
                tcpServer?.reuseAddress = true
                val port = tcpServer!!.localPort
                runOnUiThread { status.text = "Ready • Direct transfer enabled" }
                while (running) {
                    val client = tcpServer!!.accept()
                    Thread { receiveFile(client) }.start()
                }
            } catch (_: Exception) {
                if (running) runOnUiThread { status.text = "Direct transfer server unavailable" }
            }
        }.start()

        Thread {
            try {
                udpSocket = DatagramSocket(DISCOVERY_PORT, InetAddress.getByName("0.0.0.0"))
                udpSocket!!.broadcast = true
                udpSocket!!.reuseAddress = true
                udpSocket!!.soTimeout = 1000
                val buffer = ByteArray(1024)
                while (running) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        udpSocket!!.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        if (text != PROTOCOL) continue
                        val port = tcpServer?.localPort ?: continue
                        val response = JSONObject().apply {
                            put("protocol", PROTOCOL)
                            put("name", android.os.Build.MODEL ?: "Android")
                            put("port", port)
                        }.toString().toByteArray(Charsets.UTF_8)
                        val reply = DatagramPacket(response, response.size, packet.address, packet.port)
                        udpSocket!!.send(reply)
                    } catch (_: java.net.SocketTimeoutException) { }
                }
            } catch (_: Exception) {
                if (running) runOnUiThread { status.text = "Phone discovery unavailable" }
            }
        }.start()
    }

    private fun discover() {
        if (!running || !discoveryRunning.compareAndSet(false, true)) return
        Thread {
            val found = mutableMapOf<String, Peer>()
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 700
                    val request = PROTOCOL.toByteArray(Charsets.UTF_8)
                    for (address in broadcastAddresses()) {
                        try { socket.send(DatagramPacket(request, request.size, address, DISCOVERY_PORT)) } catch (_: Exception) { }
                    }
                    val deadline = System.currentTimeMillis() + DISCOVERY_WINDOW_MS
                    while (running && System.currentTimeMillis() < deadline) {
                        try {
                            val packet = DatagramPacket(ByteArray(1024), 1024)
                            socket.receive(packet)
                            val json = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                            if (json.optString("protocol") != PROTOCOL) continue
                            val address = packet.address.hostAddress ?: continue
                            val port = json.optInt("port", 0)
                            if (port <= 0 || address == localIpv4()) continue
                            val peer = Peer(json.optString("name", "Android"), address, port)
                            found["$address:$port"] = peer
                        } catch (_: java.net.SocketTimeoutException) { }
                    }
                }
            } catch (_: Exception) { }
            runOnUiThread {
                if (running) mergeDevices(found.values.toList())
            }
            discoveryRunning.set(false)
        }.start()
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        try {
            result.add(InetAddress.getByName("255.255.255.255"))
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                ni.interfaceAddresses.forEach { ia ->
                    if (ia.address is Inet4Address && ia.broadcast != null) result.add(ia.broadcast)
                }
            }
        } catch (_: Exception) { }
        return result.distinctBy { it.hostAddress }
    }

    private fun localIpv4(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    if (ia.address is Inet4Address) return ia.address.hostAddress
                }
            }
            null
        } catch (_: Exception) { null }
    }

    private fun mergeDevices(found: List<Peer>) {
        found.forEach { devices["${it.address}:${it.port}"] = it }

        val selectedKey = selectedPeer?.let { "${it.address}:${it.port}" }
        if (selectedKey != null && devices[selectedKey] != null) selectedPeer = devices[selectedKey]

        devicesContainer.removeAllViews()
        val current = devices.values.sortedBy { it.name.lowercase() }
        if (current.isEmpty()) {
            devicesContainer.addView(TextView(this).apply {
                text = "No nearby PhotoSync phones found\nKeep both phones on the same Wi-Fi network."
                setPadding(8, 16, 8, 16)
            })
            if (selectedPeer == null) status.text = "Searching local network…"
            return
        }

        if (selectedPeer == null) status.text = "${current.size} phone${if (current.size == 1) "" else "s"} found"
        current.forEach { peer ->
            val button = Button(this).apply {
                text = "📱 ${peer.name}\n${peer.address}"
                isAllCaps = false
                setOnClickListener {
                    selectedPeer = peer
                    status.text = "Connected target: ${peer.name}"
                    refreshSelection(peer)
                }
            }
            if (selectedPeer?.let { it.address == peer.address && it.port == peer.port } == true) button.alpha = 1f
            else button.alpha = 0.82f
            devicesContainer.addView(button)
        }
    }

    private fun refreshSelection(selected: Peer) {
        for (i in 0 until devicesContainer.childCount) {
            val child = devicesContainer.getChildAt(i) as? Button ?: continue
            child.alpha = if (child.text.toString().contains(selected.name)) 1f else 0.82f
        }
    }

    private fun fileName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return "file_${System.currentTimeMillis()}"
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return "${bytes / 1024} KB"
        if (bytes < 1024L * 1024L * 1024L) return "${bytes / (1024 * 1024)} MB"
        return "${bytes / (1024L * 1024L * 1024L)} GB"
    }

    private fun showEmptyState(container: LinearLayout, text: String) {
        container.removeAllViews()
        container.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(0xFFB9C7D9.toInt())
            setPadding(8, 12, 8, 12)
        })
    }

    private fun addTransferRow(container: LinearLayout, direction: String, name: String, size: Long) {
        if (container.childCount == 1 && container.getChildAt(0) is TextView) container.removeAllViews()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 10, 10, 10)
            setBackgroundColor(0xFF172235.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 6
            }
        }
        row.addView(TextView(this).apply {
            text = if (direction == "Sent") "↑" else "↓"
            textSize = 24f
            setTextColor(0xFFBDA4FF.toInt())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(42, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        row.addView(TextView(this).apply {
            text = "$name\n$direction • ${formatSize(size)}"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        container.addView(row, 0)
    }

    private fun writeAsciiLine(output: OutputStream, line: String) {
        output.write((line + "\n").toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun readAsciiLine(input: InputStream): String {
        val buffer = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) error("Connection closed")
            if (b == '\n'.code) return buffer.toString().removeSuffix("\r")
            if (buffer.length > 64 * 1024) error("Header too large")
            buffer.append(b.toChar())
        }
    }

    private fun sendFile(peer: Peer, uri: Uri) {
        Thread {
            val name = fileName(uri)
            var temp: File? = null
            var lastError: Exception? = null
            try {
                temp = File(cacheDir, "peer_${System.currentTimeMillis()}_${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Unable to open file" }
                    temp.outputStream().use { output -> input.copyTo(output) }
                }

                repeat(2) { attempt ->
                    if (lastError == null) {
                        try {
                            sendFileOnce(peer, temp!!, uri, name)
                            runOnUiThread {
                                addTransferRow(sentFilesContainer, "Sent", name, temp!!.length())
                                progressBar.progress = 100
                                transferStatus.text = "Sent ✓ $name"
                                status.text = "Sent ✓ $name"
                            }
                        } catch (e: Exception) {
                            lastError = e
                            if (attempt == 0) Thread.sleep(500)
                        }
                    }
                }
                if (lastError != null) throw lastError!!
            } catch (e: Exception) {
                runOnUiThread {
                    progressBar.progress = 0
                    transferStatus.text = "Send failed: ${e.message ?: "connection lost"}"
                    status.text = "Direct send failed — retrying discovery"
                }
            } finally {
                temp?.delete()
            }
        }.start()
    }

    private fun sendFileOnce(peer: Peer, temp: File, uri: Uri, name: String) {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.soTimeout = SOCKET_TIMEOUT_MS
        socket.connect(java.net.InetSocketAddress(peer.address, peer.port), 7000)
        socket.use { s ->
            val size = temp.length()
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"
            val output = s.getOutputStream()
            writeAsciiLine(output, JSONObject().apply {
                put("protocol", PROTOCOL)
                put("filename", name)
                put("size", size)
                put("mime", mime)
            }.toString())
            val response = readAsciiLine(s.getInputStream())
            if (response != "READY") error("Peer rejected transfer")
            var sent = 0L
            temp.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    sent += read
                    val percent = if (size > 0) ((sent * 100) / size).toInt().coerceIn(0, 100) else 100
                    runOnUiThread {
                        progressBar.progress = percent
                        transferStatus.text = "Sending $name • $percent%"
                        status.text = "Sending $name • $percent%"
                    }
                }
                output.flush()
            }
        }
    }

    private fun receiveFile(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.use { s ->
                val input = s.getInputStream()
                val header = JSONObject(readAsciiLine(input))
                if (header.optString("protocol") != PROTOCOL) error("Unsupported protocol")
                val name = header.optString("filename", "received_file")
                val size = header.optLong("size", -1L)
                val mime = header.optString("mime", "application/octet-stream")
                if (size < 0) error("Invalid size")
                writeAsciiLine(s.getOutputStream(), "READY")

                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PhotoSync")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Cannot create destination")
                try {
                    contentResolver.openOutputStream(uri).use { output ->
                        requireNotNull(output) { "Cannot open destination" }
                        val buffer = ByteArray(64 * 1024)
                        var received = 0L
                        while (received < size) {
                            val toRead = minOf(buffer.size.toLong(), size - received).toInt()
                            val read = input.read(buffer, 0, toRead)
                            if (read <= 0) error("Transfer interrupted")
                            output.write(buffer, 0, read)
                            received += read
                            val percent = ((received * 100) / size).toInt().coerceIn(0, 100)
                            runOnUiThread {
                                progressBar.progress = percent
                                transferStatus.text = "Receiving $name • $percent%"
                                status.text = "Receiving $name • $percent%"
                            }
                        }
                    }
                    contentResolver.update(uri, ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }, null, null)
                    runOnUiThread {
                        addTransferRow(receivedFilesContainer, "Received", name, size)
                        progressBar.progress = 100
                        transferStatus.text = "Received ✓ $name"
                        status.text = "Received ✓ $name"
                    }
                } catch (e: Exception) {
                    contentResolver.delete(uri, null, null)
                    throw e
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                progressBar.progress = 0
                transferStatus.text = "Receive failed: ${e.message ?: "connection lost"}"
                status.text = "Receive failed — waiting for connection"
            }
        }
    }
}
