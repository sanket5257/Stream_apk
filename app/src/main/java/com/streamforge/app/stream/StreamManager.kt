package com.streamforge.app.stream

import android.media.AudioManager
import com.pedro.common.ConnectChecker
import com.pedro.library.base.Camera2Base
import com.pedro.library.multiple.MultiRtpCamera2
import com.pedro.library.multiple.RtpType
import com.pedro.library.util.BitrateAdapter
import com.streamforge.app.storage.DestinationStore
import com.streamforge.app.storage.StreamConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the broadcast: encoder configuration, publishing, adaptive bitrate, and per-destination
 * connection state.
 *
 * MULTISTREAM. The camera is a [MultiRtpCamera2], which holds a fixed array of RTMP clients
 * (one per slot) fed by a SINGLE encode. That matters: adding a destination costs uplink
 * bandwidth, not another hardware encoder pass, so a mid-range phone can publish to YouTube
 * and Facebook at once. The array length is fixed when the camera is constructed, so
 * [DestinationStore.MAX_ACTIVE] and the ConnectChecker array built in StreamActivity must
 * always agree.
 *
 * A destination that fails does NOT end the broadcast. The aggregate [state] is Live while at
 * least one destination is up, and only Failed once every one of them has failed — otherwise
 * a wrong Facebook key would take a perfectly healthy YouTube stream down with it.
 */
