package com.streamforge.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.Surface
import com.pedro.encoder.input.gl.render.filters.`object`.BaseObjectFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender

/**
 * Streams a video into RootEncoder's GL pipeline. Frames go: hardware decoder →
 * SurfaceTexture (GL OES) → filter → encoder. No CPU readback.
 *
 * If [chromaEnabled], a [ChromaVideoFilterRender] is used so the key colour
 * (green-screen) is removed on the GPU and the camera shows through; otherwise a plain
 * [SurfaceFilterRender]. Audio is muted; loop is configurable.
 */
class VideoOverlayPlayer(
    private val context: Context,
    private val uri: Uri,
    private val loop: Boolean,
    chromaEnabled: Boolean = false,
    chromaColor: Int = Color.GREEN,
    chromaSensitive: Float = 0.45f
) {
    private var mediaPlayer: MediaPlayer? = null
    private var surface: Surface? = null
    private val lock = Any()
    private var released = false

    /** The filter the caller adds to `rtmpCamera.glInterface`. */
    val filter: BaseObjectFilterRender = if (chromaEnabled) {
        ChromaVideoFilterRender { st -> attachMediaPlayer(st) }.apply {
            setChromaColor(
                Color.red(chromaColor) / 255f,
                Color.green(chromaColor) / 255f,
                Color.blue(chromaColor) / 255f
            )
            setSensitive(chromaSensitive)
        }
    } else {
        SurfaceFilterRender { st -> attachMediaPlayer(st) }
    }

    private fun attachMediaPlayer(surfaceTexture: SurfaceTexture) {
        synchronized(lock) {
            if (released || mediaPlayer != null) return
            try {
                val s = Surface(surfaceTexture)
                surface = s
                mediaPlayer = MediaPlayer().apply {
                    setSurface(s)
                    setVolume(0f, 0f)
                    isLooping = loop
                    setDataSource(context, uri)
                    setOnPreparedListener {
                        try { start() } catch (_: Exception) { }
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.w(TAG, "MediaPlayer error what=$what extra=$extra uri=$uri")
                        true
                    }
                    prepareAsync()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach MediaPlayer for $uri", e)
            }
        }
    }

    fun release() {
        synchronized(lock) {
            released = true
            mediaPlayer?.let { mp ->
                try { mp.stop() } catch (_: Exception) { }
                try { mp.release() } catch (_: Exception) { }
            }
            mediaPlayer = null
            surface?.release()
            surface = null
        }
    }

    companion object {
        private const val TAG = "VideoOverlayPlayer"
    }
}
