// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.Sport

class SoonBySportTest {

    @Test
    fun `rows follow the given sport order, other sports after it in enum order`() {
        val soon = listOf(
            match("golf-1", Sport.GOLF),
            match("nba-1", Sport.BASKETBALL),
            match("liga-1", Sport.SOCCER),
            match("atp-1", Sport.TENNIS),
            match("nhl-1", Sport.HOCKEY),
        )

        val rows = soonBySport(soon, order = listOf(Sport.TENNIS, Sport.SOCCER))

        // Tennis and soccer as listed, then the rest in Sport's own order: basketball, hockey, golf.
        assertEquals(
            listOf(Sport.TENNIS, Sport.SOCCER, Sport.BASKETBALL, Sport.HOCKEY, Sport.GOLF),
            rows.map { it.first },
        )
    }

    @Test
    fun `a sport keeps at most perSport matches, in the order given`() {
        val soon = listOf(
            match("s1", Sport.SOCCER), match("b1", Sport.BASKETBALL), match("s2", Sport.SOCCER),
            match("s3", Sport.SOCCER), match("b2", Sport.BASKETBALL), match("s4", Sport.SOCCER),
        )

        val rows = soonBySport(soon, order = listOf(Sport.SOCCER, Sport.BASKETBALL), perSport = 3).toMap()

        assertEquals(listOf("s1", "s2", "s3"), rows.getValue(Sport.SOCCER).map { it.id })
        assertEquals(listOf("b1", "b2"), rows.getValue(Sport.BASKETBALL).map { it.id })
    }

    @Test
    fun `twelve per sport by default, so one busy sport does not crowd out the others`() {
        val soon = (1..20).map { match("s$it", Sport.SOCCER) } + match("t1", Sport.TENNIS)

        val rows = soonBySport(soon, order = listOf(Sport.SOCCER, Sport.TENNIS)).toMap()

        assertEquals(12, rows.getValue(Sport.SOCCER).size)
        assertEquals(listOf("t1"), rows.getValue(Sport.TENNIS).map { it.id })
    }

    @Test
    fun `sports with no match get no row`() {
        val rows = soonBySport(listOf(match("nba-1", Sport.BASKETBALL)), order = listOf(Sport.SOCCER, Sport.BASKETBALL, Sport.TENNIS))

        assertEquals(listOf(Sport.BASKETBALL), rows.map { it.first })
        assertTrue(soonBySport(emptyList(), order = listOf(Sport.SOCCER)).isEmpty())
    }

    @Test
    fun `a sport listed twice gets one row`() {
        val rows = soonBySport(listOf(match("s1", Sport.SOCCER)), order = listOf(Sport.SOCCER, Sport.SOCCER))

        assertEquals(listOf(Sport.SOCCER), rows.map { it.first })
    }

    @Test
    fun `soonCardAt finds the card's row and place in it`() {
        val rows = soonBySport(
            listOf(match("s1", Sport.SOCCER), match("t1", Sport.TENNIS), match("t2", Sport.TENNIS)),
            order = listOf(Sport.SOCCER, Sport.TENNIS),
        )
        val t2 = rows[1].second[1]

        assertEquals(1 to 1, soonCardAt(rows, t2.key))
        assertEquals(0 to 0, soonCardAt(rows, rows[0].second[0].key))
        assertNull(soonCardAt(rows, "not-a-card"))
    }

    @Test
    fun `a card's row is found by counting back past a none item, and the drawn keys confirm it`() {
        val rows = soonBySport(
            listOf(match("s1", Sport.SOCCER), match("t1", Sport.TENNIS), match("t2", Sport.TENNIS)),
            order = listOf(Sport.SOCCER, Sport.TENNIS),
        )
        // The column: head, chips, the nothing-followed note, then the two soon rows.
        val column = listOf("head", "chips", "none", soonRowKey(Sport.SOCCER), soonRowKey(Sport.TENNIS))
        val (row, at) = soonCardAt(rows, rows[1].second[1].key)!!
        val counted = soonRowItem(totalItems = column.size, rows = rows.size, row = row)

        assertEquals(1 to 1, row to at)
        assertEquals(soonRowKey(Sport.TENNIS), column[counted])
        // Only head, chips and none drawn: scroll to the counted item.
        val top = column.take(3).mapIndexed { i, k -> i to k }
        assertEquals(RowScroll.SCROLL, soonRowScroll(top, soonRowKey(Sport.TENNIS), counted))
        // After the scroll the tennis row is drawn: the column stays.
        val bottom = column.mapIndexed { i, k -> i to k }.drop(3)
        assertEquals(RowScroll.SHOWN, soonRowScroll(bottom, soonRowKey(Sport.TENNIS), counted))
    }

    @Test
    fun `a count that lands on another item stops the reveal`() {
        // An item after the soon rows: counting back from the end lands one item short.
        val column = listOf("head", "chips", soonRowKey(Sport.SOCCER), soonRowKey(Sport.TENNIS), "footer")
        val counted = soonRowItem(totalItems = column.size, rows = 2, row = 1)
        assertEquals("footer", column[counted])

        // Before the scroll only head and chips are drawn: nothing contradicts the count, so scroll.
        assertEquals(RowScroll.SCROLL, soonRowScroll(listOf(0 to "head", 1 to "chips"), soonRowKey(Sport.TENNIS), counted))
        // After it the counted item is drawn and it is the footer: stop.
        assertEquals(RowScroll.WRONG, soonRowScroll(listOf(4 to "footer"), soonRowKey(Sport.TENNIS), counted))
        // A count below zero never scrolls.
        assertEquals(RowScroll.WRONG, soonRowScroll(emptyList(), soonRowKey(Sport.TENNIS), counted = -1))
    }

    @Test
    fun `onFilter keeps the rows on the first call and on the same value, resets them on a change`() {
        val rows = SoonRows()
        val soccer = rows.of(Sport.SOCCER)
        soccer.requestScrollToItem(3)

        rows.onFilter("all" to "none")
        assertEquals(3, soccer.firstVisibleItemIndex)

        rows.onFilter("all" to "none")
        assertEquals(3, soccer.firstVisibleItemIndex)

        rows.onFilter("tennis" to "none")
        assertEquals(0, soccer.firstVisibleItemIndex)
    }

    @Test
    fun `a focused row asks for itself plus the key hints' room under it`() {
        assertEquals(Rect(0f, 0f, 1600f, 310f), wholeRowRect(IntSize(1600, 200), below = 110f))
    }

    private fun match(id: String, sport: Sport) = Match(
        id = id, sport = sport, feed = "${sport.name.lowercase()}/test", leagueSlug = "test", leagueName = "Test",
        startMs = 1_800_000_000_000L, state = MatchState.PRE, statusText = "", title = id,
    )
}
