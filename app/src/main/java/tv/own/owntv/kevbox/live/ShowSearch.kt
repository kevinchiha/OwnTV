// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.live

import androidx.room.useReaderConnection
import kotlinx.coroutines.flow.first
import tv.own.owntv.core.content.ProgrammeSearchResult
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.OwnTVDatabase
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
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

/**
 * The shortest query that looks for shows. One letter matches nearly every title in the guide; it would
 * read thousands of rows to say nothing useful.
 */
const val SHOW_QUERY_MIN = 2

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
 * Each show in [keyed] paired with the guide id its channel reads. With [onePerFeed], a show carried by
 * two channels on the same guide id (an HD and an SD copy, or two playlists) is kept on the first
 * channel only, as core's search does; without it, every channel keeps its own copy.
 */
fun keepOnePerFeed(keyed: List<Pair<String, ProgrammeSearchResult>>, onePerFeed: Boolean): List<ProgrammeSearchResult> =
    (if (onePerFeed) keyed.distinctBy { (key, show) -> Triple(key, show.programme.startMs, show.programme.title) } else keyed)
        .map { it.second }

/**
 * Looks up shows by title in the stored guide and pins each to a channel the profile may see.
 *
 * The mapping copies core's SearchReader (programmes), so a show found here sits on the same channel,
 * at the same shifted time, as the guide grid draws it.
 */
/**
 * Programme titles from the stored guide holding every word of the search, in any order, without caring
 * about accents or case ([accentGlobs]).
 * The same read as core's EpgDao.searchTitles (the window, the order, the columns it fills, the
 * description left out), except that its `title LIKE` only ignores the case of A to Z.
 */
class ProgrammeTitles(private val db: OwnTVDatabase) {

    suspend fun search(query: String, fromMs: Long, toMs: Long, limit: Int): List<EpgProgrammeEntity> {
        val globs = accentGlobs(query)
        if (globs.isEmpty()) return emptyList()
        return db.useReaderConnection { connection ->
            connection.usePrepared(programmeTitlesSql(globs.size)) { st ->
                st.bindLong(1, fromMs)
                st.bindLong(2, toMs)
                st.bindLong(3, fromMs)
                globs.forEachIndexed { i, glob -> st.bindText(4 + i, glob) }
                st.bindLong(4 + globs.size, limit.toLong())
                buildList {
                    while (st.step()) {
                        add(
                            EpgProgrammeEntity(
                                id = st.getLong(0), sourceId = st.getLong(1), epgChannelId = st.getText(2),
                                startMs = st.getLong(3), stopMs = st.getLong(4), title = st.getText(5),
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The title lookup for a search of [words] words: the window (from, to, from), then one `title GLOB ?` per
 * word, all of which must match, then the limit.
 */
fun programmeTitlesSql(words: Int): String =
    "SELECT id, sourceId, epgChannelId, startMs, stopMs, title FROM epg_programmes " +
        "WHERE startMs > ? - 86400000 AND startMs < ? AND stopMs > ?" +
        " AND title GLOB ?".repeat(words) +
        " ORDER BY startMs ASC LIMIT ?"

class ShowSearch(
    private val titles: ProgrammeTitles,
    private val channelDao: ChannelDao,
    private val settings: SettingsRepository,
) {

    /**
     * Shows whose title contains [query] (any case, with or without accents), on now or starting within [SHOW_WINDOW_MS] of
     * [nowMs], on a channel from [liveSourceIds] that is neither hidden in [cust] nor in one of
     * [hiddenCategoryIds]. Channels keep their Customize names.
     *
     * [onePerFeed]: a show on a guide id two channels read is listed on the first channel only. Right
     * for Search's list of shows; the guide sets it false, so each copy of a channel gets its row.
     */
    suspend fun find(
        query: String,
        liveSourceIds: List<Long>,
        cust: SectionCustomizations,
        hiddenCategoryIds: Set<Long>,
        nowMs: Long,
        onePerFeed: Boolean = true,
    ): ShowMatches {
        val text = query.trim()
        if (text.length < SHOW_QUERY_MIN || liveSourceIds.isEmpty()) return ShowMatches.EMPTY
        // The stored times are before any guide shift, so the window is widened on both sides to keep
        // a shifted channel's rows; the exact window is applied after the shift, on the user's clock.
        val rows = titles.search(text, nowMs - SHIFT_SLACK_MS, nowMs + SHOW_WINDOW_MS + SHIFT_SLACK_MS, ROWS)
        if (rows.isEmpty()) return ShowMatches.EMPTY
        // Keyed as epgKeyOf keys a channel, trimmed and lower-cased; byEpgKeys compares LOWER(TRIM(...)) too.
        val byKey = rows.groupBy { it.epgChannelId.trim().lowercase() }
        // A channel the user matched to a guide by hand does not carry that guide's id, so it is found
        // by the tail of its Customize key (its provider id or its name) instead.
        val matchedTails = cust.epgMatches
            .filterValues { it.trim().lowercase() in byKey }
            .keys.map { CustomizeKeys.tailOf(it) }.filter { it.isNotEmpty() }.distinct()
        val candidates = byKey.keys.toList().chunked(KEY_CHUNK).flatMap { channelDao.byEpgKeys(liveSourceIds, it) } +
            matchedTails.chunked(KEY_CHUNK).flatMap { channelDao.byRemoteIdsOrNames(liveSourceIds, it) }
        val globalShift = settings.epgOffsetMinutes.first()
        val keyed = candidates
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
        return splitShows(keepOnePerFeed(keyed, onePerFeed), nowMs)
    }

    private companion object {
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
