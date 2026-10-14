// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import tv.own.owntv.core.settings.RemoteShortcutAction

/**
 * Remote button shortcuts in a live-TV-only app. Core still knows actions that open Home, Movies,
 * Series, Downloads and the TV Guide; KevBox Live has none of those screens. The picker stops
 * offering them, and a button already bound to one opens Live TV instead. Stored bindings keep
 * their old action name, so nothing on disk is rewritten.
 */
object LiveShortcuts {
    /** OPEN_HOME, OPEN_MOVIES, OPEN_SERIES, OPEN_DOWNLOADS, OPEN_GUIDE. */
    val REMOVED: Set<RemoteShortcutAction> = setOf(
        RemoteShortcutAction.OPEN_HOME,
        RemoteShortcutAction.OPEN_MOVIES,
        RemoteShortcutAction.OPEN_SERIES,
        RemoteShortcutAction.OPEN_DOWNLOADS,
        RemoteShortcutAction.OPEN_GUIDE,
    )

    /** The actions the picker offers: every entry but REMOVED, in Core's order. */
    fun offered(all: List<RemoteShortcutAction>): List<RemoteShortcutAction> = all.filterNot { it in REMOVED }

    /** What a press runs: OPEN_LIVE_TV for a REMOVED action, else the action itself. */
    fun resolve(action: RemoteShortcutAction): RemoteShortcutAction =
        if (action in REMOVED) RemoteShortcutAction.OPEN_LIVE_TV else action
}
