// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.refresh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.settings.PlaylistAutoRefresh
import tv.own.owntv.core.settings.PlaylistRefresh

class RefreshChoiceTest {
    private val hour = 3600_000L

    @Test
    fun `the choices are the six Kevin picked, in menu order`() {
        assertEquals(
            listOf("APP_START", "HOURS_6", "HOURS_12", "HOURS_24", "DAYS_4", "NEVER"),
            RefreshChoice.entries.map { it.name },
        )
    }

    @Test
    fun `each timed choice makes the channel list stale after its own time`() {
        val expected = mapOf(
            RefreshChoice.HOURS_6 to 6 * hour,
            RefreshChoice.HOURS_12 to 12 * hour,
            RefreshChoice.HOURS_24 to 24 * hour,
            RefreshChoice.DAYS_4 to 96 * hour,
        )
        expected.forEach { (choice, ms) -> assertEquals(choice.name, ms, choice.playlist.thresholdMs) }
    }

    @Test
    fun `every app start and never map to Core's startup and off`() {
        assertEquals(PlaylistRefresh(PlaylistAutoRefresh.STARTUP), RefreshChoice.APP_START.playlist)
        assertEquals(PlaylistRefresh.OFF, RefreshChoice.NEVER.playlist)
        assertNull(RefreshChoice.NEVER.playlist.thresholdMs)
    }

    @Test
    fun `nothing stored, or something unknown, reads as every 4 days`() {
        assertEquals(RefreshChoice.DAYS_4, RefreshChoice.DEFAULT)
        assertEquals(RefreshChoice.DAYS_4, RefreshChoice.parse(null))
        assertEquals(RefreshChoice.DAYS_4, RefreshChoice.parse("DAYS_2"))
        assertEquals(RefreshChoice.HOURS_12, RefreshChoice.parse("HOURS_12"))
    }
}
