package com.streamforge.app.auth.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    val email: String,
    val username: String,
    // Never returned by the server-side auth functions; kept nullable so safe
    // user payloads (which omit the password hash) still deserialize.
    val password: String? = null,
    @SerialName("is_active")
    val isActive: Boolean,
    @SerialName("active_device_id")
    val activeDeviceId: String? = null,
    @SerialName("device_name")
    val deviceName: String? = null,
    @SerialName("device_model")
    val deviceModel: String? = null,
    @SerialName("last_login")
    val lastLogin: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null
)

@Serializable
data class NewUser(
    val email: String,
    val username: String,
    val password: String,
    @SerialName("is_active")
    val isActive: Boolean = true,
    @SerialName("active_device_id")
    val activeDeviceId: String,
    @SerialName("device_name")
    val deviceName: String,
    @SerialName("device_model")
    val deviceModel: String,
    @SerialName("last_login")
    val lastLogin: String
)
