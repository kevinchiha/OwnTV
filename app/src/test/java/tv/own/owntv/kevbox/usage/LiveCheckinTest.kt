package tv.own.owntv.kevbox.usage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCheckinTest {

    @Test
    fun `checkinDue never sends signed out`() {
        assertFalse(checkinDue(lastSentAtMs = null, nowMs = NOW, signedIn = false))
        assertFalse(checkinDue(lastSentAtMs = NOW - 2 * CHECKIN_INTERVAL_MS, nowMs = NOW, signedIn = false))
    }

    @Test
    fun `checkinDue sends the first check-in of the process`() {
        assertTrue(checkinDue(lastSentAtMs = null, nowMs = NOW, signedIn = true))
    }

    @Test
    fun `checkinDue waits the full 6 hours`() {
        val almost = 5 * HOUR + 59 * MINUTE

        assertFalse(checkinDue(lastSentAtMs = NOW - almost, nowMs = NOW, signedIn = true))
        assertTrue(checkinDue(lastSentAtMs = NOW - 6 * HOUR, nowMs = NOW, signedIn = true))
        assertTrue(checkinDue(lastSentAtMs = NOW - 9 * HOUR, nowMs = NOW, signedIn = true))
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
