package com.streamforge.app.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.pedro.library.rtmp.RtmpCamera2
import com.streamforge.app.MainActivity
import com.streamforge.app.StreamActivity
import com.streamforge.app.storage.StreamConfig
import com.streamforge.app.stream.StreamManager
import com.streamforge.app.stream.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Phase 4A: Foreground service for background streaming.
 * Keeps the stream alive when the app is backgrounded or screen is locked.
 */
class StreamService : Service() {

    companion object {
        const val ACTION_START = "com.streamforge.app.ACTION_START_STREAM"
        const val ACTION_STOP = "com.streamforge.app.ACTION_STOP_STREAM"
        const val EXTRA_CONFIG = "extra_config"
        private const val TAG = "StreamService"
        private const val MAX_RETRY_COUNT = 3

        // No-data watchdog tuning. "Live" from RootEncoder only means the RTMP handshake
        // succeeded; these let us detect a session that connected but carries no media and
        // recover it the way other streaming apps do — auto-reconnect.
        //
        // These are deliberately lenient: a brief network stall or slow YouTube ingest warmup
        // must NOT be mistaken for a dead session, or a perfectly healthy stream gets torn down
        // (the "breaks after ~1 min" symptom, since the old 8s+8s+give-up-after-3 tripped right
        // around the one-minute mark). With adaptive bitrate now keeping the pipe from
        // congesting, genuine zero-media is rare, so we can afford to wait longer before acting.
        private const val NO_DATA_GRACE_MS = 12000L     // let the encoder + ingest warm up first
        private const val WATCHDOG_TICK_MS = 1000L
        private const val NO_DATA_TIMEOUT_MS = 15000L   // sustained no bytes => dead session
        private const val RECONNECT_COOLDOWN_MS = 4000L // let YouTube release the key
        private const val MIN_RECONNECT_MS = 3000L      // floor for backoff reconnects
        private const val MAX_FORCED_RECONNECTS = 5     // give up only after real persistence
    }

    private val binder = StreamBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private var streamManager: StreamManager? = null
    private var retryCount = 0
    private var usingBackup = false
    private var backupExhausted = false
    private var currentConfig: StreamConfig? = null
    // Set when ACTION_START arrives before the activity has handed us a StreamManager. The
    // start is replayed the moment setStreamManager() is called, so the foreground notification
    // never shows for a stream that silently never started (the "press Go Live several times"
    // symptom on a cold start).
    private var pendingStartConfig: StreamConfig? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    // Tracked so we can cancel them on stop — otherwise a delayed reconnect can re-publish
    // after the user stopped, or a watchdog can keep poking a session that's gone.
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null
    // Counts consecutive "connected but no media" sessions; reset only when real bytes flow.
    private var forcedReconnects = 0
    // True once we've decided to give up, so the state collector stops auto-retrying.
    @Volatile private var terminating = false
    // Ensures we only attach one state collector even if the activity rebinds repeatedly.
    private var stateCollectorStarted = false
    
    private val _serviceState = MutableStateFlow<StreamState>(StreamState.Idle)
    val serviceState: StateFlow<StreamState> = _serviceState.asStateFlow()

    inner class StreamBinder : Binder() {
        fun getService(): StreamService = this@StreamService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
        Log.d(TAG, "StreamService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val config = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_CONFIG, StreamConfig::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_CONFIG)
                }
                
                if (config != null) {
                    startStreaming(config)
                } else {
                    Log.e(TAG, "No config provided")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopStreaming()
                stopSelf()
            }
        }
        
