// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.errors

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
import tv.own.owntv.player.PlaybackFailure
import tv.own.owntv.player.PlayerFailureReason

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
 * The guide errors to report now. [reported] holds the error last reported per feed in this process,
 * [seen] the errors of the previous emission (null before the first).
 *
 * A process that has shown a screen ([shown]) reports every error not yet reported, so a feed already
 * failing at start shows once. A background process (a WorkManager wake) reports only errors that
 * changed while it ran: a standing error is not news there, and would come back on every wake.
 */
fun guideErrorsToReport(
    seen: Map<Long, String?>?,
    reported: Map<Long, String?>,
    feeds: List<EpgSource>,
    shown: Boolean,
): List<EpgSource> = feeds.filter { feed ->
    val error = feed.lastError
    !error.isNullOrEmpty() && error != reported[feed.id] && (shown || (seen != null && error != seen[feed.id]))
}

/**
 * Whether a sync state change ends a run worth checking: active to idle, or idle at the first
 * emission ([wasActive] null), since a run in a cold process can end before Core's first emission.
 */
fun syncEnded(wasActive: Boolean?, active: Boolean): Boolean = !active && wasActive != false

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
 *
 * A full-screen failure waits in a [FallbackHold] for Core's engine ladder ([ladderEvents]): when
 * Core hands the channel to the other engine the member saw nothing, so nothing is recorded. A
 * failure in the Live TV top box is recorded at once.
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
    private val ladderEvents: Flow<PlayerFailureReason> = LadderEvents.events,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lastSyncReported = ConcurrentHashMap<Long, Long>()
    private val started = AtomicBoolean(false)
    private val hold = FallbackHold()

    /** Sync runs that ended before this are an earlier process's to report. */
    private val createdAtMs = now()

    /** Starts the reporter and every watcher. A second call does nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        reporter.start()
        scope.launch(Dispatchers.Default) { guarded { watchLadder() } }
        scope.launch(Dispatchers.Default) { guarded { watchPlayback() } }
        scope.launch(Dispatchers.IO) { guarded { watchSyncs() } }
        scope.launch(Dispatchers.IO) { guarded { watchGuides() } }
    }

    private suspend fun watchPlayback() {
        scene.shown.first { it }
        val preview = livePreview.value
        val full = player.value
        coroutineScope {
            launch {
                guarded {
                    watchEngine(preview.errorInfo, { Engine.EXO }, { preview.error.value is PlaybackFailure.LostConnection })
                }
            }
            launch {
                guarded {
                    watchEngine(
                        full.errorInfo,
                        { if (full.isExoActive) Engine.EXO else Engine.MPV },
                        { full.error.value is PlaybackFailure.LostConnection },
                    )
                }
            }
        }
    }

    private suspend fun watchEngine(errors: StateFlow<ErrorInfo?>, engine: () -> Engine, lost: () -> Boolean) {
        var previous: ErrorInfo? = null
        errors.collect { next ->
            if (next != null && isNewFailure(previous, next)) {
                onPlaybackFailure(Failure.Playback(engine(), next.raw, next.reason, scene.snapshot(), lost()))
            }
            previous = next
        }
    }

    private fun onPlaybackFailure(failure: Failure.Playback) {
        val atOnce = hold.hold(failure, now())
        atOnce.forEach(reporter::record)
        if (atOnce.isNotEmpty()) return
        scope.launch(Dispatchers.Default) {
            delay(FallbackHold.HOLD_MS)
            hold.due(now()).forEach(reporter::record)
        }
    }

    private suspend fun watchLadder() {
        ladderEvents.collect { reason -> hold.onLadder(reason, now()).forEach(reporter::record) }
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
        var wasActive: Boolean? = null
        catalogSync.observeSync(sourceId).collect { state ->
            val active = state.isActive
            if (syncEnded(wasActive, active)) reportSyncEnd(sourceId)
            wasActive = active
        }
    }

    private suspend fun reportSyncEnd(sourceId: Long) {
        val stats = sourceRepository.getLastSyncStats(sourceId) ?: return
        val reportedAt = lastSyncReported[sourceId] ?: createdAtMs
        val failure = playlistFailureOf(sourceId, stats, reportedAt, reconciler.currentSourceId()) ?: return
        lastSyncReported[sourceId] = stats.finishedAt
        reporter.record(failure)
    }

    private suspend fun watchGuides() {
        var seen: Map<Long, String?>? = null
        val reported = HashMap<Long, String?>()
        combine(epgStore.sources, scene.shown) { feeds, shown -> feeds to shown }.collect { (feeds, shown) ->
            guideErrorsToReport(seen, reported, feeds, shown).forEach { feed ->
                reported[feed.id] = feed.lastError
                reporter.record(Failure.Guide(feed.url, feed.lastError))
            }
            // A feed that recovered may report the same text again when it next fails.
            feeds.filter { it.lastError.isNullOrEmpty() }.forEach { reported.remove(it.id) }
            seen = feeds.associate { it.id to it.lastError }
        }
    }

    /** A watcher that throws stops on its own; the app and the other watchers carry on. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The class name only: the message could hold a stream address.
            Log.w(LOG_TAG, "watcher stopped: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val LOG_TAG = "KevBoxErrors"
    }
}
