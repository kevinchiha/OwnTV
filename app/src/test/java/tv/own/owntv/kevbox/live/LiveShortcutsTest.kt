// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.settings.RemoteShortcutAction
import tv.own.owntv.features.home.ContinueAction
import tv.own.owntv.features.home.ContinueKind
import tv.own.owntv.features.home.ContinueTarget

class LiveShortcutsTest {

    private val removed = listOf(
        RemoteShortcutAction.OPEN_HOME,
        RemoteShortcutAction.OPEN_MOVIES,
        RemoteShortcutAction.OPEN_SERIES,
        RemoteShortcutAction.OPEN_DOWNLOADS,
        RemoteShortcutAction.OPEN_GUIDE,
        RemoteShortcutAction.OPEN_PROFILE_SWITCHER,
    )

    @Test
    fun thePickerOffersNoRemovedAction() {
        val offered = LiveShortcuts.offered(RemoteShortcutAction.entries)
        removed.forEach { assertFalse(it.name, it in offered) }
        assertEquals(RemoteShortcutAction.entries.filter { it !in removed }, offered)
        assertEquals(RemoteShortcutAction.OPEN_LIVE_TV, offered.first())
    }

    @Test
    fun aRemovedActionOpensLiveTv() {
        removed.forEach { assertEquals(it.name, RemoteShortcutAction.OPEN_LIVE_TV, LiveShortcuts.resolve(it)) }
        assertEquals(RemoteShortcutAction.PAGE_TOWARD_FIRST, LiveShortcuts.resolve(RemoteShortcutAction.PAGE_TOWARD_FIRST))
        assertEquals(RemoteShortcutAction.OPEN_LIVE_TV, LiveShortcuts.resolve(RemoteShortcutAction.OPEN_LIVE_TV))
    }

    @Test
    fun thePickerOffersNoProfilesAction() {
        assertFalse(RemoteShortcutAction.OPEN_PROFILE_SWITCHER in LiveShortcuts.offered(RemoteShortcutAction.entries))
        assertEquals(RemoteShortcutAction.OPEN_LIVE_TV, LiveShortcuts.resolve(RemoteShortcutAction.OPEN_PROFILE_SWITCHER))
    }

    private fun target(kind: ContinueKind) = ContinueTarget(
        kind = kind,
        name = String(),
        action = ContinueAction.RESUME,
        channelId = 42L,
        movieId = 7L,
        seriesId = 8L,
        episodeId = 9L,
    )

    @Test
    fun continueIgnoresAMovieOrEpisodeTarget() {
        assertNull(LiveShortcuts.continueChannel(target(ContinueKind.MOVIE)))
        assertNull(LiveShortcuts.continueChannel(target(ContinueKind.EPISODE)))
    }

    @Test
    fun continueOpensTheLiveChannel() = assertEquals(42L, LiveShortcuts.continueChannel(target(ContinueKind.LIVE)))

    @Test
    fun continueWithNoHistoryDoesNothing() = assertNull(LiveShortcuts.continueChannel(null))
}
