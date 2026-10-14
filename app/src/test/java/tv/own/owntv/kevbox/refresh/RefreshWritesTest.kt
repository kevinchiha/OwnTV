// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.refresh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.settings.PlaylistRefresh

class RefreshWritesTest {
    private val fourDays = RefreshChoice.DAYS_4

    @Test
    fun `a playlist with no timer yet gets the choice, as on a TV updating from 5_1_0_8`() {
        // Before this change KevBox never set the playlist's timer, so Core read it as off.
        assertEquals(mapOf(4L to fourDays.playlist), planRefreshWrites(fourDays, listOf(4L), current = emptyMap()))
    }

    @Test
    fun `a playlist on another timer moves to the choice`() {
        val writes = planRefreshWrites(RefreshChoice.NEVER, listOf(4L), current = mapOf(4L to fourDays.playlist))
        assertEquals(mapOf(4L to PlaylistRefresh.OFF), writes)
    }

    @Test
    fun `nothing is written when every playlist already matches`() {
        val writes = planRefreshWrites(fourDays, listOf(4L, 5L), current = mapOf(4L to fourDays.playlist, 5L to fourDays.playlist))
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `a stored timer for a playlist that is gone is left alone`() {
        val writes = planRefreshWrites(fourDays, listOf(4L), current = mapOf(4L to fourDays.playlist, 30L to PlaylistRefresh.OFF))
        assertTrue(writes.isEmpty())
    }
}
