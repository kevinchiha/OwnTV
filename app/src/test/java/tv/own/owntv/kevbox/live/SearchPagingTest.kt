// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchPagingTest {

    // ON NOW heading, 2 shows, NEXT 24 HOURS heading, 3 shows, LATER heading, 1 show.
    private val rows = listOf(false, true, true, false, true, true, true, false, true)

    @Test
    fun aResultRowIsKept() {
        assertEquals(4, searchPageTarget(rows, 4))
        assertEquals(8, searchPageTarget(rows, 8))
    }

    @Test
    fun aHeadingGivesTheResultUnderIt() {
        assertEquals(1, searchPageTarget(rows, 0))
        assertEquals(4, searchPageTarget(rows, 3))
        assertEquals(8, searchPageTarget(rows, 7))
    }

    @Test
    fun aHeadingWithNothingUnderItGivesTheLastResultAboveIt() {
        assertEquals(2, searchPageTarget(listOf(false, true, true, false), 3))
    }

    @Test
    fun noResultsGivesNowhere() {
        assertNull(searchPageTarget(listOf(false, false), 1))
        assertNull(searchPageTarget(emptyList(), 0))
    }
}
