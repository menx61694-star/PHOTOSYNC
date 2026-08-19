package com.photosync.uploader

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton

class MenuButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageButton(context, attrs, defStyleAttr) {

    init {
        setOnClickListener { showMenu(it) }
    }

    private fun showMenu(anchor: View) {
        val prefs = context.getSharedPreferences("photosync", Context.MODE_PRIVATE)
        val accent = parseColor(prefs.getString("theme_color", "#BDA4FF"))
        val accountName = prefs.getString("account_name", "")?.trim().orEmpty()
        val username = prefs.getString("account_username", "")?.trim().orEmpty()
        val email = prefs.getString("account_email", "")?.trim().orEmpty()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(0xFF111C2D.toInt(), 22)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(12))
        }
        val avatar = TextView(context).apply {
            val initial = if (accountName.isNotBlank()) accountName.first().uppercaseChar().toString() else "P"
            text = initial
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(accent, 50)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        header.addView(avatar)

        val identity = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        }
        identity.addView(TextView(context).apply {
            text = if (accountName.isNotBlank()) accountName else "Guest"
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        identity.addView(TextView(context).apply {
            text = when {
                username.isNotBlank() -> "@$username"
                email.isNotBlank() -> email
                else -> "Sign in or create an account"
            }
            textSize = 12f
            setTextColor(0xFF9AAAC0.toInt())
            maxLines = 1
        })
        header.addView(identity)
        root.addView(header)

        val divider = View(context).apply {
            setBackgroundColor(0xFF26354A.toInt())
            layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply {
                setMargins(dp(6), 0, dp(6), dp(8))
            }
        }
        root.addView(divider)

        addItem(root, "⌂", "Home", accent) {
            if (context !is MainActivity) context.startActivity(Intent(context, MainActivity::class.java))
        }
        addItem(root, "⚙", "Settings", accent) {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
        addItem(root, "◐", "Theme colour", accent) {
            context.startActivity(Intent(context, SettingsActivity::class.java).putExtra("open_theme", true))
        }
        addItem(root, "✉", "Feedback", accent) {
            context.startActivity(Intent(context, FeedbackActivity::class.java))
        }
        addItem(root, "●", "Account", accent) {
            context.startActivity(Intent(context, AccountActivity::class.java))
        }

        val popup = PopupWindow(root, dp(290), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(rounded(Color.TRANSPARENT, 22))
            elevation = dp(12).toFloat()
            isOutsideTouchable = true
        }
        popup.showAsDropDown(anchor, -dp(242), -dp(4))
    }

    private fun addItem(root: LinearLayout, icon: String, label: String, accent: Int, action: () -> Unit) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                action()
            }
            layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply {
                bottomMargin = dp(2)
            }
        }
        val iconView = TextView(context).apply {
            text = icon
            textSize = 20f
            setTextColor(accent)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
        }
        row.addView(iconView)
        row.addView(TextView(context).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply {
                marginStart = dp(6)
            }
        })
        root.addView(row)
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun parseColor(value: String?): Int = try {
        Color.parseColor(value ?: "#BDA4FF")
    } catch (_: Exception) {
        Color.parseColor("#BDA4FF")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
