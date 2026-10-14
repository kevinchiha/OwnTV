// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import tv.own.owntv.core.settings.RemoteShortcutAction

class LiveShortcutsTest {

    private val removed = listOf(
        RemoteShortcutAction.OPEN_HOME,
        RemoteShortcutAction.OPEN_MOVIES,
        RemoteShortcutAction.OPEN_SERIES,
        RemoteShortcutAction.OPEN_DOWNLOADS,
        RemoteShortcutAction.OPEN_GUIDE,
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
}
