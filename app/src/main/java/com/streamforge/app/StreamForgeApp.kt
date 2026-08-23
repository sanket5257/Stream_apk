package com.streamforge.app

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.streamforge.app.auth.AuthManager
import com.streamforge.app.auth.AuthResult
import com.streamforge.app.util.CrashReporter
import com.streamforge.app.util.safeLaunch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Application class. Single instance for the whole process.
 * Hook for one-time init (logging, crash reporting, etc.) as the project grows.
 */
class StreamForgeApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // First thing, before anything can throw: record uncaught crashes so a field failure
        // leaves evidence instead of just bouncing the user to the launcher.
        CrashReporter.install(this)

        // Apply the user's saved theme (Profile -> Theme) before any UI shows.
        val mode = try {
            getSharedPreferences("ui_prefs", MODE_PRIVATE)
                .getInt(ProfileActivity.KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        } catch (t: Throwable) {
            Log.w(TAG, "Reading saved theme failed; using system default", t)
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)

        // Perform periodic auth validation on app start
        validateAuthOnStartup()
    }

    /**
     * Validate the stored session in the background.
     *
     * Everything here — including constructing [AuthManager], which is what first touches the
     * Supabase/ktor client — runs off the main thread. Building that client eagerly on the main
     * thread during onCreate added startup latency to every cold start, and a misconfigured or
     * unreachable backend threw out of a static initializer, which is unrecoverable: the app
     * couldn't launch at all.
     */
    private fun validateAuthOnStartup() {
        ProcessLifecycleOwner.get().lifecycleScope.safeLaunch(TAG, "startup auth validation") {
            val result = withContext(Dispatchers.IO) {
                val authManager = AuthManager(this@StreamForgeApp)
                if (!authManager.isAuthenticated()) null else authManager.validateAuth()
            } ?: return@safeLaunch

            when (result) {
                is AuthResult.Success -> Log.d(TAG, "Auth validation successful")
                is AuthResult.Error -> Log.w(TAG, "Auth validation failed: ${result.message}")
            }
        }
    }

    private companion object {
        const val TAG = "StreamForgeApp"
    }
}
