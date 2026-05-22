package com.streamforge.app.stream

/**
 * Phase 3A: Represents the current state of the RTMP stream.
 */
sealed class StreamState {
    object Idle : StreamState()
    object Connecting : StreamState()
    object Live : StreamState()
    data class Failed(val reason: String) : StreamState()
}
