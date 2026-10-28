// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.nav.MainSection
import tv.own.owntv.features.shell.PlayerMode

class LiveVideoStageTest {

    private val box = Rect(100f, 80f, 740f, 440f)
    private val full = Rect(0f, 0f, 1920f, 1080f)

    @Test
    fun playerBeatsBox() = assertEquals(VideoSite.PLAYER, pickTarget(box = true, player = true))

    @Test
    fun boxAloneGivesBox() = assertEquals(VideoSite.BOX, pickTarget(box = true, player = false))

    @Test
    fun playerAloneGivesPlayer() = assertEquals(VideoSite.PLAYER, pickTarget(box = false, player = true))

    @Test
    fun neitherGivesNoTarget() = assertNull(pickTarget(box = false, player = false))

    @Test
    fun theFirstSiteStartsSettled() {
        val stage = LiveVideoStage()
        stage.report(VideoSite.BOX, box, 28f, nowMs = 0)
        assertEquals(VideoSite.BOX, stage.target)
        assertEquals(box, stage.current)
        assertEquals(28f, stage.currentCornerPx)
        assertTrue(stage.settledOn(VideoSite.BOX))
        assertTrue(stage.visible)
    }

    @Test
    fun anArmedBridgeHoldsTheWindowFor599msAndDropsItAt600() {
        val stage = LiveVideoStage()
        stage.report(VideoSite.PLAYER, full, 0f, nowMs = 0)
        stage.armBridge()
        stage.clear(VideoSite.PLAYER, nowMs = 1_000)
        assertNull(stage.target)
        stage.frame(1_599)
        assertTrue(stage.visible)
        assertEquals(full, stage.current)
        stage.frame(1_600)
        assertFalse(stage.visible)
        assertNull(stage.current)
    }

    @Test
    fun theBoxReportingInsideTheBridgeKeepsTheWindowAndMovesIt() {
        val stage = LiveVideoStage(durationMs = 320)
        stage.report(VideoSite.PLAYER, full, 0f, nowMs = 0)
        stage.armBridge()
        stage.clear(VideoSite.PLAYER, nowMs = 1_000)
        stage.report(VideoSite.BOX, box, 28f, nowMs = 1_300)
        stage.frame(1_300)
        assertTrue(stage.visible)
        assertTrue(stage.arrivingAt(VideoSite.BOX))
        stage.frame(1_620)
        assertTrue(stage.settledOn(VideoSite.BOX))
        stage.frame(5_000)
        assertTrue(stage.visible)
    }

    @Test
    fun anUnarmedLeaveHidesTheWindowAtOnce() {
        val stage = LiveVideoStage()
        stage.report(VideoSite.BOX, box, 28f, nowMs = 0)
        stage.clear(VideoSite.BOX, nowMs = 1_000)
        assertFalse(stage.visible)
        stage.frame(1_016)
        assertFalse(stage.visible)
        assertNull(stage.current)
    }

    @Test
    fun aSiteReportingAgainBeforeTheNextFrameMovesOnFromWhereItWas() {
        // A dock can swap the player's node: it clears and reports again within one frame.
        val mini = Rect(1300f, 760f, 1880f, 1086f)
        val stage = LiveVideoStage(durationMs = 320)
        stage.report(VideoSite.PLAYER, full, 0f, nowMs = 0)
        stage.clear(VideoSite.PLAYER, nowMs = 1_000)
        stage.report(VideoSite.PLAYER, mini, 21f, nowMs = 1_000)
        stage.frame(1_000)
        assertTrue(stage.visible)
        assertEquals(full, stage.current)
        assertFalse(stage.settledOn(VideoSite.PLAYER))
        stage.frame(1_320)
        assertTrue(stage.settledOn(VideoSite.PLAYER))
    }

    @Test
    fun aSiteAfterTheWindowWentAppearsInPlace() {
        val stage = LiveVideoStage()
        stage.report(VideoSite.BOX, box, 28f, nowMs = 0)
        stage.clear(VideoSite.BOX, nowMs = 1_000)
        stage.frame(1_016)
        stage.report(VideoSite.PLAYER, full, 0f, nowMs = 1_100)
        assertTrue(stage.settledOn(VideoSite.PLAYER))
    }

    @Test
    fun graceRuleCountsFromTheLastClear() {
        assertFalse(graceOver(lastClearMs = 1_000, nowMs = 1_599))
        assertTrue(graceOver(lastClearMs = 1_000, nowMs = 1_600))
    }

