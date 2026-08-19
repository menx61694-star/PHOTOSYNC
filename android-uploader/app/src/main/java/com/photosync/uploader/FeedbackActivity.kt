package com.photosync.uploader

import android.graphics.Color
import android.os.Build
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
    private var isBugReport = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isBugReport = intent.getStringExtra("mode") == "bug"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(Color.rgb(8, 18, 34))
        }
        setContentView(root)

        root.addView(TextView(this).apply {
            text = if (isBugReport) "Report a Bug" else "Feedback"
            textSize = 28f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(12))
        })
        root.addView(TextView(this).apply {
            text = if (isBugReport) {
                "Found something that is not working correctly? Tell us what happened so Pixel Forge can investigate it."
            } else {
                "Tell us what works, what does not, or what you want next."
            }
            textSize = 15f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, dp(16))
        })

        val message = EditText(this).apply {
            hint = if (isBugReport) {
                "Describe the bug, what you expected, and what actually happened…"
            } else {
                "Your feedback"
            }
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            gravity = android.view.Gravity.TOP
            minLines = 7
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(message, LinearLayout.LayoutParams(-1, dp(210)))

        if (isBugReport) {
            root.addView(TextView(this).apply {
                text = "Device: ${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE}"
                textSize = 12f
                setTextColor(Color.GRAY)
                setPadding(0, dp(10), 0, dp(10))
            })
        }

        root.addView(Button(this).apply {
            text = if (isBugReport) "Submit Bug Report" else "Send Feedback"
            isAllCaps = false
            setOnClickListener { submit(message.text.toString()) }
        })
    }

    private fun submit(text: String) {
        if (text.trim().isBlank()) {
            Toast.makeText(this, if (isBugReport) "Describe the bug first" else "Write some feedback first", Toast.LENGTH_SHORT).show()
            return
        }
        val server = prefs.getString("server_url", "")?.trim()?.removeSuffix("/") ?: ""
        if (server.isBlank()) {
            Toast.makeText(this, "Connect to a PhotoSync server first", Toast.LENGTH_LONG).show()
            return
        }

        Thread {
            try {
                val finalMessage = if (isBugReport) {
                    "[BUG REPORT]\n$text\n\nDevice: ${Build.MANUFACTURER} ${Build.MODEL}\nAndroid: ${Build.VERSION.RELEASE}"
                } else text.trim()
                val form = FormBody.Builder()
                    .add("message", finalMessage.trim())
                    .add("email", prefs.getString("account_email", "") ?: "")
                    .add("username", prefs.getString("account_username", "") ?: "")
                    .build()
                client.newCall(Request.Builder().url(server + "/feedback").post(form).build()).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                }
                runOnUiThread {
                    Toast.makeText(this, if (isBugReport) "Bug report sent ✓" else "Feedback sent ✓", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Submission failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
