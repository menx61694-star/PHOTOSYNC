package com.photosync.uploader

import android.app.Application

class PhotoSyncApplication : Application() {
    lateinit var localServer: LocalServer
        private set

    override fun onCreate() {
        super.onCreate()
        // Only create the server here. Do NOT bind the ServerSocket during
        // Application startup. Server startup is owned by the Activity so a
        // server/network failure can never crash the app process at launch.
        localServer = LocalServer(this, 18000)
    }

    override fun onTerminate() {
        if (::localServer.isInitialized) localServer.stop()
        super.onTerminate()
    }
}
