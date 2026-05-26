package com.streamforge.app

import android.app.Application
import android.util.Log
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
