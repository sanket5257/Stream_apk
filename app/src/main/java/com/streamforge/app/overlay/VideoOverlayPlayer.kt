package com.streamforge.app.overlay

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.Surface
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender

/**
 * Phase 6 upgrade: streams a video directly into RootEncoder's GL pipeline via
 * SurfaceFilterRender. Frames go: hardware decoder → SurfaceTexture (GL OES) →
 * RootEncoder's filter pipeline → encoder. No CPU readback, no per-frame bitmap
 * allocation — runs at the source's native frame rate.
 *
 * Audio is muted; this is a visual overlay only. Loop is configurable.
 */
class VideoOverlayPlayer(
    private val context: Context,
    private val uri: Uri,
    private val loop: Boolean
) {
    private var mediaPlayer: MediaPlayer? = null
    private var surface: Surface? = null
    private val lock = Any()
    private var released = false

    /**
     * The filter the caller adds to `rtmpCamera.glInterface`. We pass a callback
     * so we can attach the MediaPlayer only once the SurfaceTexture is GL-ready.
     */
    val filter: SurfaceFilterRender = SurfaceFilterRender(
        SurfaceFilterRender.SurfaceReadyCallback { surfaceTexture ->
            attachMediaPlayer(surfaceTexture)
        }
    )

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
