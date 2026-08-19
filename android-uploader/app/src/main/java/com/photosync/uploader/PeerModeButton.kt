package com.photosync.uploader

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatButton

class PeerModeButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatButton(context, attrs, defStyleAttr) {
    init {
        setOnClickListener {
            context.startActivity(Intent(context, PeerActivity::class.java))
        }
    }
}
