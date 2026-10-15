// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.kevbox.access.AccessMonitor
import tv.own.owntv.kevbox.expiry.ExpiryMonitor
import tv.own.owntv.kevbox.playlist.PlaylistReconciler
import tv.own.owntv.kevbox.playlist.PlaylistScreen
import tv.own.owntv.kevbox.session.KevBoxAuth
import tv.own.owntv.kevbox.supabase.Fetch
import tv.own.owntv.kevbox.supabase.PlaylistRow
import tv.own.owntv.kevbox.supabase.SignInResult

/** Which screen KevBox Live puts in front of the app. */
sealed interface GateState {
    data object SignedOut : GateState
    data object Locked : GateState
    data class ChoosePlaylist(val rows: List<PlaylistRow>) : GateState
    data object NoPlaylist : GateState
    data object Open : GateState
}

/**
 * Runs every KevBox Live check on its schedule and turns their answers into one [gate].
 *
 * - Playlist check: at [start], after a successful [signIn], on [onForeground] and every 15 minutes.
 *   A failed fetch changes nothing; a refused refresh signs the TV out (AC-13, AC-18, AC-23).
 * - Verdict check: at [start], after a sign-in, on [onForeground] and every 5 minutes (AC-30).
 * - Expiry: a check at start once a visible source is known, and again whenever the visible source
 *   changes; [ExpiryMonitor.tick] on every timer tick (the monitor keeps its own 6-hour rule) and
 *   when [playbackActive] turns false, so a check never takes a connection slot mid-stream (AC-26).
 *   An admin edit of the visible line only marks the last answer stale: its recheck goes through
 *   the tick, so it waits while something plays.
 * - Profiles: [ProfileDao.observeAll] goes to [PlaylistReconciler.watchProfiles] (AC-24).
 * - Proxy: after each good playlist fetch, a pick and a sign-out, [proxy] sets Core's global proxy to
 *   the one kevbox.dev holds for the playlist on screen.
 *
 * Each check is single flight: a trigger that arrives while one runs waits for that one instead of
 * starting a second fetch, unless the member changed since that one started: a flight started before
 * a sign-out or sign-in drops its answer, and a trigger after either starts a fresh one. The timers wake every [tickMs] through [sleep] and compare [now] with the
 * time each check last started, so a test drives them with a fake clock and a fake sleep.
 */
