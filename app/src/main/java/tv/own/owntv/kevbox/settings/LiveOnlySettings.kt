// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.settings

import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.player.EnginePreference
import tv.own.owntv.kevbox.live.LiveOnly

/**
 * The one place KevBox Live lists the Settings rows it hides and the values it fixes instead of
 * reading them from storage (0.2 spec AC-1). Upstream screens ask [shows] before drawing a row and
 * read the fixed values here, so the upstream code stays in place and merges stay clean.
 */
object LiveOnlySettings {
    // Settings root rows KevBox hides (AC-2)
    const val LIVE_LAYOUT = "live_layout"
    const val LIVE_OPENS_IN = "live_opens_in"
    const val VOD_LAYOUT = "vod_layout"
    const val TAB_PANEL_WIDTH = "tab_PANEL_WIDTH"
    const val TAB_CONTENT_MENUS = "tab_CONTENT_MENUS"
    const val BROWSING_LISTS = "browsing_lists"
    const val TAB_HOME = "tab_HOME"
    const val TAB_METADATA = "tab_METADATA"
    const val TAB_OPEN_SUBTITLES = "tab_OPEN_SUBTITLES"
    const val ACCENT = "accent"
    const val FOCUS_HIGHLIGHT = "focus_highlight"
    // KevBox: upstream 5.2.0's Settings › App › Sports row and search entry; KevBox Live has no Sports.
    const val TAB_SPORTS = "tab_SPORTS"

    // Video player rows KevBox hides (AC-3)
    const val VP_LIVE_ENGINE = "vp_live_engine"
    const val VP_LIVE_ENGINE_SOURCES = "vp_live_engine_sources"
    const val VP_VOD_ENGINE = "vp_vod_engine"
    const val VP_VOD_ENGINE_SOURCES = "vp_vod_engine_sources"
    const val VP_RESET_PINS = "vp_reset_pins"
    const val VP_RESUME = "vp_resume"
    const val VP_AFR_PAUSE = "vp_afr_pause"
    const val VP_AFR_RESOLUTION = "vp_afr_resolution"
    const val VP_MULTIVIEW = "vp_multiview"
    const val VP_MULTIVIEW_TILES = "vp_multiview_tiles"
    const val VP_PREVIEW_AUDIO = "vp_preview_audio"

    // The Quick mute switch (AC-9, AC-13)
    const val QUICK_PREVIEW_SOUND = "quick_preview_sound"

    /** Exactly the 24 keys above. */
    val HIDDEN_KEYS: Set<String> = setOf(
        LIVE_LAYOUT, LIVE_OPENS_IN, VOD_LAYOUT, TAB_PANEL_WIDTH, TAB_CONTENT_MENUS, BROWSING_LISTS,
        TAB_HOME, TAB_METADATA, TAB_OPEN_SUBTITLES, ACCENT, FOCUS_HIGHLIGHT, TAB_SPORTS,
        VP_LIVE_ENGINE, VP_LIVE_ENGINE_SOURCES, VP_VOD_ENGINE, VP_VOD_ENGINE_SOURCES, VP_RESET_PINS,
        VP_RESUME, VP_AFR_PAUSE, VP_AFR_RESOLUTION, VP_MULTIVIEW, VP_MULTIVIEW_TILES, VP_PREVIEW_AUDIO,
        QUICK_PREVIEW_SOUND,
    )

    /** Whether KevBox draws the row with this key. */
    fun shows(key: String): Boolean = key !in HIDDEN_KEYS

    private const val QUICK_LIVE_PREVIEW = "quick_live_preview"
    private const val QUICK_CHANNEL_NUMBERS = "quick_channel_numbers"
    private const val QUICK_HDR = "quick_hdr"
    private const val QUICK_AUTOPLAY = "quick_autoplay"
    private const val QUICK_CHECK_UPDATE = "quick_check_update"

    /** Core's out-of-the-box Quick pins; Core's own list (SettingsRepository.DEFAULT_QUICK_PINNED) is private. */
    val CORE_DEFAULT_QUICK_PINS: List<String> = listOf(
        QUICK_LIVE_PREVIEW, QUICK_PREVIEW_SOUND, QUICK_CHANNEL_NUMBERS,
        QUICK_HDR, QUICK_AUTOPLAY, QUICK_CHECK_UPDATE,
    )

    /** What Quick shows on an install whose stored pins equal [CORE_DEFAULT_QUICK_PINS] (AC-9). */
    val DEFAULT_QUICK_PINS: List<String> = listOf(QUICK_LIVE_PREVIEW, QUICK_CHANNEL_NUMBERS, QUICK_CHECK_UPDATE)

    /**
     * The Quick pins to draw. Core's untouched defaults become [DEFAULT_QUICK_PINS]; a list the member
     * edited keeps its order. Hidden keys never survive, and an empty list stays empty.
     */
    fun quickPins(stored: List<String>): List<String> {
        val pins = if (stored == CORE_DEFAULT_QUICK_PINS) DEFAULT_QUICK_PINS else stored
        return pins.filter(::shows)
    }

    // Rows KevBox moves or adds (AC-7, AC-8); not hidden keys
    const val TAB_CUSTOMIZE = "tab_CUSTOMIZE"
    const val GROUP_LIVE = "group_live"

    /** Row key to the root group key it is drawn in instead of its own. */
    val MOVED_ROWS: Map<String, String> = mapOf(TAB_CUSTOMIZE to GROUP_LIVE)
    const val ANDROID_TV_HOME = "android_tv_home"
    const val ANDROID_TV_HOME_REFRESH = "android_tv_home_refresh"

    // Fixed values, read instead of storage (AC-13, AC-14, AC-15, AC-38)
    const val ACCENT_HEX: String = LiveOnly.ACCENT_HEX
    const val FOCUS_HIGHLIGHT_HEX: String = LiveOnly.ACCENT_HEX
    const val PREVIEW_SOUND: Boolean = true
    const val REMEMBER_CATEGORY_LIVE: Boolean = true
    const val REMEMBER_CHANNEL_LIVE: Boolean = false
    val LIVE_ENGINE: EnginePreference = EnginePreference.EXO_FIRST
    val CATCHUP_ENGINE: EnginePreference = EnginePreference.MPV_FIRST

    /** The source with its per-playlist engine choices cleared, so Core falls back to [LIVE_ENGINE]. */
    fun withoutEngineChoice(source: SourceEntity?): SourceEntity? =
        source?.copy(liveEnginePreference = null, vodEnginePreference = null)
}
