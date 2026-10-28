// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.sports

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.Sport

/**
 * Fork switch for the Sports hub's "Sport on your channels now" section. Off: Kevin asked for the
 * section to go (2026-10-28). The view model still scans the guide for it; nothing else shows it.
 */
const val SHOW_ON_CHANNELS_NOW = false

/**
 * The hub's Starting soon matches as one row per sport: sports in [order] first (the chip order),
 * then any other sport in Core's [Sport] order. A sport keeps at most [perSport] matches, in the
 * order [soon] gives them. Sports with no match get no row.
 */
fun soonBySport(soon: List<Match>, order: List<Sport>, perSport: Int = 12): List<Pair<Sport, List<Match>>> {
    val bySport = soon.groupBy { it.sport }
    val sports = order.distinct() + Sport.entries.filter { it !in order }
    return sports.mapNotNull { s -> bySport[s]?.take(perSport)?.takeIf { it.isNotEmpty() }?.let { s to it } }
}

/** Where the card with [key] sits in [groups]: its row and its place in that row, or null when no row holds it. */
fun soonCardAt(groups: List<Pair<Sport, List<Match>>>, key: String): Pair<Int, Int>? {
    groups.forEachIndexed { row, (_, matches) ->
        val at = matches.indexOfFirst { it.key == key }
        if (at >= 0) return row to at
    }
    return null
}

/**
 * The hub column's item index of soon row [row]. The soon rows are the column's last items, so the
 * index counts back from [totalItems] (the column's item count) by [rows] (how many soon rows).
 */
fun soonRowItem(totalItems: Int, rows: Int, row: Int): Int = totalItems - rows + row

/**
 * The hub's Starting soon rows: one scroll state per sport, kept while the hub is shown, so a row
 * keeps its place when the hub scrolls it away and back. Back from a match uses [reveal] to bring the
 * card it was opened from on screen before focusing it.
 */
@Stable
class SoonRows {
    private val states = HashMap<Sport, LazyListState>()
    private var filter: Any? = null
    private var filterSeen = false

    /** The rows last drawn; the hub sets this each time it groups the matches. */
    var groups: List<Pair<Sport, List<Match>>> = emptyList()

    /** [sport]'s row scroll state. */
    fun of(sport: Sport): LazyListState = states.getOrPut(sport) { LazyListState() }

    /**
     * Call with the chips and leagues each time they change. A change puts every row back on its first
     * card; the first call only records them, so the hub's first draw after Back keeps a restored row.
     */
    fun onFilter(chosen: Any) {
        if (filterSeen && chosen != filter) states.values.forEach { it.requestScrollToItem(0) }
        filter = chosen
        filterSeen = true
    }

    /**
     * Scrolls [hub] to the soon row holding the card with [key], and that row to the card, so its
     * focus requester attaches on the next frame. Does nothing when no soon row holds it.
     */
    fun reveal(key: String, hub: LazyListState) {
        val (row, at) = soonCardAt(groups, key) ?: return
        val item = soonRowItem(hub.layoutInfo.totalItemsCount, groups.size, row)
        if (item < 0) return
        hub.requestScrollToItem(item)
        of(groups[row].first).requestScrollToItem(at)
    }
}
