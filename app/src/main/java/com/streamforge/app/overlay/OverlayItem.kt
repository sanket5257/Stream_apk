package com.streamforge.app.overlay

import kotlinx.serialization.Serializable

/**
 * Phase 3B: Overlay item data model.
 * Phase 4B: Made serializable for persistence.
 * Represents an item (image or text) that can be positioned on the stream.
 */
@Serializable
sealed class OverlayItem {
    abstract val id: String
    abstract var x: Float          // 0..1, fraction of view width
    abstract var y: Float          // 0..1, fraction of view height
    abstract var scale: Float      // WIDTH multiplier (1.0 = base width)
    abstract var heightScale: Float // HEIGHT multiplier (1.0 = aspect-correct height for scale=1)
    abstract var rotation: Float   // degrees
    abstract var zIndex: Int       // drawing order (higher = on top)
    abstract var visible: Boolean  // show/hide toggle
    abstract var locked: Boolean   // when true, ignore preview move/scale/rotate gestures

    /**
     * Image overlay (PNG, JPG, etc.)
     */
    @Serializable
    data class Image(
        override val id: String,
        val uri: String,           // content:// URI
        override var x: Float = 0.5f,
        override var y: Float = 0.5f,
        override var scale: Float = 1.0f,
        override var heightScale: Float = 1.0f,
        override var rotation: Float = 0f,
        override var zIndex: Int = 0,
        override var visible: Boolean = true,
        override var locked: Boolean = false
    ) : OverlayItem()

    /**
     * Text overlay
     */
    @Serializable
    data class Text(
        override val id: String,
        val text: String,
        val fontSizeSp: Float = 24f,
        val colorArgb: Int = 0xFFFFFFFF.toInt(),
        /** Font family key from [OverlayFonts]; defaults to the platform default. */
        val fontKey: String = OverlayFonts.DEFAULT_KEY,
        /** When true the text scrolls horizontally right-to-left, like a news ticker. */
        val scroll: Boolean = false,
        /**
         * Width of the band a scrolling ticker travels inside, as a fraction of the frame
         * width. The band is centred on [x], so dragging the overlay moves it. 1.0 (the
         * default) means edge-to-edge across the whole frame; anything smaller clips the
         * text to the band so it enters and exits within that area.
         */
        val scrollWidth: Float = 1f,
        override var x: Float = 0.5f,
        override var y: Float = 0.5f,
        override var scale: Float = 1.0f,
        override var heightScale: Float = 1.0f,
        override var rotation: Float = 0f,
        override var zIndex: Int = 0,
        override var visible: Boolean = true,
        override var locked: Boolean = false
    ) : OverlayItem()

    /**
     * Animated GIF overlay (rendered via RootEncoder's GifObjectFilterRender).
     */
    @Serializable
    data class Gif(
        override val id: String,
        val uri: String,
        override var x: Float = 0.5f,
        override var y: Float = 0.5f,
        override var scale: Float = 1.0f,
        override var heightScale: Float = 1.0f,
        override var rotation: Float = 0f,
        override var zIndex: Int = 0,
        override var visible: Boolean = true,
        override var locked: Boolean = false
    ) : OverlayItem()

    /**
     * Video overlay. Frames are extracted with MediaMetadataRetriever at low fps
     * and pushed into an ImageObjectFilterRender. Audio track is ignored.
     */
    @Serializable
    data class Video(
        override val id: String,
        val uri: String,
        val loop: Boolean = true,
        /** Chroma key (green-screen) removal. */
        val chromaEnabled: Boolean = false,
        /** Key colour as ARGB int; default pure green. */
        val chromaColor: Int = 0xFF00FF00.toInt(),
        /** Key radius/strength (~0.2 tight … 0.8 loose). */
        val chromaSensitive: Float = 0.45f,
        override var x: Float = 0.5f,
        override var y: Float = 0.5f,
        override var scale: Float = 1.0f,
        override var heightScale: Float = 1.0f,
        override var rotation: Float = 0f,
        override var zIndex: Int = 0,
        override var visible: Boolean = true,
        override var locked: Boolean = false
    ) : OverlayItem()

    /**
     * Browser / URL overlay. A live web page (e.g. a StreamElements alert or chat box URL)
     * is rendered off-screen in a WebView and composited into the stream. The render canvas
     * defaults to StreamElements' native 1920×1080 so widget sizes/positions reproduce the
     * dashboard 1:1. Defaults to full-frame (scale/heightScale = 5.0 → 100% × 100%) so it
     * fills the screen out of the box, but can be resized via the Width/Height controls.
     */
    @Serializable
    data class Browser(
        override val id: String,
        val url: String,
        val renderWidth: Int = 1920,
        val renderHeight: Int = 1080,
        override var x: Float = 0.5f,
        override var y: Float = 0.5f,
        override var scale: Float = 5.0f,
        override var heightScale: Float = 5.0f,
        override var rotation: Float = 0f,
        override var zIndex: Int = 0,
        override var visible: Boolean = true,
        override var locked: Boolean = false
    ) : OverlayItem()
}
