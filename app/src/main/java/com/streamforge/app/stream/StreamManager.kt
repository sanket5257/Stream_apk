package com.streamforge.app.stream

import android.media.AudioManager
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

    /**
     * Phase 7: set by StreamActivity so external-mic selection works in the service too.
     */
    var audioManager: AudioManager? = null

    fun setCamera(camera: RtmpCamera2) {
        this.rtmpCamera = camera
    }

    /**
     * Start streaming with the given configuration. By default uses the primary URL;
     * pass [useBackup] = true to dial the backup URL (Phase 7 failover).
     */
    fun startStream(config: StreamConfig, useBackup: Boolean = false) {
        val camera = rtmpCamera ?: return
        _state.value = StreamState.Connecting

        val videoPrepared = camera.prepareVideo(
            config.width,
            config.height,
            config.fps,
            config.videoBitrateKbps * 1024,
            2, // iFrameInterval
            0  // rotation
        )

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

        // Phase 7: route to external mic if the user picked one. Must be between
        // prepareAudio (creates AudioRecord) and startStream (starts recording).
        audioManager?.let { am ->
            if (config.preferredMicId > 0) {
                MicAudioHelper.applyPreferredDevice(camera, config.preferredMicId, am)
            }
        }

        val base = if (useBackup && config.backupRtmpUrl.isNotBlank()) {
            config.backupRtmpUrl
        } else {
            config.rtmpUrl
        }
        val rtmpUrl = if (base.endsWith("/")) base + config.streamKey
                      else "$base/${config.streamKey}"

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
