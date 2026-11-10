// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.realtime

import java.net.UnknownHostException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.kevbox.session.KevBoxAuth
import tv.own.owntv.kevbox.session.SessionStore
import tv.own.owntv.kevbox.session.StoredSession
import tv.own.owntv.kevbox.supabase.SupabaseApi

class PlaylistRealtimeTest {

    // --- fakes ---------------------------------------------------------------------------------

    /**
     * A clock for every sleep in the test: each sleeper wakes when [advance] passes its time, in
     * time order. Everything runs on runBlocking's one thread, so [settle] lets it all catch up.
     */
    private class VirtualTime {
        var now = 0L
            private set
        private var seq = 0
        private val sleepers = mutableListOf<Sleeper>()

        private class Sleeper(val wake: Long, val seq: Int, val done: CompletableDeferred<Unit>)

        suspend fun sleep(ms: Long) {
            val sleeper = Sleeper(now + ms, seq++, CompletableDeferred())
            sleepers += sleeper
            try {
                sleeper.done.await()
            } finally {
                sleepers.remove(sleeper)
            }
        }

        suspend fun advance(ms: Long) {
            val target = now + ms
            while (true) {
                settle()
                val next = sleepers.filter { it.wake <= target }.minWithOrNull(compareBy({ it.wake }, { it.seq })) ?: break
                sleepers.remove(next)
                now = next.wake
                next.done.complete(Unit)
            }
            now = target
            settle()
        }
    }

    private class FakeSocket(val openedAt: Long, val events: RealtimeSocket.Events) : RealtimeSocket {
        val sent = mutableListOf<String>()
        var closed = false

        override fun send(text: String): Boolean {
            if (closed) return false
            sent += text
            return true
        }

        override fun close() {
            closed = true
        }

        fun events(name: String) = sent.map { JSONObject(it) }.filter { it.getString("event") == name }
    }

    private class MemorySessionStore(var session: StoredSession?) : SessionStore {
        override fun load() = session
        override fun save(session: StoredSession) { this.session = session }
        override fun clear() { session = null }
    }

