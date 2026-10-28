// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.sports

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
