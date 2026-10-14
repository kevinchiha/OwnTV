// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.refresh

import tv.own.owntv.core.settings.PlaylistAutoRefresh
import tv.own.owntv.core.settings.PlaylistRefresh

/**
 * Settings › Playlists › Refresh playlist: how often the channel list downloads again on its own
 * (Kevin, 2026-10-14). The channel list only; the guide keeps its own 24-hour timer from [tv.own.owntv.kevbox.epg.GuideFeeds].
 * Core checks the timer when the app opens or comes back to the front, never in the background,
 * and holds an automatic sync back while something plays.
 */
enum class RefreshChoice(val playlist: PlaylistRefresh) {
    APP_START(PlaylistRefresh(PlaylistAutoRefresh.STARTUP)),
    HOURS_6(PlaylistRefresh(PlaylistAutoRefresh.HOURS_6)),
    HOURS_12(PlaylistRefresh(PlaylistAutoRefresh.HOURS_12)),
    // Core has no fixed 24-hour or 4-day entry for playlists: longer timers are a day count.
    HOURS_24(PlaylistRefresh(PlaylistAutoRefresh.MANUAL, 1)),
    DAYS_4(PlaylistRefresh(PlaylistAutoRefresh.MANUAL, 4)),
    NEVER(PlaylistRefresh.OFF),
    ;

    companion object {
        /** New installs, and TVs updating from a version without the setting. */
        val DEFAULT = DAYS_4

        fun parse(raw: String?): RefreshChoice = entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

/**
 * The playlist timers to write so every source in [sourceIds] runs on [choice]: only the ones that
 * differ from [current], Core's stored timers by source ID. Timers of sources not listed stay as they are.
 */
fun planRefreshWrites(
    choice: RefreshChoice,
    sourceIds: List<Long>,
    current: Map<Long, PlaylistRefresh>,
): Map<Long, PlaylistRefresh> =
    sourceIds.filter { current[it] != choice.playlist }.associateWith { choice.playlist }
