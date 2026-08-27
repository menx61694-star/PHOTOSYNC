package com.photosync.uploader

import android.app.Application

class PhotoSyncApplication : Application() {
    lateinit var localServer: LocalServer
        private set

    override fun onCreate() {
        super.onCreate()
        localServer = LocalServer(this, 18000)

        // Start the embedded Android server off the main/UI thread.  The
        // server itself catches bind/start failures, so a port/network
        // problem must never crash application startup.
        Thread {
            try {
                localServer.start()
            } catch (_: Throwable) {
                // Keep the app usable even if the LAN server cannot start.
            }
        }.start()
    }

    override fun onTerminate() {
        if (::localServer.isInitialized) localServer.stop()
        super.onTerminate()
    }
}
