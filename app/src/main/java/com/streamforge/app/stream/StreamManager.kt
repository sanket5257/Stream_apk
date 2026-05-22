package com.streamforge.app.stream

import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2
import com.streamforge.app.storage.StreamConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 3A: Manages RTMP streaming to YouTube.
 * Wraps RtmpCamera2 and exposes stream state via StateFlow.
 */
class StreamManager(
    private var rtmpCamera: RtmpCamera2?
) : ConnectChecker {

    private val _state = MutableStateFlow<StreamState>(StreamState.Idle)
    val state: StateFlow<StreamState> = _state.asStateFlow()

    fun setCamera(camera: RtmpCamera2) {
        this.rtmpCamera = camera
    }

    /**
     * Start streaming to YouTube with the given configuration.
     */
    fun startStream(config: StreamConfig) {
        val camera = rtmpCamera ?: return
        _state.value = StreamState.Connecting

        // Prepare video encoder (positional arguments)
        val videoPrepared = camera.prepareVideo(
            config.width,
            config.height,
            config.fps,
            config.videoBitrateKbps * 1024,
            2, // iFrameInterval
            0  // rotation
        )

        // Prepare audio encoder (positional arguments)
        val audioPrepared = camera.prepareAudio(
            config.audioBitrateKbps * 1024,
            44100,
            true,
            false,
            false
        )

        if (!videoPrepared || !audioPrepared) {
            _state.value = StreamState.Failed("Failed to prepare encoders")
            return
        }

        // Build RTMP URL (server + key)
        val rtmpUrl = if (config.rtmpUrl.endsWith("/")) {
            config.rtmpUrl + config.streamKey
        } else {
            config.rtmpUrl + "/" + config.streamKey
        }

        // Start streaming
        camera.startStream(rtmpUrl)
    }

    /**
     * Stop the current stream.
     */
    fun stopStream() {
        rtmpCamera?.stopStream()
        _state.value = StreamState.Idle
    }

    // ConnectChecker interface implementation
    override fun onConnectionStarted(url: String) {
        _state.value = StreamState.Connecting
    }

    override fun onConnectionSuccess() {
        _state.value = StreamState.Live
    }

    override fun onConnectionFailed(reason: String) {
        _state.value = StreamState.Failed(reason)
    }

    override fun onNewBitrate(bitrate: Long) {
        // Could be used to show current bitrate in UI
    }

    override fun onDisconnect() {
        _state.value = StreamState.Idle
    }

    override fun onAuthError() {
        _state.value = StreamState.Failed("Authentication failed")
    }

    override fun onAuthSuccess() {
        // Connection successful, will transition to Live
    }
}
