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
     * The browse sections the left menu shows, in place of Core's NavVisibility: Live TV only. Search
     * and More (which holds Settings) are pinned rail items outside this set. Live TV shows even
     * before the playlist has synced, so the menu never falls back to Settings alone.
     */
    val browseSections: Set<MainSection> = setOf(MainSection.LIVE_TV)

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

    /**
     * Whether upstream's Sports Hub runs at all (upstream 5.2.0). KevBox Live has no Sports section and
     * hides Settings › Sports, so the board rebuild after launch and the goal watch while a channel
     * plays stay off: they would only fetch scores nobody sees.
     */
    const val SPORTS = false
}
