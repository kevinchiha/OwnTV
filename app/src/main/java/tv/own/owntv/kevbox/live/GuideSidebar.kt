// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

/**
 * The rules behind Live TV's category column (EpgScreen in liveMode). The column always sits beside the
 * main menu; the guide is either collapsed (moved right to make room for the menu and the column) or
 * expanded (the menu and the column slid off to the left, the guide filling the screen).
 *
 * The layout follows focus ([expanded]): a channel row holding focus means expanded, the column or the
 * menu in use means collapsed. A move the screen has started outranks where focus sits on its way, so a
 * row that focus passes through while it is sent into the column, or the main menu holding focus while
 * the player hands back to the grid, changes nothing. Focus anywhere else (a dialog, nothing at all, a
 * passing visit to the menu) leaves the layout as it was.
 */
internal object GuideSidebar {

    /**
     * The column's width, in mpx. The guide moves by the menu's room plus this. 320 since 2026-10-14
     * (was 420): Kevin marked the empty space between the longest names and the counts to cut.
     */
    const val COLUMN_WIDTH_MPX = 320

    /** How long the slide takes; the menu's own slide in StageRail takes the same. */
    const val SLIDE_MS = 320

    /** How long the cursor rests on a category before the guide behind shows it. */
    const val REST_SELECT_MS = 300L

    /** Where focus is, as far as the layout goes. */
    enum class Focus {
        /** A channel row, or a programme inside one. */
        GRID,

        /** The category column: a category or its search field. */
        COLUMN,

        /** The main menu, with the user on it (not a passing visit focus makes by itself). */
        MENU,

        /** Anywhere else: a dialog or menu over the guide, nothing, a passing visit to the main menu. */
        ELSEWHERE,
    }

    /** A move into the grid or the column that the screen has started and that has not landed yet. */
    enum class Entry {
        NONE,

        /** ▶ or OK from the column once the channels are in, the way back from full screen, or a reveal
         *  (Search's "Go to channel", CH+ / CH- then Back). */
        INTO_GRID,

        /** ▶ from the main menu, or ◀ / Back on a channel row. */
        INTO_COLUMN,
    }

    /** Whether the guide is expanded, given it [was] before, where focus is now and the move under way. */
    fun expanded(was: Boolean, focus: Focus, entry: Entry): Boolean = when (entry) {
        Entry.INTO_GRID -> true
        Entry.INTO_COLUMN -> false
        Entry.NONE -> when (focus) {
            Focus.GRID -> true
            Focus.COLUMN, Focus.MENU -> false
            Focus.ELSEWHERE -> was
        }
    }

    /** Where a pending move into the grid stands. */
    enum class Exit {
        /** The guide is loading, or still shows the channels from before the category was asked for. */
        WAIT,

        /** The channels are in and there are none: nothing to land on. */
        EMPTY,

        /** The channels are in: the move can go ahead. */
        READY,
    }

    /**
     * Where a pending move into the grid stands. [stateIsOld] is true while the guide still shows the
     * state from before a category change the move waits for.
     */
    fun exit(loading: Boolean, stateIsOld: Boolean, channels: Int): Exit = when {
        loading || stateIsOld -> Exit.WAIT
        channels == 0 -> Exit.EMPTY
        else -> Exit.READY
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
