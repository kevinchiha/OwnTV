// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

/**
 * The rules behind Live TV's category column (EpgScreen in liveMode). The column always sits beside the
 * main menu; the guide is either collapsed (moved right to make room for the menu and the column) or
 * expanded (the menu and the column slid off to the left, the guide filling the screen).
 *
 * The guide expands only on a deliberate move into it and collapses only when the menu takes focus or
 * ◀ / Back leaves a channel row. Anything else, a row briefly taking focus while focus is being sent
 * into the column or a menu opening over the guide, leaves the layout alone.
 */
internal object GuideSidebar {

    /** The column's width, in mpx. The guide moves by the menu's room plus this. */
    const val COLUMN_WIDTH_MPX = 420

    /** How long the slide takes; the menu's own slide in StageRail takes the same. */
    const val SLIDE_MS = 320

    /** How long the cursor rests on a category before the guide behind shows it. */
    const val REST_SELECT_MS = 300L

    enum class Event {
        /** The main menu took focus. */
        MENU_FOCUSED,

        /** Focus went into the grid on purpose: ▶ or OK from the column, back from the full-screen
         *  player, or Search's "Go to channel". */
        GRID_ENTERED,

        /** A grid row took focus on its way elsewhere, while focus was being sent into the column. */
        GRID_FOCUSED_DURING_COLUMN_ENTRY,

        /** ◀ or Back on a channel row, outside its programmes. */
        ROW_LEFT_OR_BACK,

        /** A programme or channel menu, or a dialog, opened over the guide. */
        DIALOG_OPENED,
    }

    /** Whether the guide is expanded after [event], given it was [expanded] before. */
    fun reduce(expanded: Boolean, event: Event): Boolean = when (event) {
        Event.MENU_FOCUSED, Event.ROW_LEFT_OR_BACK -> false
        Event.GRID_ENTERED -> true
        Event.GRID_FOCUSED_DURING_COLUMN_ENTRY, Event.DIALOG_OPENED -> expanded
    }

    /** What ▶ or OK on a category in the column does. */
    enum class Move {
        /** Select the category now, then enter the grid once its channels are there (or stay, if none). */
        SELECT_THEN_ENTER,

        /** The guide already shows this category's channels: enter the grid. */
        ENTER,

        /** The guide shows this category and it has no channels: nothing to enter. */
        STAY,
    }

    /**
     * The move for ▶ / OK with the cursor on [cursor], while the guide shows [shown] with [shownChannels]
     * channels. [loadPending] is true while a category was asked for and its channels have not come in:
     * what is on screen may be about to change, so the move waits for the load instead of entering.
     */
    fun <K> rightOrOk(cursor: K, shown: K, shownChannels: Int, loadPending: Boolean): Move = when {
        cursor != shown || loadPending -> Move.SELECT_THEN_ENTER
        shownChannels > 0 -> Move.ENTER
        else -> Move.STAY
    }
}
