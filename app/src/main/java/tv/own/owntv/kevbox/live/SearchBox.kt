// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

/*
 * Search's video box (Kevin, 2026-10-14). A channel found by Search plays the way the Live TV grid's
 * does: the first OK puts it in the box on the right, OK on the channel already there opens it full
 * screen, and Back shrinks it into the box again with the results still there. CH+/CH− in full screen
 * step through the channel's own category, as from the grid.
 */

/** What OK on a search result does. */
enum class SearchOk { PREVIEW, WATCH }

/**
 * OK on [channelId] with [boxChannelId] in the box. [previewAvailable] is false while the mini player
 * owns the picture: there is no box to preview in, so one OK watches.
 */
fun searchOk(channelId: Long, boxChannelId: Long?, previewAvailable: Boolean): SearchOk =
    if (previewAvailable && boxChannelId != channelId) SearchOk.PREVIEW else SearchOk.WATCH

/**
 * The row focus lands on coming back from full screen: the one OK left from ([lastKey], about
 * [lastKeyChannelId]), unless CH+/CH− moved on to [playingChannelId] and that channel has a row of its
 * own in the results ([playingRowKey], null when it has none).
 */
fun searchReturnKey(lastKey: String?, lastKeyChannelId: Long?, playingChannelId: Long?, playingRowKey: String?): String? =
    if (playingChannelId != null && playingChannelId != lastKeyChannelId && playingRowKey != null) playingRowKey else lastKey

/** The two tabs over a typed search's results: channels by name, and shows from the guide. */
enum class SearchShowTab { CHANNELS, SHOWS }

/**
 * The tab a search shows. The one the user moved to ([chosen]) wins; with none, a new query opens on
 * Channels, unless it found no channel and at least one show, which opens the TV guide tab.
 */
fun pickShowTab(chosen: SearchShowTab?, channelCount: Int, showCount: Int): SearchShowTab =
    chosen ?: if (channelCount == 0 && showCount > 0) SearchShowTab.SHOWS else SearchShowTab.CHANNELS
