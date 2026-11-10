// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.vpn

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import tv.own.owntv.MainActivity
import tv.own.owntv.kevbox.errors.PlaybackScene
import tv.own.owntv.kevbox.errors.SceneSnapshot
import tv.own.owntv.kevbox.usage.PlaySource
import tv.own.owntv.kevbox.usage.engineLooksOf
import tv.own.owntv.kevbox.usage.playLookOf
import tv.own.owntv.player.LivePreviewEngine
import tv.own.owntv.player.OwnTVPlayer

/** How long a route change settles before the playing channel is re-tuned; changes inside it merge. */
const val RESTART_SETTLE_MS = 1_000L

/**
 * Re-tunes the playing live channel after the route under it changed: the tunnel came up, went
 * away or moved, or the proxy was switched (0.6 AC-40). A stream opened on the old route freezes;
 * this gives the customer a short pause instead.
 *
 * [restartSoon] waits [RESTART_SETTLE_MS], then:
 * 1. evicts every idle connection from the shared OkHttp pool, so the next Supabase and provider
 *    calls open on the new route (AC-27);
 * 2. only when a live channel plays by 0.4's play test ([playLookOf]) and the app is in front,
 *    re-tunes it: the Live engine through Core's own `onAppBackgrounded()` then
 *    `onAppForegrounded()` (the pair that brings the current channel back at the live edge), the
 *    full-screen player through `retry()`.
 *
 * The pair runs in one main-thread block with the play test, so MainActivity.onStop (also on the
 * main thread) can never fall between them: the foreground call consumes Core's restore slot at
 * once, and a real trip to the background later starts clean. Nothing else hears of the restart:
 * the play notes and the diary see a short stall, as they should. Nothing shows on screen (AC-34).
 * Logs carry class names only.
 *
 * Koin uses the public constructor; tests use this one, with the engines as functions.
 */
class ChannelRestarter internal constructor(
    private val scene: PlaybackScene,
    /** Which engine plays a live channel by the play test, or null. Read only once [scene] has shown. */
    private val playingSource: suspend (SceneSnapshot) -> PlaySource?,
    private val inFront: () -> Boolean,
    private val retuneLive: () -> Unit,
    private val retryPlayer: () -> Unit,
    private val evictPool: () -> Unit,
    private val scope: CoroutineScope,
    private val sleep: suspend (ms: Long) -> Unit,
    /** Where the play test and the engine calls run: Core's engines are main-thread objects. */
    private val main: CoroutineContext,
    /** Where the pool eviction runs: closing sockets stays off the main thread. */
    private val io: CoroutineContext,
) {
    constructor(
        livePreview: Lazy<LivePreviewEngine>,
        player: Lazy<OwnTVPlayer>,
        scene: PlaybackScene,
        http: OkHttpClient,
        application: Application,
        scope: CoroutineScope,
        sleep: suspend (ms: Long) -> Unit = { delay(it) },
    ) : this(
        scene = scene,
        playingSource = { snapshot ->
            val full = player.value
            val engines = engineLooksOf(livePreview.value, full).first()
            playLookOf(snapshot, engines.liveEngine, engines.player, full.isLiveContent).source
        },
        inFront = MainActivityFront(application)::started,
        retuneLive = {
            val preview = livePreview.value
            preview.onAppBackgrounded()
            preview.onAppForegrounded()
        },
        retryPlayer = { player.value.retry() },
        evictPool = { http.connectionPool.evictAll() },
        scope = scope,
        sleep = sleep,
        main = Dispatchers.Main.immediate,
        io = Dispatchers.IO,
    )

    /** True from a [restartSoon] that starts the wait until that wait ends. */
    private val waiting = AtomicBoolean(false)

    /** Re-tunes the playing live channel once, [RESTART_SETTLE_MS] from now; calls within that window merge. */
    fun restartSoon() {
        if (!waiting.compareAndSet(false, true)) return
        scope.launch {
            try {
                sleep(RESTART_SETTLE_MS)
            } finally {
                // Cleared before the restart: a change that lands during it gets one of its own.
                waiting.set(false)
            }
            guarded { restartNow() }
        }
    }

    /** One restart, now: evict the pool, then re-tune whichever engine plays a live channel. */
    internal suspend fun restartNow() {
        guarded { withContext(io) { evictPool() } }
        // Before the shell has shown, no engine exists, and resolving one would build it here.
        if (!scene.shown.value) return
        withContext(main) {
            if (!inFront()) return@withContext
            when (playingSource(scene.snapshot())) {
                PlaySource.LIVE_ENGINE -> retuneLive()
                PlaySource.PLAYER -> retryPlayer()
                null -> Unit
            }
        }
    }

    /** A restart that throws ends on its own; the channel plays on, or stalls as it would have. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "channel restart failed: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val LOG_TAG = "KevBoxVpn"
    }
}

/**
 * Whether a MainActivity (its colour subclasses included) is between onStart and onStop. Registered
 * at construction, which Koin does from OwnTVApp's start-up, before any activity starts.
 */
private class MainActivityFront(application: Application) {
    private val startedActivities = Collections.synchronizedSet(HashSet<Activity>())

    fun started(): Boolean = startedActivities.isNotEmpty()

    init {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    if (activity is MainActivity) startedActivities.add(activity)
                }

                // Removed on every stop, a rotation's too, so the set stays exact.
                override fun onActivityStopped(activity: Activity) {
                    startedActivities.remove(activity)
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }
}
