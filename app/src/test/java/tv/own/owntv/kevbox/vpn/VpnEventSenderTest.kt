// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.vpn

import android.content.SharedPreferences
import java.io.File
import java.net.UnknownHostException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Collections
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.own.owntv.kevbox.errors.DeviceId
import tv.own.owntv.kevbox.session.KevBoxAuth
import tv.own.owntv.kevbox.session.PrefsSessionStore
import tv.own.owntv.kevbox.session.StoredSession
import tv.own.owntv.kevbox.session.TokenCipher
import tv.own.owntv.kevbox.supabase.SupabaseApi

class VpnEventSenderTest {

    /** Answers each VPN event batch with the next of [answers] (204 once empty) and sign-in for [nextUser]. */
    private class FakeServer : Interceptor {
        val bodies: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
        val authHeaders: MutableList<String?> = Collections.synchronizedList(mutableListOf())
        val answers = ArrayDeque<Int>()
        var nextUser = USER_A

        /** Runs on every VPN event call, before it is answered. */
        var onEventCall: () -> Unit = {}

        fun calls() = bodies.size

        /** The client numbers each call's entries carried, in order: the test events' names. */
        fun sentNames(call: Int): List<String> {
            val entries = bodies[call].getJSONArray("p_entries")
            return (0 until entries.length()).map { entries.getJSONObject(it).getString("client_number") }
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val (code, body) = when (request.url.encodedPath) {
                EVENTS_PATH -> {
                    bodies += JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                    authHeaders += request.header("Authorization")
                    onEventCall()
                    when (val answer = answers.removeFirstOrNull() ?: 204) {
                        NO_NETWORK -> throw UnknownHostException("no network")
                        THROW -> throw IllegalStateException("broken")
                        else -> answer to ""
                    }
                }
                TOKEN_PATH -> 200 to JSONObject()
                    .put("access_token", "a-$nextUser")
                    .put("refresh_token", "r-$nextUser")
                    .put("expires_in", 360_000)
                    .put("user", JSONObject().put("id", nextUser).put("email", "$nextUser@example.com"))
                    .toString()
                else -> throw UnknownHostException("no canned response")
            }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("canned")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private class PlainCipher : TokenCipher {
        override fun encrypt(plain: ByteArray) = plain.copyOf()
        override fun decrypt(sealed: ByteArray): ByteArray? = sealed.copyOf()
    }

    /** In-memory SharedPreferences: the JVM's android.jar stub would silently drop every write. */
    private class MemoryPrefs : SharedPreferences {
        val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = defValue
        override fun getLong(key: String, defValue: Long) = defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun getBoolean(key: String, defValue: Boolean) = defValue
        override fun contains(key: String) = key in values
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removed += key }
            override fun clear() = apply { removed += values.keys }
            override fun commit(): Boolean {
                removed.forEach { values.remove(it) }
                values.putAll(pending)
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }

    @get:Rule
    val folder = TemporaryFolder()

    private val server = FakeServer()
    private val clock = ZonedDateTime.of(2026, 11, 10, 22, 0, 0, 0, BEIRUT).toInstant().toEpochMilli()
    private val store = PrefsSessionStore(MemoryPrefs(), PlainCipher())
    private val api = SupabaseApi(OkHttpClient.Builder().addInterceptor(server).build(), BASE, "anon-key")
    private val crashes: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, e -> crashes += e })
    private val queue by lazy { VpnEventQueue(File(folder.root, VPN_EVENTS_FILE)) }

    // Runs inside the sender, after it picked the member and before it asks for a token.
    private var beforeToken: () -> Unit = {}

    private fun sender(auth: KevBoxAuth) = VpnEventSender(
        queue = queue,
        auth = auth,
        api = api,
        deviceId = DeviceId(MemoryPrefs()),
        appVersion = "99.99.99.99",
        scope = scope,
        now = { clock },
        zone = {
            beforeToken()
            BEIRUT
        },
    )

    private fun signedIn(user: String = USER_A): KevBoxAuth {
        store.save(StoredSession(user, "$user@example.com", "a-$user", "r-$user", clock + 100 * HOUR))
        return KevBoxAuth(api, store) { clock }
    }

    private fun event(n: Int) = VpnEvent(
        id = "e-$n",
        kind = VpnEventKind.CONNECTED,
        server = "vpn-de.premiumize.me",
        atMs = clock - HOUR + n * 1_000L,
        clientNumber = "n-$n",
    )

    private fun queueEvents(range: IntRange, user: String) = range.forEach { queue.add(event(it), user) }

    /** kick returns at once: wait for the work it started on [scope]. */
    private suspend fun settle() = scope.coroutineContext[Job]!!.children.toList().joinAll()

