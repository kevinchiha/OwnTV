// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryStepsTest {

    // The list: item 0 is the search field; with the heading before row 3, rows 0-2 are items 1-3,
    // the heading is item 4 and rows 3.. are items 5..

    @Test
    fun rowsBeforeTheHeadingFollowTheSearchField() {
        assertEquals(1, CategorySteps.itemIndex(0, headingPos = 3))
        assertEquals(3, CategorySteps.itemIndex(2, headingPos = 3))
    }

    @Test
    fun rowsFromTheHeadingOnSitOneFurther() {
        assertEquals(5, CategorySteps.itemIndex(3, headingPos = 3))
        assertEquals(10, CategorySteps.itemIndex(8, headingPos = 3))
    }

    @Test
    fun withNoHeadingEveryRowFollowsTheSearchField() {
        assertEquals(1, CategorySteps.itemIndex(0, headingPos = -1))
        assertEquals(41, CategorySteps.itemIndex(40, headingPos = -1))
    }

    @Test
    fun rowPosUndoesItemIndex() {
        for (heading in listOf(-1, 0, 3, 7)) {
            for (pos in 0..20) assertEquals(pos, CategorySteps.rowPos(CategorySteps.itemIndex(pos, heading), heading))
        }
    }

    @Test
    fun downStepsOneRow() = assertEquals(5, CategorySteps.next(from = 4, delta = 1, rows = 150))

    @Test
    fun upStepsOneRow() = assertEquals(3, CategorySteps.next(from = 4, delta = -1, rows = 150))

    @Test
    fun downOnTheLastRowStaysThere() = assertEquals(149, CategorySteps.next(from = 149, delta = 1, rows = 150))

    @Test
    fun upFromTheFirstRowIsLeftToTheSearchField() = assertNull(CategorySteps.next(from = 0, delta = -1, rows = 150))

    // --- Wrap (B): only a fresh press goes round; a held key stops at the end ----------------------

    @Test
    fun aFreshDownOnTheLastRowWrapsToTheFirst() =
        assertEquals(0, CategorySteps.next(from = 149, delta = 1, rows = 150, fresh = true))

    @Test
    fun aHeldDownStopsOnTheLastRow() =
        assertEquals(149, CategorySteps.next(from = 149, delta = 1, rows = 150, heldMs = 3_000, fresh = false))

    @Test
    fun aFreshUpOnTheFirstRowStillGoesToTheSearchField() =
        assertNull(CategorySteps.next(from = 0, delta = -1, rows = 150, fresh = true))

    @Test
    fun aFreshUpOnTheSearchFieldGoesToTheLastRow() = assertEquals(149, CategorySteps.fromSearch(rows = 150, fresh = true))

    @Test
    fun aHeldUpStopsOnTheSearchField() = assertNull(CategorySteps.fromSearch(rows = 150, fresh = false))

    @Test
    fun anEmptyListHasNoLastRow() = assertNull(CategorySteps.fromSearch(rows = 0, fresh = true))

    // --- Speed-up (C): rows per press grow with how long the key has been held -------------------

    @Test
    fun stepSizeGrowsWithTheHold() {
        assertEquals(1, CategorySteps.stepSize(heldMs = 0))
        assertEquals(1, CategorySteps.stepSize(heldMs = 999))
        assertEquals(3, CategorySteps.stepSize(heldMs = 1_000))
        assertEquals(3, CategorySteps.stepSize(heldMs = 1_999))
        assertEquals(6, CategorySteps.stepSize(heldMs = 2_000))
        assertEquals(6, CategorySteps.stepSize(heldMs = 60_000))
    }

    @Test
    fun aLongHoldMovesSixRows() = assertEquals(46, CategorySteps.next(from = 40, delta = 1, rows = 150, heldMs = 2_500))

    @Test
    fun aFastHoldStopsAtTheEndInsteadOfPassingIt() =
        assertEquals(149, CategorySteps.next(from = 147, delta = 1, rows = 150, heldMs = 2_500))

    @Test
    fun aFastHoldUpPastTheTopGoesToTheSearchField() =
        assertNull(CategorySteps.next(from = 2, delta = -1, rows = 150, heldMs = 2_500))
}
