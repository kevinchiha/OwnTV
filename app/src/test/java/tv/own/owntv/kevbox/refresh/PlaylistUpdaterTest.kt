// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.refresh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistUpdaterTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val calls = java.util.Collections.synchronizedList(mutableListOf<String>())

    @After fun tearDown() = scope.cancel()

    private fun updater(
        sourceId: Long? = 7L,
        channels: suspend (Long) -> Unit = { calls += "channels $it" },
        check: suspend () -> Unit = { calls += "check" },
    ) = PlaylistUpdater(
        checkPlaylist = check,
        currentSourceId = { sourceId },
        syncChannels = channels,
        syncGuides = { calls += "guide $it" },
        scope = scope,
    )

    private fun PlaylistUpdater.awaitIdle() = runBlocking {
        withTimeout(5_000) { running.first { !it } }
    }

    @Test
    fun `update now checks kevbox_dev, then the channels, then the guide`() {
        val u = updater()
        u.updateNow()
        u.awaitIdle()
        assertEquals(listOf("check", "channels 7", "guide 7"), calls.toList())
    }

    @Test
    fun `the guide still downloads when the channel sync fails`() {
        val u = updater(channels = { calls += "channels $it"; error("provider down") })
        u.updateNow()
        u.awaitIdle()
        assertEquals(listOf("check", "channels 7", "guide 7"), calls.toList())
    }

    @Test
    fun `a failed kevbox_dev check does not stop the downloads`() {
        val u = updater(check = { calls += "check"; error("offline") })
        u.updateNow()
        u.awaitIdle()
        assertEquals(listOf("check", "channels 7", "guide 7"), calls.toList())
    }

    @Test
    fun `with no playlist only the kevbox_dev check runs`() {
        val u = updater(sourceId = null)
        u.updateNow()
        u.awaitIdle()
        assertEquals(listOf("check"), calls.toList())
    }

    @Test
    fun `a second press while one runs starts nothing new`() {
        val gate = CompletableDeferred<Unit>()
        val u = updater(channels = { calls += "channels $it"; gate.await() })
        u.updateNow()
        runBlocking { withTimeout(5_000) { u.running.first { it } } }
        u.updateNow()
        assertTrue(u.running.value)
        gate.complete(Unit)
        u.awaitIdle()
        assertEquals(listOf("check", "channels 7", "guide 7"), calls.toList())
        assertFalse(u.running.value)
    }
}
