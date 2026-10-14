// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import tv.own.owntv.core.settings.RemoteShortcutAction
import tv.own.owntv.features.home.ContinueKind
import tv.own.owntv.features.home.ContinueTarget

/**
 * Remote button shortcuts in a live-TV-only app. Core still knows actions that open Home, Movies,
 * Series, Downloads, the TV Guide and the profile switcher; KevBox Live has none of those screens
 * and keeps one hidden profile. The picker stops offering them, and a button already bound to one
 * opens Live TV instead. Stored bindings keep their old action name, so nothing on disk is rewritten.
 */
object LiveShortcuts {
    /** OPEN_HOME, OPEN_MOVIES, OPEN_SERIES, OPEN_DOWNLOADS, OPEN_GUIDE, OPEN_PROFILE_SWITCHER. */
    val REMOVED: Set<RemoteShortcutAction> = setOf(
        RemoteShortcutAction.OPEN_HOME,
        RemoteShortcutAction.OPEN_MOVIES,
        RemoteShortcutAction.OPEN_SERIES,
        RemoteShortcutAction.OPEN_DOWNLOADS,
        RemoteShortcutAction.OPEN_GUIDE,
        RemoteShortcutAction.OPEN_PROFILE_SWITCHER,
    )

    /** The actions the picker offers: every entry but REMOVED, in Core's order. */
    fun offered(all: List<RemoteShortcutAction>): List<RemoteShortcutAction> = all.filterNot { it in REMOVED }

    /** What a press runs: OPEN_LIVE_TV for a REMOVED action, else the action itself. */
    fun resolve(action: RemoteShortcutAction): RemoteShortcutAction =
        if (action in REMOVED) RemoteShortcutAction.OPEN_LIVE_TV else action

    /**
     * The channel the Continue pill and the Continue shortcut open, or null when they do nothing.
     * Only a live channel counts: the newest history row can still be a stored movie or episode,
     * and those never show in a live-only app.
     */
    fun continueChannel(target: ContinueTarget?): Long? =
        target?.takeIf { it.kind == ContinueKind.LIVE }?.channelId
}
