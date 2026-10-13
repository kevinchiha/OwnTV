package tv.own.owntv.kevbox.expiry

import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.repository.SourceTestResult

class ExpiryMonitorTest {

    private class MemoryExpiryPrefs : ExpiryPrefs {
        override var lastWarnedDay: String? = null
    }

    private class Harness {
        var nowMs = 1_000_000_000_000L
        var day: LocalDate = LocalDate.of(2026, 10, 13)
        var next: SourceTestResult? = SourceTestResult.Ok()
        val tested = mutableListOf<Long>()
        val prefs = MemoryExpiryPrefs()

        fun monitor() = ExpiryMonitor(
            test = { id -> tested += id; next },
            now = { nowMs },
            today = { day },
            prefs = prefs,
        )

        fun advance(ms: Long) {
            nowMs += ms
        }
    }

    private val hour = 3_600_000L
    private val minute = 60_000L

    @Test
    fun `checks one at start, the next after 6 h, deferred while playing and run when playback stops, none without a source`() = runBlocking {
        val h = Harness()
        val m = h.monitor()

        m.checkAtStart(null)
        m.tick(null, playing = false)
        assertEquals(emptyList<Long>(), h.tested)

        m.checkAtStart(7)
        assertEquals(listOf(7L), h.tested)

        h.advance(6 * hour - 1)
        m.tick(7, playing = false)
        assertEquals(1, h.tested.size)

        h.advance(1)
        m.tick(7, playing = true)
        assertEquals(1, h.tested.size)
        h.advance(hour)
        m.tick(7, playing = true)
        assertEquals(1, h.tested.size)

        m.tick(7, playing = false)
        assertEquals(2, h.tested.size)

        m.tick(7, playing = false)
        assertEquals(2, h.tested.size)
    }

    @Test
    fun `the expired popup shows once per app start, after OK later Expired results keep it hidden, a new monitor shows it again`() = runBlocking {
        val h = Harness()
        h.next = SourceTestResult.Expired(h.nowMs - hour)
        val m = h.monitor()

        m.checkAtStart(7)
        assertEquals(ExpiryPopup.Expired, m.popup.value)
        assertTrue(m.channelShowsExpired.value)

        m.dismissPopup()
        assertNull(m.popup.value)

        h.advance(6 * hour)
        m.tick(7, playing = false)
        assertNull(m.popup.value)
        h.advance(11 * minute)
        m.onChannelFailed(7)
        assertNull(m.popup.value)
        assertTrue(m.channelShowsExpired.value)

        val restarted = h.monitor()
        restarted.checkAtStart(7)
        assertEquals(ExpiryPopup.Expired, restarted.popup.value)
    }

    @Test
    fun `a channel failure reuses a result under 10 minutes old, otherwise tests, Ok clears the channel message`() = runBlocking {
        val h = Harness()
        h.next = SourceTestResult.Expired(null)
        val m = h.monitor()
        m.checkAtStart(7)
        assertEquals(1, h.tested.size)

        h.advance(9 * minute)
        m.onChannelFailed(7)
        assertEquals(1, h.tested.size)
        assertTrue(m.channelShowsExpired.value)

        // A different source never inherits the last line's answer.
        m.onChannelFailed(8)
        assertEquals(listOf(7L, 8L), h.tested)

        h.next = SourceTestResult.Ok()
        h.advance(10 * minute)
        m.onChannelFailed(7)
        assertEquals(3, h.tested.size)
        assertFalse(m.channelShowsExpired.value)
        assertNull(m.popup.value)
    }

    @Test
    fun `expiring - 36 h left gives 2 and 1 h gives 1, at most once per calendar day, never for a null expiry or 7+ days`() = runBlocking {
        assertEquals(2, daysLeftRoundedUp(expiryMs = 36 * hour, nowMs = 0))
        assertEquals(1, daysLeftRoundedUp(expiryMs = hour, nowMs = 0))

        val h = Harness()
        h.next = SourceTestResult.Ok(expiryMs = h.nowMs + 36 * hour)
        val m = h.monitor()
        m.checkAtStart(7)
        assertEquals(ExpiryPopup.Expiring(2), m.popup.value)
        assertEquals("2026-10-13", h.prefs.lastWarnedDay)
        m.dismissPopup()

        h.advance(6 * hour)
        h.next = SourceTestResult.Ok(expiryMs = h.nowMs + hour)
        m.tick(7, playing = false)
        assertNull(m.popup.value)

        h.day = h.day.plusDays(1)
        h.advance(6 * hour)
        h.next = SourceTestResult.Ok(expiryMs = h.nowMs + hour)
        m.tick(7, playing = false)
        assertEquals(ExpiryPopup.Expiring(1), m.popup.value)

        val fresh = Harness()
        fresh.next = SourceTestResult.Ok(expiryMs = null)
        val noExpiry = fresh.monitor()
        noExpiry.checkAtStart(7)
        assertNull(noExpiry.popup.value)

        fresh.next = SourceTestResult.Ok(expiryMs = fresh.nowMs + 7 * 24 * hour)
        val farOff = fresh.monitor()
        farOff.checkAtStart(7)
        assertNull(farOff.popup.value)
        assertNull(fresh.prefs.lastWarnedDay)
    }

    @Test
    fun `an Expired popup takes precedence over Expiring`() = runBlocking {
        val h = Harness()
        h.next = SourceTestResult.Ok(expiryMs = h.nowMs + hour)
        val m = h.monitor()
        m.checkAtStart(7)
        assertEquals(ExpiryPopup.Expiring(1), m.popup.value)

        h.advance(6 * hour)
        h.next = SourceTestResult.Expired(h.nowMs - hour)
        m.tick(7, playing = false)
        assertEquals(ExpiryPopup.Expired, m.popup.value)
    }

    @Test
    fun `Unreachable, AuthFailed and a vanished source change nothing`() = runBlocking {
        val h = Harness()
        h.next = SourceTestResult.Expired(null)
        val m = h.monitor()
        m.checkAtStart(7)

        for (result in listOf(SourceTestResult.Unreachable("timeout"), SourceTestResult.AuthFailed, null)) {
            h.advance(6 * hour)
            h.next = result
            m.tick(7, playing = false)
            assertTrue(m.channelShowsExpired.value)
            assertEquals(ExpiryPopup.Expired, m.popup.value)
        }

        val okFirst = Harness()
        val ok = okFirst.monitor()
        ok.checkAtStart(7)
        okFirst.next = SourceTestResult.Unreachable(null)
        okFirst.advance(6 * hour)
        ok.tick(7, playing = false)
        assertFalse(ok.channelShowsExpired.value)
        assertNull(ok.popup.value)
    }

    @Test
    fun `reset forgets the old line, so the next start check decides afresh`() = runBlocking {
        val h = Harness()
        h.next = SourceTestResult.Expired(null)
        val m = h.monitor()
        m.checkAtStart(7)
        m.dismissPopup()

        m.reset()
        assertFalse(m.channelShowsExpired.value)
        assertNull(m.popup.value)

        m.checkAtStart(9)
        assertEquals(ExpiryPopup.Expired, m.popup.value)

        m.reset()
        h.next = SourceTestResult.Ok()
        m.checkAtStart(9)
        assertFalse(m.channelShowsExpired.value)
        assertNull(m.popup.value)
    }
}
