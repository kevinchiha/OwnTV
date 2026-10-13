// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.playlist

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ProfileSourceCrossRef
import tv.own.owntv.core.launcher.LauncherIntegrationRepository
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.sync.SyncContentTypes
import tv.own.owntv.core.sync.work.CatalogSyncScheduler
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
 */
class CoreLocalSources(
    private val sourceRepository: SourceRepository,
    private val sourceDao: SourceDao,
    private val profileDao: ProfileDao,
    private val catalogSyncScheduler: CatalogSyncScheduler,
    private val launcherIntegrationRepository: LauncherIntegrationRepository,
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
            it.type == SourceType.XTREAM && it.url == serverUrl && it.username == username
        }?.id

    override suspend fun profileIds(): List<Long> = profileDao.getAllOnce().map { it.id }

    override suspend fun create(row: PlaylistRow, profileIds: List<Long>): Long {
        val first = profileIds.first()
        val source = sourceRepository.addXtreamSource(
            profileId = first,
            name = row.name,
            serverUrl = row.serverUrl,
            username = row.username,
            password = row.password,
        )
        profileIds.drop(1).forEach { sourceDao.link(ProfileSourceCrossRef(profileId = it, sourceId = source.id)) }
        refreshLauncher(profileIds)
        return source.id
    }

    override suspend fun update(sourceId: Long, row: PlaylistRow) {
        val existing = sourceDao.getById(sourceId) ?: return
        sourceRepository.updateSource(
            existing.copy(name = row.name, url = row.serverUrl, username = row.username, password = row.password),
        )
    }

    override suspend fun link(sourceId: Long, profileIds: List<Long>) {
        profileIds.forEach { sourceDao.link(ProfileSourceCrossRef(profileId = it, sourceId = sourceId)) }
        refreshLauncher(profileIds)
    }

    override suspend fun unlinkEverywhere(sourceId: Long) {
        val linked = sourceDao.profileIdsForSource(sourceId)
        linked.forEach { sourceDao.unlink(profileId = it, sourceId = sourceId) }
        refreshLauncher(linked)
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
