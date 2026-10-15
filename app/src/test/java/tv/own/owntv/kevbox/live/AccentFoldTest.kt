// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccentFoldTest {

    @Test
    fun foldDropsAccentsAndCase() {
        assertEquals("telematin", foldAccents("Télématin"))
        assertEquals("ca va", foldAccents("ÇA VA"))
        assertEquals("noel", foldAccents("Noël"))
    }

    @Test
    fun titlesMatchWithOrWithoutAccents() {
        assertTrue(titleMatches("Live: Télématin", "telematin"))
        assertTrue(titleMatches("Live: Telematin", "télématin"))
        assertTrue(titleMatches("ICI MATIN", "matin"))
        assertFalse(titleMatches("Slam", "matin"))
    }

    @Test
    fun globLetsEveryLetterMatchItsAccentedFormsInAnyCase() {
        val e = accentGlob("e")
        assertTrue(e.startsWith("*[") && e.endsWith("]*"))
        for (c in listOf('e', 'E', 'é', 'É', 'è', 'ê', 'ë')) assertTrue("$c in $e", c in e)
        assertTrue(accentGlob("é") == e)
    }

    @Test
    fun globEscapesItsOwnSpecialCharacters() {
        assertEquals("*[*][?][[]*", accentGlob("*?["))
        assertEquals("*1 2*", accentGlob("1 2"))
    }
}
