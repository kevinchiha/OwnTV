// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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

        // Tennis and soccer as listed, then the rest in Sport's own order.
        val unlisted = Sport.entries.filter { it in setOf(Sport.GOLF, Sport.BASKETBALL, Sport.HOCKEY) }
        assertEquals(listOf(Sport.TENNIS, Sport.SOCCER) + unlisted, rows.map { it.first })
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
    fun `soon rows are the column's last items`() {
        // head, chips, live, then three soon rows: 6 items, the soon rows at 3, 4 and 5.
        assertEquals(3, soonRowItem(totalItems = 6, rows = 3, row = 0))
        assertEquals(5, soonRowItem(totalItems = 6, rows = 3, row = 2))
    }

    private fun match(id: String, sport: Sport) = Match(
        id = id, sport = sport, feed = "${sport.name.lowercase()}/test", leagueSlug = "test", leagueName = "Test",
        startMs = 1_800_000_000_000L, state = MatchState.PRE, statusText = "", title = id,
    )
}
