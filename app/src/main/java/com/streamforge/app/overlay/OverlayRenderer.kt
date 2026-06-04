package com.streamforge.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.BaseObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.GifObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.TextObjectFilterRender
import com.pedro.library.rtmp.RtmpCamera2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/**
 * Phase 6: Bridges OverlayItem domain model to RootEncoder's GL filter pipeline.
 *
 * Maps each visible OverlayItem to a BaseObjectFilterRender attached to the camera's
 * glInterface. The same filters drive both the on-device preview and the outgoing RTMP
 * stream, so what the user arranges in OverlayEditorView is exactly what viewers see.
 *
 * Coordinate convention:
 *  - OverlayItem.x/y are 0..1 fractions representing the CENTER of the overlay.
 *  - RootEncoder setPosition expects percent (0..100) of the TOP-LEFT corner.
 *  - We use a base size of 20% × item.scale of the stream dimensions, so scale=1.0 is
 *    20% wide; user pinches to grow/shrink via OverlayEditorView gestures.
 */
class OverlayRenderer(
    private val context: Context,
    private val rtmpCamera: RtmpCamera2
) {
    private val filters = mutableMapOf<String, BaseFilterRender>()
    private val bitmaps = mutableMapOf<String, Bitmap>()
    private val videoPlayers = mutableMapOf<String, VideoOverlayPlayer>()
    private val pendingLoads = mutableMapOf<String, Job>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Self-heal backstop. The GL pipeline processes one filter action per rendered
    // frame and only when its render target is ready; a freshly-added overlay can race
    // that state and silently never attach (the old "toggle the eye icon to make it
    // show" symptom). We re-run reconciliation a few times after every change so any
    // missed/transient attach is re-applied automatically — no user action needed.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastItems: List<OverlayItem> = emptyList()
    private var verifyAttempts = 0

    // Ids whose texture has already been force-reloaded since their current filter was
    // attached. The very first GL texture upload can race the render thread and land
    // blank (the "empty square until I toggle the eye icon" symptom). The verify sweep
    // forces exactly one re-upload per attach — the automatic equivalent of the toggle.
    private val texturesHealed = mutableSetOf<String>()

    // News-ticker scrolling state. For each scrolling Text overlay we drive its horizontal
    // position ourselves frame-by-frame instead of using its static x; the map holds the
    // current left-edge position (percent of stream width). A single repeating runnable
    // advances every scrolling overlay so one timer covers any number of tickers.
    private val scrollPositions = mutableMapOf<String, Float>()
    private var scrollRunning = false

    private val scrollRunnable = object : Runnable {
        override fun run() {
            tickScroll()
            if (scrollRunning) mainHandler.postDelayed(this, SCROLL_FRAME_MS)
        }
    }

    private val verifyRunnable = object : Runnable {
        override fun run() {
            reconcile(lastItems, forceTextureReload = true)
            verifyAttempts++
            if (verifyAttempts < MAX_VERIFY_ATTEMPTS) {
                mainHandler.postDelayed(this, VERIFY_INTERVAL_MS)
            }
        }
    }

    /**
     * Reconcile the filter pipeline with the given list of overlays, then schedule a
     * short self-heal sweep so transient GL-readiness races can't leave an overlay
     * invisible. Adds new items, updates existing ones, removes any that vanished
     * or became hidden.
     */
    fun applyOverlays(items: List<OverlayItem>) {
        lastItems = items
        verifyAttempts = 0
        mainHandler.removeCallbacks(verifyRunnable)
        reconcile(items)
        mainHandler.postDelayed(verifyRunnable, VERIFY_INTERVAL_MS)
    }

    private fun reconcile(items: List<OverlayItem>, forceTextureReload: Boolean = false) {
        android.util.Log.d("OverlayRenderer", "applyOverlays called with ${items.size} items")
        val visible = items.filter { it.visible }
        android.util.Log.d("OverlayRenderer", "Visible items: ${visible.size}, Current filters: ${filters.size}")
        val visibleIds = visible.map { it.id }.toSet()

        filters.keys.toList()
            .filter { it !in visibleIds }
            .forEach { 
                android.util.Log.d("OverlayRenderer", "Removing overlay $it (not in visible list)")
                removeOverlay(it) 
            }
        pendingLoads.keys.toList()
            .filter { it !in visibleIds }
            .forEach { pendingLoads.remove(it)?.cancel() }

        visible.sortedBy { it.zIndex }.forEach { item ->
            if (filters.containsKey(item.id)) {
                android.util.Log.d("OverlayRenderer", "Updating existing overlay ${item.id}")
                updateOverlay(item)
                // Heal a possibly-blank first texture upload, once per attach.
                if (forceTextureReload) healTextureOnce(item)
            } else {
                android.util.Log.d("OverlayRenderer", "Adding new overlay ${item.id} (type: ${item::class.simpleName})")
                addOverlay(item)
            }
        }
        android.util.Log.d("OverlayRenderer", "applyOverlays complete. Filters: ${filters.size}, Bitmaps cached: ${bitmaps.size}, GIFs cached: ${gifBytes.size}")
        updateScrollLoop()
    }

    /**
     * Start or stop the ticker animation depending on whether any visible scrolling text
     * overlay currently exists, and drop scroll state for overlays that no longer scroll.
     */
    private fun updateScrollLoop() {
        val activeIds = lastItems
            .filter { it is OverlayItem.Text && it.scroll && it.visible }
            .map { it.id }
            .toSet()
        scrollPositions.keys.retainAll(activeIds)

        if (activeIds.isNotEmpty() && !scrollRunning) {
            scrollRunning = true
            mainHandler.post(scrollRunnable)
        } else if (activeIds.isEmpty() && scrollRunning) {
            scrollRunning = false
            mainHandler.removeCallbacks(scrollRunnable)
        }
    }

    /**
     * Advance every scrolling text overlay one frame: move its left edge leftward and wrap
     * back to the right edge once it has fully exited on the left.
     */
    private fun tickScroll() {
        lastItems.forEach { item ->
            if (item !is OverlayItem.Text || !item.scroll || !item.visible) return@forEach
            val filter = filters[item.id] as? BaseObjectFilterRender ?: return@forEach
            val sizePercent = 20f * item.scale
            var x = scrollPositions[item.id] ?: 100f
            x -= SCROLL_SPEED_PERCENT_PER_FRAME
            // Wrap once the whole overlay has slid off the left edge.
            if (x <= -sizePercent) x = 100f
            scrollPositions[item.id] = x
            val topLeftY = (item.y * 100f) - (sizePercent / 2f)
            filter.setPosition(x, topLeftY)
        }
    }

    /**
     * Live-update path for a single overlay (drag / pinch / rotate / text edit).
     * Falls through to add if the overlay isn't registered yet.
     */
    fun updateOverlay(item: OverlayItem) {
        if (!item.visible) {
            removeOverlay(item.id)
            return
        }
        val filter = filters[item.id]
        if (filter == null) {
            if (!pendingLoads.containsKey(item.id)) addOverlay(item)
            return
        }
        if (item is OverlayItem.Text && filter is TextObjectFilterRender) {
            filter.setText(item.text, item.fontSizeSp, item.colorArgb)
        }
        applyTransform(filter, item)
    }

    private fun addOverlay(item: OverlayItem) {
        when (item) {
            is OverlayItem.Text -> attachFilter(item, buildTextFilter(item))
            is OverlayItem.Video -> attachFilter(item, buildVideoFilter(item))
            is OverlayItem.Image -> loadAndAttachImage(item)
            is OverlayItem.Gif -> loadAndAttachGif(item)
        }
    }

    private fun loadAndAttachImage(item: OverlayItem.Image) {
        if (pendingLoads.containsKey(item.id) || filters.containsKey(item.id)) {
            android.util.Log.d("OverlayRenderer", "Skipping image ${item.id} - already pending or attached")
            return
        }
        
        // If bitmap is already cached, reuse it immediately
        val cachedBitmap = bitmaps[item.id]
        if (cachedBitmap != null && !cachedBitmap.isRecycled) {
            android.util.Log.d("OverlayRenderer", "Reusing cached bitmap for ${item.id}")
            val filter = ImageObjectFilterRender().apply { setImage(cachedBitmap) }
            attachFilter(item, filter)
            return
        }
        
        android.util.Log.d("OverlayRenderer", "Loading image ${item.id} from ${item.uri}")
        pendingLoads[item.id] = scope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeBitmap(item.uri) }
            pendingLoads.remove(item.id)
            if (bitmap == null) {
                android.util.Log.e("OverlayRenderer", "Failed to decode bitmap for ${item.id}")
                return@launch
            }
            if (!item.visible) {
                android.util.Log.d("OverlayRenderer", "Item ${item.id} no longer visible, recycling bitmap")
                bitmap.recycle()
                return@launch
            }
            if (filters.containsKey(item.id)) {
                android.util.Log.d("OverlayRenderer", "Filter already exists for ${item.id}, recycling new bitmap")
                bitmap.recycle()
                return@launch
            }
            bitmaps[item.id] = bitmap
            android.util.Log.d("OverlayRenderer", "Bitmap loaded for ${item.id}, creating filter")
            val filter = ImageObjectFilterRender().apply { setImage(bitmap) }
            attachFilter(item, filter)
        }
    }

    private val gifBytes = mutableMapOf<String, ByteArray>()

    private fun loadAndAttachGif(item: OverlayItem.Gif) {
        if (pendingLoads.containsKey(item.id) || filters.containsKey(item.id)) return
        
        // If GIF bytes are already cached, reuse them immediately
        val cachedBytes = gifBytes[item.id]
        if (cachedBytes != null) {
            val filter = try {
                GifObjectFilterRender().apply { setGif(ByteArrayInputStream(cachedBytes)) }
            } catch (_: Exception) {
                return
            }
            attachFilter(item, filter)
            return
        }
        
        pendingLoads[item.id] = scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(Uri.parse(item.uri))?.use { it.readBytes() }
                } catch (_: Exception) {
                    null
                }
            }
            pendingLoads.remove(item.id)
            if (bytes == null || !item.visible || filters.containsKey(item.id)) return@launch
            gifBytes[item.id] = bytes
            val filter = try {
                GifObjectFilterRender().apply { setGif(ByteArrayInputStream(bytes)) }
            } catch (_: Exception) {
                return@launch
            }
            attachFilter(item, filter)
        }
    }

    private fun attachFilter(item: OverlayItem, filter: BaseFilterRender) {
        applyTransform(filter, item)
        try {
            rtmpCamera.glInterface.addFilter(filter)
            filters[item.id] = filter
            // Fresh filter instance — its texture upload hasn't been verified yet.
            texturesHealed.remove(item.id)
            // Timing-proof backstop: re-upload this overlay's texture shortly after the
            // attach lands, in case the first GL upload raced the render thread and came
            // out blank. Deduped via texturesHealed so it fires at most once per attach.
            if (item is OverlayItem.Image || item is OverlayItem.Gif) {
                mainHandler.postDelayed({ healTextureOnce(item) }, VERIFY_INTERVAL_MS)
            }
            android.util.Log.d("OverlayRenderer", "Successfully attached filter for ${item.id}")
        } catch (e: Exception) {
            // glInterface may not be ready yet — caller will retry via applyOverlays.
            android.util.Log.e("OverlayRenderer", "Failed to attach filter for ${item.id}", e)
            bitmaps.remove(item.id)?.recycle()
            videoPlayers.remove(item.id)?.release()
        }
    }

    fun removeOverlay(id: String) {
        pendingLoads.remove(id)?.cancel()
        texturesHealed.remove(id)
        val filter = filters.remove(id) ?: return
        try {
            rtmpCamera.glInterface.removeFilter(filter)
        } catch (_: Exception) { }
        videoPlayers.remove(id)?.release()
        // Don't recycle bitmaps or clear gif bytes - keep them cached for re-application
        // bitmaps.remove(item.id)?.recycle()
        // gifBytes.remove(item.id)
    }

    /**
     * The GL pipeline was torn down and rebuilt out from under us — going live calls
     * prepareVideo(), which calls stopPreview(), which releases every filter and clears
     * MainRender's filter list. Our cached filter handles are now stale: a plain
     * applyOverlays() would see them as still-attached and only nudge transforms, leaving
     * empty rectangles with no texture. Drop the stale handles (and detach any that did
     * survive, to avoid duplicates) so the *next* applyOverlays() re-adds every overlay
     * from scratch and re-uploads its texture. Bitmap / GIF caches are kept for speed.
     */
    fun onPipelineReset() {
        mainHandler.removeCallbacksAndMessages(null)
        scrollRunning = false
        scrollPositions.clear()
        pendingLoads.values.forEach { it.cancel() }
        pendingLoads.clear()
        filters.keys.toList().forEach { id ->
            filters.remove(id)?.let { f ->
                try { rtmpCamera.glInterface.removeFilter(f) } catch (_: Exception) { }
            }
        }
        videoPlayers.values.forEach { it.release() }
        videoPlayers.clear()
        texturesHealed.clear()
    }

    /**
     * Re-upload an overlay's texture at most once per attach (deduped via texturesHealed).
     * Both the post-attach backstop and the verify sweep funnel through here, so a given
     * filter instance is healed exactly once no matter which fires first.
     */
    private fun healTextureOnce(item: OverlayItem) {
        if (item.id in texturesHealed) return
        if (reloadTexture(item)) texturesHealed.add(item.id)
    }

    /**
     * Force a one-shot texture re-upload for an attached image/GIF overlay.
     * Re-issuing setImage/setGif flips the filter's shouldLoad flag, so the GL thread
     * releases the (possibly blank) texture and uploads it again on the next frame —
     * the same effect as toggling visibility off/on, without detaching the filter.
     * Returns true if a reload was issued.
     */
    private fun reloadTexture(item: OverlayItem): Boolean {
        val filter = filters[item.id] ?: return false
        return when {
            item is OverlayItem.Image && filter is ImageObjectFilterRender -> {
                val bmp = bitmaps[item.id]
                if (bmp != null && !bmp.isRecycled) {
                    filter.setImage(bmp)
                    android.util.Log.d("OverlayRenderer", "Healed texture for image ${item.id}")
                    true
                } else false
            }
            item is OverlayItem.Gif && filter is GifObjectFilterRender -> {
                val bytes = gifBytes[item.id] ?: return false
                try {
                    filter.setGif(ByteArrayInputStream(bytes))
                    android.util.Log.d("OverlayRenderer", "Healed texture for gif ${item.id}")
                    true
                } catch (_: Exception) {
                    false
                }
            }
            else -> false
        }
    }
    
    /**
     * Permanently delete an overlay and its cached resources.
     * Use this when an overlay is deleted from the store, not just hidden.
     */
    fun deleteOverlay(id: String) {
        removeOverlay(id)
        bitmaps.remove(id)?.recycle()
        gifBytes.remove(id)
    }

    /**
     * Tear down all overlays and release MediaPlayers. Call from Activity.onDestroy.
     */
    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        scrollRunning = false
        scrollPositions.clear()
        pendingLoads.values.forEach { it.cancel() }
        pendingLoads.clear()
        filters.keys.toList().forEach { id ->
            filters.remove(id)?.let { filter ->
                try {
                    rtmpCamera.glInterface.removeFilter(filter)
                } catch (_: Exception) { }
            }
            videoPlayers.remove(id)?.release()
        }
        bitmaps.values.forEach { it.recycle() }
        bitmaps.clear()
        gifBytes.clear()
        texturesHealed.clear()
        scope.cancel()
    }

    private fun applyTransform(filter: BaseFilterRender, item: OverlayItem) {
        if (filter !is BaseObjectFilterRender) return
        val baseSize = 20f
        val sizePercent = baseSize * item.scale
        val topLeftY = (item.y * 100f) - (sizePercent / 2f)
        if (item is OverlayItem.Text && item.scroll) {
            // The ticker loop owns the horizontal position; seed it (off the right edge)
            // and let tickScroll drive it from here so the two don't fight.
            val x = scrollPositions.getOrPut(item.id) { 100f }
            filter.setPosition(x, topLeftY)
        } else {
            val topLeftX = (item.x * 100f) - (sizePercent / 2f)
            filter.setPosition(topLeftX, topLeftY)
            scrollPositions.remove(item.id)
        }
        filter.setScale(sizePercent, sizePercent)
        filter.setRotation(item.rotation.toInt())
    }

    private fun buildTextFilter(item: OverlayItem.Text): TextObjectFilterRender {
        val filter = TextObjectFilterRender()
        filter.setText(item.text, item.fontSizeSp, item.colorArgb)
        return filter
    }

    private fun buildVideoFilter(item: OverlayItem.Video): BaseObjectFilterRender {
        val player = VideoOverlayPlayer(context, Uri.parse(item.uri), item.loop)
        videoPlayers[item.id] = player
        return player.filter
    }

    /**
     * Decode with inSampleSize so a 12MP camera photo (~48MB) doesn't allocate
     * a giant bitmap. Overlays render at <=20% of stream width — anything larger
     * than ~1024px on the long edge is wasted memory and GPU upload time.
     */
    private fun decodeBitmap(uriString: String): Bitmap? = try {
        val uri = Uri.parse(uriString)
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calcSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, MAX_OVERLAY_EDGE_PX)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri).use { stream ->
            BitmapFactory.decodeStream(stream, null, opts)
        }
    } catch (_: Exception) {
        null
    }

    private fun calcSampleSize(srcW: Int, srcH: Int, maxEdge: Int): Int {
        if (srcW <= 0 || srcH <= 0) return 1
        val longEdge = maxOf(srcW, srcH)
        var sample = 1
        while (longEdge / sample > maxEdge) sample *= 2
        return sample
    }

    private companion object {
        const val MAX_OVERLAY_EDGE_PX = 1024

        // Self-heal sweep: re-reconcile at t = 300ms and 600ms after each change.
        // Bounded (no infinite loop); reconcile is idempotent for already-attached
        // items and only (re)adds genuinely-missing or failed ones.
        const val VERIFY_INTERVAL_MS = 300L
        const val MAX_VERIFY_ATTEMPTS = 2

        // News-ticker animation: advance ~33ms/frame (~30fps). At 0.35%/frame the text
        // crosses the full width in ~10s — readable, like a broadcast lower-third ticker.
        const val SCROLL_FRAME_MS = 33L
        const val SCROLL_SPEED_PERCENT_PER_FRAME = 0.35f
    }
}
