package tv.own.owntv.kevbox.errors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.kevbox.supabase.ApiResponse
import tv.own.owntv.kevbox.supabase.Fetch

class SendPolicyTest {

    @Test
    fun `send plan`() {
        // 60 s after the first entry.
        assertEquals(NOW + 60_000, SendPolicy.nextSendAtMs(NOW, unsentCount = 1, failures = 0, lastFailureAtMs = null))
        assertEquals(NOW + 60_000, SendPolicy.nextSendAtMs(NOW, unsentCount = 19, failures = 0, lastFailureAtMs = null))

        // At once with 20 waiting: the due time is already past.
        val atTwenty = SendPolicy.nextSendAtMs(NOW, unsentCount = 20, failures = 0, lastFailureAtMs = null)!!
        assertTrue(atTwenty <= NOW)

        // Backoff 1/5/15/30/30 min after the last failure, whatever is waiting.
        listOf(1 to 1L, 2 to 5L, 3 to 15L, 4 to 30L, 5 to 30L).forEach { (failures, minutes) ->
            assertEquals(minutes * MINUTE, SendPolicy.backoffMs(failures))
            assertEquals(
                NOW + minutes * MINUTE,
                SendPolicy.nextSendAtMs(NOW - HOUR, unsentCount = 40, failures = failures, lastFailureAtMs = NOW),
            )
        }

        // Nothing unsent, nothing planned.
        assertNull(SendPolicy.nextSendAtMs(null, unsentCount = 0, failures = 0, lastFailureAtMs = null))
        assertNull(SendPolicy.nextSendAtMs(null, unsentCount = 0, failures = 2, lastFailureAtMs = NOW))
    }

    @Test
    fun `outcome of each server answer`() {
        assertEquals(SendOutcome.Sent, outcome(200))
        assertEquals(SendOutcome.Sent, outcome(204))
        assertEquals(SendOutcome.Retry, outcome(401))
        assertEquals(SendOutcome.Rejected, outcome(400))
        assertEquals(SendOutcome.Rejected, outcome(403))
        assertEquals(SendOutcome.Rejected, outcome(422))
        assertEquals(SendOutcome.Rejected, outcome(499))
        assertEquals(SendOutcome.Retry, outcome(0))
        assertEquals(SendOutcome.Retry, outcome(500))
        assertEquals(SendOutcome.Retry, outcome(503))
        assertEquals(SendOutcome.Retry, outcomeOf(Fetch.Failed))
        assertEquals(SendOutcome.SignedOut, outcomeOf(Fetch.SignedOut))
        assertEquals(SendOutcome.SignedOut, outcomeOf(Fetch.SessionRejected))
    }

    @Test
    fun `a missing function, a timeout, too early and a rate limit retry instead of counting as refusals`() {
        listOf(404, 408, 425, 429).forEach { code ->
            assertEquals("HTTP $code", SendOutcome.Retry, outcome(code))
        }
    }

    @Test
    fun `a wall clock moved back does not stall the send plan`() {
        // A failure stamped an hour ahead of now: the backoff runs from now, not from then.
        assertEquals(NOW + MINUTE, SendPolicy.planAtMs(NOW, NOW - MINUTE, unsentCount = 3, failures = 1, lastFailureAtMs = NOW + HOUR))
        // An entry queued an hour ahead of now: the first wait runs from now.
        assertEquals(NOW + 60_000, SendPolicy.planAtMs(NOW, NOW + HOUR, unsentCount = 1, failures = 0, lastFailureAtMs = null))
        // Times in the past plan exactly as nextSendAtMs does.
        assertEquals(
            SendPolicy.nextSendAtMs(NOW - MINUTE, 3, 2, NOW - 2 * MINUTE),
            SendPolicy.planAtMs(NOW, NOW - MINUTE, unsentCount = 3, failures = 2, lastFailureAtMs = NOW - 2 * MINUTE),
        )
        assertNull(SendPolicy.planAtMs(NOW, null, unsentCount = 0, failures = 0, lastFailureAtMs = null))
    }

    @Test
    fun `a signed-out pause ends on time, or at once when the clock moved back`() {
        val until = NOW + SendPolicy.SIGNED_OUT_PAUSE_MS
        assertFalse(SendPolicy.pauseOver(NOW, until))
        assertFalse(SendPolicy.pauseOver(until - 1, until))
        assertTrue(SendPolicy.pauseOver(until, until))
        // The clock jumped back an hour after the pause was set: it would end 75 minutes from now.
        assertTrue(SendPolicy.pauseOver(NOW - HOUR, until))
    }

    private fun outcome(code: Int) = outcomeOf(Fetch.Ok(ApiResponse.Http(code, "")))

    private companion object {
        const val NOW = 1_792_000_931_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
