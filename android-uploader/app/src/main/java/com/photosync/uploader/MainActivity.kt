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
    private val localServer by lazy { LocalServer(this, LOCAL_SERVER_PORT) }
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
        startLocalServer()
        discoverServer()
        handler.removeCallbacks(receiveRefreshRunnable)
        handler.postDelayed(receiveRefreshRunnable, 3000)
    }

    override fun onStop() {
        started = false
        handler.removeCallbacks(receiveRefreshRunnable)
        socket?.close(1000, "App stopped")
        socket = null
        localServer.stop()
        serverStatus.text = "● Server: Disconnected"
        super.onStop()
    }

    private fun startLocalServer() {
        val ok = localServer.start()
        if (!ok) {
            serverStatus.text = "● Local server: Failed"
            return
        }
        val address = localServer.url()
        if (address != null) {
            serverUrlInput.setText(address)
            serverStatus.text = "● Local server: Running"
            status.text = "Local server ready: $address"
        } else {
            serverStatus.text = "● Local server: Running (waiting for network)"
            status.text = "Local server running; connect to Wi-Fi/hotspot to get Web address"
        }
    }

    companion object {
        private const val LOCAL_SERVER_PORT = 18000
    }
