package com.streamforge.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.BaseObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.GifObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.library.base.Camera2Base
import com.streamforge.app.packs.PackCatalog
import com.streamforge.app.packs.PackRasterizer
import com.streamforge.app.packs.PackTheme
import com.streamforge.app.util.CrashReporter
import com.streamforge.app.util.safeLaunch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import kotlin.math.ceil
import kotlin.math.roundToInt

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
    private val rtmpCamera: Camera2Base,
    // Window-capable context (an Activity) used to host browser-overlay Presentations.
    // Defaults to [context] for callers that don't use browser overlays.
    private val uiContext: Context = context
) {
    private val filters = mutableMapOf<String, BaseFilterRender>()
    private val bitmaps = mutableMapOf<String, Bitmap>()
    private val videoPlayers = mutableMapOf<String, VideoOverlayPlayer>()
    private val browserSources = mutableMapOf<String, BrowserOverlaySource>()
    private val pendingLoads = mutableMapOf<String, Job>()

    // Native content aspect ratio (width / height) per overlay, used to size the overlay
    // box without distorting it. Populated as each overlay's content becomes known
    // (bitmap decoded, text measured, gif bounds read, browser render size). Absent = use
    // the stream aspect (i.e. no correction — the legacy behaviour).
    private val contentAspect = mutableMapOf<String, Float>()

    // Content signature (text + font + colour + scroll) of the bitmap currently uploaded for
    // each text overlay. Re-rasterizing is expensive and updateOverlay() runs on every drag /
    // pinch frame and on every verify sweep, so we only redraw when the content really changed.
    private val textSignatures = mutableMapOf<String, String>()

    // Output stream dimensions, needed to convert a content aspect ratio into the
    // independent width%/height% RootEncoder's setScale expects. Updated by the host
    // whenever the configured resolution changes; defaults to a 16:9 frame.
    private var streamWidth: Int = 1280
    private var streamHeight: Int = 720

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

    // News-ticker state. One repeating runnable advances every scrolling overlay, so a single
    // timer covers any number of tickers.
    private var scrollRunning = false

    // The band-sized strip each ticker's line is drawn onto (see tickScroll).
    private val tickerStrips = mutableMapOf<String, TickerStrip>()

    private val scrollRunnable = object : Runnable {
        override fun run() {
            tickScroll()
            if (scrollRunning) mainHandler.postDelayed(this, SCROLL_FRAME_MS)
        }
    }

    private val verifyRunnable = object : Runnable {
        override fun run() {
            // Own guard: this runs on the main looper, outside any caller's try/catch, so an
            // escaping throw here is a process kill during an ordinary self-heal sweep.
            try {
                reconcile(lastItems, forceTextureReload = true)
            } catch (t: Throwable) {
                android.util.Log.e("OverlayRenderer", "Verify sweep failed", t)
            }
            verifyAttempts++
            if (verifyAttempts < MAX_VERIFY_ATTEMPTS) {
                mainHandler.postDelayed(this, VERIFY_INTERVAL_MS)
            }
        }
    }

    // Consecutive watchdog ticks that saw fewer filters in the GL pipeline than we believe
    // are attached. Requires a streak so a normal drain (one filter action per rendered
    // frame) isn't mistaken for a lost pipeline.
    private var detachedStreak = 0

    private val attachWatchdog = object : Runnable {
        override fun run() {
            // As with verifyRunnable: main-looper work needs its own guard, and this one must
            // keep re-arming even after a failure or overlays stop self-healing entirely.
            try {
                checkStillAttached()
            } catch (t: Throwable) {
                android.util.Log.e("OverlayRenderer", "Attach watchdog failed", t)
            }
            // A rebuild inside checkStillAttached() re-arms this runnable via applyOverlays,
            // so clear any pending tick first — otherwise each rebuild doubles the watchdog.
            mainHandler.removeCallbacks(this)
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    /**
     * Going live tears the GL pipeline down and back up (prepareVideo → stopPreview →
     * MainRender.release, which releases every filter AND clears its list). Anything that
     * rebuilds the pipeline after we've re-attached — a late restart, a surface bounce —
     * leaves our filter handles pointing at objects the renderer no longer draws, and
     * reconcile() would see them as attached and never re-add them. That's an overlay that
     * silently vanishes the moment you go live.
     *
     * glInterface.filtersCount() is the pipeline's own count, so compare against it and
     * rebuild from scratch when it says our overlays aren't there.
     */
    private fun checkStillAttached() {
        val expected = filters.size
        if (expected == 0) {
            detachedStreak = 0
            return
        }
        val actual = try {
            rtmpCamera.glInterface.filtersCount()
        } catch (_: Exception) {
            return
        }
        if (actual >= expected) {
            detachedStreak = 0
            return
        }
        detachedStreak++
        if (detachedStreak < WATCHDOG_STRIKES) return
        detachedStreak = 0
        android.util.Log.w(
            "OverlayRenderer",
            "GL pipeline reports $actual filters but $expected are attached — re-adding overlays"
        )
        val items = lastItems
        // Drops the stale handles; the re-apply then rebuilds every overlay and re-uploads
        // its texture. Queued-but-unprocessed adds are cancelled by the matching removes,
        // so this can't leave duplicates behind.
        onPipelineReset()
        applyOverlays(items)
    }

    /**
     * Reconcile the filter pipeline with the given list of overlays, then schedule a
     * short self-heal sweep so transient GL-readiness races can't leave an overlay
     * invisible. Adds new items, updates existing ones, removes any that vanished
     * or became hidden.
     */
    /**
     * Tell the renderer the output resolution so it can size overlays without distorting
     * them. Safe to call any time; takes effect on the next reconcile.
     */
    fun setStreamSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            streamWidth = width
            streamHeight = height
        }
    }

    /**
     * Best-known content aspect ratio (width / height) for an overlay, or null if it hasn't
     * been measured yet. The editor view uses this to draw and hit-test each overlay's box at
     * the same size the GL pipeline renders it, so a touch lands on what the user sees.
     */
    fun aspectFor(id: String): Float? = contentAspect[id]

    fun applyOverlays(items: List<OverlayItem>) {
        lastItems = items
        verifyAttempts = 0
        mainHandler.removeCallbacks(verifyRunnable)
        reconcile(items)
        mainHandler.postDelayed(verifyRunnable, VERIFY_INTERVAL_MS)
        // (Re)arm the watchdog that notices if the GL pipeline drops our filters later on.
        detachedStreak = 0
        mainHandler.removeCallbacks(attachWatchdog)
        mainHandler.postDelayed(attachWatchdog, WATCHDOG_INTERVAL_MS)
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
            try {
                if (filters.containsKey(item.id)) {
                    android.util.Log.d("OverlayRenderer", "Updating existing overlay ${item.id}")
                    updateOverlay(item)
                    // Heal a possibly-blank first texture upload, once per attach.
                    if (forceTextureReload) healTextureOnce(item)
                } else {
                    android.util.Log.d("OverlayRenderer", "Adding new overlay ${item.id} (type: ${item::class.simpleName})")
                    addOverlay(item)
                }
            } catch (t: Throwable) {
                // Isolate per-overlay failures so one bad item can't crash the whole apply.
                // Throwable, not Exception: attaching an overlay uploads a texture, so an
                // OutOfMemoryError here is entirely possible and must not be fatal — it should
                // cost that one overlay, not the broadcast.
                android.util.Log.e("OverlayRenderer", "Failed to reconcile overlay ${item.id}", t)
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
        tickerStrips.keys.retainAll(activeIds)

        if (activeIds.isNotEmpty() && !scrollRunning) {
            scrollRunning = true
            mainHandler.post(scrollRunnable)
        } else if (activeIds.isEmpty() && scrollRunning) {
            scrollRunning = false
            mainHandler.removeCallbacks(scrollRunnable)
        }
    }

    /**
     * Advance every scrolling text overlay one frame.
     *
     * A ticker is ONE unbroken horizontal line, whatever its length — so it is never routed
     * through the wrapped-text rasterizer. Instead the quad is pinned to the ticker's band and
     * we draw the line straight into a band-sized strip at a shifting offset, re-uploading it
     * each frame. That gives exact clipping at the band's edges (RootEncoder has no per-filter
     * clip) and, because only the band is ever a texture, no message is long enough to hit the
     * GL texture-size cap — which is what used to force a long ticker to wrap onto two lines.
     */
    private fun tickScroll() {
        lastItems.forEach { item ->
            if (item !is OverlayItem.Text || !item.scroll || !item.visible) return@forEach
            val filter = filters[item.id] as? ImageObjectFilterRender ?: return@forEach
            // This runs on the ticker's own repeating runnable, OUTSIDE reconcile's per-item
            // guard. A filter torn down by a visibility toggle can throw here — an uncaught
            // exception on this loop crashes the whole app (the "toggling kicks me back to
            // Home" symptom, since a restart re-routes through Login → Home). Isolate it.
            try {
                advanceTicker(item, filter)
            } catch (e: OutOfMemoryError) {
                // Strip allocation under memory pressure: drop it and coast on the last frame.
                android.util.Log.e("OverlayRenderer", "ticker strip OOM for ${item.id}", e)
                tickerStrips.remove(item.id)
            } catch (t: Throwable) {
                android.util.Log.e("OverlayRenderer", "tickScroll failed for ${item.id}", t)
            }
        }
    }

    /** Redraw a ticker's strip one step further left and re-upload it. */
    private fun advanceTicker(item: OverlayItem.Text, filter: ImageObjectFilterRender) {
        val band = tickerBand(item)
        val bandWidthPx = band.width * streamWidth
        val bandHeightPx = heightPercentFor(item) / 100f * streamHeight
        if (bandWidthPx < 1f || bandHeightPx < 1f) return

        // The strip maps 1:1 onto the band, so rendering it any larger would only cost upload
        // bandwidth. Text is drawn into it at the band's own resolution.
        val stripWidth = bandWidthPx.roundToInt().coerceIn(64, MAX_STRIP_WIDTH_PX)
        val stripHeight = (stripWidth * bandHeightPx / bandWidthPx).roundToInt()
            .coerceIn(8, MAX_STRIP_HEIGHT_PX)

        var strip = tickerStrips[item.id]
        if (strip == null || strip.width != stripWidth || strip.height != stripHeight) {
            strip = TickerStrip(stripWidth, stripHeight)
            tickerStrips[item.id] = strip
        }
        strip.configure(item)

        val textWidth = strip.textWidth
        if (textWidth <= 0f) return

        // Same on-screen speed as before, expressed in strip pixels.
        val step = (SCROLL_SPEED_PERCENT_PER_FRAME / 100f) * streamWidth *
            (stripWidth / bandWidthPx)

        var offset = strip.offset
        if (offset.isNaN()) offset = stripWidth.toFloat()
        offset -= step
        // Wrap once the line has fully exited on the left.
        if (offset <= -textWidth) offset = stripWidth.toFloat()
        strip.offset = offset

        filter.setImage(strip.draw())
    }

    /**
     * Left edge and width of a ticker's band, both fractions of the frame width. The band is
     * centred on the overlay (so dragging it aims the band) and nudged inward so it never
     * hangs off the frame; the default width of 1.0 is the whole frame, edge to edge.
     */
    private fun tickerBand(item: OverlayItem.Text): Band {
        val width = item.scrollWidth.coerceIn(MIN_TICKER_BAND_WIDTH, 1f)
        val left = (item.x - width / 2f).coerceIn(0f, 1f - width)
        return Band(left, width)
    }

    private data class Band(val left: Float, val width: Float)

    /**
     * A ticker's line, and the geometry needed to draw it onto a band-sized strip.
     *
     * It holds NO reusable buffer on purpose. RootEncoder's TextureLoader calls recycle() on
     * every bitmap it uploads — the library takes ownership — so a strip we kept and redrew
     * would be dead by the next frame (which is exactly why the ticker stopped moving: each
     * redraw threw on a recycled bitmap and got swallowed by the tick loop's guard). Every
     * frame therefore gets a fresh bitmap, handed over and forgotten.
     */
    private class TickerStrip(val width: Int, val height: Int) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        private var signature = ""
        private var line = ""
        private var baseline = 0f

        private companion object {
            /** Share of the band's height one line of type fills; the rest is headroom. */
            const val TEXT_FILL = 0.9f
        }

        /** Current left edge of the line within the strip, in strip pixels. NaN until started. */
        var offset: Float = Float.NaN

        /** Width of the rendered line in strip pixels. */
        var textWidth: Float = 0f
            private set

        /** Re-measure only when the text, font or colour actually changed. */
        fun configure(item: OverlayItem.Text) {
            val sig = "${item.text}|${item.fontKey}|${item.colorArgb}"
            if (sig == signature) return
            signature = sig
            // One line: hard newlines become spaces so nothing can break the strip.
            line = item.text.replace('\n', ' ').replace('\r', ' ').trim()
            paint.typeface = OverlayFonts.typefaceFor(item.fontKey)
            paint.color = item.colorArgb
            // Scale the type so one line of it fills the strip's height, less a little
            // headroom — Devanagari conjuncts and matras reach past the nominal metrics and
            // would otherwise clip against the band's edges.
            paint.textSize = height.toFloat()
            val fm = paint.fontMetrics
            val lineHeight = (fm.descent - fm.ascent).coerceAtLeast(1f)
            paint.textSize = (height * height / lineHeight * TEXT_FILL).coerceAtLeast(1f)
            val scaled = paint.fontMetrics
            // Centre the line vertically in whatever headroom is left.
            baseline = (height - (scaled.descent - scaled.ascent)) / 2f - scaled.ascent
            textWidth = paint.measureText(line)
            // Restart the run so the new text enters from the right rather than mid-flight.
            offset = Float.NaN
        }

        /**
         * A fresh, transparent strip with the line drawn at the current offset. The caller
         * hands it straight to the filter, which uploads and recycles it.
         */
        fun draw(): Bitmap {
            // createBitmap already returns fully transparent pixels — no erase needed.
            val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(target).drawText(line, offset, baseline, paint)
            return target
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
        try {
            if (item is OverlayItem.Text && filter is ImageObjectFilterRender) {
                applyText(filter, item)
            }
            // The live-control path: a "+4" tap arrives here as a Pack whose values changed,
            // and applyPack re-rasterizes only when the content signature actually differs.
            if (item is OverlayItem.Pack && filter is ImageObjectFilterRender) {
                applyPack(filter, item)
            }
            applyTransform(filter, item)
        } catch (t: Throwable) {
            // A GL/encoder call can throw if the pipeline is mid-teardown, and re-rasterizing
            // text allocates a bitmap (so: OutOfMemoryError). Never let either crash the caller
            // — this runs on every drag frame and every overlay toggle.
            android.util.Log.e("OverlayRenderer", "updateOverlay failed for ${item.id}", t)
        }
    }

    private fun addOverlay(item: OverlayItem) {
        when (item) {
            is OverlayItem.Text -> buildTextFilter(item)?.let { attachFilter(item, it) }
            is OverlayItem.Pack -> buildPackFilter(item)?.let { attachFilter(item, it) }
            is OverlayItem.Video -> attachFilter(item, buildVideoFilter(item))
            is OverlayItem.Image -> loadAndAttachImage(item)
            is OverlayItem.Gif -> loadAndAttachGif(item)
            is OverlayItem.Browser -> attachFilter(item, buildBrowserFilter(item))
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
            if (cachedBitmap.height > 0) contentAspect[item.id] = cachedBitmap.width.toFloat() / cachedBitmap.height
            val filter = ImageObjectFilterRender().apply { setImage(cachedBitmap) }
            attachFilter(item, filter)
            return
        }
        
        android.util.Log.d("OverlayRenderer", "Loading image ${item.id} from ${item.uri}")
        pendingLoads[item.id] = scope.safeLaunch(TAG, "loading overlay ${item.id}") {
            val bitmap = withContext(Dispatchers.IO) { decodeBitmap(item.uri) }
            pendingLoads.remove(item.id)
            if (bitmap == null) {
                android.util.Log.e("OverlayRenderer", "Failed to decode bitmap for ${item.id}")
                return@safeLaunch
            }
            if (!item.visible) {
                android.util.Log.d("OverlayRenderer", "Item ${item.id} no longer visible, recycling bitmap")
                bitmap.recycle()
                return@safeLaunch
            }
            if (filters.containsKey(item.id)) {
                android.util.Log.d("OverlayRenderer", "Filter already exists for ${item.id}, recycling new bitmap")
                bitmap.recycle()
                return@safeLaunch
            }
            bitmaps[item.id] = bitmap
            if (bitmap.height > 0) contentAspect[item.id] = bitmap.width.toFloat() / bitmap.height
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
            recordGifAspect(item.id, cachedBytes)
            val filter = try {
                GifObjectFilterRender().apply { setGif(ByteArrayInputStream(cachedBytes)) }
            } catch (_: Exception) {
                return
            }
            attachFilter(item, filter)
            return
        }
        
        pendingLoads[item.id] = scope.safeLaunch(TAG, "loading overlay ${item.id}") {
            val bytes = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(Uri.parse(item.uri))?.use { it.readBytes() }
                } catch (_: Exception) {
                    null
                }
            }
            pendingLoads.remove(item.id)
            if (bytes == null || !item.visible || filters.containsKey(item.id)) return@safeLaunch
            gifBytes[item.id] = bytes
            recordGifAspect(item.id, bytes)
            val filter = try {
                GifObjectFilterRender().apply { setGif(ByteArrayInputStream(bytes)) }
            } catch (_: Exception) {
                return@safeLaunch
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
            if (item is OverlayItem.Image || item is OverlayItem.Gif ||
                item is OverlayItem.Text || item is OverlayItem.Pack
            ) {
                mainHandler.postDelayed({
                    // Posted work runs outside every caller's guard, so it needs its own:
                    // healing re-rasterizes/re-uploads a texture and can throw or OOM.
                    try {
                        healTextureOnce(item)
                    } catch (t: Throwable) {
                        android.util.Log.e("OverlayRenderer", "Texture heal failed for ${item.id}", t)
                    }
                }, VERIFY_INTERVAL_MS)
            }
            android.util.Log.d("OverlayRenderer", "Successfully attached filter for ${item.id}")
        } catch (t: Throwable) {
            // glInterface may not be ready yet — caller will retry via applyOverlays.
            android.util.Log.e("OverlayRenderer", "Failed to attach filter for ${item.id}", t)
            bitmaps.remove(item.id)?.recycle()
            videoPlayers.remove(item.id)?.release()
        }
    }

    fun removeOverlay(id: String) {
        pendingLoads.remove(id)?.cancel()
        texturesHealed.remove(id)
        // Strip buffers are dropped, never recycled: the GL thread may still be uploading the
        // last frame's buffer. Letting the GC take them is the safe way out.
        tickerStrips.remove(id)
        val filter = filters.remove(id) ?: return
        try {
            rtmpCamera.glInterface.removeFilter(filter)
        } catch (_: Exception) { }
        try { videoPlayers.remove(id)?.release() } catch (_: Exception) { }
        try { browserSources.remove(id)?.release() } catch (_: Exception) { }
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
        tickerStrips.clear()
        pendingLoads.values.forEach { it.cancel() }
        pendingLoads.clear()
        filters.keys.toList().forEach { id ->
            filters.remove(id)?.let { f ->
                try { rtmpCamera.glInterface.removeFilter(f) } catch (_: Exception) { }
            }
        }
        videoPlayers.values.forEach { it.release() }
        videoPlayers.clear()
        browserSources.values.forEach { it.release() }
        browserSources.clear()
        texturesHealed.clear()
        // Every filter is gone, so each text overlay needs a fresh rasterize + upload.
        textSignatures.clear()
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
     * Force a one-shot texture re-upload for an attached overlay.
     * Re-issuing setImage/setGif flips the filter's shouldLoad flag, so the GL thread
     * releases the (possibly blank) texture and uploads it again on the next frame —
     * the same effect as toggling visibility off/on, without detaching the filter.
     * Returns true if a reload was issued.
     */
    private fun reloadTexture(item: OverlayItem): Boolean {
        val filter = filters[item.id] ?: return false
        return when {
            // A ticker re-uploads its strip every frame, so there is nothing to heal.
            item is OverlayItem.Text && item.scroll -> false
            item is OverlayItem.Text && filter is ImageObjectFilterRender -> {
                // The uploaded bitmap is gone (the library recycled it), so healing static
                // text means rasterizing it again — clearing the signature forces that.
                textSignatures.remove(item.id)
                applyText(filter, item)
            }
            item is OverlayItem.Pack && filter is ImageObjectFilterRender -> {
                // Same story as text: the library owns and recycles what it uploads, so a
                // heal means redrawing the graphic from its definition.
                textSignatures.remove(item.id)
                applyPack(filter, item)
            }
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
        contentAspect.remove(id)
        textSignatures.remove(id)
    }

    /**
     * Tear down all overlays and release MediaPlayers. Call from Activity.onDestroy.
     */
    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        scrollRunning = false
        tickerStrips.clear()
        pendingLoads.values.forEach { it.cancel() }
        pendingLoads.clear()
        filters.keys.toList().forEach { id ->
            filters.remove(id)?.let { filter ->
                try {
                    rtmpCamera.glInterface.removeFilter(filter)
                } catch (_: Exception) { }
            }
            videoPlayers.remove(id)?.release()
            browserSources.remove(id)?.release()
        }
        bitmaps.values.forEach { it.recycle() }
        bitmaps.clear()
        gifBytes.clear()
        contentAspect.clear()
        texturesHealed.clear()
        textSignatures.clear()
        // Logos decoded for graphics packs are cached across renders; drop them with the rest.
        PackRasterizer.clearCache()
        scope.cancel()
    }

    // -------------------------------------------------------------------------------------
    // Scene visibility
    // -------------------------------------------------------------------------------------

    /**
     * Overlays hidden by the CURRENT SCENE, as opposed to overlays the user has switched off
     * in the overlay manager.
     *
     * The distinction matters. `OverlayItem.visible` means "this overlay is part of my show at
     * all", and turning it off detaches the GL filter and frees its texture. A scene switch is
     * a different question — "is this on screen right now?" — and happens live, repeatedly,
     * mid-broadcast. Detaching and re-attaching filters for that would re-upload every texture
     * on every switch, which is visible to viewers as a flicker.
     *
     * So scenes drive the filter's ALPHA instead. The filter stays attached with its texture
     * resident, and switching is a single uniform change on the next rendered frame.
     */
    private val sceneHidden = mutableSetOf<String>()

    /**
     * Apply a scene's hidden set. Cheap and safe to call while live — no filter is added,
     * removed, or re-uploaded.
     */
    fun applySceneVisibility(hiddenIds: Set<String>) {
        sceneHidden.clear()
        sceneHidden.addAll(hiddenIds)
        filters.forEach { (id, filter) ->
            if (filter !is BaseObjectFilterRender) return@forEach
            try {
                filter.setAlpha(if (id in sceneHidden) 0f else 1f)
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "Setting scene alpha failed for $id", t)
            }
        }
    }

    /** Ids currently hidden by the active scene. */
    fun sceneHiddenIds(): Set<String> = sceneHidden.toSet()

    private fun applyTransform(filter: BaseFilterRender, item: OverlayItem) {
        if (filter !is BaseObjectFilterRender) return
        // Re-assert scene alpha on every transform: a filter attached AFTER a scene was
        // applied (a late texture load, a watchdog rebuild) would otherwise pop into view at
        // full opacity despite the scene saying it should be hidden.
        try {
            filter.setAlpha(if (item.id in sceneHidden) 0f else 1f)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Setting alpha failed for ${item.id}", t)
        }
        // Browser/URL overlays are a full-canvas layer — always edge-to-edge. Its page
        // content is fit to the full frame in BrowserOverlaySource, so the quad is just 100%.
        if (item is OverlayItem.Browser) {
            filter.setScale(100f, 100f)
            filter.setPosition(0f, 0f)
            filter.setRotation(0)
            return
        }
        // Width as a percent of the stream width: scale=1.0 → 20% wide.
        val widthPercent = widthPercentFor(item)
        // Height as an INDEPENDENT percent of the stream height (driven by heightScale).
        // When scale == heightScale the content keeps its real aspect ratio; moving the two
        // sliders apart stretches it. Browser overlays default both to 5.0 → 100% × 100%.
        val heightPercent = heightPercentFor(item)
        val topLeftY = (item.y * 100f) - (heightPercent / 2f)
        // Scale FIRST: RootEncoder's Sprite.scale() rewrites the stored position by the
        // old/new scale ratio, so a position set before it lands off-target whenever the size
        // changed (a resize, or text whose aspect was just re-measured). Positioning last
        // always writes the real percentage.
        filter.setScale(widthPercent, heightPercent)
        if (item is OverlayItem.Text && item.scroll) {
            // The quad is pinned to the ticker's band; the text scrolls inside the strip we
            // composite, so there is no quad motion for the ticker loop to own.
            filter.setPosition(tickerBand(item).left * 100f, topLeftY)
        } else {
            val topLeftX = (item.x * 100f) - (widthPercent / 2f)
            filter.setPosition(topLeftX, topLeftY)
        }
        filter.setRotation(item.rotation.toInt())
    }

    /**
     * Width percent (of stream width): scale = 1.0 → 20% wide.
     *
     * A scrolling ticker is the exception: it's one long strip whose aspect grows with the
     * message, so pinning its WIDTH would shrink the glyphs to nothing as the text gets
     * longer (and the whole strip has to travel off-frame anyway). For tickers the size
     * slider drives the text HEIGHT and the width follows from the aspect.
     */
    private fun widthPercentFor(item: OverlayItem): Float {
        // A ticker's quad IS its band — the text scrolls inside it, so the quad's width is the
        // band's, not the message's.
        if (item is OverlayItem.Text && item.scroll) return tickerBand(item).width * 100f
        return 20f * item.scale
    }

    /**
     * Height percent (of stream height), driven INDEPENDENTLY by [OverlayItem.heightScale].
     * Base is 20% × heightScale (mirroring the 20% × scale width base), then corrected by the
     * stream/content aspect ratio so that at heightScale == scale the content is undistorted:
     * the pixel box (20%·scale·streamW) × (heightPercent%·streamH) then has the content's own
     * aspect. Falls back to the uncorrected base if the content aspect isn't known yet.
     * Scrolling text is height-driven instead (see [widthPercentFor]).
     */
    private fun heightPercentFor(item: OverlayItem): Float {
        if (item is OverlayItem.Text && item.scroll) {
            return TICKER_BASE_HEIGHT_PERCENT * item.heightScale
        }
        val base = 20f * item.heightScale
        val aspect = contentAspect[item.id] ?: return base
        if (aspect <= 0f) return base
        return base * streamAspect() / aspect
    }

    private fun streamAspect(): Float = streamWidth.toFloat() / streamHeight.toFloat()

    /**
     * Text overlays are drawn as plain textured quads: we rasterize the text ourselves
     * ([TextOverlayBitmap]) and hand the bitmap to an ImageObjectFilterRender, rather than
     * using the library's TextObjectFilterRender — see [TextOverlayBitmap] for why (word
     * wrapping, bounded texture size, and no OutOfMemoryError on long strings).
     * Returns null if the text couldn't be rasterized, so the caller skips the attach.
     */
    private fun buildTextFilter(item: OverlayItem.Text): ImageObjectFilterRender? {
        val filter = ImageObjectFilterRender()
        // Fresh filter: force a rasterize even if the content is unchanged, since the new
        // filter instance has no texture of its own yet.
        textSignatures.remove(item.id)
        return if (applyText(filter, item)) filter else null
    }

    /**
     * Rasterize the overlay's text and upload it, recording its true aspect ratio so the box
     * isn't stretched. No-ops when the text, font, colour and scroll mode are unchanged —
     * this runs on every drag/pinch frame and every verify sweep.
     * Returns true if the filter has a valid texture afterwards.
     */
    private fun applyText(filter: ImageObjectFilterRender, item: OverlayItem.Text): Boolean {
        // A ticker's texture is the strip its line is drawn onto, rebuilt every frame by the
        // scroll loop — there is nothing to rasterize here, and uploading a wrapped block
        // would be exactly the wrong picture. The strip re-measures itself when the text,
        // font or colour changes.
        if (item.scroll) return true

        val target = textTargetPx(item)
        val signature = textSignature(item, target)
        if (textSignatures[item.id] == signature) return true

        val rendered = TextOverlayBitmap.render(
            text = item.text,
            fontKey = item.fontKey,
            colorArgb = item.colorArgb,
            targetPx = target
        ) ?: return false

        contentAspect[item.id] = rendered.aspect
        textSignatures[item.id] = signature
        // Handed over, not cached: the library's TextureLoader recycles whatever it uploads.
        // (Keeping it in `bitmaps` and testing isRecycled would report "dead" on the very next
        // frame and re-rasterize the text on every drag frame.)
        filter.setImage(rendered.bitmap)
        return true
    }

    private fun textSignature(item: OverlayItem.Text, targetPx: Float): String =
        "${item.text}|${item.fontKey}|${item.colorArgb}|${targetPx.toInt()}"

    // -------------------------------------------------------------------------------------
    // Graphics Packs
    // -------------------------------------------------------------------------------------

    /**
     * Pack definitions, loaded once from assets. Resolved lazily rather than in the
     * constructor: OverlayRenderer is built on the main thread while the camera comes up, and
     * reading + parsing the pack JSON there would add latency to every entry into the studio
     * for users who have no packs at all.
     */
    private val packCatalog: PackCatalog by lazy { PackCatalog.loadBlocking(context) }

    /**
     * Fresh filter for a pack overlay. Mirrors [buildTextFilter]: the pack rasterizes to a
     * single bitmap which is then an ordinary textured quad, so everything downstream —
     * transforms, aspect correction, texture healing — is the code that already exists.
     */
    private fun buildPackFilter(item: OverlayItem.Pack): ImageObjectFilterRender? {
        val filter = ImageObjectFilterRender()
        // Fresh filter instance has no texture, so force a rasterize even if the content
        // signature is unchanged from a previous attach.
        textSignatures.remove(item.id)
        return if (applyPack(filter, item)) filter else null
    }

    /**
     * Rasterize a pack and upload it, recording its aspect so the box isn't stretched.
     * No-ops when the pack, its values, its theme and its target size are all unchanged —
     * this runs on every drag frame and every verify sweep, as well as every live-control tap.
     * Returns true if the filter has a valid texture afterwards.
     */
    private fun applyPack(filter: ImageObjectFilterRender, item: OverlayItem.Pack): Boolean {
        val definition = packCatalog.byId(item.packId) ?: run {
            // Silently skipping here is what "the graphic never appears on the stream" looks
            // like from the outside, so say which pack and what the catalogue actually holds —
            // an empty catalogue and one missing id are very different faults.
            val reason = "No pack definition for ${item.packId} " +
                "(catalogue holds ${packCatalog.all.size}: ${packCatalog.all.joinToString { it.id }})"
            android.util.Log.w(TAG, reason)
            CrashReporter.recordNonFatal(TAG, reason, IllegalStateException(reason))
            return false
        }

        val target = packTargetPx(item)
        val signature = packSignature(item, target)
        if (textSignatures[item.id] == signature) return true

        // Declared defaults first, so a field the user never touched still draws.
        val values = definition.defaultValues() + item.values

        val rendered = PackRasterizer.render(
            context = context,
            pack = definition,
            values = values,
            theme = PackTheme.byKey(item.themeKey),
            targetWidthPx = target,
        ) ?: run {
            // PackRasterizer has already logged the reason; note that it cost an on-air
            // graphic, since from the stream's side this is just a missing overlay.
            android.util.Log.e(
                TAG,
                "Pack ${item.packId} produced no texture: ${PackRasterizer.lastFailure}",
            )
            return false
        }

        contentAspect[item.id] = rendered.aspect
        textSignatures[item.id] = signature
        // Handed over, not cached: the library's TextureLoader recycles whatever it uploads.
        filter.setImage(rendered.bitmap)
        return true
    }

    private fun packSignature(item: OverlayItem.Pack, targetPx: Int): String =
        buildString {
            append(item.packId).append('|').append(item.themeKey).append('|').append(targetPx)
            // Sorted so an equal map with different insertion order doesn't force a redraw.
            item.values.entries.sortedBy { it.key }.forEach { (k, v) ->
                append('|').append(k).append('=').append(v)
            }
        }

    /**
     * Resolution to rasterize a pack at, in output pixels: the width it actually occupies on
     * the frame, supersampled a little so edges and type stay crisp after the GPU downscale.
     * Quantized so dragging the size slider redraws a handful of times, not every frame.
     */
    private fun packTargetPx(item: OverlayItem.Pack): Int {
        val raw = (20f * item.scale / 100f) * streamWidth * PACK_SUPERSAMPLE
        val stepped = ceil(raw / PACK_TARGET_STEP_PX) * PACK_TARGET_STEP_PX
        return stepped.coerceIn(PACK_TARGET_STEP_PX, MAX_PACK_TARGET_PX).toInt()
    }

    /**
     * Resolution to rasterize this overlay's text at, in output pixels: the size it actually
     * occupies on the frame, supersampled a little so it stays crisp.
     *
     * Rendering every text overlay at a fixed maximum instead would allocate several MB per
     * overlay no matter how small it is on screen — an allocation that lands exactly when Go
     * Live is also claiming encoder, camera and GL buffers.
     *
     * Quantized to [TEXT_TARGET_STEP_PX] buckets so dragging the size slider re-rasterizes a
     * handful of times across its whole range, not on every frame.
     */
    private fun textTargetPx(item: OverlayItem.Text): Float {
        // Static text only — tickers draw straight onto their strip. Sized by the width of
        // the text's box on the frame.
        val raw = (20f * item.scale / 100f) * streamWidth * TEXT_SUPERSAMPLE
        val stepped = ceil(raw / TEXT_TARGET_STEP_PX) * TEXT_TARGET_STEP_PX
        return stepped.coerceIn(TEXT_TARGET_STEP_PX, MAX_TEXT_TARGET_PX)
    }

    private fun buildVideoFilter(item: OverlayItem.Video): BaseObjectFilterRender {
        val player = VideoOverlayPlayer(
            context, Uri.parse(item.uri), item.loop,
            chromaEnabled = item.chromaEnabled,
            chromaColor = item.chromaColor,
            chromaSensitive = item.chromaSensitive
        )
        videoPlayers[item.id] = player
        return player.filter
    }

    private fun buildBrowserFilter(item: OverlayItem.Browser): BaseObjectFilterRender {
        // Supersample: render the web overlay well ABOVE the output resolution so a sub-1080p
        // StreamElements canvas (e.g. 1280×720) is rasterized at high resolution after the
        // scale-to-fill, then the GPU cleanly downscales to the stream — keeping vector content
        // (text, ticker, shapes) crisp. 1440p is a good quality/performance balance.
        val source = BrowserOverlaySource(uiContext, item.url, BROWSER_RENDER_W, BROWSER_RENDER_H)
        browserSources[item.id] = source
        contentAspect[item.id] = BROWSER_RENDER_W.toFloat() / BROWSER_RENDER_H
        return source.filter
    }

    /** Read just the first GIF frame's bounds to record its aspect ratio (cheap). */
    private fun recordGifAspect(id: String, bytes: ByteArray) {
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                contentAspect[id] = opts.outWidth.toFloat() / opts.outHeight
            }
        } catch (_: Exception) { }
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
            inSampleSize = calcSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, maxOverlayEdgePx())
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri).use { stream ->
            BitmapFactory.decodeStream(stream, null, opts)
        }
    } catch (t: Throwable) {
        // Throwable: decoding is exactly where OutOfMemoryError shows up, and it is an Error.
        // A missing overlay is recoverable; a dead process during a broadcast is not.
        android.util.Log.e("OverlayRenderer", "Failed to decode overlay image $uriString", t)
        null
    }

    /**
     * Largest texture edge we keep for image overlays.
     *
     * An overlay's box maxes out at the full frame width, so a texture at the output's long
     * edge already only ever downscales — it can't look soft. The previous 2× (up to 4096)
     * meant a single image overlay could be a 4096×4096 ARGB_8888 allocation: ~67 MB, claimed
     * at the same moment the encoder, camera and GL pipeline want memory, for no visible gain.
     * That was a straightforward OutOfMemoryError on any mid-range device.
     */
    private fun maxOverlayEdgePx(): Int =
        maxOf(streamWidth, streamHeight).coerceIn(1280, 2048)

    private fun calcSampleSize(srcW: Int, srcH: Int, maxEdge: Int): Int {
        if (srcW <= 0 || srcH <= 0) return 1
        val longEdge = maxOf(srcW, srcH)
        var sample = 1
        while (longEdge / sample > maxEdge) sample *= 2
        return sample
    }

    private companion object {
        const val TAG = "OverlayRenderer"

        /**
         * Web/URL overlay render resolution.
         *
         * This is 1080p, not the 1440p it used to be, because the WebView is in SOFTWARE layer
         * mode (required for transparency) and is redrawn ~30×/second on the main thread. At
         * 2560×1440 each backing bitmap is ~14 MB and every frame is a 3.7-megapixel CPU
         * rasterization — enough to OOM a mid-range phone and enough main-thread work to jank
         * or ANR the app, while the encoder and camera are competing for the same memory.
         * 1080p matches the app's own max output resolution, so nothing is actually lost: any
         * supersample above the stream size is discarded by the GPU downscale anyway.
         */
        const val BROWSER_RENDER_W = 1920
        const val BROWSER_RENDER_H = 1080

        // Ticker (scrolling text) height at scale 1.0, as a percent of the stream height —
        // ~8% is a broadcast-style lower third on a 1080p frame. The size slider multiplies it.
        const val TICKER_BASE_HEIGHT_PERCENT = 8f

        // Text is rasterized at this multiple of its on-screen size, so the GPU only ever
        // downscales it (upscaling a glyph texture is what looks soft).
        const val TEXT_SUPERSAMPLE = 1.5f
        // Bucket size for that target, so a slider drag re-rasterizes a few times, not always.
        const val TEXT_TARGET_STEP_PX = 512f
        const val MAX_TEXT_TARGET_PX = 3072f

        // Graphics packs. A lower supersample than text: a pack is mostly filled shapes and
        // large type, which survive the downscale better than small glyphs, and the redraw
        // happens on every live-control tap so it has to stay cheap.
        const val PACK_SUPERSAMPLE = 1.25f
        const val PACK_TARGET_STEP_PX = 256f
        const val MAX_PACK_TARGET_PX = 2048f

        // Banded tickers: narrowest band the renderer honours, and the point at which a band
        // is treated as edge-to-edge (which takes the cheaper quad-sliding path instead).
        const val MIN_TICKER_BAND_WIDTH = 0.1f
        const val FULL_BAND_THRESHOLD = 0.999f

        // Caps on the composited strip. It maps 1:1 onto the band, so these only bite on very
        // large frames; they bound the per-frame texture upload.
        const val MAX_STRIP_WIDTH_PX = 2048
        const val MAX_STRIP_HEIGHT_PX = 512

        // Attachment watchdog: how often to compare our filter map against the pipeline's own
        // count, and how many consecutive mismatches before rebuilding. Filter actions drain
        // one per rendered frame, so a couple of seconds of disagreement means genuinely lost.
        const val WATCHDOG_INTERVAL_MS = 1000L
        const val WATCHDOG_STRIKES = 2

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
