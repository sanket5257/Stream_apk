package com.streamforge.app.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import com.streamforge.app.R
import com.streamforge.app.StreamActivity
import com.streamforge.app.storage.StreamConfig
import com.streamforge.app.stream.StreamManager
import com.streamforge.app.stream.StreamState
import com.streamforge.app.util.safeLaunch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive

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

        // Held for the length of a broadcast. The old 10-minute cap silently expired mid-stream,
        // and releasing an already-expired lock is one of the ways release() throws.
        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L
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
        // Every path out of here is guarded. onStartCommand runs on the main thread, so an
        // escaping exception — a rejected foreground start, a bad parcel, an encoder that
        // throws on configure — kills the process, and the relaunch drops the user back at the
        // login screen mid-stream.
        try {
            when (intent?.action) {
                ACTION_START -> {
                    val config = readConfig(intent)
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
                else -> {
                    // Nothing to do, but the system may have delivered this via
                    // startForegroundService and be waiting for a startForeground() that is
                    // never coming. Stop immediately so it doesn't time us out instead.
                    Log.w(TAG, "Ignoring start command with action=${intent?.action}")
                    stopSelf()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onStartCommand failed", t)
            failStream("Couldn't start the streaming service")
            stopStreaming()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun readConfig(intent: Intent): StreamConfig? = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_CONFIG, StreamConfig::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_CONFIG)
        }
    } catch (t: Throwable) {
        Log.e(TAG, "Couldn't read stream config from intent", t)
        null
    }

    /** Report a terminal problem through the same state channel the UI already observes. */
    private fun failStream(reason: String) {
        terminating = true
        _serviceState.value = StreamState.Failed(reason)
        streamManager?.markFailed(reason)
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

        if (!enterForeground(notification)) {
            // Android refused the foreground promotion — see enterForeground. Don't start the
            // encoder for a session the system is about to kill.
            releaseWakeLock()
            failStream(getString(R.string.stream_foreground_blocked))
            stopSelf()
            return
        }

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

    /**
     * Promote to a foreground service, declaring the camera + microphone types explicitly.
     *
     * This is one of the app's real crash sources. On Android 12+ a foreground service started
     * while the app is in the background throws ForegroundServiceStartNotAllowedException, and
     * on Android 14+ a camera/microphone service whose permission was revoked throws
     * SecurityException — both from inside startForeground(), on the main thread, uncaught.
     * That is exactly the "opened the gallery picker, came back, app was gone" report: the
     * surface-loss auto-recovery re-issued a foreground start while we were still backgrounded.
     *
     * Returning false lets the caller report a real message instead of dying.
     */
    private fun enterForeground(notification: android.app.Notification): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NotificationHelper.NOTIFICATION_ID,
            notification,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
        )
        true
    } catch (t: Throwable) {
        Log.e(TAG, "startForeground rejected by the system", t)
        false
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
        try { streamManager?.stopStream() } catch (t: Throwable) { Log.e(TAG, "stopStream failed", t) }
        releaseWakeLock()
        leaveForeground()
    }

    /** stopForeground can throw if we were never promoted; stopping must never crash. */
    private fun leaveForeground() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (t: Throwable) {
            Log.w(TAG, "stopForeground failed", t)
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "StreamForge::StreamingWakeLock"
                )
            }
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            Log.d(TAG, "Wake lock acquired")
        } catch (t: Throwable) {
            // A missing wake lock costs battery-saver resilience, not correctness.
            Log.w(TAG, "Could not acquire wake lock", t)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "Wake lock released")
                }
            }
        } catch (t: Throwable) {
            // release() throws if the timeout already expired and dropped the last reference.
            Log.w(TAG, "Wake lock release failed", t)
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
        serviceScope.safeLaunch(TAG, "stream state collector") {
            manager.state.collect { state ->
                _serviceState.value = state
                try {
                    handleStreamState(state)
                } catch (t: Throwable) {
                    // Keep the collector alive: if retry/reconnect bookkeeping throws once, the
                    // stream should degrade, not take the process with it — and losing the
                    // collector would leave the notification stuck on a dead session.
                    Log.e(TAG, "Handling state $state failed", t)
                }
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
        watchdogJob = serviceScope.safeLaunch(TAG, "no-data watchdog") {
            delay(NO_DATA_GRACE_MS)
            var starvedMs = 0L
            while (isActive) {
                if (_serviceState.value !is StreamState.Live) return@safeLaunch
                if (mgr.lastBitrateBps > 0) {
                    starvedMs = 0L
                    forcedReconnects = 0 // a healthy, media-carrying session — clear the counter
                } else {
                    starvedMs += WATCHDOG_TICK_MS
                    if (starvedMs >= NO_DATA_TIMEOUT_MS) {
                        handleNoMedia()
                        return@safeLaunch
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
            leaveForeground()
            stopSelf()
            return
        }

        Log.w(TAG, "No outbound media — clean reconnect $forcedReconnects/$MAX_FORCED_RECONNECTS")
        reconnectJob?.cancel()
        reconnectJob = serviceScope.safeLaunch(TAG, "reconnect") {
            streamManager?.stopStream()             // tear the dead session down fully
            delay(RECONNECT_COOLDOWN_MS)            // let YouTube release the key
            if (!isActive) return@safeLaunch
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
        
        try {
            val notificationManager =
                getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.notify(NotificationHelper.NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            // A notification we can't post is cosmetic; the reconnect below still runs.
            Log.w(TAG, "Couldn't update the reconnect notification", t)
        }


        // Exponential backoff (1s, 4s, 9s), floored so we always give YouTube enough time to
        // release the previous ingest session on this key before re-publishing.
        val delayMs = (retryCount * retryCount * 1000L).coerceAtLeast(MIN_RECONNECT_MS)

        reconnectJob?.cancel()
        reconnectJob = serviceScope.safeLaunch(TAG, "reconnect") {
            delay(delayMs)
            if (!isActive) return@safeLaunch
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
