// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.settings.SettingsRepository.GuideSort

class HomeRowsTest {

    private fun channel(
        id: Long,
        name: String = "Ch $id",
        sourceId: Long = 1,
        sortOrder: Int = id.toInt(),
        catchup: Boolean = false,
        logoUrl: String? = null,
        remoteId: String? = "$id",
        categoryId: Long? = null,
    ) = ChannelEntity(
        id = id, sourceId = sourceId, name = name, streamUrl = "http://x/$id.ts",
        remoteId = remoteId, sortOrder = sortOrder, catchup = catchup, logoUrl = logoUrl, categoryId = categoryId,
    )

    private val link: (ChannelEntity) -> String = { "link:${it.sourceId}:${it.remoteId}:${it.name}" }
    private val noCust = SectionCustomizations()

    private fun recent(
        history: List<ChannelEntity>,
        sources: Set<Long> = setOf(1, 2),
        cust: SectionCustomizations = noCust,
        hiddenCats: Set<Long> = emptySet(),
        now: Map<Long, String> = emptyMap(),
        fitted: (String) -> String? = { null },
    ) = HomeRows.recent(history, sources, cust, hiddenCats, now, fitted, link)

    private fun favorites(
        channels: List<ChannelEntity>,
        sort: GuideSort,
        sources: Set<Long> = setOf(1, 2),
        cust: SectionCustomizations = noCust,
    ) = HomeRows.favorites(channels, sort, sources, cust, emptyMap(), { null }, link)

    @Test
    fun rowsAreRecentFirst() {
        assertEquals(listOf(HomeRow.RECENT, HomeRow.FAVORITES), HomeRow.entries.toList())
        assertEquals("kevbox-live:recent", HomeRow.RECENT.providerId)
        assertEquals("kevbox-live:favorites", HomeRow.FAVORITES.providerId)
    }

    @Test
    fun recentKeepsHistoryOrderAndCapsAt15() {
        val history = (30L downTo 1L).map { channel(it) }
        val cards = recent(history)
        assertEquals(15, cards.size)
        assertEquals((30L downTo 16L).map { "ch:1:$it" }, cards.map { it.key })
        val weights = cards.map { it.weight }
        assertEquals(weights.sortedDescending(), weights)
        assertEquals(weights.size, weights.toSet().size)
    }

    @Test
    fun hiddenAndOtherSourceChannelsAreDroppedBeforeTheCap() {
        val history = (20L downTo 1L).map { channel(it, sourceId = if (it == 19L) 9 else 1) }
        val cust = SectionCustomizations(hiddenItems = mapOf("1:20" to "x"))
        val cards = recent(history, cust = cust)
        assertEquals(15, cards.size)
        assertEquals("ch:1:18", cards.first().key)
        assertTrue(cards.none { it.key == "ch:1:20" || it.key == "ch:9:19" })
    }

    @Test
    fun historyChannelInAHiddenCategoryIsLeftOutOfRecent() {
        val history = listOf(channel(1, categoryId = 7), channel(2, categoryId = 8), channel(3, categoryId = null))
        val cards = recent(history, hiddenCats = setOf(7))
        assertEquals(listOf("ch:1:2", "ch:1:3"), cards.map { it.key })
    }

    @Test
    fun cardCarriesRenameOnNowArtAndLink() {
        val ch = channel(5, name = "TF1 HD", logoUrl = " https://logo/tf1.png ")
        val noLogo = channel(6, logoUrl = null)
        val badLogo = channel(7, logoUrl = "file:///x.png")
        val cust = SectionCustomizations(itemNames = mapOf("1:5" to "TF1"))
        val cards = recent(
            listOf(ch, noLogo, badLogo), cust = cust,
            now = mapOf(5L to "Journal", 6L to " "),
        )
        val first = cards[0]
        assertEquals("ch:1:5", first.key)
        assertEquals("TF1", first.title)
        assertEquals("Journal", first.description)
        assertEquals("https://logo/tf1.png", first.artUri)
        assertEquals("link:1:5:TF1 HD", first.intentUri)
        assertNull(cards[1].description)
        assertNull(cards[1].artUri)
        assertNull(cards[2].artUri)

        val fitted = recent(listOf(ch), fitted = { if (it == "https://logo/tf1.png") "content://fit/5" else null })
        assertEquals("content://fit/5", fitted.single().artUri)
    }

