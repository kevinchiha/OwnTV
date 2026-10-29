// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.home

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Where a logo goes on its card, in canvas pixels. */
data class LogoBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * The plate rule for home screen logo cards, as pure maths on ARGB ints so unit tests can run it.
 *
 * Copied from the app's `ui/components/ChannelLogoTile.kt` (an upstream file, so it is not edited
 * to share this): measure the logo's alpha-weighted ink colour, and when its contrast with the dark
 * surface is under 3:1, put it on the light plate. Opaque logos bring their own background and are
 * left alone. On the launcher the surface is the card's own dark plate.
 */
object HomeLogoPlate {
    /** ChannelLogoTile's LightPlate: slightly off-white, so it does not glare in a dark room. */
    const val LIGHT_PLATE = 0xFFEDF1F5.toInt()

    /** ChannelLogoTile's DarkPlate, the card's background unless the logo needs the light one. */
    const val DARK_PLATE = 0xFF161C24.toInt()

    /** Below this contrast ratio between the logo's ink and the dark plate, the logo gets the light plate. */
    private const val MIN_CONTRAST = 3f

    /** ChannelLogoTile caps the work at a 48 x 48 grid however big the source is. */
    private const val GRID = 48

    /**
     * The logo's mean colour, weighted by alpha so the transparent surround does not drag every
     * logo towards black; null for artwork that is essentially opaque or has nothing to measure.
     * [pixel] reads one ARGB pixel. Same sampling as ChannelLogoTile's measureInk.
     */
    fun ink(width: Int, height: Int, pixel: (x: Int, y: Int) -> Int): Int? {
        val stepX = max(1, width / GRID)
        val stepY = max(1, height / GRID)
        var samples = 0
        var opaque = 0
        var alphaSum = 0.0
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val p = pixel(x, y)
                val alpha = (p ushr 24) and 0xFF
                samples++
                if (alpha > 240) opaque++
                if (alpha > 0) {
                    alphaSum += alpha.toDouble()
                    r += ((p shr 16) and 0xFF).toDouble() * alpha
                    g += ((p shr 8) and 0xFF).toDouble() * alpha
                    b += (p and 0xFF).toDouble() * alpha
                }
                x += stepX
            }
            y += stepY
        }
        if (samples == 0 || alphaSum == 0.0 || opaque > samples * 0.9) return null
        return argb(channel(r / alphaSum), channel(g / alphaSum), channel(b / alphaSum))
    }

    /** The card's background for a logo of ink [ink] (null: opaque or nothing to measure). */
    fun plateFor(ink: Int?): Int =
        if (ink != null && contrastRatio(luminance(ink), luminance(DARK_PLATE)) < MIN_CONTRAST) LIGHT_PLATE else DARK_PLATE

    /**
     * Relative luminance of an ARGB colour (alpha ignored), as Compose's Color.luminance() works it
     * out for sRGB, which is what ChannelLogoTile calls.
     */
    fun luminance(color: Int): Float {
        fun linear(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        val l = 0.2126 * linear((color shr 16) and 0xFF) + 0.7152 * linear((color shr 8) and 0xFF) + 0.0722 * linear(color and 0xFF)
        return l.toFloat().coerceIn(0f, 1f)
    }

    /** WCAG contrast ratio between two relative luminances, 1.0 (identical) to 21.0 (black on white). */
    fun contrastRatio(a: Float, b: Float): Float {
        val lighter = max(a, b)
        val darker = min(a, b)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    /**
     * The logo fitted whole inside a box of [boxFractionX] by [boxFractionY] of the canvas, centred:
     * never cropped, and the padding round it survives a launcher that crops the card a little.
     */
    fun fit(
        sourceWidth: Int,
        sourceHeight: Int,
        canvasWidth: Int,
        canvasHeight: Int,
        boxFractionX: Float,
        boxFractionY: Float,
    ): LogoBounds {
        require(sourceWidth > 0 && sourceHeight > 0)
        val scale = min(canvasWidth * boxFractionX / sourceWidth, canvasHeight * boxFractionY / sourceHeight)
        val width = (sourceWidth * scale).roundToInt().coerceIn(1, canvasWidth)
        val height = (sourceHeight * scale).roundToInt().coerceIn(1, canvasHeight)
        val left = (canvasWidth - width) / 2
        val top = (canvasHeight - height) / 2
        return LogoBounds(left, top, left + width, top + height)
    }

    private fun channel(v: Double): Int = v.toInt().coerceIn(0, 255)

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
