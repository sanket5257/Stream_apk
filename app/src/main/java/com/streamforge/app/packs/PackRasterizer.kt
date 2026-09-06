package com.streamforge.app.packs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import android.util.LruCache
import kotlin.math.roundToInt

/**
 * Draws a [GraphicsPack] into a bitmap the overlay pipeline can upload as a texture.
 *
 * The whole pack is ONE bitmap, not one overlay per element. That is deliberate:
 *  - a scoreboard is a single object the user drags and sizes as a unit;
 *  - a broadcast graphic's internal alignment must be exact, and pixel layout in the pack's
 *    own canvas space gives that, where independently-positioned overlays never would;
 *  - it is one GL filter and one texture upload instead of a dozen, which matters on a phone
 *    that is simultaneously running a camera, an encoder and the RTMP stack.
 *
 * The canvas is sized to the graphic's own bounding box (e.g. 1200×160), NOT the full frame,
 * so a lower third costs a ~1200×160 texture rather than a 1920×1080 one.
 */
object PackRasterizer {

    private const val TAG = "PackRasterizer"

    data class Rendered(val bitmap: Bitmap, val aspect: Float)

    /**
     * Decoded logos, keyed by URI + target box. A scoreboard re-rasterizes on every "+1" tap,
     * and re-decoding a channel logo from storage each time would make the live control panel
     * feel sluggish exactly when it must not.
     */
    private val imageCache = LruCache<String, Bitmap>(8)

