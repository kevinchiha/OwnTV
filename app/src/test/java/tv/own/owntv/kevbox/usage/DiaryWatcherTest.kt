// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.usage

import java.io.File
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.parser.XtEpgEntry
import tv.own.owntv.kevbox.errors.PlaybackScene

class DiaryWatcherTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob())

    @After
    fun tearDown() {
        scope.cancel()
    }

    private val file: File get() = File(folder.root, "diary.json")
    private val openFile: File get() = File(folder.root, "diary_open.json")

    // The wall clock and the monotonic one; tests move them apart to fake a clock correction.
    // Volatile: a started watcher reads them from its own threads.
    @Volatile private var wall = AT_20
    @Volatile private var steady = 5_000L
    @Volatile private var member: String? = USER
    private var sends = 0
    private var guideEntry: XtEpgEntry? = null
    private val scene = PlaybackScene()
    // Lazy: the temporary folder exists only once the rule has run.
    private val queue by lazy { DiaryQueue(file, openFile) }
    private val lastPress = LastPress { wall }
    private val looks = AtomicInteger()
    private var ids = 0
    // The watcher's cutter, kept so a test can wait until a started watcher's look has opened a stretch.
    private var cutter: StretchCutter? = null

    private fun watcher(
        engines: Flow<EngineLooks> = emptyFlow(),
        guide: suspend (ChannelEntity) -> XtEpgEntry? = { guideEntry },
    ) = DiaryWatcher(
        cutter = StretchCutter { "stretch-${++ids}" }.also { cutter = it },
        queue = queue,
        startSender = {},
        sendNow = { sends++ },
        lastPress = lastPress,
        guide = guide,
        userId = { member },
        scene = scene,
        engines = { engines },
        // Read once per look: the count tells a test that a started watcher has looked.
        playerLive = {
            looks.incrementAndGet()
            false
        },
        proxy = { true },
        registerCallbacks = {},
        scope = scope,
        now = { wall },
        elapsed = { steady },
    )

    /** Waits up to 10 s for [done]; the assertions after it tell whether it came. */
    private fun waitFor(done: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!done() && System.currentTimeMillis() < until) Thread.sleep(10)
    }

    private fun advance(ms: Long) {
        wall += ms
        steady += ms
    }

    private fun show(streamUrl: String = LOGIN_URL, inBox: Boolean = false, catchup: Boolean = false) {
        val channel = ChannelEntity(id = 7, sourceId = 1, name = "One", streamUrl = streamUrl, number = 1)
        scene.update(channel, "News", inBox = inBox, catchup = catchup)
    }

    @Test
    fun `an engine failure closes the stretch at once`() = runBlocking {
        show()
        val w = watcher()
        w.look(LIVE_ENGINE_PLAYS)
        advance(5 * MIN)
        w.look(LIVE_ENGINE_PLAYS.copy(liveEngine = EngineLook(playing = false, failed = true)))

        // A stop would wait out the stall grace; a failure closes now, with no background (AC-2).
        val s = queue.oldest(10, USER).single()
        assertEquals(AT_20 + 5 * MIN, s.endedAtMs)
    }

    @Test
    fun `catch-up starting closes the stretch at once`() = runBlocking {
        show()
        val w = watcher()
        w.look(LIVE_ENGINE_PLAYS)
        advance(5 * MIN)
        show(catchup = true)
        w.look(LIVE_ENGINE_PLAYS)

        val s = queue.oldest(10, USER).single()
        assertEquals(AT_20 + 5 * MIN, s.endedAtMs)
        // Catch-up opens nothing: the background close adds no second stretch (AC-3).
        advance(5 * MIN)
        w.background()
        assertEquals(1, queue.size())
    }

    @Test
    fun `a look cancelled mid guide read never gives the new channel the old title`() = runBlocking {
        var readFails = false
        val w = watcher(guide = { channel ->
            if (readFails) throw CancellationException("the next look started")
            val title = if (channel.streamUrl == OTHER_URL) "Two" else "One"
            XtEpgEntry(title, "", AT_20 - 10 * MIN, AT_20 + 120 * MIN)
        })
        show()
        w.look(LIVE_ENGINE_PLAYS)
        advance(MIN)

        show(streamUrl = OTHER_URL)
        readFails = true
        try {
            w.look(LIVE_ENGINE_PLAYS)
            fail("the cancelled look carried on")
        } catch (_: CancellationException) {
        }
        readFails = false
        w.look(LIVE_ENGINE_PLAYS)
        advance(MIN)
        w.background()

        assertEquals(listOf("One", "Two"), queue.oldest(10, USER).map { it.programme })
    }

    @Test
    fun `coming back to the app looks again with the engines unchanged`() = runBlocking {
        // Catch-up on screen: the first look opens nothing.
        show(catchup = true)
        val w = watcher(engines = MutableStateFlow(LIVE_ENGINE_PLAYS))
        w.start()
        waitFor { looks.get() >= 1 }
        assertEquals(1, looks.get())

        // Live again while away. The engines still say playing, so they send no change.
        show()
        advance(MIN)
        val back = wall
        w.onAppStarted()
        // Wait for the open stretch, not the look count: the count goes up before the cutter runs,
        // so a background close could beat the look and leave nothing to close.
        waitFor { cutter?.isOpen == true }
        assertTrue("the look on return opened no stretch", cutter?.isOpen == true)
        advance(5 * MIN)
        w.background()

        val s = queue.oldest(10, USER).single()
        assertEquals(back, s.startedAtMs)
        assertEquals(back + 5 * MIN, s.endedAtMs)
    }

    @Test
    fun `a sign-out mid stretch keeps it for the member it opened under`() = runBlocking {
        show()
        val w = watcher()
        w.look(LIVE_ENGINE_PLAYS)
        advance(5 * MIN)
        // Sign-out clears the member before the engines stop.
        member = null
        w.look(LIVE_ENGINE_PLAYS)
        w.background()

        val s = queue.oldest(10, USER).single()
        assertEquals(AT_20 + 5 * MIN, s.endedAtMs)
    }

    @Test
    fun `the entry carries the on-now title and no login`() = runBlocking {
        guideEntry = XtEpgEntry("Evening news", "", AT_20 - 10 * MIN, AT_20 + 60 * MIN)
        show()
        val w = watcher()
        w.look(LIVE_ENGINE_PLAYS)
        advance(30 * MIN)
        w.look(LIVE_ENGINE_PLAYS)
        w.background()

        val stretch = queue.oldest(10, USER).single()
        assertEquals("Evening news", stretch.programme)
        assertEquals(AT_20, stretch.startedAtMs)
        assertEquals(AT_20 + 30 * MIN, stretch.endedAtMs)
        val json = stretch.toEntry(ZoneOffset.UTC)
        assertEquals("host", json.getString("provider_host"))
        val text = json.toString()
        listOf("user", "pass", "u:p", LOGIN_URL, "/live/").forEach { assertFalse(it, text.contains(it)) }
        assertEquals(1, sends)
    }

    @Test
    fun `no guide gives a blank programme`() = runBlocking {
        show()
        val w = watcher()
        w.look(LIVE_ENGINE_PLAYS)
        advance(MIN)
        w.background()
        assertNull(queue.oldest(10, USER).single().programme)
    }

    @Test
    fun `the app start counts as a press and the stretch carries the press before Home`() = runBlocking {
        show()
        val w = watcher()
        advance(2_000)
        w.onAppStarted()
        val started = wall
        w.look(LIVE_ENGINE_PLAYS)
        advance(5 * MIN)
        // Home is no key event: the background close carries the app start.
        w.background()
        assertEquals(started, queue.oldest(10, USER).single().lastPressAtMs)
    }

    @Test
    fun `signed out, a finished stretch is dropped`() = runBlocking {
        member = null
        show()
        val w = watcher()
        w.look(LIVE_ENGINE_PLAYS)
        advance(5 * MIN)
        w.background()
        member = USER
        assertEquals(0, queue.size())
    }

    @Test
    fun `the open stretch is saved every 60 s and cleared by a normal close`() = runBlocking {
        show()
        val w = watcher()
        w.look(LIVE_ENGINE_PLAYS)
        advance(OPEN_SAVE_MS - 1_000)
        w.look(LIVE_ENGINE_PLAYS)
        assertFalse(openFile.exists())
        advance(1_000)
        w.look(LIVE_ENGINE_PLAYS)
        assertTrue(openFile.exists())

        // A killed app: the next start's queue finishes it at that save (AC-6). A copy, so this run goes on.
        val copy = File(folder.root, "copy_open.json")
        openFile.copyTo(copy)
        val recovered = DiaryQueue(File(folder.root, "other.json"), copy)
        recovered.recoverOpen()
        assertEquals(AT_20 + OPEN_SAVE_MS, recovered.oldest(1, USER).single().endedAtMs)

        advance(10_000)
        w.background()
        assertFalse(openFile.exists())
        assertEquals(1, queue.size())
    }

    @Test
    fun `a clock correction during a stretch never stretches it`() = runBlocking {
        show()
        val w = watcher()
        w.look(LIVE_ENGINE_PLAYS)
        steady += 20 * MIN
        // NTP moves the wall clock an hour on while 20 minutes play.
        wall += 80 * MIN
        w.background()
        val s = queue.oldest(1, USER).single()
        assertEquals(AT_20 + 80 * MIN, s.endedAtMs)
        assertEquals(20 * MIN, s.endedAtMs - s.startedAtMs)
    }

    @Test
    fun `the guide reads the stored now entry of the channel`() = runBlocking {
        val channel = ChannelEntity(id = 7, sourceId = 1, name = "One", streamUrl = LOGIN_URL, number = 1)
        val entry = XtEpgEntry("Match", "", 0, 1)
        var askedOffset: Int? = null
        val guide = GuideNow(
            programmes = { channels, _, offset ->
                askedOffset = offset
                channels.associate { it.id to entry }
            },
            customizations = { SectionCustomizations() },
            offsetMinutes = { 30 },
        )
        assertEquals(entry, guide.nowOn(channel))
        assertEquals(30, askedOffset)
        assertNull(GuideNow({ _, _, _ -> emptyMap() }, { SectionCustomizations() }, { 0 }).nowOn(channel))
    }

    private companion object {
        const val MIN = 60_000L
        const val AT_20 = 1_792_180_800_000L
        const val USER = "member-a"
        const val LOGIN_URL = "http://u:p@host/live/user/pass/1.ts"
        const val OTHER_URL = "http://u:p@host/live/user/pass/2.ts"
        val LIVE_ENGINE_PLAYS = EngineLooks(
            liveEngine = EngineLook(playing = true, failed = false),
            player = EngineLook(playing = false, failed = false),
        )
    }
}
