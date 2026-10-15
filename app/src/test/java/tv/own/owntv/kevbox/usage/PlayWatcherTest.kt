// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.usage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.kevbox.errors.ErrorEntry
import tv.own.owntv.kevbox.errors.PlaybackScene
import tv.own.owntv.kevbox.errors.SceneSnapshot
import tv.own.owntv.player.LivePreviewEngine

class PlayWatcherTest {

    private val scope = CoroutineScope(SupervisorJob())

    @After
    fun tearDown() {
        scope.cancel()
    }

    // --- PlayRun -------------------------------------------------------------------------------

    @Test
    fun `30 seconds of play proves a run once`() {
        val run = PlayRun()
        run.onView(playing(ONE), 0)

        run.onView(playing(ONE), 29_900)
        assertFalse(run.proven(29_900))
        run.onView(playing(ONE), 30_000)
        assertTrue(run.proven(30_000))
        // AC-1: however long it plays on, that run never proves again.
        run.onView(playing(ONE), 90_000)
        assertFalse(run.proven(90_000))
    }

    @Test
    fun `a failure, stop or channel change at 29 s resets the count`() {
        val ends = listOf(
            PlayView(ONE, playing = true, failed = true, catchup = false),
            PlayView(ONE, playing = false, failed = false, catchup = false),
            playing(TWO),
        )
        ends.forEach { end ->
            val run = PlayRun()
            run.onView(playing(ONE), 0)
            run.onView(end, 29_000)
            assertFalse(run.proven(29_000))

            // AC-2: the next channel counts from 0, so 30 s from its own start at 29 s, not from the first.
            run.onView(playing(TWO), 29_000)
            run.onView(playing(TWO), 30_000)
            assertFalse(run.proven(30_000))
            run.onView(playing(TWO), 58_999)
            assertFalse(run.proven(58_999))
            run.onView(playing(TWO), 59_000)
            assertTrue("after $end", run.proven(59_000))
        }
    }

    @Test
    fun `a failure recorded while a proven run plays on proves it again 30 s later`() {
        // The startup channel proves at 30 s, a channel-list sync fails at 60 s, the same channel plays on.
        val run = PlayRun()
        run.onView(playing(ONE), 0)
        run.onView(playing(ONE), 30_000)
        assertTrue(run.proven(30_000))

        run.onView(playing(ONE), 60_000)
        run.onFailureRecorded(60_000)
        run.onView(playing(ONE), 89_999)
        assertFalse(run.proven(89_999))
        run.onView(playing(ONE), 90_000)
        assertTrue(run.proven(90_000))
        run.onView(playing(ONE), 200_000)
        assertFalse(run.proven(200_000))
    }

    @Test
    fun `a failure recorded with no run playing starts nothing`() {
        val run = PlayRun()
        run.onFailureRecorded(0)
        assertFalse(run.running)
        assertFalse(run.proven(60_000))
    }

    @Test
    fun `the full-screen player counts only while a frame shows`() {
        // Core keeps isPlaying true from the start of an mpv load through its retries; buffering is the tell.
        assertFalse(playerLook(isPlaying = true, buffering = true, failed = false).playing)
        assertFalse(playerLook(isPlaying = false, buffering = false, failed = false).playing)
        assertTrue(playerLook(isPlaying = true, buffering = false, failed = false).playing)
        assertTrue(playerLook(isPlaying = true, buffering = false, failed = true).failed)

        val scene = SceneSnapshot(channel = "One", category = "News", streamUrl = ONE, inBox = false, catchup = false)
        val stuck = playLookOf(scene, IDLE, playerLook(isPlaying = true, buffering = true, failed = false), playerLive = true)
        val run = PlayRun()
        run.onView(stuck.view, 0)
        run.onView(stuck.view, 30_000)
        assertFalse(run.proven(30_000))
    }

