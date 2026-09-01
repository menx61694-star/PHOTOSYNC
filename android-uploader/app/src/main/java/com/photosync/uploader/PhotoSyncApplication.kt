package com.photosync.uploader

import android.app.Application

class PhotoSyncApplication : Application() {
    lateinit var localServer: LocalServer
        private set

    override fun onCreate() {
        super.onCreate()
        localServer = LocalServer(this, 18000)
        // LocalServer safely converts startup failures into an unavailable
        // server state, so the embedded server can start with the app without
        // taking down the Android process.
        localServer.start()
    }

    override fun onTerminate() {
        if (::localServer.isInitialized) localServer.stop()
        super.onTerminate()
    }
}
