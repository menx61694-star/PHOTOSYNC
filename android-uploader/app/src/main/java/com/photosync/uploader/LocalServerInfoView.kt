package com.photosync.uploader

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.widget.Button
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

    private val refresh = object : Runnable {
        override fun run() {
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
            serverExecutor.execute {
                try {
                    if (app.localServer.isRunning()) app.localServer.refreshPin()
                } finally {
                    handler.post { updateInfo() }
                }
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
        val app = context.applicationContext as? PhotoSyncApplication
        if (app != null && !app.localServer.isRunning()) {
            status.text = "● Local Server: Starting…"
            refreshPin.isEnabled = false
            serverExecutor.execute {
                val started = try { app.localServer.start() } catch (_: Throwable) { false }
                handler.post {
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
        handler.removeCallbacks(refresh)
        serverExecutor.shutdownNow()
        super.onDetachedFromWindow()
    }

    private fun updateInfo() {
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
    }
}
