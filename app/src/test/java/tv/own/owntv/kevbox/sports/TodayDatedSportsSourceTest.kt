// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.sports

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.sports.MonthBoard
import tv.own.owntv.core.sports.SportsSource
import tv.own.owntv.core.sports.model.League
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.Sport

class TodayDatedSportsSourceTest {

    /** Answers a dated scoreboard from [byDay] and a dateless one with [espnDefault]; records each day asked. */
    private class FakeSource(
        val byDay: Map<LocalDate, List<Match>>,
        val espnDefault: List<Match>,
    ) : SportsSource {
        val asked = mutableListOf<LocalDate?>()
        override suspend fun scoreboard(league: League, day: LocalDate?): List<Match> {
            asked += day
            return if (day == null) espnDefault else byDay[day].orEmpty()
        }
        override suspend fun summary(match: Match) = throw UnsupportedOperationException()
        override suspend fun standings(league: League) = throw UnsupportedOperationException()
        override suspend fun teams(league: League) = throw UnsupportedOperationException()
        override suspend fun month(league: League, month: YearMonth): MonthBoard = throw UnsupportedOperationException()
        override suspend fun rankings(league: League) = throw UnsupportedOperationException()
        override suspend fun teamSchedule(league: League, teamId: String, fixtures: Boolean) = throw UnsupportedOperationException()
    }

    private val tonight = listOf(match("milan-bologna", "2026-10-28T17:30:00Z"), match("genoa-juve", "2026-10-28T19:45:00Z"))
    private val lastNight = listOf(match("roma-cagliari", "2026-10-27T19:45:00Z", MatchState.POST))
    private val wed = LocalDate.parse("2026-10-28")

    @Test
    fun `before noon in New York, today is asked by date and last night's games are added`() = runBlocking {
        // 06:25 UTC = 02:25 in New York on the 28th, when ESPN's dateless answer still held the 27th.
        val fake = FakeSource(mapOf(wed to tonight), espnDefault = lastNight + tonight.first())
        val source = TodayDatedSportsSource(fake) { Instant.parse("2026-10-28T06:25:00Z").toEpochMilli() }

        val today = source.scoreboard(SERIE_A)

        assertEquals(listOf<LocalDate?>(wed, null), fake.asked)
        assertEquals(listOf("milan-bologna", "genoa-juve", "roma-cagliari"), today.map { it.id })
    }

    @Test
    fun `from noon in New York, today is one dated call`() = runBlocking {
        val fake = FakeSource(mapOf(wed to tonight), espnDefault = lastNight)
        val source = TodayDatedSportsSource(fake) { Instant.parse("2026-10-28T16:00:00Z").toEpochMilli() }

        assertEquals(tonight, source.scoreboard(SERIE_A))
        assertEquals(listOf<LocalDate?>(wed), fake.asked)
    }

    @Test
    fun `a dated call passes through unchanged`() = runBlocking {
        val thu = LocalDate.parse("2026-10-29")
        val fake = FakeSource(mapOf(thu to tonight), espnDefault = lastNight)
        val source = TodayDatedSportsSource(fake) { Instant.parse("2026-10-28T06:25:00Z").toEpochMilli() }

        assertEquals(tonight, source.scoreboard(SERIE_A, thu))
        assertEquals(listOf<LocalDate?>(thu), fake.asked)
    }

    private fun match(id: String, start: String, state: MatchState = MatchState.PRE) = Match(
        id = id, sport = Sport.SOCCER, feed = "soccer/ita.1", leagueSlug = "ita.1", leagueName = "Serie A",
        startMs = Instant.parse(start).toEpochMilli(), state = state, statusText = "", title = id,
    )

    private companion object {
        val SERIE_A = League(Sport.SOCCER, "ita.1", "Serie A")
    }
}
