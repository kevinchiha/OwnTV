// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.nav.MainSection
import tv.own.owntv.kevbox.live.LiveVideoStage

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

    @Test
    fun `Left from the block goes back to the row that last had focus`() =
        assertEquals(20L, sportsBlockLeftRowId(lastFocusedId = 20L, rowIds = listOf(10L, 20L, 30L)))

    @Test
    fun `Left goes to the first row once the last one has left the list`() {
        assertEquals(30L, sportsBlockLeftRowId(lastFocusedId = 20L, rowIds = listOf(30L, 10L)))
        assertEquals(30L, sportsBlockLeftRowId(lastFocusedId = null, rowIds = listOf(30L, 10L)))
    }

    @Test
    fun `Left has no row when the tab shows none`() {
        assertNull(sportsBlockLeftRowId(lastFocusedId = 20L, rowIds = emptyList()))
        assertNull(sportsBlockLeftRowId(lastFocusedId = null, rowIds = emptyList()))
    }

    private fun channel(id: Long) =
        ChannelEntity(id = id, sourceId = 1, categoryId = 10, name = "Ch $id", streamUrl = "http://x/$id.ts", remoteId = "$id")

    @Test
    fun `leaving a match page empties a Sports box`() {
        val stage = LiveVideoStage()
        stage.putInBox(channel(5), MainSection.SPORTS)
        assertTrue(clearSportsBox(stage))
        assertNull(stage.boxChannel)
        assertNull(stage.boxSection)
    }

    @Test
    fun `a box another screen filled is left alone`() {
        for (section in listOf(MainSection.SEARCH, MainSection.LIVE_TV)) {
            val stage = LiveVideoStage()
            stage.putInBox(channel(5), section)
            assertFalse(clearSportsBox(stage))
            assertEquals(5L, stage.boxChannel?.id)
            assertEquals(section, stage.boxSection)
        }
    }

    @Test
    fun `no box or no stage has nothing to stop`() {
        assertFalse(clearSportsBox(LiveVideoStage()))
        assertFalse(clearSportsBox(null))
    }
}
