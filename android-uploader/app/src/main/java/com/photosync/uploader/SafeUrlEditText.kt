package com.photosync.uploader

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText

class SafeUrlEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatEditText(context, attrs) {
    override fun onTextContextMenuItem(id: Int): Boolean {
        return try { super.onTextContextMenuItem(id) } catch (_: Throwable) { true }
    }
}
