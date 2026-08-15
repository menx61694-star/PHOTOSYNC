package com.photosync.uploader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

class MainActivity : AppCompatActivity() {
    private val client = OkHttpClient()
    private lateinit var status: TextView
    private lateinit var serverUrlInput: EditText

    private val prefs by lazy { getSharedPreferences("photosync", MODE_PRIVATE) }

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) upload(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.statusText)
        serverUrlInput = findViewById(R.id.serverUrlInput)
        serverUrlInput.setText(prefs.getString("server_url", "http://10.0.2.2:8000"))

        findViewById<Button>(R.id.saveServerButton).setOnClickListener {
            val url = serverUrlInput.text.toString().trim().removeSuffix("/")
            if (url.isBlank()) {
                status.text = "Enter a server URL"
                return@setOnClickListener
            }
            prefs.edit().putString("server_url", url).apply()
            status.text = "Server saved ✓"
        }

        findViewById<Button>(R.id.selectButton).setOnClickListener {
            picker.launch("image/*")
        }
    }

    private fun compressPhoto(uri: Uri): File {
        val bitmap = contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open image" }
            BitmapFactory.decodeStream(input) ?: error("Unable to decode image")
        }

        val compressed = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        compressed.outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                "Unable to compress image"
            }
        }
        bitmap.recycle()
        return compressed
    }

    private fun upload(uri: Uri) {
        val serverUrl = prefs.getString("server_url", serverUrlInput.text.toString().trim().removeSuffix("/"))
            ?.trim()?.removeSuffix("/") ?: return
        if (serverUrl.isBlank()) {
            runOnUiThread { status.text = "Set server URL first" }
            return
        }

        status.text = "Compressing…"
        Thread {
            var file: File? = null
            try {
                file = compressPhoto(uri)
                val body = file.asRequestBody("image/jpeg".toMediaType())
                val multipart = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.name, body)
                    .build()

                val request = Request.Builder()
                    .url("$serverUrl/upload")
                    .post(multipart)
                    .build()

                runOnUiThread { status.text = "Uploading…" }
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    runOnUiThread { status.text = "Uploaded ✓" }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Upload failed: ${e.message}" }
            } finally {
                file?.delete()
            }
        }.start()
    }
}
