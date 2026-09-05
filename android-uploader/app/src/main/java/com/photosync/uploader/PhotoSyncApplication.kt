package com.photosync.uploader

import android.app.Application

class PhotoSyncApplication : Application() {
    lateinit var localServer: LocalServer
        private set

    override fun onCreate() {
        super.onCreate()
        // Create the embedded server here, but let LocalServerInfoView own startup.
        // This keeps socket binding out of Application startup.
        localServer = LocalServer(this, 18000)
    }

    override fun onTerminate() {
        if (::localServer.isInitialized) localServer.stop()
        super.onTerminate()
    }
}
