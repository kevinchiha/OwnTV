// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.nav.MainSection

class HoldBackTest {

    private fun holds(
        playerIdle: Boolean = true,
        section: MainSection = MainSection.LIVE_TV,
        boxSection: MainSection? = MainSection.LIVE_TV,
        hasBoxChannel: Boolean = true,
        dialogOpen: Boolean = false,
    ) = holdBackOpensBox(playerIdle, section, boxSection, hasBoxChannel, dialogOpen)

    @Test
    fun liveTvWithItsBoxFilledAndThePlayerIdleOpensTheBox() = assertTrue(holds())

    @Test
    fun searchWithItsOwnBoxOpensTheBox() =
        assertTrue(holds(section = MainSection.SEARCH, boxSection = MainSection.SEARCH))

    @Test
    fun aBoxFilledByTheOtherScreenDoesNotCount() {
        assertFalse(holds(section = MainSection.LIVE_TV, boxSection = MainSection.SEARCH))
        assertFalse(holds(section = MainSection.SEARCH, boxSection = MainSection.LIVE_TV))
    }

    @Test
    fun aDialogUpStopsIt() = assertFalse(holds(dialogOpen = true))

    @Test
    fun anOpenPlayerStopsIt() = assertFalse(holds(playerIdle = false))

    @Test
    fun settingsNeverOpensTheBox() {
        assertFalse(holds(section = MainSection.SETTINGS, boxSection = MainSection.LIVE_TV))
        assertFalse(holds(section = MainSection.SETTINGS, boxSection = MainSection.SETTINGS))
    }

    @Test
    fun anEmptyBoxDoesNothing() {
        assertFalse(holds(hasBoxChannel = false))
        assertFalse(holds(boxSection = null, hasBoxChannel = false))
    }

    @Test
    fun screensSkipOnlyTheReleaseOfAHoldThatActed() {
        val stage = LiveVideoStage()
        assertFalse(stage.swallowsBack())
        stage.heldBackActed = true
        assertTrue(stage.swallowsBack())
        stage.heldBackActed = false
        assertFalse(stage.swallowsBack())
        assertFalse((null as LiveVideoStage?).swallowsBack())
    }
}