    @Test
    fun anotherSiteStartsAMoveFromTheCurrentRectangle() {
        val stage = LiveVideoStage(durationMs = 320)
        stage.report(VideoSite.BOX, box, 28f, nowMs = 0)
        stage.report(VideoSite.PLAYER, full, 0f, nowMs = 1_000)
        assertEquals(VideoSite.PLAYER, stage.target)
        assertFalse(stage.settledOn(VideoSite.PLAYER))
        stage.frame(1_000)
        stage.frame(1_160)
        val mid = stage.current!!
        assertTrue(mid.width > box.width && mid.width < full.width)
        stage.frame(1_320)
        assertTrue(stage.settledOn(VideoSite.PLAYER))
        assertEquals(full, stage.current)
        assertEquals(0f, stage.currentCornerPx)
    }

    @Test
    fun aMoveStartsAtItsFirstFrameNotWhenItWasAskedFor() {
        // The TV draws the frame after OK ~350 ms late; the move must still play in full from there.
        val stage = LiveVideoStage(durationMs = 320)
        stage.report(VideoSite.BOX, box, 28f, nowMs = 0)
        stage.report(VideoSite.PLAYER, full, 0f, nowMs = 1_000)
        stage.frame(1_350)
        assertEquals(box, stage.current)
        stage.frame(1_510)
        val mid = stage.current!!
        assertTrue(mid.width > box.width && mid.width < full.width)
        stage.frame(1_669)
        assertFalse(stage.settledOn(VideoSite.PLAYER))
        stage.frame(1_670)
        assertTrue(stage.settledOn(VideoSite.PLAYER))
    }

    @Test
    fun aNewRectangleOnTheSameSiteStartsAMove() {
        val mini = Rect(1300f, 760f, 1880f, 1086f)
        val stage = LiveVideoStage(durationMs = 320)
        stage.report(VideoSite.PLAYER, full, 0f, nowMs = 0)
        assertTrue(stage.settledOn(VideoSite.PLAYER))
        stage.report(VideoSite.PLAYER, mini, 21f, nowMs = 1_000)
        assertFalse(stage.settledOn(VideoSite.PLAYER))
        stage.frame(1_000)
        stage.frame(1_319)
        assertFalse(stage.settledOn(VideoSite.PLAYER))
        stage.frame(1_320)
        assertTrue(stage.settledOn(VideoSite.PLAYER))
        assertEquals(mini, stage.current)
        assertEquals(21f, stage.currentCornerPx)
    }

    @Test
    fun aSettledSiteMovedAtTheSameSizeTakesTheWindowAlongAtOnce() {
        // The guide's layer slid the box: the box is there now, and the video must not trail it.
        val stage = LiveVideoStage(durationMs = 320)
        stage.report(VideoSite.BOX, box, 28f, nowMs = 0)
        val moved = box.translate(-548f, 0f)
        stage.report(VideoSite.BOX, moved, 28f, nowMs = 1_000)
        assertTrue(stage.settledOn(VideoSite.BOX))
        assertEquals(moved, stage.current)
    }

    @Test
    fun aReportOffByFloatRoundingChangesNothing() {
        val stage = LiveVideoStage(durationMs = 320)
        stage.report(VideoSite.PLAYER, full, 0f, nowMs = 0)
        stage.report(VideoSite.PLAYER, Rect(0.001f, 0f, 1920.001f, 1080f), 0f, nowMs = 1_000)
        assertEquals(full, stage.current)
        assertTrue(stage.settledOn(VideoSite.PLAYER))
    }

    @Test
    fun theBoxIsDrawnWithItsSlide() {
        var slide = 548f
        val stage = LiveVideoStage(durationMs = 320)
        stage.boxSlide = { slide }
        stage.report(VideoSite.BOX, box, 28f, nowMs = 0)
        assertEquals(box.translate(548f, 0f), stage.shown)
        slide = 0f
        assertEquals(box, stage.shown)
        assertTrue(stage.settledOn(VideoSite.BOX))
    }

    @Test
    fun aGrowFromASlidBoxStartsWhereTheBoxIsOnScreen() {
        val stage = LiveVideoStage(durationMs = 320)
        stage.boxSlide = { 548f }
        stage.report(VideoSite.BOX, box, 28f, nowMs = 0)
        stage.report(VideoSite.PLAYER, full, 0f, nowMs = 1_000)
        stage.frame(1_000)
        assertEquals(box.translate(548f, 0f), stage.shown)
        stage.frame(1_320)
        assertEquals(full, stage.shown)
    }

