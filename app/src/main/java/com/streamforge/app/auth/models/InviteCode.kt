package com.streamforge.app.auth.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents an invite code in the database.
 */
@Serializable
data class InviteCode(
    @SerialName("id")
    val id: String,
    
    @SerialName("code")
    val code: String,
    
    @SerialName("used")
    val used: Boolean = false,
    
    @SerialName("used_by")
    val usedBy: String? = null,
    
    @SerialName("created_at")
    val createdAt: String
)

/**
 * Data class for updating invite code after use.
 */
@Serializable
data class InviteCodeUpdate(
    @SerialName("used")
    val used: Boolean,
    
    @SerialName("used_by")
    val usedBy: String
)
