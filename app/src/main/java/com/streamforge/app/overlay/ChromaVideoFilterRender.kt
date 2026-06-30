package com.streamforge.app.overlay

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.pedro.encoder.input.gl.render.filters.`object`.BaseObjectFilterRender
import com.pedro.encoder.utils.gl.GlUtil
import com.streamforge.app.R

/**
 * A video overlay filter with GPU chroma-key (green-screen) removal.
 *
 * Mirrors RootEncoder's SurfaceFilterRender (MediaPlayer renders into an external/OES
 * SurfaceTexture), but compiles a custom fragment shader (chroma_video_fragment) that
 * cuts out pixels near a key colour so the camera shows through. Extends
 * BaseObjectFilterRender so the existing position/scale/rotation transform path keeps
 * working unchanged.
 */
class ChromaVideoFilterRender(
    private val onSurfaceReady: (SurfaceTexture) -> Unit
) : BaseObjectFilterRender() {

    private var surfaceTexture: SurfaceTexture? = null
    var surface: Surface? = null
        private set

    @Volatile private var sensitive = 0.45f
    @Volatile private var cr = 0f
    @Volatile private var cg = 1f
    @Volatile private var cb = 0f

    // Cached uniform locations for the custom chroma uniforms (the base class keeps the
    // GL program private, so we resolve them from the active program on first draw).
    private var uSensitiveLoc = -2
    private var uChromaLoc = -2

    override fun initGlFilter(context: Context) {
        // Use our chroma shader instead of the default surface shader. Must be set BEFORE
        // super compiles the program.
        fragment = R.raw.chroma_video_fragment
        super.initGlFilter(context)
        GlUtil.createExternalTextures(streamObjectTextureId.size, streamObjectTextureId, 0)
        val st = SurfaceTexture(streamObjectTextureId[0])
        st.setDefaultBufferSize(width, height)
        surfaceTexture = st
        surface = Surface(st)
        Handler(Looper.getMainLooper()).post { onSurfaceReady(st) }
    }

    override fun drawFilter() {
        surfaceTexture?.updateTexImage()
        super.drawFilter()
        // Rebind the object texture as EXTERNAL/OES (the base bound it as 2D) and set alpha.
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, streamObjectTextureId[0])
        GLES20.glUniform1f(uAlphaHandle, if (streamObjectTextureId[0] == -1) 0f else alpha)

        if (uSensitiveLoc == -2) {
            val prog = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, prog, 0)
            if (prog[0] != 0) {
                uSensitiveLoc = GLES20.glGetUniformLocation(prog[0], "uSensitive")
                uChromaLoc = GLES20.glGetUniformLocation(prog[0], "uChromaColor")
            }
        }
        if (uSensitiveLoc >= 0) GLES20.glUniform1f(uSensitiveLoc, sensitive)
        if (uChromaLoc >= 0) GLES20.glUniform3f(uChromaLoc, cr, cg, cb)
    }

    override fun release() {
        super.release()
        surfaceTexture?.release()
        surfaceTexture = null
        surface?.release()
        surface = null
    }

    /** Key radius in RGB space (~0.2 tight … 0.8 loose). */
    fun setSensitive(value: Float) { sensitive = value }

    /** Key colour, channels 0..1. Default is green. */
    fun setChromaColor(r: Float, g: Float, b: Float) { cr = r; cg = g; cb = b }
}
