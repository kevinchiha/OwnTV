// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

/**
 * ▲ ▼ in the category list (the Live TV sidebar and every other LiveCategories list) by row number,
 * as the channel grid steps (fix playbook 2). Compose's own focus search moved the highlight ahead of
 * the list's animated scroll; a held key then reached rows not laid out yet, the search found nothing,
 * and the list stopped until the scroll caught up (Kevin, 2026-10-13: "very laggy and stops").
 *
 * A row's place in the list ("pos") is not its LazyColumn item: the search field is item 0, and the
 * "GROUPS" heading, when it shows, is an item of its own just before row [headingPos] (-1 = none).
 */
internal object CategorySteps {

    /** The LazyColumn item that row [pos] is. */
    fun itemIndex(pos: Int, headingPos: Int): Int = pos + 1 + if (headingPos in 0..pos) 1 else 0

    /** The row that LazyColumn item [itemIndex] is (the inverse of [itemIndex]). */
    fun rowPos(itemIndex: Int, headingPos: Int): Int = itemIndex - 1 - if (headingPos >= 0 && itemIndex > headingPos + 1) 1 else 0

    /**
     * The row a press of [delta] (+1 ▼, -1 ▲) moves to from row [from], out of [rows]. Null for ▲ on the
     * first row, which is left to focus search so it reaches the search field; ▼ on the last row stays.
     */
    fun next(from: Int, delta: Int, rows: Int): Int? {
        val to = from + delta
        return if (to < 0) null else to.coerceAtMost(rows - 1)
    }
}
