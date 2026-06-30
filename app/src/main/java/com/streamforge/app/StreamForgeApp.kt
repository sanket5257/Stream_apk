package com.streamforge.app

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.streamforge.app.auth.AuthManager
import com.streamforge.app.auth.AuthResult
import kotlinx.coroutines.launch

/**
 * Application class. Single instance for the whole process.
 * Hook for one-time init (logging, crash reporting, etc.) as the project grows.
 */
class StreamForgeApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Apply the user's saved theme (Profile -> Theme) before any UI shows.
        val mode = getSharedPreferences("ui_prefs", MODE_PRIVATE)
            .getInt(ProfileActivity.KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(mode)

        // Perform periodic auth validation on app start
        validateAuthOnStartup()
    }
    
    private fun validateAuthOnStartup() {
        val authManager = AuthManager(this)
        
        if (!authManager.isAuthenticated()) {
            return
        }
        
        // Validate in background
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            when (val result = authManager.validateAuth()) {
                is AuthResult.Success -> {
                    Log.d("StreamForgeApp", "Auth validation successful")
                }
                is AuthResult.Error -> {
                    Log.w("StreamForgeApp", "Auth validation failed: ${result.message}")
                }
            }
        }
    }
}
