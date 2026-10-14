// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.errors

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.epg.EpgSource
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.sync.SyncResult
import tv.own.owntv.core.sync.SyncRunStats
import tv.own.owntv.core.sync.work.CatalogSyncScheduler
import tv.own.owntv.kevbox.playlist.PlaylistReconciler
import tv.own.owntv.player.ErrorInfo
import tv.own.owntv.player.LivePreviewEngine
import tv.own.owntv.player.OwnTVPlayer

/** Core sets `errorInfo` only once its own retries give up, so only the step from none to one counts. */
fun isNewFailure(previous: ErrorInfo?, next: ErrorInfo?): Boolean = previous == null && next != null

/**
 * The feeds whose `lastError` is non-empty and differs from [previous] (feed id to its last error).
 * With an empty [previous], as on the first emission of a process, every failing feed is new: Core
 * writes the same text again on each failure, so a standing error would otherwise never show.
 */
fun newGuideErrors(previous: Map<Long, String?>, feeds: List<EpgSource>): List<EpgSource> =
    feeds.filter { feed ->
        val error = feed.lastError
        !error.isNullOrEmpty() && error != previous[feed.id]
    }

/**
 * The playlist failure a finished channel-list sync should report, or null. Only a failed run that
 * ended after the last one reported for this source counts, and only for the source the TV is on
 * ([currentSourceId]); a hidden or stale source failing says nothing about what the member sees.
 */
fun playlistFailureOf(sourceId: Long, stats: SyncRunStats?, lastReportedAt: Long?, currentSourceId: Long?): Failure.Playlist? {
    val failed = stats?.result as? SyncResult.Failed ?: return null
    if (lastReportedAt != null && stats.finishedAt <= lastReportedAt) return null
    if (sourceId != currentSourceId) return null
    return Failure.Playlist(sourceId, failed.message)
}

/**
 * Hooks [reporter] to Core's failure feeds: both players' `errorInfo`, the end of each channel-list
 * sync and the guide feeds' last error. It only reads Core; nothing is subclassed or patched, and
 * Core's own on-screen messages stay as they are. Multiview tiles and the Home hero are not watched.
 *
 * The engines come in lazily: the playback watchers resolve them only once [scene] has heard from
 * the shell, so a process that never shows a screen (a sync worker waking the app) builds no player.
 */
class ErrorWatchers(
    private val reporter: ErrorReporter,
    private val livePreview: Lazy<LivePreviewEngine>,
    private val player: Lazy<OwnTVPlayer>,
    private val scene: PlaybackScene,
    private val catalogSync: CatalogSyncScheduler,
    private val sourceRepository: SourceRepository,
    private val sourceDao: SourceDao,
    private val epgStore: EpgSourceStore,
    private val reconciler: PlaylistReconciler,
    private val scope: CoroutineScope,
) {
    private val lastSyncReported = ConcurrentHashMap<Long, Long>()
    private val started = AtomicBoolean(false)

    /** Starts the reporter and every watcher. A second call does nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        reporter.start()
        scope.launch(Dispatchers.Default) { guarded { watchPlayback() } }
        scope.launch(Dispatchers.IO) { guarded { watchSyncs() } }
        scope.launch(Dispatchers.IO) { guarded { watchGuides() } }
    }

    private suspend fun watchPlayback() {
        scene.shown.first { it }
        val preview = livePreview.value
        val full = player.value
        coroutineScope {
            launch { guarded { watchEngine(preview.errorInfo) { Engine.EXO } } }
            launch { guarded { watchEngine(full.errorInfo) { if (full.isExoActive) Engine.EXO else Engine.MPV } } }
        }
    }

    private suspend fun watchEngine(errors: StateFlow<ErrorInfo?>, engine: () -> Engine) {
        var previous: ErrorInfo? = null
        errors.collect { next ->
            if (next != null && isNewFailure(previous, next)) {
                reporter.record(Failure.Playback(engine(), next.raw, next.reason, scene.snapshot()))
            }
            previous = next
        }
    }

    private suspend fun watchSyncs() {
        sourceDao.observeAll()
            .map { sources -> sources.map { it.id }.toSet() }
            .distinctUntilChanged()
            .collectLatest { ids ->
                coroutineScope { ids.forEach { id -> launch { guarded { watchSync(id) } } } }
            }
    }

    private suspend fun watchSync(sourceId: Long) {
        var wasActive = false
        catalogSync.observeSync(sourceId).collect { state ->
            val active = state.isActive
            if (wasActive && !active) reportSyncEnd(sourceId)
            wasActive = active
        }
    }

    private suspend fun reportSyncEnd(sourceId: Long) {
        val stats = sourceRepository.getLastSyncStats(sourceId) ?: return
        val failure = playlistFailureOf(sourceId, stats, lastSyncReported[sourceId], reconciler.currentSourceId()) ?: return
        lastSyncReported[sourceId] = stats.finishedAt
        reporter.record(failure)
    }

    private suspend fun watchGuides() {
        // Empty at the start of each process, so feeds already failing are reported once.
        var previous: Map<Long, String?> = emptyMap()
        epgStore.sources.collect { feeds ->
            newGuideErrors(previous, feeds).forEach { feed -> reporter.record(Failure.Guide(feed.url, feed.lastError)) }
            previous = feeds.associate { it.id to it.lastError }
        }
    }

    /** A watcher that throws stops on its own; the app and the other watchers carry on. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Nothing is logged: the message could hold a stream address.
        }
    }
}
