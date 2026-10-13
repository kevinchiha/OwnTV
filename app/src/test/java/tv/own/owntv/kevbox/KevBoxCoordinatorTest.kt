package tv.own.owntv.kevbox

import java.net.UnknownHostException
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.repository.SourceTestResult
import tv.own.owntv.kevbox.access.AccessMonitor
import tv.own.owntv.kevbox.access.VerdictPrefs
import tv.own.owntv.kevbox.expiry.ExpiryMonitor
import tv.own.owntv.kevbox.expiry.ExpiryPrefs
import tv.own.owntv.kevbox.playlist.LocalSources
import tv.own.owntv.kevbox.playlist.ManagedSourceStore
import tv.own.owntv.kevbox.playlist.ManagedState
import tv.own.owntv.kevbox.playlist.PlaylistReconciler
import tv.own.owntv.kevbox.playlist.PlaylistScreen
import tv.own.owntv.kevbox.playlist.SourceFields
import tv.own.owntv.kevbox.playlist.toFields
import tv.own.owntv.kevbox.session.KevBoxAuth
import tv.own.owntv.kevbox.session.SessionStore
import tv.own.owntv.kevbox.session.StoredSession
import tv.own.owntv.kevbox.supabase.AccessVerdict
import tv.own.owntv.kevbox.supabase.PlaylistRow
import tv.own.owntv.kevbox.supabase.SupabaseApi

class KevBoxCoordinatorTest {

    // --- fakes ---------------------------------------------------------------------------------

    private class Canned(val code: Int, val body: String)

    /**
     * Answers each path from its queue, then from its sticky answer. A path with a latch waits on it
     * (on OkHttp's thread) before answering, so a test can hold a fetch in flight.
     */
    private class FakeServer : Interceptor {
        private val queues = mutableMapOf<String, ArrayDeque<Canned>>()
        private val sticky = mutableMapOf<String, Canned>()
        private val holds = mutableMapOf<String, CountDownLatch>()
        private val counts = mutableMapOf<String, Int>()

