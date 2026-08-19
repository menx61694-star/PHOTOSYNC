package com.photosync.uploader

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
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

class PeerActivity : AppCompatActivity() {
    companion object {
        private const val DISCOVERY_PORT = 47777
        private const val PROTOCOL = "PHOTOSYNC_PEER_V1"
    }

    private lateinit var status: TextView
    private lateinit var devicesContainer: LinearLayout
    private lateinit var progressBar: ProgressBar
    private val handler = Handler(Looper.getMainLooper())
    private val devices = ConcurrentHashMap<String, Peer>()
    private var running = false
    private var tcpServer: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null

    private data class Peer(val name: String, val address: String, val port: Int)

    private val picker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val peer = selectedPeer
        if (peer == null) {
            Toast.makeText(this, "Select a phone first", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        uris.forEach { sendFile(peer, it) }
    }

    private var selectedPeer: Peer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_peer)
        status = findViewById(R.id.peerStatus)
        devicesContainer = findViewById(R.id.peerDevices)
        progressBar = findViewById(R.id.peerProgress)
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
        handler.postDelayed({ if (running) discover() }, 500)
    }

    override fun onStop() {
        running = false
        tcpServer?.close()
        udpSocket?.close()
        tcpServer = null
        udpSocket = null
        super.onStop()
    }

    private fun startServers() {
        Thread {
            try {
                tcpServer = ServerSocket(0)
                val port = tcpServer!!.localPort
                runOnUiThread { status.text = "Ready • Direct transfer enabled" }
                while (running) {
                    val client = tcpServer!!.accept()
                    Thread { receiveFile(client) }.start()
                }
            } catch (_: Exception) { }
        }.start()

        Thread {
            try {
                udpSocket = DatagramSocket(DISCOVERY_PORT, InetAddress.getByName("0.0.0.0"))
                udpSocket!!.broadcast = true
                val buffer = ByteArray(512)
                while (running) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket!!.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (text == PROTOCOL) {
                        val port = tcpServer?.localPort ?: continue
                        val response = JSONObject().apply {
                            put("protocol", PROTOCOL)
                            put("name", android.os.Build.MODEL ?: "Android")
                            put("port", port)
                        }.toString().toByteArray(Charsets.UTF_8)
                        val reply = DatagramPacket(response, response.size, packet.address, packet.port)
                        udpSocket!!.send(reply)
                    }
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun discover() {
        Thread {
            val found = mutableListOf<Peer>()
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 500
                    val request = PROTOCOL.toByteArray(Charsets.UTF_8)
                    for (address in broadcastAddresses()) {
                        try { socket.send(DatagramPacket(request, request.size, address, DISCOVERY_PORT)) } catch (_: Exception) { }
                    }
                    val deadline = System.currentTimeMillis() + 1800
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            val packet = DatagramPacket(ByteArray(1024), 1024)
                            socket.receive(packet)
                            val json = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                            if (json.optString("protocol") != PROTOCOL) continue
                            val peer = Peer(json.optString("name", "Android"), packet.address.hostAddress ?: continue, json.optInt("port", 0))
                            if (peer.port > 0 && peer.address != localIpv4()) found.add(peer)
                        } catch (_: java.net.SocketTimeoutException) { }
                    }
                }
            } catch (_: Exception) { }
            runOnUiThread { showDevices(found.distinctBy { "${it.address}:${it.port}" }) }
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
                ni.interfaceAddresses.forEach { ia -> if (ia.address is Inet4Address && ia.broadcast != null) result.add(ia.broadcast) }
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
                for (ia in ni.interfaceAddresses) if (ia.address is Inet4Address) return ia.address.hostAddress
            }
            null
        } catch (_: Exception) { null }
    }

    private fun showDevices(found: List<Peer>) {
        devices.clear()
        found.forEach { devices["${it.address}:${it.port}"] = it }
        devicesContainer.removeAllViews()
        selectedPeer = null
        if (found.isEmpty()) {
            devicesContainer.addView(TextView(this).apply { text = "No nearby PhotoSync phones found"; setPadding(8, 16, 8, 16) })
            status.text = "Searching local network…"
            return
        }
        status.text = "${found.size} phone${if (found.size == 1) "" else "s"} found"
        found.forEach { peer ->
            val button = Button(this).apply {
                text = "📱 ${peer.name}\n${peer.address}"
                isAllCaps = false
                setOnClickListener {
                    selectedPeer = peer
                    status.text = "Selected ${peer.name}"
                    refreshSelection(peer)
                }
            }
            devicesContainer.addView(button)
        }
    }

    private fun refreshSelection(selected: Peer) {
        for (i in 0 until devicesContainer.childCount) {
            val child = devicesContainer.getChildAt(i) as? Button ?: continue
            child.alpha = if (child.text.toString().contains(selected.name)) 1f else 0.65f
        }
    }

    private fun fileName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return "file_${System.currentTimeMillis()}"
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
            try {
                temp = File(cacheDir, "peer_${System.currentTimeMillis()}_${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
                contentResolver.openInputStream(uri).use { input -> requireNotNull(input); temp.outputStream().use { output -> input.copyTo(output) } }
                val socket = Socket(peer.address, peer.port)
                socket.soTimeout = 15000
                socket.use { s ->
                    val size = temp.length()
                    val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                    val output = s.getOutputStream()
                    writeAsciiLine(output, JSONObject().apply { put("protocol", PROTOCOL); put("filename", name); put("size", size); put("mime", mime) }.toString())
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
                            val percent = if (size > 0) ((sent * 100) / size).toInt() else 0
                            runOnUiThread { progressBar.progress = percent; status.text = "Sending $name • $percent%" }
                        }
                        output.flush()
                    }
                    runOnUiThread { progressBar.progress = 100; status.text = "Sent ✓ $name" }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Direct send failed: ${e.message}" }
            } finally { temp?.delete() }
        }.start()
    }

    private fun receiveFile(socket: Socket) {
        try {
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
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Cannot create destination")
                try {
                    contentResolver.openOutputStream(uri).use { output ->
                        requireNotNull(output)
                        val buffer = ByteArray(64 * 1024)
                        var received = 0L
                        while (received < size) {
                            val toRead = minOf(buffer.size.toLong(), size - received).toInt()
                            val read = input.read(buffer, 0, toRead)
                            if (read <= 0) error("Transfer interrupted")
                            output.write(buffer, 0, read)
                            received += read
                            val percent = ((received * 100) / size).toInt()
                            runOnUiThread { progressBar.progress = percent; status.text = "Receiving $name • $percent%" }
                        }
                    }
                    contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                    runOnUiThread { progressBar.progress = 100; status.text = "Received ✓ $name" }
                } catch (e: Exception) {
                    contentResolver.delete(uri, null, null)
                    throw e
                }
            }
        } catch (e: Exception) {
            runOnUiThread { status.text = "Receive failed: ${e.message}" }
        }
    }
}
