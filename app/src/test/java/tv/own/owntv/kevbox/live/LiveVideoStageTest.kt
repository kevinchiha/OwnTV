// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun theWindowHoldsFor599msAfterTheLastClearAndGoesAt600() {
        val stage = LiveVideoStage()
        stage.report(VideoSite.BOX, box, 28f, nowMs = 0)
        stage.clear(VideoSite.BOX, nowMs = 1_000)
        assertNull(stage.target)
        stage.frame(1_599)
        assertTrue(stage.visible)
        assertEquals(box, stage.current)
        stage.frame(1_600)
        assertFalse(stage.visible)
        assertNull(stage.current)
    }

    @Test
    fun aSiteReturningInsideTheGracePeriodKeepsTheWindow() {
        val stage = LiveVideoStage()
        stage.report(VideoSite.BOX, box, 28f, nowMs = 0)
        stage.clear(VideoSite.BOX, nowMs = 1_000)
        stage.report(VideoSite.BOX, box, 28f, nowMs = 1_300)
        stage.frame(5_000)
        assertTrue(stage.visible)
        assertTrue(stage.settledOn(VideoSite.BOX))
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
        stage.frame(1_160)
        val mid = stage.current!!
        assertTrue(mid.width > box.width && mid.width < full.width)
        stage.frame(1_320)
        assertTrue(stage.settledOn(VideoSite.PLAYER))
        assertEquals(full, stage.current)
        assertEquals(0f, stage.currentCornerPx)
    }

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
