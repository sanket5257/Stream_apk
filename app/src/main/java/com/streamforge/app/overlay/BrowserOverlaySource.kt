package com.streamforge.app.overlay

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender

/**
 * Renders a live web page (e.g. a StreamElements browser-source URL) into RootEncoder's
 * GL pipeline so it composites onto the outgoing stream and the on-device preview — with a
 * TRANSPARENT background, so only the page's own pixels (alerts/ticker/logo) land on top of
 * the camera and everything else shows the camera through.
 *
 * Why this isn't just a Presentation-on-a-VirtualDisplay pointed at our Surface:
 * a VirtualDisplay always composites onto an OPAQUE BLACK background. The captured Surface
 * therefore has alpha=1 everywhere, and RootEncoder's full-frame object filter then blends
 * that solid black over the camera — blacking out the whole frame and leaving only the
 * drawn widgets visible (the "URL overlay isn't capturing the full view" symptom).
 *
 * So we split the two jobs the VirtualDisplay used to do:
 *  - LAYOUT/TICK: we still host the [WebView] in a [Presentation] on a 1920×1080
 *    [VirtualDisplay] so the page lays out at StreamElements' native resolution and its JS
 *    timers / CSS animations run against a real display vsync. That virtual display now
 *    renders to a throwaway [ImageReader] whose frames we drain and discard.
 *  - CAPTURE: we put the WebView in software layer mode and, on every frame, lock the
 *    SurfaceFilterRender's [Surface], clear it to fully transparent, and draw the WebView
 *    onto it ourselves. Because WE produce the buffer (CPU) and clear with PorterDuff.CLEAR,
 *    the page's transparent regions stay alpha=0 and the camera shows through.
 *
 * The [context] must be window-capable (an Activity); a Presentation cannot be shown from a
 * bare application context.
 */
