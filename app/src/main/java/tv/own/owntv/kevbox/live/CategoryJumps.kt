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
 * three on Kevin's playlist) has a line per stretch, largest first: the counts tell them apart, and a
 * jump lands on that stretch rather than on a stray category above the main block. The fixed rows above
 * the categories (Favorites, All channels…) are [Block.Top].
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
            // One line per stretch of a named block (UK| apart from its main run is a line of its own), so
            // a jump lands where that stretch starts; the fixed rows and Other are one line each. Key: the
            // block and, for a named one, the row its stretch starts on.
            val lines = LinkedHashMap<Pair<Block, Int>, Pair<Int, Int>>() // key -> (first row, count)
            var prev: Block? = null
            var stretchStart = 0
            val rowKey = labels.indices.map { row ->
                val b = blockOf(row)
                if (b != prev) stretchStart = row
                prev = b
                val key = b to if (b is Block.Named) stretchStart else -1
                val seen = lines[key]
                lines[key] = if (seen == null) row to 1 else seen.first to seen.second + 1
                key
            }
            // Alphabetical (Kevin, 2026-10-14), with the fixed rows first and Other last; a name's stretches
            // largest first, the same size in list order.
            val keys = lines.keys.sortedWith(
                compareBy<Pair<Block, Int>>({ when (it.first) { Block.Top -> 0; is Block.Named -> 1; Block.Other -> 2 } })
                    .thenBy { (it.first as? Block.Named)?.name?.uppercase().orEmpty() }
                    .thenByDescending { lines.getValue(it).second }
                    .thenBy { lines.getValue(it).first },
            )
            val groups = keys.map { k -> lines.getValue(k).let { (first, count) -> JumpGroup(k.first, count, first) } }
            val index = keys.withIndex().associate { it.value to it.index }
            return CategoryJumps(groups, IntArray(labels.size) { index.getValue(rowKey[it]) })
        }

        private fun firstWord(label: String): String = label.trim().substringBefore(' ')
    }
}