    @Test
    fun aShrinkIntoASlidBoxEndsOnTheBoxOnScreen() {
        val stage = LiveVideoStage(durationMs = 320)
        stage.boxSlide = { 548f }
        stage.report(VideoSite.PLAYER, full, 0f, nowMs = 0)
        stage.report(VideoSite.BOX, box, 28f, nowMs = 0)
        stage.clear(VideoSite.PLAYER, nowMs = 1_000)
        stage.frame(1_000)
        assertEquals(full, stage.shown)
        stage.frame(1_320)
        assertTrue(stage.settledOn(VideoSite.BOX))
        assertEquals(box.translate(548f, 0f), stage.shown)
    }

    @Test
    fun withNoDurationOneFrameSettles() {
        val stage = LiveVideoStage(durationMs = 0)
        stage.report(VideoSite.BOX, box, 28f, nowMs = 0)
        stage.report(VideoSite.PLAYER, full, 0f, nowMs = 100)
        assertFalse(stage.settledOn(VideoSite.PLAYER))
        stage.frame(100)
        assertTrue(stage.settledOn(VideoSite.PLAYER))
        assertEquals(full, stage.current)
    }

    @Test
    fun patchRuleWithNoTarget() {
        assertTrue(patchShows(null, settledOnTarget = false, lastCleared = VideoSite.PLAYER, bridgeArmed = true))
        assertFalse(patchShows(null, settledOnTarget = false, lastCleared = VideoSite.PLAYER, bridgeArmed = false))
        assertFalse(patchShows(null, settledOnTarget = false, lastCleared = VideoSite.BOX, bridgeArmed = true))
        assertFalse(patchShows(null, settledOnTarget = false, lastCleared = null, bridgeArmed = true))
    }

    @Test
    fun patchRuleWithATarget() {
        assertTrue(patchShows(VideoSite.BOX, settledOnTarget = false, lastCleared = null, bridgeArmed = false))
        assertFalse(patchShows(VideoSite.BOX, settledOnTarget = true, lastCleared = null, bridgeArmed = true))
    }

    @Test
    fun anArmedPlayerExitKeepsThePatch() {
        val stage = LiveVideoStage()
        stage.report(VideoSite.PLAYER, full, 0f, nowMs = 0)
        stage.armBridge()
        stage.clear(VideoSite.PLAYER, nowMs = 1_000)
        assertTrue(stage.patchVisible)
        stage.frame(1_599)
        assertTrue(stage.patchVisible)
        stage.frame(1_600)
        assertFalse(stage.patchVisible)
    }

    @Test
    fun aReportFromTheSiteAlreadyThereKeepsTheArm() {
        val stage = LiveVideoStage()
        stage.report(VideoSite.PLAYER, full, 0f, nowMs = 0)
        stage.armBridge()
        stage.report(VideoSite.PLAYER, Rect(0f, 0f, 1920f, 1000f), 0f, nowMs = 500)
        stage.clear(VideoSite.PLAYER, nowMs = 1_000)
        assertTrue(stage.patchVisible)
    }

    @Test
    fun anUnarmedPlayerExitHidesAtOnce() {
        val stage = LiveVideoStage()
        stage.report(VideoSite.PLAYER, full, 0f, nowMs = 0)
        stage.clear(VideoSite.PLAYER, nowMs = 1_000)
        assertFalse(stage.visible)
        assertFalse(stage.patchVisible)
    }

    @Test
    fun aBoxExitHidesAtOnceEvenWhenArmed() {
        val stage = LiveVideoStage()
        stage.report(VideoSite.BOX, box, 28f, nowMs = 0)
        stage.armBridge()
        stage.clear(VideoSite.BOX, nowMs = 1_000)
        assertFalse(stage.visible)
        assertFalse(stage.patchVisible)
    }

    @Test
    fun aPlayerLeavingWhileTheBoxIsThereSpendsTheArm() {
        // Back during the grow: the box is still reported when the player clears.
        val stage = LiveVideoStage(durationMs = 320)
        stage.report(VideoSite.BOX, box, 28f, nowMs = 0)
        stage.report(VideoSite.PLAYER, full, 0f, nowMs = 1_000)
        stage.armBridge()
        stage.clear(VideoSite.PLAYER, nowMs = 1_100)
        stage.frame(1_420)
        assertTrue(stage.settledOn(VideoSite.BOX))
        // Later, an unarmed player exit with no box hides at once.
        stage.report(VideoSite.PLAYER, full, 0f, nowMs = 2_000)
        stage.frame(2_320)
        stage.clear(VideoSite.BOX, nowMs = 2_400)
        stage.clear(VideoSite.PLAYER, nowMs = 2_500)
        assertFalse(stage.visible)
        assertFalse(stage.patchVisible)
    }

