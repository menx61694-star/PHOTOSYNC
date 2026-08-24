package com.photosync.uploader

import android.content.Context
import java.util.UUID

/**
 * Persistent private identity for this PhotoSync app installation.
 * It is deliberately not based on IMEI, phone number, or LAN IP.
 */
class DeviceIdentity(context: Context) {
    companion object {
        private const val PREFS = "photosync"
        private const val KEY_DEVICE_ID = "device_id"
    }

    val id: String

    init {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        id = if (!existing.isNullOrBlank()) {
            existing
        } else {
            val generated = "device_${UUID.randomUUID()}"
            prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
            generated
        }
    }
}
