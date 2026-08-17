package com.photosync.uploader

import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.EditText
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
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private lateinit var status: TextView
    private lateinit var serverStatus: TextView
    private lateinit var serverUrlInput: EditText
    private lateinit var receivedFilesContainer: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("photosync", MODE_PRIVATE) }

    private val picker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) uris.forEach { upload(it) }
    }

    private val statusChecker = object : Runnable {
        override fun run() {
            checkServerConnection()
            loadReceivedFiles()
            handler.postDelayed(this, 4000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.statusText)
        serverStatus = findViewById(R.id.serverStatusText)
        serverUrlInput = findViewById(R.id.serverUrlInput)
        receivedFilesContainer = findViewById(R.id.receivedFilesContainer)
        serverUrlInput.setText(prefs.getString("server_url", "http://10.0.2.2:8000"))

        findViewById<Button>(R.id.saveServerButton).setOnClickListener {
            val url = serverUrlInput.text.toString().trim().removeSuffix("/")
            if (url.isBlank()) {
                status.text = "Enter a server URL"
                return@setOnClickListener
            }
            prefs.edit().putString("server_url", url).apply()
            status.text = "Server saved ✓"
            checkServerConnection()
            loadReceivedFiles()
        }

        findViewById<Button>(R.id.selectButton).setOnClickListener {
            picker.launch("*/*")
        }
    }

    override fun onStart() {
        super.onStart()
        handler.post(statusChecker)
    }

    override fun onStop() {
        handler.removeCallbacks(statusChecker)
        super.onStop()
    }

    private fun currentServerUrl(): String {
        return prefs.getString("server_url", serverUrlInput.text.toString().trim().removeSuffix("/"))
            ?.trim()?.removeSuffix("/") ?: ""
    }

    private fun checkServerConnection() {
        val serverUrl = currentServerUrl()
        if (serverUrl.isBlank()) {
            serverStatus.text = "● Server: Not configured"
            return
        }
        Thread {
            try {
                val request = Request.Builder().url("$serverUrl/health").get().build()
                client.newCall(request).execute().use { response ->
                    val connected = response.isSuccessful
                    runOnUiThread {
                        serverStatus.text = if (connected) "● Server: Connected" else "● Server: Offline (HTTP ${response.code})"
                    }
                }
            } catch (_: Exception) {
                runOnUiThread { serverStatus.text = "● Server: Disconnected" }
            }
        }.start()
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return "file_${System.currentTimeMillis()}"
    }

    private fun isImage(uri: Uri): Boolean {
        val type = contentResolver.getType(uri) ?: return false
        return type.startsWith("image/")
    }

    private fun prepareUpload(uri: Uri, originalName: String): File {
        if (isImage(uri)) {
            val bitmap = contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to open file" }
                BitmapFactory.decodeStream(input) ?: error("Unable to decode image")
            }
            val compressed = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
            compressed.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) { "Unable to compress image" }
            }
            bitmap.recycle()
            return compressed
        }

        val safeExtension = originalName.substringAfterLast('.', "bin").replace(Regex("[^A-Za-z0-9]"), "")
        val temp = File(cacheDir, "upload_${System.currentTimeMillis()}.$safeExtension")
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open file" }
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        return temp
    }

    private fun upload(uri: Uri) {
        val serverUrl = currentServerUrl()
        if (serverUrl.isBlank()) {
            runOnUiThread { status.text = "Set server URL first" }
            return
        }
        val originalName = displayName(uri)
        Thread {
            var temp: File? = null
            try {
                runOnUiThread { status.text = "Preparing $originalName…" }
                temp = prepareUpload(uri, originalName)
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                val body = temp.asRequestBody(mime.toMediaType())
                val multipart = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", originalName, body)
                    .build()
                val request = Request.Builder().url("$serverUrl/upload").post(multipart).build()
                runOnUiThread { status.text = "Uploading $originalName…" }
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    runOnUiThread { status.text = "Uploaded ✓ $originalName" }
                }
                loadReceivedFiles()
            } catch (e: Exception) {
                runOnUiThread { status.text = "Upload failed: ${e.message}" }
                checkServerConnection()
            } finally {
                temp?.delete()
            }
        }.start()
    }

    private fun loadReceivedFiles() {
        val serverUrl = currentServerUrl()
        if (serverUrl.isBlank()) return
        Thread {
            try {
                val request = Request.Builder().url("$serverUrl/files").get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val json = response.body?.string() ?: "[]"
                    runOnUiThread { renderReceivedFiles(JSONArray(json)) }
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun renderReceivedFiles(files: JSONArray) {
        receivedFilesContainer.removeAllViews()
        if (files.length() == 0) {
            val empty = TextView(this)
            empty.text = "No files on server yet"
            empty.setPadding(0, 8, 0, 8)
            receivedFilesContainer.addView(empty)
            return
        }
        for (i in 0 until files.length()) {
            val item = files.getJSONObject(i)
            val name = item.optString("filename", "file")
            val url = item.optString("url", "")
            val size = item.optLong("size", 0L)
            val button = Button(this)
            button.text = "$name  •  ${formatSize(size)}"
            button.setOnClickListener { downloadFile(url, name) }
            receivedFilesContainer.addView(button)
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return "${bytes / 1024} KB"
        return "${bytes / (1024 * 1024)} MB"
    }

    private fun downloadFile(path: String, name: String) {
        val fullUrl = currentServerUrl() + path
        try {
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
            val request = DownloadManager.Request(Uri.parse(fullUrl))
                .setTitle(name)
                .setDescription("PHOTOSYNC received file")
                .setMimeType(mime)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, "Downloading $name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
