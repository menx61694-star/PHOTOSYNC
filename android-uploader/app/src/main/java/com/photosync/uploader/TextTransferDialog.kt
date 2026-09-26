package com.photosync.uploader

import android.app.AlertDialog
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import java.io.File
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
            minLines = 1
            maxLines = 12
            isVerticalScrollBarEnabled = true
            setOnFocusChangeListener { _, _ -> }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    post {
                        val desired = (lineHeight * (lineCount.coerceIn(1, 12))) + paddingTop + paddingBottom
                        layoutParams = layoutParams.apply { height = desired.coerceAtMost(dp(context, 260)) }
                        requestLayout()
                    }
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            setPadding(24, 20, 24, 20)
            setBackgroundColor(0xFF172235.toInt())
        }
        val paste = Button(context).apply {
            text = "Paste text from clipboard"
            isAllCaps = false
        }
        val pasteImage = Button(context).apply {
            text = "Paste image from clipboard"
            isAllCaps = false
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 8, 20, 0)
            addView(input, LinearLayout.LayoutParams(-1, -2))
            addView(paste, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10 })
            addView(pasteImage, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6 })
        }
        paste.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clip = clipboard?.primaryClip
            val pasted = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context).toString() else ""
            if (pasted.isBlank()) ToastCompat.show(context, "Clipboard has no text") else input.append(pasted)
        }
        pasteImage.setOnClickListener {
            pasteImageFromClipboard(context, dialog = null)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("Text / Clipboard Transfer")
            .setView(box)
            .setPositiveButton("Send", null)
            .setNeutralButton("Receive", null)
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
            input.requestFocus()
        }
        dialog.show()
    }

    private fun pasteImageFromClipboard(context: Context, dialog: AlertDialog?) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clip = clipboard?.primaryClip
        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val uri = item?.uri
        if (uri == null) {
            ToastCompat.show(context, "Clipboard has no image")
            return
        }
        Thread {
            var temp: File? = null
            try {
                val mime = context.contentResolver.getType(uri) ?: "image/png"
                if (!mime.startsWith("image/")) error("Clipboard item is not an image")
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "png"
                temp = File(context.cacheDir, "photosync_clipboard_" + System.currentTimeMillis() + "." + ext)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp!!.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
                } ?: error("Unable to read clipboard image")
                if (!temp!!.isFile || temp!!.length() <= 0L) error("Clipboard image is empty")
                sendImage(context, temp!!, mime, dialog)
            } catch (e: Exception) {
                postIfActivityAlive(context) {
                    ToastCompat.show(context, "Image paste failed: " + (e.message ?: "unknown error"))
                }
                temp?.delete()
            }
        }.start()
    }

    private fun sendImage(context: Context, file: File, mime: String, dialog: AlertDialog?) {
        val prefs = context.getSharedPreferences("photosync", Context.MODE_PRIVATE)
        val app = context.applicationContext as? PhotoSyncApplication
        val local = app?.localServer?.isRunning() == true
        val saved = prefs.getString("server_url", "")?.trim()?.removeSuffix("/") ?: ""
        val backend = prefs.getString("backend_server_url", "")?.trim()?.removeSuffix("/") ?: ""
        val target = if (local) app?.localServer?.url()?.trim()?.removeSuffix("/") ?: "" else backend.ifBlank { saved }
        if (target.isBlank()) {
            file.delete()
            postIfActivityAlive(context) {
                ToastCompat.show(context, "Connect to a PhotoSync server first")
            }
            return
        }
        Thread {
            try {
                val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "png"
                val name = "clipboard_image_" + System.currentTimeMillis() + "." + extension
                val body = file.asRequestBody(mime.toMediaType())
                val response = if (local) {
                    client.newCall(
                        buildAuthenticatedRequest(context, "$target/upload?source=app&filename=" + Uri.encode(name), local, prefs)
                        .header("Content-Type", mime)
                        .post(body)
                        .build()
                    ).execute()
                } else {
                    val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("source", "app")
                        .addFormDataPart("filename", name)
                        .addFormDataPart("file", name, body)
                        .build()
                    client.newCall(
                        buildAuthenticatedRequest(context, "$target/upload", local, prefs)
                            .post(form)
                            .build()
                    ).execute()
                }
                response.use {
                    if (!it.isSuccessful) error("HTTP " + it.code)
                }
                postIfActivityAlive(context) {
                    ToastCompat.show(context, "Image pasted and sent ✓")
                    if (dialog?.isShowing == true) dialog.dismiss()
                }
            } catch (e: Exception) {
                postIfActivityAlive(context) {
                    ToastCompat.show(context, "Image send failed: " + (e.message ?: "unknown error"))
                }
            } finally {
                file.delete()
            }
        }.start()
    }

    private fun send(context: Context, text: String, dialog: AlertDialog) {
        val prefs = context.getSharedPreferences("photosync", Context.MODE_PRIVATE)
        val app = context.applicationContext as? PhotoSyncApplication
        val local = app?.localServer?.isRunning() == true
        val saved = prefs.getString("server_url", "")?.trim()?.removeSuffix("/") ?: ""
        val backend = prefs.getString("backend_server_url", "")?.trim()?.removeSuffix("/") ?: ""
        val target = if (local) app?.localServer?.url()?.trim()?.removeSuffix("/") ?: "" else backend.ifBlank { saved }
        if (target.isBlank()) { ToastCompat.show(context, "Connect to a PhotoSync server first"); return }

        Thread {
            try {
                val name = "text_" + System.currentTimeMillis() + ".txt"
                val response = if (local) {
                    val body = text.toRequestBody("text/plain; charset=utf-8".toMediaType())
                    client.newCall(buildAuthenticatedRequest(context, "$target/text", local, prefs).post(body).build()).execute()
                } else {
                    val fileBody = text.toRequestBody("text/plain; charset=utf-8".toMediaType())
                    val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("source", "app")
                        .addFormDataPart("filename", name)
                        .addFormDataPart("file", name, fileBody)
                        .build()
                    client.newCall(
                        buildAuthenticatedRequest(context, "$target/upload", local, prefs)
                            .post(form).build()
                    ).execute()
                }
                response.use {
                    if (!it.isSuccessful) error("HTTP " + it.code)
                }
                postIfActivityAlive(context) {
                    ToastCompat.show(context, "Text sent ✓")
                    if (dialog.isShowing) dialog.dismiss()
                }
            } catch (e: Exception) {
                postIfActivityAlive(context) {
                    ToastCompat.show(context, "Text send failed: " + e.message)
                }
            }
        }.start()
    }

    private fun showReceived(context: Context) {
        val prefs = context.getSharedPreferences("photosync", Context.MODE_PRIVATE)
        val app = context.applicationContext as? PhotoSyncApplication
        val local = app?.localServer?.isRunning() == true
        val saved = prefs.getString("server_url", "")?.trim()?.removeSuffix("/") ?: ""
        val backend = prefs.getString("backend_server_url", "")?.trim()?.removeSuffix("/") ?: ""
        val target = if (local) app?.localServer?.url()?.trim()?.removeSuffix("/") ?: "" else backend.ifBlank { saved }
        if (target.isBlank()) { ToastCompat.show(context, "Connect to a server first"); return }
        Thread {
            try {
                val req = buildAuthenticatedRequest(context, "$target/files?source=received", local, prefs).build()
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
                    postIfActivityAlive(context) { renderReceived(context, texts, target) }
                }
            } catch (e: Exception) {
                postIfActivityAlive(context) { ToastCompat.show(context, "Unable to load received text: " + e.message) }
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
                val prefs = context.getSharedPreferences("photosync", Context.MODE_PRIVATE)
                val app = context.applicationContext as? PhotoSyncApplication
                val local = app?.localServer?.isRunning() == true
                client.newCall(buildAuthenticatedRequest(context, url, local, prefs).build()).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP " + response.code)
                    val text = response.body?.string().orEmpty()
                    postIfActivityAlive(context) {
                        AlertDialog.Builder(context).setTitle(name).setMessage(text).setPositiveButton("Close", null).show()
                    }
                }
            } catch (e: Exception) {
                postIfActivityAlive(context) { ToastCompat.show(context, "Unable to open text: " + e.message) }
            }
        }.start()
    }
}

private fun buildAuthenticatedRequest(
    context: Context,
    url: String,
    local: Boolean,
    prefs: android.content.SharedPreferences
): Request.Builder {
    val builder = Request.Builder().url(url)
    if (local) {
        val token = (context.applicationContext as? PhotoSyncApplication)?.localServer?.localAppToken().orEmpty()
        if (token.isNotBlank()) builder.header("X-PhotoSync-Local-Token", token)
    } else {
        builder.header("X-PhotoSync-Device-ID", DeviceIdentity(context).id)
            .header("X-PhotoSync-Server-PIN", prefs.getString("server_pin", "").orEmpty())
    }
    return builder
}

private fun postIfActivityAlive(context: Context, action: () -> Unit) {
    val activity = context as? Activity ?: return
    activity.runOnUiThread {
        if (!activity.isFinishing && !activity.isDestroyed) action()
    }
}

private object ToastCompat {
    fun show(context: Context, message: String) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun dp(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).toInt()
