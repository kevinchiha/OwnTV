// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.usage

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.own.owntv.MainActivity
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.parser.XtEpgEntry
import tv.own.owntv.kevbox.errors.PlaybackScene
import tv.own.owntv.kevbox.network.ProxyInUse
import tv.own.owntv.kevbox.session.KevBoxAuth
import tv.own.owntv.player.LivePreviewEngine
import tv.own.owntv.player.OwnTVPlayer

/** How often the guide is asked again while the open stretch has no programme (AC-2, AC-4). */
private const val GUIDE_RETRY_MS: Long = 60_000L

/** How often an open or stalled stretch is looked at again. */
private const val DIARY_TICK_MS: Long = 1_000L

/**
 * The watching diary: cuts live play into stretches from the same engines and scene as PlayWatcher,
 * stamps each with the guide's on-now title and the last remote press, and queues the finished ones
 * for the signed-in member (signed out, they are dropped, AC-8).
 *
 * Two clocks. The cutter runs on [elapsed] (monotonic), so a clock correction during a stretch
 * (NTP after a cold boot) never stretches or misdates it. Each stretch leaves the cutter with its
 * end on the wall clock as of then ([now]) and its start that end minus its length; the server
 * shifts both by `tv_now`.
 *
 * Every cutter call, queue write and send runs one at a time under [worker], off the main thread.
 * The lifecycle callbacks only post to it. It ticks once a second only while a stretch is open, so
 * an idle TV wakes nothing. Nothing here logs a channel, a title or an address.
 *
 * Koin uses the public constructor; tests use this one, with the collaborators as functions.
 */
