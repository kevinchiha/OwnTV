package tv.own.owntv.kevbox.access

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.kevbox.supabase.AccessVerdict
import tv.own.owntv.kevbox.supabase.Fetch

class AccessMonitorTest {

    private class MemoryVerdictPrefs(override var last: AccessVerdict? = null) : VerdictPrefs

    @Test
    fun `LOCKED locks, ALLOWED and NO_ROW unlock, a failure keeps the last, a fresh install is unlocked`() = runBlocking {
        val prefs = MemoryVerdictPrefs()
        var next: Fetch<AccessVerdict> = Fetch.Ok(AccessVerdict.LOCKED)
        val monitor = AccessMonitor(fetch = { next }, prefs = prefs)
        assertFalse(monitor.locked.value)

        monitor.check()
        assertTrue(monitor.locked.value)
        assertEquals(AccessVerdict.LOCKED, prefs.last)

        for (failure in listOf(Fetch.Failed, Fetch.SessionRejected, Fetch.SignedOut)) {
            next = failure
            monitor.check()
            assertTrue(monitor.locked.value)
            assertEquals(AccessVerdict.LOCKED, prefs.last)
        }

        next = Fetch.Ok(AccessVerdict.ALLOWED)
        monitor.check()
        assertFalse(monitor.locked.value)
        assertEquals(AccessVerdict.ALLOWED, prefs.last)

        next = Fetch.Ok(AccessVerdict.LOCKED)
        monitor.check()
        next = Fetch.Ok(AccessVerdict.NO_ROW)
        monitor.check()
        assertFalse(monitor.locked.value)
        assertEquals(AccessVerdict.NO_ROW, prefs.last)
    }

    @Test
    fun `a saved LOCKED verdict locks from the start, and a failure keeps it locked`() = runBlocking {
        val prefs = MemoryVerdictPrefs(AccessVerdict.LOCKED)
        val monitor = AccessMonitor(fetch = { Fetch.Failed }, prefs = prefs)
        assertTrue(monitor.locked.value)
        monitor.check()
        assertTrue(monitor.locked.value)
    }

    @Test
    fun `reset unlocks and forgets the saved verdict`() = runBlocking {
        val prefs = MemoryVerdictPrefs(AccessVerdict.LOCKED)
        val monitor = AccessMonitor(fetch = { Fetch.Failed }, prefs = prefs)
        monitor.reset()
        assertFalse(monitor.locked.value)
        assertNull(prefs.last)
    }

    @Test
    fun `a verdict in flight across a reset is dropped`() = runBlocking {
        val prefs = MemoryVerdictPrefs()
        val answer = CompletableDeferred<Fetch<AccessVerdict>>()
        val monitor = AccessMonitor(fetch = { answer.await() }, prefs = prefs)
        val checking = launch { monitor.check() }
        yield()

        monitor.reset() // sign-out while the server answers for the old member
        answer.complete(Fetch.Ok(AccessVerdict.LOCKED))
        checking.join()

        assertFalse(monitor.locked.value)
        assertNull(prefs.last)
    }
}