        @Synchronized fun always(path: String, canned: Canned) { sticky[path] = canned }
        @Synchronized fun once(path: String, vararg canned: Canned) { queues.getOrPut(path) { ArrayDeque() }.addAll(canned) }
        @Synchronized fun hold(path: String) = CountDownLatch(1).also { holds[path] = it }
        @Synchronized fun calls(path: String) = counts[path] ?: 0

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val path = request.url.encodedPath
            val latch = synchronized(this) {
                counts[path] = (counts[path] ?: 0) + 1
                holds[path]
            }
            latch?.await(5, TimeUnit.SECONDS)
            val next = synchronized(this) { queues[path]?.removeFirstOrNull() ?: sticky[path] }
                ?: throw UnknownHostException("no canned response")
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(next.code)
                .message("canned")
                .body(next.body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private class MemorySessionStore(var session: StoredSession? = null) : SessionStore {
        override fun load() = session
        override fun save(session: StoredSession) { this.session = session }
        override fun clear() { session = null }
    }

    /** Core's sources as far as the reconciler sees them. */
    private class FakeSources : LocalSources {
        val sources = mutableMapOf<Long, SourceFields>()
        val profiles = mutableListOf(10L, 11L)
        val calls = mutableListOf<String>()
        private var nextId = 1L

        fun calls(prefix: String) = calls.count { it.startsWith(prefix) }

        override suspend fun fields(sourceId: Long) = sources[sourceId]
        override suspend fun findByLogin(serverUrl: String, username: String): Long? =
            sources.entries.firstOrNull { it.value.serverUrl == serverUrl && it.value.username == username }?.key
        override suspend fun profileIds(): List<Long> = profiles.toList()
        override suspend fun create(row: PlaylistRow, profileIds: List<Long>): Long {
            val id = nextId++
            sources[id] = row.toFields()
            calls += "create $id"
            return id
        }
        override suspend fun update(sourceId: Long, row: PlaylistRow) { sources[sourceId] = row.toFields(); calls += "update $sourceId" }
        override suspend fun link(sourceId: Long, profileIds: List<Long>) { calls += "link $sourceId" }
        override suspend fun unlinkEverywhere(sourceId: Long) { calls += "unlink $sourceId" }
        override suspend fun resync(sourceId: Long) { calls += "resync $sourceId" }
        override suspend fun stopSync(sourceId: Long) { calls += "stopSync $sourceId" }
        override suspend fun delete(sourceId: Long) { sources.remove(sourceId); calls += "delete $sourceId" }
    }

    private class MemoryStore : ManagedSourceStore {
        var state = ManagedState(pick = null, sources = emptyMap())
        override suspend fun read() = state
        override suspend fun write(state: ManagedState) { this.state = state }
        override suspend fun clear() { state = ManagedState(pick = null, sources = emptyMap()) }
    }

    /** Only observeAll is read; the rest is Room's business. */
    private class FakeProfileDao(ids: List<Long>) : ProfileDao {
        val all = MutableStateFlow(ids.map { ProfileEntity(id = it, name = "P$it", avatarColor = 0) })
        override fun observeAll(): Flow<List<ProfileEntity>> = all
        override suspend fun getAllOnce() = all.value
        override suspend fun deleteAll() = unused()
        override suspend fun getById(id: Long) = all.value.firstOrNull { it.id == id }
        override fun observeById(id: Long): Flow<ProfileEntity?> = unused()
        override suspend fun setAvatar(id: Long, avatarId: Int) = unused()
        override suspend fun setAvatarPath(id: Long, path: String?) = unused()
        override suspend fun count() = all.value.size
        override suspend fun insert(profile: ProfileEntity): Long = unused()
        override suspend fun update(profile: ProfileEntity) = unused()
        override suspend fun delete(profile: ProfileEntity) = unused()
        override suspend fun setPin(id: Long, pinHash: String?) = unused()
        private fun unused(): Nothing = throw UnsupportedOperationException()
    }

    private class MemoryExpiryPrefs : ExpiryPrefs { override var lastWarnedDay: String? = null }
    private class MemoryVerdictPrefs : VerdictPrefs { override var last: AccessVerdict? = null }

    // --- harness -------------------------------------------------------------------------------

    private inner class Harness(val scope: CoroutineScope) {
        val server = FakeServer()
        val sessions = MemorySessionStore()
        val core = FakeSources()
        val store = MemoryStore()
        val reconciler = PlaylistReconciler(core, store)
        val playing = MutableStateFlow(false)
        val tests = MutableStateFlow(0)
        var clock = 1_000_000_000_000L

        // The timer's sleep: tell the test the loop is idle, then wait for the test to move the clock.
        private val idle = Channel<Unit>()
        private val go = Channel<Unit>()

        lateinit var auth: KevBoxAuth
        lateinit var coordinator: KevBoxCoordinator

        init {
            server.always(LOGOUT, Canned(204, ""))
            server.always(VERDICT, Canned(200, "\"ALLOWED\""))
        }

        fun signedIn() {
            sessions.session = StoredSession("user-1", "member@example.com", "access", "refresh", clock + 3_600_000)
        }

        /** Builds everything over the seeded state and starts it; returns once the start checks ran. */
        suspend fun start() {
            auth = KevBoxAuth(SupabaseApi(OkHttpClient.Builder().addInterceptor(server).build(), BASE, "anon"), sessions) { clock }
            val expiry = ExpiryMonitor(
                test = { tests.value += 1; SourceTestResult.Ok() },
                now = { clock },
                today = { LocalDate.of(2026, 10, 13) },
                prefs = MemoryExpiryPrefs(),
            )
            val access = AccessMonitor(fetch = { auth.verdict() }, prefs = MemoryVerdictPrefs())
            coordinator = KevBoxCoordinator(
                auth = auth,
                reconciler = reconciler,
                expiry = expiry,
                access = access,
                profileDao = FakeProfileDao(core.profiles),
                playbackActive = playing,
                scope = scope,
                now = { clock },
                sleep = { idle.send(Unit); go.receive() },
            )
            coordinator.start()
            idle.receive()
        }

        /** Moves the clock, lets the timer wake once, and returns when it is idle again. */
        suspend fun advance(ms: Long) {
            clock += ms
            go.send(Unit)
            idle.receive()
        }
    }

    private fun harness(block: suspend Harness.() -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        try {
            Harness(scope).block()
        } finally {
            scope.cancel()
        }
    }

    private suspend fun <T> StateFlow<T>.await(predicate: (T) -> Boolean): T = withTimeout(5_000) { first(predicate) }

    private fun row(number: String, name: String = "Line $number") =
        PlaylistRow(clientNumber = number, name = name, serverUrl = "http://s$number.example", username = "u$number", password = "p$number")

    private fun rowsBody(vararg rows: PlaylistRow) = rows.joinToString(",", "[", "]") {
        """{"client_number":"${it.clientNumber}","name":"${it.name}","server_url":"${it.serverUrl}",""" +
            """"username":"${it.username}","password":"${it.password}"}"""
    }

    private val minute = 60_000L

    // --- tests ---------------------------------------------------------------------------------

    @Test
    fun `a failed playlist check leaves the managed store, the pick and the gate as they were`() = harness {
        signedIn()
        server.once(PLAYLIST, Canned(200, rowsBody(row("1"))))
        server.always(PLAYLIST, Canned(500, "oops"))
        start()
        assertEquals(GateState.Open, coordinator.gate.await { it == GateState.Open })
        val before = store.state
        val callsBefore = core.calls.toList()

        advance(15 * minute)

        assertEquals(2, server.calls(PLAYLIST))
        assertEquals(before, store.state)
        assertEquals("1", store.state.pick)
        assertEquals(callsBefore, core.calls)
        assertEquals(GateState.Open, coordinator.gate.value)
    }

    @Test
    fun `a refused refresh signs out - every managed source deleted, pick and mapping cleared, gate SignedOut - and a later apply creates nothing`() =
        harness {
            // Two lines on this TV, one hidden and one visible, before the session goes bad.
            reconciler.apply(listOf(row("1"), row("2")))
            reconciler.choose("1")
            reconciler.choose("2")
            assertEquals(2, core.sources.size)
            sessions.session = StoredSession("user-1", "member@example.com", "access", "refresh", clock - 1)
            server.always(TOKEN, Canned(400, """{"error":"invalid_grant"}"""))

            start()

            assertEquals(GateState.SignedOut, coordinator.gate.await { it == GateState.SignedOut })
            assertEquals(emptyMap<Long, SourceFields>(), core.sources)
            assertEquals(ManagedState(pick = null, sources = emptyMap()), store.state)
            assertEquals(null, sessions.session)
            assertEquals(null, reconciler.currentClientNumber.value)

            val creates = core.calls("create")
            reconciler.apply(listOf(row("1")))
            assertEquals(creates, core.calls("create"))
            assertTrue(core.sources.isEmpty())
        }

    @Test
    fun `checks fire at start and on foreground, two triggers at once run one fetch, and the cadences follow the clock`() = harness {
        signedIn()
        server.always(PLAYLIST, Canned(200, rowsBody(row("1"))))
        start()
        assertEquals(1, server.calls(PLAYLIST))
        assertEquals(1, server.calls(VERDICT))

        // Two foregrounds while the fetch is held: the second joins the first.
        val latch = server.hold(PLAYLIST)
        coordinator.onForeground()
        coordinator.onForeground()
        withTimeout(5_000) { while (server.calls(PLAYLIST) < 2) yield() }
        // Every queued trigger ran before the fetch reached the server; none started a second one.
        assertEquals(2, server.calls(PLAYLIST))
        latch.countDown()
        withTimeout(5_000) { while (server.calls(VERDICT) < 2) yield() }

        // 5 minutes: the verdict only. 15 minutes after the foreground: both.
        advance(5 * minute)
        assertEquals(2, server.calls(PLAYLIST))
        assertEquals(3, server.calls(VERDICT))
        advance(4 * minute)
        assertEquals(2, server.calls(PLAYLIST))
        assertEquals(3, server.calls(VERDICT))
        advance(1 * minute)
        assertEquals(4, server.calls(VERDICT))
        advance(5 * minute)
        assertEquals(3, server.calls(PLAYLIST))
        assertEquals(5, server.calls(VERDICT))
    }

    @Test
    fun `an expiry check that falls due during playback runs when playbackActive turns false`() = harness {
        signedIn()
        server.always(PLAYLIST, Canned(200, rowsBody(row("1"))))
        reconciler.apply(listOf(row("1")))
        start()
        tests.await { it == 1 } // the start check
        playing.value = true

        advance(6 * 60 * minute)
        assertEquals(1, tests.value)

        playing.value = false
        assertEquals(2, tests.await { it == 2 })
    }

    @Test
    fun `the gate shows Locked over a ChoosePlaylist state, and a later ALLOWED returns to it without signing in`() = harness {
        signedIn()
        val alpha = row("2", name = "Alpha")
        val zed = row("1", name = "Zed")
        server.always(PLAYLIST, Canned(200, rowsBody(zed, alpha)))
        server.always(VERDICT, Canned(200, "\"LOCKED\""))
        start()

        assertEquals(GateState.Locked, coordinator.gate.await { it == GateState.Locked })
        assertEquals(PlaylistScreen.ChooseFirst(listOf(alpha, zed)), reconciler.screen.value)

        server.always(VERDICT, Canned(200, "\"ALLOWED\""))
        advance(5 * minute)

        assertEquals(GateState.ChoosePlaylist(listOf(alpha, zed)), coordinator.gate.await { it is GateState.ChoosePlaylist })
        assertEquals(0, server.calls(TOKEN))
    }

    private companion object {
        const val BASE = "https://kevbox.example"
        const val TOKEN = "/auth/v1/token"
        const val LOGOUT = "/auth/v1/logout"
        const val PLAYLIST = "/rest/v1/member_playlist"
        const val VERDICT = "/rest/v1/rpc/get_access_verdict"
    }
}