    @Test
    fun keyFallsBackToNameWithoutRemoteId() {
        val cards = recent(listOf(channel(3, name = "Local", remoteId = null)))
        assertEquals("ch:1:Local", cards.single().key)
    }

    @Test
    fun favoritesFollowAlphaOrder() {
        val chs = listOf(channel(1, "delta"), channel(2, "Alpha"), channel(3, "charlie"), channel(4, "Bravo"))
        val cards = favorites(chs, GuideSort.ALPHA)
        assertEquals(listOf("Alpha", "Bravo", "charlie", "delta"), cards.map { it.title })
    }

    @Test
    fun favoritesAlphaUsesRenames() {
        val chs = listOf(channel(1, "Zed"), channel(2, "Bee"))
        val cust = SectionCustomizations(itemNames = mapOf("1:1" to "Aardvark"))
        assertEquals(listOf("Aardvark", "Bee"), favorites(chs, GuideSort.ALPHA, cust = cust).map { it.title })
    }

    @Test
    fun favoritesFollowProviderOrder() {
        val chs = listOf(
            channel(1, "b", sourceId = 2, sortOrder = 0),
            channel(2, "z", sourceId = 1, sortOrder = 5),
            channel(3, "a", sourceId = 1, sortOrder = 5),
            channel(4, "m", sourceId = 1, sortOrder = 1),
        )
        val expected = listOf("m", "a", "z", "b")
        assertEquals(expected, favorites(chs, GuideSort.PROVIDER).map { it.title })
        assertEquals(expected, favorites(chs, GuideSort.LIVE_TV).map { it.title })
        assertEquals(expected, favorites(chs, GuideSort.FAVORITES).map { it.title })
    }

    @Test
    fun favoritesCatchupFirstThenProvider() {
        val chs = listOf(channel(1, "a"), channel(2, "b", catchup = true), channel(3, "c"), channel(4, "d", catchup = true))
        assertEquals(listOf("b", "d", "a", "c"), favorites(chs, GuideSort.CATCHUP).map { it.title })
    }

    @Test
    fun favoritesCapAt15WithWeightsHighestFirst() {
        val cards = favorites((1L..20L).map { channel(it) }, GuideSort.PROVIDER)
        assertEquals(15, cards.size)
        assertEquals("ch:1:1", cards.first().key)
        assertEquals(cards.map { it.weight }.sortedDescending(), cards.map { it.weight })
    }

    @Test
    fun diffOfIdenticalInputsIsEmpty() {
        val wanted = recent((1L..4L).map { channel(it, logoUrl = "https://l/$it") }, now = mapOf(2L to "News"))
        val existing = stored(wanted)
        assertEquals(HomeDiff(emptyList(), emptyList(), emptyList()), HomeRows.diff(wanted, existing))
    }

    @Test
    fun aRemovedChannelYieldsOneDelete() {
        val before = recent((1L..4L).map { channel(it) })
        val existing = stored(before)
        // Channel 3 left the history; the others keep their stored weights.
        val after = before.filter { it.key != "ch:1:3" }
        val diff = HomeRows.diff(after, existing)
        assertEquals(emptyList<HomeCard>(), diff.insert)
        assertEquals(emptyList<Pair<Long, HomeCard>>(), diff.update)
        assertEquals(listOf(existing.getValue("ch:1:3").rowIds.single()), diff.delete)
    }

    @Test
    fun diffInsertsNewUpdatesChangedAndDropsDuplicates() {
        val before = recent(listOf(channel(1), channel(2)))
        val existing = stored(before).toMutableMap()
        existing["ch:1:2"] = existing.getValue("ch:1:2").copy(rowIds = listOf(200L, 201L, 202L))
        val changed = before[0].copy(description = "On now")
        val added = recent(listOf(channel(9))).single()
        val diff = HomeRows.diff(listOf(changed, before[1], added), existing)
        assertEquals(listOf(added), diff.insert)
        assertEquals(listOf(existing.getValue("ch:1:1").rowIds.single() to changed), diff.update)
        assertEquals(listOf(201L, 202L), diff.delete)
    }

    private fun stored(cards: List<HomeCard>): Map<String, ExistingProgram> =
        cards.withIndex().associate { (i, c) ->
            c.key to ExistingProgram(listOf(100L + i), c.title, c.description, c.artUri, c.intentUri, c.weight)
        }
}
