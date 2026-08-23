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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.encoder.utils.gl.AspectRatioMode
import com.pedro.library.rtmp.RtmpCamera2
import com.streamforge.app.databinding.ActivityStreamBinding
import com.streamforge.app.overlay.OverlayItem
import com.streamforge.app.overlay.OverlayRenderer
import com.streamforge.app.overlay.OverlayStore
import com.streamforge.app.service.StreamService
import com.streamforge.app.stream.AudioLevelEffect
import com.pedro.library.util.FpsListener
import com.streamforge.app.storage.StreamConfig
import com.streamforge.app.storage.StreamPrefs
import com.streamforge.app.stream.StreamManager
import com.streamforge.app.stream.StreamState
import com.streamforge.app.ui.OverlayManagerBottomSheet
import com.streamforge.app.util.PermissionHelper
import com.streamforge.app.util.isUiAlive
import com.streamforge.app.util.safeLaunch

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
    private var overlayRenderer: OverlayRenderer? = null
    private val audioLevelEffect = AudioLevelEffect()
    private var streamConfig: StreamConfig? = null
    private var isMuted = false
    
    private var streamService: StreamService? = null
    private var isServiceBound = false

    // Set when the preview surface is destroyed while streaming (e.g. the system media
    // picker covers the screen). Triggers an automatic stream restart once the surface
    // — and with it the GL pipeline / encoder feed — is recreated.
    private var pendingStreamRecovery = false
    
    /**
     * Registered as a field, which is the only point in an activity's life where the Activity
     * Result API allows it — registering later (e.g. from inside a permission-check branch
     * that runs after the activity has started) throws IllegalStateException.
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            initializeCamera()
        } else {
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            // The binder can be null or an unexpected type if the service died during bind;
            // an unchecked cast here would crash on a path the user can't even see.
            val binder = service as? StreamService.StreamBinder ?: run {
                android.util.Log.e("StreamActivity", "Service bound without a usable binder")
                return
            }
            if (!::streamManager.isInitialized) {
                android.util.Log.w("StreamActivity", "Service connected before the camera was ready")
                return
            }
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

    // Debounce overlay gesture writes — drag/pinch fires per-frame and writing JSON
    // to DataStore each time was the source of preview jank.
    private val overlayPersistHandler = Handler(Looper.getMainLooper())
    private var pendingOverlayPersist: OverlayItem? = null
    private val overlayPersistRunnable = Runnable {
        val toSave = pendingOverlayPersist ?: return@Runnable
        pendingOverlayPersist = null
        safeLaunch(TAG, "persisting overlay position") {
            overlayStore.updateOverlay(toSave)
        }
    }

    // Phase 7: stats HUD
    private val statsHandler = Handler(Looper.getMainLooper())
    @Volatile private var currentFps: Int = 0
    private var liveStartedAtMs: Long = 0L
    private val statsRunnable = object : Runnable {
        override fun run() {
            updateStatsHud()
            statsHandler.postDelayed(this, 1000)
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

        // Load stream configuration. Guarded: a DataStore read can throw (corrupt file, disk
        // pressure) and an unhandled throw inside launch{} takes the whole process down.
        safeLaunch(TAG, "loading stream config") {
            streamConfig = streamPrefs.load()
            // Size overlays to the real output resolution so they aren't distorted.
            streamConfig?.let { overlayRenderer?.setStreamSize(it.width, it.height) }
        }

        // Check permissions
        if (PermissionHelper.hasCameraAndAudio(this)) {
            initializeCamera()
        } else {
            permissionLauncher.launch(PermissionHelper.REQUIRED_PERMISSIONS)
        }
    }

    private fun initializeCamera() {
        // The permission callback can land on an activity that's already finishing, and
        // nothing stops it firing twice — a second pass would leak a whole camera pipeline and
        // stack duplicate surface callbacks.
        if (isFinishing || isDestroyed || ::rtmpCamera.isInitialized) return

        // Initialize StreamManager first (will be set as ConnectChecker)
        streamManager = StreamManager(null)

        // Initialize RtmpCamera2 with OpenGlView and StreamManager as ConnectChecker
        rtmpCamera = RtmpCamera2(binding.openGlView, streamManager)

        // Set the camera instance in StreamManager
        streamManager.setCamera(rtmpCamera)

        // WYSIWYG preview: show the ENTIRE 16:9 broadcast frame, exactly as it goes out.
        // Adjust keeps the true aspect ratio so full-frame overlays (e.g. a StreamElements
        // ticker anchored at the bottom) are always fully visible and the preview matches
        // YouTube 1:1 — the standard broadcast-preview behaviour. RootEncoder centers the
        // 16:9 preview against the view's black background, so the margins read as a clean
        // canvas frame rather than a bug.
        binding.openGlView.setAspectRatioMode(AspectRatioMode.Adjust)

        // Phase 6: bridge overlay model → RootEncoder filter pipeline.
        // Pass `this` (an Activity) as the window-capable context browser overlays need to
        // host their Presentation; applicationContext can't show windows.
        overlayRenderer = OverlayRenderer(applicationContext, rtmpCamera, this)
        // If config already loaded, size overlays to the output resolution right away.
        streamConfig?.let { overlayRenderer?.setStreamSize(it.width, it.height) }
        setupOverlayEditor()

        // Phase 7: attach a pass-through PCM effect so the level meter reads real RMS,
        // not a fake animation.
        rtmpCamera.setCustomAudioEffect(audioLevelEffect)

        // Phase 7: encoder-side fps for the stats HUD.
        rtmpCamera.setFpsListener(FpsListener.Callback { fps -> currentFps = fps })

        // Phase 7: hand StreamManager the AudioManager so it can pin the preferred mic.
        streamManager.audioManager =
            getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager

        // Set callback to re-apply overlays after encoder preparation.
        // prepareVideo() resets the GL pipeline, so overlays must be re-added.
        // This runs synchronously to ensure overlays are applied before stream starts.
        streamManager.onEncoderPrepared = {
            try {
                val overlays = kotlinx.coroutines.runBlocking {
                    try {
                        overlayStore.loadOverlays()
                    } catch (e: Exception) {
                        android.util.Log.e("StreamActivity", "Failed to load overlays", e)
                        emptyList()
                    }
                }
                android.util.Log.d("StreamActivity", "Re-applying ${overlays.size} overlays after encoder prep")
                // prepareVideo() tore down the GL pipeline (stopPreview → MainRender.release
                // clears all filters). Drop stale filter handles so overlays re-attach fresh
                // with their textures re-uploaded — otherwise they render as empty rectangles.
                overlayRenderer?.onPipelineReset()
                overlayRenderer?.applyOverlays(overlays)
            } catch (e: Exception) {
                android.util.Log.e("StreamActivity", "Failed to re-apply overlays", e)
            }
        }

        // Set up surface callbacks
        binding.openGlView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                try {
                    // Start preview when surface is ready
                    startPreviewAtConfiguredResolution(CameraHelper.Facing.BACK)
                    // Apply persisted overlays now that the GL pipeline is alive.
                    loadAndApplyOverlays()
                    // Start audio level monitoring
                    startAudioLevelMonitoring()
                    // If the surface was destroyed mid-stream (e.g. the media picker covered
                    // us), the encoder feed died with it — transparently restart the stream.
                    if (pendingStreamRecovery) {
                        pendingStreamRecovery = false
                        recoverStreamAfterSurfaceLoss()
                    }
                } catch (e: Exception) {
                    // Camera/GL bring-up is device-dependent and throws on some OEM builds.
                    // A black preview the user can retry beats a dead process.
                    android.util.Log.e("StreamActivity", "surfaceCreated setup failed", e)
                }
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
                // Losing the surface tears down the GL pipeline (OpenGlView.stop()), which
                // kills the live encoder feed. If we were streaming, stop the now-dead
                // stream cleanly and flag it to auto-restart when the surface returns.
                try {
                    val state = streamManager.state.value
                    if (state is StreamState.Live || state is StreamState.Connecting) {
                        pendingStreamRecovery = true
                        streamManager.stopStream()
                    }
                    if (rtmpCamera.isOnPreview) {
                        rtmpCamera.stopPreview()
                    }
                } catch (e: Exception) {
                    // surfaceDestroyed runs during teardown, when the encoder/GL objects are
                    // already half-gone. Throwing here would crash us on the way out.
                    android.util.Log.e("StreamActivity", "surfaceDestroyed cleanup failed", e)
                }
                // Stop audio level monitoring
                stopAudioLevelMonitoring()
            }
        })

        // Switch camera button. RootEncoder declares switchCamera() as throwing
        // CameraOpenException — Kotlin doesn't force us to handle it, so this was an ordinary
        // tap that could kill the app whenever the other camera was busy or unavailable
        // (in use by another app, or a device with only one usable lens).
        binding.btnSwitchCamera.setOnClickListener {
            try {
                rtmpCamera.switchCamera()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "switchCamera failed", e)
                Toast.makeText(this, R.string.switch_camera_failed, Toast.LENGTH_SHORT).show()
            }
        }

        // Rotate screen button: cycle auto → landscape-left → landscape-right → auto.
        // Lets the user override sensor auto-rotate when they want a specific side up.
        binding.btnRotateScreen.setOnClickListener {
            cycleScreenOrientation()
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

        // First-launch path: we only reach initializeCamera() after the runtime permission
        // dialog is granted, by which time the OpenGlView surface was already created (while
        // the dialog was showing). The surfaceCreated callback we just registered therefore
        // won't fire again, so the preview would stay black until Go Live rebuilt the pipeline.
        // If the surface is already up, start the preview now.
        if (binding.openGlView.holder.surface?.isValid == true) {
            startPreviewAtConfiguredResolution(CameraHelper.Facing.BACK)
            loadAndApplyOverlays()
            startAudioLevelMonitoring()
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

    /**
     * Start the camera preview at the user's configured stream resolution so the preview is
     * as sharp as the broadcast. The plain startPreview(facing) overload falls back to the
     * encoder's default 640×480 (prepareVideo hasn't run yet before Go Live), which made the
     * preview look blurry and low-res. Falls back to the default if the exact size isn't
     * supported by the camera.
     */
    private fun startPreviewAtConfiguredResolution(facing: CameraHelper.Facing) {
        if (!::rtmpCamera.isInitialized) return
        // Idempotent: both surfaceCreated and the first-launch immediate-start can call this.
        if (rtmpCamera.isOnPreview) return
        val width = streamConfig?.width ?: StreamConfig.DEFAULT.width
        val height = streamConfig?.height ?: StreamConfig.DEFAULT.height
        try {
            rtmpCamera.startPreview(facing, width, height)
        } catch (e: Exception) {
            android.util.Log.e("StreamActivity", "startPreview at ${width}x$height failed; using default", e)
            try { rtmpCamera.startPreview(facing) } catch (_: Exception) { }
        }
    }

    // Debounce Go Live / Stop taps. Starting a stream has IPC + encoder-config latency before
    // the state flips to Connecting; without this, an impatient double-tap fired a second
    // ACTION_START (or a Stop right after a Start), which is exactly the "had to press Go Live
    // several times / it fought itself" symptom.
    private var lastGoLiveClickMs: Long = 0L

    private fun handleGoLiveClick() {
        val now = System.currentTimeMillis()
        if (now - lastGoLiveClickMs < GO_LIVE_DEBOUNCE_MS) return
        lastGoLiveClickMs = now

        val config = streamConfig
        if (config == null) {
            Toast.makeText(this, "Configuration not loaded", Toast.LENGTH_SHORT).show()
            return
        }

        when (streamManager.state.value) {
            is StreamState.Idle, is StreamState.Failed -> {
                // Pre-flight the stream key here so we give instant feedback instead of
                // spinning up the foreground service just to fail.
                if (config.streamKey.isBlank()) {
                    Toast.makeText(this, R.string.enter_stream_key_first, Toast.LENGTH_LONG).show()
                    return
                }
                // Immediate visual feedback — flip to Connecting now rather than waiting for the
                // service round-trip, so the button stops inviting another tap.
                updateUIForState(StreamState.Connecting)
                if (!requestStreamStart(config)) {
                    updateUIForState(StreamState.Idle)
                }
            }
            is StreamState.Live, is StreamState.Connecting -> {
                // Stop streaming via service
                try {
                    val intent = Intent(this, StreamService::class.java).apply {
                        action = StreamService.ACTION_STOP
                    }
                    startService(intent)
                } catch (e: Exception) {
                    // Stopping must never be the thing that crashes us; fall back to stopping
                    // the encoder directly so the user isn't stuck "live" with a dead button.
                    android.util.Log.e("StreamActivity", "Couldn't deliver stop to the service", e)
                    streamManager.stopStream()
                }
            }
        }
    }

    /**
     * Ask the service to go live. Returns false if the request couldn't be delivered.
     *
     * `startForegroundService` is not safe to call blind: from Android 12 it throws
     * ForegroundServiceStartNotAllowedException whenever the app isn't in the foreground, and
     * we call it from a delayed recovery path that can easily land while the user is elsewhere.
     * Uncaught, that is a hard crash of a *streaming* app — the worst possible moment.
     */
    private fun requestStreamStart(config: StreamConfig): Boolean {
        if (!isUiAlive()) {
            android.util.Log.w("StreamActivity", "Skipping stream start: activity isn't in the foreground")
            return false
        }
        return try {
            val intent = Intent(this, StreamService::class.java).apply {
                action = StreamService.ACTION_START
                putExtra(StreamService.EXTRA_CONFIG, config)
            }
            ContextCompat.startForegroundService(this, intent)
            true
        } catch (e: Exception) {
            android.util.Log.e("StreamActivity", "Foreground service start rejected", e)
            Toast.makeText(this, R.string.stream_foreground_blocked, Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun observeStreamState() {
        safeLaunch(TAG, "observing stream state") {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                streamManager.state.collect { state ->
                    updateUIForState(state)
                }
            }
        }
    }

    private fun observeServiceState() {
        safeLaunch(TAG, "observing service state") {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                streamService?.serviceState?.collect { state ->
                    // Service state updates UI as well
                    updateUIForState(state)
                }
            }
        }
    }

    /**
     * Render a stream state. Wrapped because it runs from two flow collectors: a throw here —
     * a missing string arg, a view already detached — would cancel the collector *and* crash,
     * leaving the UI permanently out of sync with the stream.
     */
    private fun updateUIForState(state: StreamState) {
        try {
            applyUIForState(state)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Rendering state $state failed", e)
        }
    }

    private fun applyUIForState(state: StreamState) {
        when (state) {
            is StreamState.Idle -> {
                binding.tvStreamStatus.text = getString(R.string.status_idle)
                binding.tvStreamStatus.setTextColor(Color.GRAY)
                binding.btnGoLive.text = getString(R.string.go_live)
                binding.btnGoLive.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#E53935"))
                binding.btnSwitchCamera.isEnabled = true
                binding.btnMuteToggle.isEnabled = true
                stopStatsHud()
            }
            is StreamState.Connecting -> {
                binding.tvStreamStatus.text = getString(R.string.status_connecting)
                binding.tvStreamStatus.setTextColor(Color.parseColor("#FFA726"))
                binding.btnGoLive.text = getString(R.string.stop)
                binding.btnSwitchCamera.isEnabled = true
            }
            is StreamState.Live -> {
                binding.tvStreamStatus.text = getString(R.string.status_live)
                binding.tvStreamStatus.setTextColor(Color.RED)
                binding.btnGoLive.text = getString(R.string.stop)
                binding.btnGoLive.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.DKGRAY)
                binding.btnSwitchCamera.isEnabled = true
                startStatsHud()
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
                stopStatsHud()
            }
        }
    }

    private fun toggleMute() {
        if (!::rtmpCamera.isInitialized) return
        val nextMuted = !isMuted
        // enable/disableAudio reach into the running AudioRecord, which can object mid-
        // teardown. Keep isMuted in sync with what actually happened rather than flipping it
        // first and crashing on the call.
        try {
            if (nextMuted) rtmpCamera.disableAudio() else rtmpCamera.enableAudio()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Toggling mute failed", e)
            return
        }
        isMuted = nextMuted

        if (isMuted) {
            binding.btnMuteToggle.setIconResource(R.drawable.ic_mic_off)
            binding.btnMuteToggle.contentDescription = getString(R.string.unmute)
            binding.audioLevelBar.progress = 0
        } else {
            binding.btnMuteToggle.setIconResource(R.drawable.ic_mic)
            binding.btnMuteToggle.contentDescription = getString(R.string.mute)
        }
    }

    private fun startAudioLevelMonitoring() {
        // Idempotent: clear any existing loop before re-posting so two entry points
        // (surfaceCreated / first-launch immediate-start) can't stack duplicate runnables.
        audioLevelHandler.removeCallbacks(audioLevelRunnable)
        audioLevelHandler.post(audioLevelRunnable)
    }

    private fun stopAudioLevelMonitoring() {
        audioLevelHandler.removeCallbacks(audioLevelRunnable)
        binding.audioLevelBar.progress = 0
    }

    private fun updateAudioLevel() {
        if (!isMuted && ::rtmpCamera.isInitialized && rtmpCamera.isOnPreview) {
            binding.audioLevelBar.progress = audioLevelEffect.levelPercent
        } else {
            binding.audioLevelBar.progress = 0
        }
    }

    private fun startStatsHud() {
        if (liveStartedAtMs == 0L) liveStartedAtMs = System.currentTimeMillis()
        binding.tvStatsHud.visibility = android.view.View.VISIBLE
        statsHandler.removeCallbacks(statsRunnable)
        statsHandler.post(statsRunnable)
    }

    private fun stopStatsHud() {
        statsHandler.removeCallbacks(statsRunnable)
        binding.tvStatsHud.visibility = android.view.View.GONE
        liveStartedAtMs = 0L
        currentFps = 0
    }

    private fun updateStatsHud() {
        if (!::rtmpCamera.isInitialized) return
        val kbps = try { rtmpCamera.bitrate / 1024 } catch (_: Exception) { 0 }
        val uptimeMs = if (liveStartedAtMs > 0) System.currentTimeMillis() - liveStartedAtMs else 0L
        binding.tvStatsHud.text = getString(R.string.hud_format, kbps, currentFps, formatUptime(uptimeMs))
    }

    private fun cycleScreenOrientation() {
        val next = when (requestedOrientation) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        requestedOrientation = next
    }

    private fun formatUptime(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
    
    private fun showOverlayManager() {
        // Committing a fragment transaction after the activity has saved its state throws
        // IllegalStateException ("Can not perform this action after onSaveInstanceState"), and
        // a second sheet on top of the first is just as broken. Both are reachable by tapping
        // the button as the activity is going away.
        if (supportFragmentManager.isStateSaved || isFinishing) return
        if (supportFragmentManager.findFragmentByTag(OverlayManagerBottomSheet.TAG) != null) return

        val bottomSheet = OverlayManagerBottomSheet.newInstance()
        bottomSheet.setOnOverlaysChangedListener { overlays ->
            // Persisted store changed (add / delete / visibility / text edit).
            // Push the new list to both the gesture surface and the filter pipeline.
            // Guard: a GL/encoder hiccup here must never crash the activity — an uncaught
            // exception would kill the process, and the relaunch re-routes Login → Home
            // (the "toggling an overlay bounced me back to Home" symptom).
            try {
                binding.overlayEditor.setItems(overlays)
                overlayRenderer?.applyOverlays(overlays)
            } catch (e: Exception) {
                android.util.Log.e("StreamActivity", "applyOverlays from sheet failed", e)
            }
        }
        bottomSheet.setOnOverlayLiveUpdateListener { item ->
            // Live size-slider drag: update preview + GL transform without a full reconcile.
            try {
                binding.overlayEditor.updateItem(item)
                overlayRenderer?.updateOverlay(item)
            } catch (e: Exception) {
                android.util.Log.e("StreamActivity", "live overlay update failed", e)
            }
        }
        bottomSheet.show(supportFragmentManager, OverlayManagerBottomSheet.TAG)
    }

    private fun setupOverlayEditor() {
        // Real preview underneath — only show selection/outline, not filled placeholders.
        binding.overlayEditor.showPlaceholders = false
        // Let the editor size its gesture boxes exactly like the GL pipeline renders each
        // overlay, so a touch selects what the user actually sees (esp. wide/thin tickers).
        binding.overlayEditor.aspectProvider = { id -> overlayRenderer?.aspectFor(id) }
        binding.overlayEditor.setItemChangeListener { item ->
            // Live-update path: a drag / pinch / rotate gesture finished a frame.
            // GL transform update is cheap — apply immediately.
            overlayRenderer?.updateOverlay(item)
            // Persist only after the gesture settles. JSON-encoding + DataStore I/O
            // on every frame previously caused visible preview lag.
            pendingOverlayPersist = item
            overlayPersistHandler.removeCallbacks(overlayPersistRunnable)
            overlayPersistHandler.postDelayed(overlayPersistRunnable, 250)
        }
    }

    /**
     * Restart the stream after the preview surface was destroyed mid-stream (typically
     * the user opening the media picker, which covers us and triggers surfaceDestroyed →
     * GL teardown). The preview was just re-created in surfaceCreated; give the camera a
     * brief moment to settle, then start the stream again through the service so the
     * foreground notification and reconnect logic stay intact. The viewer sees a short
     * disconnect/reconnect rather than a permanently dead stream.
     */
    private fun recoverStreamAfterSurfaceLoss() {
        val config = streamConfig ?: return
        Toast.makeText(this, R.string.stream_recovering, Toast.LENGTH_SHORT).show()
        overlayPersistHandler.postDelayed({
            // Bail if the user already stopped, or we somehow started again in the meantime.
            if (streamManager.state.value is StreamState.Live ||
                streamManager.state.value is StreamState.Connecting) return@postDelayed
            if (!rtmpCamera.isOnPreview) return@postDelayed
            // requestStreamStart refuses (rather than crashes) if we're no longer foreground —
            // the delay means the user may well have navigated away by now.
            requestStreamStart(config)
        }, 600)
    }

    private fun loadAndApplyOverlays() {
        safeLaunch(TAG, "applying saved overlays") {
            val overlays = try {
                overlayStore.loadOverlays()
            } catch (_: Exception) {
                emptyList()
            }
            // Inside the guard: pushing to the gesture surface and the GL pipeline can both
            // throw, and previously did so outside any handler.
            binding.overlayEditor.setItems(overlays)
            overlayRenderer?.applyOverlays(overlays)
        }
    }

    override fun onPause() {
        super.onPause()
        // Do NOT stop the preview while we're broadcasting. The preview surface IS the
        // encoder's video feed, so tearing it down for a transient pause (a dialog, the
        // notification shade, the permission sheet) killed the live stream and kicked off the
        // whole stop/recover/foreground-restart cycle — the most common way the app died
        // mid-broadcast. When the activity really goes away the surface is destroyed, and
        // surfaceDestroyed handles that case properly.
        if (::rtmpCamera.isInitialized && rtmpCamera.isOnPreview && !isStreamingOrConnecting()) {
            try {
                rtmpCamera.stopPreview()
            } catch (e: Exception) {
                android.util.Log.e("StreamActivity", "stopPreview on pause failed", e)
            }
        }
        stopAudioLevelMonitoring()
        flushPendingOverlayPersist()
    }

    private fun isStreamingOrConnecting(): Boolean {
        if (!::streamManager.isInitialized) return false
        val state = streamManager.state.value
        return state is StreamState.Live || state is StreamState.Connecting
    }

    private fun flushPendingOverlayPersist() {
        overlayPersistHandler.removeCallbacks(overlayPersistRunnable)
        val toSave = pendingOverlayPersist ?: return
        pendingOverlayPersist = null
        safeLaunch(TAG, "persisting overlay position") {
            overlayStore.updateOverlay(toSave)
        }
    }

    override fun onResume() {
        super.onResume()
        // Normally surfaceCreated restarts the preview. But a pause that did NOT destroy the
        // surface (a dialog, the picker, the app switcher) leaves us with a valid surface and
        // a stopped preview, and no callback will ever fire — that was the black-preview-after-
        // coming-back report. Restart it here when that's the situation we're in.
        if (!::rtmpCamera.isInitialized) return
        try {
            if (!rtmpCamera.isOnPreview && binding.openGlView.holder.surface?.isValid == true) {
                startPreviewAtConfiguredResolution(currentFacing())
                loadAndApplyOverlays()
            }
            startAudioLevelMonitoring()
        } catch (e: Exception) {
            android.util.Log.e("StreamActivity", "Resuming the preview failed", e)
        }
    }

    private fun currentFacing(): CameraHelper.Facing = try {
        if (rtmpCamera.cameraFacing == CameraHelper.Facing.FRONT) CameraHelper.Facing.FRONT
        else CameraHelper.Facing.BACK
    } catch (_: Exception) {
        CameraHelper.Facing.BACK
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Don't re-init preview while streaming - the encoder can't handle it
        // Let the preview adapt naturally to the new orientation
        if (!::rtmpCamera.isInitialized) return
        if (streamManager.state.value is StreamState.Live ||
            streamManager.state.value is StreamState.Connecting) {
            // Stream is live - don't touch the camera/encoder
            return
        }
        // Only restart preview if we're idle
        if (rtmpCamera.isOnPreview) {
            val facing = if (rtmpCamera.cameraFacing == CameraHelper.Facing.FRONT)
                CameraHelper.Facing.FRONT else CameraHelper.Facing.BACK
            rtmpCamera.stopPreview()
            // Brief delay to let the surface settle
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (::rtmpCamera.isInitialized && !rtmpCamera.isOnPreview) {
                    startPreviewAtConfiguredResolution(facing)
                }
            }, 100)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioLevelMonitoring()
        stopStatsHud()
        flushPendingOverlayPersist()

        // Teardown touches GL, MediaPlayers, WebViews and a bound service, any of which can
        // object at this point. Crashing during onDestroy is both fatal and pointless.
        try { overlayRenderer?.release() } catch (e: Exception) {
            android.util.Log.e("StreamActivity", "Releasing overlays failed", e)
        }
        overlayRenderer = null

        // Unbind from service
        if (isServiceBound) {
            try { unbindService(serviceConnection) } catch (e: Exception) {
                android.util.Log.e("StreamActivity", "unbindService failed", e)
            }
            isServiceBound = false
        }

        // Note: We don't stop the stream here - the service keeps it running
        // Only stop if user explicitly taps Stop button
    }

    private companion object {
        const val TAG = "StreamActivity"

        // Ignore Go Live / Stop taps that land within this window of the previous one.
        const val GO_LIVE_DEBOUNCE_MS = 1500L
    }
}


