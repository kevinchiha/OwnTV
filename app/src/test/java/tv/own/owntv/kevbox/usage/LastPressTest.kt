// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.usage

import org.junit.Assert.assertEquals
import org.junit.Test

class LastPressTest {

    @Test
    fun `last press is the latest press at or before the close, app start counts`() {
        var clock = 1_000L
        val last = LastPress { clock }
        // Built with the process: the app start is the first press.
        assertEquals(1_000L, last.at())

        clock = 5_000L
        assertEquals(1_000L, last.at())
        last.press()
        assertEquals(5_000L, last.at())

        clock = 9_000L
        last.press()
        clock = 20_000L
        assertEquals(9_000L, last.at())
    }

    @Test
    fun `the monotonic stamp follows its own clock, the wall stamp stays as it was`() {
        var wall = 1_000L
        var elapsed = 50L
        val last = LastPress(elapsed = { elapsed }) { wall }
        assertEquals(1_000L, last.at())
        assertEquals(50L, last.elapsedAt())

        // The wall clock jumps back a day; the monotonic one only moves on.
        wall = -86_400_000L
        elapsed = 70L
        last.press()
        assertEquals(-86_400_000L, last.at())
        assertEquals(70L, last.elapsedAt())
    }
}
