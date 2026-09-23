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
    private val startServer = Button(context)
    private val stopServer = Button(context)
    @Volatile private var serverExecutor: ExecutorService? = null
    @Volatile private var attached = false
    @Volatile private var startRequested = false

    interface Listener {
        fun onEmbeddedStartRequested()
        fun onEmbeddedStopRequested()
    }
    private var listener: Listener? = null
    fun setListener(value: Listener?) { listener = value }

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
        startServer.text = "Start Embedded"
        stopServer.text = "Stop Embedded"
        startServer.setOnClickListener { listener?.onEmbeddedStartRequested() }
        stopServer.setOnClickListener { listener?.onEmbeddedStopRequested() }
        refreshPin.setOnClickListener {
            val app = context.applicationContext as? PhotoSyncApplication ?: return@setOnClickListener
            if (!app.localServer.isRunning()) return@setOnClickListener
            refreshPin.isEnabled = false
            executeServerTask {
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
        val serverButtons = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(startServer, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(stopServer, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 8 }
        }
        addView(status)
        addView(address)
        addView(serverButtons)
        addView(row)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        val app = context.applicationContext as? PhotoSyncApplication
        if (app != null) {
            if (serverExecutor == null || serverExecutor?.isShutdown == true) serverExecutor = Executors.newSingleThreadExecutor()
            refreshInfoAsync()
        }
        handler.removeCallbacks(refresh)
        handler.post(refresh)
        startServer.isEnabled = true
        stopServer.isEnabled = true
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
        executeServerTask {
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
                    startServer.isEnabled = true
                    stopServer.isEnabled = false
                } else {
                    status.text = "● Local Server: Running"
                    address.text = "Web: ${state.first ?: "Waiting for network…"}"
                    pin.text = "Pairing PIN: ${state.second}"
                    refreshPin.isEnabled = true
                    startServer.isEnabled = false
                    stopServer.isEnabled = true
                }
            }
        }
    }

    private fun executeServerTask(task: () -> Unit) {
        if (!attached) return
        val executor = serverExecutor
        if (executor == null || executor.isShutdown || executor.isTerminated) return
        try {
            executor.execute(task)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // The view may have detached/re-attached while a refresh was queued.
            // Never let executor lifecycle races crash the Activity.
        }
    }
}
