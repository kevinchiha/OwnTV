// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.errors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.epg.EpgSource
import tv.own.owntv.core.sync.SyncResult
import tv.own.owntv.core.sync.SyncRunStats
import tv.own.owntv.player.ErrorInfo
import tv.own.owntv.player.PlayerFailureReason

class ErrorWatchersTest {

    @Test
    fun `only a change from no failure to a failure counts`() {
        val first = ErrorInfo(PlayerFailureReason.DRM, null, "Response code: 403")
        val second = ErrorInfo(PlayerFailureReason.DRM, null, "Response code: 404")

        assertTrue(isNewFailure(null, first))
        assertFalse(isNewFailure(first, second))
        assertFalse(isNewFailure(first, first))
        assertFalse(isNewFailure(first, null))
        assertFalse(isNewFailure(null, null))
    }

    @Test
    fun `guide errors report standing errors once at start, then only new values`() {
        val start = listOf(feed(1, "HTTP 404"), feed(2, null), feed(3, ""))

        // First emission of the process: the feed already failing shows once.
        assertEquals(listOf(1L), newGuideErrors(emptyMap(), start).map { it.id })
        var previous = start.associate { it.id to it.lastError }

        // The same text written again on the next failure is not new.
        assertEquals(emptyList<Long>(), newGuideErrors(previous, start).map { it.id })

        // A new text, and a feed that starts failing, both are.
        val next = listOf(feed(1, "timeout"), feed(2, "HTTP 500"), feed(3, ""))
        assertEquals(listOf(1L, 2L), newGuideErrors(previous, next).map { it.id })
        previous = next.associate { it.id to it.lastError }

        // A success clears the error; the same text failing again afterwards counts again.
        val cleared = listOf(feed(1, null), feed(2, "HTTP 500"), feed(3, ""))
        assertEquals(emptyList<Long>(), newGuideErrors(previous, cleared).map { it.id })
        previous = cleared.associate { it.id to it.lastError }
        assertEquals(listOf(1L), newGuideErrors(previous, listOf(feed(1, "timeout"))).map { it.id })
    }

    @Test
    fun `a failed channel-list sync on the current source reports once per run`() {
        val failed = stats(finishedAt = 1_000, SyncResult.Failed("HTTP 401"))

        assertEquals(Failure.Playlist(SOURCE, "HTTP 401"), playlistFailureOf(SOURCE, failed, null, SOURCE))
        // The same run seen again, or an older one, is not new.
        assertNull(playlistFailureOf(SOURCE, failed, 1_000, SOURCE))
        assertEquals(Failure.Playlist(SOURCE, "HTTP 401"), playlistFailureOf(SOURCE, stats(2_000, SyncResult.Failed("HTTP 401")), 1_000, SOURCE))
    }

    @Test
    fun `a sync that worked, or one on another source, reports nothing`() {
        assertNull(playlistFailureOf(SOURCE, stats(1_000, SyncResult.Failed("x")), null, SOURCE + 1))
        assertNull(playlistFailureOf(SOURCE, stats(1_000, SyncResult.Failed("x")), null, null))
        assertNull(playlistFailureOf(SOURCE, null, null, SOURCE))
        assertNull(playlistFailureOf(SOURCE, stats(1_000, SyncResult.Cancelled), null, SOURCE))
    }

    @Test
    fun `an ExoPlayer failure Core hands to mpv records nothing`() {
        val hold = FallbackHold()
        assertEquals(emptyList<Failure.Playback>(), hold.hold(FULL_EXO, 0))
        assertEquals(emptyList<Failure.Playback>(), hold.onLadder(PlayerFailureReason.LIVE_FALLBACK, 500))
        assertEquals(emptyList<Failure.Playback>(), hold.due(10_000))
    }

    @Test
    fun `an ExoPlayer failure with no fallback records one entry`() {
        val hold = FallbackHold()
        assertEquals(emptyList<Failure.Playback>(), hold.hold(FULL_EXO, 0))
        // Other ladder steps say nothing about this failure.
        assertEquals(emptyList<Failure.Playback>(), hold.onLadder(PlayerFailureReason.MPV_HANDOFF, 100))
        assertEquals(listOf(FULL_EXO), hold.onLadder(PlayerFailureReason.LIVE_NO_FALLBACK, 500))
        assertEquals(emptyList<Failure.Playback>(), hold.due(10_000))
    }

    @Test
    fun `a failure no ladder event claims is recorded once the hold ends`() {
        val hold = FallbackHold()
        hold.hold(FULL_EXO, 0)
        assertEquals(emptyList<Failure.Playback>(), hold.due(FallbackHold.HOLD_MS - 1))
        assertEquals(listOf(FULL_EXO), hold.due(FallbackHold.HOLD_MS))
        assertEquals(emptyList<Failure.Playback>(), hold.due(FallbackHold.HOLD_MS * 2))
    }