class DiaryWatcher internal constructor(
    private val cutter: StretchCutter,
    private val queue: DiaryQueue,
    private val startSender: () -> Unit,
    private val sendNow: () -> Unit,
    private val lastPress: LastPress,
    private val guide: suspend (ChannelEntity) -> XtEpgEntry?,
    private val userId: () -> String?,
    private val scene: PlaybackScene,
    private val engines: () -> Flow<EngineLooks>,
    private val playerLive: () -> Boolean,
    private val proxy: suspend () -> Boolean,
    private val registerCallbacks: (Application.ActivityLifecycleCallbacks) -> Unit,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val elapsed: () -> Long,
) {
    constructor(
        cutter: StretchCutter,
        queue: DiaryQueue,
        sender: DiarySender,
        lastPress: LastPress,
        guide: GuideNow,
        auth: KevBoxAuth,
        scene: PlaybackScene,
        livePreview: Lazy<LivePreviewEngine>,
        player: Lazy<OwnTVPlayer>,
        proxyInUse: ProxyInUse,
        application: Application,
        scope: CoroutineScope,
        now: () -> Long,
        elapsed: () -> Long,
    ) : this(
        cutter = cutter,
        queue = queue,
        startSender = sender::start,
        sendNow = sender::sendNow,
        lastPress = lastPress,
        guide = guide::nowOn,
        userId = { auth.userId.value },
        scene = scene,
        // Resolved only once the scene has shown, like PlayWatcher: a background process builds no player.
        engines = { engineLooksOf(livePreview.value, player.value) },
        playerLive = { player.value.isLiveContent },
        proxy = proxyInUse::current,
        registerCallbacks = application::registerActivityLifecycleCallbacks,
        scope = scope,
        now = now,
        elapsed = elapsed,
    )

    private val started = AtomicBoolean(false)
    private val worker = Mutex()

    // Held under [worker]: the open stretch last saved, and when (elapsed) it opened or was saved.
    private var savedId: String? = null
    private var trackedId: String? = null
    private var lastSaveAt = 0L

    // Only the watch coroutine touches these: the guide answer for the channel on screen.
    private var guideKey: String? = null
    private var guideEntry: XtEpgEntry? = null
    private var guideAskedAt = 0L

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            if (activity is MainActivity) onAppStarted()
        }

        override fun onActivityStopped(activity: Activity) {
            if (activity is MainActivity && !activity.isChangingConfigurations) onAppStopped()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    /** Recovers the open stretch, registers the lifecycle callbacks, starts the sender, watches. Once. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) { guarded { worker.withLock { queue.recoverOpen() } } }
        registerCallbacks(callbacks)
        startSender()
        scope.launch(Dispatchers.Default) { guarded { watch() } }
    }

    /** MainActivity started: the app start counts as a press (AC-5). */
    internal fun onAppStarted() {
        lastPress.press()
    }

    /** MainActivity went to the background: post the close and the send to the worker. */
    internal fun onAppStopped() {
        scope.launch(Dispatchers.IO) { guarded { background() } }
    }

    /** The background close (AC-2), then a send (AC-7). */
    internal suspend fun background() {
        worker.withLock {
            val clock = clockNow()
            finish(cutter.onBackground(clock.steady, clock.steadyOf(lastPress.at())), clock)
        }
        sendNow()
    }

    private suspend fun watch() {
        scene.shown.first { it }
        engines().collectLatest { looks ->
            look(looks)
            // collectLatest cancels this loop on the next change, so one look runs at a time.
            while (worker.withLock { cutter.isOpen }) {
                delay(DIARY_TICK_MS)
                look(looks)
            }
        }
    }

    /** One look: the scene and engines through 0.4's play test, then the cutter. */
    internal suspend fun look(looks: EngineLooks) {
        val snap = scene.snapshot()
        val view = playLookOf(snap, looks.liveEngine, looks.player, playerLive()).view
        val look = DiaryLook(
            playing = view.playing,
            failed = view.failed,
            catchup = view.catchup,
            channelKey = view.channelKey,
            inBox = snap.inBox,
            channel = snap.channel,
            category = snap.category,
            streamUrl = snap.streamUrl,
        )
        val plays = look.playing && !look.failed && !look.catchup && look.channelKey != null
        val entry = if (plays) programmeFor(look.channelKey, snap.channelEntity) else null
        val proxyNow = plays && proxyOrFalse()
        worker.withLock {
            val clock = clockNow()
            // The guide's stop is wall clock; the cutter's clock is elapsed.
            val programme = entry?.let { it.copy(stopMs = clock.steadyOf(it.stopMs)) }
            finish(cutter.onLook(look, programme, clock.steady, clock.steadyOf(lastPress.at()), proxyNow), clock)
        }
    }

    /** Call with [worker] held: queues what finished, clears or refreshes the open save (AC-6). */
    private fun finish(done: List<Stretch>, clock: Clock) {
        val member = userId()
        if (member != null) done.forEach { queue.add(clock.wall(it), member) }
        val openId = cutter.openId
        if (savedId != null && savedId != openId) {
            queue.clearOpen()
            savedId = null
        }
        if (openId != trackedId) {
            trackedId = openId
            lastSaveAt = clock.steady
        }
        if (openId != null && clock.steady - lastSaveAt >= OPEN_SAVE_MS) {
            lastSaveAt = clock.steady
            val snapshot = cutter.open(clock.steady, clock.steadyOf(lastPress.at()))
            if (snapshot != null && member != null) {
                queue.saveOpen(clock.wall(snapshot), member)
                savedId = snapshot.id
            }
        }
    }

    /** The guide entry for [key]: asked when the channel changes, its programme ends, or every 60 s with none. */
    private suspend fun programmeFor(key: String?, channel: ChannelEntity?): XtEpgEntry? {
        val at = elapsed()
        val entry = guideEntry
        val ask = key != guideKey ||
            (entry == null && at - guideAskedAt >= GUIDE_RETRY_MS) ||
            (entry != null && now() >= entry.stopMs)
        if (ask) {
            guideKey = key
            guideAskedAt = at
            guideEntry = channel?.let { guardedOrNull { guide(it) } }
        }
        return guideEntry
    }

    private suspend fun proxyOrFalse(): Boolean = guardedOrNull { proxy() } ?: false

    private fun clockNow(): Clock {
        val steady = elapsed()
        return Clock(steady = steady, offset = now() - steady)
    }

    /** One reading of both clocks: [steady] is elapsed, wall = steady + [offset]. */
    private class Clock(val steady: Long, val offset: Long) {
        fun steadyOf(wallMs: Long): Long = wallMs - offset

        /** The end on the wall clock as of now, the start that end minus the elapsed length. */
        fun wall(s: Stretch): Stretch {
            val ended = s.endedAtMs + offset
            return s.copy(
                startedAtMs = ended - (s.endedAtMs - s.startedAtMs),
                endedAtMs = ended,
                lastPressAtMs = minOf(s.lastPressAtMs + offset, ended),
            )
        }
    }

    /** A watcher that throws stops on its own; the app carries on. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The class name only: the message could hold a stream address.
            Log.w(LOG_TAG, "diary watcher stopped: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun <T> guardedOrNull(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val LOG_TAG = "KevBoxUsage"
    }
}
