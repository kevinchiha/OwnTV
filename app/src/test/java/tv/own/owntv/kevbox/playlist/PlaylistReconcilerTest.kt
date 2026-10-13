package tv.own.owntv.kevbox.playlist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.kevbox.supabase.PlaylistRow

class PlaylistReconcilerTest {

    // --- fakes ---------------------------------------------------------------------------------

    private class FakeSource(var fields: SourceFields, val links: MutableSet<Long>)

    /** Core as far as the reconciler can see it. [favourites] stand in for rows keyed on a source. */
    private class FakeSources : LocalSources {
        val sources = mutableMapOf<Long, FakeSource>()
        val profiles = mutableListOf<Long>()
        val favourites = mutableMapOf<Long, MutableList<String>>()
        val calls = mutableListOf<String>()
        var nextId = 1L
        var createDelayMs = 0L
        val failDelete = mutableSetOf<Long>()

        /** Moves on every link change, as Room's invalidation does for profile_source. */
        val linkVersion = MutableStateFlow(0)

        fun calls(prefix: String) = calls.count { it.startsWith(prefix) }

        /** What Core's ProfileManager.create does: the new profile gets every source, hidden ones too. */
        fun addProfileLikeCore(id: Long) {
            profiles += id
            sources.values.forEach { it.links += id }
            linkVersion.value += 1
        }

        /** Core's ProfileManager.create, second half: the links land after the profile row. */
        fun linkEverySourceTo(profileId: Long) {
            sources.values.forEach { it.links += profileId }
            linkVersion.value += 1
        }

        override fun observeLinkedSourceIds(profileId: Long): Flow<List<Long>> =
            linkVersion.map { sources.filterValues { profileId in it.links }.keys.sorted() }.distinctUntilChanged()

        override suspend fun fields(sourceId: Long) = sources[sourceId]?.fields

        override suspend fun findByLogin(serverUrl: String, username: String): Long? =
            sources.entries.firstOrNull { it.value.fields.serverUrl == serverUrl && it.value.fields.username == username }?.key

        override suspend fun profileIds(): List<Long> = profiles.toList()

        override suspend fun create(row: PlaylistRow, profileIds: List<Long>): Long {
            if (createDelayMs > 0) delay(createDelayMs)
            val id = nextId++
            sources[id] = FakeSource(row.toFields(), profileIds.toMutableSet())
            linkVersion.value += 1
            calls += "create $id"
            return id
        }

        override suspend fun update(sourceId: Long, row: PlaylistRow) {
            sources.getValue(sourceId).fields = row.toFields()
            calls += "update $sourceId"
        }

        override suspend fun link(sourceId: Long, profileIds: List<Long>) {
            sources.getValue(sourceId).links += profileIds
            linkVersion.value += 1
            calls += "link $sourceId"
        }

        override suspend fun unlinkEverywhere(sourceId: Long) {
            sources.getValue(sourceId).links.clear()
            linkVersion.value += 1
            calls += "unlink $sourceId"
        }

        override suspend fun resync(sourceId: Long) {
            calls += "resync $sourceId"
        }

        override suspend fun stopSync(sourceId: Long) {
            calls += "stopSync $sourceId"
        }

        override suspend fun delete(sourceId: Long) {
            if (sourceId in failDelete) throw IllegalStateException("database is locked")
            sources.remove(sourceId)
            favourites.remove(sourceId)
            calls += "delete $sourceId"
        }
    }

    private class MemoryStore(var state: ManagedState = ManagedState(pick = null, sources = emptyMap())) : ManagedSourceStore {
        val writes = mutableListOf<ManagedState>()
        override suspend fun read() = state
        override suspend fun write(state: ManagedState) {
            this.state = state
            writes += state
        }
        override suspend fun clear() {
            state = ManagedState(pick = null, sources = emptyMap())
        }
    }

    private fun row(number: String, name: String = "Line $number") =
        PlaylistRow(clientNumber = number, name = name, serverUrl = "http://s$number.example", username = "u$number", password = "p$number")

    private val core = FakeSources().apply { profiles += listOf(10L, 11L) }
    private val store = MemoryStore()
    private val reconciler = PlaylistReconciler(core, store)

    private fun mapped(number: String) = store.state.sources[number]

