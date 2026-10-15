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

/**
 * True when a Back handler on Search or Live TV must ignore this Back: it is the release of a held Back
 * that already opened full screen ([LiveVideoStage.heldBackActed]).
 *
 * Why the handlers need this: on Android 13+ with predictive back (always on for targetSdk 36 on Android
 * 16) the system starts a Back on the handler on top when the key goes DOWN and runs that same handler on
 * release, before any key handler sees the release and even if the handler was switched off in between.
 * The handler on top at the press belongs to the screen now behind full screen (Search's clear, the
 * guide's leave-row), so it would act on the hidden screen and stop the stream just opened. Found on the
 * Android 16 emulator, 2026-10-15; Android 12 (Kevin's TCL) has no predictive back.
 * `OnBackPressedDispatcher.dispatchOnBackCancelled()` cannot stop it: it cancels only the dispatcher's own
 * input, not the system's.
 */
fun LiveVideoStage?.swallowsBack(): Boolean = this?.heldBackActed == true
