// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.max

/**
 * Draws a page background once into a small picture and then paints only that picture each frame.
 *
 * The Stage page is a dark wash with two or three soft lights, each its own pass over most of the
 * screen. On Kevin's TCL (MediaTek MT5896, 1080p) the GPU took 22-33 ms a frame on the Live TV grid,
 * and the overdraw view showed every pixel painted five times or more before any channel row. One
 * opaque picture is one pass, and copying it needs no blending with what is under it.
 *
 * The picture is a quarter of the screen size: the wash and the lights have no edges, so stretching
 * them back up costs nothing visible. [key] must change whenever [draw] would draw something else
 * (accent, theme, light or dark). [draw] runs at full size, as it would have on screen, and must cover
 * every pixel with opaque colour.
 */
fun Modifier.bakedBackground(key: Any, draw: DrawScope.() -> Unit): Modifier = drawWithCache {
    val w = max(1, ceil(size.width / SCALE).toInt())
    val h = max(1, ceil(size.height / SCALE).toInt())
    val picture = BakedPictures.get(key, IntSize(w, h)) {
        ImageBitmap(w, h, hasAlpha = false).also { image ->
            CanvasDrawScope().draw(this, layoutDirection, Canvas(image), Size(w.toFloat(), h.toFloat())) {
                scale(1f / SCALE, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                    // The scope's size is the full element again inside the scale, so [draw] lays out
                    // its lights against the real screen.
                    drawFullSize(this@drawWithCache.size, draw)
                }
            }
        }
    }
    val dst = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt())
    onDrawBehind {
        drawImage(picture, dstSize = dst, blendMode = BlendMode.Src, filterQuality = FilterQuality.Low)
    }
}

private const val SCALE = 4f

/** Runs [draw] with a DrawScope whose size is [full], however the canvas under it is scaled. */
private fun DrawScope.drawFullSize(full: Size, draw: DrawScope.() -> Unit) {
    val outer = this
    CanvasDrawScope().draw(outer, outer.layoutDirection, outer.drawContext.canvas, full) { draw() }
}

/** The last few baked pictures, so going back to a page does not draw it again. */
private object BakedPictures {
    private const val MAX = 4
    private data class Entry(val key: Any, val size: IntSize)
    private val cache = LinkedHashMap<Entry, ImageBitmap>()

    @Synchronized
    fun get(key: Any, size: IntSize, make: () -> ImageBitmap): ImageBitmap {
        val entry = Entry(key, size)
        cache.remove(entry)?.let { cache[entry] = it; return it }
        if (cache.size >= MAX) cache.remove(cache.keys.first())
        return make().also { cache[entry] = it }
    }
}
