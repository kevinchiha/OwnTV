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
}
