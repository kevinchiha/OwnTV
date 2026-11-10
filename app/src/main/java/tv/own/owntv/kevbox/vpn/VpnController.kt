// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.vpn

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.own.owntv.MainActivity
import tv.own.owntv.kevbox.session.KevBoxAuth
import tv.own.owntv.kevbox.supabase.Fetch

/**
 * How long our own tunnel lingers after a stop, and after a main-process death: until then a VPN
 * network on the TV is taken to be ours, and server reads wait so they go outside it.
 */
const val VPN_LINGER_MS = 5_000L

/**
 * Runs [VpnRules] against the real engine for the chosen playlist (0.6).
 *
 * Every input (a want from [ManagedVpn], an engine event, the prompt's answer, the screen going on
 * or off, the timer) goes through one queue that one coroutine reads, so [VpnRules.step] never runs
 * twice at once. After each step it carries out the actions in order, then re-arms one timer for
 * the state's next due time. The actions:
 * - Connect: another app's VPN starts nothing (AC-29). Otherwise it waits out the linger after a
 *   stop, reads that server's config, and starts the engine; a failed read or a missing row is a
 *   failed try at once (AC-24). A newer want or a sign-out queued meanwhile starts nothing: it
 *   decides. The 30-second window runs from the engine's start (AC-28).
 * - PickServers: waits out the linger, reads the server list, pings every host at once and picks
 *   the fastest (AC-41).
 * - AskPermission: Android's VPN prompt, from the started MainActivity only (AC-26).
 * - Report: queues the event for the signed-in member and kicks the sender.
 * A Connect's or PickServers' answer is dropped when a newer try or a stop came in between, so it
 * never lands on a try it does not belong to.
 *
 * Android gives the VPN right to one app at a time, and VpnService.prepare hands it to the caller
 * once the customer has accepted the prompt. So [permissionGranted] (prepare) runs only right before
 * a connect or a prompt, with a want and no other app's VPN up; every other look at the permission
 * uses what the rules already know, so a playlist check never takes the right from another VPN app
 * (AC-27, AC-29).
 *
 * Deadlines run on [monotonic], which a TV syncing its clock does not move; [now] (the wall clock)
 * stamps events and the stored fall-back time, which has to mean the same after a restart (AC-32).
 *
 * Foreground comes only from MainActivity starting, so a process started at boot or by a worker
 * never connects (AC-37). Nothing here logs a login, a config, a host or a token: class names only.
 */
