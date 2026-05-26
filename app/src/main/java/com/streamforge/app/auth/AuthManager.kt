package com.streamforge.app.auth

import android.content.Context
import android.util.Log
import com.streamforge.app.auth.models.User
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Manages authentication with username/password and single device binding.
 */
class AuthManager(private val context: Context) {
    
    private val prefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
    private val supabase = SupabaseClient.client
    
    companion object {
        private const val TAG = "AuthManager"
        private const val PREF_USERNAME = "username"
        private const val PREF_USER_ID = "user_id"
        private const val PREF_DEVICE_ID = "device_id"
        private const val PREF_IS_AUTHENTICATED = "is_authenticated"
        private const val PREF_LAST_VALIDATION = "last_validation"
        private const val VALIDATION_INTERVAL_MS = 7 * 24 * 60 * 60 * 1000L // 7 days
    }
    
    /**
     * Check if user is authenticated locally.
     */
    fun isAuthenticated(): Boolean {
        return prefs.getBoolean(PREF_IS_AUTHENTICATED, false)
    }
    
    /**
     * Get stored username.
     */
    fun getUsername(): String? {
        return prefs.getString(PREF_USERNAME, null)
    }
    
    /**
     * Sign up new user with email, username, password, and invite code.
     */
    suspend fun signUp(email: String, username: String, password: String, inviteCode: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val deviceId = DeviceHelper.getDeviceId(context)
            val deviceName = DeviceHelper.getDeviceName()
            val deviceModel = DeviceHelper.getDeviceModel()
            
            Log.d(TAG, "Signing up user: $username")
            
            // 1. Validate invite code
            val inviteCodeManager = InviteCodeManager()
            val canonicalCode: String = when (val inviteResult = inviteCodeManager.validateInviteCode(inviteCode)) {
                is InviteCodeResult.Invalid -> {
                    return@withContext AuthResult.Error(inviteResult.message)
                }
                is InviteCodeResult.AlreadyUsed -> {
                    return@withContext AuthResult.Error(inviteResult.message)
                }
                is InviteCodeResult.Valid -> {
                    // Use the exact code stored in the DB (preserves casing)
                    inviteResult.inviteCode.code
                }
            }
            
            // 2. Check if email already exists
            val existingEmailUsers = supabase.from("users")
                .select {
                    filter {
                        eq("email", email)
                    }
                }
                .decodeList<User>()
            
            if (existingEmailUsers.isNotEmpty()) {
                return@withContext AuthResult.Error("Email already registered")
            }
            
            // 3. Check if username already exists
            val existingUsernameUsers = supabase.from("users")
                .select {
                    filter {
                        eq("username", username)
                    }
                }
                .decodeList<User>()
            
            if (existingUsernameUsers.isNotEmpty()) {
                return@withContext AuthResult.Error("Username already taken")
            }
            
            // 4. Create new user with device binding
            val now = Instant.now().toString()
            val newUser = com.streamforge.app.auth.models.NewUser(
                email = email,
                username = username,
                password = password,
                isActive = true,
                activeDeviceId = deviceId,
                deviceName = deviceName,
                deviceModel = deviceModel,
                lastLogin = now
            )
            
            val insertedUsers = supabase.from("users")
                .insert(newUser) {
                    select() // return the inserted row (Prefer: return=representation)
                }
                .decodeList<User>()
            
            if (insertedUsers.isEmpty()) {
                return@withContext AuthResult.Error("Failed to create account")
            }
            
            val user = insertedUsers.first()
            
            // 5. Mark invite code as used (use canonical DB code, not user input)
            val codeMarked = inviteCodeManager.markInviteCodeAsUsed(canonicalCode, user.id)
            if (!codeMarked) {
                Log.w(TAG, "Failed to mark invite code as used, but user was created")
            }
            
            saveAuthData(user.id, username, deviceId)
            
            Log.d(TAG, "Sign up successful")
            AuthResult.Success(user)
            
        } catch (e: Exception) {
            Log.e(TAG, "Sign up error", e)
            AuthResult.Error("Sign up failed: ${e.message}")
        }
    }
    
    /**
     * Authenticate with username and password, bind to current device.
     */
    suspend fun authenticate(username: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val deviceId = DeviceHelper.getDeviceId(context)
            val deviceName = DeviceHelper.getDeviceName()
            val deviceModel = DeviceHelper.getDeviceModel()
            
            Log.d(TAG, "Authenticating user: $username")
            Log.d(TAG, "Device ID: $deviceId")
            
            // 1. Verify username and password
            val users = supabase.from("users")
                .select {
                    filter {
                        eq("username", username)
                        eq("password", password)
                    }
                }
                .decodeList<User>()
            
            if (users.isEmpty()) {
                return@withContext AuthResult.Error("Invalid username or password")
            }
            
            val user = users.first()
            
            if (!user.isActive) {
                return@withContext AuthResult.Error("Account has been deactivated")
            }
            
            // 2. Check if account is already active on another device
            if (user.activeDeviceId != null && user.activeDeviceId != deviceId) {
                return@withContext AuthResult.Error(
                    "This account is already active on another device.\nDevice: ${user.deviceName ?: "Unknown"}"
                )
            }
            
            // 3. Bind device to account
            val now = Instant.now().toString()
            supabase.from("users")
                .update({
                    set("active_device_id", deviceId)
                    set("device_name", deviceName)
                    set("device_model", deviceModel)
                    set("last_login", now)
                }) {
                    filter {
                        eq("id", user.id)
                    }
                }
            
            saveAuthData(user.id, username, deviceId)
            
            Log.d(TAG, "Authentication successful")
            AuthResult.Success(user)
            
        } catch (e: Exception) {
            Log.e(TAG, "Authentication error", e)
            AuthResult.Error("Authentication failed: ${e.message}")
        }
    }
    
    /**
     * Validate existing authentication (periodic check).
     */
    suspend fun validateAuth(): AuthResult = withContext(Dispatchers.IO) {
        try {
            val username = getUsername() ?: return@withContext AuthResult.Error("Not authenticated")
            val userId = prefs.getString(PREF_USER_ID, null) ?: return@withContext AuthResult.Error("Not authenticated")
            val deviceId = prefs.getString(PREF_DEVICE_ID, null) ?: return@withContext AuthResult.Error("Not authenticated")
            
            // Check if validation is needed (every 7 days)
            val lastValidation = prefs.getLong(PREF_LAST_VALIDATION, 0)
            val now = System.currentTimeMillis()
            
            if (now - lastValidation < VALIDATION_INTERVAL_MS) {
                Log.d(TAG, "Validation not needed yet")
                return@withContext AuthResult.Success(null)
            }
            
            Log.d(TAG, "Performing periodic validation")
            
            // 1. Check if user is still active and device matches
            val users = supabase.from("users")
                .select {
                    filter {
                        eq("id", userId)
                        eq("username", username)
                    }
                }
                .decodeList<User>()
            
            if (users.isEmpty()) {
                // User not found - clear session
                prefs.edit().clear().apply()
                return@withContext AuthResult.Error("Session expired. Please login again.")
            }
            
            if (!users.first().isActive) {
                prefs.edit().clear().apply()
                return@withContext AuthResult.Error("Account has been deactivated")
            }
            
            val user = users.first()
            
            // 2. Check if device still matches
            if (user.activeDeviceId != deviceId) {
                prefs.edit().clear().apply()
                return@withContext AuthResult.Error("This device is no longer authorized")
            }
            
            // Update validation time
            prefs.edit().putLong(PREF_LAST_VALIDATION, now).apply()
            
            Log.d(TAG, "Validation successful")
            AuthResult.Success(user)
            
        } catch (e: Exception) {
            Log.e(TAG, "Validation error: ${e.message}", e)
            // Clear session on validation error (likely schema mismatch)
            prefs.edit().clear().apply()
            AuthResult.Error("Session expired. Please login again.")
        }
    }
    
    /**
     * Logout and clear device binding.
     */
    suspend fun logout(): Boolean = withContext(Dispatchers.IO) {
        try {
            val userId = prefs.getString(PREF_USER_ID, null)
            
            if (userId != null) {
                // Clear device binding in database
                supabase.from("users")
                    .update({
                        set("active_device_id", null as String?)
                        set("device_name", null as String?)
                        set("device_model", null as String?)
                    }) {
                        filter {
                            eq("id", userId)
                        }
                    }
            }
            
            // Clear local data
            prefs.edit().clear().apply()
            Log.d(TAG, "Logged out")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Logout error", e)
            // Clear local data anyway
            prefs.edit().clear().apply()
            false
        }
    }
    
    private fun saveAuthData(userId: String, username: String, deviceId: String) {
        prefs.edit()
            .putString(PREF_USER_ID, userId)
            .putString(PREF_USERNAME, username)
            .putString(PREF_DEVICE_ID, deviceId)
            .putBoolean(PREF_IS_AUTHENTICATED, true)
            .putLong(PREF_LAST_VALIDATION, System.currentTimeMillis())
            .apply()
    }
}

sealed class AuthResult {
    data class Success(val user: User?) : AuthResult()
    data class Error(val message: String) : AuthResult()
}
