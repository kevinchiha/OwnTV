// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.refresh

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AwaitSyncEndTest {

    @Test
    fun `waits for the sync to start and then to end`() = runBlocking {
        // WorkManager first reports the old, finished run: idle.
        val active = MutableStateFlow(false)
        val wait = async { awaitSyncEnd(active, startWaitMs = 5_000, maxWaitMs = 5_000) }
        delay(50)
        active.value = true
        delay(50)
        assertFalse("still syncing", wait.isCompleted)
        active.value = false
        wait.await()
        assertTrue(wait.isCompleted)
    }

    @Test
    fun `a sync that never shows up ends the wait after the start window`() = runBlocking {
        val active = MutableStateFlow(false)
        val wait = async { awaitSyncEnd(active, startWaitMs = 100, maxWaitMs = 5_000) }
        delay(400)
        yield()
        assertTrue(wait.isCompleted)
    }

    @Test
    fun `a sync stuck waiting for the network ends the wait after the cap`() = runBlocking {
        val active = MutableStateFlow(true)
        val wait = async { awaitSyncEnd(active, startWaitMs = 5_000, maxWaitMs = 200) }
        delay(600)
        yield()
        assertTrue(wait.isCompleted)
    }
}
