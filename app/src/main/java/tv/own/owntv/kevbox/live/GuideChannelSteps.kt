// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

/**
 * Where CH+ / CH− land in the Live TV grid (AC-24, AC-37). The grid does not call this: it pages
 * through upstream's [tv.own.owntv.ui.components.chNavPaging], which reads the member's Remote
 * shortcuts bindings and skip counts. This object mirrors chNavPaging's step-and-clamp rule so the
 * AC-37 tests can pin it down: a page moves by the member's skip counts and a jump uses a skip of
 * the whole grid, so it lands on the first or last row. No wrap: CH+ on the first row stays put.
 */
object GuideChannelSteps {
    /** The row CH+ (towardFirst) or CH− lands on, or null when there is nowhere to go. */
    fun target(from: Int, towardFirst: Boolean, upSkip: Int, downSkip: Int, count: Int): Int? {
        if (count <= 0) return null
        val to = (if (towardFirst) from - upSkip else from + downSkip).coerceIn(0, count - 1)
        return to.takeIf { it != from }
    }
}
