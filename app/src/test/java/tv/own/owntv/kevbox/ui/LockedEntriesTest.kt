package tv.own.owntv.kevbox.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockedEntriesTest {
    @Test
    fun `choose playlist shows only with two or more rows`() {
        assertFalse(LockedEntries.showChoosePlaylist(0))
        assertFalse(LockedEntries.showChoosePlaylist(1))
        assertTrue(LockedEntries.showChoosePlaylist(2))
        assertTrue(LockedEntries.showChoosePlaylist(3))
    }
}
