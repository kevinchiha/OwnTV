// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.refresh

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.network.ConnectivityObserver
import tv.own.owntv.core.repository.EpgRepository
import tv.own.owntv.core.sync.ImportFinalizer
import tv.own.owntv.core.sync.SyncContentTypes
import tv.own.owntv.core.sync.work.CatalogSyncScheduler
import tv.own.owntv.core.sync.work.CatalogSyncState
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
    private val connectivity: ConnectivityObserver,
) {
    /**
     * Downloads the channel list again and, when online, returns once that download has ended, however
     * it ended. See [channelStep] for when it waits for a download already under way instead.
     */
    suspend fun syncChannels(sourceId: Long) {
        val source = sourceDao.getById(sourceId) ?: return
        val current = catalogSyncScheduler.observeSync(sourceId).first()
        val downloading = current is CatalogSyncState.Syncing && current.totalProcessed > 0
        val step = channelStep(online = connectivity.isOnlineNow(), downloading = downloading)
        if (step != ChannelStep.WAIT) {
            val counts = importFinalizer.contentCounts(sourceId)
            catalogSyncScheduler.enqueueSync(
                sourceId,
                reason = REASON,
                contentTypes = SyncContentTypes.enabledOf(source),
                baseItemCount = counts.channels + counts.movies + counts.series,
            )
        }
        if (step != ChannelStep.START) awaitSyncEnd(catalogSyncScheduler.observeSync(sourceId).map { it.isActive })
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

/** What Update now does about the channel list. */
enum class ChannelStep {
    /** Start a download (replacing one only queued, such as an automatic refresh held back by playback) and wait for it. */
    START_AND_WAIT,

    /** A download is already making progress: wait for it instead of throwing its work away. */
    WAIT,

    /** Offline: queue the download for when the TV is back online and go on to the guide at once. */
    START,
}

/** [downloading]: a channel download is under way and has processed something. */
fun channelStep(online: Boolean, downloading: Boolean): ChannelStep = when {
    !online -> ChannelStep.START
    downloading -> ChannelStep.WAIT
    else -> ChannelStep.START_AND_WAIT
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
