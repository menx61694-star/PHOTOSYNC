package com.photosync.uploader

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout

/** Explicit Connect/Disconnect controls. The Activity owns connection state. */
class ServerConnectionControls @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    interface Listener {
        fun onConnectRequested()
        fun onDisconnectRequested()
    }

    private var listener: Listener? = null

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        val gap = (8 * resources.displayMetrics.density).toInt()

        val connect = Button(context).apply {
            text = "Connect"
            isAllCaps = false
            setOnClickListener { listener?.onConnectRequested() }
        }
        val disconnect = Button(context).apply {
            text = "Disconnect"
            isAllCaps = false
            setOnClickListener { listener?.onDisconnectRequested() }
        }
        addView(connect, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(disconnect, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = gap
        })
    }
}