    /** No test here may reach the network: every call fails at once. */
    private object NoNetwork : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response = throw UnknownHostException("offline")
    }

    // --- harness -------------------------------------------------------------------------------

    private inner class Harness(val scope: CoroutineScope) {
        val time = VirtualTime()
        val store = MemorySessionStore(StoredSession("user-1", "member@example.com", "access", "refresh", Long.MAX_VALUE / 2))
        val auth = KevBoxAuth(SupabaseApi(OkHttpClient.Builder().addInterceptor(NoNetwork).build(), BASE, "anon"), store) { 0L }
        val sockets = mutableListOf<FakeSocket>()
        var changes = 0

        /** What the fake server does with each frame the TV sends; by default it answers like Supabase. */
        var server: (FakeSocket, JSONObject) -> Unit = { socket, frame -> answer(socket, frame) }

        val realtime = PlaylistRealtime(
            url = URL,
            auth = auth,
            scope = scope,
            sleep = { time.sleep(it) },
            onChange = { changes += 1 },
            connect = { _, events ->
                val fake = FakeSocket(time.now, events)
                sockets += fake
                Watched(fake)
            },
        )

        /** Lets the fake server see each frame as it goes out. */
        private inner class Watched(val fake: FakeSocket) : RealtimeSocket by fake {
            override fun send(text: String): Boolean {
                val ok = fake.send(text)
                if (ok) server(fake, JSONObject(text))
                return ok
            }
        }

        fun answer(socket: FakeSocket, frame: JSONObject) {
            val ref = frame.getString("ref")
            when (frame.getString("event")) {
                "phx_join" -> socket.events.onMessage(reply(REALTIME_TOPIC, ref, "ok"))
                "heartbeat" -> socket.events.onMessage(reply("phoenix", ref, "ok"))
            }
        }

        suspend fun start() {
            realtime.start()
            settle()
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

    private fun reply(topic: String, ref: String, status: String) =
        """{"topic":"$topic","event":"phx_reply","ref":"$ref","payload":{"status":"$status","response":{}}}"""

    private val change =
        """{"topic":"$REALTIME_TOPIC","event":"postgres_changes","ref":null,"payload":{"data":{"type":"UPDATE"},"ids":[1]}}"""

    private val systemError =
        """{"topic":"$REALTIME_TOPIC","event":"system","ref":null,""" +
            """"payload":{"status":"error","extension":"postgres_changes","message":"Unable to subscribe"}}"""

    // --- tests ---------------------------------------------------------------------------------

    @Test
    fun `backoff doubles from 5 s to 5 minutes, also when the join is refused after its ok`() = harness {
        assertEquals(listOf(5L, 10L, 20L, 40L, 80L, 160L, 300L, 300L, 300L), (0..8).map { backoffMs(it) / 1000 })

        // Supabase without 0023: the join gets its ok, then the change feed fails a second later.
        server = { socket, frame ->
            answer(socket, frame)
            if (frame.getString("event") == "phx_join") {
                scope.launch {
                    time.sleep(1_000)
                    socket.events.onMessage(systemError)
                }
            }
        }
        start()

        time.advance(60_000)

        // Open at 0, refused at 1 s, wait 5 s; open at 6 s, refused at 7 s, wait 10 s; and so on.
        assertEquals(listOf(0L, 6_000L, 17_000L, 38_000L), sockets.map { it.openedAt })
        assertTrue(sockets.dropLast(1).all { it.closed })
    }

    @Test
    fun `a join that holds for a minute resets the waits`() = harness {
        var drop = true
        server = { socket, frame ->
            answer(socket, frame)
            if (frame.getString("event") == "phx_join" && drop) {
                scope.launch {
                    time.sleep(1_000)
                    socket.events.onMessage(systemError)
                }
            }
        }
        start()
        time.advance(7_000) // refused at 1 s, reopened at 6 s, refused at 7 s
        assertEquals(2, sockets.size)

        drop = false
        time.advance(10_000) // reopened at 17 s; this join holds
        assertEquals(3, sockets.size)
        time.advance(70_000)
        sockets.last().events.onGone()
        val goneAt = time.now
        time.advance(6_000)

        assertEquals(4, sockets.size)
        assertEquals(goneAt + 5_000, sockets.last().openedAt)
    }

    @Test
    fun `a change and every join call onChange, and the frames carry the member's filter and token`() = harness {
        start()
        assertEquals(1, sockets.size)
        val join = sockets[0].events("phx_join").single()
        val payload = join.getJSONObject("payload")
        assertEquals("access", payload.getString("access_token"))
        assertEquals(
            "user_id=eq.user-1",
            payload.getJSONObject("config").getJSONArray("postgres_changes").getJSONObject(0).getString("filter"),
        )
        assertEquals(1, changes) // the join itself: a change made while the socket was down

        sockets[0].events.onMessage(change)
        settle()
        assertEquals(2, changes) // within the 5 s window: the clock has not moved
        assertEquals(0L, time.now)

        sockets[0].events.onGone()
        time.advance(5_000)
        assertEquals(2, sockets.size)
        assertEquals(3, changes) // the rejoin
    }

    @Test
    fun `heartbeats every 25 s, a missing reply reconnects, and a new token goes out every 30 minutes`() = harness {
        start()
        time.advance(25_000)
        assertEquals(1, sockets[0].events("heartbeat").size)
        time.advance(30 * 60_000L - 25_000)
        assertEquals(72, sockets[0].events("heartbeat").size)
        assertEquals("access", sockets[0].events("access_token").single().getJSONObject("payload").getString("access_token"))
        assertEquals(1, sockets.size)

        // The server goes quiet: the next heartbeat gets no reply within 10 s.
        server = { _, _ -> }
        time.advance(25_000)
        assertEquals(73, sockets[0].events("heartbeat").size)
        time.advance(10_000)
        assertTrue(sockets[0].closed)
        time.advance(5_000)
        assertEquals(2, sockets.size)
    }

    @Test
    fun `a join with no reply within 10 s reconnects, though heartbeats are answered`() = harness {
        // The join is lost; the socket itself stays alive and answers every heartbeat.
        server = { socket, frame ->
            if (frame.getString("event") == "heartbeat") answer(socket, frame)
        }
        start()
        assertEquals(0, changes)

        time.advance(9_999)
        assertTrue(!sockets[0].closed)
        time.advance(1)
        assertTrue(sockets[0].closed)

        // Answered normally again: the reconnect after the first wait joins.
        server = { socket, frame -> answer(socket, frame) }
        time.advance(5_000)
        assertEquals(2, sockets.size)
        assertEquals(1, changes)
    }

    @Test
    fun `the socket's client goes direct and retries a failed connection`() {
        // Core turns retries off on the shared client; the socket needs them on (AC-39).
        val shared = OkHttpClient.Builder().retryOnConnectionFailure(false).build()

        val client = PlaylistRealtime.realtimeClient(shared)

        assertTrue(client.retryOnConnectionFailure)
        assertEquals(java.net.Proxy.NO_PROXY, client.proxy)
    }

    @Test
    fun `signing out closes the socket and stops the reconnects`() = harness {
        start()
        assertEquals(1, sockets.size)

        auth.signOut()
        settle()

        assertTrue(sockets[0].closed)
        time.advance(10 * 60_000L)
        assertEquals(1, sockets.size)
    }

    @Test
    fun `a token that cannot be read counts as a failed attempt`() = harness {
        store.session = null // signed out in the store while userId still names the member
        start()
        assertEquals(0, sockets.size)
        store.session = StoredSession("user-1", "member@example.com", "access", "refresh", Long.MAX_VALUE / 2)

        time.advance(4_999)
        assertEquals(0, sockets.size)
        time.advance(1)
        assertEquals(1, sockets.size)
    }

    private companion object {
        const val BASE = "https://kevbox.example"
        const val URL = "wss://kevbox.example/realtime/v1/websocket?apikey=anon&vsn=1.0.0"

        suspend fun settle() = withTimeout(5_000) { repeat(200) { yield() } }
    }
}