    @Test
    fun armingWithNoPlayerDoesNothing() {
        val stage = LiveVideoStage()
        stage.report(VideoSite.BOX, box, 28f, nowMs = 0)
        stage.armBridge() // stale: the player is not on screen
        stage.report(VideoSite.PLAYER, full, 0f, nowMs = 500)
        stage.frame(820)
        stage.clear(VideoSite.BOX, nowMs = 1_000)
        stage.clear(VideoSite.PLAYER, nowMs = 1_000)
        assertFalse(stage.visible)
        assertFalse(stage.patchVisible)
    }

    @Test
    fun aMoveToThePlayerCountsAsArrivingUntilItSettles() {
        val stage = LiveVideoStage(durationMs = 320)
        stage.report(VideoSite.BOX, box, 28f, nowMs = 0)
        // Full screen is up but the player has not reported yet: the window sits in the box.
        assertTrue(stage.arrivingAt(VideoSite.PLAYER))
        stage.report(VideoSite.PLAYER, full, 0f, nowMs = 1_000)
        stage.frame(1_000)
        stage.frame(1_319)
        assertTrue(stage.arrivingAt(VideoSite.PLAYER))
        stage.frame(1_320)
        assertFalse(stage.arrivingAt(VideoSite.PLAYER))
    }

    @Test
    fun withNoWindowThePlayerIsNeverArriving() {
        // An engine flip to ExoPlayer in full screen: no window yet, so the player appears in place.
        val stage = LiveVideoStage(durationMs = 320)
        assertFalse(stage.arrivingAt(VideoSite.PLAYER))
        stage.report(VideoSite.PLAYER, full, 0f, nowMs = 1_000)
        assertFalse(stage.arrivingAt(VideoSite.PLAYER))
        assertTrue(stage.settledOn(VideoSite.PLAYER))
    }

    @Test
    fun theArmIsSpentByTheNextReport() {
        val stage = LiveVideoStage()
        stage.report(VideoSite.PLAYER, full, 0f, nowMs = 0)
        stage.armBridge()
        stage.clear(VideoSite.PLAYER, nowMs = 1_000)
        stage.report(VideoSite.BOX, box, 28f, nowMs = 1_100)
        stage.frame(1_100)
        stage.frame(1_420)
        assertTrue(stage.settledOn(VideoSite.BOX))
        stage.report(VideoSite.PLAYER, full, 0f, nowMs = 2_000)
        stage.clear(VideoSite.BOX, nowMs = 2_000)
        stage.clear(VideoSite.PLAYER, nowMs = 2_100)
        assertFalse(stage.patchVisible)
    }

    @Test
    fun backToGridWhenAllHold() = assertTrue(
        isBackToGrid(PlayerMode.FULLSCREEN, MainSection.LIVE_TV, MainSection.LIVE_TV, liveOnExo = true, catchupActive = false, rewound = false, boxSection = MainSection.LIVE_TV),
    )

    @Test
    fun notBackToGridFromTheMiniPlayerOrAudioMode() {
        for (mode in listOf(PlayerMode.NONE, PlayerMode.MINI, PlayerMode.AUDIO)) {
            assertFalse(isBackToGrid(mode, MainSection.LIVE_TV, MainSection.LIVE_TV, liveOnExo = true, catchupActive = false, rewound = false, boxSection = MainSection.LIVE_TV))
        }
    }

    @Test
    fun backToSearchWhenAllHold() = assertTrue(
        isBackToGrid(PlayerMode.FULLSCREEN, MainSection.SEARCH, MainSection.LIVE_TV, liveOnExo = true, catchupActive = false, rewound = false, boxSection = MainSection.SEARCH),
    )

