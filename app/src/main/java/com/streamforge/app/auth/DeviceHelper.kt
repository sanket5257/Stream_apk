package com.streamforge.app.auth

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

/**
 * Helper to generate a unique, stable device identifier.
 * Uses Android ID + device fingerprint for uniqueness.
 */
object DeviceHelper {
    
    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        
        // Create a fingerprint from device characteristics
        val fingerprint = "${Build.MANUFACTURER}-${Build.MODEL}-${Build.DEVICE}-$androidId"
        
        // Hash it for privacy and consistency
        return hashString(fingerprint)
    }
    
    fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer)) {
            model
        } else {
            "$manufacturer $model"
        }
    }
    
    fun getDeviceModel(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
    }
    
    private fun hashString(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .fold("") { str, byte -> str + "%02x".format(byte) }
            .take(32) // Use first 32 chars
    }
    
    /**
     * Generate a random license key (for testing/admin purposes).
     * Format: XXXX-XXXX-XXXX-XXXX
     */
    fun generateLicenseKey(): String {
        val uuid = UUID.randomUUID().toString().replace("-", "").uppercase()
        return uuid.chunked(4).take(4).joinToString("-")
    }
}
