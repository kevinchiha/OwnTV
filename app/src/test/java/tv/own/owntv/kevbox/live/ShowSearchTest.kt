// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.content.ProgrammeSearchResult
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity

class ShowSearchTest {

    private val now = 1_000_000_000_000L
    private val hour = 60 * 60_000L

    private fun channel(id: Long) =
        ChannelEntity(id = id, sourceId = 1, categoryId = 10, name = "Ch $id", streamUrl = "http://x/$id.ts", remoteId = "$id")

    private fun show(channelId: Long, title: String, startMs: Long, stopMs: Long) = ProgrammeSearchResult(
        EpgProgrammeEntity(sourceId = 1, epgChannelId = "ch$channelId", startMs = startMs, stopMs = stopMs, title = title),
        channel(channelId),
    )

    @Test
    fun onNowNext24HoursAndLaterAreSplitAndWhatIsOverDropped() {
        val spanningNow = show(1, "News", now - hour, now + hour)
        val inThreeHours = show(2, "News", now + 3 * hour, now + 4 * hour)
        val afterADay = show(3, "News", now + 25 * hour, now + 26 * hour) // KevBox 2026-10-16: kept, under Later
        val alreadyOver = show(4, "News", now - 2 * hour, now - hour)

        val split = splitShows(listOf(spanningNow, inThreeHours, afterADay, alreadyOver), now)

        assertEquals(listOf(spanningNow), split.onNow)
        assertEquals(listOf(inThreeHours), split.later)
        assertEquals(listOf(afterADay), split.beyond)
        assertEquals(3, split.size)
        assertEquals(setOf(1L, 2L, 3L), split.channelIds)
    }

    @Test
    fun aShowStartingExactly24HoursOnIsLaterNotNext24Hours() {
        val atTheEdge = show(1, "News", now + SHOW_WINDOW_MS, now + SHOW_WINDOW_MS + hour)
        val justBefore = show(2, "News", now + SHOW_WINDOW_MS - 1, now + SHOW_WINDOW_MS + hour)

        val split = splitShows(listOf(atTheEdge, justBefore), now)

        assertEquals(listOf(justBefore), split.later)
        assertEquals(listOf(atTheEdge), split.beyond)
    }

    @Test
    fun laterRunsByStartAndDropsARepeat() {
        val inThreeDays = show(1, "Match", now + 72 * hour, now + 73 * hour)
        val inTwoDays = show(2, "Match", now + 48 * hour, now + 49 * hour)
        val sameAgain = show(2, "Match", now + 48 * hour, now + 49 * hour)

        assertEquals(listOf(inTwoDays, inThreeDays), splitShows(listOf(inThreeDays, inTwoDays, sameAgain), now).beyond)
    }

    @Test
    fun laterIsOrderedByStartAndADuplicateIsDropped() {
        val inFiveHours = show(1, "Match", now + 5 * hour, now + 6 * hour)
        val inOneHour = show(2, "Match", now + hour, now + 2 * hour)
        val sameAgain = show(2, "Match", now + hour, now + 2 * hour)
        val inThreeHours = show(3, "Match", now + 3 * hour, now + 4 * hour)

        val split = splitShows(listOf(inFiveHours, inOneHour, sameAgain, inThreeHours), now)

        assertEquals(emptyList<ProgrammeSearchResult>(), split.onNow)
        assertEquals(listOf(inOneHour, inThreeHours, inFiveHours), split.later)
    }

    @Test
    fun onNowKeepsTheChannelOrderItCameIn() {
        val second = show(5, "Film", now - hour, now + hour)
        val first = show(2, "Film", now - 10 * 60_000L, now + 3 * hour)

        assertEquals(listOf(second, first), splitShows(listOf(second, first), now).onNow)
    }

    @Test
    fun aShowStartingRightNowIsOnNow() {
        val startsNow = show(1, "News", now, now + hour)

        assertEquals(listOf(startsNow), splitShows(listOf(startsNow), now).onNow)
    }

    @Test
    fun twoChannelsOnOneGuideIdKeepTheShowOnTheFirstOnlyWhenOnePerFeed() {
        val hd = show(1, "News", now - hour, now + hour)
        val sd = show(2, "News", now - hour, now + hour)
        val keyed = listOf("news.fr" to hd, "news.fr" to sd)

        assertEquals(listOf(hd), keepOnePerFeed(keyed, onePerFeed = true))
        assertEquals(listOf(hd, sd), keepOnePerFeed(keyed, onePerFeed = false))
    }

    @Test
    fun withoutOnePerFeedEachChannelGetsItsRowAndARepeatOnOneChannelIsStillDropped() {
        val hd = show(1, "News", now - hour, now + hour)
        val sd = show(2, "News", now - hour, now + hour)
        val sdAgain = show(2, "News", now - hour, now + hour)
        val hdLater = show(1, "News", now + 2 * hour, now + 3 * hour)
        val sdLater = show(2, "News", now + 2 * hour, now + 3 * hour)
        val keyed = listOf("news.fr" to hd, "news.fr" to sd, "news.fr" to sdAgain, "news.fr" to hdLater, "news.fr" to sdLater)

        val split = splitShows(keepOnePerFeed(keyed, onePerFeed = false), now)

        assertEquals(listOf(hd, sd), split.onNow)
        assertEquals(listOf(hdLater, sdLater), split.later)
        assertEquals(setOf(1L, 2L), split.channelIds)
    }

    @Test
    fun theTitleLookupHasOneGlobPerWordAndTheLimitLast() {
        val sql = programmeTitlesSql(2)

        assertTrue(sql.contains("AND title GLOB ? AND title GLOB ? ORDER BY"))
        assertEquals(6, sql.count { it == '?' })
        assertTrue(sql.endsWith("LIMIT ?"))
        assertEquals(1, programmeTitlesSql(1).split("title GLOB ?").size - 1)
    }

    @Test
    fun emptyHasNoShows() {
        assertEquals(0, ShowMatches.EMPTY.size)
        assertEquals(emptyList<ProgrammeSearchResult>(), ShowMatches.EMPTY.beyond)
        assertEquals(emptySet<Long>(), ShowMatches.EMPTY.channelIds)
    }
}
