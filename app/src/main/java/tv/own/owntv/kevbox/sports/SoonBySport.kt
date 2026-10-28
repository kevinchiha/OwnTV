// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.sports

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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

/** The hub column's item key of [sport]'s Starting soon row. */
fun soonRowKey(sport: Sport): String = "soon-${sport.name}"

/**
 * The hub column's item index of soon row [row], counted back from [totalItems] (the column's item
 * count) by [rows] (how many soon rows). Right only while the soon rows are the column's last items;
 * [soonRowScroll] checks that against the item keys before and after it scrolls.
 */
fun soonRowItem(totalItems: Int, rows: Int, row: Int): Int = totalItems - rows + row

/** What [SoonRows.reveal] does with the hub column for one soon row. */
enum class RowScroll {
    /** The row's item is drawn: leave the column where it is. */
    SHOWN,

    /** Scroll the column to the counted index. */
    SCROLL,

    /** The counted index is drawn under another key, so the count is wrong: leave the column alone. */
    WRONG,
}

/**
 * Checks the counted index [counted] of the soon row with key [rowKey] against the column's drawn
 * items, [drawn] as (index, key). After a scroll to [counted] that index is drawn, so a row that is
 * still missing shows up here as [RowScroll.WRONG] and the reveal stops.
 */
fun soonRowScroll(drawn: List<Pair<Int, Any>>, rowKey: String, counted: Int): RowScroll = when {
    drawn.any { it.second == rowKey } -> RowScroll.SHOWN
    counted < 0 || drawn.any { it.first == counted } -> RowScroll.WRONG
    else -> RowScroll.SCROLL
}

/**
 * The hub's Starting soon rows: one scroll state per sport, kept while the hub is shown, so a row
 * keeps its place when the hub scrolls it away and back. Back from a match uses [reveal] to bring the
 * card it was opened from on screen before focusing it.
 *
 * Not marked @Stable: [groups] and the filter fields are plain vars that Compose does not watch. The
 * hub only holds it in a remember and never passes it to a composable, so nothing skips on it.
 */
class SoonRows {
    private val states = HashMap<Sport, LazyListState>()
    private var filter: Any? = null
    private var filterSeen = false

    /** The rows last drawn; the hub sets this after each composition (a SideEffect). */
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
     * focus requester attaches on the next frame. Does nothing when no soon row holds it, or when the
     * column shows another item where the row should be ([RowScroll.WRONG]).
     */
    fun reveal(key: String, hub: LazyListState) {
        val (row, at) = soonCardAt(groups, key) ?: return
        val info = hub.layoutInfo
        val counted = soonRowItem(info.totalItemsCount, groups.size, row)
        val drawn = info.visibleItemsInfo.map { it.index to it.key }
        when (soonRowScroll(drawn, soonRowKey(groups[row].first), counted)) {
            RowScroll.WRONG -> return
            RowScroll.SCROLL -> hub.requestScrollToItem(counted)
            RowScroll.SHOWN -> Unit
        }
        of(groups[row].first).requestScrollToItem(at)
    }
}

/**
 * The rectangle a focused soon row asks the hub column to show: the whole row, [size], plus [below]
 * pixels under it, where the key hints sit.
 */
fun wholeRowRect(size: IntSize, below: Float): Rect = Rect(0f, 0f, size.width.toFloat(), size.height + below)

/**
 * Keeps a focused Starting soon row whole on screen, cards and all, above the hub's key hints. When
 * focus enters the row, it asks the column for [wholeRowRect]. The column's own scrolling then moves
 * only as far as that needs, so a row that already fits does not move, and only soon rows get it.
 */
class WholeRow {
    internal val requester = BringIntoViewRequester()
    internal var size = IntSize.Zero
}

/** [WholeRow] on the row's outer layout; [row] is remembered with the row. [below] is the key hints' height in pixels. */
fun Modifier.wholeAboveHints(row: WholeRow, scope: CoroutineScope, below: Float): Modifier = this
    .bringIntoViewRequester(row.requester)
    .onSizeChanged { row.size = it }
    .onFocusChanged { if (it.hasFocus) scope.launch { row.requester.bringIntoView(wholeRowRect(row.size, below)) } }