    // --- tests ---------------------------------------------------------------------------------

    @Test
    fun `one row is created and picked with no prompt, linked to every profile and synced`() = runBlocking {
        assertNull(reconciler.screen.value)
        reconciler.apply(listOf(row("1")))

        val id = mapped("1")!!.sourceId
        assertFalse(mapped("1")!!.hidden)
        assertEquals(setOf(10L, 11L), core.sources.getValue(id).links)
        assertEquals(listOf("create $id", "resync $id"), core.calls)
        assertEquals("1", store.state.pick)
        assertNull(store.state.pendingCreate)
        assertEquals(PlaylistScreen.Home, reconciler.screen.value)
        assertEquals("1", reconciler.currentClientNumber.value)
        assertEquals(id, reconciler.currentSourceId())
    }

    @Test
    fun `two rows with no pick ask, sorted by name, and create nothing`() = runBlocking {
        val zed = row("1", name = "Zed")
        val alpha = row("2", name = "Alpha")
        reconciler.apply(listOf(zed, alpha))

        assertEquals(PlaylistScreen.ChooseFirst(listOf(alpha, zed)), reconciler.screen.value)
        assertEquals(listOf(alpha, zed), reconciler.rows.value)
        assertEquals(0, core.calls("create"))
        assertNull(reconciler.currentClientNumber.value)

        reconciler.choose("1")
        assertEquals(PlaylistScreen.Home, reconciler.screen.value)
        assertEquals("1", reconciler.currentClientNumber.value)
        assertEquals(1, core.calls("create"))
    }

    @Test
    fun `a row added later does not ask a TV with a pick`() = runBlocking {
        reconciler.apply(listOf(row("1")))
        reconciler.apply(listOf(row("1"), row("2")))

        assertEquals(PlaylistScreen.Home, reconciler.screen.value)
        assertEquals("1", reconciler.currentClientNumber.value)
        assertEquals(1, core.calls("create"))
    }

    @Test
    fun `two overlapping apply calls create one source`() = runBlocking {
        core.createDelayMs = 50
        listOf(
            async(Dispatchers.Default) { reconciler.apply(listOf(row("1"))) },
            async(Dispatchers.Default) { reconciler.apply(listOf(row("1"))) },
        ).awaitAll()

        assertEquals(1, core.sources.size)
        assertEquals(1, core.calls("create"))
    }

    @Test
    fun `the picked row removed hides its source, then uses the single row left`() = runBlocking {
        reconciler.apply(listOf(row("1")))
        val old = mapped("1")!!.sourceId

        reconciler.apply(listOf(row("2")))

        assertTrue(mapped("1")!!.hidden)
        assertTrue(core.sources.getValue(old).links.isEmpty())
        assertTrue(core.calls.contains("stopSync $old"))
        assertEquals(0, core.calls("delete"))
        assertEquals("2", reconciler.currentClientNumber.value)
        assertFalse(mapped("2")!!.hidden)
        assertEquals(PlaylistScreen.Home, reconciler.screen.value)
    }

    @Test
    fun `the picked row removed asks among several`() = runBlocking {
        reconciler.apply(listOf(row("1")))
        reconciler.apply(listOf(row("2", name = "B"), row("3", name = "A")))

        assertTrue(mapped("1")!!.hidden)
        assertEquals(PlaylistScreen.ChooseFirst(listOf(row("3", name = "A"), row("2", name = "B"))), reconciler.screen.value)
        assertNull(reconciler.currentClientNumber.value)
        assertNull(reconciler.currentSourceId())
    }

    @Test
    fun `the picked row removed with no rows left shows NoPlaylist`() = runBlocking {
        reconciler.apply(listOf(row("1")))
        reconciler.apply(emptyList())

        assertTrue(mapped("1")!!.hidden)
        assertEquals(PlaylistScreen.NoPlaylist, reconciler.screen.value)
        assertEquals(1, core.sources.size)
    }