class StreamManager(
    private var camera: MultiRtpCamera2?
) {

    private val _state = MutableStateFlow<StreamState>(StreamState.Idle)
    val state: StateFlow<StreamState> = _state.asStateFlow()

    private val _destinationStates = MutableStateFlow<Map<String, DestinationState>>(emptyMap())
    val destinationStates: StateFlow<Map<String, DestinationState>> = _destinationStates.asStateFlow()

    /**
     * Destinations to publish to on the next start, in slot order. Set by StreamActivity from
     * DestinationStore. When empty, [startStream] falls back to a single destination derived
     * from [StreamConfig] — which is what keeps the service's cold-start replay working even
     * if it fires before the activity has loaded the list.
     */
    @Volatile
    var destinations: List<Destination> = emptyList()

    /** Destinations actually dialled by the current/last session, index == RTMP slot. */
    @Volatile
    private var activeDestinations: List<Destination> = emptyList()

    /** Slots we issued a startStream for, so stopStream only tears down what we started. */
    private val startedSlots = mutableSetOf<Int>()

    /**
     * Last outbound bitrate (bits/sec), taken as the MAX across destinations. The service's
     * no-data watchdog uses this to tell a real, media-carrying session apart from a
     * "connected but sending nothing" one. Max, not sum: one healthy destination means the
     * encoder feed is alive, which is exactly what the watchdog is testing for.
     */
    @Volatile
    var lastBitrateBps: Long = 0L
        private set

    private val slotBitrates = HashMap<Int, Long>()

    /**
     * Adaptive bitrate. A fixed high bitrate on a variable mobile uplink overruns the RTMP send
     * queue, which shows up as "packet sending" errors and mid-stream disconnects. The adapter
     * watches the achieved bitrate + the socket's congestion signal each second and lowers or
     * raises the encoder bitrate on the fly so the stream rides the real available bandwidth
     * instead of dropping. Ceiling is the profile we actually prepared with (set in
     * [startStream]); the adapter never exceeds it.
     *
     * With multistream this matters more, not less: the encoder feeds every destination, so
     * the bitrate has to fit the NARROWEST of them.
     */
    private val bitrateAdapter = BitrateAdapter(BitrateAdapter.Listener { bitrate ->
        try {
            camera?.setVideoBitrateOnFly(bitrate)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "setVideoBitrateOnFly failed", e)
        }
    })

    // Resolution the encoder actually configured with (after any fallback). Reported so the
    // overlay pipeline can size overlays to the real output, not the requested-but-unsupported
    // profile.
    @Volatile var activeWidth: Int = 0
        private set
    @Volatile var activeHeight: Int = 0
        private set

    /** True while RootEncoder considers at least one client connected/publishing. */
    fun isStreaming(): Boolean = camera?.isStreaming == true

    /** Force a Failed state (used by the watchdog when a session carries no media). */
    fun markFailed(reason: String) {
        _state.value = StreamState.Failed(reason)
    }

    /** Set by StreamActivity so external-mic selection works in the service too. */
    var audioManager: AudioManager? = null

    fun setCamera(camera: MultiRtpCamera2) {
        this.camera = camera
    }

    /**
     * Callback to re-apply overlays after encoder preparation. Set by StreamActivity so
     * overlays survive the GL pipeline reset that prepareVideo() performs.
     */
    var onEncoderPrepared: (() -> Unit)? = null

    /**
     * Build the ConnectCheckers for a [MultiRtpCamera2]. One per slot, so each destination
     * reports its own connection result. Called by StreamActivity at construction time.
     */
    fun buildConnectCheckers(slots: Int): Array<ConnectChecker> =
        Array(slots) { index -> SlotChecker(index) }

    /**
     * Start streaming to every configured destination.
     *
     * [useBackup] dials the backup RTMP URL for the primary destination (Phase 7 failover);
     * secondary destinations have no backup concept and are dialled normally.
     */
    fun startStream(config: StreamConfig, useBackup: Boolean = false) {
        val cam = camera
        if (cam == null) {
            // Surface it instead of silently no-oping — a silent return here is why "Go Live"
            // sometimes did nothing and had to be tapped again.
            android.util.Log.e(TAG, "startStream: camera not initialized")
            _state.value = StreamState.Failed("Camera not ready")
            return
        }

        // Guard against a double start — a stale reconnect racing a manual start, or the
        // surface-loss recovery firing while we're already up. Re-publishing on an
        // already-streaming client makes YouTube see a duplicate publish on the same key and
        // silently drop media while the handshake still "succeeds" (the classic
        // shows-live-but-no-video failure on the 2nd session).
        if (cam.isStreaming) {
            android.util.Log.w(TAG, "startStream ignored — already streaming")
            return
        }

        val targets = resolveDestinations(config)
        if (targets.isEmpty()) {
            _state.value = StreamState.Failed("Add a stream key for at least one destination")
            return
        }

        lastBitrateBps = 0L
        slotBitrates.clear()
        activeDestinations = targets
        startedSlots.clear()
        _destinationStates.value = targets.associate { it.id to DestinationState.Connecting }
        _state.value = StreamState.Connecting

        // Prepare the video encoder, falling back to progressively lighter profiles if the
        // requested one can't be configured on this device's encoder. Configuring 1080p@6Mbps
        // returns false on some phones; retrying the SAME impossible profile just dead-ends
        // into repeated failures, which read as "stream configure issue" and force the user to
        // keep pressing Go Live.
        val chosenBitrateKbps = prepareVideoWithFallback(cam, config, targets.size)
        val videoPrepared = chosenBitrateKbps > 0

        val audioPrepared = if (videoPrepared) {
            try {
                cam.prepareAudio(config.audioBitrateKbps * 1024, 44100, true, false, false)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "prepareAudio threw", e); false
            }
        } else false

        if (!videoPrepared || !audioPrepared) {
            failAll("Couldn't configure the encoder on this device")
            return
        }

        // Arm adaptive bitrate against the profile we actually prepared with.
        bitrateAdapter.setMaxBitrate(chosenBitrateKbps * 1024)

        // Route to the external mic if the user picked one. Must be between prepareAudio
        // (creates AudioRecord) and startStream (starts recording).
        audioManager?.let { am ->
            if (config.preferredMicId > 0) {
                MicAudioHelper.applyPreferredDevice(cam, config.preferredMicId, am)
            }
        }

        // Dial each destination on its own slot. The FIRST call also starts the encoder
        // (MultiRtpCamera2 does that when no client is streaming yet); the rest only connect
        // their client, which is exactly the single-encode fan-out we want.
        var anyStarted = false
        targets.forEachIndexed { slot, destination ->
            val url = publishUrlFor(destination, config, useBackup)
            try {
                cam.startStream(RtpType.RTMP, slot, url)
                startedSlots.add(slot)
                anyStarted = true
            } catch (t: Throwable) {
                // RootEncoder throws out of startStream when the RTMP client or the encoder is
                // in a state it doesn't expect. This runs on the main thread from the service,
                // so letting it escape kills the app instead of showing "couldn't connect".
                // One bad destination must not stop the others from going live.
                android.util.Log.e(TAG, "startStream threw for ${destination.label}", t)
                setDestinationState(destination.id, DestinationState.Failed("Couldn't open the connection"))
            }
        }

        if (!anyStarted) {
            failAll("Couldn't open the connection to the server")
            return
        }

        // Re-apply overlays AFTER the stream starts, when the GL context is fully ready.
        // prepareVideo() resets the GL pipeline, and glInterface needs the stream running to
        // accept new filters.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                onEncoderPrepared?.invoke()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to re-apply overlays", e)
            }
        }, 200)
    }

    /**
     * Destinations for this session. Falls back to a single destination synthesised from
     * [StreamConfig] so a service-side start that beats the activity's destination load still
     * publishes to the user's YouTube key rather than failing.
     */
    private fun resolveDestinations(config: StreamConfig): List<Destination> {
        val configured = destinations.filter { it.enabled && it.isConfigured }
            .take(DestinationStore.MAX_ACTIVE)
        if (configured.isNotEmpty()) return configured
        if (config.streamKey.isBlank()) return emptyList()
        return listOf(
            Destination(
                platform = Platform.YOUTUBE,
                ingestUrl = config.rtmpUrl,
                streamKey = config.streamKey,
            )
        )
    }

    /**
     * Backup failover applies only to the primary (slot 0) destination — it's the YouTube
     * ingest fallback, and a Facebook slot has no meaningful "backup URL".
     */
    private fun publishUrlFor(destination: Destination, config: StreamConfig, useBackup: Boolean): String {
        val isPrimary = destination.platform == Platform.YOUTUBE
        if (useBackup && isPrimary && config.backupRtmpUrl.isNotBlank()) {
            return destination.copy(ingestUrl = config.backupRtmpUrl).publishUrl()
        }
        return destination.publishUrl()
    }

    /**
     * Try the requested profile, then progressively lighter fallbacks. Returns the video
     * bitrate (kbps) actually prepared with, or 0 if even the lowest profile failed.
     *
     * [destinationCount] tightens the ceiling: the same encoded stream is sent to every
     * destination, so N destinations need N × bitrate of uplink. Publishing 6 Mbps to three
     * targets needs 18 Mbps sustained upload, which almost no mobile connection holds — the
     * result is congestion on all three rather than a clean stream on one. Backing the
     * encoder off keeps every destination watchable.
     */
    private fun prepareVideoWithFallback(
        camera: Camera2Base,
        config: StreamConfig,
        destinationCount: Int,
    ): Int {
        val ceiling = multistreamBitrateCeiling(config.videoBitrateKbps, destinationCount)
        val profiles = buildList {
            add(Triple(config.width, config.height, ceiling))
            if (config.height > 720) add(Triple(1280, 720, minOf(ceiling, 4500)))
            if (config.height > 480) add(Triple(854, 480, minOf(ceiling, 2500)))
        }
        for ((w, h, kbps) in profiles) {
            val ok = try {
                camera.prepareVideo(
                    w, h, config.fps, kbps * 1024,
                    1, // iFrameInterval (s) — short GOP trims glass-to-glass latency.
                    0  // rotation
                )
            } catch (e: Exception) {
                android.util.Log.e(TAG, "prepareVideo ${w}x$h threw", e)
                false
            }
            if (ok) {
                activeWidth = w
                activeHeight = h
                android.util.Log.d(TAG, "Encoder configured at ${w}x$h @ ${kbps}kbps for $destinationCount destination(s)")
                return kbps
            }
            android.util.Log.w(TAG, "Encoder rejected ${w}x$h; trying a lighter profile")
        }
        return 0
    }

    /**
     * Bitrate ceiling given how many destinations share the uplink. One destination gets the
     * user's setting untouched; each extra one scales it back, with a floor so the picture
     * never collapses. Adaptive bitrate then trims further if the real uplink is worse.
     */
    private fun multistreamBitrateCeiling(requestedKbps: Int, destinationCount: Int): Int {
        if (destinationCount <= 1) return requestedKbps
        val scaled = (requestedKbps.toFloat() / destinationCount * MULTISTREAM_HEADROOM).toInt()
        return scaled.coerceAtLeast(MIN_MULTISTREAM_KBPS).coerceAtMost(requestedKbps)
    }

    /** Stop the current broadcast on every destination. */
    fun stopStream() {
        lastBitrateBps = 0L
        slotBitrates.clear()
        try { bitrateAdapter.reset() } catch (_: Throwable) { }

        // Stopping is a cleanup path — it runs from onDestroy, from the Stop button and from
        // the reconnect loop. It must always leave us in Idle, even if the encoder objects to
        // being torn down, or a failed stop would strand the UI on "Live" forever.
        val cam = camera
        if (cam != null) {
            startedSlots.toList().forEach { slot ->
                try {
                    cam.stopStream(RtpType.RTMP, slot)
                } catch (t: Throwable) {
                    android.util.Log.e(TAG, "stopStream(slot=$slot) threw", t)
                }
            }
            // Backstop: MultiRtpCamera2 stops the encoder itself once the last client is
            // disconnected, but if a per-slot stop threw, the encoder can still be running.
            try {
                if (cam.isStreaming) cam.stopStream()
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "stopStream backstop threw", t)
            }
        }
        startedSlots.clear()
        activeDestinations = emptyList()
        _destinationStates.value = emptyMap()
        _state.value = StreamState.Idle
    }

    private fun failAll(reason: String) {
        _destinationStates.value =
            activeDestinations.associate { it.id to DestinationState.Failed(reason) }
        _state.value = StreamState.Failed(reason)
    }

    private fun destinationForSlot(slot: Int): Destination? = activeDestinations.getOrNull(slot)

    private fun setDestinationState(id: String, state: DestinationState) {
        _destinationStates.value = _destinationStates.value.toMutableMap().apply { put(id, state) }
        recomputeAggregate()
    }

    /**
     * Collapse per-destination states into the single [StreamState] the rest of the app (the
     * service's retry logic, the notification, the Go Live button) reasons about.
     *
     * Live wins over everything: as long as one destination is publishing, the user IS live.
     * Failed is reported only when every destination has failed, which is the point at which
     * the service should retry or give up.
     */
    private fun recomputeAggregate() {
        val states = _destinationStates.value
        if (states.isEmpty()) {
            _state.value = StreamState.Idle
            return
        }
        val values = states.values
        _state.value = when {
            values.any { it is DestinationState.Live } -> StreamState.Live
            values.any { it is DestinationState.Connecting } -> StreamState.Connecting
            values.all { it is DestinationState.Failed } ->
                StreamState.Failed(
                    (values.firstOrNull { it is DestinationState.Failed } as? DestinationState.Failed)
                        ?.reason ?: "Connection failed"
                )
            else -> StreamState.Idle
        }
    }

    /**
     * ConnectChecker for one RTMP slot. RootEncoder gives each client its own checker, which
     * is what makes per-destination status possible at all.
     */
    private inner class SlotChecker(private val slot: Int) : ConnectChecker {

        override fun onConnectionStarted(url: String) {
            destinationForSlot(slot)?.let { setDestinationState(it.id, DestinationState.Connecting) }
        }

        override fun onConnectionSuccess() {
            destinationForSlot(slot)?.let { setDestinationState(it.id, DestinationState.Live) }
        }

        override fun onConnectionFailed(reason: String) {
            destinationForSlot(slot)?.let { setDestinationState(it.id, DestinationState.Failed(reason)) }
        }

        override fun onNewBitrate(bitrate: Long) {
            // Real outbound bitrate for this destination. The watchdog reads the max, so a
            // single healthy destination is enough to prove the encoder feed is alive.
            slotBitrates[slot] = bitrate
            lastBitrateBps = slotBitrates.values.maxOrNull() ?: 0L

            // Feed the adaptive-bitrate loop. Congestion on ANY destination should ease the
            // shared encoder down — the slowest link sets the pace, because they all carry
            // the same frames.
            val congested = anyDestinationCongested()
            bitrateAdapter.adaptBitrate(lastBitrateBps, congested)
        }

        override fun onDisconnect() {
            destinationForSlot(slot)?.let { setDestinationState(it.id, DestinationState.Idle) }
        }

        override fun onAuthError() {
            destinationForSlot(slot)?.let {
                setDestinationState(it.id, DestinationState.Failed("Authentication failed"))
            }
        }

        override fun onAuthSuccess() {
            // Connection succeeded; onConnectionSuccess drives the transition to Live.
        }
    }

    private fun anyDestinationCongested(): Boolean {
        val cam = camera ?: return false
        // NOTE: MultiRtpCamera2.getStreamClient() returns null by design — there is no single
        // client to hand back. Congestion must be asked per slot.
        return startedSlots.any { slot ->
            try {
                cam.hasCongestion(RtpType.RTMP, slot)
            } catch (_: Exception) {
                false
            }
        }
    }

    private companion object {
        const val TAG = "StreamManager"

        /**
         * Fraction of the naive per-destination share to actually use. Below 1.0 because the
         * uplink also carries audio, RTMP overhead and retransmits, and because a link running
         * at exactly 100% of capacity is a link that stalls.
         */
        const val MULTISTREAM_HEADROOM = 0.85f

        /** Never drop below this, whatever the destination count — 1.5 Mbps is watchable 720p. */
        const val MIN_MULTISTREAM_KBPS = 1500
    }
}
