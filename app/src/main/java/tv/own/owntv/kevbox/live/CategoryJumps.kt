// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

/**
 * The "Jump to" list that Hold OK opens in a category list (Kevin, 2026-10-14): one line per block of
 * categories, alphabetical with the fixed rows first and [Block.Other] last, so a long list (350 rows
 * on Kevin's playlist) is two presses from anywhere.
 *
 * A category's block is its provider prefix ("FR|", "AFR|", spaces before the bar dropped). One with no
 * prefix takes its first word when another unprefixed category shares it ("24/7 DISNEY+", "24/7 CRIME
 * VIP"); the rest go under [Block.Other]. A prefix split over several stretches of the list (US| in
 * three on Kevin's playlist) is one line that jumps to its first category. The fixed rows above the
 * categories (Favorites, All channels…) are [Block.Top].
 *
 * Rows ("row") are places in the list as LiveCategories counts them, the search field not included.
 */
internal class CategoryJumps private constructor(val groups: List<JumpGroup>, private val rowGroup: IntArray) {

    sealed interface Block {
        data object Top : Block
        data class Named(val name: String) : Block
        data object Other : Block
    }

    /** A line of the list: [count] categories, the first of them at [firstRow]. */
    data class JumpGroup(val block: Block, val count: Int, val firstRow: Int)

    /** The line row [row] belongs to, for the cursor to start on; 0 for a row outside the list. */
    fun groupOf(row: Int): Int = rowGroup.getOrElse(row) { 0 }

    companion object {

        /** "FR|" for "FR| FRANCE HD" or "FR | FRANCE HD": two to four capitals before a bar; else null. */
        fun prefixOf(label: String): String? {
            val bar = label.indexOf('|')
            if (bar < 0) return null
            val letters = label.substring(0, bar).trimEnd()
            if (letters.length !in 2..4 || letters.any { it !in 'A'..'Z' }) return null
            return letters + '|'
        }

        /**
         * The jump list for rows labelled [labels], categories starting at row [firstCategoryRow]: rows
         * before it are the fixed ones; -1 means there are no categories at all.
         */
        fun of(labels: List<String>, firstCategoryRow: Int): CategoryJumps {
            val fixed = if (firstCategoryRow < 0) labels.size else firstCategoryRow.coerceAtMost(labels.size)
            val firstWords = labels.drop(fixed).filter { prefixOf(it) == null }.map(::firstWord)
            val shared = firstWords.groupingBy { it }.eachCount().filterValues { it >= 2 }.keys
            fun blockOf(row: Int): Block {
                if (row < fixed) return Block.Top
                val label = labels[row]
                prefixOf(label)?.let { return Block.Named(it) }
                val word = firstWord(label)
                return if (word in shared) Block.Named(word) else Block.Other
            }
            val order = LinkedHashMap<Block, Pair<Int, Int>>() // block -> (first row, count)
            val rowBlock = labels.indices.map { row ->
                val b = blockOf(row)
                val seen = order[b]
                order[b] = if (seen == null) row to 1 else seen.first to seen.second + 1
                b
            }
            // Alphabetical (Kevin, 2026-10-14), with the fixed rows first and Other last.
            val groups = order.map { (b, v) -> JumpGroup(b, v.second, v.first) }.sortedWith(
                compareBy<JumpGroup>({ when (it.block) { Block.Top -> 0; is Block.Named -> 1; Block.Other -> 2 } })
                    .thenBy { (it.block as? Block.Named)?.name?.uppercase().orEmpty() },
            )
            val index = groups.withIndex().associate { it.value.block to it.index }
            return CategoryJumps(groups, IntArray(labels.size) { index.getValue(rowBlock[it]) })
        }

        private fun firstWord(label: String): String = label.trim().substringBefore(' ')
    }
}
