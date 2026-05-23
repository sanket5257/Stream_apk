package com.streamforge.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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

    /**
     * Reconcile the filter pipeline with the given list of overlays.
     * Adds new items, updates existing ones, removes any that vanished or became hidden.
     */
    fun applyOverlays(items: List<OverlayItem>) {
        val visible = items.filter { it.visible }
        val visibleIds = visible.map { it.id }.toSet()

        filters.keys.toList()
            .filter { it !in visibleIds }
            .forEach { removeOverlay(it) }
        pendingLoads.keys.toList()
            .filter { it !in visibleIds }
            .forEach { pendingLoads.remove(it)?.cancel() }

        visible.sortedBy { it.zIndex }.forEach { item ->
            if (filters.containsKey(item.id)) updateOverlay(item) else addOverlay(item)
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
        if (pendingLoads.containsKey(item.id) || filters.containsKey(item.id)) return
        pendingLoads[item.id] = scope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeBitmap(item.uri) }
            pendingLoads.remove(item.id)
            if (bitmap == null || !item.visible) {
                bitmap?.recycle()
                return@launch
            }
            if (filters.containsKey(item.id)) {
                bitmap.recycle()
                return@launch
            }
            bitmaps[item.id] = bitmap
            val filter = ImageObjectFilterRender().apply { setImage(bitmap) }
            attachFilter(item, filter)
        }
    }

    private fun loadAndAttachGif(item: OverlayItem.Gif) {
        if (pendingLoads.containsKey(item.id) || filters.containsKey(item.id)) return
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
        } catch (_: Exception) {
            // glInterface may not be ready yet — caller will retry via applyOverlays.
            bitmaps.remove(item.id)?.recycle()
            videoPlayers.remove(item.id)?.release()
        }
    }

    fun removeOverlay(id: String) {
        pendingLoads.remove(id)?.cancel()
        val filter = filters.remove(id) ?: return
        try {
            rtmpCamera.glInterface.removeFilter(filter)
        } catch (_: Exception) { }
        videoPlayers.remove(id)?.release()
        bitmaps.remove(id)?.recycle()
    }

    /**
     * Tear down all overlays and release MediaPlayers. Call from Activity.onDestroy.
     */
    fun release() {
        pendingLoads.values.forEach { it.cancel() }
        pendingLoads.clear()
        filters.keys.toList().forEach { removeOverlay(it) }
        scope.cancel()
    }

    private fun applyTransform(filter: BaseFilterRender, item: OverlayItem) {
        if (filter !is BaseObjectFilterRender) return
        val baseSize = 20f
        val sizePercent = baseSize * item.scale
        val topLeftX = (item.x * 100f) - (sizePercent / 2f)
        val topLeftY = (item.y * 100f) - (sizePercent / 2f)
        filter.setPosition(topLeftX, topLeftY)
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
    }
}
