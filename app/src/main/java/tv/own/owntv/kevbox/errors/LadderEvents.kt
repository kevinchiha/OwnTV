// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.errors

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import tv.own.owntv.player.PlayerFailureReason

/**
 * Core's live engine ladder as KevBox hears it. LiveViewModel's host hands every
 * `recordLadderEvent` here, so the watchers can tell an ExoPlayer failure Core recovered from by
 * moving to mpv (`LIVE_FALLBACK`) from one the member saw (`LIVE_NO_FALLBACK`), AC-10.
 */
object LadderEvents {
    private val _events = MutableSharedFlow<PlayerFailureReason>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<PlayerFailureReason> = _events.asSharedFlow()

    /** Safe on the main thread: never suspends. */
    fun record(reason: PlayerFailureReason) {
        _events.tryEmit(reason)
    }
}

/**
 * Holds full-screen playback failures for [holdMs] until Core's ladder says what became of them.
 * `LIVE_FALLBACK` drops every held failure: the other engine took the channel over. `LIVE_NO_FALLBACK`
 * releases them. A failure no event claims within [holdMs] is released by [due]. A failure in the
 * Live TV top box passes straight through: the box has no engine ladder, so the member saw it.
 *
 * The watchers read Core's failure and its ladder event on different threads, so either can arrive
 * first: an event with nothing held waits [holdMs] for the failure it belongs to. Pure and
 * thread-safe; the clock comes in as a parameter.
 */
class FallbackHold(private val holdMs: Long = HOLD_MS) {
    private val held = ArrayList<Pair<Long, Failure.Playback>>()
    private var early: Pair<Long, PlayerFailureReason>? = null

    /** Takes [failure] in at [atMs]; returns what to record now. */
    @Synchronized
    fun hold(failure: Failure.Playback, atMs: Long): List<Failure.Playback> {
        if (failure.engine == Engine.EXO && failure.scene.inBox) return listOf(failure)
        val event = early?.takeIf { atMs - it.first <= holdMs }?.second
        early = null
        return when (event) {
            PlayerFailureReason.LIVE_FALLBACK -> emptyList()
            PlayerFailureReason.LIVE_NO_FALLBACK -> listOf(failure)
            else -> {
                held += atMs to failure
                emptyList()
            }
        }
    }

    /** One ladder event at [atMs]; returns what to record now. Other reasons are ignored. */
    @Synchronized
    fun onLadder(reason: PlayerFailureReason, atMs: Long): List<Failure.Playback> {
        if (reason != PlayerFailureReason.LIVE_FALLBACK && reason != PlayerFailureReason.LIVE_NO_FALLBACK) return emptyList()
        if (held.isEmpty()) {
            early = atMs to reason
            return emptyList()
        }
        val released = if (reason == PlayerFailureReason.LIVE_NO_FALLBACK) held.map { it.second } else emptyList()
        held.clear()
        return released
    }

    /** The held failures no event claimed by [nowMs]. */
    @Synchronized
    fun due(nowMs: Long): List<Failure.Playback> {
        val (old, young) = held.partition { nowMs - it.first >= holdMs }
        held.clear()
        held += young
        return old.map { it.second }
    }

    companion object {
        const val HOLD_MS = 2_000L
    }
}
