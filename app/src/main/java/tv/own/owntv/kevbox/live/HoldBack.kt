// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import tv.own.owntv.core.nav.MainSection

/**
 * Holding Back for 600 ms opens the box's channel full screen, the way OK on that channel does (Kevin,
 * 2026-10-14). The shell's hold detector (OwnTVShell, backHoldJob) arms on this. It holds only with
 * the player idle ([playerIdle]), a channel in the box ([hasBoxChannel]), no shell dialog up
 * ([dialogOpen]), and [section], the screen on show, being Live TV or Search and the one that filled
 * the box ([boxSection]). Where focus sits on that screen does not matter.
 */
fun holdBackOpensBox(
    playerIdle: Boolean,
    section: MainSection,
    boxSection: MainSection?,
    hasBoxChannel: Boolean,
    dialogOpen: Boolean,
): Boolean = playerIdle && hasBoxChannel && !dialogOpen && section == boxSection &&
    (section == MainSection.LIVE_TV || section == MainSection.SEARCH)
