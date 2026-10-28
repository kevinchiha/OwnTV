// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.sports

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import tv.own.owntv.core.sports.SportsSource
import tv.own.owntv.core.sports.model.League
import tv.own.owntv.core.sports.model.Match

/**
 * Core's sports source with "today" asked by date. Core fetches today's scoreboard with no date and
 * tomorrow's as the US Eastern date plus one. ESPN's dateless answer moves to the new day hours after
 * midnight in New York (on 28 Oct 2026 at 02:25 Eastern it still answered the 27th), so until then
 * the day in between was fetched by neither call: on a Lebanese morning that night's games were
 * missing. Here a call without a date asks for the US Eastern date, the one tomorrow is counted from.
 * Before [CATCH_UP_UNTIL_HOUR] Eastern it also adds ESPN's dateless answer, the games of a night that
 * ran past midnight there, so one still live keeps its score; after that hour it is one call, as before.
 */
class TodayDatedSportsSource(
    private val inner: SportsSource,
    private val now: () -> Long = System::currentTimeMillis,
) : SportsSource by inner {

    override suspend fun scoreboard(league: League, day: LocalDate?): List<Match> {
        if (day != null) return inner.scoreboard(league, day)
        val eastern = Instant.ofEpochMilli(now()).atZone(ESPN_ZONE)
        val dated = inner.scoreboard(league, eastern.toLocalDate())
        if (eastern.hour >= CATCH_UP_UNTIL_HOUR) return dated
        val espnDay = try {
            inner.scoreboard(league, null)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        val keys = dated.mapTo(HashSet()) { it.key }
        return dated + espnDay.filter { it.key !in keys }
    }

    companion object {
        /** ESPN's calendar day, the one Core counts tomorrow from. */
        val ESPN_ZONE: ZoneId = ZoneId.of("America/New_York")

        /** Until this Eastern hour ESPN's dateless scoreboard may still hold the night before. */
        const val CATCH_UP_UNTIL_HOUR = 12
    }
}
