package com.streamforge.app.auth

import android.content.Context
import android.util.Log
import com.streamforge.app.auth.models.User
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Manages authentication with username/password and single device binding.
 *
 * SECURITY: all credential handling happens server-side in Supabase via
 * SECURITY DEFINER Postgres functions (app_signup / app_login /
 * app_validate_session / app_logout). The app's anon key has NO direct
 * SELECT/INSERT/UPDATE access to the `users` or `invite_codes` tables, so the
 * user table — including password hashes — is never exposed to clients.
 * Passwords are bcrypt-hashed in the database; the plaintext password leaves
 * the device only over TLS and is never stored or returned.
 *
 * See SECURE_AUTH_MIGRATION.sql for the matching database setup.
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
     * Invite-code validation, uniqueness checks, password hashing, and marking
     * the code used all happen atomically inside the app_signup function.
     */
    suspend fun signUp(email: String, username: String, password: String, inviteCode: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val deviceId = DeviceHelper.getDeviceId(context)
            Log.d(TAG, "Signing up user: $username")

            val response = supabase.postgrest.rpc(
                function = "app_signup",
                parameters = SignupParams(
                    email = email,
                    username = username,
                    password = password,
                    inviteCode = inviteCode,
                    deviceId = deviceId,
                    deviceName = DeviceHelper.getDeviceName(),
                    deviceModel = DeviceHelper.getDeviceModel()
                )
            ).decodeAs<AuthRpcResponse>()

            if (response.status != STATUS_OK || response.user == null) {
                return@withContext AuthResult.Error(response.message ?: "Failed to create account")
            }

            saveAuthData(response.user.id, username, deviceId)
            Log.d(TAG, "Sign up successful")
            AuthResult.Success(response.user)
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
            Log.d(TAG, "Authenticating user: $username")

            val response = supabase.postgrest.rpc(
                function = "app_login",
                parameters = LoginParams(
                    username = username,
                    password = password,
                    deviceId = deviceId,
                    deviceName = DeviceHelper.getDeviceName(),
                    deviceModel = DeviceHelper.getDeviceModel()
                )
            ).decodeAs<AuthRpcResponse>()

            if (response.status != STATUS_OK || response.user == null) {
                return@withContext AuthResult.Error(response.message ?: "Invalid username or password")
            }

            saveAuthData(response.user.id, username, deviceId)
            Log.d(TAG, "Authentication successful")
            AuthResult.Success(response.user)
        } catch (e: Exception) {
            Log.e(TAG, "Authentication error", e)
            AuthResult.Error("Authentication failed: ${e.message}")
        }
    }

    /**
     * Validate existing authentication (periodic check, throttled to once per 7 days).
     */
    suspend fun validateAuth(): AuthResult = withContext(Dispatchers.IO) {
        try {
            val username = getUsername() ?: return@withContext AuthResult.Error("Not authenticated")
            val userId = prefs.getString(PREF_USER_ID, null) ?: return@withContext AuthResult.Error("Not authenticated")
            val deviceId = prefs.getString(PREF_DEVICE_ID, null) ?: return@withContext AuthResult.Error("Not authenticated")

            val lastValidation = prefs.getLong(PREF_LAST_VALIDATION, 0)
            val now = System.currentTimeMillis()
            if (now - lastValidation < VALIDATION_INTERVAL_MS) {
                Log.d(TAG, "Validation not needed yet")
                return@withContext AuthResult.Success(null)
            }

            Log.d(TAG, "Performing periodic validation")

            val response = supabase.postgrest.rpc(
                function = "app_validate_session",
                parameters = ValidateParams(
                    userId = userId,
                    username = username,
                    deviceId = deviceId
                )
            ).decodeAs<AuthRpcResponse>()

            if (response.status != STATUS_OK) {
                prefs.edit().clear().apply()
                return@withContext AuthResult.Error(response.message ?: "Session expired. Please login again.")
            }

            prefs.edit().putLong(PREF_LAST_VALIDATION, now).apply()
            Log.d(TAG, "Validation successful")
            AuthResult.Success(null)
        } catch (e: Exception) {
            Log.e(TAG, "Validation error: ${e.message}", e)
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
            val deviceId = prefs.getString(PREF_DEVICE_ID, null)

            if (userId != null && deviceId != null) {
                supabase.postgrest.rpc(
                    function = "app_logout",
                    parameters = LogoutParams(userId = userId, deviceId = deviceId)
                )
            }

            prefs.edit().clear().apply()
            Log.d(TAG, "Logged out")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Logout error", e)
            // Clear local session regardless so the device isn't left bound locally.
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

// ---- RPC request/response payloads. Field names map to the SQL function args. ----

@Serializable
private data class SignupParams(
    @SerialName("p_email") val email: String,
    @SerialName("p_username") val username: String,
    @SerialName("p_password") val password: String,
    @SerialName("p_invite_code") val inviteCode: String,
    @SerialName("p_device_id") val deviceId: String,
    @SerialName("p_device_name") val deviceName: String,
    @SerialName("p_device_model") val deviceModel: String
)

@Serializable
private data class LoginParams(
    @SerialName("p_username") val username: String,
    @SerialName("p_password") val password: String,
    @SerialName("p_device_id") val deviceId: String,
    @SerialName("p_device_name") val deviceName: String,
    @SerialName("p_device_model") val deviceModel: String
)

@Serializable
private data class ValidateParams(
    @SerialName("p_user_id") val userId: String,
    @SerialName("p_username") val username: String,
    @SerialName("p_device_id") val deviceId: String
)

@Serializable
private data class LogoutParams(
    @SerialName("p_user_id") val userId: String,
    @SerialName("p_device_id") val deviceId: String
)

@Serializable
private data class AuthRpcResponse(
    val status: String,
    val message: String? = null,
    val user: User? = null
)

private const val STATUS_OK = "ok"

sealed class AuthResult {
    data class Success(val user: User?) : AuthResult()
    data class Error(val message: String) : AuthResult()
}
