package com.photosync.uploader

import android.app.Application

class PhotoSyncApplication : Application() {
    lateinit var localServer: LocalServer
        private set

    override fun onCreate() {
        super.onCreate()
        localServer = LocalServer(this, 18000)
        localServer.start()
    }

    override fun onTerminate() {
        if (::localServer.isInitialized) localServer.stop()
        super.onTerminate()
    }
}
