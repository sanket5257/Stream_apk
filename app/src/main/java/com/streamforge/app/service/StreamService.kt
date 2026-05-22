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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    }

    private val binder = StreamBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private var streamManager: StreamManager? = null
    private var retryCount = 0
    private var currentConfig: StreamConfig? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    
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
        currentConfig = config
        retryCount = 0
        
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
        
        // Start the actual stream
        streamManager?.startStream(config)
    }

    private fun stopStreaming() {
        Log.d(TAG, "Stopping streaming service")
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
        
        // Monitor stream state for auto-reconnect
        serviceScope.launch {
            manager.state.collect { state ->
                _serviceState.value = state
                handleStreamState(state)
            }
        }
    }

    private fun handleStreamState(state: StreamState) {
        when (state) {
            is StreamState.Failed -> {
                if (retryCount < MAX_RETRY_COUNT) {
                    retryCount++
                    Log.d(TAG, "Stream failed, attempting reconnect $retryCount/$MAX_RETRY_COUNT")
                    scheduleReconnect()
                } else {
                    Log.e(TAG, "Max retry attempts reached, stopping service")
                    stopStreaming()
                    stopSelf()
                }
            }
            is StreamState.Live -> {
                retryCount = 0 // Reset on successful connection
            }
            else -> {
                // Idle or Connecting - no action needed
            }
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
        
        // Exponential backoff: 1s, 4s, 9s
        val delayMs = (retryCount * retryCount * 1000L)
        
        serviceScope.launch {
            delay(delayMs)
            Log.d(TAG, "Attempting reconnect after ${delayMs}ms delay")
            streamManager?.startStream(config)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        Log.d(TAG, "StreamService destroyed")
    }
}