    /**
     * Render [pack] with [values] at [targetWidthPx] pixels wide.
     * Returns null if nothing could be drawn, so the caller skips the attach rather than
     * uploading an empty texture.
     */
    fun render(
        context: Context,
        pack: GraphicsPack,
        values: Map<String, String>,
        theme: PackTheme,
        targetWidthPx: Int,
    ): Rendered? {
        if (pack.canvas.w <= 0 || pack.canvas.h <= 0) return null

        val width = targetWidthPx.coerceIn(MIN_TARGET_PX, MAX_TARGET_PX)
        val scale = width.toFloat() / pack.canvas.w
        val height = (pack.canvas.h * scale).roundToInt().coerceAtLeast(1)

        return try {
            // createBitmap already returns fully transparent pixels — no erase needed.
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)

            pack.elements.forEach { element ->
                try {
                    if (!isVisible(element, values)) return@forEach
                    when (element.kind) {
                        PackElementKind.RECT -> drawRect(canvas, element, theme)
                        PackElementKind.TEXT -> drawText(canvas, element, pack, values, theme)
                        PackElementKind.IMAGE -> drawImage(context, canvas, element, values, scale)
                    }
                } catch (t: Throwable) {
                    // One bad element (a malformed colour, an unreadable logo) should cost that
                    // element, not the whole graphic — a scoreboard missing its crest still
                    // shows the score.
                    Log.e(TAG, "Element ${element.kind} failed in pack ${pack.id}", t)
                }
            }
            Rendered(bitmap, pack.canvas.aspect)
        } catch (t: Throwable) {
            // Throwable: bitmap allocation is exactly where OutOfMemoryError shows up, and a
            // missing graphic is recoverable where a dead process mid-broadcast is not.
            Log.e(TAG, "Rasterizing pack ${pack.id} failed", t)
            null
        }
    }

    /** Clear cached logo bitmaps. Called when the renderer tears down. */
    fun clearCache() {
        imageCache.evictAll()
    }

    // -----------------------------------------------------------------------------------

    private fun isVisible(element: PackElement, values: Map<String, String>): Boolean {
        if (element.showIf.isBlank()) return true
        val v = values[element.showIf]?.trim().orEmpty()
        return v.isNotEmpty() && !v.equals("false", ignoreCase = true) && v != "0"
    }

    private fun drawRect(canvas: Canvas, element: PackElement, theme: PackTheme) {
        if (element.w <= 0f || element.h <= 0f) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = resolveColor(element.fill, theme)
            style = Paint.Style.FILL
        }
        val rect = RectF(element.x, element.y, element.x + element.w, element.y + element.h)
        if (element.radius > 0f) {
            canvas.drawRoundRect(rect, element.radius, element.radius, paint)
        } else {
            canvas.drawRect(rect, paint)
        }
    }

    private fun drawText(
        canvas: Canvas,
        element: PackElement,
        pack: GraphicsPack,
        values: Map<String, String>,
        theme: PackTheme,
    ) {
        val resolved = resolveText(element, values)
        if (resolved.isBlank()) return

        val boxWidth = if (element.w > 0f) element.w else (pack.canvas.w - element.x)
        val boxHeight = if (element.h > 0f) element.h else element.size * 1.4f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = resolveColor(element.fill, theme)
            typeface = if (element.weight == PackWeight.BOLD) BOLD_FACE else REGULAR_FACE
            textSize = element.size
            letterSpacing = element.tracking
            isSubpixelText = true
        }

        // Auto-shrink rather than clip: team names and presenter titles vary wildly in length,
        // and a graphic that silently cuts off "Maharashtra" is worse than one that sets it a
        // little smaller. Below minScale we ellipsize, because past that it stops being legible.
        var text = resolved
        val minSize = element.size * element.minScale.coerceIn(0.2f, 1f)
        while (paint.textSize > minSize && paint.measureText(text) > boxWidth) {
            paint.textSize -= 1f
        }
        if (paint.measureText(text) > boxWidth) {
            text = ellipsize(text, paint, boxWidth)
        }

        val metrics = paint.fontMetrics
        // Vertically centre the line inside the box using real ascent/descent, not textSize —
        // Devanagari matras and conjuncts reach well past the nominal metrics.
        val baseline = element.y + (boxHeight - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent

        paint.textAlign = when (element.align) {
            PackAlign.LEFT -> Paint.Align.LEFT
            PackAlign.CENTER -> Paint.Align.CENTER
            PackAlign.RIGHT -> Paint.Align.RIGHT
        }
        val x = when (element.align) {
            PackAlign.LEFT -> element.x
            PackAlign.CENTER -> element.x + boxWidth / 2f
            PackAlign.RIGHT -> element.x + boxWidth
        }
        canvas.drawText(text, x, baseline, paint)
    }

    private fun drawImage(
        context: Context,
        canvas: Canvas,
        element: PackElement,
        values: Map<String, String>,
        scale: Float,
    ) {
        if (element.imageField.isBlank() || element.w <= 0f || element.h <= 0f) return
        val uri = values[element.imageField]?.takeIf { it.isNotBlank() } ?: return

        // Decode at the size the element actually occupies on the output, not the source's
        // full resolution — a 12 MP logo photo would otherwise be a ~48 MB allocation.
        val targetW = (element.w * scale).roundToInt().coerceAtLeast(1)
        val targetH = (element.h * scale).roundToInt().coerceAtLeast(1)
        val bitmap = decodeScaled(context, uri, targetW, targetH) ?: return

        // Fit-centre inside the element box so a non-square logo isn't stretched.
        val srcAspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
        val boxAspect = element.w / element.h
        val drawW: Float
        val drawH: Float
        if (srcAspect > boxAspect) {
            drawW = element.w
            drawH = element.w / srcAspect
        } else {
            drawH = element.h
            drawW = element.h * srcAspect
        }
        val left = element.x + (element.w - drawW) / 2f
        val top = element.y + (element.h - drawH) / 2f

        canvas.drawBitmap(
            bitmap,
            Rect(0, 0, bitmap.width, bitmap.height),
            RectF(left, top, left + drawW, top + drawH),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
    }

    private fun decodeScaled(context: Context, uriString: String, targetW: Int, targetH: Int): Bitmap? {
        val cacheKey = "$uriString@${targetW}x$targetH"
        imageCache[cacheKey]?.let { if (!it.isRecycled) return it }

        return try {
            val uri = Uri.parse(uriString)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetW, targetH)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            decoded?.also { imageCache.put(cacheKey, it) }
        } catch (t: Throwable) {
            Log.e(TAG, "Couldn't decode pack image $uriString", t)
            null
        }
    }

    private fun sampleSize(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Int {
        var sample = 1
        while (srcW / (sample * 2) >= targetW && srcH / (sample * 2) >= targetH) sample *= 2
        return sample
    }

    /**
     * Substitute `{fieldKey}` placeholders with the instance's values. An unknown or blank
     * key resolves to an empty string, so a half-filled pack degrades to a smaller graphic
     * rather than printing a literal "{teamB}" on air.
     */
    private fun resolveText(element: PackElement, values: Map<String, String>): String {
        var out = PLACEHOLDER.replace(element.text) { match ->
            values[match.groupValues[1]]?.trim().orEmpty()
        }
        // Every element draws ONE line. A LINES field (a list of headlines) therefore shows its
        // first entry — which is exactly what makes the CYCLE action work: cycling rotates the
        // list, so "next headline" is a one-tap rotation of the same field.
        out = out.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (element.caps) out = out.uppercase()
        return out
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (text.isEmpty()) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.take(end) + "…") > maxWidth) end--
        return text.take(end).trimEnd() + "…"
    }

    /**
     * Resolve a colour: either a theme role (`SURFACE`, `ACCENT`, …) or a literal
     * `#RRGGBB` / `#AARRGGBB`. Unknown values fall back to the theme's onSurface so a typo in
     * a pack shows as visible-but-wrong rather than invisible.
     */
    private fun resolveColor(token: String, theme: PackTheme): Int {
        val literal = when (token.uppercase()) {
            "SURFACE" -> theme.surface
            "ON_SURFACE", "ONSURFACE" -> theme.onSurface
            "ACCENT" -> theme.accent
            "ON_ACCENT", "ONACCENT" -> theme.onAccent
            "MUTED" -> theme.muted
            "TRANSPARENT" -> "#00000000"
            else -> token
        }
        return try {
            Color.parseColor(literal)
        } catch (_: IllegalArgumentException) {
            Log.w(TAG, "Unparseable colour '$token'; using onSurface")
            try { Color.parseColor(theme.onSurface) } catch (_: IllegalArgumentException) { Color.WHITE }
        }
    }

    private val PLACEHOLDER = Regex("\\{([A-Za-z0-9_]+)}")
    private val REGULAR_FACE: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val BOLD_FACE: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    /**
     * Bounds on the rasterized texture. The lower bound keeps small graphics legible; the
     * upper one keeps a full-width pack from allocating more than the frame is worth (2048px
     * wide already exceeds a 1080p output, so anything more is discarded by the GPU downscale).
     */
    private const val MIN_TARGET_PX = 256
    private const val MAX_TARGET_PX = 2048
}
