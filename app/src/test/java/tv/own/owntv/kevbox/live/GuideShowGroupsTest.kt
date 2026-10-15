// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.content.ProgrammeSearchResult
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.kevbox.live.GuideHeading.Kind

class GuideShowGroupsTest {

    private val now = 1_000_000_000_000L
    private val hour = 60 * 60_000L

    private fun channel(id: Long, categoryId: Long = 10) =
        ChannelEntity(id = id, sourceId = 1, categoryId = categoryId, name = "Ch $id", streamUrl = "http://x/$id.ts", remoteId = "$id")

    private fun show(ch: ChannelEntity, startMs: Long, stopMs: Long = startMs + hour) = ProgrammeSearchResult(
        EpgProgrammeEntity(sourceId = 1, epgChannelId = "ch${ch.id}", startMs = startMs, stopMs = stopMs, title = "News"),
        ch,
    )

    private val everywhere: (ChannelEntity) -> Boolean = { true }

    @Test
    fun aChannelSitsOnlyInTheFirstGroupItQualifiesFor() {
        val byName = channel(1)
        val onNowAndLater = channel(2)
        val laterOnly = channel(3)
        val shows = ShowMatches(
            onNow = listOf(show(byName, now - hour), show(onNowAndLater, now - hour)),
            later = listOf(show(byName, now + hour), show(onNowAndLater, now + 2 * hour), show(laterOnly, now + 3 * hour)),
        )

        val groups = groupGuideChannels(listOf(byName), shows, everywhere)

        assertEquals(listOf(1L, 2L, 3L), groups.channels.map { it.id })
        assertEquals(
            listOf(GuideHeading(Kind.NAMES, 0, 1), GuideHeading(Kind.ON_NOW, 1, 1), GuideHeading(Kind.LATER, 2, 1)),
            groups.headings,
        )
    }

    @Test
    fun laterRunsByEachChannelsEarliestStart() {
        val a = channel(1)
        val b = channel(2)
        val c = channel(3)
        // Already sorted by start, as ShowSearch hands them over: b first, then a, then b again, then c.
        val shows = ShowMatches(
            onNow = emptyList(),
            later = listOf(show(b, now + hour), show(a, now + 2 * hour), show(b, now + 3 * hour), show(c, now + 4 * hour)),
        )

        val groups = groupGuideChannels(emptyList(), shows, everywhere)

        assertEquals(listOf(2L, 1L, 3L), groups.channels.map { it.id })
        assertEquals(listOf(GuideHeading(Kind.LATER, 0, 3)), groups.headings)
    }

    @Test
    fun channelsOutsideTheCategoryAreDroppedAndEmptyGroupsHaveNoHeading() {
        val inside = channel(1, categoryId = 10)
        val outsideByName = channel(2, categoryId = 20)
        val outsideOnNow = channel(3, categoryId = 20)
        val insideLater = channel(4, categoryId = 10)
        val shows = ShowMatches(
            onNow = listOf(show(outsideOnNow, now - hour)),
            later = listOf(show(insideLater, now + hour)),
        )

        val groups = groupGuideChannels(listOf(inside, outsideByName), shows) { it.categoryId == 10L }

        assertEquals(listOf(1L, 4L), groups.channels.map { it.id })
        // ON NOW lost its only channel, so it has no heading; LATER starts right after NAMES.
        assertEquals(listOf(GuideHeading(Kind.NAMES, 0, 1), GuideHeading(Kind.LATER, 1, 1)), groups.headings)
    }

    @Test
    fun firstIndexMatchesEachGroupsFirstPositionInChannels() {
        val names = (1L..3L).map { channel(it) }
        val onNow = (4L..5L).map { channel(it) }
        val later = (6L..9L).map { channel(it) }
        val shows = ShowMatches(
            onNow = onNow.map { show(it, now - hour) },
            later = later.mapIndexed { i, ch -> show(ch, now + (i + 1) * hour) },
        )

        val groups = groupGuideChannels(names, shows, everywhere)

        assertEquals(9, groups.channels.size)
        for (h in groups.headings) {
            val expected = when (h.kind) { Kind.NAMES -> names; Kind.ON_NOW -> onNow; Kind.LATER -> later }
            assertEquals(expected.map { it.id }, groups.channels.subList(h.firstIndex, h.firstIndex + h.count).map { it.id })
        }
        assertEquals(listOf(0, 3, 5), groups.headings.map { it.firstIndex })
    }

    @Test
    fun aNameMatchListedTwiceShowsOnce() {
        val a = channel(1)
        val groups = groupGuideChannels(listOf(a, a), ShowMatches.EMPTY, everywhere)

        assertEquals(listOf(1L), groups.channels.map { it.id })
        assertEquals(listOf(GuideHeading(Kind.NAMES, 0, 1)), groups.headings)
    }

    @Test
    fun nothingFoundGivesNoChannelsAndNoHeadings() {
        val groups = groupGuideChannels(emptyList(), ShowMatches.EMPTY, everywhere)

        assertEquals(emptyList<ChannelEntity>(), groups.channels)
        assertEquals(emptyList<GuideHeading>(), groups.headings)
    }
}
