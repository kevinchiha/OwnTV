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
 * - Profiles: every emission of [ProfileDao.observeAll] goes to the reconciler (AC-24).
 *
 * Each check is single flight: a trigger that arrives while one runs waits for that one instead of
 * starting a second fetch. The timers wake every [tickMs] through [sleep] and compare [now] with the
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

    private val playlistFlight = SingleFlight(scope) { runPlaylistCheck() }
    private val verdictFlight = SingleFlight(scope) { access.check() }

    @Volatile private var lastPlaylistAt: Long? = null
    @Volatile private var lastVerdictAt: Long? = null
    @Volatile private var started = false

    /** Once, from OwnTVApp, after Koin. Later calls do nothing. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            storedPick.value = reconciler.currentSourceId() != null
            timerLoop()
        }
        scope.launch {
            profileDao.observeAll().collect { profiles -> quietly { reconciler.onProfilesChanged(profiles.map { it.id }) } }
        }
        scope.launch {
            var seen: Int? = null
            reconciler.visibleSourceChanges.collect { count ->
                // The first value is this run's starting point, not a change.
                if (seen != null) expiry.reset()
                seen = count
                quietly { expiry.checkAtStart(reconciler.currentSourceId()) }
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

    /** MainActivity.onStart. */
    fun onForeground() {
        scope.launch { playlistCheck() }
        scope.launch { verdictCheck() }
    }

    suspend fun signIn(email: String, password: String): SignInResult {
        reconciler.reopen()
        val result = auth.signIn(email, password)
        if (result is SignInResult.Success) {
            scope.launch { verdictCheck() }
            playlistCheck()
        }
        return result
    }

    suspend fun choose(clientNumber: String) {
        reconciler.choose(clientNumber)
    }

    /** Sign-out and a refused refresh: this TV forgets the member and every line (AC-13, AC-14). */
    suspend fun signOut() {
        auth.signOut()
        reconciler.wipe()
        expiry.reset()
        access.reset()
        storedPick.value = false
    }

    /** From PlayerHud when the engine it shows reports an error. */
    fun onChannelFailed() {
        scope.launch { quietly { expiry.onChannelFailed(reconciler.currentSourceId()) } }
    }

    private suspend fun playlistCheck() {
        lastPlaylistAt = now()
        playlistFlight.run()
    }

    private suspend fun verdictCheck() {
        lastVerdictAt = now()
        verdictFlight.run()
    }

    private suspend fun runPlaylistCheck() {
        when (val result = auth.playlists()) {
            is Fetch.Ok -> reconciler.apply(result.value)
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

    /** Joins a running [block] instead of starting a second one. */
    private class SingleFlight(private val scope: CoroutineScope, private val block: suspend () -> Unit) {
        private val lock = Mutex()
        private var running: Deferred<Unit>? = null

        suspend fun run() {
            val job = lock.withLock {
                running?.takeIf { it.isActive } ?: scope.async { guarded() }.also { running = it }
            }
            job.await()
        }

        // A check that throws (a Core call failing mid-reconcile) must not end the timers.
        private suspend fun guarded() {
            try {
                block()
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
