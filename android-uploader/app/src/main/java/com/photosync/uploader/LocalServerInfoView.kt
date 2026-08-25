package com.photosync.uploader

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
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
        pin.setTextColor(Color.rgb(255, 255, 255))
        pin.textSize = 22f
        pin.setPadding(0, 8, 0, 0)

        addView(status)
        addView(address)
        addView(pin)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(refresh)
        super.onDetachedFromWindow()
    }

    private fun updateInfo() {
        val app = context.applicationContext as? PhotoSyncApplication ?: return
        val server = app.localServer
        if (!server.isRunning()) {
            status.text = "● Local Server: Starting / stopped"
            address.text = "Web address: unavailable"
            pin.text = "PIN: —"
            return
        }
        status.text = "● Local Server: Running"
        address.text = "Web: ${server.url() ?: "Waiting for network…"}"
        pin.text = "Pairing PIN: ${server.currentPin()}"
    }
}
