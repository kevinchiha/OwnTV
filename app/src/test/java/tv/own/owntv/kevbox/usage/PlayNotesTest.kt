// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.usage

import android.content.SharedPreferences
import java.net.UnknownHostException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Collections
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.kevbox.errors.DeviceId
import tv.own.owntv.kevbox.session.KevBoxAuth
import tv.own.owntv.kevbox.session.PrefsSessionStore
import tv.own.owntv.kevbox.session.StoredSession
import tv.own.owntv.kevbox.session.TokenCipher
import tv.own.owntv.kevbox.supabase.SupabaseApi

class PlayNotesTest {

    // --- playNoteDue ---------------------------------------------------------------------------

    @Test
    fun `the 60-minute limit and the failure exception`() {
        assertTrue(playNoteDue(lastSentAtMs = null, failureSinceLastSent = false, nowMs = NOW))
        // AC-3: 59 minutes after a sent note, not due; 60 minutes, due.
        assertFalse(playNoteDue(lastSentAtMs = NOW - 59 * MINUTE, failureSinceLastSent = false, nowMs = NOW))
        assertTrue(playNoteDue(lastSentAtMs = NOW - 60 * MINUTE, failureSinceLastSent = false, nowMs = NOW))
        // AC-4: a failure since the last note sends 5 minutes after it.
        assertTrue(playNoteDue(lastSentAtMs = NOW - 5 * MINUTE, failureSinceLastSent = true, nowMs = NOW))
        // The wall clock moved back past the last note: due, not stalled.
        assertTrue(playNoteDue(lastSentAtMs = NOW + MINUTE, failureSinceLastSent = false, nowMs = NOW))
    }

    @Test
    fun `played-at text keeps zero seconds and the zone`() {
        val at = ZonedDateTime.of(2026, 10, 15, 22, 21, 0, 0, BEIRUT).toInstant().toEpochMilli()

        assertEquals("2026-10-15T22:21:00.000+03:00", playedAtText(at, BEIRUT))
    }

    // --- PlayNotes -----------------------------------------------------------------------------

    /** Answers each play note with the next of [playAnswers] ([NO_NETWORK]: none; 204 once empty) and sign-in for [nextUser]. */
    private class FakeServer : Interceptor {
        val playBodies: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
        val playAnswers = ArrayDeque<Int>()
        var nextUser = "user-1"

