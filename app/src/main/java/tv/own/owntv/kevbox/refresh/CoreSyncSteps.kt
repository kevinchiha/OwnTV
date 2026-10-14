// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.refresh

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.repository.EpgRepository
import tv.own.owntv.core.sync.ImportFinalizer
import tv.own.owntv.core.sync.SyncContentTypes
import tv.own.owntv.core.sync.work.CatalogSyncScheduler
import tv.own.owntv.core.sync.work.EpgSyncScheduler

/**
 * [PlaylistUpdater]'s two download steps through Core's own schedulers, queued the way upstream's
 * Re-sync buttons queue them. Neither carries Core's automatic reason, so neither waits for playback
 * to stop: the member asked for it.
 */
class CoreSyncSteps(
    private val sourceDao: SourceDao,
    private val importFinalizer: ImportFinalizer,
    private val catalogSyncScheduler: CatalogSyncScheduler,
    private val epgRepository: EpgRepository,
    private val epgSourceStore: EpgSourceStore,
    private val epgDao: EpgDao,
    private val epgSyncScheduler: EpgSyncScheduler,
) {
    /** Queues the channel download and returns once it has ended, however it ended. */
    suspend fun syncChannels(sourceId: Long) {
        val source = sourceDao.getById(sourceId) ?: return
        val counts = importFinalizer.contentCounts(sourceId)
        catalogSyncScheduler.enqueueSync(
            sourceId,
            reason = REASON,
            contentTypes = SyncContentTypes.enabledOf(source),
            baseItemCount = counts.channels + counts.movies + counts.series,
        )
        awaitSyncEnd(catalogSyncScheduler.observeSync(sourceId).map { it.isActive })
    }

    /** Queues a download of every guide feed that is the playlist's own (the ones GuideFeeds adds). */
    suspend fun syncGuides(sourceId: Long) {
        val source = sourceDao.getById(sourceId) ?: return
        val urls = epgRepository.guideUrls(source).toSet()
        epgSourceStore.getAll().filter { it.url in urls }.forEach { feed ->
            epgSyncScheduler.enqueueSync(feed.id, REASON, epgDao.countForSources(listOf(feed.id)))
        }
    }

    private companion object {
        const val REASON = "kevbox_update_now"
    }
}

/**
 * Returns once a sync that [active] reports has started and then stopped. WorkManager may first
 * report the previous, finished run, so the wait gives the new one [startWaitMs] to show up and
 * returns if it never does. [maxWaitMs] caps the whole wait: with no network the job sits queued
 * indefinitely, and the guide step should not wait on it.
 */
internal suspend fun awaitSyncEnd(
    active: Flow<Boolean>,
    startWaitMs: Long = START_WAIT_MS,
    maxWaitMs: Long = MAX_WAIT_MS,
) {
    withTimeoutOrNull(maxWaitMs) {
        withTimeoutOrNull(startWaitMs) { active.first { it } } ?: return@withTimeoutOrNull
        active.first { !it }
    }
}

private const val START_WAIT_MS = 10_000L
private const val MAX_WAIT_MS = 15 * 60_000L
