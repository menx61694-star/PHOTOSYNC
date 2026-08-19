package com.photosync.uploader

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout

/** Keeps active transfer rows during the periodic file-list refresh. */
class TransferContainerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    override fun removeAllViews() {
        val progressRows = (0 until childCount)
            .map { getChildAt(it) }
            .filter { containsProgressBar(it) }

        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)
            if (child !in progressRows) removeViewAt(i)
        }
    }

    override fun removeAllViewsInLayout() {
        removeAllViews()
    }

    private fun containsProgressBar(view: View): Boolean =
        view.findViewWithTag<View>("progress_bar") != null
}
