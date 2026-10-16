// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.kevbox.live.CategoryJumps.Block

class CategoryJumpsTest {

    // Kevin's playlist in miniature: 4 fixed rows, then categories in provider order, a prefix split
    // across two stretches (US|), unprefixed "24/7" ones and two loners.
    private val labels = listOf(
        "Favorites", "Recently watched", "Catch-up", "All channels",
        "FR| FRANCE 4K", "FR| FRANCE HD",
        "US| ENTERTAINMENT",
        "4K UHD 3840P",
        "US| SOCCER PPV", "US| NBA PACKAGE",
        "24/7 DISNEY+", "24/7 CRIME VIP",
        "WORLD LIVE SPORTS",
        "AFR| AZAM NETWORK",
        "UK | GENERAL",
    )
    private val jumps = CategoryJumps.of(labels, firstCategoryRow = 4)

    @Test
    fun prefixesAreTheLettersBeforeTheBar() {
        assertEquals("FR|", CategoryJumps.prefixOf("FR| FRANCE 4K"))
        assertEquals("AFR|", CategoryJumps.prefixOf("AFR| AZAM NETWORK"))
        assertEquals("UK|", CategoryJumps.prefixOf("UK | GENERAL"))
        assertNull(CategoryJumps.prefixOf("24/7 DISNEY+"))
        assertNull(CategoryJumps.prefixOf("Favorites"))
        assertNull(CategoryJumps.prefixOf("A| TOO SHORT"))
        assertNull(CategoryJumps.prefixOf("fr| LOWER CASE"))
        assertNull(CategoryJumps.prefixOf("PRIME VIDEO | MOVIES"))
    }

    @Test
    fun groupsAreAlphabeticalWithTheFixedRowsFirstAndOtherLast() {
        assertEquals(
            listOf(Block.Top, Block.Named("24/7"), Block.Named("AFR|"), Block.Named("FR|"), Block.Named("UK|"), Block.Named("US|"), Block.Named("US|"), Block.Other),
            jumps.groups.map { it.block },
        )
    }

    @Test
    fun aSplitPrefixHasOneLinePerStretchLargestFirst() {
        val us = jumps.groups.filter { it.block == Block.Named("US|") }
        assertEquals(listOf(8 to 2, 6 to 1), us.map { it.firstRow to it.count })
    }

    @Test
    fun stretchesOfTheSameSizeKeepListOrder() {
        val j = CategoryJumps.of(listOf("UK| A", "FR| B", "UK| C", "FR| D", "UK| E"), firstCategoryRow = 0)
        assertEquals(listOf(0, 2, 4), j.groups.filter { it.block == Block.Named("UK|") }.map { it.firstRow })
    }

    @Test
    fun sharedFirstWordsGroupAndLonersGoUnderOther() {
        assertEquals(2, jumps.groups.single { it.block == Block.Named("24/7") }.count)
        val other = jumps.groups.single { it.block == Block.Other }
        assertEquals(7, other.firstRow)
        assertEquals(2, other.count)
    }

    @Test
    fun theTopLineJumpsToTheFirstRow() {
        val top = jumps.groups.first()
        assertEquals(Block.Top, top.block)
        assertEquals(0, top.firstRow)
    }

    @Test
    fun theCursorStartsOnTheBlockOfTheRowItCameFrom() {
        assertEquals(0, jumps.groupOf(2)) // Catch-up: the fixed rows
        assertEquals(3, jumps.groupOf(5)) // FR|
        assertEquals(5, jumps.groupOf(9)) // US| NBA PACKAGE: the bigger US| stretch
        assertEquals(6, jumps.groupOf(6)) // US| ENTERTAINMENT: the smaller one
        assertEquals(7, jumps.groupOf(12)) // WORLD LIVE SPORTS: Other
        assertEquals(4, jumps.groupOf(14)) // UK|
    }

    @Test
    fun aListWithNoFixedRowsHasNoTopLine() {
        val j = CategoryJumps.of(listOf("FR| A", "US| B"), firstCategoryRow = 0)
        assertEquals(listOf(Block.Named("FR|"), Block.Named("US|")), j.groups.map { it.block })
    }

    @Test
    fun aListOfOnlyFixedRowsIsOneLine() {
        val j = CategoryJumps.of(listOf("Favorites", "All channels"), firstCategoryRow = -1)
        assertEquals(listOf(Block.Top), j.groups.map { it.block })
    }

    // CH+ / CH− in the guide's column (Kevin, 2026-10-16): stretches in list order, as on screen.
    // Stretch starts in [labels]: 0 fixed, 4 FR|, 6 US|, 7 Other, 8 US|, 10 24/7, 12 Other, 13 AFR|, 14 UK|.

    @Test
    fun chMinusGoesToTheStartOfTheNextStretchDownTheList() {
        assertEquals(4, jumps.groupStep(0, +1))
        assertEquals(4, jumps.groupStep(3, +1))
        assertEquals(6, jumps.groupStep(5, +1))
        assertEquals(7, jumps.groupStep(6, +1)) // a one-row US| stretch, then a loner
        assertEquals(10, jumps.groupStep(9, +1))
        assertEquals(14, jumps.groupStep(13, +1))
    }

    @Test
    fun chPlusGoesToTheStartOfThisStretchThenThePreviousOne() {
        assertEquals(8, jumps.groupStep(9, -1)) // mid-stretch: its own start
        assertEquals(7, jumps.groupStep(8, -1)) // at a start: the stretch above
        assertEquals(4, jumps.groupStep(5, -1))
        assertEquals(0, jumps.groupStep(4, -1))
        assertEquals(0, jumps.groupStep(2, -1))
    }

    @Test
    fun theStepsWrapAtTheEnds() {
        assertEquals(0, jumps.groupStep(14, +1))
        assertEquals(14, jumps.groupStep(0, -1))
    }

    @Test
    fun fromTheSearchFieldTheStepsGoToTheFirstOrLastStretch() {
        assertEquals(0, jumps.groupStep(-1, +1))
        assertEquals(14, jumps.groupStep(-1, -1))
    }

    @Test
    fun anEmptyListHasNowhereToStep() {
        assertNull(CategoryJumps.of(emptyList(), firstCategoryRow = -1).groupStep(-1, +1))
    }
}
