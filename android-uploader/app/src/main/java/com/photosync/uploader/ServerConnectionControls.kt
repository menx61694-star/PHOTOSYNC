package com.photosync.uploader

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Explicit Connect/Disconnect controls for the server selected in MainActivity. */
class ServerConnectionControls @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        val gap = (8 * resources.displayMetrics.density).toInt()

        val connect = Button(context).apply {
            text = "Connect"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setOnClickListener { changeConnection(true) }
        }
        val disconnect = Button(context).apply {
            text = "Disconnect"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setOnClickListener { changeConnection(false) }
        }
        addView(connect, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(disconnect, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = gap })
    }

    private fun changeConnection(connect: Boolean) {
        val host = context
        try {
            val prefs = host.getSharedPreferences("photosync", Context.MODE_PRIVATE)
            val urlField = host.javaClass.getDeclaredField("serverUrlInput").apply { isAccessible = true }
            val url = (urlField.get(host) as? android.widget.EditText)?.text?.toString()?.trim()?.removeSuffix("/") ?: ""
            val statusField = host.javaClass.getDeclaredField("serverStatus").apply { isAccessible = true }
            val status = statusField.get(host) as? TextView
            val titleField = host.javaClass.getDeclaredField("status").apply { isAccessible = true }
            val title = titleField.get(host) as? TextView
            val startedField = host.javaClass.getDeclaredField("started").apply { isAccessible = true }
            val socketField = host.javaClass.getDeclaredField("socket").apply { isAccessible = true }

            if (!connect) {
                startedField.setBoolean(host, false)
                (socketField.get(host) as? okhttp3.WebSocket)?.cancel()
                socketField.set(host, null)
                status?.text = "● Server: Disconnected"
                title?.text = "Server disconnected — choose another server or tap Connect"
                return
            }

            if (url.isBlank()) {
                title?.text = "Enter a server URL first"
                return
            }
            prefs.edit().putString("server_url", url).apply()
            (host.javaClass.getDeclaredField("serverUrlInput").apply { isAccessible = true }.get(host) as? android.widget.EditText)?.setText(url)
            startedField.setBoolean(host, true)
            val reconnect = host.javaClass.getDeclaredMethod("reconnectSocket").apply { isAccessible = true }
            reconnect.invoke(host)
            status?.text = "● Server: Connecting…"
            title?.text = "Connecting to selected server…"
        } catch (_: Exception) {
            val statusId = resources.getIdentifier("statusText", "id", host.packageName)
            (host as? android.app.Activity)?.findViewById<TextView>(statusId)?.text = "Connection control failed"
        }
    }
}
