// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.nav.MainSection

class SearchBoxTest {

    private fun channel(id: Long) =
        ChannelEntity(id = id, sourceId = 1, categoryId = 10, name = "Ch $id", streamUrl = "http://x/$id.ts", remoteId = "$id")

    // --- OK on a search result ------------------------------------------------------------------

    @Test
    fun firstOkPreviewsInTheBox() = assertEquals(SearchOk.PREVIEW, searchOk(channelId = 7, boxChannelId = null, previewAvailable = true))

    @Test
    fun okOnAnotherChannelPreviewsThatOne() = assertEquals(SearchOk.PREVIEW, searchOk(channelId = 7, boxChannelId = 3, previewAvailable = true))

    @Test
    fun okOnTheChannelInTheBoxWatchesFullScreen() = assertEquals(SearchOk.WATCH, searchOk(channelId = 7, boxChannelId = 7, previewAvailable = true))

    @Test
    fun withThePlayerBusyOneOkWatches() = assertEquals(SearchOk.WATCH, searchOk(channelId = 7, boxChannelId = null, previewAvailable = false))

    // --- Where focus lands coming back from full screen -----------------------------------------

    @Test
    fun backLandsOnTheRowItLeftFrom() = assertEquals("c2", searchReturnKey("c2", lastKeyChannelId = 2, playingChannelId = 2, playingRowKey = "c2"))

    @Test
    fun backFromAProgrammeRowStaysOnIt() = assertEquals("p9", searchReturnKey("p9", lastKeyChannelId = 1, playingChannelId = 1, playingRowKey = "c1"))

    @Test
    fun afterChPlusBackLandsOnTheChannelNowPlaying() = assertEquals("c3", searchReturnKey("c2", lastKeyChannelId = 2, playingChannelId = 3, playingRowKey = "c3"))

    @Test
    fun afterChPlusToAChannelNotInTheResultsBackLandsOnTheRowItLeftFrom() =
        assertEquals("c2", searchReturnKey("c2", lastKeyChannelId = 2, playingChannelId = 44, playingRowKey = null))

    @Test
    fun nothingToLandOn() = assertNull(searchReturnKey(null, lastKeyChannelId = null, playingChannelId = null, playingRowKey = null))

    // --- Whose box it is ------------------------------------------------------------------------

    @Test
    fun theBoxRemembersWhichScreenFilledIt() {
        val stage = LiveVideoStage()
        stage.putInBox(channel(5), MainSection.SEARCH)
        assertEquals(5L, stage.boxChannel?.id)
        assertEquals(MainSection.SEARCH, stage.boxSection)
        stage.clearBox()
        assertNull(stage.boxChannel)
        assertNull(stage.boxSection)
    }

    // --- Holding OK: Search's box goes to the guide, and Back brings it back ----------------------

    @Test
    fun searchsBoxMovesToTheGuideForTheSameChannel() {
        val stage = LiveVideoStage()
        stage.putInBox(channel(5), MainSection.SEARCH)
        assertEquals(true, stage.moveBox(MainSection.SEARCH, MainSection.LIVE_TV, onlyChannelId = 5))
        assertEquals(5L, stage.boxChannel?.id)
        assertEquals(MainSection.LIVE_TV, stage.boxSection)
    }

    @Test
    fun searchsBoxStaysWhenAnotherChannelIsHeld() {
        val stage = LiveVideoStage()
        stage.putInBox(channel(5), MainSection.SEARCH)
        assertEquals(false, stage.moveBox(MainSection.SEARCH, MainSection.LIVE_TV, onlyChannelId = 9))
        assertEquals(MainSection.SEARCH, stage.boxSection)
    }

    @Test
    fun theGuidesBoxComesBackToSearchWhateverItPlays() {
        val stage = LiveVideoStage()
        stage.putInBox(channel(9), MainSection.LIVE_TV)
        assertEquals(true, stage.moveBox(MainSection.LIVE_TV, MainSection.SEARCH))
        assertEquals(9L, stage.boxChannel?.id)
        assertEquals(MainSection.SEARCH, stage.boxSection)
    }

    @Test
    fun anEmptyOrOtherBoxDoesNotMove() {
        val stage = LiveVideoStage()
        assertEquals(false, stage.moveBox(MainSection.LIVE_TV, MainSection.SEARCH))
        assertNull(stage.boxSection)
        stage.putInBox(channel(9), MainSection.SEARCH)
        assertEquals(false, stage.moveBox(MainSection.LIVE_TV, MainSection.SEARCH))
        assertEquals(MainSection.SEARCH, stage.boxSection)
    }

    // --- Moving between screens with the player closed ------------------------------------------

    @Test
    fun theBoxsOwnScreenKeepsIt() {
        assertEquals(SectionShown(clearBox = false, stopPreview = false), sectionShown(MainSection.SEARCH, playerIdle = true, boxSection = MainSection.SEARCH))
        assertEquals(SectionShown(clearBox = false, stopPreview = false), sectionShown(MainSection.LIVE_TV, playerIdle = true, boxSection = MainSection.LIVE_TV))
    }

    @Test
    fun leavingSearchForLiveTvStopsSearchsChannel() =
        assertEquals(SectionShown(clearBox = true, stopPreview = true), sectionShown(MainSection.LIVE_TV, playerIdle = true, boxSection = MainSection.SEARCH))

    @Test
    fun leavingSearchForHomeStopsSearchsChannel() =
        assertEquals(SectionShown(clearBox = true, stopPreview = true), sectionShown(MainSection.HOME, playerIdle = true, boxSection = MainSection.SEARCH))

    @Test
    fun leavingLiveTvForSearchStopsTheGridsChannel() =
        assertEquals(SectionShown(clearBox = true, stopPreview = true), sectionShown(MainSection.SEARCH, playerIdle = true, boxSection = MainSection.LIVE_TV))

    @Test
    fun theTvGuidePageKeepsThePreviewEngineAsBefore() =
        assertEquals(SectionShown(clearBox = true, stopPreview = false), sectionShown(MainSection.EPG, playerIdle = true, boxSection = MainSection.LIVE_TV))

    @Test
    fun withNoBoxOnlyLiveScreensKeepThePreview() {
        assertEquals(SectionShown(clearBox = false, stopPreview = true), sectionShown(MainSection.SEARCH, playerIdle = true, boxSection = null))
        assertEquals(SectionShown(clearBox = false, stopPreview = false), sectionShown(MainSection.LIVE_TV, playerIdle = true, boxSection = null))
        assertEquals(SectionShown(clearBox = false, stopPreview = false), sectionShown(MainSection.EPG, playerIdle = true, boxSection = null))
    }

    @Test
    fun nothingChangesWhileThePlayerIsOpen() {
        val s = sectionShown(MainSection.HOME, playerIdle = false, boxSection = MainSection.SEARCH)
        assertFalse(s.clearBox)
        assertFalse(s.stopPreview)
    }
}
