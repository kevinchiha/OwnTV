// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.player.EnginePreference

class LiveOnlySettingsTest {

    @Test
    fun hiddenKeysAreExactlyTheAc2AndAc3KeysPlusTheQuickMute() {
        val expected = setOf(
            "live_layout", "live_opens_in", "vod_layout", "tab_PANEL_WIDTH", "tab_CONTENT_MENUS",
            "browsing_lists", "tab_HOME", "tab_METADATA", "tab_OPEN_SUBTITLES", "accent", "focus_highlight",
            "vp_live_engine", "vp_live_engine_sources", "vp_vod_engine", "vp_vod_engine_sources",
            "vp_reset_pins", "vp_resume", "vp_afr_pause", "vp_afr_resolution", "vp_multiview",
            "vp_multiview_tiles", "vp_preview_audio",
            "quick_preview_sound",
        )
        assertEquals(23, expected.size)
        assertEquals(expected, LiveOnlySettings.HIDDEN_KEYS)
        expected.forEach { assertFalse(it, LiveOnlySettings.shows(it)) }
    }

    @Test
    fun keysOutsideTheListStillShow() {
        listOf("vp_afr", "vp_reset_live_pins", "vp_forget_fixes", "quick_live_preview", "tab_CUSTOMIZE", "tab_SPORTS")
            .forEach { assertTrue(it, LiveOnlySettings.shows(it)) }
    }

    @Test
    fun fixedValues() {
        assertEquals("#6FB0FF", LiveOnlySettings.ACCENT_HEX)
        assertEquals("#6FB0FF", LiveOnlySettings.FOCUS_HIGHLIGHT_HEX)
        assertTrue(LiveOnlySettings.PREVIEW_SOUND)
        assertTrue(LiveOnlySettings.REMEMBER_CATEGORY_LIVE)
        assertFalse(LiveOnlySettings.REMEMBER_CHANNEL_LIVE)
        assertEquals(EnginePreference.EXO_FIRST, LiveOnlySettings.LIVE_ENGINE)
        assertEquals(EnginePreference.MPV_FIRST, LiveOnlySettings.CATCHUP_ENGINE)
    }

    @Test
    fun coresDefaultPinsBecomeTheThreeKevBoxPins() {
        val core = listOf(
            "quick_live_preview", "quick_preview_sound", "quick_channel_numbers",
            "quick_hdr", "quick_autoplay", "quick_check_update",
        )
        assertEquals(
            listOf("quick_live_preview", "quick_channel_numbers", "quick_check_update"),
            LiveOnlySettings.quickPins(core),
        )
    }

    @Test
    fun aHiddenPinNeverSurvives() {
        val edited = listOf("quick_hdr", "quick_preview_sound", "quick_live_preview", "vp_resume", "quick_autoplay")
        assertEquals(
            listOf("quick_hdr", "quick_live_preview", "quick_autoplay"),
            LiveOnlySettings.quickPins(edited),
        )
    }

    @Test
    fun anEditedListKeepsItsOrderAndAnEmptyListStaysEmpty() {
        val reordered = listOf("quick_check_update", "quick_autoplay", "quick_live_preview")
        assertEquals(reordered, LiveOnlySettings.quickPins(reordered))
        assertEquals(emptyList<String>(), LiveOnlySettings.quickPins(emptyList()))
    }

    @Test
    fun withoutEngineChoiceClearsBothEngineColumnsAndNothingElse() {
        val source = SourceEntity(
            id = 7,
            name = "KevBox",
            type = SourceType.XTREAM,
            url = "http://example.test",
            username = "member",
            epgUrl = "http://example.test/epg.xml",
            liveEnginePreference = EnginePreference.MPV_FIRST.name,
            vodEnginePreference = EnginePreference.EXO_ONLY.name,
        )
        val cleared = LiveOnlySettings.withoutEngineChoice(source)
        assertEquals(source.copy(liveEnginePreference = null, vodEnginePreference = null), cleared)
        assertNull(cleared?.liveEnginePreference)
        assertNull(cleared?.vodEnginePreference)
        assertNull(LiveOnlySettings.withoutEngineChoice(null))
    }
}
