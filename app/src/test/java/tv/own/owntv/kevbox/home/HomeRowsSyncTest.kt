// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.home

import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.settings.SettingsRepository.GuideSort

class HomeRowsSyncTest {

    /** Two channels and their programs, kept in memory the way the provider keeps them. */
    private class FakeProvider : HomeProvider {
        val channels = mapOf(HomeRow.RECENT to 10L, HomeRow.FAVORITES to 11L)
        val stored = HashMap<Long, MutableMap<Long, HomeCard>>()
        var ensured = 0
        private var nextId = 100L

        override fun supported() = true

        override fun ensureChannels(): Map<HomeRow, Long> {
            ensured++
            return channels
        }

        override fun programs(channels: Map<HomeRow, Long>) = channels.mapValues { (_, id) ->
            stored[id].orEmpty().entries.associate { (rowId, c) ->
                c.key to ExistingProgram(listOf(rowId), c.title, c.description, c.artUri, c.intentUri, c.weight)
            }
        }

        override fun apply(channelId: Long, diff: HomeDiff) {
            val rows = stored.getOrPut(channelId) { HashMap() }
            diff.delete.forEach { rows.remove(it) }
            diff.update.forEach { (id, card) -> rows[id] = card }
            diff.insert.forEach { rows[nextId++] = it }
        }

        fun titles(row: HomeRow) = stored[channels.getValue(row)].orEmpty().values.sortedByDescending { it.weight }.map { it.title }
    }

    private class FakeReads(var inputs: HomeInputs?, var coreOn: Boolean = false) : HomeReads {
        var turnedOff = 0

        override fun changes(): Flow<Unit> = emptyFlow()
        override suspend fun inputs() = inputs
        override suspend fun nowTitles(channels: List<ChannelEntity>, customizations: SectionCustomizations) =
            emptyMap<Long, String>()
        override suspend fun coreHomeOn() = coreOn
        override suspend fun turnCoreHomeOff() {
            turnedOff++
            coreOn = false
        }
    }

    private object NoArt : HomeArt {
        override fun fitted(raw: String): String? = null
        override fun prepare(raws: List<String>, onReady: suspend () -> Unit) = Unit
    }

    private fun channel(id: Long, name: String) = ChannelEntity(
        id = id, sourceId = 1, name = name, streamUrl = "http://x/$id.ts", remoteId = "$id", sortOrder = id.toInt(),
    )

    private val signedIn = HomeInputs(
        history = listOf(channel(1, "News"), channel(2, "Sport")),
        favorites = listOf(channel(3, "Films")),
        sort = GuideSort.ALPHA,
        liveSourceIds = setOf(1),
        customizations = SectionCustomizations(),
    )

    private fun sync(provider: FakeProvider, reads: FakeReads) = HomeRowsSync(
        provider = provider,
        reads = reads,
        art = NoArt,
        link = { "link:${it.remoteId}" },
        onAppLeft = {},
        scope = CoroutineScope(EmptyCoroutineContext),
        io = EmptyCoroutineContext,
    )

    @Test
    fun signOutEmptiesBothRowsAndKeepsTheChannels() = runBlocking {
        val provider = FakeProvider()
        val reads = FakeReads(signedIn)
        val sync = sync(provider, reads)

        sync.syncNow()
        assertEquals(listOf("News", "Sport"), provider.titles(HomeRow.RECENT))
        assertEquals(listOf("Films"), provider.titles(HomeRow.FAVORITES))

        reads.inputs = null
        sync.syncNow()
        assertTrue(provider.titles(HomeRow.RECENT).isEmpty())
        assertTrue(provider.titles(HomeRow.FAVORITES).isEmpty())
        // The channels are asked for on every sync, signed out too: they stay, empty.
        assertEquals(2, provider.ensured)

        reads.inputs = signedIn
        sync.syncNow()
        assertEquals(listOf("News", "Sport"), provider.titles(HomeRow.RECENT))
    }

    @Test
    fun coreSwitchIsTurnedOffOnce() = runBlocking {
        val reads = FakeReads(signedIn, coreOn = true)
        val sync = sync(FakeProvider(), reads)

        sync.syncNow()
        sync.syncNow()

        assertEquals(1, reads.turnedOff)
        assertFalse(reads.coreOn)
    }

    @Test
    fun coreSwitchAlreadyOffIsLeftAlone() = runBlocking {
        val reads = FakeReads(signedIn, coreOn = false)
        sync(FakeProvider(), reads).syncNow()
        assertEquals(0, reads.turnedOff)
    }
}
