package com.streamforge.app.auth

import android.util.Log
import com.streamforge.app.auth.models.InviteCode
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages invite code validation and usage tracking.
 */
class InviteCodeManager {
    
    private val supabase = SupabaseClient.client
    
    companion object {
        private const val TAG = "InviteCodeManager"
    }
    
    /**
     * Validates an invite code.
     * Returns true if the code exists and hasn't been used yet.
     */
    suspend fun validateInviteCode(code: String): InviteCodeResult = withContext(Dispatchers.IO) {
        try {
            if (code.isBlank()) {
                Log.w(TAG, "Validation failed: Empty code")
                return@withContext InviteCodeResult.Invalid("Invite code cannot be empty")
            }
            
            Log.d(TAG, "========================================")
            Log.d(TAG, "Validating invite code: '$code'")
            Log.d(TAG, "Code length: ${code.length}")
            Log.d(TAG, "Supabase URL: ${SupabaseClient.client.supabaseUrl}")
            
            // Query the invite_codes table
            val inviteCodes = supabase.from("invite_codes")
                .select {
                    filter {
                        eq("code", code)
                    }
                }
                .decodeList<InviteCode>()
            
            Log.d(TAG, "Query returned ${inviteCodes.size} results")
            
            when {
                inviteCodes.isEmpty() -> {
                    Log.w(TAG, "Invite code NOT FOUND in database: '$code'")
                    Log.d(TAG, "Attempting to fetch all codes to debug...")
                    try {
                        val allCodes = supabase.from("invite_codes")
                            .select()
                            .decodeList<InviteCode>()
                        Log.d(TAG, "Total codes in database: ${allCodes.size}")
                        allCodes.take(5).forEach { 
                            Log.d(TAG, "  - Code: '${it.code}', Used: ${it.used}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to fetch all codes", e)
                    }
                    InviteCodeResult.Invalid("Invalid invite code")
                }
                inviteCodes.first().used -> {
                    Log.w(TAG, "Invite code already used by: ${inviteCodes.first().usedBy}")
                    InviteCodeResult.AlreadyUsed("This invite code has already been used")
                }
                else -> {
                    Log.i(TAG, "✓ Invite code is VALID")
                    Log.d(TAG, "========================================")
                    InviteCodeResult.Valid(inviteCodes.first())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "========================================")
            Log.e(TAG, "ERROR validating invite code: ${e.javaClass.simpleName}", e)
            Log.e(TAG, "Error message: ${e.message}")
            Log.e(TAG, "Stack trace:", e)
            Log.e(TAG, "========================================")
            InviteCodeResult.Invalid("Failed to validate invite code: ${e.message}")
        }
    }
    
    /**
     * Marks an invite code as used by a specific user.
     * This should be called after successful user registration.
     */
    suspend fun markInviteCodeAsUsed(code: String, userId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Marking invite code as used: $code by user: $userId")
            
            // Update the invite code
            supabase.from("invite_codes")
                .update({
                    set("used", true)
                    set("used_by", userId)
                }) {
                    filter {
                        eq("code", code)
                        eq("used", false) // Only update if not already used
                    }
                }
            
            Log.d(TAG, "Invite code marked as used successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error marking invite code as used", e)
            false
        }
    }
}

/**
 * Result of invite code validation.
 */
sealed class InviteCodeResult {
    data class Valid(val inviteCode: InviteCode) : InviteCodeResult()
    data class Invalid(val message: String) : InviteCodeResult()
    data class AlreadyUsed(val message: String) : InviteCodeResult()
}
