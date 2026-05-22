package com.streamforge.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Helper for requesting camera and audio permissions.
 * Phase 2A: Camera preview requires both CAMERA and RECORD_AUDIO.
 */
object PermissionHelper {

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    /**
     * Check if both camera and audio permissions are granted.
     */
    fun hasCameraAndAudio(ctx: Context): Boolean {
        return REQUIRED_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Request camera and audio permissions.
     * Must be called before onCreate completes (during initialization).
     *
     * @param activity The ComponentActivity requesting permissions
     * @param onResult Callback with true if all permissions granted, false otherwise
     */
    fun requestCameraAndAudio(
        activity: ComponentActivity,
        onResult: (granted: Boolean) -> Unit
    ) {
        val launcher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            onResult(allGranted)
        }
        launcher.launch(REQUIRED_PERMISSIONS)
    }
}
