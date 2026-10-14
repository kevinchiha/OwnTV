package tv.own.owntv.kevbox.errors

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.parser.XtreamClient.XtAccountDetails

class ErrorReporterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queued = LinkedBlockingQueue<ErrorEntry>()
    private var kicks = 0
    private var details: () -> XtAccountDetails = { XtAccountDetails(status = "Active") }
    private var secrets: Set<String> = emptySet()

    private val queue by lazy { ErrorQueue(File(folder.root, "errors.json"), now = { NOW }) }

    private fun reporter(fetch: suspend (SourceEntity) -> XtAccountDetails = { details() }) = ErrorReporter(
        queue = queue,
        kick = { kicks++ },
        startSender = {},
        accountCheck = AccountCheck(fetch = fetch, now = { NOW }),
        currentSource = { SOURCE },
        sourceById = { id -> SOURCE.takeIf { it.id == id } },
        proxyUsable = { false },
        isOnline = { true },
        secretChanges = emptyFlow(),
        readSecrets = { secrets },
        scope = scope,
        now = { NOW },
        model = { "AFTMM" },
        android = { "9" },
    ).also { it.addListener { entry -> queued.put(entry) } }

    @After
    fun tearDown() {
        scope.cancel()
        SecretWords.set(emptySet())
    }

    /** The next entry the worker queued, or a failure after 5 seconds. */
    private fun nextEntry(): ErrorEntry = queued.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no entry queued")

    @Test
    fun `record returns while the worker is busy`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reporter = reporter(fetch = {
            entered.countDown()
            release.await()
            details()
        })
        reporter.start()

        reporter.record(playback(Engine.EXO, "ERROR_CODE_IO_BAD_HTTP_STATUS: Response code: 401"))
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val startedAt = System.nanoTime()
        repeat(100) { reporter.record(playback(Engine.EXO, "ERROR_CODE_IO_BAD_HTTP_STATUS: Response code: 401")) }
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue("record took $tookMs ms", tookMs < 500)
        assertTrue(queued.isEmpty())

        release.countDown()
        nextEntry()
    }

    @Test
    fun `a 401 on an expired account queues account_expired with status 401 and the counts`() {
        details = { XtAccountDetails(status = "Expired", authOk = false, activeConnections = 1, maxConnections = 2) }
        val reporter = reporter()
        reporter.start()

        reporter.record(playback(Engine.EXO, "ERROR_CODE_IO_BAD_HTTP_STATUS: Response code: 401", inBox = true))
        val entry = nextEntry()

        assertEquals(LiveErrorCodes.ACCOUNT_EXPIRED, entry.code)
        assertEquals(401, entry.httpStatus)
        assertEquals(AccountStatus.EXPIRED, entry.accountStatus)
        assertEquals(1, entry.activeCons)
        assertEquals(2, entry.maxConnections)
        assertEquals("exo", entry.engine)
        assertEquals("preview", entry.surface)
        assertEquals(true, entry.live)
        assertEquals("panel.example.test", entry.providerHost)
        assertEquals(false, entry.proxy)
        assertEquals("AFTMM", entry.model)
        assertEquals(1, entry.repeatCount)
        assertEquals(NOW, entry.firstAtMs)
        assertEquals(1, queue.unsentCount())
        assertEquals(LiveErrorCodes.ACCOUNT_EXPIRED, queue.takeBatch(10).single().entry.code)
    }

    @Test
    fun `fields leaving the TV are scrubbed`() {
        secrets = setOf(SOURCE.password!!)
        val reporter = reporter()
        reporter.start()

        val raw = "Failed to open ${STREAM_URL}: login s3cretpass refused"
        reporter.record(playback(Engine.MPV, raw, catchup = true))
        val entry = nextEntry()

        listOf(entry.messageShort, entry.errorName).forEach { field ->
            assertNotNull(field)
            assertFalse(field, field!!.contains("s3cretpass"))
            assertFalse(field, field.contains("/live/"))
            assertFalse(field, field.contains("panel.example.test"))
        }
        assertEquals("mpv", entry.engine)
        assertEquals("full", entry.surface)
        assertEquals(false, entry.live)
        assertTrue(SecretWords.current().contains("s3cretpass"))
    }

    @Test
    fun `a listener that throws does not stop the worker`() {
        val reporter = reporter()
        reporter.addListener { throw IllegalStateException("boom") }
        reporter.start()

        reporter.record(Failure.Guide("http://epg.example.test/guide.xml", "empty guide"))
        reporter.record(Failure.Guide("http://epg.example.test/guide.xml", "xml parse error"))

        assertEquals(LiveErrorCodes.GUIDE_EMPTY, nextEntry().code)
        val second = nextEntry()
        assertEquals(LiveErrorCodes.GUIDE_PARSE_FAILED, second.code)
        assertEquals("epg.example.test", second.providerHost)
    }

    @Test
    fun `the account check runs only where the status hides the reason`() {
        val ok = Classified(LiveErrorCodes.OTHER, null, null)
        assertTrue(needsAccountCheck(playback(Engine.EXO, null), Classified("http_403", 403, null)))
        assertTrue(needsAccountCheck(playback(Engine.EXO, null), Classified(LiveErrorCodes.STREAM_LOST, null, null)))
        assertFalse(needsAccountCheck(playback(Engine.EXO, null), Classified("http_500", 500, null)))
        assertFalse(needsAccountCheck(playback(Engine.EXO, null), ok))
        assertTrue(needsAccountCheck(playback(Engine.MPV, null), Classified(LiveErrorCodes.NET_TIMEOUT, null, null)))
        assertFalse(needsAccountCheck(playback(Engine.MPV, null), Classified("http_500", 500, null)))
        assertTrue(needsAccountCheck(Failure.Playlist(SOURCE.id, null), ok))
        assertFalse(needsAccountCheck(Failure.Guide(null, null), Classified("http_401", 401, null)))
    }

    private fun playback(engine: Engine, raw: String?, inBox: Boolean = false, catchup: Boolean = false) =
        Failure.Playback(engine, raw, null, SceneSnapshot("BBC One", "UK", STREAM_URL, inBox, catchup))

    companion object {
        const val NOW = 1_800_000_000_000L
        const val STREAM_URL = "http://panel.example.test:8080/live/member/s3cretpass/42.ts"

        val SOURCE = SourceEntity(
            id = 3,
            name = "KevBox",
            type = SourceType.XTREAM,
            url = "http://panel.example.test:8080",
            username = "member",
            password = "s3cretpass",
        )
    }
}