    @Test
    fun `a ladder event that comes before its failure still decides it`() {
        val hold = FallbackHold()
        hold.onLadder(PlayerFailureReason.LIVE_FALLBACK, 0)
        assertEquals(emptyList<Failure.Playback>(), hold.hold(FULL_EXO, 300))
        assertEquals(emptyList<Failure.Playback>(), hold.due(10_000))

        hold.onLadder(PlayerFailureReason.LIVE_NO_FALLBACK, 20_000)
        assertEquals(listOf(FULL_EXO), hold.hold(FULL_EXO, 20_300))

        // An event older than the hold belongs to nothing; the next failure waits as usual.
        hold.onLadder(PlayerFailureReason.LIVE_FALLBACK, 30_000)
        assertEquals(emptyList<Failure.Playback>(), hold.hold(FULL_EXO, 30_000 + FallbackHold.HOLD_MS + 1))
        assertEquals(listOf(FULL_EXO), hold.due(40_000))
    }

    @Test
    fun `a preview failure records at once and a full-screen one waits for the ladder`() {
        val hold = FallbackHold()
        val box = FULL_EXO.copy(scene = FULL_EXO.scene.copy(inBox = true))
        assertEquals(listOf(box), hold.hold(box, 0))
        // Even a fallback in flight does not swallow it, and it leaves the event for the next one.
        hold.onLadder(PlayerFailureReason.LIVE_FALLBACK, 0)
        assertEquals(listOf(box), hold.hold(box, 100))
        assertEquals(emptyList<Failure.Playback>(), hold.hold(FULL_EXO, 200))
        // mpv in full screen waits too.
        val mpv = FULL_EXO.copy(engine = Engine.MPV)
        assertEquals(emptyList<Failure.Playback>(), hold.hold(mpv, 20_000))
        assertEquals(listOf(mpv), hold.due(20_000 + FallbackHold.HOLD_MS))
    }

    @Test
    fun `background processes report only guide errors that change, the screen reports standing ones`() {
        val standing = listOf(feed(1, "HTTP 404"), feed(2, null))

        // A WorkManager wake: the error was there before, so it is not news.
        assertEquals(emptyList<Long>(), guideErrorsToReport(null, emptyMap(), standing, shown = false).map { it.id })
        val seen = standing.associate { it.id to it.lastError }
        assertEquals(emptyList<Long>(), guideErrorsToReport(seen, emptyMap(), standing, shown = false).map { it.id })
        // A guide that starts failing while it runs is.
        val changed = listOf(feed(1, "HTTP 404"), feed(2, "timeout"))
        assertEquals(listOf(2L), guideErrorsToReport(seen, emptyMap(), changed, shown = false).map { it.id })

        // The screen shows: every error not yet reported in this process goes out once.
        val reported = mapOf(2L to "timeout")
        assertEquals(listOf(1L), guideErrorsToReport(seen, reported, changed, shown = true).map { it.id })
        assertEquals(emptyList<Long>(), guideErrorsToReport(seen, reported + (1L to "HTTP 404"), changed, shown = true).map { it.id })
        // First emission of a process with a screen: the standing error shows once.
        assertEquals(listOf(1L), guideErrorsToReport(null, emptyMap(), standing, shown = true).map { it.id })
    }

    @Test
    fun `a sync that already ended at the first emission is checked too`() {
        assertTrue(syncEnded(null, active = false))
        assertTrue(syncEnded(true, active = false))
        assertFalse(syncEnded(false, active = false))
        assertFalse(syncEnded(null, active = true))
        assertFalse(syncEnded(true, active = true))
        // A run that ended before this process started is an earlier process's to report.
        assertNull(playlistFailureOf(SOURCE, stats(1_000, SyncResult.Failed("HTTP 401")), 1_500, SOURCE))
        assertEquals(Failure.Playlist(SOURCE, "HTTP 401"), playlistFailureOf(SOURCE, stats(2_000, SyncResult.Failed("HTTP 401")), 1_500, SOURCE))
    }

    @Test
    fun `the scene keeps the last update and starts empty`() {
        val scene = PlaybackScene()
        assertFalse(scene.shown.value)
        assertNull(scene.snapshot().channel)

        scene.update(null, "News", inBox = true, catchup = false)
        assertTrue(scene.shown.value)
        assertEquals(SceneSnapshot(channel = null, category = "News", streamUrl = null, inBox = true, catchup = false), scene.snapshot())
    }

    private fun feed(id: Long, lastError: String?) =
        EpgSource(id, "Guide $id", "http://guide.example/$id.xml", null, null, lastError)

    private fun stats(finishedAt: Long, result: SyncResult) =
        SyncRunStats(SOURCE, finishedAt - 10, finishedAt, result, emptyMap(), emptyMap(), emptyMap(), false)

    private companion object {
        const val SOURCE = 7L
        val FULL_EXO = Failure.Playback(
            Engine.EXO,
            "ERROR_CODE_IO_BAD_HTTP_STATUS: Response code: 403",
            null,
            SceneSnapshot("BBC One", "UK", null, inBox = false, catchup = false),
        )
    }
}
