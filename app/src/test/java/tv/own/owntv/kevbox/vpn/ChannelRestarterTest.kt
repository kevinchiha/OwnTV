// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.vpn

import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.kevbox.errors.PlaybackScene
import tv.own.owntv.kevbox.usage.PlaySource

class ChannelRestarterTest {

    /** Fake engines and a fake clock: every call is written down with the time it happened at. */
    private inner class Harness(val scope: CoroutineScope) {
        val scene = PlaybackScene()
        var clock = 0L
        var source: PlaySource? = PlaySource.LIVE_ENGINE
        var front = true
        val calls = mutableListOf<Pair<String, Long>>()
        var engineReads = 0
        var evictions = 0

        // The settle wait: parks until the test lets it go, then moves the clock by the wait.
        private val waits = mutableListOf<CompletableDeferred<Unit>>()

        val restarter = ChannelRestarter(
            scene = scene,
            playingSource = { engineReads += 1; source },
            inFront = { front },
            retuneLive = {
                calls += "background" to clock
                calls += "foreground" to clock
            },
            retryPlayer = { calls += "retry" to clock },
            evictPool = { evictions += 1 },
            scope = scope,
            sleep = { ms ->
                val gate = CompletableDeferred<Unit>()
                waits += gate
                gate.await()
                clock += ms
            },
            main = EmptyCoroutineContext,
            io = EmptyCoroutineContext,
        )

        fun show() = scene.update(channel = null, category = null, inBox = true, catchup = false)

        /** Lets every parked wait end and returns once each restart it started has run. */
        suspend fun settle() {
            yield()
            waits.toList().forEach { it.complete(Unit) }
            waits.clear()
            withTimeout(5_000) { while (scope.coroutineContext[Job]!!.children.any { it.isActive }) yield() }
        }
    }

    private fun harness(block: suspend Harness.() -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        try {
            Harness(scope).block()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `two calls a moment apart restart once`() = harness {
        show()
        val calledAt = clock
        restarter.restartSoon()
        yield()
        clock += 400
        restarter.restartSoon()

        settle()

        assertEquals(listOf("background", "foreground"), calls.map { it.first })
        assertEquals(1, evictions)
        calls.forEach { (_, at) -> assertTrue("restart at $at", at - calledAt <= 3_000) }
    }

    @Test
    fun `a call after the window ends restarts again`() = harness {
        show()
        restarter.restartSoon()
        settle()
        restarter.restartSoon()
        settle()

        assertEquals(listOf("background", "foreground", "background", "foreground"), calls.map { it.first })
        assertEquals(2, evictions)
    }

    @Test
    fun `nothing playing restarts nothing but still evicts the pool`() = harness {
        restarter.restartSoon()
        settle()

        assertEquals(emptyList<Pair<String, Long>>(), calls)
        assertEquals(0, engineReads)
        assertEquals(1, evictions)
    }

    @Test
    fun `a shown scene with no live channel playing re-tunes nothing`() = harness {
        show()
        source = null
        restarter.restartNow()

        assertEquals(emptyList<Pair<String, Long>>(), calls)
        assertEquals(1, evictions)
    }

    @Test
    fun `the full-screen player is retried when it is the engine playing`() = harness {
        show()
        source = PlaySource.PLAYER
        restarter.restartNow()

        assertEquals(listOf("retry"), calls.map { it.first })
    }

    @Test
    fun `an app in the background never gets the pair`() = harness {
        show()
        front = false
        restarter.restartNow()

        assertEquals(emptyList<Pair<String, Long>>(), calls)
        assertEquals(1, evictions)
    }

    @Test
    fun `a pool eviction that throws still re-tunes the channel`() = harness {
        show()
        val throwing = ChannelRestarter(
            scene = scene,
            playingSource = { PlaySource.LIVE_ENGINE },
            inFront = { true },
            retuneLive = { calls += "background" to clock },
            retryPlayer = {},
            evictPool = { throw IllegalStateException() },
            scope = scope,
            sleep = {},
            main = EmptyCoroutineContext,
            io = EmptyCoroutineContext,
        )
        throwing.restartNow()

        assertEquals(listOf("background"), calls.map { it.first })
    }
}
