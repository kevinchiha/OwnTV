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
    fun everyWordMustAppearButNotAsOnePhrase() {
        assertTrue(titleMatches("Manchester City v Arsenal", "man city"))
        assertTrue(titleMatches("Manchester City v Arsenal", "city man"))
        assertTrue(titleMatches("Manchester City v Arsenal", "  man   city "))
        assertTrue(titleMatches("Man City TV", "man city"))
        assertFalse(titleMatches("Manchester United v Arsenal", "man city"))
        assertTrue(titleMatches("Télématin", "tele matin"))
    }

    @Test
    fun anEmptyQueryMatchesEverything() {
        assertTrue(titleMatches("Anything", ""))
        assertTrue(titleMatches("Anything", "   "))
    }

    @Test
    fun theQueryIsSplitAtSpacesOnly() {
        assertEquals(listOf("man", "city"), queryWords("  man   city "))
        assertEquals(listOf("canal+", "sport"), queryWords("canal+ sport"))
        assertEquals(emptyList<String>(), queryWords("  "))
    }

    @Test
    fun oneGlobPerWord() {
        assertEquals(listOf(accentGlob("man"), accentGlob("city")), accentGlobs("man city"))
    }

    @Test
    fun theLongestWordNarrowsTheChannelRead() {
        assertEquals("city", longestWord("man city"))
        assertEquals("sports", longestWord("sky sports 1"))
        assertEquals("", longestWord("  "))
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