    @Test
    fun `a changed name, server, username or password updates in place with the same source id`() = runBlocking {
        val original = row("1")
        reconciler.apply(listOf(original))
        val id = mapped("1")!!.sourceId
        assertEquals(id, reconciler.visibleSourceId.value)
        listOf(
            original.copy(name = "Renamed"),
            original.copy(serverUrl = "http://moved.example"),
            original.copy(username = "other"),
            original.copy(password = "secret2"),
        ).forEach { changed ->
            val before = reconciler.visibleSourceChanges.value
            reconciler.apply(listOf(changed))
            assertEquals(id, mapped("1")!!.sourceId)
            assertEquals(changed.toFields(), core.sources.getValue(id).fields)
            // The expiry check hears about the edit; the ID, which stops playback, stays put.
            assertTrue(reconciler.visibleSourceChanges.value > before)
            assertEquals(id, reconciler.visibleSourceId.value)
        }
        assertEquals(1, core.calls("create"))
        assertEquals(4, core.calls("update"))
        assertEquals(5, core.calls("resync"))
    }

    @Test
    fun `choosing another row hides the old one and brings back a hidden match`() = runBlocking {
        reconciler.apply(listOf(row("1"), row("2")))
        reconciler.choose("1")
        val first = mapped("1")!!.sourceId
        reconciler.choose("2")
        val second = mapped("2")!!.sourceId
        assertTrue(mapped("1")!!.hidden)

        reconciler.choose("1")

        assertEquals(first, mapped("1")!!.sourceId)
        assertFalse(mapped("1")!!.hidden)
        assertTrue(mapped("2")!!.hidden)
        assertTrue(core.sources.getValue(second).links.isEmpty())
        assertEquals(2, core.calls("create"))
        assertEquals(first, reconciler.currentSourceId())
    }

    @Test
    fun `unhide relinks the same source id to every profile and resyncs, leaving its favourites alone`() = runBlocking {
        reconciler.apply(listOf(row("1"), row("2")))
        reconciler.choose("1")
        val first = mapped("1")!!.sourceId
        core.favourites[first] = mutableListOf("channel 42")
        reconciler.choose("2")
        assertTrue(core.sources.getValue(first).links.isEmpty())
        core.calls.clear()

        reconciler.choose("1")

        assertEquals(first, mapped("1")!!.sourceId)
        assertEquals(setOf(10L, 11L), core.sources.getValue(first).links)
        assertTrue(core.calls.contains("update $first"))
        assertTrue(core.calls.contains("link $first"))
        assertTrue(core.calls.contains("resync $first"))
        assertEquals(mutableListOf("channel 42"), core.favourites[first])
        assertEquals(0, core.calls("create"))
    }

    @Test
    fun `a new profile gets the visible source and none of the hidden ones`() = runBlocking {
        reconciler.apply(listOf(row("1"), row("2")))
        reconciler.choose("1")
        reconciler.choose("2")
        val hiddenId = mapped("1")!!.sourceId
        val visibleId = mapped("2")!!.sourceId

        core.addProfileLikeCore(12)
        reconciler.onProfilesChanged(listOf(10, 11, 12))

        assertEquals(setOf(10L, 11L, 12L), core.sources.getValue(visibleId).links)
        assertTrue(core.sources.getValue(hiddenId).links.isEmpty())
    }

    @Test
    fun `with no profile, Create waits for the first one and the screen is Home`() = runBlocking {
        core.profiles.clear()
        reconciler.apply(listOf(row("1")))

        assertEquals(0, core.calls("create"))
        assertEquals("1", store.state.pick)
        assertEquals(PlaylistScreen.Home, reconciler.screen.value)
        assertNull(reconciler.currentSourceId())

        core.addProfileLikeCore(10)
        reconciler.onProfilesChanged(listOf(10))

        val id = mapped("1")!!.sourceId
        assertEquals(setOf(10L), core.sources.getValue(id).links)
        assertEquals(1, core.calls("create"))
        assertTrue(core.calls.contains("resync $id"))
    }

    @Test
    fun `a Create the process died in is adopted instead of creating a second copy`() = runBlocking {
        val line = row("1")
        core.sources[5] = FakeSource(line.toFields(), mutableSetOf(10))
        store.state = ManagedState(pick = "1", sources = emptyMap(), pendingCreate = "1")

        reconciler.apply(listOf(line))

        assertEquals(0, core.calls("create"))
        assertEquals(5L, mapped("1")!!.sourceId)
        assertEquals(setOf(10L, 11L), core.sources.getValue(5).links)
        assertNull(store.state.pendingCreate)
    }

