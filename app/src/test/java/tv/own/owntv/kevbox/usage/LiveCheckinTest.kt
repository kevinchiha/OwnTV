// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.usage

import android.content.SharedPreferences
import java.net.UnknownHostException
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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

class LiveCheckinTest {

    // --- checkinDue ----------------------------------------------------------------------------

    @Test
    fun `checkinDue never sends signed out`() {
        assertFalse(checkinDue(lastSentAtMs = null, nowMs = NOW, signedIn = false))
        assertFalse(checkinDue(lastSentAtMs = NOW - 2 * CHECKIN_INTERVAL_MS, nowMs = NOW, signedIn = false))
    }

    @Test
    fun `checkinDue sends the first check-in of the process`() {
        assertTrue(checkinDue(lastSentAtMs = null, nowMs = NOW, signedIn = true))
    }

    @Test
    fun `checkinDue waits the full 6 hours`() {
        val almost = 5 * HOUR + 59 * MINUTE

        assertFalse(checkinDue(lastSentAtMs = NOW - almost, nowMs = NOW, signedIn = true))
        assertTrue(checkinDue(lastSentAtMs = NOW - 6 * HOUR, nowMs = NOW, signedIn = true))
        assertTrue(checkinDue(lastSentAtMs = NOW - 9 * HOUR, nowMs = NOW, signedIn = true))
    }

    @Test
    fun `checkinDue sends when the wall clock moved back past the last check-in`() {
        assertTrue(checkinDue(lastSentAtMs = NOW + 1, nowMs = NOW, signedIn = true))
        assertTrue(checkinDue(lastSentAtMs = NOW + 3 * HOUR, nowMs = NOW, signedIn = true))
        assertFalse(checkinDue(lastSentAtMs = NOW + 3 * HOUR, nowMs = NOW, signedIn = false))
    }

    // --- LiveCheckin ---------------------------------------------------------------------------

    /** Answers the check-in with [checkinCode] and sign-in with a session for [nextUser]. */
    private class FakeServer : Interceptor {
        val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
        var checkinCode = 204
        var nextUser = "user-1"

        fun checkins() = requests.count { it.url.encodedPath == CHECKIN_PATH }

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            val (code, body) = when (request.url.encodedPath) {
                CHECKIN_PATH -> checkinCode to ""
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
    private var clock = NOW
    private val store = PrefsSessionStore(MemoryPrefs(), PlainCipher())
    private val api = SupabaseApi(OkHttpClient.Builder().addInterceptor(server).build(), BASE, "anon-key")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun checkin(auth: KevBoxAuth) =
        LiveCheckin(auth, api, DeviceId(MemoryPrefs()), "0.3.0", scope, now = { clock })

    private fun signedIn(): KevBoxAuth {
        store.save(StoredSession("user-1", "user-1@example.com", "a-user-1", "r-user-1", NOW + 100 * HOUR))
        return KevBoxAuth(api, store) { clock }
    }

    @Test
    fun `a check-in goes out at once, then again only once 6 hours have passed`() = runBlocking {
        val checkin = checkin(signedIn())

        checkin.checkInIfDue()
        assertEquals(1, server.checkins())

        // The 15-minute ticks before the 6 hours are up send nothing.
        clock += 6 * HOUR - 15 * MINUTE
        checkin.checkInIfDue()
        assertEquals(1, server.checkins())

        // The first tick at 6 hours sends: not 12 hours, as a 6-hour delay from start did.
        clock += 15 * MINUTE
        checkin.checkInIfDue()
        assertEquals(2, server.checkins())
    }

    @Test
    fun `a failed check-in is due again on the next tick`() = runBlocking {
        val checkin = checkin(signedIn())
        server.checkinCode = 500

        checkin.checkInIfDue()
        clock += 15 * MINUTE
        server.checkinCode = 204
        checkin.checkInIfDue()
        clock += 15 * MINUTE
        checkin.checkInIfDue()

        assertEquals(2, server.checkins())
    }

    @Test
    fun `a different member signing in is due at once, the same member is not`() = runBlocking {
        val auth = signedIn()
        val checkin = checkin(auth)
        checkin.checkInIfDue()
        assertEquals(1, server.checkins())

        auth.signOut()
        checkin.checkInIfDue()
        assertEquals(1, server.checkins())

        server.nextUser = "user-2"
        auth.signIn("user-2@example.com", "secret")
        clock += MINUTE
        checkin.checkInIfDue()
        assertEquals(2, server.checkins())
        assertEquals("Bearer a-user-2", server.requests.last { it.url.encodedPath == CHECKIN_PATH }.header("Authorization"))

        clock += MINUTE
        checkin.checkInIfDue()
        assertEquals(2, server.checkins())
    }

    @Test
    fun `a wall clock moved back sends instead of waiting for the old time`() = runBlocking {
        val checkin = checkin(signedIn())
        checkin.checkInIfDue()

        clock -= 2 * HOUR
        checkin.checkInIfDue()

        assertEquals(2, server.checkins())
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val BASE = "https://test.supabase.local"
        const val CHECKIN_PATH = "/rest/v1/rpc/record_live_checkin"
        const val TOKEN_PATH = "/auth/v1/token"
    }
}
