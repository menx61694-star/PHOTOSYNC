package com.photosync.uploader

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class LocalServerInfoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private val handler = Handler(Looper.getMainLooper())
    private val status = TextView(context)
    private val address = TextView(context)
    private val pin = TextView(context)
    private val refreshPin = Button(context)
    private val serverExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var attached = false
    @Volatile private var startRequested = false
    @Volatile private var discoveryTriggered = false

    private val refresh = object : Runnable {
        override fun run() {
            if (!attached) return
            updateInfo()
            handler.postDelayed(this, 1000)
        }
    }

    init {
        orientation = VERTICAL
        setPadding(18, 18, 18, 18)
        setBackgroundColor(Color.rgb(23, 34, 53))
        status.setTextColor(Color.WHITE)
        status.textSize = 16f
        address.setTextColor(Color.rgb(185, 199, 217))
        address.textSize = 14f
        pin.setTextColor(Color.WHITE)
        pin.textSize = 22f
        pin.setPadding(0, 8, 0, 0)
        refreshPin.text = "Refresh PIN"
        refreshPin.setOnClickListener {
            val app = context.applicationContext as? PhotoSyncApplication ?: return@setOnClickListener
            if (!app.localServer.isRunning()) return@setOnClickListener
            refreshPin.isEnabled = false
            serverExecutor.execute {
                try { app.localServer.refreshPin() } catch (_: Throwable) { }
                handler.post { if (attached) updateInfo() }
            }
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(pin, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(refreshPin, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        addView(status)
        addView(address)
        addView(row)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        val app = context.applicationContext as? PhotoSyncApplication
        if (app != null && !app.localServer.isRunning() && !startRequested) {
            startRequested = true
            status.text = "● Local Server: Starting…"
            refreshPin.isEnabled = false
            serverExecutor.execute {
                val started = try { app.localServer.start() } catch (_: Throwable) { false }
                handler.post {
                    startRequested = false
                    if (!attached) return@post
                    if (started) updateInfo() else {
                        status.text = "● Local Server: Not running"
                        address.text = "Web address: unavailable"
                        pin.text = "PIN: —"
                        refreshPin.isEnabled = false
                    }
                }
            }
        }
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onDetachedFromWindow() {
        attached = false
        handler.removeCallbacks(refresh)
        super.onDetachedFromWindow()
    }

    private fun clearEmbeddedUrlFromTransferTarget(server: LocalServer) {
        val activity = context as? Activity ?: return
        val localUrl = server.url()?.removeSuffix("/") ?: return
        val prefs = activity.getSharedPreferences("photosync", Context.MODE_PRIVATE)
        val saved = prefs.getString("server_url", "")?.trim()?.removeSuffix("/") ?: ""
        if (saved != localUrl) return

        // The embedded Android server is a receiver/browser endpoint, not the
        // PC transfer target. Never let the app accidentally select itself as
        // the destination for Send Files (that was the source of HTTP 401).
        prefs.edit().remove("server_url").apply()
        activity.findViewById<EditText>(R.id.serverUrlInput)?.setText("")

        // MainActivity can run its initial connection logic just before or
        // after this view's asynchronous server startup. Retry discovery on a
        // short cooldown so a race cannot put the embedded :18000 URL back.
        if (!discoveryTriggered) {
            discoveryTriggered = true
            activity.findViewById<Button>(R.id.findServerButton)?.performClick()
            handler.postDelayed({ discoveryTriggered = false }, 4000)
        }
    }

    private fun updateInfo() {
        if (!attached) return
        val app = context.applicationContext as? PhotoSyncApplication ?: return
        val server = app.localServer
        if (!server.isRunning()) {
            status.text = "● Local Server: Not running"
            address.text = "Web address: unavailable"
            pin.text = "PIN: —"
            refreshPin.isEnabled = false
            return
        }
        status.text = "● Local Server: Running"
        address.text = "Web: ${server.url() ?: "Waiting for network…"}"
        pin.text = "Pairing PIN: ${server.currentPin()}"
        refreshPin.isEnabled = true
        clearEmbeddedUrlFromTransferTarget(server)
    }
}
