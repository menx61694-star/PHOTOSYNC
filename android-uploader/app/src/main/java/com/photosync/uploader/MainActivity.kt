package com.photosync.uploader

import android.app.DownloadManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private lateinit var status: TextView
    private lateinit var serverStatus: TextView
    private lateinit var serverUrlInput: EditText
    private lateinit var sentFilesContainer: LinearLayout
    private lateinit var receivedFilesContainer: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("photosync", MODE_PRIVATE) }

    private val picker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> if (uris.isNotEmpty()) uris.forEach { upload(it) } }
    private val statusChecker = object : Runnable {
        override fun run() {
            checkServerConnection()
            loadFiles("app", sentFilesContainer, "No files sent from this app yet")
            loadFiles("received", receivedFilesContainer, "No files received yet")
            handler.postDelayed(this, 4000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.statusText)
        serverStatus = findViewById(R.id.serverStatusText)
        serverUrlInput = findViewById(R.id.serverUrlInput)
        sentFilesContainer = findViewById(R.id.sentFilesContainer)
        receivedFilesContainer = findViewById(R.id.receivedFilesContainer)
        serverUrlInput.setText(prefs.getString("server_url", "http://10.0.2.2:8000"))
        findViewById<Button>(R.id.saveServerButton).setOnClickListener {
            val url = serverUrlInput.text.toString().trim().removeSuffix("/")
            if (url.isBlank()) { status.text = "Enter a server URL"; return@setOnClickListener }
            prefs.edit().putString("server_url", url).apply()
            status.text = "Server saved ✓"
            checkServerConnection(); refreshLists()
        }
        findViewById<Button>(R.id.selectButton).setOnClickListener { picker.launch("*/*") }
    }

    override fun onStart() { super.onStart(); handler.post(statusChecker) }
    override fun onStop() { handler.removeCallbacks(statusChecker); super.onStop() }
    private fun currentServerUrl(): String = prefs.getString("server_url", serverUrlInput.text.toString().trim().removeSuffix("/"))?.trim()?.removeSuffix("/") ?: ""

    private fun checkServerConnection() {
        val serverUrl = currentServerUrl()
        if (serverUrl.isBlank()) { serverStatus.text = "● Server: Not configured"; return }
        Thread {
            try {
                client.newCall(Request.Builder().url("$serverUrl/health").get().build()).execute().use { response ->
                    runOnUiThread { serverStatus.text = if (response.isSuccessful) "● Server: Connected" else "● Server: Offline (HTTP ${response.code})" }
                }
            } catch (_: Exception) { runOnUiThread { serverStatus.text = "● Server: Disconnected" } }
        }.start()
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return "file_${System.currentTimeMillis()}"
    }

    private fun prepareUpload(uri: Uri, originalName: String): File {
        val ext = originalName.substringAfterLast('.', "bin").replace(Regex("[^A-Za-z0-9]"), "")
        val temp = File(cacheDir, "upload_${System.currentTimeMillis()}.$ext")
        contentResolver.openInputStream(uri).use { input -> requireNotNull(input) { "Unable to open file" }; temp.outputStream().use { output -> input.copyTo(output) } }
        return temp
    }

    private fun upload(uri: Uri) {
        val serverUrl = currentServerUrl()
        if (serverUrl.isBlank()) { runOnUiThread { status.text = "Set server URL first" }; return }
        val originalName = displayName(uri)
        Thread {
            var temp: File? = null
            try {
                runOnUiThread { status.text = "Preparing $originalName…" }
                temp = prepareUpload(uri, originalName)
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", originalName, temp.asRequestBody(mime.toMediaType()))
                    .addFormDataPart("source", "app").build()
                runOnUiThread { status.text = "Sending $originalName…" }
                client.newCall(Request.Builder().url("$serverUrl/upload").post(multipart).build()).execute().use { response -> if (!response.isSuccessful) error("HTTP ${response.code}") }
                runOnUiThread { status.text = "Sent ✓ $originalName" }
                refreshLists()
            } catch (e: Exception) { runOnUiThread { status.text = "Send failed: ${e.message}" }; checkServerConnection() }
            finally { temp?.delete() }
        }.start()
    }

    private fun refreshLists() {
        loadFiles("app", sentFilesContainer, "No files sent from this app yet")
        loadFiles("received", receivedFilesContainer, "No files received yet")
    }

    private fun loadFiles(source: String, container: LinearLayout, emptyText: String) {
        val serverUrl = currentServerUrl(); if (serverUrl.isBlank()) return
        Thread {
            try {
                client.newCall(Request.Builder().url("$serverUrl/files?source=$source").get().build()).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val json = response.body?.string() ?: "[]"
                    runOnUiThread { renderFiles(JSONArray(json), container, emptyText) }
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun renderFiles(files: JSONArray, container: LinearLayout, emptyText: String) {
        container.removeAllViews()
        if (files.length() == 0) {
            container.addView(TextView(this).apply { text = emptyText; setPadding(0, dp(8), 0, dp(8)) }); return
        }
        for (i in 0 until files.length()) {
            val item = files.getJSONObject(i)
            val name = item.optString("filename", "file")
            val url = item.optString("url", "")
            val size = item.optLong("size", 0L)
            val type = item.optString("type", "file")
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)); setOnClickListener { downloadFile(url, name) } }
            if (type == "image") {
                val image = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)); scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF2B2B2B.toInt()) }
                row.addView(image); loadThumbnail(currentServerUrl() + url, image)
            } else {
                row.addView(TextView(this).apply { text = "📄"; textSize = 30f; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)) })
            }
            row.addView(TextView(this).apply { text = "$name\n${formatSize(size)}"; textSize = 14f; setPadding(dp(12), 0, dp(8), 0); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            container.addView(row)
        }
    }

    private fun loadThumbnail(url: String, image: ImageView) {
        Thread {
            try { URL(url).openStream().use { BitmapFactory.decodeStream(it) }?.let { bitmap -> runOnUiThread { image.setImageBitmap(bitmap) } } } catch (_: Exception) { }
        }.start()
    }

    private fun formatSize(bytes: Long): String { if (bytes < 1024) return "$bytes B"; if (bytes < 1024 * 1024) return "${bytes / 1024} KB"; return "${bytes / (1024 * 1024)} MB" }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun downloadFile(path: String, name: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(currentServerUrl() + path)).setTitle(name).setDescription("PHOTOSYNC file").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, "Downloading $name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show() }
    }
}
