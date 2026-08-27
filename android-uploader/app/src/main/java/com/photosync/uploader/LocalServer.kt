package com.photosync.uploader

import android.content.Context

/**
 * Safe facade around the embedded HTTP server.
 *
 * The server implementation must never be allowed to take down the Android
 * process during Application/Activity startup. Construction is therefore
 * lazy and failures are converted into an unavailable-server state.
 */
class LocalServer(context: Context, port: Int = 18000) {
    private val delegate: LocalWebServer? by lazy {
        try {
            LocalWebServer(context.applicationContext, port)
        } catch (_: Throwable) {
            null
        }
    }

    fun start(): Boolean = try {
        delegate?.start() == true
    } catch (_: Throwable) {
        false
    }

    fun stop() {
        try { delegate?.stop() } catch (_: Throwable) { }
    }

    fun refreshPin(): String = try {
        delegate?.refreshPin() ?: ""
    } catch (_: Throwable) {
        ""
    }

    fun isRunning(): Boolean = try {
        delegate?.isRunning() == true
    } catch (_: Throwable) {
        false
    }

    fun currentPin(): String = try {
        delegate?.currentPin() ?: ""
    } catch (_: Throwable) {
        ""
    }

    fun isAuthorized(token: String?): Boolean = try {
        delegate?.isAuthorized(token) == true
    } catch (_: Throwable) {
        false
    }

    fun localIpv4(): String? = try {
        delegate?.localIpv4()
    } catch (_: Throwable) {
        null
    }

    fun url(): String? = try {
        delegate?.url()
    } catch (_: Throwable) {
        null
    }
}
