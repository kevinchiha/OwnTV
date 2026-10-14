package tv.own.owntv.kevbox.errors

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
    private val kicks = AtomicInteger(0)
    private var details: () -> XtAccountDetails = { XtAccountDetails(status = "Active") }
    private var secrets: Set<String> = emptySet()

    private val queue by lazy { ErrorQueue(File(folder.root, "errors.json"), now = { NOW }) }

    private fun reporter(fetch: suspend (SourceEntity) -> XtAccountDetails = { details() }) = ErrorReporter(
        queue = queue,
        kick = { kicks.incrementAndGet() },
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

    /** Waits up to 5 seconds for the sender kick count to reach [n]; the worker kicks after the listeners. */
    private fun awaitKicks(n: Int) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (kicks.get() < n && System.nanoTime() < until) Thread.sleep(10)
        assertEquals(n, kicks.get())
    }

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
        // One queued entry, one kick to the sender.
        awaitKicks(1)
        Thread.sleep(100)
        assertEquals(1, kicks.get())
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
            assertFalse(field, field.contains(SOURCE.username!!))
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

    @Test
    fun `Core's give-up reaches the classifier as stream_lost`() {
        val reporter = reporter()
        reporter.start()

        val codec = "ERROR_CODE_DECODING_FAILED: MediaCodecVideoRenderer\$MediaCodecVideoDecoderException"
        reporter.record(playback(Engine.EXO, codec, lost = true))
        val entry = nextEntry()

        assertEquals(LiveErrorCodes.STREAM_LOST, entry.code)
        // stream_lost runs the account check (AC-13).
        assertEquals(AccountStatus.ACTIVE, entry.accountStatus)
    }

    @Test
    fun `a long first line is scrubbed before it is cut`() {
        secrets = setOf(SOURCE.password!!)
        val reporter = reporter()
        reporter.start()

        // The password starts before character 120 and ends after it.
        val raw = "x".repeat(115) + SOURCE.password + " refused"
        reporter.record(Failure.Guide("http://epg.example.test/guide.xml", raw))
        val name = nextEntry().errorName!!

        assertEquals(120, name.length)
        assertFalse(name, name.contains("s3cr"))
    }

    private fun playback(engine: Engine, raw: String?, inBox: Boolean = false, catchup: Boolean = false, lost: Boolean = false) =
        Failure.Playback(engine, raw, null, SceneSnapshot("BBC One", "UK", STREAM_URL, inBox, catchup), lost)

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