    @Test
    fun `the store records pendingCreate before Core creates the source`() = runBlocking {
        reconciler.apply(listOf(row("1")))
        val beforeCreate = store.writes.first()
        assertEquals("1", PendingCreate.parse(beforeCreate.pendingCreate!!).clientNumber)
        assertNull(beforeCreate.sources["1"])
    }

    @Test
    fun `a mapping whose source is gone from Core counts as no mapping`() = runBlocking {
        core.nextId = 3
        store.state = ManagedState(pick = "1", sources = mapOf("1" to ManagedSource(1, hidden = false), "2" to ManagedSource(2, hidden = true)))

        reconciler.apply(listOf(row("1")))

        assertEquals(1, core.calls("create"))
        assertEquals(3L, mapped("1")!!.sourceId)
        assertNull(mapped("2"))
    }

    @Test
    fun `wipe deletes every managed source and closes the reconciler until reopen`() = runBlocking {
        reconciler.apply(listOf(row("1"), row("2")))
        reconciler.choose("1")
        reconciler.choose("2")
        assertEquals(2, core.sources.size)

        reconciler.wipe()

        assertTrue(core.sources.isEmpty())
        assertEquals(ManagedState(pick = null, sources = emptyMap()), store.state)
        assertNull(reconciler.screen.value)
        assertTrue(reconciler.rows.value.isEmpty())
        assertNull(reconciler.currentClientNumber.value)

        reconciler.apply(listOf(row("1")))
        reconciler.onProfilesChanged(listOf(10))
        assertTrue(core.sources.isEmpty())
        assertNull(reconciler.screen.value)

        reconciler.reopen()
        reconciler.apply(listOf(row("1")))
        assertEquals(1, core.sources.size)
        assertNotNull(reconciler.screen.value)
    }

    @Test
    fun `an apply in flight finishes before wipe, which then deletes what it made`() = runBlocking {
        core.createDelayMs = 50
        val applying = async(Dispatchers.Default) { reconciler.apply(listOf(row("1"))) }
        delay(10)
        reconciler.wipe()
        applying.await()

        assertTrue(core.sources.isEmpty())
        assertNull(reconciler.screen.value)
    }

    @Test
    fun `the visible-source counter moves on create and switch, not on a no-op fetch or a re-pick`() = runBlocking {
        reconciler.apply(listOf(row("1"), row("2")))
        assertEquals(0, reconciler.visibleSourceChanges.value)
        assertNull(reconciler.visibleSourceId.value)
        reconciler.choose("1")
        assertEquals(1, reconciler.visibleSourceChanges.value)
        val first = reconciler.visibleSourceId.value
        reconciler.apply(listOf(row("1"), row("2")))
        assertEquals(1, reconciler.visibleSourceChanges.value)
        reconciler.choose("2")
        assertEquals(2, reconciler.visibleSourceChanges.value)
        val second = reconciler.visibleSourceId.value
        assertTrue(second != first)
        reconciler.choose("2")
        assertEquals(2, reconciler.visibleSourceChanges.value)
        assertEquals(second, reconciler.visibleSourceId.value)
    }

    @Test
    fun `an admin credential change updates in place and does not stop playback, a switch to another row does`() = runBlocking {
        reconciler.apply(listOf(row("1"), row("2")))
        reconciler.choose("1")
        val first = reconciler.visibleSourceId.value!!
        val switches = reconciler.visibleSourceSwitches.value

        reconciler.apply(listOf(row("1").copy(password = "new"), row("2")))
        assertEquals(first, reconciler.visibleSourceId.value)
        assertEquals(switches, reconciler.visibleSourceSwitches.value)

        reconciler.choose("2")
        assertEquals(mapped("2")!!.sourceId, reconciler.visibleSourceId.value)
        assertTrue(reconciler.visibleSourceId.value != first)
        assertEquals(switches + 1, reconciler.visibleSourceSwitches.value)
    }

