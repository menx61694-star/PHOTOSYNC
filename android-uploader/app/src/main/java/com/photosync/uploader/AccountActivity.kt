package com.photosync.uploader

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AccountActivity : AppCompatActivity() {
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val prefs by lazy { getSharedPreferences("photosync", MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private lateinit var title: TextView
    private lateinit var name: EditText
    private lateinit var mobile: EditText
    private lateinit var username: EditText
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var action: Button
    private lateinit var modeToggle: Button
    private var signup = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(38), dp(24), dp(38), dp(24))
            setBackgroundColor(Color.rgb(8, 18, 34))
        }
        setContentView(root)

        title = TextView(this).apply {
            text = "Create PhotoSync account"
            textSize = 28f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(24))
        }
        root.addView(title)

        name = field("Full name")
        mobile = field("Mobile number")
        username = field("Username (mandatory)")
        email = field("Email")
        password = field("Password (minimum 8 characters)")
        password.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD

        root.addView(name)
        root.addView(mobile)
        root.addView(username)
        root.addView(email)
        root.addView(password)

        action = primaryButton("Create Account")
        root.addView(action)

        modeToggle = secondaryButton("Already have an account? Log in")
        modeToggle.setOnClickListener { signup = !signup; refreshMode() }
        root.addView(modeToggle)

        refreshMode()
    }

    private fun refreshMode() {
        title.text = if (signup) "Create PhotoSync account" else "Log in to PhotoSync"
        action.text = if (signup) "Create Account" else "Log In"
        name.visibility = if (signup) View.VISIBLE else View.GONE
        mobile.visibility = if (signup) View.VISIBLE else View.GONE
        username.visibility = if (signup) View.VISIBLE else View.GONE
        modeToggle.text = if (signup) "Already have an account? Log in" else "Need an account? Sign up"
    }

    private fun submit() {
        val server = prefs.getString("server_url", "")?.trim()?.removeSuffix("/") ?: ""
        if (server.isBlank()) {
            Toast.makeText(this, "Connect to a PhotoSync server first", Toast.LENGTH_LONG).show()
            return
        }
        val emailText = email.text.toString().trim()
        val pass = password.text.toString()
        if (emailText.isBlank() || pass.length < 8) {
            Toast.makeText(this, "Enter email and an 8+ character password", Toast.LENGTH_SHORT).show()
            return
        }
        if (signup && (name.text.toString().trim().isBlank() || mobile.text.toString().trim().isBlank() || username.text.toString().trim().isBlank())) {
            Toast.makeText(this, "Name, mobile and username are mandatory", Toast.LENGTH_SHORT).show()
            return
        }

        Thread {
            try {
                val form = FormBody.Builder().add("email", emailText).add("password", pass)
                if (signup) {
                    form.add("name", name.text.toString().trim())
                        .add("mobile", mobile.text.toString().trim())
                        .add("username", username.text.toString().trim())
                }
                val endpoint = if (signup) "/account/signup" else "/account/login"
                val response = client.newCall(Request.Builder().url(server + endpoint).post(form.build()).build()).execute()
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IllegalStateException(JSONObject(body).optString("detail", "Request failed"))
                val json = JSONObject(body)
                val account = json.optJSONObject("account")
                prefs.edit()
                    .putString("account_name", account?.optString("name", "") ?: "")
                    .putString("account_username", account?.optString("username", "") ?: "")
                    .putString("account_email", account?.optString("email", emailText) ?: emailText)
                    .putBoolean("account_logged_in", true)
                    .apply()

                runOnUiThread {
                    Toast.makeText(this, if (signup) "Account created ✓" else "Login successful ✓", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Account failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun field(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        setTextColor(Color.WHITE)
        setHintTextColor(Color.rgb(145, 158, 177))
        setSingleLine(true)
        background = getDrawable(com.photosync.uploader.R.drawable.bg_input)
        setPadding(dp(14), 0, dp(14), 0)
        layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(12) }
    }

    private fun primaryButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 16f
        isAllCaps = false
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = getDrawable(com.photosync.uploader.R.drawable.bg_send_button)
        setPadding(dp(12), 0, dp(12), 0)
        layoutParams = LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(4); bottomMargin = dp(10) }
    }

    private fun secondaryButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 15f
        isAllCaps = false
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = getDrawable(com.photosync.uploader.R.drawable.bg_outline_button)
        setPadding(dp(12), 0, dp(12), 0)
        layoutParams = LinearLayout.LayoutParams(-1, dp(52))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
