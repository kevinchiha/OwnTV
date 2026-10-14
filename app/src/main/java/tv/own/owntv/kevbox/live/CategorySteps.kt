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
     * The row a press of [delta] (+1 ▼, -1 ▲) moves to from row [from], out of [rows]. Null for ▲ past
     * the first row, which is left to focus search so it reaches the search field.
     *
     * KevBox (Kevin, 2026-10-14): a key held for [heldMs] moves [stepSize] rows a press, so a long list
     * is quick to cross. ▼ on the last row wraps to the first on a [fresh] press only (not a repeat
     * of a held key); a held key stops at the end instead of going round and round.
     */
    fun next(from: Int, delta: Int, rows: Int, heldMs: Long = 0, fresh: Boolean = false): Int? {
        if (fresh && delta > 0 && from >= rows - 1) return 0
        val to = from + delta * stepSize(heldMs)
        return if (to < 0) null else to.coerceAtMost(rows - 1)
    }

    /** ▲ on the search field, the list's top: a [fresh] press wraps to the last row, a held key stays. */
    fun fromSearch(rows: Int, fresh: Boolean): Int? = if (fresh && rows > 0) rows - 1 else null

    /** Rows a press moves while the key has been held [heldMs]: 1 for the first second, then 3, then 6. */
    fun stepSize(heldMs: Long): Int = when {
        heldMs < 1_000 -> 1
        heldMs < 2_000 -> 3
        else -> 6
    }
}
