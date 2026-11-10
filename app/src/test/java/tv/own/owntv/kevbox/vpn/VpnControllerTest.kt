// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.vpn

import android.app.Application
import android.content.SharedPreferences
import java.io.File
import java.net.UnknownHostException
import java.time.ZoneId
import java.util.Collections
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.own.owntv.kevbox.errors.DeviceId
import tv.own.owntv.kevbox.session.KevBoxAuth
import tv.own.owntv.kevbox.session.PrefsSessionStore
import tv.own.owntv.kevbox.session.StoredSession
import tv.own.owntv.kevbox.session.TokenCipher
import tv.own.owntv.kevbox.supabase.SupabaseApi
import tv.own.owntv.kevbox.supabase.VPN_AUTO

class VpnControllerTest {

    /** kevbox.dev as the runner sees it: the vpn_server rows in [servers], and the event RPC refusing. */
    private class FakeServer : Interceptor {
        /** host to config; a host missing here has no row. */
        val servers = linkedMapOf<String, String>()
        val serverReads: MutableList<String?> = Collections.synchronizedList(mutableListOf())

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val (code, body) = when (request.url.encodedPath) {
                SERVERS_PATH -> {
                    val filter = request.url.queryParameter("host")
                    serverReads += filter
                    val wanted = filter?.removePrefix("in.(")?.removeSuffix(")")?.split(",")
                    val rows = JSONArray()
                    servers.entries.forEachIndexed { index, (host, config) ->
                        if (wanted == null || host in wanted) {
                            rows.put(
                                JSONObject()
                                    .put("host", host)
                                    .put("label", host)
                                    .put("config", config)
                                    .put("backup_host", JSONObject.NULL)
                                    .put("sort_order", index + 1),
                            )
                        }
                    }
                    200 to rows.toString()
                }
                // Events stay queued, so a test reads them there.
                EVENTS_PATH -> 500 to ""
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

    /** Records every start and stop; emits only what a test sends, never after a stop. */
    private class FakeEngine : VpnEngine {
        val starts: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var stops = 0
        override val events: SharedFlow<EngineEvent> = MutableSharedFlow()

        override fun start(host: String, config: String, username: String, password: String) {
            starts += host
        }

        override fun stop() {
            stops += 1
        }
    }

    private class FakePinger(private val averages: Map<String, Long?>) : Pinger {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun averageMs(host: String): Long? {
            calls += host
            return averages[host]
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
        override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
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
    private val engine = FakeEngine()
    private val crashes: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, e -> crashes += e })
    private val api = SupabaseApi(OkHttpClient.Builder().addInterceptor(server).build(), BASE, "anon-key")

    @Volatile private var clock = 1_800_000_000_000L
    @Volatile private var otherVpn = false

    private val queue by lazy { VpnEventQueue(File(folder.root, VPN_EVENTS_FILE)) }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun controller(pinger: Pinger = FakePinger(emptyMap())): VpnController {
        val sessions = PrefsSessionStore(MemoryPrefs(), PlainCipher())
        sessions.save(StoredSession(USER, "$USER@example.com", "a-$USER", "r-$USER", clock + 100 * HOUR))
        val auth = KevBoxAuth(api, sessions) { clock }
        val sender = VpnEventSender(queue, auth, api, DeviceId(MemoryPrefs()), "99.99.99.99", scope, { clock }, { ZoneId.of("UTC") })
        return VpnController(
            engine = engine,
            auth = auth,
            pinger = pinger,
            store = FallbackStore(MemoryPrefs()),
            queue = queue,
            sender = sender,
            application = Application(),
            scope = scope,
            now = { clock },
            otherVpnActive = { otherVpn },
            permissionGranted = { true },
        ).also {
            it.start()
            // Past the start's linger, so a first Connect neither waits nor takes a VPN for ours.
            clock += 10 * SECOND
        }
    }

    private fun want(server: String, backup: String? = null) =
        VpnWant(clientNumber = "C1", server = server, backup = backup, username = "vu", password = "vp", changedAt = "c1")

    private suspend fun VpnController.drive(vararg inputs: VpnInput) {
        inputs.forEach { feed(it) }
        withTimeout(5_000) { settle() }
    }

    private suspend fun VpnController.hand(want: VpnWant) {
        wanted(want)
        withTimeout(5_000) { settle() }
    }

    private fun queuedKinds() = queue.oldest(VPN_BATCH_MAX, USER).map { it.kind }

    @Test
    fun `a missing config counts as a failed try`() = runBlocking {
        server.servers[NL] = "nl-config"
        val vpn = controller()

        vpn.drive(VpnInput.Foreground)
        vpn.hand(want(DE))

        // The config read asked for vpn-de alone and found no row: a failed try, nothing started (AC-24).
        assertEquals(listOf<String?>("in.($DE)"), server.serverReads.toList())
        assertEquals(emptyList<String>(), engine.starts.toList())
        assertEquals(VpnPhase.Waiting(clock + RETRY_WAIT_MS), vpn.state.phase)
        assertEquals(clock + RETRY_WAIT_MS, vpn.state.dueAtMs)
        assertEquals(1, vpn.state.cycle?.failures)
        assertEquals(emptyList<Throwable>(), crashes.toList())
    }

    @Test
    fun `another VPN at a retry's connect starts nothing`() = runBlocking {
        server.servers[DE] = "de-config"
        val vpn = controller()

        vpn.drive(VpnInput.Foreground)
        vpn.hand(want(DE))
        assertEquals(listOf(DE), engine.starts.toList())

        vpn.drive(VpnInput.EngineFailed)
        assertEquals(1, engine.stops)
        clock += RETRY_WAIT_MS
        otherVpn = true
        vpn.drive(VpnInput.Tick)

        // The retry found another app's VPN: nothing started, one event, no fall-back (AC-29).
        assertEquals(listOf(DE), engine.starts.toList())
        assertEquals(listOf(VpnEventKind.OTHER_VPN_ACTIVE), queuedKinds())
        assertEquals(VpnPhase.Idle, vpn.state.phase)
        assertEquals(emptyList<Throwable>(), crashes.toList())
    }

    @Test
    fun `auto pings every server once at cycle start and connects the fastest`() = runBlocking {
        server.servers[DE] = "de-config"
        server.servers[NL] = "nl-config"
        val pinger = FakePinger(mapOf(DE to 40L, NL to 25L))
        val vpn = controller(pinger)

        vpn.drive(VpnInput.Foreground)
        vpn.hand(want(VPN_AUTO))

        assertEquals(listOf(DE, NL), pinger.calls.sorted())
        assertEquals(listOf(NL), engine.starts.toList())
        assertEquals(NL, vpn.state.cycle?.main)
        assertEquals(DE, vpn.state.cycle?.backup)
        // The list read went out unfiltered, then the connect read nl's config alone.
        assertEquals(listOf(null, "in.($NL)"), server.serverReads.toList())

        // Two failures on nl in the same cycle: the retries ping nothing (AC-41).
        vpn.drive(VpnInput.EngineFailed)
        clock += RETRY_WAIT_MS
        vpn.drive(VpnInput.Tick)
        assertEquals(listOf(NL, NL), engine.starts.toList())
        vpn.drive(VpnInput.EngineFailed)

        assertEquals(2, pinger.calls.size)
        assertEquals(2, vpn.state.cycle?.failures)
        assertTrue(vpn.state.phase is VpnPhase.Waiting)
        assertEquals(emptyList<Throwable>(), crashes.toList())
    }

    @Test
    fun `an engine that comes up reports connected for the signed-in member`() = runBlocking {
        server.servers[DE] = "de-config"
        val vpn = controller()

        vpn.drive(VpnInput.Foreground)
        vpn.hand(want(DE))
        vpn.drive(VpnInput.EngineUp)

        assertEquals(VpnPhase.Up(DE), vpn.state.phase)
        val event = queue.oldest(VPN_BATCH_MAX, USER).single()
        assertEquals(VpnEventKind.CONNECTED, event.kind)
        assertEquals(DE, event.server)
        assertEquals("C1", event.clientNumber)
        assertEquals(clock, event.atMs)
    }

    private companion object {
        const val USER = "user-a"
        const val SECOND = 1_000L
        const val HOUR = 3_600_000L
        const val DE = "vpn-de.premiumize.me"
        const val NL = "vpn-nl.premiumize.me"
        const val BASE = "https://test.supabase.local"
        const val SERVERS_PATH = "/rest/v1/vpn_server"
        const val EVENTS_PATH = "/rest/v1/rpc/record_live_vpn_events"
    }
}
