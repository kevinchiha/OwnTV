// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import kotlinx.coroutines.flow.first
import tv.own.owntv.core.content.ProgrammeSearchResult
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.epg.EpgDedupe
import tv.own.owntv.core.epg.EpgShift
import tv.own.owntv.core.settings.SettingsRepository

/*
 * Finding shows by title, for Search's TV guide tab and the guide's own search (Kevin, 2026-10-14).
 * Core's SearchReader already finds programmes, but only 12 hours ahead and 20 at most, and both
 * limits are private constants in the prebuilt core. This is the same lookup with our own window
 * (on now plus the next 24 hours) and no cap on what it returns, so both searches list every match.
 */

/** How far past now a show may start and still be found. */
const val SHOW_WINDOW_MS = 24 * 60 * 60_000L

/** The shows found for a query: on now, in channel order, and starting later, by start time. */
data class ShowMatches(
    val onNow: List<ProgrammeSearchResult>,
    val later: List<ProgrammeSearchResult>,
) {
    val size: Int get() = onNow.size + later.size

    /** Every channel carrying at least one of the shows. */
    val channelIds: Set<Long> get() = (onNow + later).mapTo(HashSet()) { it.channel.id }

    companion object {
        val EMPTY = ShowMatches(emptyList(), emptyList())
    }
}

/**
 * Splits [found] into what is on at [nowMs] and what starts in the next [SHOW_WINDOW_MS]. A show that
 * is over, or starts later than that, is dropped. On now keeps the order it came in (channel order);
 * later is sorted by start, so the soonest show comes first. The same show on the same channel at the
 * same time is listed once: a channel can read two guides that both carry it.
 */
fun splitShows(found: List<ProgrammeSearchResult>, nowMs: Long): ShowMatches {
    val unique = found.distinctBy { Triple(it.channel.id, it.programme.startMs, it.programme.title) }
    val onNow = unique.filter { it.programme.startMs <= nowMs && nowMs < it.programme.stopMs }
    val later = unique
        .filter { it.programme.startMs > nowMs && it.programme.startMs < nowMs + SHOW_WINDOW_MS }
        .sortedBy { it.programme.startMs }
    return ShowMatches(onNow, later)
}

/**
 * Looks up shows by title in the stored guide and pins each to a channel the profile may see.
 *
 * The mapping copies core's SearchReader (programmes), so a show found here sits on the same channel,
 * at the same shifted time, as the guide grid draws it.
 */
class ShowSearch(
    private val epgDao: EpgDao,
    private val channelDao: ChannelDao,
    private val settings: SettingsRepository,
) {

    /**
     * Shows whose title contains [query] (any case), on now or starting within [SHOW_WINDOW_MS] of
     * [nowMs], on a channel from [liveSourceIds] that is neither hidden in [cust] nor in one of
     * [hiddenCategoryIds]. Channels keep their Customize names.
     */
    suspend fun find(
        query: String,
        liveSourceIds: List<Long>,
        cust: SectionCustomizations,
        hiddenCategoryIds: Set<Long>,
        nowMs: Long,
    ): ShowMatches {
        val text = query.trim()
        // One letter matches nearly every title in the guide; it would read thousands of rows to say nothing useful.
        if (text.length < MIN_QUERY || liveSourceIds.isEmpty()) return ShowMatches.EMPTY
        // The stored times are before any guide shift, so the window is widened on both sides to keep
        // a shifted channel's rows; the exact window is applied after the shift, on the user's clock.
        val rows = epgDao.searchTitles(text, nowMs - SHIFT_SLACK_MS, nowMs + SHOW_WINDOW_MS + SHIFT_SLACK_MS, ROWS)
        if (rows.isEmpty()) return ShowMatches.EMPTY
        val byKey = rows.groupBy { it.epgChannelId }
        // A channel the user matched to a guide by hand does not carry that guide's id, so it is found
        // by the tail of its Customize key (its provider id or its name) instead.
        val matchedTails = cust.epgMatches
            .filterValues { it.trim().lowercase() in byKey }
            .keys.map { CustomizeKeys.tailOf(it) }.filter { it.isNotEmpty() }.distinct()
        val candidates = byKey.keys.toList().chunked(KEY_CHUNK).flatMap { channelDao.byEpgKeys(liveSourceIds, it) } +
            matchedTails.chunked(KEY_CHUNK).flatMap { channelDao.byRemoteIdsOrNames(liveSourceIds, it) }
        val globalShift = settings.epgOffsetMinutes.first()
        val found = candidates
            .distinctBy { it.id }
            .filter { CustomizeKeys.channel(it) !in cust.hiddenItems && (it.categoryId == null || it.categoryId !in hiddenCategoryIds) }
            .sortedWith(compareBy({ liveSourceIds.indexOf(it.sourceId) }, { it.sortOrder }))
            .flatMap { ch ->
                val key = epgKeyOf(ch, cust) ?: return@flatMap emptyList()
                val shift = EpgShift.minutesFor(cust, ch, globalShift)
                val named = cust.itemNames[CustomizeKeys.channel(ch)]?.let { ch.copy(name = it) } ?: ch
                EpgShift.apply(EpgDedupe.collapse(byKey[key].orEmpty()), shift)
                    .map { key to ProgrammeSearchResult(it, named) }
            }
            // One feed carried by two playlists is one programme, as in core: the first channel keeps it.
            .distinctBy { (key, show) -> Triple(key, show.programme.startMs, show.programme.title) }
            .map { it.second }
        return splitShows(found, nowMs)
    }

    private companion object {
        const val MIN_QUERY = 2

        /** The largest guide shift still allowed for when reading stored times (core uses the same). */
        const val SHIFT_SLACK_MS = 2 * 60 * 60_000L

        /** Guide rows read at most; one show can sit on many guide channels, so this is well above what a screen lists. */
        const val ROWS = 2000

        /** Keys per IN (...) lookup, inside SQLite's variable limit. */
        const val KEY_CHUNK = 400
    }
}

/**
 * The guide id a channel reads: its manual match first, then its own id, trimmed and lower-cased.
 * A copy of core's GuideReader helper, which is internal to the core library and so out of reach here.
 */
internal fun epgKeyOf(channel: ChannelEntity, cust: SectionCustomizations): String? =
    (cust.epgMatchResolver.epgIdFor(channel) ?: channel.epgChannelId)
        ?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
