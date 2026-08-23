package com.streamforge.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Helper for requesting camera and audio permissions.
 * Phase 2A: Camera preview requires both CAMERA and RECORD_AUDIO.
 */
object PermissionHelper {

    /**
     * Permissions the camera pipeline needs. Public so callers can register their own
     * launcher as an activity FIELD.
     *
     * This deliberately no longer offers a "request" helper that registers a launcher on
     * demand: `registerForActivityResult` throws IllegalStateException ("LifecycleOwners must
     * call register before they are STARTED") whenever it runs after the activity has started,
     * which made any late or repeat permission request a crash. Registering at field-init time
     * is the only form the Activity Result API actually supports.
     */
    val REQUIRED_PERMISSIONS = arrayOf(
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
}
