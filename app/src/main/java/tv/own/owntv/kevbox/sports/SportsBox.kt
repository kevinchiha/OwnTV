// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.sports

import tv.own.owntv.core.nav.MainSection
import tv.own.owntv.kevbox.live.LiveVideoStage

/**
 * The channel row that takes focus when Back from full screen returns to a match's Channels tab.
 * Back puts the playing channel in the box, so its row comes first. After CH+/CH− that channel may
 * not be in the match's list, so the row OK was pressed on comes next, then the first row. Null when
 * the list is empty.
 */
fun sportsReturnChannelId(playingId: Long?, lastPlayedId: Long?, shownIds: List<Long>): Long? = when {
    playingId != null && playingId in shownIds -> playingId
    lastPlayedId != null && lastPlayedId in shownIds -> lastPlayedId
    else -> shownIds.firstOrNull()
}

/**
 * The channel row that Left from the right column's Watch live block (the filter chips, Find
 * channels) goes back to: the row that last had focus while it is still listed, else the first row.
 * That row leaves the list when Find channels finishes or the filter changes. Null when the tab shows
 * no rows, and the caller then focuses the Channels tab.
 */
fun sportsBlockLeftRowId(lastFocusedId: Long?, rowIds: List<Long>): Long? =
    lastFocusedId?.takeIf { it in rowIds } ?: rowIds.firstOrNull()

/**
 * The box is Sports' only while a match page shows it. Leaving that page (Back, the league page,
 * another match) empties the box when Sports filled it, and returns true so the caller stops the
 * preview as well. A box another screen filled is left alone.
 */
fun clearSportsBox(stage: LiveVideoStage?): Boolean {
    if (stage == null || stage.boxSection != MainSection.SPORTS) return false
    stage.clearBox()
    return true
}
