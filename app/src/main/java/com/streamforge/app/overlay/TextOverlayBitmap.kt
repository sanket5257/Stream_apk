package com.streamforge.app.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Rasterizes a text overlay into a bitmap ourselves instead of letting RootEncoder's
 * TextStreamObject do it.
 *
 * Why we don't use the library path: TextStreamObject renders the string as ONE line into a
 * bitmap sized exactly `measureText(text)` × lineHeight, with no upper bound. For a sentence
 * or a paragraph at our high render size that asks for a bitmap tens of thousands of pixels
 * wide, which breaks in three ways at once:
 *  1. It blows past GL_MAX_TEXTURE_SIZE, so the quad can't show the text properly.
 *  2. The overlay box is a fixed 20% × scale of the frame width, so a single 40:1 line gets
 *     squeezed into a sliver — the "long text looks tiny even at max size" symptom.
 *  3. It throws OutOfMemoryError, which is an *Error*, not an Exception — so the per-overlay
 *     `catch (e: Exception)` guards in OverlayRenderer never see it and the process dies.
 *     Re-picking a font re-rasterizes at the (usually wider) new typeface, which is why
 *     changing the font on a long overlay kicked the user back to the home screen.
 *
 * Here the text is word-wrapped into a balanced block, the render size is derived from that
 * block (never from the raw string length), every dimension is hard-capped, and the whole
 * thing is wrapped in a `Throwable` guard so a pathological string degrades to "no overlay"
 * instead of a crash.
 */
object TextOverlayBitmap {

    private const val TAG = "TextOverlayBitmap"

    /** A rasterized overlay: the texture plus its true width / height ratio. */
    class Rendered(val bitmap: Bitmap, val aspect: Float)

