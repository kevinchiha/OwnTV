// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.kevbox.live.GuideSidebar.Entry
import tv.own.owntv.kevbox.live.GuideSidebar.Exit
import tv.own.owntv.kevbox.live.GuideSidebar.Focus
import tv.own.owntv.kevbox.live.GuideSidebar.Move

class GuideSidebarTest {

    private fun expanded(was: Boolean, focus: Focus, entry: Entry = Entry.NONE) = GuideSidebar.expanded(was, focus, entry)

    @Test
    fun aRowHoldingFocusExpands() {
        assertTrue(expanded(was = false, focus = Focus.GRID))
        assertTrue(expanded(was = true, focus = Focus.GRID))
    }

    @Test
    fun theColumnHoldingFocusCollapses() {
        // Up from the first row or a move into the grid that never landed: the cursor is in the column,
        // so the column shows.
        assertFalse(expanded(was = true, focus = Focus.COLUMN))
        assertFalse(expanded(was = false, focus = Focus.COLUMN))
    }

    @Test
    fun theMenuInUseCollapses() {
        assertFalse(expanded(was = true, focus = Focus.MENU))
        assertFalse(expanded(was = false, focus = Focus.MENU))
    }

    @Test
    fun focusElsewhereLeavesTheLayoutAlone() {
        // A dialog over the guide, nothing focused, or the menu holding focus for a moment by itself.
        assertTrue(expanded(was = true, focus = Focus.ELSEWHERE))
        assertFalse(expanded(was = false, focus = Focus.ELSEWHERE))
    }

    @Test
    fun aMoveIntoTheGridOutranksTheMenuHoldingFocusOnItsWay() {
        // Back from full screen: the shell parks focus on the menu until the grid's row takes it.
        assertTrue(expanded(was = true, focus = Focus.MENU, entry = Entry.INTO_GRID))
        assertTrue(expanded(was = false, focus = Focus.MENU, entry = Entry.INTO_GRID))
        // ▶ from the column: the column holds focus until the row takes it.
        assertTrue(expanded(was = false, focus = Focus.COLUMN, entry = Entry.INTO_GRID))
        assertTrue(expanded(was = true, focus = Focus.ELSEWHERE, entry = Entry.INTO_GRID))
    }

    @Test
    fun aMoveIntoTheColumnOutranksARowFocusOnItsWay() {
        // ▶ from the menu: the shell's focus restorer sends the first request to the row last focused.
        assertFalse(expanded(was = false, focus = Focus.GRID, entry = Entry.INTO_COLUMN))
        // ◀ / Back on a row: the row still holds focus until the column takes it.
        assertFalse(expanded(was = true, focus = Focus.GRID, entry = Entry.INTO_COLUMN))
    }

    @Test
    fun aMoveIntoTheGridWaitsForTheChannels() {
        assertEquals(Exit.WAIT, GuideSidebar.exit(loading = true, stateIsOld = false, channels = 0))
        assertEquals(Exit.WAIT, GuideSidebar.exit(loading = false, stateIsOld = true, channels = 40))
        assertEquals(Exit.EMPTY, GuideSidebar.exit(loading = false, stateIsOld = false, channels = 0))
        assertEquals(Exit.READY, GuideSidebar.exit(loading = false, stateIsOld = false, channels = 40))
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
