// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.parser.XtEpgEntry

class StretchCutterTest {

    private var ids = 0
    private val cutter = StretchCutter { "s${++ids}" }

    @Test
    fun `a 30-minute full-screen play is one stretch`() {
        val done = mutableListOf<Stretch>()
        var t = AT_20
        while (t <= AT_20 + 30 * MIN) {
            done += cutter.onLook(full(ONE), null, t, AT_20, proxy = true)
            t += 1_000
        }
        assertTrue(done.isEmpty())
        done += cutter.onBackground(AT_20 + 30 * MIN, AT_20)

        val s = done.single()
        assertEquals(AT_20, s.startedAtMs)
        assertEquals(AT_20 + 30 * MIN, s.endedAtMs)
        assertFalse(s.inBox)
        assertEquals("One", s.channel)
        assertEquals("News", s.category)
        assertEquals("panel.example", s.providerHost)
        assertTrue(s.proxy)
    }

    // --- each boundary closes and the next opens at the same moment (AC-2) ----------------------

    @Test
    fun `each boundary closes and the next opens at the same moment - channel change`() {
        cutter.onLook(full(ONE), null, 0, 0, false)
        val closed = cutter.onLook(full(TWO), null, MIN, 0, false).single()
        assertEquals(0L, closed.startedAtMs)
        assertEquals(MIN, closed.endedAtMs)
        assertEquals("One", closed.channel)
        assertOpensAt(MIN, "Two")
    }

    @Test
    fun `each boundary closes and the next opens at the same moment - stop past the grace`() {
        cutter.onLook(full(ONE), null, 0, 0, false)
        assertTrue(cutter.onLook(full(ONE).copy(playing = false), null, MIN, 0, false).isEmpty())
        assertTrue(cutter.onLook(full(ONE).copy(playing = false), null, MIN + 9_000, 0, false).isEmpty())
        // The grace passed: the stretch ends at the stall's start, not now.
        val closed = cutter.onLook(full(ONE).copy(playing = false), null, MIN + STALL_GRACE_MS, 0, false).single()
        assertEquals(MIN, closed.endedAtMs)
        assertFalse(cutter.isOpen)
        // Play coming back later opens a new one then.
        assertTrue(cutter.onLook(full(ONE), null, MIN + 20_000, 0, false).isEmpty())
        assertOpensAt(MIN + 20_000, "One")
    }

    @Test
    fun `each boundary closes and the next opens at the same moment - failure`() {
        cutter.onLook(full(ONE), null, 0, 0, false)
        // A failure is not a stall: it closes at once, with no grace.
        val closed = cutter.onLook(full(ONE).copy(failed = true), null, MIN, 0, false).single()
        assertEquals(MIN, closed.endedAtMs)
        assertFalse(cutter.isOpen)
    }

    @Test
    fun `each boundary closes and the next opens at the same moment - catch-up`() {
        cutter.onLook(full(ONE), null, 0, 0, false)
        val closed = cutter.onLook(full(ONE).copy(catchup = true), null, MIN, 0, false).single()
        assertEquals(MIN, closed.endedAtMs)
        assertFalse(cutter.isOpen)
    }

    @Test
    fun `each boundary closes and the next opens at the same moment - background`() {
        cutter.onLook(full(ONE), null, 0, 0, false)
        val closed = cutter.onBackground(MIN, 0).single()
        assertEquals(MIN, closed.endedAtMs)
        assertFalse(cutter.isOpen)
    }

    @Test
    fun `each boundary closes and the next opens at the same moment - box to full switch`() {
        cutter.onLook(full(ONE).copy(inBox = true), null, 0, 0, false)
        val closed = cutter.onLook(full(ONE), null, MIN, 0, false).single()
        assertTrue(closed.inBox)
        assertEquals(MIN, closed.endedAtMs)
        assertOpensAt(MIN, "One")
        assertFalse(cutter.onBackground(2 * MIN, 0).single().inBox)
    }

    @Test
    fun `each boundary closes and the next opens at the same moment - programme end`() {
        val news = XtEpgEntry("News at 8", "", AT_20 - 10 * MIN, AT_20 + 30 * MIN)
        val film = XtEpgEntry("Film", "", AT_20 + 30 * MIN, AT_20 + 120 * MIN)
        cutter.onLook(full(ONE), news, AT_20, AT_20, false)
        assertTrue(cutter.onLook(full(ONE), news, AT_20 + 30 * MIN - 1, AT_20, false).isEmpty())

        val closed = cutter.onLook(full(ONE), film, AT_20 + 30 * MIN, AT_20, false).single()
        assertEquals("News at 8", closed.programme)
        assertEquals(AT_20 + 30 * MIN, closed.endedAtMs)
        val next = cutter.onBackground(AT_20 + 40 * MIN, AT_20).single()
        assertEquals(AT_20 + 30 * MIN, next.startedAtMs)
        assertEquals("Film", next.programme)
    }