class BrowserOverlaySource(
    private val context: Context,
    private val url: String,
    private val renderWidth: Int,
    private val renderHeight: Int
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var captureSurface: Surface? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: WebPresentation? = null
    private var webView: WebView? = null
    private var released = false
    private var drawing = false
    private var loggedSizes = false

    val filter: SurfaceFilterRender = SurfaceFilterRender(
        SurfaceFilterRender.SurfaceReadyCallback { surfaceTexture ->
            // SurfaceReadyCallback fires on the GL thread; touch WebView/Presentation on main.
            mainHandler.post { onSurfaceReady(surfaceTexture) }
        }
    )

    // Continuously redraw the WebView onto the capture surface (~30fps) so animations,
    // alerts and the ticker stay live. One repeating runnable covers the overlay's lifetime.
    private val drawRunnable = object : Runnable {
        override fun run() {
            drawFrame()
            if (drawing) mainHandler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    private fun onSurfaceReady(surfaceTexture: SurfaceTexture) {
        if (released || captureSurface != null) return
        try {
            surfaceTexture.setDefaultBufferSize(renderWidth, renderHeight)
            captureSurface = Surface(surfaceTexture)

            // Throwaway sink for the virtual display. We never show these frames; draining
            // them keeps the display's buffer queue moving so the WebView keeps ticking.
            val reader = ImageReader.newInstance(
                renderWidth, renderHeight, PixelFormat.RGBA_8888, 2
            )
            reader.setOnImageAvailableListener({ r ->
                try { r.acquireLatestImage()?.close() } catch (_: Exception) { }
            }, mainHandler)
            imageReader = reader

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
                reader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            )
            virtualDisplay = vd

            val display = vd?.display ?: return
            val p = WebPresentation(context, display, url, renderWidth, renderHeight) { wv ->
                // WebView is now created, laid out at native res and loading the page.
                if (released) return@WebPresentation
                webView = wv
                if (!drawing) {
                    drawing = true
                    mainHandler.post(drawRunnable)
                }
            }
            presentation = p
            p.show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start browser overlay for $url", e)
        }
    }

    /**
     * Capture one frame: lock the GL pipeline's surface, wipe it to fully transparent, and
     * paint the current WebView state on top. The CLEAR is what gives the overlay real
     * transparency — without it the buffer would keep whatever opaque content was there
     * before and we'd be back to a black box over the camera.
     */
    private fun drawFrame() {
        if (released) return
        val wv = webView ?: return
        val surface = captureSurface ?: return
        if (!surface.isValid) return
        val canvas = try {
            surface.lockCanvas(null)
        } catch (_: Exception) {
            return
        }
        try {
            // Keep the WebView laid out at its authored render size so the page renders
            // correctly, then STRETCH its actual content to fill the whole capture buffer.
            // The page often paints shorter than the canvas (its content box is < the full
            // height), which left the lower part transparent and anchored the visible pixels
            // top-left. Mapping the content box (renderWidth × contentPx) onto the full buffer
            // makes it cover the entire frame automatically — no manual sizing needed.
            // Keep the WebView at the authored render size; the injected CSS (see
            // WebPresentation) stretches the page's own content to fill that viewport, so
            // here we just map the full viewport onto the capture buffer.
            if (wv.width != renderWidth || wv.height != renderHeight) {
                wv.measure(
                    View.MeasureSpec.makeMeasureSpec(renderWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(renderHeight, View.MeasureSpec.EXACTLY)
                )
                wv.layout(0, 0, renderWidth, renderHeight)
            }
            val cw = canvas.width
            val ch = canvas.height
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            if (cw > 0 && ch > 0 && (cw != renderWidth || ch != renderHeight)) {
                canvas.scale(cw.toFloat() / renderWidth, ch.toFloat() / renderHeight)
            }
            wv.draw(canvas)
        } catch (e: Exception) {
            Log.e(TAG, "draw failed for $url", e)
        } finally {
            try { surface.unlockCanvasAndPost(canvas) } catch (_: Exception) { }
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
        drawing = false
        mainHandler.removeCallbacks(drawRunnable)
        webView = null
        try { presentation?.dismiss() } catch (_: Exception) { }
        presentation = null
        try { virtualDisplay?.release() } catch (_: Exception) { }
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) { }
        imageReader = null
        captureSurface?.release()
        captureSurface = null
    }

    /**
     * A full-bleed transparent WebView window shown on the virtual display. It exists purely
     * to give the WebView a real, correctly-sized window so it lays out at native resolution
     * and its animations tick; the visible pixels are captured by the outer class drawing the
     * WebView itself, not by this Presentation's own (opaque) compositing.
     */
    private class WebPresentation(
        outerContext: Context,
        display: android.view.Display,
        private val url: String,
        private val renderWidth: Int,
        private val renderHeight: Int,
        private val onReady: (WebView) -> Unit
    ) : Presentation(outerContext, display) {

        private var webView: WebView? = null

        @SuppressLint("SetJavaScriptEnabled")
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
            val wv = WebView(context)
            wv.layoutParams = ViewGroup.LayoutParams(renderWidth, renderHeight)
            // Transparent so only the overlay's own pixels (alerts/text) composite onto video.
            wv.setBackgroundColor(Color.TRANSPARENT)
            // Software layer is required so the outer class can draw the live page onto an
            // arbitrary (CPU) Canvas with preserved alpha — a hardware-layer WebView draws
            // blank to a software canvas, and the virtual display's own output is opaque.
            wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
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
            // Force the page to fill the full 1920×1080 canvas. Some overlay pages don't
            // stretch html/body to 100% height, so bottom-anchored widgets (e.g. a news
            // ticker) render high and the lower part of the frame stays empty. Pin the
            // document to the exact render size once it loads.
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, finishedUrl: String?) {
                    super.onPageFinished(view, finishedUrl)
                    // StreamElements overlays render onto a FIXED-size canvas element (#overlay
                    // / .overlay) sized to the resolution set in the SE dashboard — commonly
                    // 1280×720, which then sits in the top-left of our 1920×1080 WebView. Scale
                    // the whole page so that canvas element fills our render size. For a 16:9
                    // overlay on a 16:9 render the X/Y factors are equal → uniform scale, NO
                    // distortion (just the overlay's native upscale). Re-run because the canvas
                    // and its widgets initialise asynchronously.
                    val js = "(function(){try{" +
                        "var b=document.body;if(!b)return;" +
                        "b.style.margin='0';b.style.padding='0';b.style.overflow='hidden';" +
                        "var ov=document.querySelector('#overlay,.overlay');" +
                        "var ow=ov?ov.offsetWidth:0,oh=ov?ov.offsetHeight:0;" +
                        "if(ow>0&&oh>0){var sx=$renderWidth/ow,sy=$renderHeight/oh;" +
                        "b.style.transformOrigin='0 0';b.style.transform='scale('+sx+','+sy+')';}" +
                        "}catch(e){}})();"
                    view.evaluateJavascript(js, null)
                    view.postDelayed({ try { view.evaluateJavascript(js, null) } catch (_: Exception) {} }, 1000)
                    view.postDelayed({ try { view.evaluateJavascript(js, null) } catch (_: Exception) {} }, 2500)
                }
            }
            wv.webChromeClient = WebChromeClient()
            wv.isVerticalScrollBarEnabled = false
            wv.isHorizontalScrollBarEnabled = false
            webView = wv
            setContentView(wv)
            wv.loadUrl(url)
            onReady(wv)
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
        // ~30fps redraw of the page onto the capture surface.
        private const val FRAME_INTERVAL_MS = 33L
    }
}
