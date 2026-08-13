package com.streamforge.app.stream

import android.media.AudioManager
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.util.BitrateAdapter
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

    /**
     * Adaptive bitrate. A fixed high bitrate on a variable mobile uplink overruns the RTMP
     * send queue, which shows up as "packet sending" errors and mid-stream disconnects. The
     * adapter watches the achieved bitrate + the socket's congestion signal each second and
     * lowers/raises the encoder bitrate on the fly (setVideoBitrateOnFly) so the stream rides
     * the real available bandwidth instead of dropping. Ceiling is the profile we actually
     * prepared with (set in startStream); the adapter never exceeds it.
     */
    private val bitrateAdapter = BitrateAdapter(BitrateAdapter.Listener { bitrate ->
        try {
            rtmpCamera?.setVideoBitrateOnFly(bitrate)
        } catch (e: Exception) {
            android.util.Log.w("StreamManager", "setVideoBitrateOnFly failed", e)
        }
    })

    // Resolution the encoder actually configured with (after any fallback). Reported so the
    // overlay pipeline can size overlays to the real output, not the requested-but-unsupported
    // profile.
    @Volatile var activeWidth: Int = 0
        private set
    @Volatile var activeHeight: Int = 0
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
        val camera = rtmpCamera
        if (camera == null) {
            // Surface it instead of silently no-oping — a silent return here is why "Go Live"
            // sometimes did nothing and had to be tapped again.
            android.util.Log.e("StreamManager", "startStream: camera not initialized")
            _state.value = StreamState.Failed("Camera not ready")
            return
        }

        // Guard against a double start — a stale reconnect racing a manual start, or the
        // surface-loss recovery firing while we're already up. Re-publishing on an
        // already-streaming client makes YouTube see a duplicate publish on the same key and
        // silently drop media while the handshake still "succeeds" (the classic
        // shows-live-but-no-video failure on the 2nd session).
        if (camera.isStreaming) {
            android.util.Log.w("StreamManager", "startStream ignored — already streaming")
            return
        }

        // Fail fast with a clear message rather than dialing a keyless ingest URL that YouTube
        // just rejects (looks to the user like a random "configure"/connection failure).
        if (config.streamKey.isBlank()) {
            _state.value = StreamState.Failed("Enter your YouTube stream key first")
            return
        }

        lastBitrateBps = 0L
        _state.value = StreamState.Connecting

        // Prepare the video encoder, falling back to progressively lighter profiles if the
        // requested one can't be configured on this device's encoder. Configuring 1080p@6Mbps
        // returns false on some phones; retrying the SAME impossible profile (the old
        // behaviour) just dead-ends into repeated failures, which read as "stream configure
        // issue" and forced the user to keep pressing Go Live.
        val chosenBitrateKbps = prepareVideoWithFallback(camera, config)
        val videoPrepared = chosenBitrateKbps > 0

        val audioPrepared = if (videoPrepared) {
            try {
                camera.prepareAudio(config.audioBitrateKbps * 1024, 44100, true, false, false)
            } catch (e: Exception) {
                android.util.Log.e("StreamManager", "prepareAudio threw", e); false
            }
        } else false

        if (!videoPrepared || !audioPrepared) {
            _state.value = StreamState.Failed("Couldn't configure the encoder on this device")
            return
        }

        // Arm adaptive bitrate against the profile we actually prepared with.
        bitrateAdapter.setMaxBitrate(chosenBitrateKbps * 1024)

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
     * Try to configure the video encoder at the requested profile, then progressively lighter
     * fallbacks (720p, then 480p) if configuration fails. Returns the video bitrate (kbps) the
     * encoder was actually prepared with, or 0 if even the lowest profile failed. Also records
     * [activeWidth]/[activeHeight] for the profile that succeeded.
     */
    private fun prepareVideoWithFallback(camera: RtmpCamera2, config: StreamConfig): Int {
        // requested first, then safe fallbacks — deduped and only those lighter than requested.
        val profiles = buildList {
            add(Triple(config.width, config.height, config.videoBitrateKbps))
            if (config.height > 720) add(Triple(1280, 720, minOf(config.videoBitrateKbps, 4500)))
            if (config.height > 480) add(Triple(854, 480, minOf(config.videoBitrateKbps, 2500)))
        }
        for ((w, h, kbps) in profiles) {
            val ok = try {
                camera.prepareVideo(
                    w, h, config.fps, kbps * 1024,
                    1, // iFrameInterval (s) — short GOP trims glass-to-glass latency.
                    0  // rotation
                )
            } catch (e: Exception) {
                android.util.Log.e("StreamManager", "prepareVideo ${w}x$h threw", e)
                false
            }
            if (ok) {
                activeWidth = w
                activeHeight = h
                android.util.Log.d("StreamManager", "Encoder configured at ${w}x$h @ ${kbps}kbps")
                return kbps
            }
            android.util.Log.w("StreamManager", "Encoder rejected ${w}x$h; trying a lighter profile")
        }
        return 0
    }

    /**
     * Stop the current stream.
     */
    fun stopStream() {
        lastBitrateBps = 0L
        bitrateAdapter.reset()
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
        // Feed the adaptive-bitrate loop: if the RTMP send queue is congested, ease the
        // encoder bitrate down so we keep publishing instead of dropping the connection.
        val congested = try {
            rtmpCamera?.streamClient?.hasCongestion() ?: false
        } catch (e: Exception) {
            false
        }
        bitrateAdapter.adaptBitrate(bitrate, congested)
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