class KevBoxCoordinator(
    private val auth: KevBoxAuth,
    private val reconciler: PlaylistReconciler,
    private val expiry: ExpiryMonitor,
    private val access: AccessMonitor,
    private val profileDao: ProfileDao,
    private val playbackActive: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val sleep: suspend (ms: Long) -> Unit = { delay(it) },
    private val tickMs: Long = TICK_MS,
    /** The customer's proxy from kevbox.dev; null in tests that do not look at it. */
    private val proxy: tv.own.owntv.kevbox.network.ManagedProxy? = null,
    /** The usage check-in's own foreground check ([tv.own.owntv.kevbox.usage.LiveCheckin.onForeground]). */
    private val checkinOnForeground: () -> Unit = {},
    /** The play notes' foreground retry ([tv.own.owntv.kevbox.usage.PlayNotes.onForeground]). */
    private val playNotesOnForeground: () -> Unit = {},
) {
    /** True when the store held a visible pick at [start]: an offline start opens on it. */
    private val storedPick = MutableStateFlow(false)

    /**
     * False until a playlist fetch of this run succeeded. Until then an empty store reads as
     * [GateState.NoPlaylist], which the gate draws as the bare mark rather than "No playlist".
     */
    val playlistKnown: StateFlow<Boolean> = reconciler.screen
        .map { it != null }
        .stateIn(scope, SharingStarted.Eagerly, reconciler.screen.value != null)

    val gate: StateFlow<GateState> =
        combine(auth.email, access.locked, reconciler.screen, storedPick) { email, locked, screen, picked ->
            gateOf(signedIn = email != null, locked = locked, screen = screen, storedPick = picked)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            gateOf(auth.email.value != null, access.locked.value, reconciler.screen.value, storedPick.value),
        )

    private val playlistFlight = SingleFlight(scope) { startedIn -> runPlaylistCheck(startedIn) }
    private val verdictFlight = SingleFlight(scope) { _ -> access.check() }

    @Volatile private var lastPlaylistAt: Long? = null
    @Volatile private var lastVerdictAt: Long? = null
    @Volatile private var started = false

    /** Goes up on every sign-out and successful sign-in: the member a check started for. */
    @Volatile private var session = 0

    /** Once, from OwnTVApp, after Koin. Later calls do nothing. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            // A store or Core read that throws must not keep the timers from starting.
            quietly { storedPick.value = reconciler.currentSourceId() != null }
            timerLoop()
        }
        scope.launch {
            quietly { reconciler.watchProfiles(profileDao.observeAll().map { profiles -> profiles.map { it.id } }) }
        }
        scope.launch {
            var first = true
            var seenId: Long? = null
            reconciler.visibleSourceChanges.collect {
                if (first) {
                    // The first value is this run's starting point, not a change: the stored pick,
                    // which the reconciler's own ID only learns at the first fetch.
                    first = false
                    var start: Long? = null
                    quietly { start = reconciler.currentSourceId() }
                    seenId = start
                    quietly { expiry.checkAtStart(start) }
                    return@collect
                }
                val id = reconciler.visibleSourceId.value
                if (id != seenId) {
                    // Another line: its own answers, its own popups. The gate stops the old stream.
                    expiry.reset()
                    quietly { expiry.checkAtStart(reconciler.currentSourceId()) }
                } else {
                    // The same line with new credentials: test again, but never mid-stream.
                    expiry.onCredentialsChanged()
                    quietly { expiry.tick(reconciler.currentSourceId(), playing = playbackActive.value) }
                }
                seenId = id
            }
        }
        scope.launch {
            var wasPlaying = playbackActive.value
            playbackActive.collect { playing ->
                if (wasPlaying && !playing) quietly { expiry.tick(reconciler.currentSourceId(), playing = false) }
                wasPlaying = playing
            }
        }
    }

    /**
     * MainActivity.onStart. Also the usage check-in's foreground check: its 6-hour timer stops while
     * the TV sleeps, so an app open is where an overdue check-in goes out (AC-36). A play note
     * still waiting to go out goes again here too (AC-5).
     */
    fun onForeground() {
        scope.launch { playlistCheck() }
        scope.launch { verdictCheck() }
        checkinOnForeground()
        playNotesOnForeground()
    }

    suspend fun signIn(email: String, password: String): SignInResult {
        val result = auth.signIn(email, password)
        if (result is SignInResult.Success) {
            // Only now: a fetch still out for the member before must find the reconciler shut.
            session += 1
            reconciler.reopen()
            scope.launch { verdictCheck() }
            playlistCheck()
        }
        return result
    }

    suspend fun choose(clientNumber: String) {
        reconciler.choose(clientNumber)
        quietly { proxy?.apply(reconciler.rows.value, reconciler.currentClientNumber.value) }
    }

    /** Sign-out and a refused refresh: this TV forgets the member and every line (AC-13, AC-14). */
    /** Each step runs even when one before it threw, so the TV always ends signed out. */
    suspend fun signOut() {
        session += 1
        quietly { auth.signOut() }
        quietly { reconciler.wipe() }
        quietly { proxy?.clear() }
        expiry.reset()
        access.reset()
        storedPick.value = false
    }

    /** Settings › Playlists › Update now: the playlist check, now, joined to one already running. */
    suspend fun checkPlaylistNow() = playlistCheck()

    /** From PlayerHud when the engine it shows reports an error. */
    fun onChannelFailed() {
        scope.launch { quietly { expiry.onChannelFailed(reconciler.currentSourceId()) } }
    }

    private suspend fun playlistCheck() {
        lastPlaylistAt = now()
        playlistFlight.run(session)
    }

    private suspend fun verdictCheck() {
        lastVerdictAt = now()
        verdictFlight.run(session)
    }

    private suspend fun runPlaylistCheck(startedIn: Int) {
        val result = auth.playlists()
        // The member signed out (and maybe another signed in) while this fetch was out: its rows and
        // its refusal both belong to someone else now.
        if (session != startedIn) return
        when (result) {
            is Fetch.Ok -> {
                reconciler.apply(result.value)
                // The playlist on screen decides the proxy, so this follows the reconcile.
                quietly { proxy?.apply(result.value, reconciler.currentClientNumber.value) }
            }
            Fetch.SessionRejected -> signOut()
            // A failed call is never "no rows": the store, the pick and playback stay as they are.
            Fetch.Failed, Fetch.SignedOut -> Unit
        }
    }

    /**
     * Runs the start checks at once, then wakes every [tickMs] and runs whatever is due. The expiry
     * tick waits for the first wake-up: the start test is [ExpiryMonitor.checkAtStart]'s, and two at
     * once would take two connection slots from the line.
     */
    private suspend fun timerLoop() {
        runDueChecks()
        while (true) {
            sleep(tickMs)
            quietly { expiry.tick(reconciler.currentSourceId(), playing = playbackActive.value) }
            runDueChecks()
        }
    }

    private suspend fun runDueChecks() {
        val at = now()
        coroutineScope {
            if (lastVerdictAt.isDue(at, VERDICT_INTERVAL_MS)) launch { verdictCheck() }
            if (lastPlaylistAt.isDue(at, PLAYLIST_INTERVAL_MS)) launch { playlistCheck() }
        }
    }

    private fun Long?.isDue(at: Long, interval: Long) = this == null || at - this >= interval

    /** Runs [block]; a Core call that throws must not end a collector or the timers. */
    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The next emission or tick tries again.
        }
    }

    /**
     * Joins a running [block] started for the same session instead of starting a second one. A
     * running block from an older session is left to finish on its own and drop its answer.
     */
    private class SingleFlight(private val scope: CoroutineScope, private val block: suspend (session: Int) -> Unit) {
        private val lock = Mutex()
        private var running: Deferred<Unit>? = null
        private var runningSession = 0

        suspend fun run(session: Int) {
            val job = lock.withLock {
                running?.takeIf { it.isActive && runningSession == session }
                    ?: scope.async { guarded(session) }.also {
                        running = it
                        runningSession = session
                    }
            }
            job.await()
        }

        // A check that throws (a Core call failing mid-reconcile) must not end the timers.
        private suspend fun guarded(session: Int) {
            try {
                block(session)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Nothing changed that the next check will not redo.
            }
        }
    }

    companion object {
        const val TICK_MS = 60_000L
        const val PLAYLIST_INTERVAL_MS = 15 * 60_000L
        const val VERDICT_INTERVAL_MS = 5 * 60_000L

        /** Gate priority: no session, then the lock, then what the playlist rows say (AC-10). */
        fun gateOf(signedIn: Boolean, locked: Boolean, screen: PlaylistScreen?, storedPick: Boolean): GateState = when {
            !signedIn -> GateState.SignedOut
            locked -> GateState.Locked
            screen is PlaylistScreen.ChooseFirst -> GateState.ChoosePlaylist(screen.rows)
            screen == PlaylistScreen.NoPlaylist -> GateState.NoPlaylist
            screen == PlaylistScreen.Home -> GateState.Open
            // Before this run's first good fetch: open on a stored pick so offline starts keep working.
            storedPick -> GateState.Open
            else -> GateState.NoPlaylist
        }
    }
}