    @Test
    fun `on a cold start, a first fetch that moves off the stored pick counts as a switch`() = runBlocking {
        // The stored pick plays before this run's first fetch; the admin moved the TV to row 2 while it was off.
        core.nextId = 6
        core.sources[5] = FakeSource(row("1").toFields(), mutableSetOf(10, 11))
        store.state = ManagedState(pick = "1", sources = mapOf("1" to ManagedSource(5, hidden = false)))
        assertNull(reconciler.visibleSourceId.value)

        reconciler.apply(listOf(row("2")))

        assertTrue(mapped("1")!!.hidden)
        assertEquals(mapped("2")!!.sourceId, reconciler.visibleSourceId.value)
        assertEquals(1, reconciler.visibleSourceSwitches.value)
    }

    @Test
    fun `on a cold start, a first fetch that keeps the stored pick, even edited, is no switch`() = runBlocking {
        core.sources[5] = FakeSource(row("1").toFields(), mutableSetOf(10, 11))
        store.state = ManagedState(pick = "1", sources = mapOf("1" to ManagedSource(5, hidden = false)))

        reconciler.apply(listOf(row("1").copy(password = "new")))

        assertEquals(5L, reconciler.visibleSourceId.value)
        assertEquals(0, reconciler.visibleSourceSwitches.value)
    }

    @Test
    fun `the same server URL with a trailing slash, other case or spaces is no change`() = runBlocking {
        reconciler.apply(listOf(row("1")))
        core.calls.clear()
        val before = reconciler.visibleSourceChanges.value

        reconciler.apply(listOf(row("1").copy(serverUrl = " HTTP://S1.example/ ")))

        assertEquals(0, core.calls("update"))
        assertEquals(0, core.calls("resync"))
        assertEquals(before, reconciler.visibleSourceChanges.value)
    }

    @Test
    fun `a hidden source linked to a new profile after its profile emission is unlinked`() = runBlocking {
        reconciler.apply(listOf(row("1"), row("2")))
        reconciler.choose("1")
        reconciler.choose("2")
        val hiddenId = mapped("1")!!.sourceId
        val visibleId = mapped("2")!!.sourceId
        val profiles = MutableStateFlow(listOf(10L, 11L))
        val watching = launch { reconciler.watchProfiles(profiles) }
        yield()

        // Core inserts profile 12 and Room emits it before ProfileManager.create links any source.
        core.profiles += 12
        profiles.value = listOf(10L, 11L, 12L)
        yield()
        assertTrue(core.sources.getValue(hiddenId).links.isEmpty())

        // Then Core's loop links every source, the hidden one too.
        core.linkEverySourceTo(12)
        withTimeout(5_000) { while (core.sources.getValue(hiddenId).links.isNotEmpty()) yield() }

        assertTrue(core.sources.getValue(hiddenId).links.isEmpty())
        assertTrue(12L in core.sources.getValue(visibleId).links)
        watching.cancel()
    }

    @Test
    fun `wipe keeps entries it failed to delete, hidden, and the next wipe deletes them`() = runBlocking {
        reconciler.apply(listOf(row("1"), row("2")))
        reconciler.choose("1")
        reconciler.choose("2")
        val stuck = mapped("2")!!.sourceId
        val gone = mapped("1")!!.sourceId
        core.failDelete += stuck

        reconciler.wipe()

        assertNull(core.sources[gone])
        assertNotNull(core.sources[stuck])
        assertTrue(core.sources.getValue(stuck).links.isEmpty())
        assertEquals(ManagedState(pick = null, sources = mapOf("2" to ManagedSource(stuck, hidden = true))), store.state)

        core.failDelete.clear()
        reconciler.wipe()
        assertTrue(core.sources.isEmpty())
        assertEquals(ManagedState(pick = null, sources = emptyMap()), store.state)
    }

    @Test
    fun `a cold start that signs out at once still deletes a Create the process died in`() = runBlocking {
        // The last run wrote pendingCreate, then died inside Core's create.
        core.createDelayMs = 0
        val line = row("1")
        val crashed = PlaylistReconciler(core, store)
        crashed.apply(listOf(line))
        val orphan = mapped("1")!!.sourceId
        store.state = store.writes.first() // the state on disk at the crash: pending, no mapping

        // A new process: no rows in memory.
        val cold = PlaylistReconciler(core, store)
        cold.wipe()

        assertNull(core.sources[orphan])
        assertEquals(ManagedState(pick = null, sources = emptyMap()), store.state)
    }
}
