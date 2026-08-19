package com.photosync.uploader

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.view.Menu
import android.view.View
import android.widget.PopupMenu
import androidx.appcompat.widget.AppCompatImageButton

class MenuButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageButton(context, attrs, defStyleAttr) {
    init {
        setOnClickListener { showMenu(it) }
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(context, anchor)
        popup.menu.add(Menu.NONE, 1, 1, "Home")
        popup.menu.add(Menu.NONE, 2, 2, "Settings")
        popup.menu.add(Menu.NONE, 3, 3, "Theme colour")
        popup.menu.add(Menu.NONE, 4, 4, "Feedback")
        popup.menu.add(Menu.NONE, 5, 5, "Account")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    if (context !is MainActivity) context.startActivity(Intent(context, MainActivity::class.java))
                    true
                }
                2 -> {
                    context.startActivity(Intent(context, SettingsActivity::class.java))
                    true
                }
                3 -> {
                    context.startActivity(Intent(context, SettingsActivity::class.java).putExtra("open_theme", true))
                    true
                }
                4 -> {
                    context.startActivity(Intent(context, FeedbackActivity::class.java))
                    true
                }
                5 -> {
                    context.startActivity(Intent(context, AccountActivity::class.java))
                    true
                }
                else -> false
            }
        }
        popup.show()
    }
}
