package com.photosync.uploader

import android.content.Context
import java.util.UUID

/**
 * Persistent private identity for this PhotoSync app installation.
 * It is deliberately not based on IMEI, phone number, or LAN IP.
 */
object DeviceIdentity {
    private const val PREFS = "photosync"
    private const val KEY_DEVICE_ID = "device_id"

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing

        val id = "device_${UUID.randomUUID()}"
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }
}
