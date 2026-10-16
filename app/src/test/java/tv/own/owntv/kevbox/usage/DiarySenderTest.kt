// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.usage

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

class DiarySenderTest {

    /** Answers each stretch batch with the next of [answers] (204 once empty) and sign-in for [nextUser]. */
    private class FakeServer : Interceptor {
        val bodies: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
        val answers = ArrayDeque<Int>()
        var nextUser = USER_A

        /** Runs on every stretch call, before it is answered. */
        var onStretchCall: () -> Unit = {}

        fun calls() = bodies.size

        /** The stretch ids each call carried, in order. */
        fun sentIds(call: Int): List<String> {
            val entries = bodies[call].getJSONArray("p_entries")
            return (0 until entries.length()).map { entries.getJSONObject(it).getString("id") }
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val (code, body) = when (request.url.encodedPath) {
                STRETCHES_PATH -> {
                    bodies += JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                    onStretchCall()
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
    private val clock = ZonedDateTime.of(2026, 10, 15, 22, 0, 0, 0, BEIRUT).toInstant().toEpochMilli()
    private val store = PrefsSessionStore(MemoryPrefs(), PlainCipher())
    private val api = SupabaseApi(OkHttpClient.Builder().addInterceptor(server).build(), BASE, "anon-key")
    private val crashes: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, e -> crashes += e })
    private val queue by lazy { DiaryQueue(File(folder.root, "diary.json"), File(folder.root, "open.json")) }

    // Runs inside the sender, after it picked the member and before it asks for a token.
    private var beforeToken: () -> Unit = {}

    private fun sender(auth: KevBoxAuth) = DiarySender(
        queue = queue,
        auth = auth,
        api = api,
        deviceId = DeviceId(MemoryPrefs()),
        appVersion = "5.1.0.16",
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

    private fun stretch(n: Int) = Stretch(
        id = "s-$n",
        startedAtMs = clock - HOUR + n * 1_000L,
        endedAtMs = clock - HOUR + n * 1_000L + 5 * MINUTE,
        inBox = true,
        lastPressAtMs = clock - HOUR + n * 1_000L,
        channel = null,
        category = null,
        programme = null,
        providerHost = null,
        proxy = false,
    )

    private fun queueStretches(range: IntRange, user: String) = range.forEach { queue.add(stretch(it), user) }

    /** sendNow returns at once: wait for the work it started on [scope]. */
    private suspend fun settle() = scope.coroutineContext[Job]!!.children.toList().joinAll()

    @Test
    fun `a 2xx removes exactly the sent stretches, a failure keeps them`() = runBlocking {
        val sender = sender(signedIn())
        queueStretches(1..250, USER_A)
        server.answers.addAll(listOf(NO_NETWORK, 500, THROW, 200))

        // No network, a 500 and a throw each keep all 250 (AC-7).
        repeat(3) {
            sender.sendOnce()
            assertEquals(250, queue.size())
        }
        assertEquals(3, server.calls())

        sender.sendOnce()
        assertEquals(4, server.calls())
        assertEquals((1..200).map { "s-$it" }, server.sentIds(3))
        assertEquals("2026-10-15T22:00:00.000+03:00", server.bodies[3].getString("p_tv_now"))
        assertEquals(50, queue.size())
        assertEquals((201..250).map { "s-$it" }, queue.oldest(DIARY_BATCH_MAX, USER_A).map { it.id })
        assertEquals(emptyList<Throwable>(), crashes.toList())
    }

    @Test
    fun `sendNow keeps sending after a 2xx while stretches remain`() = runBlocking {
        val sender = sender(signedIn())
        queueStretches(1..250, USER_A)

        sender.sendNow()
        settle()

        assertEquals(2, server.calls())
        assertEquals(200, server.sentIds(0).size)
        assertEquals(50, server.sentIds(1).size)
        assertEquals(0, queue.size())
    }

    @Test
    fun `sendNow stops at the first failure`() = runBlocking {
        val sender = sender(signedIn())
        queueStretches(1..250, USER_A)
        server.answers.addAll(listOf(200, 500))

        sender.sendNow()
        settle()

        assertEquals(2, server.calls())
        assertEquals(50, queue.size())
    }

    @Test
    fun `a new member never sends the previous member's stretches`() = runBlocking {
        queueStretches(1..3, USER_A)
        queueStretches(4..4, USER_B)
        server.nextUser = USER_B
        val sender = sender(signedIn(USER_B))

        sender.sendOnce()

        assertEquals(1, server.calls())
        assertEquals(listOf("s-4"), server.sentIds(0))
        assertEquals(0, queue.size())
    }

    @Test
    fun `a new member with nothing queued makes no call and the previous member's stretches go`() = runBlocking {
        queueStretches(1..3, USER_A)
        server.nextUser = USER_B
        val sender = sender(signedIn(USER_B))

        sender.sendOnce()

        assertEquals(0, server.calls())
        assertEquals(0, queue.size())
    }

    @Test
    fun `a member switch during a send never sends one member's stretches under the other's token`() = runBlocking {
        val auth = signedIn(USER_A)
        val sender = sender(auth)
        queueStretches(1..3, USER_A)
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
        // Still tagged A: the next send drops them under B (AC-8).
        assertEquals(3, queue.oldest(10, USER_A).size)
    }

    @Test
    fun `one trigger makes at most 10 calls`() = runBlocking {
        val sender = sender(signedIn())
        queueStretches(1..DIARY_BATCH_MAX, USER_A)
        // Every call is answered 2xx and a fresh batch arrives meanwhile, so more always waits.
        var next = DIARY_BATCH_MAX
        server.onStretchCall = {
            queueStretches(next + 1..next + DIARY_BATCH_MAX, USER_A)
            next += DIARY_BATCH_MAX
        }

        sender.sendNow()
        settle()

        assertEquals(10, server.calls())
        assertEquals(DIARY_BATCH_MAX, queue.size())
    }

    @Test
    fun `signed out sends nothing`() = runBlocking {
        queueStretches(1..3, USER_A)
        val sender = sender(KevBoxAuth(api, store) { clock })

        sender.sendOnce()
        sender.sendNow()
        settle()

        assertEquals(0, server.calls())
        assertEquals(3, queue.size())
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
        const val STRETCHES_PATH = "/rest/v1/rpc/record_live_stretches"
        const val TOKEN_PATH = "/auth/v1/token"
    }
}
