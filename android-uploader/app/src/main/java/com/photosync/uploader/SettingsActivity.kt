package com.photosync.uploader

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("photosync", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(38), dp(24), dp(38), dp(24))
            setBackgroundColor(Color.rgb(8, 18, 34))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "Settings"
            textSize = 28f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(24))
        })

        root.addView(settingButton("Home", true) { startActivity(Intent(this, MainActivity::class.java)); finish() })
        root.addView(settingButton("Theme colour", false) { chooseTheme() })
        root.addView(settingButton("Connection / Server", false) { startActivity(Intent(this, MainActivity::class.java)); finish() })
        root.addView(settingButton("Clear saved server", false) {
            prefs.edit().remove("server_url").apply()
            Toast.makeText(this, "Saved server cleared", Toast.LENGTH_SHORT).show()
        })
        root.addView(settingButton("Feedback", false) { startActivity(Intent(this, FeedbackActivity::class.java)) })
        root.addView(settingButton("Report a Bug", false) {
            startActivity(Intent(this, FeedbackActivity::class.java).putExtra("mode", "bug"))
        })
        root.addView(settingButton("Account", false) { startActivity(Intent(this, AccountActivity::class.java)) })
        root.addView(settingButton("About Pixel Forge", false) { showAbout() })

        if (intent.getBooleanExtra("open_theme", false)) chooseTheme()
    }

    private fun showAbout() {
        val about = """
            Pixel Forge
            Independent Technology Studio

            Pixel Forge is an independent technology studio focused on building thoughtful, practical and privacy-conscious digital products. We believe good software should feel simple on the surface while being carefully engineered underneath.

            PhotoSync is built around that idea: fast local file sharing, a clean experience, and keeping your files under your control.

            Built with curiosity, crafted with purpose.

            — Pixel Forge
        """.trimIndent()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("About Pixel Forge")
            .setMessage(about)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun chooseTheme() {
        val colours = arrayOf("Default", "Purple", "Cyan", "Green", "Orange")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Theme colour")
            .setItems(colours) { _, which ->
                val value = when (which) {
                    1 -> "#BDA4FF"
                    2 -> "#18C7E8"
                    3 -> "#21C978"
                    4 -> "#FF9F43"
                    else -> "#BDA4FF"
                }
                prefs.edit().putString("theme_color", value).apply()
                Toast.makeText(this, "Theme colour saved. Return to Home to apply.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun settingButton(label: String, primary: Boolean, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 16f
        isAllCaps = false
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = getDrawable(if (primary) com.photosync.uploader.R.drawable.bg_send_button else com.photosync.uploader.R.drawable.bg_outline_button)
        setPadding(dp(12), 0, dp(12), 0)
        layoutParams = LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(12) }
        setOnClickListener { action() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
