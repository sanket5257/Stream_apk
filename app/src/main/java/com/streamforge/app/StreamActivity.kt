package com.streamforge.app

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.SurfaceHolder
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.encoder.utils.gl.AspectRatioMode
import com.pedro.library.multiple.MultiRtpCamera2
import com.pedro.library.util.FpsListener
import com.streamforge.app.billing.EntitlementStore
import com.streamforge.app.databinding.ActivityStreamBinding
import com.streamforge.app.overlay.OverlayItem
import com.streamforge.app.overlay.OverlayRenderer
import com.streamforge.app.overlay.OverlayStore
import com.streamforge.app.packs.PackAction
import com.streamforge.app.packs.PackCatalog
import com.streamforge.app.scenes.Scene
import com.streamforge.app.scenes.SceneStore
import com.streamforge.app.service.StreamService
import com.streamforge.app.storage.DestinationStore
import com.streamforge.app.storage.StreamConfig
import com.streamforge.app.storage.StreamPrefs
import com.streamforge.app.stream.AudioLevelEffect
import com.streamforge.app.stream.Destination
import com.streamforge.app.stream.StreamManager
import com.streamforge.app.stream.StreamState
import com.streamforge.app.ui.OverlayManagerBottomSheet
import com.streamforge.app.ui.studio.StudioBottomChrome
import com.streamforge.app.ui.studio.StudioCallbacks
import com.streamforge.app.ui.studio.StudioPack
import com.streamforge.app.ui.studio.StudioPanel
import com.streamforge.app.ui.studio.StudioTopChrome
import com.streamforge.app.ui.studio.StudioUiState
import com.streamforge.app.ui.theme.StreamForgeCameraTheme
import com.streamforge.app.util.PermissionHelper
import com.streamforge.app.util.isUiAlive
import com.streamforge.app.util.safeAction
import com.streamforge.app.util.safeAction1
import com.streamforge.app.util.safeAction2
import com.streamforge.app.util.safeLaunch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The studio: camera, overlays, graphics packs, scenes, and going live.
 *
 * Structure — the split matters more than anything else in this file:
 *  - RootEncoder's OpenGlView and the OverlayEditorView stay NATIVE views. The GL surface is
 *    the encoder's video feed and cannot be a composable.
 *  - Everything else on screen is Compose, hosted in two wrap_content ComposeViews pinned to
 *    the top and bottom (see activity_stream.xml for why it is two and not one).
 *
 * The streaming logic below — the surface-loss recovery, the Go Live debounce, the guarded
 * foreground-service start — is carried over unchanged from the pre-Compose version. It is
 * the result of chasing real field crashes, and none of it is incidental.
 */
class StreamActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStreamBinding
    private lateinit var camera: MultiRtpCamera2
    private lateinit var streamManager: StreamManager
    private lateinit var streamPrefs: StreamPrefs
    private lateinit var overlayStore: OverlayStore
    private lateinit var destinationStore: DestinationStore
    private lateinit var sceneStore: SceneStore

    private var overlayRenderer: OverlayRenderer? = null
    private val audioLevelEffect = AudioLevelEffect()
    private var streamConfig: StreamConfig? = null
    private var packCatalog: PackCatalog? = null

    /** The show's overlays, kept in memory so pack edits and scene switches are instant. */
    private var overlays: List<OverlayItem> = emptyList()
    private var scenes: List<Scene> = emptyList()

    private var streamService: StreamService? = null
    private var isServiceBound = false

    /** Everything the Compose chrome renders. */
    private var uiState by mutableStateOf(StudioUiState())

    // Set when the preview surface is destroyed while streaming (e.g. the system media picker
    // covers the screen). Triggers an automatic stream restart once the surface — and with it
    // the GL pipeline / encoder feed — is recreated.
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
                android.util.Log.e(TAG, "Service bound without a usable binder")
                return
            }
            if (!::streamManager.isInitialized) {
                android.util.Log.w(TAG, "Service connected before the camera was ready")
                return
            }
            streamService = binder.getService()
            streamService?.setStreamManager(streamManager)
            isServiceBound = true
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
            audioLevelHandler.postDelayed(this, 100)
        }
    }

    // Debounce overlay writes — drag/pinch fires per-frame, and writing JSON to DataStore each
    // time was the source of preview jank.
    private val overlayPersistHandler = Handler(Looper.getMainLooper())
    private var pendingOverlayPersist: OverlayItem? = null
    private val overlayPersistRunnable = Runnable {
        val toSave = pendingOverlayPersist ?: return@Runnable
        pendingOverlayPersist = null
        safeLaunch(TAG, "persisting overlay") { overlayStore.updateOverlay(toSave) }
    }

    private val statsHandler = Handler(Looper.getMainLooper())
    @Volatile private var currentFps: Int = 0
    private var liveStartedAtMs: Long = 0L
    private val statsRunnable = object : Runnable {
        override fun run() {
            updateStatsHud()
            statsHandler.postDelayed(this, 1000)
        }
    }

    /**
     * Every studio control, wrapped.
     *
     * These fire on the main thread from the Compose chrome, so an uncaught throw in any of
     * them ends the process — and doing that mid-broadcast is the single worst failure this
     * app has. The handlers below already guard the calls known to be fragile (the encoder,
     * GL, the camera); [safeAction] catches whatever is left, so the worst case is a control
     * that appears not to respond while the stream keeps running.
     */
    private val callbacks = StudioCallbacks(
        onGoLive = safeAction(TAG, "going live") { handleGoLiveClick() },
        onStop = safeAction(TAG, "stopping the stream") { handleGoLiveClick() },
        onSwitchCamera = safeAction(TAG, "switching camera") { switchCamera() },
        onToggleMute = safeAction(TAG, "toggling mute") { toggleMute() },
        onRotate = safeAction(TAG, "rotating the screen") { cycleScreenOrientation() },
        onManageOverlays = safeAction(TAG, "opening the overlay manager") { showOverlayManager() },
        onTogglePanel = safeAction1(TAG, "opening a panel") { panel: StudioPanel ->
            uiState = uiState.copy(
                openPanel = if (uiState.openPanel == panel) StudioPanel.NONE else panel
            )
        },
        onSelectPack = safeAction1(TAG, "selecting a pack") { id: String ->
            uiState = uiState.copy(activePackOverlayId = id)
        },
        onPackAction = safeAction2(TAG, "applying a pack action") { overlayId: String, action: PackAction ->
            applyPackAction(overlayId, action)
        },
        onSelectScene = safeAction1(TAG, "switching scene") { id: String -> selectScene(id) },
        onSaveScene = safeAction1(TAG, "saving a scene") { id: String -> saveScene(id) },
        onExit = safeAction(TAG, "leaving the studio") { finish() },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        streamPrefs = StreamPrefs(this)
        overlayStore = OverlayStore(this)
        destinationStore = DestinationStore(this)
        sceneStore = SceneStore(this)

        setupChrome()

        // Load configuration. Guarded: a DataStore read can throw (corrupt file, disk
        // pressure) and an unhandled throw inside launch{} takes the whole process down.
        safeLaunch(TAG, "loading studio state") {
            streamConfig = streamPrefs.load()
            streamConfig?.let { overlayRenderer?.setStreamSize(it.width, it.height) }

            packCatalog = PackCatalog.load(this@StreamActivity)

            val destinations = destinationStore.load()
            streamManager.takeIf { ::streamManager.isInitialized }?.destinations = destinations

            scenes = sceneStore.load()
            val entitlement = withContext(Dispatchers.IO) { EntitlementStore(this@StreamActivity).current() }

            uiState = uiState.copy(
                destinations = destinationStore.activeOf(destinations),
                scenes = scenes,
                activeSceneId = sceneStore.lastActiveId(),
                tier = entitlement.effectiveTier(),
            )
            refreshPackState()
        }

        if (PermissionHelper.hasCameraAndAudio(this)) {
            initializeCamera()
        } else {
            permissionLauncher.launch(PermissionHelper.REQUIRED_PERMISSIONS)
        }
    }

    private fun setupChrome() {
        // The activity is the lifecycle owner, so the default composition strategy is right;
        // setting it explicitly documents that these views live and die with the screen.
        binding.studioTop.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.studioBottom.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.studioTop.setContent {
            StreamForgeCameraTheme { StudioTopChrome(uiState, callbacks) }
        }
        binding.studioBottom.setContent {
            StreamForgeCameraTheme { StudioBottomChrome(uiState, callbacks) }
        }
    }

    private fun initializeCamera() {
        // The permission callback can land on an activity that's already finishing, and
        // nothing stops it firing twice — a second pass would leak a whole camera pipeline and
        // stack duplicate surface callbacks.
        if (isFinishing || isDestroyed || ::camera.isInitialized) return

        streamManager = StreamManager(null)

        // MULTISTREAM: the client array length is fixed at construction, so it is sized to the
        // destination cap once here. Slots beyond the destinations actually in use simply
        // never get dialled.
        camera = MultiRtpCamera2(
            binding.openGlView,
            streamManager.buildConnectCheckers(DestinationStore.MAX_ACTIVE),
            emptyArray(),
        )
        streamManager.setCamera(camera)

        // WYSIWYG preview: show the ENTIRE 16:9 broadcast frame, exactly as it goes out.
        // Adjust keeps the true aspect ratio so full-frame overlays are always fully visible
        // and the preview matches the destination 1:1.
        binding.openGlView.setAspectRatioMode(AspectRatioMode.Adjust)

        // Pass `this` (an Activity) as the window-capable context browser overlays need to
        // host their Presentation; applicationContext can't show windows.
        overlayRenderer = OverlayRenderer(applicationContext, camera, this)
        streamConfig?.let { overlayRenderer?.setStreamSize(it.width, it.height) }
        setupOverlayEditor()

        // Pass-through PCM effect so the level meter reads real RMS, not a fake animation.
        camera.setCustomAudioEffect(audioLevelEffect)
        camera.setFpsListener(FpsListener.Callback { fps -> currentFps = fps })

        streamManager.audioManager =
            getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager

        // Re-apply overlays after encoder preparation: prepareVideo() resets the GL pipeline,
        // so overlays must be re-added. Runs synchronously to ensure they're applied before
        // the stream starts.
        streamManager.onEncoderPrepared = {
            try {
                val items = kotlinx.coroutines.runBlocking {
                    try {
                        overlayStore.loadOverlays()
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Failed to load overlays", e)
                        emptyList()
                    }
                }
                overlays = items
                // prepareVideo() tore down the GL pipeline (stopPreview → MainRender.release
                // clears all filters). Drop stale filter handles so overlays re-attach fresh
                // with their textures re-uploaded — otherwise they render as empty rectangles.
                overlayRenderer?.onPipelineReset()
                overlayRenderer?.applyOverlays(applied(items))
                reapplyActiveScene()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to re-apply overlays", e)
            }
        }

        binding.openGlView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                try {
                    startPreviewAtConfiguredResolution(CameraHelper.Facing.BACK)
                    loadAndApplyOverlays()
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
                    android.util.Log.e(TAG, "surfaceCreated setup failed", e)
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // Losing the surface tears down the GL pipeline (OpenGlView.stop()), which
                // kills the live encoder feed. If we were streaming, stop the now-dead stream
                // cleanly and flag it to auto-restart when the surface returns.
                try {
                    val state = streamManager.state.value
                    if (state is StreamState.Live || state is StreamState.Connecting) {
                        pendingStreamRecovery = true
                        streamManager.stopStream()
                    }
                    if (camera.isOnPreview) camera.stopPreview()
                } catch (e: Exception) {
                    // surfaceDestroyed runs during teardown, when the encoder/GL objects are
                    // already half-gone. Throwing here would crash us on the way out.
                    android.util.Log.e(TAG, "surfaceDestroyed cleanup failed", e)
                }
                stopAudioLevelMonitoring()
            }
        })

        // First-launch path: we only reach initializeCamera() after the runtime permission
        // dialog is granted, by which time the OpenGlView surface was already created (while
        // the dialog was showing). The surfaceCreated callback we just registered therefore
        // won't fire again, so the preview would stay black until Go Live rebuilt the pipeline.
        if (binding.openGlView.holder.surface?.isValid == true) {
            startPreviewAtConfiguredResolution(CameraHelper.Facing.BACK)
            loadAndApplyOverlays()
            startAudioLevelMonitoring()
        }

        observeStreamState()
        observeDestinationStates()
        bindStreamService()
    }

    private fun bindStreamService() {
        val intent = Intent(this, StreamService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    /**
     * Start the camera preview at the user's configured stream resolution so the preview is as
     * sharp as the broadcast. The plain startPreview(facing) overload falls back to the
     * encoder's default 640×480 (prepareVideo hasn't run yet before Go Live), which made the
     * preview look blurry. Falls back to the default if the exact size isn't supported.
     */
    private fun startPreviewAtConfiguredResolution(facing: CameraHelper.Facing) {
        if (!::camera.isInitialized) return
        // Idempotent: both surfaceCreated and the first-launch immediate-start can call this.
        if (camera.isOnPreview) return
        val width = streamConfig?.width ?: StreamConfig.DEFAULT.width
        val height = streamConfig?.height ?: StreamConfig.DEFAULT.height
        try {
            camera.startPreview(facing, width, height)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "startPreview at ${width}x$height failed; using default", e)
            try { camera.startPreview(facing) } catch (_: Exception) { }
        }
    }

    // Debounce Go Live / Stop taps. Starting a stream has IPC + encoder-config latency before
    // the state flips to Connecting; without this, an impatient double-tap fired a second
    // ACTION_START (or a Stop right after a Start) — the "had to press Go Live several times /
    // it fought itself" symptom.
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

        // The chrome is on screen from the moment the activity draws, but the camera pipeline
        // is only built once the permission dialog is answered. Tapping Go Live in that gap
        // would touch an uninitialised lateinit and take the process down.
        if (!::streamManager.isInitialized) {
            Toast.makeText(this, "Camera is still starting up", Toast.LENGTH_SHORT).show()
            return
        }

        when (streamManager.state.value) {
            is StreamState.Idle, is StreamState.Failed -> {
                // Pre-flight here so we give instant feedback instead of spinning up the
                // foreground service just to fail.
                if (uiState.destinations.isEmpty() && config.streamKey.isBlank()) {
                    Toast.makeText(this, R.string.enter_stream_key_first, Toast.LENGTH_LONG).show()
                    return
                }
                // Immediate visual feedback — flip to Connecting now rather than waiting for
                // the service round-trip, so the button stops inviting another tap.
                renderState(StreamState.Connecting)
                if (!requestStreamStart(config)) renderState(StreamState.Idle)
            }
            is StreamState.Live, is StreamState.Connecting -> {
                try {
                    val intent = Intent(this, StreamService::class.java).apply {
                        action = StreamService.ACTION_STOP
                    }
                    startService(intent)
                } catch (e: Exception) {
                    // Stopping must never be the thing that crashes us; fall back to stopping
                    // the encoder directly so the user isn't stuck "live" with a dead button.
                    android.util.Log.e(TAG, "Couldn't deliver stop to the service", e)
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
     * we call it from a delayed recovery path that can easily land while the user is
     * elsewhere. Uncaught, that is a hard crash of a *streaming* app — the worst moment.
     */
    private fun requestStreamStart(config: StreamConfig): Boolean {
        if (!isUiAlive()) {
            android.util.Log.w(TAG, "Skipping stream start: activity isn't in the foreground")
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
            android.util.Log.e(TAG, "Foreground service start rejected", e)
            Toast.makeText(this, R.string.stream_foreground_blocked, Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun observeStreamState() {
        safeLaunch(TAG, "observing stream state") {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                streamManager.state.collect { renderState(it) }
            }
        }
    }

    private fun observeDestinationStates() {
        safeLaunch(TAG, "observing destination states") {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                streamManager.destinationStates.collect { states ->
                    uiState = uiState.copy(destinationStates = states)
                }
            }
        }
    }

    private fun observeServiceState() {
        safeLaunch(TAG, "observing service state") {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                streamService?.serviceState?.collect { renderState(it) }
            }
        }
    }

    /**
     * Render a stream state. Wrapped because it runs from several flow collectors: a throw
     * here would cancel the collector *and* crash, leaving the UI permanently out of sync
     * with the stream.
     */
    private fun renderState(state: StreamState) {
        try {
            uiState = uiState.copy(streamState = state)
            when (state) {
                is StreamState.Live -> startStatsHud()
                is StreamState.Failed -> {
                    stopStatsHud()
                    Toast.makeText(this, "Stream failed: ${state.reason}", Toast.LENGTH_LONG).show()
                }
                is StreamState.Idle -> stopStatsHud()
                is StreamState.Connecting -> Unit
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Rendering state $state failed", e)
        }
    }

    private fun switchCamera() {
        // RootEncoder declares switchCamera() as throwing CameraOpenException — Kotlin doesn't
        // force us to handle it, so this was an ordinary tap that could kill the app whenever
        // the other camera was busy or unavailable.
        try {
            camera.switchCamera()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "switchCamera failed", e)
            Toast.makeText(this, R.string.switch_camera_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleMute() {
        if (!::camera.isInitialized) return
        val nextMuted = !uiState.muted
        // enable/disableAudio reach into the running AudioRecord, which can object mid-
        // teardown. Keep the flag in sync with what actually happened rather than flipping it
        // first and crashing on the call.
        try {
            if (nextMuted) camera.disableAudio() else camera.enableAudio()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Toggling mute failed", e)
            return
        }
        uiState = uiState.copy(muted = nextMuted, audioLevel = if (nextMuted) 0 else uiState.audioLevel)
    }

    private fun startAudioLevelMonitoring() {
        // Idempotent: clear any existing loop before re-posting so two entry points
        // (surfaceCreated / first-launch immediate-start) can't stack duplicate runnables.
        audioLevelHandler.removeCallbacks(audioLevelRunnable)
        audioLevelHandler.post(audioLevelRunnable)
    }

    private fun stopAudioLevelMonitoring() {
        audioLevelHandler.removeCallbacks(audioLevelRunnable)
        uiState = uiState.copy(audioLevel = 0)
    }

    private fun updateAudioLevel() {
        val level = if (!uiState.muted && ::camera.isInitialized && camera.isOnPreview) {
            audioLevelEffect.levelPercent
        } else 0
        if (level != uiState.audioLevel) uiState = uiState.copy(audioLevel = level)
    }

    private fun startStatsHud() {
        if (liveStartedAtMs == 0L) liveStartedAtMs = System.currentTimeMillis()
        statsHandler.removeCallbacks(statsRunnable)
        statsHandler.post(statsRunnable)
    }

    private fun stopStatsHud() {
        statsHandler.removeCallbacks(statsRunnable)
        liveStartedAtMs = 0L
        currentFps = 0
        uiState = uiState.copy(bitrateKbps = 0, fps = 0, uptime = "")
    }

    private fun updateStatsHud() {
        if (!::camera.isInitialized) return
        val kbps = try { camera.bitrate / 1024 } catch (_: Exception) { 0 }
        val uptimeMs = if (liveStartedAtMs > 0) System.currentTimeMillis() - liveStartedAtMs else 0L
        uiState = uiState.copy(
            bitrateKbps = kbps,
            fps = currentFps,
            uptime = formatUptime(uptimeMs),
        )
    }

    private fun cycleScreenOrientation() {
        requestedOrientation = when (requestedOrientation) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun formatUptime(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    // -----------------------------------------------------------------------------------
    // Graphics packs — the live control path
    // -----------------------------------------------------------------------------------

    /**
     * Apply a live-control action ("+4", "Wicket", "Next headline") to a pack on air.
     *
     * The whole point of this path is that it never touches the encoder or the connection: the
     * pack's values change, the overlay re-rasterizes into the same GL filter, and viewers see
     * the new number on the next frame. Persisting is debounced so rapid tapping doesn't hit
     * DataStore once per tap.
     */
    private fun applyPackAction(overlayId: String, action: PackAction) {
        val item = overlays.firstOrNull { it.id == overlayId } as? OverlayItem.Pack ?: return
        val definition = packCatalog?.byId(item.packId) ?: return

        val merged = definition.defaultValues() + item.values
        val next = action.apply(merged) { key -> definition.fields.firstOrNull { it.key == key } }
        if (next == merged) return

        val updated = item.copy(values = next)
        overlays = overlays.map { if (it.id == overlayId) updated else it }

        try {
            overlayRenderer?.updateOverlay(updated)
            binding.overlayEditor.updateItem(updated)
        } catch (e: Exception) {
            // A GL hiccup must cost the graphic update, not the broadcast.
            android.util.Log.e(TAG, "Applying pack action failed", e)
        }

        refreshPackState()
        pendingOverlayPersist = updated
        overlayPersistHandler.removeCallbacks(overlayPersistRunnable)
        overlayPersistHandler.postDelayed(overlayPersistRunnable, PACK_PERSIST_DEBOUNCE_MS)
    }

    // -----------------------------------------------------------------------------------
    // Free-tier watermark
    // -----------------------------------------------------------------------------------

    /**
     * The overlay list actually pushed to the renderer and the gesture surface.
     *
     * On the free tier this appends a watermark. It is deliberately NOT part of [overlays]:
     * that list is what gets persisted, and a watermark written into the user's saved overlays
     * would survive an upgrade and be editable/deletable from the overlay manager — which
     * would make the gate meaningless AND leave a stray overlay behind for paying customers.
     */
    private fun applied(items: List<OverlayItem>): List<OverlayItem> =
        if (uiState.tier.hasWatermark) items + watermarkOverlay(items) else items

    private fun watermarkOverlay(items: List<OverlayItem>): OverlayItem = OverlayItem.Text(
        id = WATERMARK_ID,
        text = getString(R.string.app_name),
        colorArgb = 0xCCFFFFFF.toInt(),
        x = 0.88f,
        y = 0.07f,
        scale = 0.5f,
        heightScale = 0.5f,
        // Above everything the user has added, and not draggable — it isn't theirs to move.
        zIndex = (items.maxOfOrNull { it.zIndex } ?: 0) + 1000,
        locked = true,
    )

    /** Rebuild the pack list the control panel renders from the current overlays. */
    private fun refreshPackState() {
        val catalog = packCatalog
        val packs = if (catalog == null) emptyList() else {
            overlays.filterIsInstance<OverlayItem.Pack>().mapNotNull { overlay ->
                catalog.byId(overlay.packId)?.let { StudioPack(overlay, it) }
            }
        }
        uiState = uiState.copy(
            packs = packs,
            activePackOverlayId = uiState.activePackOverlayId
                ?.takeIf { id -> packs.any { it.overlay.id == id } }
                ?: packs.firstOrNull()?.overlay?.id,
        )
    }

    // -----------------------------------------------------------------------------------
    // Scenes
    // -----------------------------------------------------------------------------------

    /**
     * Switch to a scene. Only overlay ALPHA changes in the GL pipeline, so this is safe and
     * instant mid-broadcast — no filter is added, removed, or re-uploaded, and the RTMP
     * connection is untouched.
     */
    private fun selectScene(sceneId: String) {
        val scene = scenes.firstOrNull { it.id == sceneId } ?: return
        uiState = uiState.copy(activeSceneId = sceneId)

        val withValues = scene.applyPackValues(overlays)
        if (withValues !== overlays) {
            overlays = withValues
            try {
                withValues.filterIsInstance<OverlayItem.Pack>().forEach {
                    overlayRenderer?.updateOverlay(it)
                }
                binding.overlayEditor.setItems(applied(withValues))
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Applying scene pack values failed", e)
            }
            safeLaunch(TAG, "persisting scene pack values") { overlayStore.saveOverlays(withValues) }
            refreshPackState()
        }

        try {
            overlayRenderer?.applySceneVisibility(scene.hiddenIdsFor(overlays))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Applying scene visibility failed", e)
        }
        safeLaunch(TAG, "saving active scene") { sceneStore.setLastActiveId(sceneId) }
    }

    /** Re-assert the active scene after the GL pipeline was rebuilt (e.g. going live). */
    private fun reapplyActiveScene() {
        val scene = scenes.firstOrNull { it.id == uiState.activeSceneId } ?: return
        try {
            overlayRenderer?.applySceneVisibility(scene.hiddenIdsFor(overlays))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Re-applying scene visibility failed", e)
        }
    }

    /** Long-press on a scene chip: capture the current arrangement into that scene. */
    private fun saveScene(sceneId: String) {
        val existing = scenes.firstOrNull { it.id == sceneId } ?: return
        val hidden = overlayRenderer?.sceneHiddenIds().orEmpty()
        val captured = Scene.capture(existing.name, existing.order, overlays, hidden)
            .copy(id = existing.id)
        scenes = scenes.map { if (it.id == sceneId) captured else it }
        uiState = uiState.copy(scenes = scenes)
        safeLaunch(TAG, "saving scene") { sceneStore.upsert(captured) }
        Toast.makeText(this, "Saved “${existing.name}”", Toast.LENGTH_SHORT).show()
    }

    // -----------------------------------------------------------------------------------
    // Overlays
    // -----------------------------------------------------------------------------------

    private fun showOverlayManager() {
        // Committing a fragment transaction after the activity has saved its state throws
        // IllegalStateException, and a second sheet on top of the first is just as broken.
        // Both are reachable by tapping the button as the activity is going away.
        if (supportFragmentManager.isStateSaved || isFinishing) return
        if (supportFragmentManager.findFragmentByTag(OverlayManagerBottomSheet.TAG) != null) return

        val sheet = OverlayManagerBottomSheet.newInstance()
        sheet.setOnOverlaysChangedListener { items ->
            // Persisted store changed (add / delete / visibility / text edit). Push the new
            // list to both the gesture surface and the filter pipeline. Guarded: an uncaught
            // exception here would kill the process, and the relaunch re-routes Login → Home
            // (the "toggling an overlay bounced me back to Home" symptom).
            try {
                overlays = items
                binding.overlayEditor.setItems(applied(items))
                overlayRenderer?.applyOverlays(applied(items))
                reapplyActiveScene()
                refreshPackState()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "applyOverlays from sheet failed", e)
            }
        }
        sheet.setOnOverlayLiveUpdateListener { item ->
            // Live size-slider drag: update preview + GL transform without a full reconcile.
            try {
                overlays = overlays.map { if (it.id == item.id) item else it }
                binding.overlayEditor.updateItem(item)
                overlayRenderer?.updateOverlay(item)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "live overlay update failed", e)
            }
        }
        sheet.show(supportFragmentManager, OverlayManagerBottomSheet.TAG)
    }

    private fun setupOverlayEditor() {
        // Real preview underneath — only show selection/outline, not filled placeholders.
        binding.overlayEditor.showPlaceholders = false
        // Let the editor size its gesture boxes exactly like the GL pipeline renders each
        // overlay, so a touch selects what the user actually sees.
        binding.overlayEditor.aspectProvider = { id -> overlayRenderer?.aspectFor(id) }
        binding.overlayEditor.setItemChangeListener { item ->
            // GL transform update is cheap — apply immediately.
            overlayRenderer?.updateOverlay(item)
            overlays = overlays.map { if (it.id == item.id) item else it }
            // Persist only after the gesture settles. JSON-encoding + DataStore I/O on every
            // frame previously caused visible preview lag.
            pendingOverlayPersist = item
            overlayPersistHandler.removeCallbacks(overlayPersistRunnable)
            overlayPersistHandler.postDelayed(overlayPersistRunnable, 250)
        }
    }

    /**
     * Restart the stream after the preview surface was destroyed mid-stream (typically the
     * user opening the media picker, which covers us and triggers surfaceDestroyed → GL
     * teardown). The preview was just re-created in surfaceCreated; give the camera a moment
     * to settle, then start again through the service so the foreground notification and
     * reconnect logic stay intact. The viewer sees a short reconnect, not a dead stream.
     */
    private fun recoverStreamAfterSurfaceLoss() {
        val config = streamConfig ?: return
        Toast.makeText(this, R.string.stream_recovering, Toast.LENGTH_SHORT).show()
        overlayPersistHandler.postDelayed({
            // Bail if the user already stopped, or we somehow started again in the meantime.
            if (streamManager.state.value is StreamState.Live ||
                streamManager.state.value is StreamState.Connecting
            ) return@postDelayed
            if (!camera.isOnPreview) return@postDelayed
            // requestStreamStart refuses (rather than crashes) if we're no longer foreground —
            // the delay means the user may well have navigated away by now.
            requestStreamStart(config)
        }, 600)
    }

    private fun loadAndApplyOverlays() {
        safeLaunch(TAG, "applying saved overlays") {
            val items = try {
                overlayStore.loadOverlays()
            } catch (_: Exception) {
                emptyList()
            }
            overlays = items
            // Inside the guard: pushing to the gesture surface and the GL pipeline can both
            // throw, and previously did so outside any handler.
            binding.overlayEditor.setItems(applied(items))
            overlayRenderer?.applyOverlays(applied(items))
            reapplyActiveScene()
            refreshPackState()
        }
    }

    // -----------------------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------------------

    override fun onPause() {
        super.onPause()
        // Do NOT stop the preview while we're broadcasting. The preview surface IS the
        // encoder's video feed, so tearing it down for a transient pause (a dialog, the
        // notification shade, the permission sheet) killed the live stream and kicked off the
        // whole stop/recover/foreground-restart cycle — the most common way the app died
        // mid-broadcast. When the activity really goes away the surface is destroyed, and
        // surfaceDestroyed handles that case properly.
        if (::camera.isInitialized && camera.isOnPreview && !isStreamingOrConnecting()) {
            try {
                camera.stopPreview()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "stopPreview on pause failed", e)
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
        safeLaunch(TAG, "persisting overlay") { overlayStore.updateOverlay(toSave) }
    }

    override fun onResume() {
        super.onResume()
        // Normally surfaceCreated restarts the preview. But a pause that did NOT destroy the
        // surface (a dialog, the picker, the app switcher) leaves us with a valid surface and
        // a stopped preview, and no callback will ever fire — that was the black-preview-
        // after-coming-back report. Restart it here when that's the situation we're in.
        if (!::camera.isInitialized) return
        try {
            if (!camera.isOnPreview && binding.openGlView.holder.surface?.isValid == true) {
                startPreviewAtConfiguredResolution(currentFacing())
                loadAndApplyOverlays()
            }
            startAudioLevelMonitoring()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Resuming the preview failed", e)
        }
        // Destinations can be edited from the home screen between visits.
        safeLaunch(TAG, "refreshing destinations") {
            val destinations = destinationStore.load()
            streamManager.destinations = destinations
            uiState = uiState.copy(destinations = destinationStore.activeOf(destinations))
        }
    }

    private fun currentFacing(): CameraHelper.Facing = try {
        if (camera.cameraFacing == CameraHelper.Facing.FRONT) CameraHelper.Facing.FRONT
        else CameraHelper.Facing.BACK
    } catch (_: Exception) {
        CameraHelper.Facing.BACK
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::camera.isInitialized) return
        // Don't re-init the preview while streaming — the encoder can't handle it. Let the
        // preview adapt naturally to the new orientation.
        if (isStreamingOrConnecting()) return
        if (camera.isOnPreview) {
            val facing = currentFacing()
            camera.stopPreview()
            // Brief delay to let the surface settle.
            Handler(Looper.getMainLooper()).postDelayed({
                if (::camera.isInitialized && !camera.isOnPreview) {
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
            android.util.Log.e(TAG, "Releasing overlays failed", e)
        }
        overlayRenderer = null

        if (isServiceBound) {
            try { unbindService(serviceConnection) } catch (e: Exception) {
                android.util.Log.e(TAG, "unbindService failed", e)
            }
            isServiceBound = false
        }

        // Note: we don't stop the stream here — the service keeps it running. Only an explicit
        // Stop ends the broadcast.
    }

    private companion object {
        const val TAG = "StreamActivity"

        // Ignore Go Live / Stop taps that land within this window of the previous one.
        const val GO_LIVE_DEBOUNCE_MS = 1500L

        // Scoring is tapped in bursts ("+4" then "Ball" then "+1"); wait for the burst to end
        // before writing to disk.
        const val PACK_PERSIST_DEBOUNCE_MS = 600L

        // Id of the synthetic free-tier watermark. Fixed (not random) so repeated applies
        // reuse the same GL filter instead of stacking a new one on every reconcile.
        const val WATERMARK_ID = "sf-watermark"
    }
}

