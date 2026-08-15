package com.photosync.uploader

import android.net.Uri
import android.os.Bundle
import android.widget.Button
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

    // Change this to the address of the PHOTOSYNC FastAPI server.
    private val serverUrl = "http://10.0.2.2:8000"

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) upload(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.statusText)
        findViewById<Button>(R.id.selectButton).setOnClickListener {
            picker.launch("image/*")
        }
    }

    private fun upload(uri: Uri) {
        status.text = "Uploading…"
        Thread {
            try {
                val file = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Unable to open image" }
                    file.outputStream().use { output -> input.copyTo(output) }
                }

                val body = file.asRequestBody("image/jpeg".toMediaType())
                val multipart = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.name, body)
                    .build()

                val request = Request.Builder()
                    .url("$serverUrl/upload")
                    .post(multipart)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    runOnUiThread { status.text = "Uploaded ✓" }
                }
                file.delete()
            } catch (e: Exception) {
                runOnUiThread { status.text = "Upload failed: ${e.message}" }
            }
        }.start()
    }
}
