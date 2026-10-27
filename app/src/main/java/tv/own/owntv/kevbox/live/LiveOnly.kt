// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import tv.own.owntv.core.nav.MainSection
import tv.own.owntv.core.live.ProviderName

/**
 * KevBox Live is a live-TV-only app (Kevin, 2026-10-14). The rules that turn upstream's
 * all-in-one player into that live here, so each upstream file it touches gains one call.
 */
object LiveOnly {
    /**
     * A provider category exactly as the provider wrote it: "FR| FRANCE VIP ʳᴬᵂ" stays that, with
     * no country prefix cut off and no RAW/HD/4K badges split out. Members match these names
     * against the provider's own lists, so they must read the same. Channel names still go through
     * upstream's [tv.own.owntv.core.live.ProviderTags].
     */
    fun categoryName(raw: String): ProviderName = ProviderName(raw.trim(), emptyList(), null)

    /**
     * The browse sections the left menu always shows: Live TV only. Search and More (which holds
     * Settings) are pinned rail items outside this set. Live TV shows even before the playlist has
     * synced, so the menu never falls back to Settings alone. Screens that ask "is Live TV the only
     * section?" (Customize, Navigation's Menu items) read this set, so Sports stays out of it.
     */
    val browseSections: Set<MainSection> = setOf(MainSection.LIVE_TV)

    /**
     * The browse sections the menu shows, in place of Core's NavVisibility: [browseSections] plus
     * Sports, unless the member turned off Settings › Sports › Show in menu (Kevin, 2026-10-28: Sports
     * shows for everyone by default). [hidden] is Core's navMenuHidden, the set that switch writes.
     * Unlike upstream, the switch counts in both menu modes: KevBox Live has no Menu items picker.
     */
    fun menuSections(hidden: Set<String>): Set<MainSection> =
        if (MainSection.SPORTS.name in hidden) browseSections else browseSections + MainSection.SPORTS

    /**
     * Where a request to open [section] lands. Removed browse sections (Home from Search's Back, a
     * deep link to a movie) open Live TV. Sports opens even when hidden from the menu, so a goal
     * alert can still show its match page, as upstream does.
     */
    fun sectionToOpen(section: MainSection): MainSection =
        if (section in MainSection.allBrowse && section !in browseSections && section != MainSection.SPORTS) MainSection.LIVE_TV
        else section

    /**
     * The KevBox accent. AutoSetup writes it at first run; since 0.2 the accent is fixed through
     * [tv.own.owntv.kevbox.settings.LiveOnlySettings], so members can no longer change it.
     */
    const val ACCENT_HEX = "#6FB0FF"

    /**
     * Whether the top bar shows the "Last channel" pill between the mini player and the playlist
     * pill. Hidden (Kevin, 2026-10-16). The Continue remote shortcut does not depend on it.
     */
    const val SHOW_CONTINUE_PILL = false
}
