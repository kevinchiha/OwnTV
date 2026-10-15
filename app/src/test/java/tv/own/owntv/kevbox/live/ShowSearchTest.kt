// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import org.junit.Assert.assertEquals
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
    fun onNowAndNext24HoursAreSplitAndTheRestDropped() {
        val spanningNow = show(1, "News", now - hour, now + hour)
        val inThreeHours = show(2, "News", now + 3 * hour, now + 4 * hour)
        val tooFarOut = show(3, "News", now + 25 * hour, now + 26 * hour)
        val alreadyOver = show(4, "News", now - 2 * hour, now - hour)

        val split = splitShows(listOf(spanningNow, inThreeHours, tooFarOut, alreadyOver), now)

        assertEquals(listOf(spanningNow), split.onNow)
        assertEquals(listOf(inThreeHours), split.later)
        assertEquals(2, split.size)
        assertEquals(setOf(1L, 2L), split.channelIds)
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
    fun emptyHasNoShows() {
        assertEquals(0, ShowMatches.EMPTY.size)
        assertEquals(emptySet<Long>(), ShowMatches.EMPTY.channelIds)
    }
}
