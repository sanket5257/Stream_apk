package com.streamforge.app.overlay

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender

/**
 * Renders a live web page (e.g. a StreamElements browser-source URL) into RootEncoder's
 * GL pipeline so it composites onto the outgoing stream and the on-device preview.
 *
 * How it works: RootEncoder gives us a [SurfaceTexture] via [SurfaceFilterRender]. We wrap
 * it in a [Surface] and drive a [VirtualDisplay] onto it. A [Presentation] shown on that
 * virtual display hosts a [WebView] sized to the render resolution. Because the WebView
 * lives in a real (virtual) window, its JS timers, CSS animations and video play back
 * normally and are hardware-composited straight onto our Surface — no per-frame software
 * capture, no SYSTEM_ALERT_WINDOW permission.
 *
 * The [context] must be window-capable (an Activity); a Presentation cannot be shown from
 * a bare application context. This matches the app's model where the GL pipeline only
 * lives while StreamActivity is in the foreground.
 */
class BrowserOverlaySource(
    private val context: Context,
    private val url: String,
    private val renderWidth: Int,
    private val renderHeight: Int
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var surface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: WebPresentation? = null
    private var released = false

    val filter: SurfaceFilterRender = SurfaceFilterRender(
        SurfaceFilterRender.SurfaceReadyCallback { surfaceTexture ->
            // SurfaceReadyCallback fires on the GL thread; touch WebView/Presentation on main.
            mainHandler.post { onSurfaceReady(surfaceTexture) }
        }
    )

    private fun onSurfaceReady(surfaceTexture: SurfaceTexture) {
        if (released || virtualDisplay != null) return
        try {
            surfaceTexture.setDefaultBufferSize(renderWidth, renderHeight)
            val s = Surface(surfaceTexture)
            surface = s

            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            // Use mdpi (1 CSS px == 1 device px) so a [renderWidth]-px WebView lays the page
            // out at a [renderWidth]-CSS-px viewport. StreamElements overlays are authored on a
            // fixed 1920×1080 canvas, so rendering at 1920×1080 @ 160dpi reproduces the
            // dashboard 1:1 instead of re-scaling everything by the phone's screen density.
            val densityDpi = android.util.DisplayMetrics.DENSITY_DEFAULT
            val vd = dm.createVirtualDisplay(
                "browser-overlay-$url",
                renderWidth,
                renderHeight,
                densityDpi,
                s,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            )
            virtualDisplay = vd

            val display = vd?.display ?: return
            val p = WebPresentation(context, display, url, renderWidth, renderHeight)
            presentation = p
            p.show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start browser overlay for $url", e)
        }
    }

    fun release() {
        // Always tear down on the main thread; release() may be called from anywhere.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            doRelease()
        } else {
            mainHandler.post { doRelease() }
        }
    }

    private fun doRelease() {
        released = true
        try { presentation?.dismiss() } catch (_: Exception) { }
        presentation = null
        try { virtualDisplay?.release() } catch (_: Exception) { }
        virtualDisplay = null
        surface?.release()
        surface = null
    }

    /** A minimal full-bleed transparent WebView window shown on the virtual display. */
    private class WebPresentation(
        outerContext: Context,
        display: android.view.Display,
        private val url: String,
        private val renderWidth: Int,
        private val renderHeight: Int
    ) : Presentation(outerContext, display) {

        private var webView: WebView? = null

        @SuppressLint("SetJavaScriptEnabled")
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            // The Presentation is a Dialog: its window paints an opaque (white) background by
            // default, which would fill the whole video frame. Make the window — and its dim
            // layer — fully transparent so only the web page's own pixels composite onto video.
            window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
            val wv = WebView(context)
            wv.layoutParams = ViewGroup.LayoutParams(renderWidth, renderHeight)
            // Transparent so only the overlay's own pixels (alerts/text) composite onto video.
            wv.setBackgroundColor(Color.TRANSPARENT)
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                // Render the page at its authored size on our fixed-size canvas — do NOT
                // overview-fit or apply a wide viewport, which would re-scale the layout and
                // make element sizes diverge from the StreamElements dashboard.
                loadWithOverviewMode = false
                useWideViewPort = false
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            wv.webViewClient = WebViewClient()
            wv.webChromeClient = WebChromeClient()
            wv.isVerticalScrollBarEnabled = false
            wv.isHorizontalScrollBarEnabled = false
            webView = wv
            setContentView(wv)
            wv.loadUrl(url)
        }

        override fun onStop() {
            super.onStop()
            webView?.let { wv ->
                try {
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    wv.destroy()
                } catch (_: Exception) { }
            }
            webView = null
        }
    }

    companion object {
        private const val TAG = "BrowserOverlaySource"
    }
}
