package com.streamforge.app.overlay

import android.graphics.Paint
import android.graphics.Typeface

/**
 * Font catalog for text overlays.
 *
 * Each entry maps a stable serialized [key] (persisted in the overlay JSON) to a human
 * [label] and an Android [Typeface]. We deliberately use the platform font families
 * (sans/serif/mono/condensed) rather than bundling TTFs: every one of them resolves
 * Devanagari (Marathi/Hindi) glyphs through the system Noto fallback, so the choice is
 * about Latin styling — crispness of complex scripts comes from rendering the text bitmap
 * at high resolution (see OverlayRenderer), not from the family.
 */
object OverlayFonts {

    data class Entry(val key: String, val label: String)

    const val DEFAULT_KEY = "default"

    val ALL: List<Entry> = listOf(
        Entry(DEFAULT_KEY, "Default"),
        Entry("sans", "Sans Serif"),
        Entry("sans_bold", "Sans Serif Bold"),
        Entry("sans_condensed", "Sans Condensed"),
        Entry("serif", "Serif"),
        Entry("serif_bold", "Serif Bold"),
        Entry("mono", "Monospace"),
    )

    /** Resolve a serialized key to a concrete [Typeface]; unknown keys fall back to default. */
    fun typefaceFor(key: String?): Typeface = when (key) {
        "sans" -> Typeface.SANS_SERIF
        "sans_bold" -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        "sans_condensed" -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        "serif" -> Typeface.SERIF
        "serif_bold" -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        "mono" -> Typeface.MONOSPACE
        else -> Typeface.DEFAULT
    }

    fun labelFor(key: String?): String =
        ALL.firstOrNull { it.key == key }?.label ?: ALL.first().label

    fun keyForLabel(label: String?): String =
        ALL.firstOrNull { it.label == label }?.key ?: DEFAULT_KEY

    /**
     * The aspect ratio (width / height) that RootEncoder's TextStreamObject produces for
     * this text, computed with the same Paint geometry the library uses
     * (width = measureText, height = descent − ascent). Aspect is scale-invariant, so any
     * text size works; we just need it to size the overlay box without distortion.
     */
    fun textAspect(text: String, fontKey: String?): Float {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 100f
            typeface = typefaceFor(fontKey)
        }
        val width = paint.measureText(text).coerceAtLeast(1f)
        val fm = paint.fontMetrics
        val height = (fm.descent - fm.ascent).coerceAtLeast(1f)
        return width / height
    }
}
