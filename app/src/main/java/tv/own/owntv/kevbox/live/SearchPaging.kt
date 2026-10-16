// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

/**
 * CH+ / CH− in Search's results (Kevin, 2026-10-16) move by upstream's skip counts, which count every
 * row of the list, group headings included. A heading takes no focus, so a step landing on one goes on
 * to the first result under it, or back to the last one above it when nothing follows.
 *
 * [isResult] says, row by row, which rows take focus. Returns the row to focus, or null when no row does.
 */
fun searchPageTarget(isResult: List<Boolean>, index: Int): Int? {
    if (isResult.getOrNull(index) == true) return index
    val below = (index + 1 until isResult.size).firstOrNull { isResult[it] }
    if (below != null) return below
    return (minOf(index, isResult.size) - 1 downTo 0).firstOrNull { isResult[it] }
}