    @Test
    fun notBackToGridOutsideLiveTvSearchAndSports() {
        assertFalse(isBackToGrid(PlayerMode.FULLSCREEN, MainSection.HOME, MainSection.LIVE_TV, liveOnExo = true, catchupActive = false, rewound = false, boxSection = null))
        assertFalse(isBackToGrid(PlayerMode.FULLSCREEN, MainSection.EPG, MainSection.LIVE_TV, liveOnExo = true, catchupActive = false, rewound = false, boxSection = null))
        assertFalse(isBackToGrid(PlayerMode.FULLSCREEN, MainSection.LIVE_TV, MainSection.MOVIES, liveOnExo = true, catchupActive = false, rewound = false, boxSection = MainSection.LIVE_TV))
        assertFalse(isBackToGrid(PlayerMode.FULLSCREEN, MainSection.LIVE_TV, null, liveOnExo = true, catchupActive = false, rewound = false, boxSection = MainSection.LIVE_TV))
        // A Sports box does not make another screen a box screen.
        assertFalse(isBackToGrid(PlayerMode.FULLSCREEN, MainSection.HOME, MainSection.LIVE_TV, liveOnExo = true, catchupActive = false, rewound = false, boxSection = MainSection.SPORTS))
    }

    @Test
    fun backToSportsWhenTheBoxIsSports() = assertTrue(
        isBackToGrid(PlayerMode.FULLSCREEN, MainSection.SPORTS, MainSection.LIVE_TV, liveOnExo = true, catchupActive = false, rewound = false, boxSection = MainSection.SPORTS),
    )

    @Test
    fun notBackToSportsWithoutASportsBox() {
        for (box in listOf(null, MainSection.LIVE_TV, MainSection.SEARCH)) {
            assertFalse(isBackToGrid(PlayerMode.FULLSCREEN, MainSection.SPORTS, MainSection.LIVE_TV, liveOnExo = true, catchupActive = false, rewound = false, boxSection = box))
        }
    }

    @Test
    fun notBackToSportsWhenAnotherConditionFails() {
        val box = MainSection.SPORTS
        assertFalse(isBackToGrid(PlayerMode.MINI, MainSection.SPORTS, MainSection.LIVE_TV, liveOnExo = true, catchupActive = false, rewound = false, boxSection = box))
        assertFalse(isBackToGrid(PlayerMode.FULLSCREEN, MainSection.SPORTS, MainSection.MOVIES, liveOnExo = true, catchupActive = false, rewound = false, boxSection = box))
        assertFalse(isBackToGrid(PlayerMode.FULLSCREEN, MainSection.SPORTS, MainSection.LIVE_TV, liveOnExo = false, catchupActive = false, rewound = false, boxSection = box))
        assertFalse(isBackToGrid(PlayerMode.FULLSCREEN, MainSection.SPORTS, MainSection.LIVE_TV, liveOnExo = true, catchupActive = true, rewound = false, boxSection = box))
        assertFalse(isBackToGrid(PlayerMode.FULLSCREEN, MainSection.SPORTS, MainSection.LIVE_TV, liveOnExo = true, catchupActive = false, rewound = true, boxSection = box))
    }

    @Test
    fun notBackToGridOnMpv() =
        assertFalse(isBackToGrid(PlayerMode.FULLSCREEN, MainSection.LIVE_TV, MainSection.LIVE_TV, liveOnExo = false, catchupActive = false, rewound = false, boxSection = MainSection.LIVE_TV))

    @Test
    fun notBackToGridDuringCatchup() =
        assertFalse(isBackToGrid(PlayerMode.FULLSCREEN, MainSection.LIVE_TV, MainSection.LIVE_TV, liveOnExo = true, catchupActive = true, rewound = false, boxSection = MainSection.LIVE_TV))

    @Test
    fun notBackToGridWhenRewound() =
        assertFalse(isBackToGrid(PlayerMode.FULLSCREEN, MainSection.LIVE_TV, MainSection.LIVE_TV, liveOnExo = true, catchupActive = false, rewound = true, boxSection = MainSection.LIVE_TV))

    @Test
    fun lerpRectEndsAndMiddle() {
        val from = Rect(0f, 0f, 100f, 50f)
        val to = Rect(100f, 200f, 300f, 450f)
        assertEquals(from, lerpRect(from, to, 0f))
        assertEquals(to, lerpRect(from, to, 1f))
        assertEquals(Rect(50f, 100f, 200f, 250f), lerpRect(from, to, 0.5f))
    }

    @Test
    fun lerpCornerEndsAndMiddle() {
        assertEquals(28f, lerpCorner(28f, 0f, 0f))
        assertEquals(0f, lerpCorner(28f, 0f, 1f))
        assertEquals(14f, lerpCorner(28f, 0f, 0.5f))
    }
}