        return START_NOT_STICKY
    }

    private fun startStreaming(config: StreamConfig) {
        Log.d(TAG, "Starting streaming service")
        // Clear any leftover work from a previous session so we start clean.
        reconnectJob?.cancel(); reconnectJob = null
        watchdogJob?.cancel(); watchdogJob = null
        currentConfig = config
        retryCount = 0
        usingBackup = false
        backupExhausted = false
        forcedReconnects = 0
        terminating = false
        
        // Acquire wake lock to keep CPU running
        acquireWakeLock()
        
        // Start foreground with notification
        val stopIntent = Intent(this, StreamService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val returnIntent = Intent(this, StreamActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val returnPendingIntent = PendingIntent.getActivity(
            this,
            0,
            returnIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val notification = NotificationHelper.buildLiveNotification(
            this,
            stopPendingIntent,
            returnPendingIntent
        )
        
        startForeground(NotificationHelper.NOTIFICATION_ID, notification)

        // Start the actual stream. If the activity hasn't bound its StreamManager yet (cold
        // start race), queue the request instead of silently dropping it — setStreamManager()
        // will replay it.
        val mgr = streamManager
        if (mgr == null) {
            Log.w(TAG, "StreamManager not ready yet — queuing start until bind completes")
            pendingStartConfig = config
        } else {
            mgr.startStream(config)
        }
    }

    private fun stopStreaming() {
        Log.d(TAG, "Stopping streaming service")
        // Cancel pending reconnect/watchdog FIRST so they can't re-publish after the user stops.
        reconnectJob?.cancel(); reconnectJob = null
        watchdogJob?.cancel(); watchdogJob = null
        retryCount = 0
        usingBackup = false
        backupExhausted = false
        forcedReconnects = 0
        pendingStartConfig = null
        streamManager?.stopStream()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "StreamForge::StreamingWakeLock"
            )
        }
        wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes max
        Log.d(TAG, "Wake lock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }

    fun setStreamManager(manager: StreamManager) {
        this.streamManager = manager

        // Replay a start that arrived before we had a manager (cold-start race). Guard on
        // isStreaming so a rebind mid-stream doesn't kick off a duplicate publish.
        pendingStartConfig?.let { cfg ->
            pendingStartConfig = null
            if (!manager.isStreaming()) {
                Log.d(TAG, "Replaying queued start now that StreamManager is bound")
                manager.startStream(cfg)
            }
        }

        // Attach the state collector exactly once. The activity can rebind multiple times
        // (rotation, returning from the media picker); without this guard each rebind added
        // another collector, multiplying the retry/reconnect logic.
        if (stateCollectorStarted) return
        stateCollectorStarted = true
        serviceScope.launch {
            manager.state.collect { state ->
                _serviceState.value = state
                handleStreamState(state)
            }
        }
    }

    private fun handleStreamState(state: StreamState) {
        // Once we've decided to give up, ignore further state churn so the terminal Failed
        // isn't immediately re-retried by the logic below.
        if (terminating) return
        when (state) {
            is StreamState.Failed -> {
                val hasBackup = currentConfig?.backupRtmpUrl?.isNotBlank() == true
                if (retryCount < MAX_RETRY_COUNT) {
                    retryCount++
                    Log.d(TAG, "Stream failed; retry $retryCount/$MAX_RETRY_COUNT" +
                            if (usingBackup) " (backup)" else " (primary)")
                    scheduleReconnect()
                } else if (hasBackup && !usingBackup && !backupExhausted) {
                    // Primary exhausted — flip to backup URL and try MAX_RETRY_COUNT more times.
                    Log.w(TAG, "Primary exhausted; switching to backup URL")
                    usingBackup = true
                    retryCount = 1
                    scheduleReconnect()
                } else {
                    if (usingBackup) backupExhausted = true
                    Log.e(TAG, "All retry attempts exhausted on " +
                            (if (usingBackup) "backup" else "primary") + " — stopping service")
                    stopStreaming()
                    stopSelf()
                }
            }
            is StreamState.Live -> {
                retryCount = 0 // Reset on successful connection
                startNoDataWatchdog()
            }
            else -> {
                // Idle or Connecting - no action needed
            }
        }
    }

    /**
     * RootEncoder reports "Live" the moment the RTMP handshake/publish succeeds — even if the
     * encoder feed never re-linked on a reused pipeline, or YouTube is dropping a stale-key
     * session. Watch the real outbound bitrate; if no bytes leave for a few seconds, the
     * session is dead, so do a clean stop → cooldown → restart (the auto-reconnect other apps
     * rely on). NOTE: this catches a dead encoder/connection, but NOT a deleted/unbound
     * YouTube broadcast — there the bytes are sent and read-then-discarded, so bitrate looks
     * healthy. The only fix for that is to not delete the broadcast before reconnecting.
     */
    private fun startNoDataWatchdog() {
        watchdogJob?.cancel()
        val mgr = streamManager ?: return
        watchdogJob = serviceScope.launch {
            delay(NO_DATA_GRACE_MS)
            var starvedMs = 0L
            while (isActive) {
                if (_serviceState.value !is StreamState.Live) return@launch
                if (mgr.lastBitrateBps > 0) {
                    starvedMs = 0L
                    forcedReconnects = 0 // a healthy, media-carrying session — clear the counter
                } else {
                    starvedMs += WATCHDOG_TICK_MS
                    if (starvedMs >= NO_DATA_TIMEOUT_MS) {
                        handleNoMedia()
                        return@launch
                    }
                }
                delay(WATCHDOG_TICK_MS)
            }
        }
    }

    /** A connected-but-media-less session: clean-reconnect, or give up after too many. */
    private fun handleNoMedia() {
        val config = currentConfig ?: return
        watchdogJob?.cancel(); watchdogJob = null
        forcedReconnects++

        if (forcedReconnects > MAX_FORCED_RECONNECTS) {
            Log.e(TAG, "No media after $MAX_FORCED_RECONNECTS reconnects — giving up")
            terminating = true
            reconnectJob?.cancel(); reconnectJob = null
            streamManager?.stopStream()
            streamManager?.markFailed(
                "YouTube isn't receiving the stream. Make sure live streaming is enabled and " +
                "the previous broadcast has fully ended (don't delete it before reconnecting), " +
                "then Go Live again."
            )
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        Log.w(TAG, "No outbound media — clean reconnect $forcedReconnects/$MAX_FORCED_RECONNECTS")
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            streamManager?.stopStream()             // tear the dead session down fully
            delay(RECONNECT_COOLDOWN_MS)            // let YouTube release the key
            if (!isActive) return@launch
            streamManager?.startStream(config, useBackup = usingBackup)
        }
    }

    private fun scheduleReconnect() {
        val config = currentConfig ?: return
        
        // Update notification to show reconnecting
        val stopIntent = Intent(this, StreamService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val returnIntent = Intent(this, StreamActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val returnPendingIntent = PendingIntent.getActivity(
            this,
            0,
            returnIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val notification = NotificationHelper.buildReconnectingNotification(
            this,
            stopPendingIntent,
            returnPendingIntent,
            retryCount
        )
        
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NotificationHelper.NOTIFICATION_ID, notification)
        
        // Exponential backoff (1s, 4s, 9s), floored so we always give YouTube enough time to
        // release the previous ingest session on this key before re-publishing.
        val delayMs = (retryCount * retryCount * 1000L).coerceAtLeast(MIN_RECONNECT_MS)

        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            delay(delayMs)
            if (!isActive) return@launch
            Log.d(TAG, "Attempting reconnect after ${delayMs}ms delay (backup=$usingBackup)")
            // stopStream() first so the client is clean — the start guard would otherwise
            // skip the reconnect if RootEncoder still thinks it's streaming.
            streamManager?.stopStream()
            streamManager?.startStream(config, useBackup = usingBackup)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        reconnectJob?.cancel(); reconnectJob = null
        watchdogJob?.cancel(); watchdogJob = null
        serviceScope.cancel() // stop the state collector and any in-flight coroutines
        releaseWakeLock()
        Log.d(TAG, "StreamService destroyed")
    }
}