    @Test
    fun `a 2xx removes exactly the sent events, a failure keeps them`() = runBlocking {
        val sender = sender(signedIn())
        queueEvents(1..60, USER_A)
        server.answers.addAll(listOf(NO_NETWORK, 500, THROW, 404, 200))

        // No network, a 500, a throw and a 404 (function not on the server yet) each keep all 60 (AC-35).
        repeat(4) {
            sender.sendOnce()
            assertEquals(60, queue.size())
        }
        assertEquals(4, server.calls())

        sender.sendOnce()
        assertEquals(5, server.calls())
        assertEquals((1..50).map { "n-$it" }, server.sentNames(4))
        assertEquals("Bearer a-$USER_A", server.authHeaders[4])
        assertEquals("99.99.99.99", server.bodies[4].getString("p_app_version"))
        val first = server.bodies[4].getJSONArray("p_entries").getJSONObject(0)
        assertEquals("connected", first.getString("kind"))
        assertEquals("vpn-de.premiumize.me", first.getString("server"))
        assertEquals("2026-11-10T21:00:01.000+02:00", first.getString("at"))
        assertEquals(10, queue.size())
        assertEquals((51..60).map { "e-$it" }, queue.oldest(VPN_BATCH_MAX, USER_A).map { it.id })
        assertEquals(emptyList<Throwable>(), crashes.toList())
    }

    @Test
    fun `a refused session keeps the events`() = runBlocking {
        // A session at its expiry whose refresh the server refuses: SessionRejected, nothing sent.
        store.save(StoredSession(USER_A, "$USER_A@example.com", "a-$USER_A", "r-$USER_A", clock))
        val refusing = Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(400)
                .message("canned")
                .body("""{"error":"invalid_grant"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }
        val refusingApi = SupabaseApi(OkHttpClient.Builder().addInterceptor(refusing).build(), BASE, "anon-key")
        val auth = KevBoxAuth(refusingApi, store) { clock }
        val sender = VpnEventSender(queue, auth, refusingApi, DeviceId(MemoryPrefs()), "99.99.99.99", scope, { clock }, { BEIRUT })
        queueEvents(1..3, USER_A)

        sender.sendOnce()

        assertEquals(3, queue.size())
    }

    @Test
    fun `kick keeps sending after a 2xx while events remain`() = runBlocking {
        val sender = sender(signedIn())
        queueEvents(1..120, USER_A)

        sender.kick()
        settle()

        assertEquals(3, server.calls())
        assertEquals(listOf(50, 50, 20), (0 until 3).map { server.sentNames(it).size })
        assertEquals(0, queue.size())
    }

    @Test
    fun `kick stops at the first failure`() = runBlocking {
        val sender = sender(signedIn())
        queueEvents(1..120, USER_A)
        server.answers.addAll(listOf(200, 500))

        sender.kick()
        settle()

        assertEquals(2, server.calls())
        assertEquals(70, queue.size())
    }

    @Test
    fun `one trigger makes at most 10 calls`() = runBlocking {
        val sender = sender(signedIn())
        queueEvents(1..VPN_BATCH_MAX, USER_A)
        // Every call is answered 2xx and a fresh batch arrives meanwhile, so more always waits.
        var next = VPN_BATCH_MAX
        server.onEventCall = {
            queueEvents(next + 1..next + VPN_BATCH_MAX, USER_A)
            next += VPN_BATCH_MAX
        }

        sender.kick()
        settle()

        assertEquals(10, server.calls())
        assertEquals(VPN_BATCH_MAX, queue.size())
    }

    @Test
    fun `a new member never sends the previous member's events`() = runBlocking {
        queueEvents(1..2, USER_A)
        server.nextUser = USER_B
        val auth = KevBoxAuth(api, store) { clock }
        auth.signIn("$USER_B@example.com", "secret")
        val sender = sender(auth)

        sender.sendOnce()
        // Nothing of A's goes out under B, and A's are gone from the queue.
        assertEquals(0, server.calls())
        assertEquals(0, queue.size())

        queueEvents(3..3, USER_B)
        sender.sendOnce()
        assertEquals(1, server.calls())
        assertEquals(listOf("n-3"), server.sentNames(0))
        assertEquals("Bearer a-$USER_B", server.authHeaders[0])
    }

    @Test
    fun `a member switch during a send never sends one member's events under the other's token`() = runBlocking {
        val auth = signedIn(USER_A)
        val sender = sender(auth)
        queueEvents(1..3, USER_A)
        // A signs out and B signs in after the sender picked A's batch, before it reads a token.
        server.nextUser = USER_B
        beforeToken = {
            beforeToken = {}
            runBlocking {
                auth.signOut()
                auth.signIn("$USER_B@example.com", "secret")
            }
        }

        sender.sendOnce()

        assertEquals(USER_B, auth.userId.value)
        assertEquals(0, server.calls())
        assertEquals(3, queue.oldest(10, USER_A).size)
    }

    @Test
    fun `signed out sends nothing`() = runBlocking {
        queueEvents(1..3, USER_A)
        val sender = sender(KevBoxAuth(api, store) { clock })

        sender.sendOnce()
        sender.kick()
        settle()

        assertEquals(0, server.calls())
        assertEquals(3, queue.size())
    }

    @Test
    fun `the retry waits 1, 2, 5 and 15 minutes after failures, then every 15`() {
        assertEquals(listOf(1L, 1L, 2L, 5L, 15L, 15L, 15L), (0..6).map { vpnRetryDelayMs(it) / MINUTE })
    }

    private companion object {
        val BEIRUT: ZoneId = ZoneId.of("Asia/Beirut")
        const val USER_A = "user-a"
        const val USER_B = "user-b"
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val NO_NETWORK = -1
        const val THROW = -2
        const val BASE = "https://test.supabase.local"
        const val EVENTS_PATH = "/rest/v1/rpc/record_live_vpn_events"
        const val TOKEN_PATH = "/auth/v1/token"
    }
}
