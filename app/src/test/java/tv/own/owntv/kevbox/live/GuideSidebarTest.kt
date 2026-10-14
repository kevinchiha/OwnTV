// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.kevbox.live.GuideSidebar.Event
import tv.own.owntv.kevbox.live.GuideSidebar.Move

class GuideSidebarTest {

    @Test
    fun menuFocusCollapses() {
        assertFalse(GuideSidebar.reduce(expanded = true, event = Event.MENU_FOCUSED))
        assertFalse(GuideSidebar.reduce(expanded = false, event = Event.MENU_FOCUSED))
    }

    @Test
    fun aPurposefulGridEntryExpands() {
        assertTrue(GuideSidebar.reduce(expanded = false, event = Event.GRID_ENTERED))
        assertTrue(GuideSidebar.reduce(expanded = true, event = Event.GRID_ENTERED))
    }

    @Test
    fun leftOrBackOnARowCollapses() =
        assertFalse(GuideSidebar.reduce(expanded = true, event = Event.ROW_LEFT_OR_BACK))

    @Test
    fun aGridFocusWhileEnteringTheColumnLeavesTheLayoutAlone() {
        assertFalse(GuideSidebar.reduce(expanded = false, event = Event.GRID_FOCUSED_DURING_COLUMN_ENTRY))
        assertTrue(GuideSidebar.reduce(expanded = true, event = Event.GRID_FOCUSED_DURING_COLUMN_ENTRY))
    }

    @Test
    fun aDialogLeavesTheLayoutAlone() {
        assertFalse(GuideSidebar.reduce(expanded = false, event = Event.DIALOG_OPENED))
        assertTrue(GuideSidebar.reduce(expanded = true, event = Event.DIALOG_OPENED))
    }

    @Test
    fun aCategoryNotShownYetIsSelectedFirst() =
        assertEquals(Move.SELECT_THEN_ENTER, GuideSidebar.rightOrOk(cursor = "News", shown = "Sport", shownChannels = 40, loadPending = false))

    @Test
    fun theShownCategoryWithChannelsEnters() =
        assertEquals(Move.ENTER, GuideSidebar.rightOrOk(cursor = "Sport", shown = "Sport", shownChannels = 40, loadPending = false))

    @Test
    fun anEmptyShownCategoryStays() =
        assertEquals(Move.STAY, GuideSidebar.rightOrOk(cursor = "Sport", shown = "Sport", shownChannels = 0, loadPending = false))

    @Test
    fun aPendingLoadWaitsInsteadOfEnteringWhatIsOnScreen() {
        // The cursor came back to the shown category while another one was still loading.
        assertEquals(Move.SELECT_THEN_ENTER, GuideSidebar.rightOrOk(cursor = "Sport", shown = "Sport", shownChannels = 40, loadPending = true))
        assertEquals(Move.SELECT_THEN_ENTER, GuideSidebar.rightOrOk(cursor = "Sport", shown = "Sport", shownChannels = 0, loadPending = true))
    }
}
