package com.photosync.uploader

import android.app.Application
import java.util.concurrent.Executors

class PhotoSyncApplication : Application() {
    lateinit var localServer: LocalServer
        private set

    private val serverExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        localServer = LocalServer(this, 18000)
        // ServerSocket.bind() must not run on the Android main thread.
        // Starting it in the application lifecycle was causing startup crashes
        // on newer Android versions when the framework enforced network I/O
        // restrictions more strictly.
        serverExecutor.execute {
            localServer.start()
        }
    }

    override fun onTerminate() {
        if (::localServer.isInitialized) localServer.stop()
        serverExecutor.shutdownNow()
        super.onTerminate()
    }
}
