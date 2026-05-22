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
import kotlin.math.atan2
import kotlin.math.hypot

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
    
    // Paint for drawing items
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
    
    // Rotation detection
    private var initialAngle = 0f
    private var currentAngle = 0f
    private var isRotating = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw items sorted by zIndex, skip invisible items
        items.sortedBy { it.zIndex }.filter { it.visible }.forEach { item ->
            canvas.save()
            
            // Calculate screen position
            val screenX = item.x * width
            val screenY = item.y * height
            
            // Apply transformations
            canvas.translate(screenX, screenY)
            canvas.rotate(item.rotation)
            canvas.scale(item.scale, item.scale)
            
            // Draw based on type
            when (item) {
                is OverlayItem.Image -> {
                    // Draw red rectangle for image placeholder
                    val rect = RectF(-50f, -50f, 50f, 50f)
                    canvas.drawRect(rect, imagePaint)
                    
                    // Draw selection border if selected
                    if (item.id == selectedId) {
                        canvas.drawRect(rect, selectionPaint)
                    }
                }
                is OverlayItem.Text -> {
                    // Draw blue rectangle for text placeholder
                    val rect = RectF(-75f, -25f, 75f, 25f)
                    canvas.drawRect(rect, textPaint)
                    
                    // Draw text preview
                    textDrawPaint.textSize = item.fontSizeSp
                    textDrawPaint.color = item.colorArgb
                    val textWidth = textDrawPaint.measureText(item.text)
                    canvas.drawText(item.text, -textWidth / 2, 10f, textDrawPaint)
                    
                    // Draw selection border if selected
                    if (item.id == selectedId) {
                        canvas.drawRect(rect, selectionPaint)
                    }
                }
            }
            
            canvas.restore()
        }
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
                if (event.pointerCount == 2) {
                    // Start rotation detection
                    isRotating = true
                    initialAngle = getAngle(event)
                    currentAngle = getSelectedItem()?.rotation ?: 0f
                }
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
                } else if (event.pointerCount == 2 && isRotating) {
                    // Two finger rotation
                    val angle = getAngle(event)
                    val deltaAngle = angle - initialAngle
                    rotateSelectedItem(currentAngle + deltaAngle)
                }
                
                invalidate()
                return true
            }
            
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                isRotating = false
            }
            
            MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                isRotating = false
            }
        }
        
        return true
    }

    private fun selectItemAt(x: Float, y: Float) {
        // Hit test from top of z-order, only visible items
        val hitItem = items.sortedByDescending { it.zIndex }.filter { it.visible }.firstOrNull { item ->
            val screenX = item.x * width
            val screenY = item.y * height
            val distance = hypot(x - screenX, y - screenY)
            distance < 100f * item.scale // Hit radius
        }
        
        selectedId = hitItem?.id
        selectionListener?.invoke(selectedId)
        invalidate()
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

    private fun rotateSelectedItem(rotation: Float) {
        val item = getSelectedItem() ?: return
        
        item.rotation = rotation % 360f
        
        itemChangeListener?.invoke(item)
    }

    private fun getSelectedItem(): OverlayItem? {
        return items.find { it.id == selectedId }
    }

    private fun getAngle(event: MotionEvent): Float {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
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
}
