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
    }
}
