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
            addOverlay(item)
            return
        }
        if (item is OverlayItem.Text && filter is TextObjectFilterRender) {
            filter.setText(item.text, item.fontSizeSp, item.colorArgb)
        }
        applyTransform(filter, item)
    }

    private fun addOverlay(item: OverlayItem) {
        val filter: BaseFilterRender = when (item) {
            is OverlayItem.Image -> buildImageFilter(item) ?: return
            is OverlayItem.Text -> buildTextFilter(item)
            is OverlayItem.Gif -> buildGifFilter(item) ?: return
            is OverlayItem.Video -> buildVideoFilter(item)
        }
        applyTransform(filter, item)
        try {
            rtmpCamera.glInterface.addFilter(filter)
            filters[item.id] = filter
        } catch (e: Exception) {
            // glInterface may not be ready yet — caller will retry via applyOverlays.
            bitmaps.remove(item.id)?.recycle()
            videoPlayers.remove(item.id)?.release()
        }
    }

    fun removeOverlay(id: String) {
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
        filters.keys.toList().forEach { removeOverlay(it) }
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

    private fun buildImageFilter(item: OverlayItem.Image): ImageObjectFilterRender? {
        val bitmap = decodeBitmap(item.uri) ?: return null
        bitmaps[item.id] = bitmap
        val filter = ImageObjectFilterRender()
        filter.setImage(bitmap)
        return filter
    }

    private fun buildTextFilter(item: OverlayItem.Text): TextObjectFilterRender {
        val filter = TextObjectFilterRender()
        filter.setText(item.text, item.fontSizeSp, item.colorArgb)
        return filter
    }

    private fun buildGifFilter(item: OverlayItem.Gif): GifObjectFilterRender? {
        return try {
            val bytes = context.contentResolver.openInputStream(Uri.parse(item.uri))?.use {
                it.readBytes()
            } ?: return null
            val filter = GifObjectFilterRender()
            filter.setGif(ByteArrayInputStream(bytes))
            filter
        } catch (e: Exception) {
            null
        }
    }

    private fun buildVideoFilter(item: OverlayItem.Video): BaseObjectFilterRender {
        val player = VideoOverlayPlayer(context, Uri.parse(item.uri), item.loop)
        videoPlayers[item.id] = player
        return player.filter
    }

    private fun decodeBitmap(uriString: String): Bitmap? = try {
        context.contentResolver.openInputStream(Uri.parse(uriString)).use { stream ->
            BitmapFactory.decodeStream(stream)
        }
    } catch (e: Exception) {
        null
    }
}
