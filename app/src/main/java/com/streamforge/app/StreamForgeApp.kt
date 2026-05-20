package com.streamforge.app

import android.app.Application

/**
 * Application class. Single instance for the whole process.
 * Hook for one-time init (logging, crash reporting, etc.) as the project grows.
 */
class StreamForgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
