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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LocalServerInfoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private val handler = Handler(Looper.getMainLooper())
    private val status = TextView(context)
    private val address = TextView(context)
    private val pin = TextView(context)
    private val refreshPin = Button(context)
    @Volatile private var serverExecutor: ExecutorService? = null
    @Volatile private var attached = false
    @Volatile private var startRequested = false

    private val refresh = object : Runnable {
        override fun run() {
            if (!attached) return
            refreshInfoAsync()
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
            serverExecutor?.execute {
                try { app.localServer.refreshPin() } catch (_: Throwable) { }
                handler.post { if (attached) refreshInfoAsync() }
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
        if (app != null && !startRequested) {
            if (serverExecutor == null || serverExecutor?.isShutdown == true) serverExecutor = Executors.newSingleThreadExecutor()
            startRequested = true
            status.text = "● Local Server: Starting…"
            refreshPin.isEnabled = false
            serverExecutor?.execute {
                val started = try {
                    if (app.localServer.isRunning()) true else app.localServer.start()
                } catch (_: Throwable) { false }
                handler.post {
                    startRequested = false
                    if (!attached) return@post
                    if (started) refreshInfoAsync() else {
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
        serverExecutor?.shutdownNow()
        serverExecutor = null
        super.onDetachedFromWindow()
    }

    private fun refreshInfoAsync() {
        if (!attached) return
        val app = context.applicationContext as? PhotoSyncApplication ?: return
        serverExecutor?.execute {
            val state = try {
                val server = app.localServer
                if (!server.isRunning()) null
                else Triple(server.url(), server.currentPin(), true)
            } catch (_: Throwable) { null }
            handler.post {
                if (!attached) return@post
                if (state == null) {
                    status.text = "● Local Server: Not running"
                    address.text = "Web address: unavailable"
                    pin.text = "PIN: —"
                    refreshPin.isEnabled = false
                } else {
                    status.text = "● Local Server: Running"
                    address.text = "Web: ${state.first ?: "Waiting for network…"}"
                    pin.text = "Pairing PIN: ${state.second}"
                    refreshPin.isEnabled = true
                }
            }
        }
    }
}
