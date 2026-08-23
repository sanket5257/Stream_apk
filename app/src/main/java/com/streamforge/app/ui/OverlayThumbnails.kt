package com.streamforge.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors

/**
 * Row thumbnails for the overlay list, decoded small and off the main thread.
 *
 * The previous inline decode was a crash and a stall at once. `BitmapFactory.decodeStream`
 * with no options decodes at FULL resolution: a 12 MP phone photo is a ~48 MB ARGB_8888
 * allocation, per row, on the main thread, every time the sheet scrolled or reloaded. That
 * reliably threw [OutOfMemoryError] — which is an Error, so the `catch (Exception)` around it
 * never fired and the process died. `MediaMetadataRetriever.getFrameAtTime` had the same two
 * problems plus multi-hundred-millisecond I/O on the UI thread.
 *
 * Here every decode is bounded to [TARGET_PX] via inSampleSize, runs on a small background
 * pool, is memory-cached by URI, and is guarded against [Throwable] so a corrupt file or a
 * memory spike degrades to the placeholder icon.
 */
object OverlayThumbnails {

    private const val TAG = "OverlayThumbnails"

    /** Long-edge size we decode to. Rows show a ~40dp icon; this is generous even at xxhdpi. */
    private const val TARGET_PX = 192

    /** ~40 thumbnails at 192px ARGB_8888 (~150 KB each). Plenty for any realistic overlay list. */
    private const val CACHE_BYTES = 6 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    // Two threads: enough to keep a scrolling list fed, few enough that several large decodes
    // can never be in flight at once (which is how bounded decodes still add up to an OOM).
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "overlay-thumb").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Show a thumbnail for [uriString] in [target], falling back to [fallbackRes].
     *
     * The ImageView is tagged with the URI it is loading so a recycled row never receives the
     * previous row's bitmap.
     */
    fun load(target: ImageView, uriString: String, isVideo: Boolean, fallbackRes: Int) {
        val cached = cache.get(uriString)
        if (cached != null && !cached.isRecycled) {
            target.setTag(TAG_KEY, uriString)
            target.setImageBitmap(cached)
            return
        }

        target.setTag(TAG_KEY, uriString)
        target.setImageResource(fallbackRes)
        val context = target.context.applicationContext

        executor.execute {
            val bitmap = try {
                if (isVideo) decodeVideoFrame(context, uriString) else decodeImage(context, uriString)
            } catch (t: Throwable) {
                // Includes OutOfMemoryError: a thumbnail is never worth killing the process.
                Log.w(TAG, "Thumbnail decode failed for $uriString", t)
                null
            } ?: return@execute

            cache.put(uriString, bitmap)
            mainHandler.post {
                if (target.getTag(TAG_KEY) == uriString) target.setImageBitmap(bitmap)
            }
        }
    }

    private fun decodeImage(context: Context, uriString: String): Bitmap? {
        val uri = Uri.parse(uriString)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            // RGB_565 halves the allocation; a 40dp row icon has no use for an alpha channel
            // or 8 bits per component.
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    private fun decodeVideoFrame(context: Context, uriString: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(uriString))
            // getScaledFrameAtTime decodes straight to the size we want instead of pulling a
            // full 4K frame into memory and throwing most of it away.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, TARGET_PX, TARGET_PX
                )
            } else {
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?.let { downscale(it) }
            }
        } finally {
            try { retriever.release() } catch (_: Throwable) { }
        }
    }

    private fun downscale(source: Bitmap): Bitmap {
        val longEdge = maxOf(source.width, source.height)
        if (longEdge <= TARGET_PX) return source
        val ratio = TARGET_PX.toFloat() / longEdge
        val scaled = Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun sampleSize(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / sample > TARGET_PX) sample *= 2
        return sample
    }

    private val TAG_KEY = com.streamforge.app.R.id.overlay_thumbnail_uri_tag
}
