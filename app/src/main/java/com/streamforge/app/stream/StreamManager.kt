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
     * Last outbound bitrate (bits/sec) reported by RootEncoder. Used by the service's
     * no-data watchdog to tell a real, media-carrying session apart from a "connected but
     * sending nothing" session (e.g. a re-prepared encoder whose GL feed didn't re-link).
     * 0 means no media is leaving the device.
     */
    @Volatile
    var lastBitrateBps: Long = 0L
        private set

    /** True while RootEncoder considers the RTMP client connected/publishing. */
    fun isStreaming(): Boolean = rtmpCamera?.isStreaming == true

    /** Force a Failed state (used by the watchdog when a session carries no media). */
    fun markFailed(reason: String) {
        _state.value = StreamState.Failed(reason)
    }

    /**
     * Phase 7: set by StreamActivity so external-mic selection works in the service too.
     */
    var audioManager: AudioManager? = null

    fun setCamera(camera: RtmpCamera2) {
        this.rtmpCamera = camera
    }

    /**
     * Callback to re-apply overlays after encoder preparation.
     * Set by StreamActivity so overlays survive the GL pipeline reset.
     */
    var onEncoderPrepared: (() -> Unit)? = null

    /**
     * Start streaming with the given configuration. By default uses the primary URL;
     * pass [useBackup] = true to dial the backup URL (Phase 7 failover).
     */
    fun startStream(config: StreamConfig, useBackup: Boolean = false) {
        val camera = rtmpCamera ?: return

        // Guard against a double start — a stale reconnect racing a manual start, or the
        // surface-loss recovery firing while we're already up. Re-publishing on an
        // already-streaming client makes YouTube see a duplicate publish on the same key and
        // silently drop media while the handshake still "succeeds" (the classic
        // shows-live-but-no-video failure on the 2nd session).
        if (camera.isStreaming) {
            android.util.Log.w("StreamManager", "startStream ignored — already streaming")
            return
        }

        lastBitrateBps = 0L
        _state.value = StreamState.Connecting

        val videoPrepared = camera.prepareVideo(
            config.width,
            config.height,
            config.fps,
            config.videoBitrateKbps * 1024,
            1, // iFrameInterval in seconds — shorter GOP cuts glass-to-glass latency
               // (~1s reduction vs 2s) at the cost of ~5–10% bitrate efficiency.
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
        
        // Re-apply overlays AFTER stream starts, when GL context is fully ready.
        // prepareVideo() resets the GL pipeline, and glInterface needs the stream
        // running to accept new filters.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                onEncoderPrepared?.invoke()
            } catch (e: Exception) {
                android.util.Log.e("StreamManager", "Failed to re-apply overlays", e)
            }
        }, 200) // Small delay to ensure GL context is ready
    }

    /**
     * Stop the current stream.
     */
    fun stopStream() {
        lastBitrateBps = 0L
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
        // Real outbound bitrate — the watchdog uses this to detect a media-less session.
        lastBitrateBps = bitrate
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
