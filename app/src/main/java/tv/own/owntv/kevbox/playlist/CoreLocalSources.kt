// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.playlist

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ProfileSourceCrossRef
import tv.own.owntv.core.launcher.LauncherIntegrationRepository
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.sync.SyncContentTypes
import tv.own.owntv.core.sync.work.CatalogSyncScheduler
import tv.own.owntv.kevbox.epg.GuideFeeds
import tv.own.owntv.kevbox.supabase.PlaylistRow

/**
 * [LocalSources] through Core's own classes.
 *
 * Never calls `SourceDao.insert` on an existing source: it is REPLACE, which deletes the row and
 * cascades its channels and profile links. Edits go through [SourceRepository.updateSource].
 *
 * A first sync needs nothing beyond [CatalogSyncScheduler.enqueueSync]: `CatalogSyncWorker` runs
 * `ImportFinalizer.finalize`, queues the content index build for a never-synced source and refreshes
 * the launcher for every linked profile. Core's auto-refresh only looks at sources linked to the
 * active profile, so a hidden (unlinked) source gets no further sync without touching its
 * syncLive/syncMovies/syncSeries flags (AC-20).
 *
 * [create], [link] and [unlinkEverywhere] refresh the Android TV launcher rows of the profiles they
 * touch, as upstream does after adding or editing a source, so a hidden line's channels leave the
 * home screen and a new one's appear.
 *
 * [delete] and [unlinkEverywhere] also take the line's guide feed out of the EPG list ([GuideFeeds]
 * adds it back when a hidden line returns and syncs). [update] drops the feed for a guide URL the
 * edit makes stale, such as the old server's.
 */
class CoreLocalSources(
    private val sourceRepository: SourceRepository,
    private val sourceDao: SourceDao,
    private val profileDao: ProfileDao,
    private val catalogSyncScheduler: CatalogSyncScheduler,
    private val launcherIntegrationRepository: LauncherIntegrationRepository,
    private val guideFeeds: GuideFeeds,
) : LocalSources {

    override suspend fun fields(sourceId: Long): SourceFields? {
        val source = sourceDao.getById(sourceId) ?: return null
        return SourceFields(
            name = source.name,
            serverUrl = source.url,
            username = source.username.orEmpty(),
            password = source.password.orEmpty(),
        )
    }

    override suspend fun findByLogin(serverUrl: String, username: String): Long? =
        sourceDao.getAllOnce().firstOrNull {
            it.type == SourceType.XTREAM && sameServerUrl(it.url, serverUrl) && it.username == username
        }?.id

    // Room re-runs observeForProfile on every write to sources or profile_source; only a change in
    // which sources the profile has matters here.
    override fun observeLinkedSourceIds(profileId: Long): Flow<List<Long>> =
        sourceDao.observeForProfile(profileId).map { list -> list.map { it.id }.sorted() }.distinctUntilChanged()

    override suspend fun profileIds(): List<Long> = profileDao.getAllOnce().map { it.id }

    override suspend fun create(row: PlaylistRow, profileIds: List<Long>): Long {
        val first = profileIds.first()
        val source = sourceRepository.addXtreamSource(
            profileId = first,
            name = row.name,
            serverUrl = row.serverUrl,
            username = row.username,
            password = row.password,
            // Live TV only: the app has no Movies or Series pages, so their catalogues never download.
            syncMovies = false,
            syncSeries = false,
        )
        profileIds.drop(1).forEach { sourceDao.link(ProfileSourceCrossRef(profileId = it, sourceId = source.id)) }
        refreshLauncher(profileIds)
        return source.id
    }

    override suspend fun update(sourceId: Long, row: PlaylistRow) {
        val existing = sourceDao.getById(sourceId) ?: return
        // Live TV only, for a source made before the app dropped Movies and Series too.
        val updated = existing.copy(
            name = row.name, url = row.serverUrl, username = row.username, password = row.password,
            syncMovies = false, syncSeries = false,
        )
        // The save runs inside the guide lock, after the old feed is gone, so a sync finishing meanwhile
        // cannot re-add the old feed. If the removal fails or is cancelled, the save never runs, the
        // stored fields still differ from the row, and the next reconcile retries both (AC-28).
        guideFeeds.replaceFor(existing, updated) { sourceRepository.updateSource(updated) }
    }

    override suspend fun link(sourceId: Long, profileIds: List<Long>) {
        profileIds.forEach { sourceDao.link(ProfileSourceCrossRef(profileId = it, sourceId = sourceId)) }
        refreshLauncher(profileIds)
    }

    override suspend fun unlinkEverywhere(sourceId: Long) {
        val linked = sourceDao.profileIdsForSource(sourceId)
        linked.forEach { sourceDao.unlink(profileId = it, sourceId = sourceId) }
        refreshLauncher(linked)
        sourceDao.getById(sourceId)?.let { guideFeeds.removeFor(it) }
    }

    override suspend fun resync(sourceId: Long) {
        val source = sourceDao.getById(sourceId) ?: return
        catalogSyncScheduler.enqueueSync(
            sourceId,
            reason = SYNC_REASON,
            contentTypes = SyncContentTypes.enabledOf(source),
        )
    }

    override suspend fun stopSync(sourceId: Long) {
        catalogSyncScheduler.cancelSync(sourceId)
    }

    override suspend fun delete(sourceId: Long) {
        catalogSyncScheduler.cancelSync(sourceId)
        val source = sourceDao.getById(sourceId) ?: return
        val linked = sourceDao.profileIdsForSource(sourceId)
        // Same as upstream's delete: finish the cascade even if the caller is cancelled midway.
        withContext(NonCancellable) {
            guideFeeds.removeFor(source)
            sourceRepository.deleteSource(source)
            refreshLauncher(linked)
        }
    }

    private suspend fun refreshLauncher(profileIds: List<Long>) {
        profileIds.forEach { runCatching { launcherIntegrationRepository.refreshProfile(it) } }
    }

    private companion object {
        const val SYNC_REASON = "kevbox"
    }
}
