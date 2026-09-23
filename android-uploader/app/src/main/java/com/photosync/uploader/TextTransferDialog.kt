package com.photosync.uploader

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class TextTransferButton(context: Context, attrs: android.util.AttributeSet? = null) : androidx.appcompat.widget.AppCompatButton(context, attrs) {
    init { setOnClickListener { TextTransferDialog.show(context) } }
}

object TextTransferDialog {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .build()

    fun show(context: Context) {
        val input = EditText(context).apply {
            hint = "Type text to send…"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF8795A8.toInt())
            gravity = Gravity.TOP or Gravity.START
            minLines = 6
            maxLines = 12
            setPadding(24, 20, 24, 20)
            setBackgroundColor(0xFF172235.toInt())
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 8, 20, 0)
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("Text Transfer")
            .setView(box)
            .setPositiveButton("Send", null)
            .setNeutralButton("Received Text", null)
            .setNegativeButton("Close", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text.toString()
                if (text.isBlank()) { input.error = "Enter some text"; return@setOnClickListener }
                send(context, text, dialog)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                showReceived(context)
            }
        }
        dialog.show()
    }

    private fun send(context: Context, text: String, dialog: AlertDialog) {
        val prefs = context.getSharedPreferences("photosync", Context.MODE_PRIVATE)
        val saved = prefs.getString("server_url", "")?.trim()?.removeSuffix("/") ?: ""
        val backend = prefs.getString("backend_server_url", "")?.trim()?.removeSuffix("/") ?: ""
        val local = saved.contains(":18000")
        val target = if (local || backend.isBlank()) saved else backend
        if (target.isBlank()) { ToastCompat.show(context, "Connect to a PhotoSync server first"); return }

        Thread {
            try {
                val name = "text_" + System.currentTimeMillis() + ".txt"
                val response = if (local) {
                    val body = text.toRequestBody("text/plain; charset=utf-8".toMediaType())
                    client.newCall(Request.Builder().url("$target/text").post(body).build()).execute()
                } else {
                    val fileBody = text.toRequestBody("text/plain; charset=utf-8".toMediaType())
                    val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("source", "app")
                        .addFormDataPart("filename", name)
                        .addFormDataPart("file", name, fileBody)
                        .build()
                    client.newCall(Request.Builder().url("$target/upload").header("X-PhotoSync-Device-ID", DeviceIdentity(context).id).post(form).build()).execute()
                }
                response.use {
                    if (!it.isSuccessful) error("HTTP " + it.code)
                }
                (context as? android.app.Activity)?.runOnUiThread {
                    ToastCompat.show(context, "Text sent ✓")
                    dialog.dismiss()
                }
            } catch (e: Exception) {
                (context as? android.app.Activity)?.runOnUiThread {
                    ToastCompat.show(context, "Text send failed: " + e.message)
                }
            }
        }.start()
    }

    private fun showReceived(context: Context) {
        val prefs = context.getSharedPreferences("photosync", Context.MODE_PRIVATE)
        val target = prefs.getString("server_url", "")?.trim()?.removeSuffix("/") ?: ""
        if (target.isBlank()) { ToastCompat.show(context, "Connect to a server first"); return }
        Thread {
            try {
                val req = Request.Builder().url("$target/files?source=received").build()
                client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP " + response.code)
                    val array = JSONArray(response.body?.string() ?: "[]")
                    val texts = mutableListOf<Pair<String,String>>()
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val name = item.optString("filename", "")
                        val url = item.optString("url", "")
                        if (name.lowercase().endsWith(".txt") && url.isNotBlank()) texts.add(name to url)
                    }
                    (context as? android.app.Activity)?.runOnUiThread { renderReceived(context, texts, target) }
                }
            } catch (e: Exception) {
                (context as? android.app.Activity)?.runOnUiThread { ToastCompat.show(context, "Unable to load received text: " + e.message) }
            }
        }.start()
    }

    private fun renderReceived(context: Context, texts: List<Pair<String,String>>, base: String) {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 8, 20, 8) }
        if (texts.isEmpty()) {
            box.addView(TextView(context).apply { text = "No received text yet"; setTextColor(Color.WHITE); setPadding(8, 20, 8, 20) })
        } else {
            texts.forEach { (name, path) ->
                val b = Button(context).apply {
                    text = name
                    isAllCaps = false
                    setOnClickListener { readText(context, if (path.startsWith("http")) path else base + if (path.startsWith("/")) path else "/$path", name) }
                }
                box.addView(b)
            }
        }
        AlertDialog.Builder(context).setTitle("Received Text").setView(box).setPositiveButton("Close", null).show()
    }

    private fun readText(context: Context, url: String, name: String) {
        Thread {
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP " + response.code)
                    val text = response.body?.string().orEmpty()
                    (context as? android.app.Activity)?.runOnUiThread {
                        AlertDialog.Builder(context).setTitle(name).setMessage(text).setPositiveButton("Close", null).show()
                    }
                }
            } catch (e: Exception) {
                (context as? android.app.Activity)?.runOnUiThread { ToastCompat.show(context, "Unable to open text: " + e.message) }
            }
        }.start()
    }
}

private object ToastCompat {
    fun show(context: Context, message: String) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
