// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLogoPlateTest {

    private val clear = 0x00000000
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    /** A 100 x 100 transparent logo with a [ink] block in the middle (France 2's black "2"). */
    private fun inkOnClear(ink: Int): Int? =
        HomeLogoPlate.ink(100, 100) { x, y -> if (x in 30..69 && y in 20..79) ink else clear }

    @Test
    fun darkInkGetsTheLightPlate() {
        val ink = inkOnClear(black)
        assertEquals(black, ink)
        assertEquals(HomeLogoPlate.LIGHT_PLATE, HomeLogoPlate.plateFor(ink))
        // A dark navy ink is as unreadable on the dark plate.
        assertEquals(HomeLogoPlate.LIGHT_PLATE, HomeLogoPlate.plateFor(inkOnClear(0xFF1A2A4A.toInt())))
    }

    @Test
    fun lightInkKeepsTheDarkPlate() {
        assertEquals(HomeLogoPlate.DARK_PLATE, HomeLogoPlate.plateFor(inkOnClear(white)))
        assertEquals(HomeLogoPlate.DARK_PLATE, HomeLogoPlate.plateFor(inkOnClear(0xFFFFCC00.toInt())))
    }

    @Test
    fun opaqueLogoKeepsTheDarkPlate() {
        // An opaque logo brings its own background, whatever its colours.
        val ink = HomeLogoPlate.ink(100, 100) { x, _ -> if (x < 50) black else 0xFF202020.toInt() }
        assertNull(ink)
        assertEquals(HomeLogoPlate.DARK_PLATE, HomeLogoPlate.plateFor(ink))
    }

    @Test
    fun fullyClearLogoHasNothingToMeasure() {
        assertNull(HomeLogoPlate.ink(10, 10) { _, _ -> clear })
        assertNull(HomeLogoPlate.ink(0, 0) { _, _ -> black })
    }

    @Test
    fun inkIsWeightedByAlpha() {
        // Half-transparent white over a clear surround is still white ink, not grey.
        val ink = HomeLogoPlate.ink(10, 10) { x, _ -> if (x < 5) 0x80FFFFFF.toInt() else clear }
        assertEquals(white, ink)
    }

    @Test
    fun contrastMathMatchesWcag() {
        assertEquals(21f, HomeLogoPlate.contrastRatio(HomeLogoPlate.luminance(black), HomeLogoPlate.luminance(white)), 0.01f)
        assertEquals(1f, HomeLogoPlate.contrastRatio(0.3f, 0.3f), 0.0001f)
        assertTrue(HomeLogoPlate.luminance(HomeLogoPlate.DARK_PLATE) < 0.02f)
        assertTrue(HomeLogoPlate.luminance(HomeLogoPlate.LIGHT_PLATE) > 0.8f)
    }

    @Test
    fun logoIsFittedWholeAndCentred() {
        // A square logo on a 16:9 card: limited by the height box, centred, never past the card.
        val square = HomeLogoPlate.fit(500, 500, 1280, 720, 0.72f, 0.6f)
        assertEquals(LogoBounds(424, 144, 856, 576), square)
        // A wide logo: limited by the width box.
        val wide = HomeLogoPlate.fit(1000, 100, 1280, 720, 0.72f, 0.6f)
        assertEquals(922, wide.right - wide.left)
        assertEquals(92, wide.bottom - wide.top)
        assertEquals(1280 - wide.right, wide.left)
        for (b in listOf(square, wide)) {
            assertFalse(b.left < 0 || b.top < 0 || b.right > 1280 || b.bottom > 720)
        }
    }

    @Test
    fun providerServesOnlyHashNamedPngs() {
        val hash = "0123456789abcdef".repeat(4)
        assertTrue(HomeLogoArtProvider.isArtName("$hash.png"))
        // Right length and suffix, but a "/" or ".." inside: refused.
        assertFalse(HomeLogoArtProvider.isArtName("a/" + hash.drop(2) + ".png"))
        assertFalse(HomeLogoArtProvider.isArtName("../" + hash.drop(3) + ".png"))
        assertFalse(HomeLogoArtProvider.isArtName(".." + hash.drop(2) + ".png"))
        assertFalse(HomeLogoArtProvider.isArtName("logo.png"))
        assertFalse(HomeLogoArtProvider.isArtName(hash.uppercase() + ".png"))
        assertFalse(HomeLogoArtProvider.isArtName("$hash.jpg"))
        assertFalse(HomeLogoArtProvider.isArtName("$hash.png.tmp"))
        assertFalse(HomeLogoArtProvider.isArtName(""))
    }
}
