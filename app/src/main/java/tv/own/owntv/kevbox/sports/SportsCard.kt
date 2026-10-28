// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.sports

import tv.own.owntv.features.shell.components.SettingsGroup
import tv.own.owntv.features.shell.components.SettingsStart

/**
 * More › Settings' Sports card. The cards open Settings at a group by its index (SettingsGroup); the
 * Sports card sits after the last group, so its index is one past them, and Settings reads that index
 * as "open the Sports page" instead of a group.
 */
object SportsCard {
    /** The Sports card's index: the one after the last Settings group. */
    val INDEX: Int get() = SettingsGroup.entries.size

    /** True when [start] came from the Sports card. */
    fun opens(start: SettingsStart?): Boolean = start?.group == INDEX

    /** [start]'s group, or null when it is the Sports card (which opens a page, not a group). */
    fun group(start: SettingsStart?): Int? = start?.group?.takeIf { it != INDEX }
}