    @Test
    fun `the Live engine counts only in its PLAYING state`() {
        assertTrue(liveEngineLook(LivePreviewEngine.State.PLAYING, failed = false).playing)
        listOf(LivePreviewEngine.State.IDLE, LivePreviewEngine.State.LOADING, LivePreviewEngine.State.ERROR)
            .forEach { assertFalse("$it", liveEngineLook(it, failed = false).playing) }
        assertTrue(liveEngineLook(LivePreviewEngine.State.PLAYING, failed = true).failed)
    }

    @Test
    fun `catch-up never proves, live on mpv full screen does`() {
        val catchup = PlayRun()
        for (at in 0L..60_000L step 1_000L) {
            catchup.onView(PlayView(ONE, playing = true, failed = false, catchup = true), at)
            assertFalse(catchup.proven(at))
        }

        // Full screen on mpv: the Live engine is stopped, the player plays live, there is no video site.
        val scene = SceneSnapshot(channel = "One", category = "News", streamUrl = ONE, inBox = false, catchup = false)
        val look = playLookOf(scene, liveEngine = IDLE, player = PLAYS, playerLive = true)
        assertEquals(PlaySource.PLAYER, look.source)
        val run = PlayRun()
        run.onView(look.view, 0)
        run.onView(look.view, 30_000)
        assertTrue(run.proven(30_000))

        // The same player playing a film or catch-up, or a scene with no channel, is not live play.
        assertFalse(playLookOf(scene, IDLE, PLAYS, playerLive = false).view.playing)
        assertEquals(null, playLookOf(scene.copy(channel = null, streamUrl = null), PLAYS, PLAYS, true).source)
        assertTrue(playLookOf(scene.copy(catchup = true), IDLE, PLAYS, true).view.catchup)
    }

    @Test
    fun `the box counts the Live engine and an engine failure ends the run`() {
        val scene = SceneSnapshot(channel = "One", category = "News", streamUrl = ONE, inBox = true, catchup = false)

        val box = playLookOf(scene, liveEngine = PLAYS, player = IDLE, playerLive = false)
        assertEquals(PlaySource.LIVE_ENGINE, box.source)
        assertTrue(box.view.playing)

        // A stale failure on the full-screen player says nothing about the box.
        assertTrue(playLookOf(scene, PLAYS, EngineLook(playing = false, failed = true), false).view.playing)

        val failed = playLookOf(scene, EngineLook(playing = true, failed = true), IDLE, false)
        assertFalse(failed.view.playing)
        assertTrue(failed.view.failed)
    }

    // --- PlayWatcher ---------------------------------------------------------------------------

    @Test
    fun `a failure recorded by the reporter reaches PlayNotes`() {
        val listeners = mutableListOf<(ErrorEntry) -> Unit>()
        var failures = 0
        var notesStarted = 0
        val watcher = PlayWatcher(
            onPlayed = {},
            onFailureRecorded = { failures++ },
            startNotes = { notesStarted++ },
            addFailureListener = { listeners += it },
            scene = PlaybackScene(),
            // The scene never shows, so the engines are never built.
            livePreview = lazy { error("no Live engine in a test") },
            player = lazy { error("no player in a test") },
            scope = scope,
            now = { 0L },
        )

        watcher.start()
        watcher.start()

        assertEquals(1, listeners.size)
        assertEquals(1, notesStarted)
        listeners.single().invoke(entry())
        assertEquals(1, failures)
    }

    private fun entry() = ErrorEntry(
        firstAtMs = 0, lastAtMs = 0, repeatCount = 1, code = "http_404", httpStatus = 404,
        errorName = null, accountStatus = null, activeCons = null, maxConnections = null,
        engine = null, surface = null, live = true, channel = null, category = null,
        providerHost = null, proxy = null, model = null, android = null, messageShort = null,
    )

    private companion object {
        const val ONE = "http://panel.example/live/1.ts"
        const val TWO = "http://panel.example/live/2.ts"
        val IDLE = EngineLook(playing = false, failed = false)
        val PLAYS = EngineLook(playing = true, failed = false)

        fun playing(key: String) = PlayView(key, playing = true, failed = false, catchup = false)
    }
}
