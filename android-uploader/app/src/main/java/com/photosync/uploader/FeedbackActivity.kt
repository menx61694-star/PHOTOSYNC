package com.photosync.uploader

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class FeedbackActivity : AppCompatActivity() {
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val prefs by lazy { getSharedPreferences("photosync", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(Color.rgb(8, 18, 34))
        }
        setContentView(root)
        root.addView(TextView(this).apply { text = "Feedback"; textSize = 28f; setTextColor(Color.WHITE); setPadding(0, 0, 0, dp(12)) })
        root.addView(TextView(this).apply { text = "Tell us what works, what does not, or what you want next."; textSize = 15f; setTextColor(Color.LTGRAY); setPadding(0, 0, 0, dp(16)) })
        val message = EditText(this).apply {
            hint = "Your feedback"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            gravity = android.view.Gravity.TOP
            minLines = 6
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(message, LinearLayout.LayoutParams(-1, dp(180)))
        root.addView(Button(this).apply {
            text = "Send Feedback"
            isAllCaps = false
            setOnClickListener { submit(message.text.toString()) }
        })
    }

    private fun submit(text: String) {
        if (text.trim().isBlank()) { Toast.makeText(this, "Write some feedback first", Toast.LENGTH_SHORT).show(); return }
        val server = prefs.getString("server_url", "")?.trim()?.removeSuffix("/") ?: ""
        if (server.isBlank()) { Toast.makeText(this, "Connect to a PhotoSync server first", Toast.LENGTH_LONG).show(); return }
        Thread {
            try {
                val form = FormBody.Builder().add("message", text.trim()).add("email", prefs.getString("account_email", "") ?: "").add("username", prefs.getString("account_username", "") ?: "").build()
                client.newCall(Request.Builder().url(server + "/feedback").post(form).build()).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                }
                runOnUiThread { Toast.makeText(this, "Feedback sent ✓", Toast.LENGTH_SHORT).show(); finish() }
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "Feedback failed: ${e.message}", Toast.LENGTH_LONG).show() } }
        }.start()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
