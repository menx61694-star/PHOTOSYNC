package com.photosync.uploader

import android.content.Context

class LocalServer(context: Context, port: Int = 18000) {
    private val delegate = LocalWebServer(context, port)
    fun start(): Boolean = delegate.start()
    fun stop() = delegate.stop()
    fun refreshPin(): String = delegate.refreshPin()
    fun isRunning(): Boolean = delegate.isRunning()
    fun currentPin(): String = delegate.currentPin()
    fun isAuthorized(token: String?): Boolean = delegate.isAuthorized(token)
    fun localIpv4(): String? = delegate.localIpv4()
    fun url(): String? = delegate.url()
}
