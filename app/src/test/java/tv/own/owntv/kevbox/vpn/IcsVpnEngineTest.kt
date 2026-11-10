// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.vpn

import android.content.ContextWrapper
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The engine set-up runs while the app starts (Koin builds the engine in OwnTVApp.onCreate), so a
 * throw there must never crash the app; [IcsVpnEngine.start] tries it again. The set-up is swapped
 * for a counting one, so the Android and engine classes it calls stay out of these tests.
 */
class IcsVpnEngineTest {
    private class SetUpBoom : RuntimeException()

    /** A set-up that throws on the calls listed in [failOn] (1 is the constructor's) and counts calls. */
    private class CountingSetUp(private val failOn: Set<Int>) {
        @Volatile var calls = 0

        fun run() {
            calls += 1
            if (calls in failOn) throw SetUpBoom()
        }
    }

    private fun engine(setUp: CountingSetUp) = IcsVpnEngine(ContextWrapper(null)) { setUp.run() }

    /** Starts [engine] and returns its first event, or null when none came in time. */
    private fun startAndAwait(engine: IcsVpnEngine): EngineEvent? = runBlocking {
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(TIMEOUT_MS) { engine.events.first() }
        }
        engine.start("vpn.example", "", "user", "pass")
        first.await()
    }

    @Test
    fun `a set-up that throws while the app starts is swallowed`() {
        val setUp = CountingSetUp(failOn = setOf(1))

        engine(setUp)

        assertEquals(1, setUp.calls)
    }

    @Test
    fun `start tries a failed set-up again and a second failure is a failed try`() {
        val setUp = CountingSetUp(failOn = setOf(1, 2, 3))
        val engine = engine(setUp)

        assertEquals(EngineEvent.Failed, startAndAwait(engine))
        assertEquals(2, setUp.calls)

        // Still not set up: the next start tries once more.
        assertEquals(EngineEvent.Failed, startAndAwait(engine))
        assertEquals(3, setUp.calls)
    }

    @Test
    fun `once set up, start does not run the set-up again`() {
        val setUp = CountingSetUp(failOn = setOf(1))
        val engine = engine(setUp)

        // The retry works; the empty config is then refused, which ends the try.
        assertEquals(EngineEvent.Failed, startAndAwait(engine))
        assertEquals(2, setUp.calls)

        assertEquals(EngineEvent.Failed, startAndAwait(engine))
        assertEquals(2, setUp.calls)
    }

    @Test
    fun `a set-up that works at construction is not run again by start`() {
        val setUp = CountingSetUp(failOn = emptySet())
        val engine = engine(setUp)

        assertEquals(EngineEvent.Failed, startAndAwait(engine))
        assertEquals(1, setUp.calls)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
