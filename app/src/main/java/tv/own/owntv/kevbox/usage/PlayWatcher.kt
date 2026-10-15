// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.usage

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.own.owntv.kevbox.errors.ErrorEntry
import tv.own.owntv.kevbox.errors.ErrorReporter
import tv.own.owntv.kevbox.errors.PlaybackScene
import tv.own.owntv.kevbox.errors.SceneSnapshot
import tv.own.owntv.player.LivePreviewEngine
import tv.own.owntv.player.OwnTVPlayer

/** How long a live channel plays, with no error, stop or channel change, before it counts (AC-1). */
const val PLAY_PROOF_MS: Long = 30_000L

/** How often a live run is looked at again while it plays. */
private const val PLAY_TICK_MS: Long = 1_000L

/** One look at Live TV: which channel, whether it plays, whether it failed. */
data class PlayView(val channelKey: String?, val playing: Boolean, val failed: Boolean, val catchup: Boolean)

/** Which engine shows the live channel: the Live engine (box, or full screen on Exo) or the full-screen player (mpv). */
enum class PlaySource { LIVE_ENGINE, PLAYER }

/** What one engine reports: whether it plays and whether its last failure still stands. */
data class EngineLook(val playing: Boolean, val failed: Boolean)

/** One look at Live TV and the engine it came from (null when no engine shows a live channel). */
data class PlayLook(val source: PlaySource?, val view: PlayView)

/**
 * Pure: what Live TV shows now. The Live engine counts whenever it plays, since only Live TV puts a
 * channel on it (the box, and full screen on Exo). The full-screen player counts only while it plays
 * live content ([playerLive]): it also plays catch-up and films. Multiview tiles and the Home hero
 * have engines of their own and never come in here. The video site is not read: live on mpv has none.
 */
fun playLookOf(scene: SceneSnapshot, liveEngine: EngineLook, player: EngineLook, playerLive: Boolean): PlayLook {
    // The stream address only tells channels apart; it stays in memory and never leaves the TV.
    val key = scene.streamUrl ?: scene.channel
    val failed = liveEngine.failed || player.failed
    if (key == null) return PlayLook(null, PlayView(null, playing = false, failed = failed, catchup = scene.catchup))
    return when {
        liveEngine.playing && !liveEngine.failed ->
            PlayLook(PlaySource.LIVE_ENGINE, PlayView(key, playing = true, failed = false, catchup = scene.catchup))
        player.playing && playerLive ->
            PlayLook(PlaySource.PLAYER, PlayView(key, playing = true, failed = player.failed, catchup = scene.catchup))
        else -> PlayLook(null, PlayView(key, playing = false, failed = failed, catchup = scene.catchup))
    }
}

/**
 * Pure 30-second rule. Feed every change and every tick; true exactly once per uninterrupted run.
 * A run is a live channel (not catch-up) playing with no failure; anything else ends it, and a new
 * channel key starts a new one. The next run counts from 0 (AC-2).
 */
class PlayRun {
    private var startedAtMs: Long? = null
    private var channelKey: String? = null
    private var proved = false

    /** True while a run counts, so the watcher knows whether to keep ticking. */
    val running: Boolean get() = startedAtMs != null

    fun onView(view: PlayView, nowMs: Long) {
        val live = view.playing && !view.failed && !view.catchup && view.channelKey != null
        if (!live) {
            startedAtMs = null
            channelKey = null
            proved = false
            return
        }
        if (startedAtMs == null || view.channelKey != channelKey) {
            startedAtMs = nowMs
            channelKey = view.channelKey
            proved = false
        }
    }

    fun proven(nowMs: Long): Boolean {
        val start = startedAtMs ?: return false
        if (proved || nowMs - start < PLAY_PROOF_MS) return false
        proved = true
        return true
    }
}

/**
 * Tells [PlayNotes] when a live channel has played 30 seconds in the Live TV box or full screen
 * (AC-1, AC-2), and hands it every failure the reporter records. Catch-up, films, Multiview tiles
 * and the Home hero are never watched (AC-7).
 *
 * The engines come in lazily and are resolved only once [scene] has heard from the shell, like
 * ErrorWatchers: a background process builds no player. It ticks once a second only while a run
 * counts, so a paused or idle TV wakes nothing. Nothing here logs a channel or an address.
 *
 * Koin uses the public constructor; tests use this one, with the notes and reporter as functions.
 */
class PlayWatcher internal constructor(
    private val onPlayed: () -> Unit,
    private val onFailureRecorded: () -> Unit,
    private val startNotes: () -> Unit,
    private val addFailureListener: ((ErrorEntry) -> Unit) -> Unit,
    private val scene: PlaybackScene,
    private val livePreview: Lazy<LivePreviewEngine>,
    private val player: Lazy<OwnTVPlayer>,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {
    constructor(
        notes: PlayNotes,
        reporter: ErrorReporter,
        scene: PlaybackScene,
        livePreview: Lazy<LivePreviewEngine>,
        player: Lazy<OwnTVPlayer>,
        scope: CoroutineScope,
        now: () -> Long,
    ) : this(
        onPlayed = notes::onPlayed,
        onFailureRecorded = notes::onFailureRecorded,
        startNotes = notes::start,
        addFailureListener = { listener -> reporter.addListener(listener) },
        scene = scene,
        livePreview = livePreview,
        player = player,
        scope = scope,
        now = now,
    )

    private val started = AtomicBoolean(false)
    private val run = PlayRun()

    /** Only the watch coroutine touches this, one look at a time. */
    private var lastSource: PlaySource? = null

    /** Registers the reporter listener, starts PlayNotes, then watches. A second call does nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        addFailureListener { onFailureRecorded() }
        startNotes()
        scope.launch(Dispatchers.Default) { guarded { watch() } }
    }

    private suspend fun watch() {
        scene.shown.first { it }
        val preview = livePreview.value
        val full = player.value
        combine(preview.state, preview.errorInfo, full.isPlaying, full.errorInfo) { state, previewError, playing, playerError ->
            Engines(
                EngineLook(playing = state == LivePreviewEngine.State.PLAYING, failed = previewError != null),
                EngineLook(playing = playing, failed = playerError != null),
            )
        }.collectLatest { engines ->
            look(engines, full)
            // collectLatest cancels this loop on the next change, so one look runs at a time.
            while (run.running) {
                delay(PLAY_TICK_MS)
                look(engines, full)
            }
        }
    }

    private fun look(engines: Engines, full: OwnTVPlayer) {
        val look = playLookOf(scene.snapshot(), engines.liveEngine, engines.player, full.isLiveContent)
        val at = now()
        // A channel moving to the other engine started again there: a new run (behaviour 4).
        if (look.source != lastSource) {
            run.onView(STOPPED, at)
            lastSource = look.source
        }
        run.onView(look.view, at)
        if (run.proven(at)) onPlayed()
    }

    /** A watcher that throws stops on its own; the app carries on. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The class name only: the message could hold a stream address.
            Log.w(LOG_TAG, "play watcher stopped: ${e.javaClass.simpleName}")
        }
    }

    private data class Engines(val liveEngine: EngineLook, val player: EngineLook)

    private companion object {
        const val LOG_TAG = "KevBoxUsage"
        val STOPPED = PlayView(channelKey = null, playing = false, failed = false, catchup = false)
    }
}