class VpnController(
    private val engine: VpnEngine,
    private val auth: KevBoxAuth,
    private val pinger: Pinger,
    private val store: FallbackStore,
    private val queue: VpnEventQueue,
    private val sender: VpnEventSender,
    private val application: Application,
    private val scope: CoroutineScope,
    /** The wall clock: event times and the stored fall-back time. */
    private val now: () -> Long,
    private val otherVpnActive: () -> Boolean,
    /** VpnService.prepare: may take the VPN right from another app, so only right before a connect or a prompt. */
    private val permissionGranted: () -> Boolean,
    private val restartChannel: () -> Unit = {},
    /** A clock that never jumps, for every in-process deadline. */
    private val monotonic: () -> Long = SystemClock::elapsedRealtime,
) {
    /** One queued piece of work. A want is turned into [VpnInput.Wanted] when its turn comes. */
    private sealed interface Item {
        class Feed(val input: VpnInput) : Item
        class Want(val want: VpnWant?) : Item

        /** A Connect's or PickServers' answer, for the try numbered [attempt]. */
        class Result(val input: VpnInput, val attempt: Int) : Item
    }

    /** The newest want handed in (null: signed out or no VPN), as of the call, not of its turn. */
    private class Latest(val want: VpnWant?)

    private val items = Channel<Item>(Channel.UNLIMITED)

    /** Items queued and not yet fully carried out, actions included. */
    private val pending = AtomicInteger(0)
    private val started = AtomicBoolean(false)

    /** Read by tests; only the worker writes it. */
    @Volatile
    internal var state: VpnState = VpnRules.initial(null, null, null)
        private set

    // The engine as this runner last left it. stoppedAt (monotonic) starts at start(): after a
    // main-process death the system takes a moment to drop the old tunnel.
    @Volatile private var engineRunning = false
    @Volatile private var stoppedAtMs = monotonic()

    private var timer: Job? = null

    /** Bumped by every Connect, PickServers and Disconnect; only the worker touches it. */
    private var attempt = 0

    @Volatile private var latest: Latest? = null

    /** The started MainActivity, the only place the prompt may open from. Set on the main thread. */
    @Volatile private var front: WeakReference<Activity>? = null

    /** A prompt was asked for while no MainActivity was started: it opens at the next start. */
    private val promptWaiting = AtomicBoolean(false)

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            if (activity !is MainActivity) return
            front = WeakReference(activity)
            feed(VpnInput.Foreground)
            if (promptWaiting.getAndSet(false)) openPrompt(activity)
        }

        override fun onActivityStopped(activity: Activity) {
            if (activity !is MainActivity) return
            if (front?.get() === activity) front = null
            if (!activity.isChangingConfigurations) feed(VpnInput.Background)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    /** Once, from KevBoxCoordinator.start(): the lifecycle callbacks, the engine events, the worker, the sender. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        stoppedAtMs = monotonic()
        guardedNow { application.registerActivityLifecycleCallbacks(callbacks) }
        scope.launch {
            guarded {
                engine.events.collect { event ->
                    feed(
                        when (event) {
                            EngineEvent.Up -> VpnInput.EngineUp
                            EngineEvent.Down -> VpnInput.EngineDown
                            EngineEvent.Failed -> VpnInput.EngineFailed
                        },
                    )
                }
            }
        }
        scope.launch(Dispatchers.IO) { worker() }
        // Events left from an earlier process go out once a member is signed in; failed sends retry
        // on the sender's own schedule (AC-35).
        guardedNow { sender.start() }
    }

    /** ManagedVpn, after every successful playlist check and pick: what the chosen row asks for. */
    fun wanted(want: VpnWant?) {
        latest = Latest(want)
        enqueue(Item.Want(want))
    }

    fun signedOut() {
        latest = Latest(null)
        feed(VpnInput.SignedOut)
    }

    /** VpnConsentActivity: the prompt's answer. Back and Cancel arrive as not granted (AC-26). */
    fun onPermission(granted: Boolean) = feed(VpnInput.Permission(granted))

    internal fun feed(input: VpnInput) = enqueue(Item.Feed(input))

    /** Tests: returns once every queued input and the actions it caused are carried out. */
    internal suspend fun settle() {
        while (pending.get() > 0) delay(1)
    }

    private fun enqueue(item: Item) {
        pending.incrementAndGet()
        if (items.trySend(item).isFailure) pending.decrementAndGet()
    }

    private suspend fun worker() {
        guarded {
            // The stored fall-back time is wall clock; the rules run on the monotonic one.
            val storedAt = store.atMs()?.let { wall -> monotonic() - (now() - wall) }
            state = VpnRules.initial(storedAt, store.server(), store.changedAt())
            armTimer()
        }
        for (item in items) {
            try {
                guarded { handle(item) }
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    private suspend fun handle(item: Item) {
        val input = when (item) {
            is Item.Feed -> item.input
            // The permission as the rules know it: prepare() here would take the VPN right from
            // another app on every playlist check (AC-27).
            is Item.Want -> VpnInput.Wanted(item.want, otherVpnNow(), state.permissionGranted)
            // A newer try or a stop came in between: the answer belongs to a try that is gone.
            is Item.Result -> if (item.attempt == attempt) item.input else return
        }
        // Foreground and Tick reach the start rules without a Wanted: look at other VPNs afresh.
        if (input == VpnInput.Foreground || input == VpnInput.Tick) {
            state = VpnRules.withOtherVpn(state, otherVpnNow())
        }
        val before = state
        val step = VpnRules.step(before, input, monotonic())
        state = step.state
        // A prompt waits for MainActivity only while the rules still ask (sign-out, VPN off, an answer).
        if (!state.asking) promptWaiting.set(false)
        armTimer()
        for (action in step.actions) guarded { run(action) }
        // The tunnel settled up: a send that failed over the old route goes out over the new one.
        if (state.phase is VpnPhase.Up && before.phase !is VpnPhase.Up) guardedNow { sender.kick() }
    }

    private suspend fun run(action: VpnAction) {
        when (action) {
            is VpnAction.Connect -> connect(action.host, action.want, ++attempt)
            VpnAction.Disconnect -> {
                attempt++
                disconnect()
            }
            VpnAction.PickServers -> pickServers(++attempt)
            VpnAction.AskPermission -> askPermission()
            is VpnAction.Report -> report(action)
            is VpnAction.SaveFallback -> {
                val at = action.atMs
                val server = action.server
                // Stored as wall clock, so it still means the same after a restart (AC-32).
                if (at == null || server == null) store.clear() else store.save(now() - (monotonic() - at), server, action.changedAt)
            }
            VpnAction.RestartChannel -> restartChannel()
        }
    }

    private suspend fun connect(host: String, want: VpnWant, mine: Int) {
        // Mid-cycle too: another app's VPN means nothing starts (AC-29).
        if (otherVpnNow()) {
            enqueue(Item.Result(VpnInput.OtherVpnActive, mine))
            return
        }
        waitOutLinger()
        val row = when (val result = auth.vpnServers(listOf(host))) {
            is Fetch.Ok -> result.value.firstOrNull { it.host == host }
            else -> null
        }
        if (row == null) {
            // A config that cannot be read is a failed try like any other (AC-24).
            enqueue(Item.Result(VpnInput.EngineFailed, mine))
            return
        }
        // The VPN switched off, its server changed or the member signed out while this waited: the
        // queued input stops or restarts the cycle, so nothing starts here.
        val newest = latest
        if (newest != null && !sameCycle(newest.want, want)) return
        if (otherVpnNow()) {
            enqueue(Item.Result(VpnInput.OtherVpnActive, mine))
            return
        }
        // The only prepare() before a connect, after the other-VPN check came back clear.
        if (!permissionNow()) {
            enqueue(Item.Result(VpnInput.PermissionNeeded, mine))
            return
        }
        engineRunning = true
        try {
            engine.start(host, row.config, want.username, want.password)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "vpn engine start failed: ${e.javaClass.simpleName}")
            enqueue(Item.Result(VpnInput.EngineFailed, mine))
            return
        }
        state = VpnRules.engineStarted(state, monotonic())
        armTimer()
    }

    private fun sameCycle(newest: VpnWant?, want: VpnWant): Boolean =
        newest != null && newest.server == want.server && newest.changedAt == want.changedAt

    private fun disconnect() {
        val wasRunning = engineRunning
        engineRunning = false
        if (wasRunning) stoppedAtMs = monotonic()
        guardedNow { engine.stop() }
        if (wasRunning) {
            // After the old tunnel is gone: a send that failed over it goes out over the plain route.
            scope.launch {
                delay(VPN_LINGER_MS)
                guardedNow { sender.kick() }
            }
        }
    }

    private suspend fun pickServers(mine: Int) {
        waitOutLinger()
        val servers = when (val result = auth.vpnServers(null)) {
            is Fetch.Ok -> result.value
            else -> {
                enqueue(Item.Result(VpnInput.Picked(null, null), mine))
                return
            }
        }
        val averages = coroutineScope {
            servers.map { server -> async { server.host to pingOrNull(server.host) } }.awaitAll()
        }.toMap()
        val (main, backup) = VpnRules.pickFastest(servers, averages)
        enqueue(Item.Result(VpnInput.Picked(main, backup), mine))
    }

    private suspend fun pingOrNull(host: String): Long? = try {
        pinger.averageMs(host)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun askPermission() {
        // prepare() would take the right from another app's running VPN: look first (AC-29).
        if (otherVpnNow()) {
            feed(VpnInput.OtherVpnActive)
            return
        }
        if (permissionNow()) {
            feed(VpnInput.Permission(true))
            return
        }
        // Never from the application context: Android blocks activity starts from the background,
        // so a prompt asked for off screen waits for MainActivity to start (AC-26).
        promptWaiting.set(true)
        val activity = front?.get() ?: return
        activity.runOnUiThread {
            // Still the started MainActivity: a stopped one could not open the prompt on screen.
            if (front?.get() === activity && promptWaiting.getAndSet(false)) openPrompt(activity)
        }
    }

    /** Main thread. A translucent activity on top: MainActivity only pauses, the channel plays on. */
    private fun openPrompt(activity: Activity) {
        val opened = try {
            activity.startActivity(Intent(activity, VpnConsentActivity::class.java))
            true
        } catch (e: Exception) {
            Log.w(LOG_TAG, "vpn prompt failed to start: ${e.javaClass.simpleName}")
            false
        }
        // A prompt that cannot open is a refusal for this app start, so the rules never stay asking (AC-26).
        if (!opened) onPermission(false)
    }

    private fun report(action: VpnAction.Report) {
        // Signed out: nobody to report it for, so it is dropped.
        val member = auth.userId.value ?: return
        queue.add(VpnEvent(UUID.randomUUID().toString(), action.kind, action.server, now(), action.clientNumber), member)
        sender.kick()
    }

    /** Our tunnel lingers briefly after a stop: server reads wait until it is gone. */
    private suspend fun waitOutLinger() {
        if (engineRunning) return
        val left = VPN_LINGER_MS - (monotonic() - stoppedAtMs)
        if (left > 0) delay(left.coerceAtMost(VPN_LINGER_MS))
    }

    /** Item 8: a VPN network counts as another app's only once our own engine has been idle a while. */
    private fun otherVpnNow(): Boolean {
        if (engineRunning || monotonic() - stoppedAtMs < VPN_LINGER_MS) return false
        return try {
            otherVpnActive()
        } catch (_: Exception) {
            false
        }
    }

    private fun permissionNow(): Boolean = try {
        permissionGranted()
    } catch (_: Exception) {
        false
    }

    private fun armTimer() {
        timer?.cancel()
        val due = state.dueAtMs ?: return
        timer = scope.launch {
            delay((due - monotonic()).coerceAtLeast(0))
            feed(VpnInput.Tick)
        }
    }

    /** The app scope has no exception handler: anything thrown here would crash the app. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "vpn runner step failed: ${e.javaClass.simpleName}")
        }
    }

    private fun guardedNow(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "vpn runner call failed: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val LOG_TAG = "KevBoxVpn"
    }
}

/**
 * Android's VPN permission for this app is already given: VpnService.prepare has nothing to ask.
 * Once the customer has accepted the prompt, this call takes the VPN right from whichever app holds
 * it, so [VpnController] calls it only right before a connect or a prompt.
 */
fun vpnPermissionGranted(context: Context): Boolean = try {
    VpnService.prepare(context) == null
} catch (_: Exception) {
    false
}

/**
 * A VPN network is up that is not this app's (AC-29). On Android 11 and later the owner shows; an
 * app only sees its own uid there (NetworkCapabilities.getOwnerUid is API 30, so calling it on
 * Android 10 throws NoSuchMethodError). Before Android 11, the controller's idle rule tells ours apart.
 */
fun otherVpnNetworkPresent(context: Context): Boolean {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val mine = Process.myUid()
    @Suppress("DEPRECATION") // The only way to see every network, not just the default one.
    val networks = connectivity.allNetworks
    return networks.any { network ->
        val caps = connectivity.getNetworkCapabilities(network) ?: return@any false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || caps.ownerUid != mine)
    }
}
