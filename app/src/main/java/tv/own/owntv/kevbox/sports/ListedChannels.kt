// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.sports

import android.util.Log
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sports.WatchOption
import tv.own.owntv.core.sports.WatchTier
import tv.own.owntv.core.sports.model.Match

/** The channel reads [ListedChannels] needs, behind an interface so it is unit-tested on a fake. */
interface ListedChannelReads {
    /**
     * The active profile's live channels whose provider id or exact name is one of [keys], in
     * playlist order. Channels the profile hid, or that sit in a hidden category, are left out.
     */
    suspend fun visibleByRemoteIdsOrNames(keys: List<String>): List<ChannelEntity>
}

/**
 * The channels LiveOnSat lists for a match, as the member's own channels: each one a GUIDE option
 * whose guide title is [MARK], so the match page can label it and put it first.
 */
class ListedChannels(
    private val listings: FootballListings,
    private val channels: ListedChannelReads,
) {
    /**
     * [match]'s listed channels in the listing's order, one option per channel. A channel is found
     * by its provider stream id first, but only when its name is the listing's name too (ignoring
     * case and runs of spaces: another playlist can reuse the id for some other channel), and by its
     * exact name second. A listing with no name has nothing to agree with, so its stream id counts
     * on its own. Empty when nothing links, and on any failure, so Find channels never breaks on it.
     */
    suspend fun options(match: Match): List<WatchOption> = try {
        val listed = listings.listingFor(match)?.channels.orEmpty()
        if (listed.isEmpty()) {
            emptyList()
        } else {
            val keys = listed.flatMap { listOf(it.streamId, it.name) }.filter { it.isNotEmpty() }.distinct()
            val found = channels.visibleByRemoteIdsOrNames(keys)
            val byRemoteId = found.filter { it.remoteId != null }.groupBy { it.remoteId }
            val byName = found.groupBy { it.name }
            listed.mapNotNull { c ->
                val name = looseName(c.name)
                c.streamId.takeIf { it.isNotEmpty() }
                    ?.let { id -> byRemoteId[id]?.firstOrNull { name.isEmpty() || looseName(it.name) == name } }
                    ?: c.name.takeIf { it.isNotEmpty() }?.let { byName[it]?.first() }
            }
                .distinctBy { it.id }
                .map { WatchOption(it, WatchTier.GUIDE, guideTitle = MARK) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(LOG_TAG, "listed channels failed: ${e.javaClass.simpleName}")
        emptyList()
    }

    companion object {
        /** The guide title of a listed option. It starts with a control character, so no guide title equals it. */
        const val MARK: String = "\u0001kevbox-liveonsat"

        private const val LOG_TAG = "KevBoxListed"

        /** [listed] first, then [core] without the channels [listed] already has. [core] itself when [listed] is empty. */
        fun merge(listed: List<WatchOption>, core: List<WatchOption>): List<WatchOption> {
            if (listed.isEmpty()) return core
            val ids = listed.mapTo(HashSet()) { it.channel.id }
            return listed + core.filter { it.channel.id !in ids }
        }

        /** True for an option [options] made. */
        fun isListed(o: WatchOption): Boolean = o.guideTitle == MARK

        /**
         * [shown] (a `WatchList.shown` built with the filter [accepts] comes from) with its listed
         * options moved up. `WatchList.of` puts every option the filter passes ahead of the ones it
         * rejects (those are only shown with strict filtering off), then sorts each group, and that
         * sort can lift a guide hit above a listed channel. So the listed options go first within the
         * passing group and within the rejected group, and the passing group stays ahead: a listed
         * channel the filter rejects never climbs above one it passes. Each part keeps its own order.
         * [shown] itself when none is listed.
         */
        fun listedFirst(shown: List<WatchOption>, accepts: (WatchOption) -> Boolean): List<WatchOption> {
            if (shown.none(::isListed)) return shown
            val (passing, rejected) = shown.partition(accepts)
            fun listedUp(group: List<WatchOption>) = group.partition(::isListed).let { (listed, rest) -> listed + rest }
            return listedUp(passing) + listedUp(rejected)
        }

        private val SPACES = Regex("\\s+")

        /** [name] lowercased, trimmed, with each run of whitespace as one space. */
        private fun looseName(name: String): String = name.trim().replace(SPACES, " ").lowercase(Locale.ROOT)
    }
}

/**
 * [ListedChannelReads] on the database, for the active profile: its live playlists, with the hidden
 * channels and hidden categories removed as Core's `RoomWatchData` removes them (a kids profile also
 * hides adult categories).
 */
class RoomListedChannelReads(
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao,
    private val sourceDao: SourceDao,
    private val profileDao: ProfileDao,
    private val customize: CustomizationStore,
    private val settings: SettingsRepository,
) : ListedChannelReads {

    override suspend fun visibleByRemoteIdsOrNames(keys: List<String>): List<ChannelEntity> = withContext(Dispatchers.IO) {
        if (keys.isEmpty()) return@withContext emptyList()
        val sources = activeProfileSources(settings, sourceDao).first()
        val ids = sources.liveSourceIds
        if (ids.isEmpty()) return@withContext emptyList()
        val cust = customize.observe(sources.profileId, MediaType.LIVE).first()
        val isKids = profileDao.getById(sources.profileId)?.isKids == true
        val hiddenCats = if (cust.hiddenCategories.isEmpty() && !isKids) {
            emptySet()
        } else {
            AdultCategoryClassifier.hiddenCategoryIds(categoryDao.observe(ids, MediaType.LIVE).first(), cust.hiddenCategories, isKids)
        }
        keys.chunked(KEY_CHUNK).flatMap { channelDao.byRemoteIdsOrNames(ids, it) }
            .distinctBy { it.id }
            .filter { ch -> CustomizeKeys.channel(ch) !in cust.hiddenItems && ch.categoryId.let { it == null || it !in hiddenCats } }
            .sortedWith(compareBy({ ids.indexOf(it.sourceId) }, { it.sortOrder }))
    }

    private companion object {
        const val KEY_CHUNK = 400
    }
}
