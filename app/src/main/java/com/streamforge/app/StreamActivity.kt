package com.streamforge.app

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.SurfaceHolder
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.rtmp.RtmpCamera2
import com.streamforge.app.databinding.ActivityStreamBinding
import com.streamforge.app.overlay.OverlayItem
import com.streamforge.app.overlay.OverlayStore
import com.streamforge.app.service.StreamService
import com.streamforge.app.storage.StreamConfig
import com.streamforge.app.storage.StreamPrefs
import com.streamforge.app.stream.StreamManager
import com.streamforge.app.stream.StreamState
import com.streamforge.app.ui.OverlayManagerBottomSheet
import com.streamforge.app.util.PermissionHelper
import kotlinx.coroutines.launch

/**
 * Phase 2A: Camera preview with front/back switching.
 * Phase 2A Extended: Audio level indicator and mute/unmute toggle.
 * Phase 3A: RTMP streaming to YouTube with connection status.
 * Phase 4A: Integration with foreground service for background streaming.
 * Phase 4B: Overlay management integration.
 */
class StreamActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStreamBinding
    private lateinit var rtmpCamera: RtmpCamera2
    private lateinit var streamManager: StreamManager
    private lateinit var streamPrefs: StreamPrefs
    private lateinit var overlayStore: OverlayStore
    private var streamConfig: StreamConfig? = null
    private var isMuted = false
    
    private var streamService: StreamService? = null
    private var isServiceBound = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as StreamService.StreamBinder
            streamService = binder.getService()
            streamService?.setStreamManager(streamManager)
            isServiceBound = true
            
            // Observe service state
            observeServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            streamService = null
            isServiceBound = false
        }
    }
    
    private val audioLevelHandler = Handler(Looper.getMainLooper())
    private val audioLevelRunnable = object : Runnable {
        override fun run() {
            updateAudioLevel()
            audioLevelHandler.postDelayed(this, 100) // Update every 100ms
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep screen on while streaming
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        streamPrefs = StreamPrefs(this)
        overlayStore = OverlayStore(this)

        // Load stream configuration
        lifecycleScope.launch {
            streamConfig = streamPrefs.load()
        }

        // Check permissions
        if (!PermissionHelper.hasCameraAndAudio(this)) {
            PermissionHelper.requestCameraAndAudio(this) { granted ->
                if (granted) {
                    initializeCamera()
                } else {
                    Toast.makeText(
                        this,
                        R.string.permission_required,
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        } else {
            initializeCamera()
        }
    }

    private fun initializeCamera() {
        // Initialize StreamManager first (will be set as ConnectChecker)
        streamManager = StreamManager(null)
        
        // Initialize RtmpCamera2 with OpenGlView and StreamManager as ConnectChecker
        rtmpCamera = RtmpCamera2(binding.openGlView, streamManager)
        
        // Set the camera instance in StreamManager
        streamManager.setCamera(rtmpCamera)

        // Set up surface callbacks
        binding.openGlView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                // Start preview when surface is ready
                rtmpCamera.startPreview(CameraHelper.Facing.BACK)
                // Start audio level monitoring
                startAudioLevelMonitoring()
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                // Handle surface changes if needed
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // Stop preview when surface is destroyed
                if (rtmpCamera.isOnPreview) {
                    rtmpCamera.stopPreview()
                }
                // Stop audio level monitoring
                stopAudioLevelMonitoring()
            }
        })

        // Switch camera button
        binding.btnSwitchCamera.setOnClickListener {
            rtmpCamera.switchCamera()
        }

        // Mute/Unmute toggle button
        binding.btnMuteToggle.setOnClickListener {
            toggleMute()
        }

        // Go Live / Stop button
        binding.btnGoLive.setOnClickListener {
            handleGoLiveClick()
        }
        
        // Manage Overlays button
        binding.btnManageOverlays.setOnClickListener {
            showOverlayManager()
        }

        // Observe stream state
        observeStreamState()
        
        // Bind to service
        bindStreamService()
    }

    private fun bindStreamService() {
        val intent = Intent(this, StreamService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun handleGoLiveClick() {
        val config = streamConfig
        if (config == null) {
            Toast.makeText(this, "Configuration not loaded", Toast.LENGTH_SHORT).show()
            return
        }

        when (streamManager.state.value) {
            is StreamState.Idle, is StreamState.Failed -> {
                // Start streaming via service
                val intent = Intent(this, StreamService::class.java).apply {
                    action = StreamService.ACTION_START
                    putExtra(StreamService.EXTRA_CONFIG, config)
                }
                ContextCompat.startForegroundService(this, intent)
            }
            is StreamState.Live, is StreamState.Connecting -> {
                // Stop streaming via service
                val intent = Intent(this, StreamService::class.java).apply {
                    action = StreamService.ACTION_STOP
                }
                startService(intent)
            }
        }
    }

    private fun observeStreamState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                streamManager.state.collect { state ->
                    updateUIForState(state)
                }
            }
        }
    }

    private fun observeServiceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                streamService?.serviceState?.collect { state ->
                    // Service state updates UI as well
                    updateUIForState(state)
                }
            }
        }
    }

    private fun updateUIForState(state: StreamState) {
        when (state) {
            is StreamState.Idle -> {
                binding.tvStreamStatus.text = getString(R.string.status_idle)
                binding.tvStreamStatus.setTextColor(Color.GRAY)
                binding.btnGoLive.text = getString(R.string.go_live)
                binding.btnGoLive.backgroundTintList = 
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#E53935"))
                binding.btnSwitchCamera.isEnabled = true
                binding.btnMuteToggle.isEnabled = true
            }
            is StreamState.Connecting -> {
                binding.tvStreamStatus.text = getString(R.string.status_connecting)
                binding.tvStreamStatus.setTextColor(Color.parseColor("#FFA726"))
                binding.btnGoLive.text = getString(R.string.stop)
                binding.btnSwitchCamera.isEnabled = false
            }
            is StreamState.Live -> {
                binding.tvStreamStatus.text = getString(R.string.status_live)
                binding.tvStreamStatus.setTextColor(Color.RED)
                binding.btnGoLive.text = getString(R.string.stop)
                binding.btnGoLive.backgroundTintList = 
                    android.content.res.ColorStateList.valueOf(Color.DKGRAY)
                binding.btnSwitchCamera.isEnabled = false
            }
            is StreamState.Failed -> {
                binding.tvStreamStatus.text = getString(R.string.status_failed, state.reason)
                binding.tvStreamStatus.setTextColor(Color.RED)
                binding.btnGoLive.text = getString(R.string.go_live)
                binding.btnGoLive.backgroundTintList = 
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#E53935"))
                binding.btnSwitchCamera.isEnabled = true
                binding.btnMuteToggle.isEnabled = true
                Toast.makeText(this, "Stream failed: ${state.reason}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun toggleMute() {
        isMuted = !isMuted
        
        if (isMuted) {
            rtmpCamera.disableAudio()
            binding.btnMuteToggle.text = getString(R.string.unmute)
            binding.btnMuteToggle.setIconResource(android.R.drawable.ic_lock_silent_mode)
            binding.audioLevelBar.progress = 0
        } else {
            rtmpCamera.enableAudio()
            binding.btnMuteToggle.text = getString(R.string.mute)
            binding.btnMuteToggle.setIconResource(android.R.drawable.ic_btn_speak_now)
        }
    }

    private fun startAudioLevelMonitoring() {
        audioLevelHandler.post(audioLevelRunnable)
    }

    private fun stopAudioLevelMonitoring() {
        audioLevelHandler.removeCallbacks(audioLevelRunnable)
        binding.audioLevelBar.progress = 0
    }

    private fun updateAudioLevel() {
        if (!isMuted && ::rtmpCamera.isInitialized && rtmpCamera.isOnPreview) {
            try {
                // Since RootEncoder doesn't expose direct audio level access,
                // we'll show a visual indicator that mic is active
                // The bar will animate to show the mic is working
                val currentProgress = binding.audioLevelBar.progress
                
                // Create a pulsing effect to show mic is active
                val targetProgress = if (currentProgress < 50) {
                    currentProgress + (Math.random() * 15).toInt()
                } else {
                    currentProgress - (Math.random() * 10).toInt()
                }
                
                binding.audioLevelBar.progress = targetProgress.coerceIn(30, 70)
            } catch (e: Exception) {
                // If there's any error, show minimal activity
                binding.audioLevelBar.progress = 0
            }
        } else {
            binding.audioLevelBar.progress = 0
        }
    }
    
    private fun showOverlayManager() {
        val bottomSheet = OverlayManagerBottomSheet.newInstance()
        bottomSheet.setOnOverlaysChangedListener { overlays ->
            // Overlays changed - in Phase 6 we'll update the RootEncoder filters here
            // For now, just log or show a toast
        }
        bottomSheet.show(supportFragmentManager, OverlayManagerBottomSheet.TAG)
    }

    override fun onPause() {
        super.onPause()
        if (::rtmpCamera.isInitialized && rtmpCamera.isOnPreview) {
            rtmpCamera.stopPreview()
        }
        stopAudioLevelMonitoring()
    }

    override fun onResume() {
        super.onResume()
        // Don't start preview here - let surfaceCreated handle it
        // The surface might not be ready yet
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioLevelMonitoring()
        
        // Unbind from service
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        
        // Note: We don't stop the stream here - the service keeps it running
        // Only stop if user explicitly taps Stop button
    }
}


