// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.epg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.repository.EpgRepository
import tv.own.owntv.core.settings.EpgAutoRefresh
import tv.own.owntv.core.settings.EpgRefresh
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sync.work.EpgSyncScheduler

/**
 * Fills the TV guide from the playlist itself. Upstream asks for this in onboarding ("Sync the guide?"
 * after the first import); KevBox Live has no onboarding and adds the playlist on its own, so this
 * does the same job without asking: once a playlist's channels have synced, its own guide feed
 * (Xtream's xmltv.php) goes into Core's EPG list and downloads, set to refresh every 24 hours.
 *
 * Only a source linked to a profile counts, so a hidden line's guide is never added. The download
 * waits for the channel sync because Core keeps only the programmes of channels it already has.
 */
class GuideFeeds(
    private val sourceDao: SourceDao,
    private val epgRepository: EpgRepository,
    private val store: EpgSourceStore,
    private val scheduler: EpgSyncScheduler,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    @Volatile private var started = false

    /** Once, from OwnTVApp. Checks every time a playlist finishes a sync. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            sourceDao.observeAll()
                .map { all -> all.map { it.id to it.lastSyncAt } }
                .distinctUntilChanged()
                .collect { quietly { ensureAll() } }
        }
    }

    private suspend fun ensureAll() = mutex.withLock {
        sourceDao.getAllOnce()
            .filter { it.lastSyncAt != null && sourceDao.profileIdsForSource(it.id).isNotEmpty() }
            .forEach { ensure(it) }
    }

    private suspend fun ensure(source: SourceEntity) {
        val feeds = store.getAll().map { GuideFeed(it.id, it.url, it.lastSyncAt) }
        planGuideFeeds(epgRepository.guideUrls(source), feeds).forEach { step ->
            when (step) {
                is GuideFeedStep.Add -> {
                    val feed = store.add(source.name, step.url, source.userAgent)
                    settings.setEpgAutoRefresh(feed.id, EpgRefresh(EpgAutoRefresh.HOURS_24, 0))
                    scheduler.enqueueSync(feed.id, SYNC_REASON)
                }
                is GuideFeedStep.Download -> scheduler.enqueueSync(step.feedId, SYNC_REASON)
            }
        }
    }

    /**
     * Takes [source]'s guide feeds out of the EPG list with their programmes, as Settings › EPG
     * sources › Delete does. For a line that is deleted or hidden: its feed URL carries that line's
     * login, and nothing on this TV plays its channels any more.
     */
    suspend fun removeFor(source: SourceEntity) {
        val urls = epgRepository.guideUrls(source).toSet()
        if (urls.isEmpty()) return
        mutex.withLock {
            store.getAll().filter { it.url in urls }.forEach { remove(it.id) }
        }
    }

    /**
     * Takes out the guide feeds [old] used and [new] does not, when the playlist sync edits a source
     * in place. Its server, username or password changed, so the old feed URL no longer works and
     * would keep failing to download. The new feed is added by [ensure] once the resync finishes.
     */
    suspend fun replaceFor(old: SourceEntity, new: SourceEntity) {
        val oldUrls = epgRepository.guideUrls(old)
        if (oldUrls.isEmpty()) return
        val newUrls = epgRepository.guideUrls(new)
        mutex.withLock {
            val feeds = store.getAll().map { GuideFeed(it.id, it.url, it.lastSyncAt) }
            planGuideFeedSwap(oldUrls, newUrls, feeds).forEach { remove(it) }
        }
    }

    /** Call with [mutex] held. */
    private suspend fun remove(feedId: Long) {
        scheduler.cancelSync(feedId)
        // Programmes first, then the entry: a cancelled caller must not leave programmes with no feed.
        withContext(NonCancellable) {
            epgRepository.clear(feedId)
            store.remove(feedId)
        }
    }

    /** A failed Core call must not end the watch: the next sync tries again. */
    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    private companion object {
        const val SYNC_REASON = "kevbox"
    }
}
