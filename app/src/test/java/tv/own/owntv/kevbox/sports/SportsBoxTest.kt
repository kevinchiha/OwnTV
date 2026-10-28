// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SportsBoxTest {

    @Test
    fun `the playing channel's row wins when it is shown`() =
        assertEquals(20L, sportsReturnChannelId(playingId = 20L, lastPlayedId = 10L, shownIds = listOf(10L, 20L, 30L)))

    @Test
    fun `after CH+ off the list the row OK was pressed on takes focus`() =
        assertEquals(10L, sportsReturnChannelId(playingId = 99L, lastPlayedId = 10L, shownIds = listOf(30L, 10L)))

    @Test
    fun `with neither shown the first row takes focus`() {
        assertEquals(30L, sportsReturnChannelId(playingId = 99L, lastPlayedId = 98L, shownIds = listOf(30L, 10L)))
        assertEquals(30L, sportsReturnChannelId(playingId = null, lastPlayedId = null, shownIds = listOf(30L, 10L)))
    }

    @Test
    fun `an empty list has no row`() =
        assertNull(sportsReturnChannelId(playingId = 20L, lastPlayedId = 10L, shownIds = emptyList()))
}