    @Test
    fun `each boundary closes and the next opens at the same moment - title appearing on a blank stretch`() {
        cutter.onLook(full(ONE), null, 0, 0, false)
        assertTrue(cutter.onLook(full(ONE), XtEpgEntry("  ", "", 0, 10 * MIN), MIN, 0, false).isEmpty())
        val closed = cutter.onLook(full(ONE), XtEpgEntry("Match", "", 0, 10 * MIN), 2 * MIN, 0, false).single()
        assertNull(closed.programme)
        assertEquals(2 * MIN, closed.endedAtMs)
        val next = cutter.onBackground(3 * MIN, 0).single()
        assertEquals(2 * MIN, next.startedAtMs)
        assertEquals("Match", next.programme)
    }

    @Test
    fun `a 5-second stall continues the stretch`() {
        cutter.onLook(full(ONE), null, 0, 0, false)
        assertTrue(cutter.onLook(full(ONE).copy(playing = false), null, MIN, 0, false).isEmpty())
        assertTrue(cutter.onLook(full(ONE), null, MIN + 5_000, 0, false).isEmpty())
        val s = cutter.onBackground(2 * MIN, 0).single()
        assertEquals(0L, s.startedAtMs)
        assertEquals(2 * MIN, s.endedAtMs)
    }

    @Test
    fun `a channel change during a stall closes at the stall's start`() {
        cutter.onLook(full(ONE), null, 0, 0, false)
        cutter.onLook(full(TWO).copy(playing = false), null, MIN, 0, false)
        val closed = cutter.onLook(full(TWO), null, MIN + 3_000, 0, false).single()
        assertEquals(MIN, closed.endedAtMs)
        assertOpensAt(MIN + 3_000, "Two")
    }

    // --- AC-3 ------------------------------------------------------------------------------------

    @Test
    fun `under 10 seconds is never queued, catch-up never opens`() {
        cutter.onLook(full(ONE), null, 0, 0, false)
        assertTrue(cutter.onLook(full(TWO), null, STRETCH_MIN_MS - 1, 0, false).isEmpty())
        assertNull(cutter.open(STRETCH_MIN_MS - 1 + 5_000, 0))
        assertTrue(cutter.onBackground(STRETCH_MIN_MS - 1 + 5_000, 0).isEmpty())

        for (t in 0L..MIN step 1_000L) {
            assertTrue(cutter.onLook(full(ONE).copy(catchup = true), null, t, 0, false).isEmpty())
            assertFalse(cutter.isOpen)
        }
        assertTrue(cutter.onBackground(MIN, 0).isEmpty())
    }

    // --- AC-5, AC-6 ------------------------------------------------------------------------------

    @Test
    fun `last press is the latest press at or before the close`() {
        cutter.onLook(full(ONE), null, 0, 0, false)
        assertEquals(30_000L, cutter.onLook(full(TWO), null, MIN, 30_000, false).single().lastPressAtMs)

        // A stall close keeps the press seen when the stall began, never one made during the grace.
        cutter.onLook(full(TWO).copy(playing = false), null, 2 * MIN, 90_000, false)
        val stalled = cutter.onLook(full(TWO).copy(playing = false), null, 2 * MIN + STALL_GRACE_MS, 2 * MIN + 5_000, false).single()
        assertEquals(2 * MIN, stalled.endedAtMs)
        assertEquals(90_000L, stalled.lastPressAtMs)
    }

    @Test
    fun `the open stretch saves as of now and stays open`() {
        cutter.onLook(full(ONE), null, 0, 0, false)
        val saved = cutter.open(OPEN_SAVE_MS, 0)!!
        assertEquals(OPEN_SAVE_MS, saved.endedAtMs)
        assertEquals(cutter.openId, saved.id)
        val closed = cutter.onBackground(2 * OPEN_SAVE_MS, 0).single()
        assertEquals(saved.id, closed.id)
        assertEquals(2 * OPEN_SAVE_MS, closed.endedAtMs)
    }

    private fun assertOpensAt(at: Long, channel: String) {
        assertTrue(cutter.isOpen)
        val s = cutter.open(at + STRETCH_MIN_MS, 0)!!
        assertEquals(at, s.startedAtMs)
        assertEquals(channel, s.channel)
    }

    private companion object {
        const val MIN = 60_000L
        // 2026-10-16 20:00:00 UTC.
        const val AT_20 = 1_792_180_800_000L
        const val ONE = "http://panel.example/live/1.ts"
        const val TWO = "http://panel.example/live/2.ts"

        fun full(key: String) = DiaryLook(
            playing = true, failed = false, catchup = false, channelKey = key, inBox = false,
            channel = if (key == ONE) "One" else "Two", category = "News", streamUrl = key,
        )
    }
}
