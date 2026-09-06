package com.streamforge.app.packs

import android.graphics.Bitmap
import kotlinx.serialization.json.Json
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Proves every shipped pack actually draws.
 *
 * [PackDefinitionTest] guards the JSON format; this guards the picture. The two are different
 * failures: a pack can parse perfectly and still rasterize to a fully transparent bitmap (an
 * element off-canvas, a colour that resolves to nothing, a zero-sized box), and transparent is
 * exactly what "the graphic doesn't work" looks like — no crash, no log, nothing on air.
 *
 * Robolectric in NATIVE graphics mode runs the platform's real Skia, so the pixels counted here
 * are the pixels the phone would draw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PackRasterizerTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun packFiles(): List<File> =
        File("src/main/assets/packs").listFiles { f -> f.extension == "json" }
            ?.sortedBy { it.name }.orEmpty()

    @Test
    fun `every pack rasterizes to a visible graphic`() {
        val context = RuntimeEnvironment.getApplication()
        packFiles().forEach { file ->
            val pack: GraphicsPack = json.decodeFromString(file.readText())
            val rendered = PackRasterizer.render(
                context = context,
                pack = pack,
                values = pack.defaultValues(),
                theme = PackTheme.byKey(null),
                targetWidthPx = 900,
            )
            assertNotNull(
                "${pack.id} drew nothing: ${PackRasterizer.lastFailure}",
                rendered,
            )

            val bitmap: Bitmap = rendered!!.bitmap
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val visible = pixels.count { (it ushr 24) > 8 }
            // A tenth of the canvas is well below any real design and well above the "one
            // stray anti-aliased pixel" that a broken pack could still produce.
            assertTrue(
                "${pack.id} rasterized almost nothing: $visible of ${pixels.size} pixels",
                visible > pixels.size / 10,
            )
        }
    }

    @Test
    fun `a pack with no elements reports why instead of drawing nothing`() {
        val context = RuntimeEnvironment.getApplication()
        val empty = GraphicsPack(
            id = "empty",
            name = "Empty",
            category = PackCategory.GENERAL,
            canvas = PackCanvas(400, 100),
        )
        val rendered = PackRasterizer.render(
            context = context,
            pack = empty,
            values = emptyMap(),
            theme = PackTheme.byKey(null),
            targetWidthPx = 900,
        )
        assertTrue("A pack with nothing to draw must not return a texture", rendered == null)
        assertTrue(
            "The failure must be reportable to the user, not silent",
            PackRasterizer.lastFailure?.contains("empty") == true,
        )
    }
}
