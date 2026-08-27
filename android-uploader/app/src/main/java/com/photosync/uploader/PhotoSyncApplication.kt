package com.photosync.uploader

import android.app.Application

class PhotoSyncApplication : Application() {
    lateinit var localServer: LocalServer
        private set

    override fun onCreate() {
        super.onCreate()
        // Only create the server here. Do not bind the ServerSocket during
        // Application startup. MainActivity starts it after the UI exists.
        localServer = LocalServer(this, 18000)
    }

    override fun onTerminate() {
        if (::localServer.isInitialized) localServer.stop()
        super.onTerminate()
    }
}
