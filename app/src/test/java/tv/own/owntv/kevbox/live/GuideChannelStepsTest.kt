// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuideChannelStepsTest {

    private fun page(from: Int, towardFirst: Boolean, count: Int = 100) =
        GuideChannelSteps.target(from, towardFirst, upSkip = 10, downSkip = 10, count = count)

    @Test
    fun chPlusFromRow25LandsOn15() = assertEquals(15, page(25, towardFirst = true))

    @Test
    fun chMinusFromRow25LandsOn35() = assertEquals(35, page(25, towardFirst = false))

    @Test
    fun theTwoSkipCountsApplyEachToItsOwnDirection() {
        assertEquals(22, GuideChannelSteps.target(25, towardFirst = true, upSkip = 3, downSkip = 40, count = 100))
        assertEquals(65, GuideChannelSteps.target(25, towardFirst = false, upSkip = 3, downSkip = 40, count = 100))
    }

    @Test
    fun stopsAtTheFirstAndLastRow() {
        assertEquals(0, page(3, towardFirst = true))
        assertEquals(99, page(95, towardFirst = false))
    }

    @Test
    fun noMoveAtAnEdgeOrInAnEmptyGrid() {
        assertNull(page(0, towardFirst = true))
        assertNull(page(99, towardFirst = false))
        assertNull(page(0, towardFirst = true, count = 0))
        assertNull(page(0, towardFirst = false, count = 0))
    }

    @Test
    fun aJumpGoesToTheFirstOrLastRow() {
        assertEquals(0, GuideChannelSteps.target(25, towardFirst = true, upSkip = 100, downSkip = 100, count = 100))
        assertEquals(99, GuideChannelSteps.target(25, towardFirst = false, upSkip = 100, downSkip = 100, count = 100))
    }
}
