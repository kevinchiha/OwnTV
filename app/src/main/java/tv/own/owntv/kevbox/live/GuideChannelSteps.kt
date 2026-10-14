// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

/**
 * CH+ / CH− in the Live TV grid (AC-24). The member's Remote shortcuts bindings pick the action, as
 * [tv.own.owntv.ui.components.chNavPaging] reads them; this is where each action lands. A page moves
 * by the member's skip counts and a jump uses a skip of the whole grid, so it lands on the first or
 * last row. No wrap: CH+ on the first row stays put.
 */
object GuideChannelSteps {
    /** The row CH+ (towardFirst) or CH− lands on, or null when there is nowhere to go. */
    fun target(from: Int, towardFirst: Boolean, upSkip: Int, downSkip: Int, count: Int): Int? {
        if (count <= 0) return null
        val to = (if (towardFirst) from - upSkip else from + downSkip).coerceIn(0, count - 1)
        return to.takeIf { it != from }
    }
}
