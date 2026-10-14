package tv.own.owntv.kevbox.session

import android.content.SharedPreferences
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.KeyStoreException
import java.security.ProviderException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.kevbox.supabase.AccessVerdict
import tv.own.owntv.kevbox.supabase.Fetch
import tv.own.owntv.kevbox.supabase.PlaylistRow
import tv.own.owntv.kevbox.supabase.SignInResult
import tv.own.owntv.kevbox.supabase.SupabaseApi

class KevBoxAuthTest {

    // --- fakes ---------------------------------------------------------------------------------

    /**
     * One canned answer. [error] is thrown instead of answering; [arrived] counts down when the
     * request reaches the server and [hold] keeps the answer back until the test releases it.
     */
    private class Canned(
        val code: Int,
        val body: String,
        val error: IOException? = null,
        val arrived: CountDownLatch? = null,
        val hold: CountDownLatch? = null,
    )

    /** Answers each request from a per-path queue; a queue entry of null throws as no network. */
    private class FakeServer : Interceptor {
        val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
        val bodies: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val queues = mutableMapOf<String, ArrayDeque<Canned?>>()

        fun on(path: String, vararg responses: Canned?) = synchronized(this) {
            queues.getOrPut(path) { ArrayDeque() }.addAll(responses)
        }

        fun calls(path: String) = synchronized(requests) { requests.count { it.url.encodedPath == path } }

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val next = synchronized(this) {
                requests += request
                bodies += request.body?.let { body -> Buffer().also { body.writeTo(it) }.readUtf8() } ?: ""
                queues[request.url.encodedPath]?.removeFirstOrNull()
            } ?: throw UnknownHostException("no canned response")
            next.arrived?.countDown()
            next.hold?.await(5, TimeUnit.SECONDS)
            next.error?.let { throw it }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(next.code)
                .message("canned")
                .body(next.body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    /** Reversible stand-in for the Keystore cipher; [broken] makes every blob undecryptable. */
    private class FakeCipher(var broken: Boolean = false) : TokenCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(sealed: ByteArray): ByteArray? =
            if (broken) null else sealed.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
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

    /** A Keystore that cannot seal: every encrypt throws, as on cheap TV firmware. */
    private class FailingCipher(private val error: () -> Exception) : TokenCipher {
        override fun encrypt(plain: ByteArray): ByteArray = throw error()
        override fun decrypt(sealed: ByteArray): ByteArray? = null
    }

    // --- harness -------------------------------------------------------------------------------

    private val server = FakeServer()
    private val prefs = MemoryPrefs()
    private val cipher = FakeCipher()
    private val store = PrefsSessionStore(prefs, cipher)
    private var clock = 1_000_000L
    private val http = OkHttpClient.Builder().addInterceptor(server).build()

    private fun api(url: String = BASE) = SupabaseApi(http, url, ANON)
    private fun auth(url: String = BASE) = KevBoxAuth(api(url), store) { clock }

    private fun tokenBody(access: String, refresh: String, expiresIn: Long = 3600) = JSONObject()
        .put("access_token", access)
        .put("refresh_token", refresh)
        .put("expires_in", expiresIn)
        .put("token_type", "bearer")
        .put("user", JSONObject().put("id", "user-1").put("email", "member@example.com"))
        .toString()

    private fun storeSession(expiresAtMs: Long, access: String = "old-access", refresh: String = "old-refresh") =
        store.save(StoredSession("user-1", "member@example.com", access, refresh, expiresAtMs))

    private val rowsBody = """[{"client_number":"C1","name":"Home","server_url":"http://iptv.example",""" +
        """"username":"u","password":"p"}]"""

    // --- sign-in -------------------------------------------------------------------------------

    @Test
    fun `a good password signs in, saves the session and emits the email`() = runBlocking {
        server.on(TOKEN, Canned(200, tokenBody("a1", "r1", expiresIn = 3600)))
        val auth = auth()

        val result = auth.signIn("member@example.com", "secret")

        assertEquals(SignInResult.Success("member@example.com"), result)
        assertEquals("member@example.com", auth.email.value)
        assertEquals(StoredSession("user-1", "member@example.com", "a1", "r1", clock + 3_600_000), store.load())
        val request = server.requests.single()
        assertEquals("password", request.url.queryParameter("grant_type"))
        assertEquals(ANON, request.header("apikey"))
        assertNull(request.header("Authorization"))
        val sent = JSONObject(server.bodies.single())
        assertEquals("member@example.com", sent.getString("email"))
        assertEquals("secret", sent.getString("password"))
    }

    @Test
    fun `userId follows sign-in and sign-out`() = runBlocking {
        server.on(TOKEN, Canned(200, tokenBody("a1", "r1")))
        server.on(LOGOUT, Canned(204, ""))
        val auth = auth()
        assertNull(auth.userId.value)

        auth.signIn("member@example.com", "secret")
        assertEquals("user-1", auth.userId.value)

        auth.signOut()
        assertNull(auth.userId.value)
    }

    @Test
    fun `userId starts from a stored session`() {
        storeSession(expiresAtMs = clock + 3_600_000)

        assertEquals("user-1", auth().userId.value)
    }

    @Test
    fun `wrong password returns WrongCredentials and saves nothing`() = runBlocking {
        server.on(
            TOKEN,
            Canned(400, """{"error":"invalid_grant","error_description":"Invalid login credentials"}"""),
            Canned(400, """{"code":400,"error_code":"invalid_credentials","msg":"Invalid login credentials"}"""),
        )
        val auth = auth()

        assertEquals(SignInResult.WrongCredentials, auth.signIn("member@example.com", "nope"))
        assertEquals(SignInResult.WrongCredentials, auth.signIn("member@example.com", "nope"))
        assertNull(store.load())
        assertNull(auth.email.value)
    }

    @Test
    fun `sign-in reports no network, other errors and a blank URL without saving`() = runBlocking {
        server.on(TOKEN, null, Canned(500, "oops"), Canned(400, """{"error_code":"validation_failed"}"""))
        val auth = auth()

        assertEquals(SignInResult.NoNetwork, auth.signIn("m@example.com", "pw"))
        assertEquals(SignInResult.Failed, auth.signIn("m@example.com", "pw"))
        assertEquals(SignInResult.Failed, auth.signIn("m@example.com", "pw"))

        val requestsBefore = server.requests.size
        assertEquals(SignInResult.Failed, auth("").signIn("m@example.com", "pw"))
        assertEquals(requestsBefore, server.requests.size)
        assertNull(store.load())
    }

    @Test
    fun `a Keystore failure while saving returns Failed and saves nothing`() = runBlocking {
        server.on(TOKEN, Canned(200, tokenBody("a1", "r1")), Canned(200, tokenBody("a1", "r1")))
        val errors = ArrayDeque(listOf<Exception>(ProviderException("keystore"), KeyStoreException("keystore")))
        val failing = PrefsSessionStore(prefs, FailingCipher { errors.removeFirst() })
        val auth = KevBoxAuth(api(), failing) { clock }

        assertEquals(SignInResult.Failed, auth.signIn("member@example.com", "secret"))
        assertEquals(SignInResult.Failed, auth.signIn("member@example.com", "secret"))
        assertTrue(prefs.values.isEmpty())
        assertNull(auth.email.value)
    }

    @Test
    fun `sign-in sends the email trimmed and lowercased, the password as typed`() = runBlocking {
        server.on(TOKEN, Canned(200, tokenBody("a1", "r1")))

        auth().signIn("  Member@Example.COM ", " Pass Word ")

        val sent = JSONObject(server.bodies.single())
        assertEquals("member@example.com", sent.getString("email"))
        assertEquals(" Pass Word ", sent.getString("password"))
    }

    @Test
    fun `an SSLException maps to Failed, an unknown host or timeout to NoNetwork`() = runBlocking {
        server.on(
            TOKEN,
            Canned(0, "", error = SSLHandshakeException("certificate not yet valid")),
            null,
            Canned(0, "", error = SocketTimeoutException("timeout")),
        )
        val auth = auth()

        assertEquals(SignInResult.Failed, auth.signIn("m@example.com", "pw"))
        assertEquals(SignInResult.NoNetwork, auth.signIn("m@example.com", "pw"))
        assertEquals(SignInResult.NoNetwork, auth.signIn("m@example.com", "pw"))
        assertNull(store.load())
    }

    // --- refresh -------------------------------------------------------------------------------

    @Test
    fun `an access token inside 60 s of expiry is refreshed before the read, and the rotated refresh token is stored`() =
        runBlocking {
            storeSession(expiresAtMs = clock + 59_000)
            server.on(REFRESH_PATH, Canned(200, tokenBody("new-access", "new-refresh", expiresIn = 3600)))
            server.on(PLAYLIST, Canned(200, rowsBody))
            val auth = auth()

            val result = auth.playlists()

            assertEquals(Fetch.Ok(listOf(PlaylistRow("C1", "Home", "http://iptv.example", "u", "p"))), result)
            val refresh = server.requests[0]
            assertEquals("refresh_token", refresh.url.queryParameter("grant_type"))
            assertEquals("old-refresh", JSONObject(server.bodies[0]).getString("refresh_token"))
            val read = server.requests[1]
            assertEquals("Bearer new-access", read.header("Authorization"))
            assertEquals(ANON, read.header("apikey"))
            assertEquals(
                "client_number,name,server_url,username,password,proxy_enabled,proxy_host,proxy_port,proxy_username,proxy_password",
                read.url.queryParameter("select"),
            )
            assertEquals("name.asc", read.url.queryParameter("order"))
            val saved = store.load()!!
            assertEquals("new-refresh", saved.refreshToken)
            assertEquals("new-access", saved.accessToken)
            assertEquals(clock + 3_600_000, saved.expiresAtMs)
        }

    @Test
    fun `a row's proxy columns become its proxy, and an off, blank or bad one reads as OFF`() = runBlocking {
        storeSession(expiresAtMs = clock + 600_000)
        val body = """[{"client_number":"C1","name":"A","server_url":"http://a","username":"u","password":"p",""" +
            """"proxy_enabled":true,"proxy_host":" proxy.example ","proxy_port":3128,"proxy_username":"pu","proxy_password":"pp"},""" +
            """{"client_number":"C2","name":"B","server_url":"http://b","username":"u","password":"p",""" +
            """"proxy_enabled":false,"proxy_host":"proxy.example","proxy_port":3128,"proxy_username":null,"proxy_password":null},""" +
            """{"client_number":"C3","name":"C","server_url":"http://c","username":"u","password":"p",""" +
            """"proxy_enabled":true,"proxy_host":"","proxy_port":3128,"proxy_username":null,"proxy_password":null},""" +
            """{"client_number":"C4","name":"D","server_url":"http://d","username":"u","password":"p",""" +
            """"proxy_enabled":true,"proxy_host":"proxy.example","proxy_port":0,"proxy_username":null,"proxy_password":null}]"""
        server.on(PLAYLIST, Canned(200, body))

        val rows = (auth().playlists() as Fetch.Ok).value

        assertEquals(tv.own.owntv.kevbox.supabase.PlaylistProxy(true, "proxy.example", 3128, "pu", "pp"), rows[0].proxy)
        assertEquals(listOf(tv.own.owntv.kevbox.supabase.PlaylistProxy.OFF), rows.drop(1).map { it.proxy }.distinct())
    }

    @Test
    fun `a server without the proxy columns is asked again without them, and the proxy reads as off`() = runBlocking {
        storeSession(expiresAtMs = clock + 600_000)
        server.on(
            PLAYLIST,
            Canned(400, """{"code":"42703","message":"column member_playlist.proxy_enabled does not exist"}"""),
            Canned(200, rowsBody),
        )

        val result = auth().playlists()

        assertEquals(Fetch.Ok(listOf(PlaylistRow("C1", "Home", "http://iptv.example", "u", "p"))), result)
        assertEquals(2, server.calls(PLAYLIST))
        assertEquals("client_number,name,server_url,username,password", server.requests.last().url.queryParameter("select"))
    }

    @Test
    fun `a token with more than 60 s left is used as it is`() = runBlocking {
        storeSession(expiresAtMs = clock + 61_000)
        server.on(VERDICT, Canned(200, "\"ALLOWED\""))

        assertEquals(Fetch.Ok(AccessVerdict.ALLOWED), auth().verdict())
        assertEquals(0, server.calls(REFRESH_PATH))
        assertEquals("Bearer old-access", server.requests.single().header("Authorization"))
    }

    @Test
    fun `a 401 from PostgREST refreshes once and retries once`() = runBlocking {
        storeSession(expiresAtMs = clock + 600_000)
        server.on(VERDICT, Canned(401, """{"message":"JWT expired"}"""), Canned(200, "\"LOCKED\""))
        server.on(REFRESH_PATH, Canned(200, tokenBody("a2", "r2")))

        assertEquals(Fetch.Ok(AccessVerdict.LOCKED), auth().verdict())
        assertEquals(1, server.calls(REFRESH_PATH))
        assertEquals(2, server.calls(VERDICT))
        assertEquals("Bearer a2", server.requests.last().header("Authorization"))
        assertEquals("r2", store.load()!!.refreshToken)
    }

    @Test
    fun `a refused refresh returns SessionRejected, while a 503 or no network returns Failed and keeps the session`() =
        runBlocking {
            storeSession(expiresAtMs = clock)
            val kept = store.load()
            server.on(
                REFRESH_PATH,
                Canned(503, "unavailable"),
                null,
                Canned(401, """{"message":"Invalid API key"}"""),
                Canned(400, """{"code":400,"error_code":"refresh_token_already_used","msg":"Already Used"}"""),
                Canned(400, """{"error":"invalid_grant","error_description":"Refresh Token Not Found"}"""),
            )
            val auth = auth()

            assertEquals(Fetch.Failed, auth.playlists())
            assertEquals(kept, store.load())
            assertEquals(Fetch.Failed, auth.verdict())
            assertEquals(kept, store.load())
            assertEquals(Fetch.Failed, auth.playlists())
            assertEquals(kept, store.load())
            assertEquals(Fetch.SessionRejected, auth.playlists())
            assertEquals(Fetch.SessionRejected, auth.verdict())
            // Clearing is the caller's sign-out path, not this class.
            assertEquals(kept, store.load())
            assertEquals(0, server.calls(PLAYLIST) + server.calls(VERDICT))
        }

    // --- reads ---------------------------------------------------------------------------------

    @Test
    fun `a playlist body that is not an array of complete rows returns Failed`() = runBlocking {
        storeSession(expiresAtMs = clock + 600_000)
        server.on(
            PLAYLIST,
            Canned(200, """{"client_number":"C1"}"""),
            Canned(200, """[{"client_number":"C1","name":"Home","server_url":"http://x","username":"u"}]"""),
            Canned(200, """[{"client_number":"C1","name":"Home","server_url":"http://x","username":"u","password":null}]"""),
            Canned(200, """[{"client_number":7,"name":"Home","server_url":"http://x","username":"u","password":"p"}]"""),
            Canned(200, "not json"),
            Canned(500, rowsBody),
            null,
            Canned(200, "[]"),
        )
        val auth = auth()

        repeat(7) { assertEquals(Fetch.Failed, auth.playlists()) }
        assertEquals(Fetch.Ok(emptyList<PlaylistRow>()), auth.playlists())
    }

    @Test
    fun `verdict accepts only the three known strings`() = runBlocking {
        storeSession(expiresAtMs = clock + 600_000)
        server.on(
            VERDICT,
            Canned(200, "\"NO_ROW\""),
            Canned(200, "\"MAYBE\""),
            Canned(400, """{"code":"P0001","message":"not authenticated"}"""),
            Canned(200, "ALLOWED"),
        )
        val auth = auth()

        assertEquals(Fetch.Ok(AccessVerdict.NO_ROW), auth.verdict())
        assertEquals(Fetch.Failed, auth.verdict())
        assertEquals(Fetch.Failed, auth.verdict())
        assertEquals(Fetch.Failed, auth.verdict())
        assertEquals("POST", server.requests.first().method)
    }

    @Test
    fun `reads with no stored session return SignedOut without a call`() = runBlocking {
        val auth = auth()

        assertEquals(Fetch.SignedOut, auth.playlists())
        assertEquals(Fetch.SignedOut, auth.verdict())
        assertTrue(server.requests.isEmpty())
    }

    // --- restart and sign-out ------------------------------------------------------------------

    @Test
    fun `a second KevBoxAuth over the same store starts signed in, and an undecryptable blob starts signed out`() =
        runBlocking {
            server.on(TOKEN, Canned(200, tokenBody("a1", "r1")))
            auth().signIn("member@example.com", "secret")
            val callsAfterSignIn = server.requests.size

            val restarted = KevBoxAuth(api(), PrefsSessionStore(prefs, cipher)) { clock }
            assertEquals("member@example.com", restarted.email.value)
            assertEquals(callsAfterSignIn, server.requests.size)

            cipher.broken = true
            val keyLost = KevBoxAuth(api(), PrefsSessionStore(prefs, cipher)) { clock }
            assertNull(keyLost.email.value)
            assertEquals(Fetch.SignedOut, keyLost.playlists())
        }

    @Test
    fun `the session is stored as one encrypted blob`() {
        storeSession(expiresAtMs = 42)

        assertEquals(1, prefs.values.size)
        val blob = prefs.values.values.single() as String
        assertTrue(!blob.contains("old-refresh"))
        assertNotNull(store.load())
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun `sign-out logs this TV out locally, and still clears the session offline`() = runBlocking {
        storeSession(expiresAtMs = clock + 600_000)
        server.on(LOGOUT, Canned(204, ""))
        val online = auth()

        online.signOut()

        val logout = server.requests.single()
        assertEquals("local", logout.url.queryParameter("scope"))
        assertEquals("Bearer old-access", logout.header("Authorization"))
        assertNull(store.load())
        assertNull(online.email.value)

        storeSession(expiresAtMs = clock + 600_000)
        val offline = auth()
        offline.signOut() // no canned logout: the call fails as no network
        assertNull(store.load())
        assertNull(offline.email.value)
    }

    @Test
    fun `a refresh that finishes after signOut does not write a session back`() = runBlocking {
        storeSession(expiresAtMs = clock + 600_000)
        val refreshArrived = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        val logoutArrived = CountDownLatch(1)
        server.on(PLAYLIST, Canned(401, """{"message":"JWT expired"}"""), Canned(200, rowsBody))
        server.on(REFRESH_PATH, Canned(200, tokenBody("a2", "r2"), arrived = refreshArrived, hold = releaseRefresh))
        server.on(LOGOUT, Canned(204, "", arrived = logoutArrived))
        val auth = auth()

        val read = async(Dispatchers.Default) { auth.playlists() }
        assertTrue(refreshArrived.await(5, TimeUnit.SECONDS))
        val signOut = async(Dispatchers.Default) { auth.signOut() }
        assertTrue(logoutArrived.await(5, TimeUnit.SECONDS))
        // Sign-out waits for the refresh in flight instead of clearing under it.
        assertNull(withTimeoutOrNull(200) { signOut.await() })
        releaseRefresh.countDown()
        read.await()
        signOut.await()

        assertNull(store.load())
        assertNull(auth.email.value)
    }

    @Test
    fun `sign-out refreshes a token about to expire before logging out`() = runBlocking {
        storeSession(expiresAtMs = clock + 10_000)
        server.on(REFRESH_PATH, Canned(200, tokenBody("a2", "r2")))
        server.on(LOGOUT, Canned(204, ""))

        auth().signOut()

        assertEquals("Bearer a2", server.requests.last().header("Authorization"))
        assertNull(store.load())
    }

    private companion object {
        const val BASE = "https://test.supabase.local"
        const val ANON = "anon-key"
        const val TOKEN = "/auth/v1/token"
        const val REFRESH_PATH = "/auth/v1/token"
        const val PLAYLIST = "/rest/v1/member_playlist"
        const val VERDICT = "/rest/v1/rpc/get_access_verdict"
        const val LOGOUT = "/auth/v1/logout"
    }
}
