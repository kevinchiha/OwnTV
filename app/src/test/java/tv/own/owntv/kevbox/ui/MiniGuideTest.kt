package tv.own.owntv.kevbox.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity

class MiniGuideTest {
    @Test
    fun `each stored key maps to its own query`() {
        assertEquals(MiniGuideQuery.Folder(42L), MiniGuide.query("FOLDER:42"))
        assertEquals(MiniGuideQuery.Favorites, MiniGuide.query("FAV"))
        assertEquals(MiniGuideQuery.History, MiniGuide.query("HIST"))
        assertEquals(MiniGuideQuery.Catchup, MiniGuide.query("CATCHUP"))
        assertEquals(MiniGuideQuery.Custom("custom:abc"), MiniGuide.query("CUSTOM:custom:abc"))
        assertEquals(MiniGuideQuery.All, MiniGuide.query("ALL"))
        assertEquals(MiniGuideQuery.All, MiniGuide.query(""))
        assertEquals(MiniGuideQuery.All, MiniGuide.query("junk"))
        assertEquals(MiniGuideQuery.All, MiniGuide.query("FOLDER:notanumber"))
    }

    @Test
    fun `the window starts on the half hour before now`() {
        val halfHour = 30 * 60_000L
        val onTheHalfHour = 1_760_000_000_000L - 1_760_000_000_000L % halfHour
        val now = onTheHalfHour + 17 * 60_000L + 4_321L
        val (start, end) = MiniGuide.window(now)
        assertEquals(onTheHalfHour, start)
        assertEquals(start + 210 * 60_000L, end)
        assertTrue(now in start until end)
        // Exactly on the half hour, the window starts there.
        assertEquals(onTheHalfHour, MiniGuide.window(onTheHalfHour).first)
    }

    @Test
    fun `rows keep the channel order and pair each with its own programmes`() {
        val a = channel(3, "A")
        val b = channel(1, "B")
        val c = channel(2, "C")
        val forA = listOf(programme(10, "a1"), programme(11, "a2"))
        val forC = listOf(programme(20, "c1"))
        val rows = MiniGuide.rows(listOf(a, b, c), mapOf(c.id to forC, a.id to forA)) { it.name.lowercase() }

        assertEquals(listOf(a, b, c), rows.map { it.channel })
        assertEquals(listOf("a", "b", "c"), rows.map { it.name })
        assertEquals(forA, rows[0].programmes)
        assertEquals(emptyList<EpgProgrammeEntity>(), rows[1].programmes)
        assertEquals(forC, rows[2].programmes)
    }

    private fun channel(id: Long, name: String) = ChannelEntity(id = id, sourceId = 1, name = name, streamUrl = "")

    private fun programme(id: Long, title: String) =
        EpgProgrammeEntity(id = id, sourceId = 1, epgChannelId = "x", startMs = id * 1000, stopMs = id * 1000 + 500, title = title)
}
