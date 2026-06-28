package com.streamforge.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.streamforge.app.overlay.OverlayItem
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Phase 3B: Custom view for editing overlay positions with gestures.
 * Supports drag, pinch-to-scale, and two-finger rotation.
 */
class OverlayEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val items = mutableListOf<OverlayItem>()
    private var selectedId: String? = null

    private var selectionListener: ((String?) -> Unit)? = null
    private var itemChangeListener: ((OverlayItem) -> Unit)? = null

    /**
     * Supplies each overlay's content aspect ratio (width / height) so the gesture box matches
     * the size RootEncoder actually renders it at. Without it (e.g. the test activity) every
     * overlay falls back to a square box. Set by [StreamActivity] to query [OverlayRenderer].
     */
    var aspectProvider: ((String) -> Float?)? = null

    /**
     * When true (default), each item is drawn as a translucent colored rectangle —
     * used by [com.streamforge.app.OverlayTestActivity] which has no real GL pipeline.
     * StreamActivity sets this to false so only a thin outline + selection border show,
     * letting RootEncoder's filters provide the actual overlay visuals.
     */
    var showPlaceholders: Boolean = true
        set(value) { field = value; invalidate() }

    private val imagePaint = Paint().apply {
        color = Color.RED
        alpha = 128
        style = Paint.Style.FILL
    }

    private val textPaint = Paint().apply {
        color = Color.BLUE
        alpha = 128
        style = Paint.Style.FILL
    }

    private val gifPaint = Paint().apply {
        color = Color.MAGENTA
        alpha = 128
        style = Paint.Style.FILL
    }

    private val videoPaint = Paint().apply {
        color = Color.parseColor("#4CAF50")
        alpha = 128
        style = Paint.Style.FILL
    }

    private val browserPaint = Paint().apply {
        color = Color.parseColor("#3F51B5")
        alpha = 128
        style = Paint.Style.FILL
    }

    private val outlinePaint = Paint().apply {
        color = Color.WHITE
        alpha = 160
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val selectionPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val textDrawPaint = Paint().apply {
        color = Color.WHITE
        textSize = 32f
        isAntiAlias = true
    }
    
    // Gesture detection
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw items sorted by zIndex, skip invisible items
        items.sortedBy { it.zIndex }.filter { it.visible }.forEach { item ->
            // Browser/URL overlays are locked full-frame and not user-editable, so they draw
            // no gesture box (the GL pipeline shows the real web content). The test activity,
            // which has no GL pipeline, still draws a placeholder so the overlay is visible.
            if (item is OverlayItem.Browser && !showPlaceholders) return@forEach

            canvas.save()
            canvas.translate(item.x * width, item.y * height)
            canvas.rotate(item.rotation)

            // Box sized exactly like the rendered overlay so the outline/selection border the
            // user sees matches what's on the stream — and what selectItemAt hit-tests.
            val (halfW, halfH) = halfExtents(item)
            val rect = RectF(-halfW, -halfH, halfW, halfH)

            if (showPlaceholders) {
                val fill = when (item) {
                    is OverlayItem.Image -> imagePaint
                    is OverlayItem.Text -> textPaint
                    is OverlayItem.Gif -> gifPaint
                    is OverlayItem.Video -> videoPaint
                    is OverlayItem.Browser -> browserPaint
                }
                canvas.drawRect(rect, fill)
                if (item is OverlayItem.Text) {
                    textDrawPaint.textSize = item.fontSizeSp
                    textDrawPaint.color = item.colorArgb
                    val textWidth = textDrawPaint.measureText(item.text)
                    canvas.drawText(item.text, -textWidth / 2, 10f, textDrawPaint)
                }
            }

            if (item !is OverlayItem.Browser) {
                canvas.drawRect(rect, outlinePaint)
                if (item.id == selectedId) canvas.drawRect(rect, selectionPaint)
            }

            canvas.restore()
        }
    }

    /**
     * Half-width / half-height (view px) of an overlay's rendered box. Mirrors
     * [com.streamforge.app.overlay.OverlayRenderer]: width = 20% × scale of the frame width,
     * height derived from the content aspect ratio so the box isn't distorted. Falls back to a
     * square when the aspect isn't known yet (or no provider, e.g. the test activity).
     */
    private fun halfExtents(item: OverlayItem): Pair<Float, Float> {
        val boxW = BASE_WIDTH_FRACTION * item.scale * width
        val aspect = (aspectProvider?.invoke(item.id) ?: 1f).coerceAtLeast(0.01f)
        val boxH = boxW / aspect
        return (boxW / 2f) to (boxH / 2f)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Let scale detector process the event
        scaleDetector.onTouchEvent(event)
        
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                activePointerId = event.getPointerId(0)
                
                // Hit test to select item
                selectItemAt(event.x, event.y)
                return true
            }
            
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Rotation is intentionally disabled: overlays transform in 2D only (move +
                // scale). A second finger drives pinch-to-scale via scaleDetector, never a
                // z-axis twist.
            }
            
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex == -1) return true
                
                if (event.pointerCount == 1) {
                    // Single finger drag
                    val x = event.getX(pointerIndex)
                    val y = event.getY(pointerIndex)
                    
                    val dx = x - lastTouchX
                    val dy = y - lastTouchY
                    
                    moveSelectedItem(dx, dy)
                    
                    lastTouchX = x
                    lastTouchY = y
                }
                // Two-finger gestures only scale (handled by scaleDetector); rotation is
                // disabled so overlays stay axis-aligned (2D move + scale only).

                invalidate()
                return true
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }

            MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
        }
        
        return true
    }

    private fun selectItemAt(x: Float, y: Float) {
        // Hit test from the top of the z-order down, visible items only. Browser overlays are
        // locked full-frame and not user-movable, so they're excluded. We test the touch
        // against each overlay's actual rendered rectangle (rotated to match), not a uniform
        // circle — so a wide/thin ticker no longer swallows touches meant for nearby overlays.
        val hitItem = items.sortedByDescending { it.zIndex }
            .filter { it.visible && it !is OverlayItem.Browser }
            .firstOrNull { item -> hitTest(item, x, y) }

        selectedId = hitItem?.id
        selectionListener?.invoke(selectedId)
        invalidate()
    }

    /** True if (x, y) falls inside [item]'s rendered, rotated box (with a min touch target). */
    private fun hitTest(item: OverlayItem, x: Float, y: Float): Boolean {
        val (halfW, halfH) = halfExtents(item)
        // Keep tiny overlays tappable by enforcing a minimum touch half-extent.
        val hitHalfW = halfW.coerceAtLeast(MIN_TOUCH_HALF_PX)
        val hitHalfH = halfH.coerceAtLeast(MIN_TOUCH_HALF_PX)

        // Translate the touch into the overlay's local (un-rotated) frame around its center.
        val dx = x - item.x * width
        val dy = y - item.y * height
        val theta = Math.toRadians(item.rotation.toDouble())
        val cos = cos(theta).toFloat()
        val sin = sin(theta).toFloat()
        val localX = dx * cos + dy * sin
        val localY = -dx * sin + dy * cos

        return abs(localX) <= hitHalfW && abs(localY) <= hitHalfH
    }

    private fun moveSelectedItem(dx: Float, dy: Float) {
        val item = getSelectedItem() ?: return
        
        item.x = (item.x + dx / width).coerceIn(0f, 1f)
        item.y = (item.y + dy / height).coerceIn(0f, 1f)
        
        itemChangeListener?.invoke(item)
    }

    private fun scaleSelectedItem(scaleFactor: Float) {
        val item = getSelectedItem() ?: return
        
        item.scale = (item.scale * scaleFactor).coerceIn(0.2f, 5f)
        
        itemChangeListener?.invoke(item)
        invalidate()
    }

    private fun getSelectedItem(): OverlayItem? {
        return items.find { it.id == selectedId }
    }

    // Public API
    
    fun addItem(item: OverlayItem) {
        items.add(item)
        invalidate()
    }

    fun removeItem(id: String) {
        items.removeAll { it.id == id }
        if (selectedId == id) {
            selectedId = null
            selectionListener?.invoke(null)
        }
        invalidate()
    }

    fun updateItem(item: OverlayItem) {
        val index = items.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            items[index] = item
            invalidate()
        }
    }

    fun getItems(): List<OverlayItem> = items.toList()
    
    fun clearItems() {
        items.clear()
        selectedId = null
        invalidate()
    }
    
    fun setItems(newItems: List<OverlayItem>) {
        items.clear()
        items.addAll(newItems)
        invalidate()
    }

    fun setSelectionListener(listener: (String?) -> Unit) {
        this.selectionListener = listener
    }

    fun setItemChangeListener(listener: (OverlayItem) -> Unit) {
        this.itemChangeListener = listener
    }

    // Scale gesture listener
    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleSelectedItem(detector.scaleFactor)
            return true
        }
    }

    private companion object {
        // Overlay width as a fraction of the view width at scale 1.0. Mirrors
        // OverlayRenderer's 20%-of-stream-width base so the editor box matches the stream.
        const val BASE_WIDTH_FRACTION = 0.20f

        // Smallest half-extent (px) a touch target may shrink to, so heavily-shrunk
        // overlays stay tappable even when their drawn box is only a few pixels.
        const val MIN_TOUCH_HALF_PX = 48f
    }
}
