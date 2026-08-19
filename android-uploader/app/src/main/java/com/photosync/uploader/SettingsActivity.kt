package com.photosync.uploader

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("photosync", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(Color.rgb(8, 18, 34))
        }
        setContentView(root)
        root.addView(TextView(this).apply { text = "Settings"; textSize = 28f; setTextColor(Color.WHITE); setPadding(0, 0, 0, dp(20)) })
        root.addView(settingButton("Home") { startActivity(Intent(this, MainActivity::class.java)); finish() })
        root.addView(settingButton("Theme colour") { chooseTheme() })
        root.addView(settingButton("Connection / Server") { startActivity(Intent(this, MainActivity::class.java)); finish() })
        root.addView(settingButton("Clear saved server") { prefs.edit().remove("server_url").apply(); Toast.makeText(this, "Saved server cleared", Toast.LENGTH_SHORT).show() })
        root.addView(settingButton("Feedback") { startActivity(Intent(this, FeedbackActivity::class.java)) })
        root.addView(settingButton("Account") { startActivity(Intent(this, AccountActivity::class.java)) })
        root.addView(settingButton("About PhotoSync") { Toast.makeText(this, "PhotoSync • Local & Private", Toast.LENGTH_SHORT).show() })
        if (intent.getBooleanExtra("open_theme", false)) chooseTheme()
    }

    private fun chooseTheme() {
        val colours = arrayOf("Default", "Purple", "Cyan", "Green", "Orange")
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Theme colour").setItems(colours) { _, which ->
            val value = when (which) { 1 -> "#BDA4FF"; 2 -> "#18C7E8"; 3 -> "#21C978"; 4 -> "#FF9F43"; else -> "#BDA4FF" }
            prefs.edit().putString("theme_color", value).apply()
            Toast.makeText(this, "Theme colour saved. Return to Home to apply.", Toast.LENGTH_SHORT).show()
        }.show()
    }

    private fun settingButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 16f; isAllCaps = false; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setOnClickListener { action() }; setPadding(dp(12), 0, dp(12), 0)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