        fun plays() = playBodies.size

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val (code, body) = when (request.url.encodedPath) {
                PLAY_PATH -> {
                    playBodies += JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                    val answer = playAnswers.removeFirstOrNull() ?: 204
                    if (answer == NO_NETWORK) throw UnknownHostException("no network")
                    answer to ""
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

    private val server = FakeServer()
    private var clock = at(10, 0)
    private var proxy = false
    private val store = PrefsSessionStore(MemoryPrefs(), PlainCipher())
    private val api = SupabaseApi(OkHttpClient.Builder().addInterceptor(server).build(), BASE, "anon-key")
    private val scope = CoroutineScope(SupervisorJob())

    private fun at(hour: Int, minute: Int) =
        ZonedDateTime.of(2026, 10, 15, hour, minute, 0, 0, BEIRUT).toInstant().toEpochMilli()

    private fun notes(auth: KevBoxAuth) = PlayNotes(
        auth = auth,
        api = api,
        deviceId = DeviceId(MemoryPrefs()),
        appVersion = "5.1.0.15",
        proxyInUse = { proxy },
        scope = scope,
        now = { clock },
        zone = { BEIRUT },
    )

    private fun signedIn(): KevBoxAuth {
        store.save(StoredSession("user-1", "user-1@example.com", "a-user-1", "r-user-1", clock + 100 * HOUR))
        return KevBoxAuth(api, store) { clock }
    }

    /** onPlayed and onForeground return at once: wait for the work they started on [scope]. */
    private suspend fun settle() = scope.coroutineContext[Job]!!.children.toList().joinAll()

    private suspend fun PlayNotes.play() {
        onPlayed()
        settle()
    }

    @Test
    fun `a failed send stays pending and a newer note replaces it`() = runBlocking {
        val notes = notes(signedIn())
        server.playAnswers.addAll(listOf(NO_NETWORK, 500, 200))

        notes.play()
        assertEquals(1, server.plays())

        // Nothing went out yet in this process, so the next play is due and replaces the note.
        clock = at(10, 1)
        proxy = true
        notes.play()
        assertEquals(2, server.plays())

        clock = at(10, 15)
        notes.sendPendingNow()
        assertEquals(3, server.plays())
        val sent = server.playBodies.last()
        assertEquals("2026-10-15T10:01:00.000+03:00", sent.getString("p_played_at"))
        assertEquals(true, sent.getBoolean("p_proxy"))
        assertEquals(
            setOf("p_device_id", "p_app_version", "p_played_at", "p_proxy"),
            sent.keys().asSequence().toSet(),
        )

        // Nothing pending after the 200, and a play inside the hour sends nothing.
        notes.sendPendingNow()
        clock = at(10, 30)
        notes.play()
        assertEquals(3, server.plays())
    }

    @Test
    fun `a failure recorded after a note was built still lets the next play send at once`() = runBlocking {
        val notes = notes(signedIn())
        server.playAnswers.addAll(listOf(500, 200))

        notes.play()
        clock = at(10, 5)
        notes.onFailureRecorded()
        clock = at(10, 10)
        notes.sendPendingNow()
        assertEquals("2026-10-15T10:00:00.000+03:00", server.playBodies.last().getString("p_played_at"))
        assertEquals(2, server.plays())

        // AC-4: the 10:10 send carried a note built before the failure, so the flag stays set.
        clock = at(10, 20)
        notes.play()
        assertEquals(3, server.plays())

        // That note was built after the failure: sent, it clears the flag, and the hour holds again.
        clock = at(10, 30)
        notes.play()
        assertEquals(3, server.plays())
    }

    @Test
    fun `a failure lifts the 60-minute limit for the next play`() = runBlocking {
        val notes = notes(signedIn())
        notes.play()
        clock = at(10, 3)
        notes.onFailureRecorded()

        clock = at(10, 5)
        notes.play()

        assertEquals(2, server.plays())
    }

    @Test
    fun `signed out sends nothing`() = runBlocking {
        val auth = KevBoxAuth(api, store) { clock }
        val notes = notes(auth)

        notes.play()
        assertEquals(0, server.plays())

        // Nothing was left pending: signing in and retrying sends nothing either.
        auth.signIn("user-1@example.com", "secret")
        notes.sendPendingNow()
        notes.onForeground()
        settle()
        assertEquals(0, server.plays())
    }

    @Test
    fun `a pending note waits while signed out and is dropped for another member`() = runBlocking {
        val auth = signedIn()
        val notes = notes(auth)
        server.playAnswers.add(500)
        notes.play()
        assertEquals(1, server.plays())

        auth.signOut()
        notes.sendPendingNow()
        assertEquals(1, server.plays())

        server.nextUser = "user-2"
        auth.signIn("user-2@example.com", "secret")
        notes.onForeground()
        settle()
        assertEquals(1, server.plays())
    }

    @Test
    fun `a note sent for one member never holds back another member's first note`() = runBlocking {
        val auth = signedIn()
        val notes = notes(auth)
        notes.play()
        assertEquals(1, server.plays())

        auth.signOut()
        server.nextUser = "user-2"
        auth.signIn("user-2@example.com", "secret")
        clock = at(10, 1)
        notes.play()
        assertEquals(2, server.plays())

        clock = at(10, 2)
        notes.play()
        assertEquals(2, server.plays())
    }

    private companion object {
        val BEIRUT: ZoneId = ZoneId.of("Asia/Beirut")
        val NOW = ZonedDateTime.of(2026, 10, 15, 12, 0, 0, 0, ZoneId.of("Asia/Beirut")).toInstant().toEpochMilli()
        const val MINUTE = 60_000L
        const val NO_NETWORK = -1
        const val HOUR = 60 * MINUTE
        const val BASE = "https://test.supabase.local"
        const val PLAY_PATH = "/rest/v1/rpc/record_live_play"
        const val TOKEN_PATH = "/auth/v1/token"
    }
}