    /**
     * Render [text] with the given font and colour.
     *
     * The texture is sized to the overlay's actual on-screen box (supersampled), not to a
     * fixed maximum — a full-resolution texture for every text overlay would allocate many
     * megabytes each, and the allocation lands right when Go Live is also claiming encoder,
     * camera and GL buffers.
     *
     * Static text only. Scrolling tickers are one unbroken line and are drawn straight onto
     * their strip by OverlayRenderer, so they never come through here — wrapping is exactly
     * what a ticker must not do.
     *
     * @param targetPx the width the text block will occupy on screen, in output pixels,
     *                 already supersampled by the caller.
     * @return the bitmap and its aspect ratio, or null if the text is blank or rasterization
     *         failed (caller should leave the overlay as-is rather than crash).
     */
    fun render(
        text: String,
        fontKey: String?,
        colorArgb: Int,
        targetPx: Float
    ): Rendered? {
        if (text.isBlank()) return null
        val content = text.trim()
        if (content.isEmpty()) return null
        var target = targetPx
        // Degrade to a coarser texture rather than dropping the overlay: an OOM here is a
        // transient memory-pressure failure, and half-resolution text beats no text.
        repeat(OOM_RETRIES) { attempt ->
            try {
                return rasterize(content, fontKey, colorArgb, target)
            } catch (e: OutOfMemoryError) {
                android.util.Log.w(TAG, "Text bitmap OOM at target ${target.toInt()}px, retrying smaller", e)
                target /= 2f
                if (attempt == OOM_RETRIES - 1) return null
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "Text rasterization failed for \"${content.take(32)}\"", t)
                return null
            }
        }
        return null
    }

    private fun rasterize(
        text: String,
        fontKey: String?,
        colorArgb: Int,
        targetPx: Float
    ): Rendered? {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = OverlayFonts.typefaceFor(fontKey)
            color = colorArgb
            textSize = REF_TEXT_PX
        }

        // Measure once at a reference size; everything below is a ratio of it, so the layout
        // decisions are independent of the size we finally rasterize at.
        val fm = paint.fontMetrics
        val refLineHeight = (fm.descent - fm.ascent).coerceAtLeast(1f)
        val refTotalWidth = text.split('\n')
            .fold(0f) { acc, line -> acc + paint.measureText(line) }
            .coerceAtLeast(1f)

        // Wrap width (still at the reference size). A line-and-a-bit stays as typed; only text
        // that would be an extreme strip gets wrapped. Laying it out over n lines makes the
        // block ~refTotalWidth/n wide and n·lineHeight tall, so the n that lands the block near
        // TARGET_BLOCK_ASPECT is sqrt(rawAspect / TARGET_BLOCK_ASPECT). That is what keeps a
        // paragraph's glyphs big: 5 balanced lines are 5× taller than one endless line.
        val rawAspect = refTotalWidth / refLineHeight
        val refWrap = if (rawAspect <= SINGLE_LINE_MAX_ASPECT) {
            refTotalWidth
        } else {
            val lines = ceil(sqrt((rawAspect / TARGET_BLOCK_ASPECT).toDouble()))
                .toInt()
                .coerceIn(1, MAX_LINES)
            max(refTotalWidth / lines, longestWordWidth(paint, text))
        }

        // Rasterize to the block's real on-screen width.
        var textPx = (REF_TEXT_PX * targetPx / refWrap).coerceIn(MIN_TEXT_PX, MAX_TEXT_PX)

        var layout = layoutFor(text, paint, textPx, refWrap)
        // Shrink until the bitmap fits the texture-size and memory caps. Two passes converge;
        // the third is a backstop.
        var pass = 0
        while (pass++ < MAX_FIT_PASSES) {
            val w = contentWidth(layout) + 2f * horizontalPadding(textPx)
            val h = layout.height.toFloat()
            if (w < 1f || h < 1f) return null
            val factor = minOf(MAX_DIM / w, MAX_DIM / h, sqrt(MAX_PIXELS / (w * h)), 1f)
            if (factor >= 0.999f) break
            textPx = (textPx * factor).coerceAtLeast(MIN_TEXT_PX)
            layout = layoutFor(text, paint, textPx, refWrap)
        }

        val pad = horizontalPadding(textPx)
        val lineWidth = contentWidth(layout)
        val width = ceil(lineWidth + 2f * pad).toInt().coerceIn(1, MAX_DIM.toInt())
        val height = layout.height.coerceIn(1, MAX_DIM.toInt())

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Lines are centred inside the layout's wrap width; we crop to the widest line, so
        // shift left by the slack and back right by the padding.
        canvas.translate(pad - (layout.width - lineWidth) / 2f, 0f)
        layout.draw(canvas)

        return Rendered(bitmap, width.toFloat() / height.toFloat())
    }

    private fun layoutFor(
        text: String,
        paint: TextPaint,
        textPx: Float,
        refWrap: Float
    ): StaticLayout {
        paint.textSize = textPx
        // Slack absorbs rounding, so a line never wraps one word early.
        val wrapPx = (ceil(refWrap * textPx / REF_TEXT_PX).toInt() + 4)
            .coerceIn(1, MAX_DIM.toInt())

        return StaticLayout.Builder.obtain(text, 0, text.length, paint, wrapPx)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, LINE_SPACING_MULT)
            .setIncludePad(true)
            .build()
    }

    /** Widest actual line, so we crop the texture instead of padding it out to the wrap width. */
    private fun contentWidth(layout: StaticLayout): Float {
        var widest = 0f
        for (i in 0 until layout.lineCount) widest = max(widest, layout.getLineWidth(i))
        return widest
    }

    /** Breathing room for glyphs that overhang their advance width (italic tails, matras). */
    private fun horizontalPadding(textPx: Float): Float = textPx * 0.08f

    /** A line can never be narrower than the longest single word without breaking it apart. */
    private fun longestWordWidth(paint: TextPaint, text: String): Float {
        var widest = 1f
        for (word in text.split(' ', '\n', '\t')) {
            if (word.isEmpty()) continue
            widest = max(widest, paint.measureText(word))
        }
        return widest
    }

    // Reference size everything is measured at before choosing the real render size.
    private const val REF_TEXT_PX = 100f

    // Anything up to a 10:1 strip is left exactly as the user typed it — a lower-third line
    // should not silently become two lines.
    private const val SINGLE_LINE_MAX_ASPECT = 10f

    // Width / height the wrapped block aims for. Lower = taller block = bigger glyphs for a
    // given overlay width; 5:1 reads like a caption card and keeps a paragraph legible.
    private const val TARGET_BLOCK_ASPECT = 5f
    private const val MAX_LINES = 16

    // Hard caps. MAX_DIM stays within the GL_MAX_TEXTURE_SIZE every supported device
    // guarantees; MAX_PIXELS bounds the allocation at ~10 MB (ARGB_8888) even if the caller
    // asks for a full-frame overlay.
    private const val MAX_DIM = 4096f
    private const val MAX_PIXELS = 2_500_000f
    private const val MIN_TEXT_PX = 12f
    private const val MAX_TEXT_PX = 900f
    private const val MAX_FIT_PASSES = 3

    // Attempts at progressively halved resolution before giving up on an OOM.
    private const val OOM_RETRIES = 3

    private const val LINE_SPACING_MULT = 1.05f
}
