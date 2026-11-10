// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.vpn

import tv.own.owntv.kevbox.supabase.VPN_AUTO
import tv.own.owntv.kevbox.supabase.VpnServer

/** How long a try has to come up before it counts as a failed connect (AC-28). */
const val CONNECT_TIMEOUT_MS = 30_000L

/** How long a drop may last before it counts as a failure (AC-30). */
const val DROP_GRACE_MS = 30_000L

/** The wait after a failure before the next try (AC-30). */
const val RETRY_WAIT_MS = 60_000L

/** Failures on one server before the cycle moves on (AC-30). */
const val TRIES_PER_SERVER = 3

/** How long a fall-back holds before the next cycle (AC-32). */
const val FALLBACK_WAIT_MS = 3 * 60 * 60_000L

/** How long the app may sit off screen before the VPN stops (AC-37). */
const val BACKGROUND_STOP_MS = 10 * 60_000L

/** What the chosen playlist row asks for. [server] is a host or VPN_AUTO; with VPN_AUTO, [backup] is null. [changedAt] is PlaylistVpn.changedAt. */
data class VpnWant(
    val clientNumber: String,
    val server: String,
    val backup: String?,
    val username: String,
    val password: String,
    val changedAt: String?,
) {
    // The login stays out of any text this want turns into, so a stray log line cannot carry it.
    override fun toString(): String =
        "VpnWant(clientNumber=$clientNumber, server=$server, backup=$backup, changedAt=$changedAt)"
}

sealed interface VpnInput {
    /** After every successful playlist check and pick. null = no VPN for the chosen row. */
    data class Wanted(val want: VpnWant?, val otherVpnActive: Boolean, val permissionGranted: Boolean) : VpnInput

    /** The answer to [VpnAction.PickServers]; main null = the server list could not be read. */
    data class Picked(val main: String?, val backup: String?) : VpnInput
    data class Permission(val granted: Boolean) : VpnInput
    data object EngineUp : VpnInput
    data object EngineDown : VpnInput

    /** Also: the server's config could not be loaded. */
    data object EngineFailed : VpnInput

    /** The runner found another app's VPN when about to Connect; it started nothing. */
    data object OtherVpnActive : VpnInput

    /**
     * About to Connect, the runner found Android no longer gives this app the VPN right (another
     * app took it since); it started nothing. The prompt is asked again.
     */
    data object PermissionNeeded : VpnInput
    data object Foreground : VpnInput
    data object Background : VpnInput
    data object SignedOut : VpnInput

    /** The clock reached state.dueAtMs. */
    data object Tick : VpnInput
}

sealed interface VpnAction {
    data class Connect(val host: String, val want: VpnWant) : VpnAction
    data object Disconnect : VpnAction
    data object PickServers : VpnAction
    data object AskPermission : VpnAction
    data class Report(val kind: VpnEventKind, val server: String?, val clientNumber: String?) : VpnAction

    /** [atMs] null = clear. */
    data class SaveFallback(val atMs: Long?, val server: String?, val changedAt: String?) : VpnAction
    data object RestartChannel : VpnAction
}

/** What the rules are doing right now. */
sealed interface VpnPhase {
    /** Nothing runs and nothing is timed. A Wanted, a Foreground or a due fall-back looks again. */
    data object Idle : VpnPhase

    /** Waiting for [VpnInput.Picked]. [carryHost] is a dropped server, see [VpnState.carryHost]. */
    data class Picking(val carryHost: String?) : VpnPhase

    /** The engine is stopped between tries; the next try on the cycle's current server is at [atMs]. */
    data class Waiting(val atMs: Long) : VpnPhase

    /** After a drop: a new cycle starts at [atMs]. [carryHost] is the server that dropped. */
    data class NewCycleAt(val atMs: Long, val carryHost: String) : VpnPhase

    /** A try on [host] runs until [deadlineMs]. [resume] = the reconnect after an off-screen stop. */
    data class Connecting(val host: String, val deadlineMs: Long, val resume: Boolean) : VpnPhase

    /** The tunnel to [host] is up. */
    data class Up(val host: String) : VpnPhase

    /** The tunnel to [host] went down; it counts as a failure if still down at [deadlineMs]. */
    data class Dropped(val host: String, val deadlineMs: Long) : VpnPhase

    /** Stopped off screen. [host] non-null: reconnect it on return; null: carry on the cycle. */
    data class Paused(val host: String?) : VpnPhase
}

/** One cycle's servers and counts (AC-30). [tried] holds the hosts used so far, in order. */
data class VpnCycle(
    val main: String,
    val backup: String?,
    val onBackup: Boolean,
    val failures: Int,
    val tried: List<String>,
) {
    val current: String get() = if (onBackup) backup ?: main else main
}

/** Fields are the implementer's; the runner reads only [dueAtMs]. */
data class VpnState(
    val foreground: Boolean,
    /** The latest want from a Wanted; null when the row has no VPN or none came yet. */
    val want: VpnWant?,
    val permissionGranted: Boolean,
    /** The last Wanted, or an OtherVpnActive, found another app's VPN. */
    val otherVpnActive: Boolean,
    /** OTHER_VPN_ACTIVE already went out for this blocked stretch. */
    val otherReported: Boolean,
    /** AskPermission went out and no Permission came back yet. */
    val asking: Boolean,
    /** The exact want the customer refused the prompt for in this process. */
    val refused: VpnWant?,
    val phase: VpnPhase,
    val cycle: VpnCycle?,
    /** A server that dropped while a new cycle waits to start: it counts once if it is that cycle's main. */
    val carryHost: String?,
    /** The tunnel is up as far as routes go (from EngineUp until the stop that ends it). */
    val tunnelUp: Boolean,
    /** When the off-screen stop happens; null in the foreground. */
    val pauseAtMs: Long?,
    /** Automatic: server-list reads that failed in a row, each one a failed try (AC-30, AC-41). */
    val pickFailures: Int,
    val fallbackAtMs: Long?,
    val fallbackServer: String?,
    val fallbackChangedAt: String?,
    val dueAtMs: Long?,
)

data class VpnStep(val state: VpnState, val actions: List<VpnAction>)

/**
 * Every retry, server change, fall-back, prompt and off-screen rule of the VPN as one pure machine:
 * a state, an input and the time in, a new state and the actions to run out. No Android, no
 * coroutines and no clock reads, so the tests drive it with plain numbers.
 */
object VpnRules {

    fun initial(fallbackAtMs: Long?, fallbackServer: String?, fallbackChangedAt: String?): VpnState {
        // A fall-back needs both its time and its server; half of one is no fall-back.
        val at = fallbackAtMs?.takeIf { fallbackServer != null }
        return VpnState(
            foreground = false,
            want = null,
            permissionGranted = false,
            otherVpnActive = false,
            otherReported = false,
            asking = false,
            refused = null,
            phase = VpnPhase.Idle,
            cycle = null,
            carryHost = null,
            tunnelUp = false,
            pauseAtMs = null,
            pickFailures = 0,
            fallbackAtMs = at,
            fallbackServer = fallbackServer.takeIf { at != null },
            fallbackChangedAt = fallbackChangedAt.takeIf { at != null },
            // A restored fall-back is timed from its stored start; a past one ticks at once and lapses.
            dueAtMs = at?.plus(FALLBACK_WAIT_MS),
        )
    }

    fun step(state: VpnState, input: VpnInput, nowMs: Long): VpnStep {
        val run = Run(state, nowMs)
        when (input) {
            is VpnInput.Wanted -> run.wanted(input)
            is VpnInput.Picked -> run.picked(input)
            is VpnInput.Permission -> run.permission(input.granted)
            VpnInput.EngineUp -> run.engineUp()
            VpnInput.EngineDown -> run.engineDown()
            VpnInput.EngineFailed -> run.engineFailed()
            VpnInput.OtherVpnActive -> run.otherVpn()
            VpnInput.PermissionNeeded -> run.permissionNeeded()
            VpnInput.Foreground -> run.foreground()
            VpnInput.Background -> run.background()
            VpnInput.SignedOut -> run.stopAll(forgetWant = true)
            VpnInput.Tick -> run.tick()
        }
        val end = run.s
        return VpnStep(end.copy(dueAtMs = dueOf(end, nowMs)), run.actions.toList())
    }

    /**
     * [state] with a fresh look at another app's VPN. The runner calls it before a Foreground and a
     * Tick, which reach the start rules without a Wanted, so a VPN that went away since the last
     * playlist check no longer blocks (AC-29).
     */
    fun withOtherVpn(state: VpnState, otherVpnActive: Boolean): VpnState = state.copy(
        otherVpnActive = otherVpnActive,
        otherReported = if (otherVpnActive) state.otherReported else false,
    )

    /**
     * [state] once the runner has really started the engine for the current try: the 30-second
     * window runs from [nowMs], not from the Connect, which may first wait out the linger and read
     * the config (AC-28).
     */
    fun engineStarted(state: VpnState, nowMs: Long): VpnState {
        val p = state.phase as? VpnPhase.Connecting ?: return state
        val moved = state.copy(phase = p.copy(deadlineMs = nowMs + CONNECT_TIMEOUT_MS))
        return moved.copy(dueAtMs = dueOf(moved, nowMs))
    }

    /** AC-41: lowest average first; never-answered hosts skipped; none answered → first two by sortOrder. */
    fun pickFastest(servers: List<VpnServer>, averageMs: Map<String, Long?>): Pair<String?, String?> {
        val ordered = servers.sortedBy { it.sortOrder }
        val answered = ordered
            .filter { averageMs[it.host] != null }
            .sortedBy { averageMs[it.host] }
        val use = answered.ifEmpty { ordered.take(2) }
        return use.getOrNull(0)?.host to use.getOrNull(1)?.host
    }

    /** The earliest time something is timed for, counting a fall-back only while it is still ahead. */
    private fun dueOf(s: VpnState, nowMs: Long): Long? {
        val phaseDue = when (val p = s.phase) {
            is VpnPhase.Waiting -> p.atMs
            is VpnPhase.NewCycleAt -> p.atMs
            is VpnPhase.Connecting -> p.deadlineMs
            is VpnPhase.Dropped -> p.deadlineMs
            else -> null
        }
        val fallbackDue = s.fallbackAtMs?.let { at ->
            val due = at + FALLBACK_WAIT_MS
            if (nowMs in at until due) due else null
        }
        return listOfNotNull(phaseDue, s.pauseAtMs, fallbackDue).minOrNull()
    }

    /** One step's working copy: [s] changes as the rules run, [actions] collects what to do. */
    private class Run(var s: VpnState, val now: Long) {
        val actions = mutableListOf<VpnAction>()

        private fun emit(action: VpnAction) {
            actions += action
        }

        private val engineRuns: Boolean
            get() = s.phase is VpnPhase.Connecting || s.phase is VpnPhase.Up || s.phase is VpnPhase.Dropped

        private fun fallbackStored() = s.fallbackAtMs != null

        private fun fallbackDue(): Boolean {
            val at = s.fallbackAtMs ?: return true
            return now >= at + FALLBACK_WAIT_MS || now < at
        }

        private fun clearFallback() {
            if (!fallbackStored()) return
            emit(VpnAction.SaveFallback(null, null, null))
            s = s.copy(fallbackAtMs = null, fallbackServer = null, fallbackChangedAt = null)
        }

        /** The tunnel stops carrying traffic: restart the channel only when someone is watching. */
        private fun tunnelDown() {
            if (s.tunnelUp && s.foreground) emit(VpnAction.RestartChannel)
            s = s.copy(tunnelUp = false)
        }

        private fun tunnelUp() {
            if (!s.tunnelUp && s.foreground) emit(VpnAction.RestartChannel)
            s = s.copy(tunnelUp = true)
        }

        private fun report(kind: VpnEventKind, server: String?) {
            emit(VpnAction.Report(kind, server, s.want?.clientNumber))
        }

        private fun reportOtherVpnOnce() {
            if (s.otherReported) return
            report(VpnEventKind.OTHER_VPN_ACTIVE, null)
            s = s.copy(otherReported = true)
        }

        /** Item 7: stop whatever runs and clear the fall-back, the refusal and an open ask. */
        fun stopAll(forgetWant: Boolean) {
            if (engineRuns) emit(VpnAction.Disconnect)
            tunnelDown()
            clearFallback()
            s = s.copy(
                phase = VpnPhase.Idle,
                cycle = null,
                carryHost = null,
                pickFailures = 0,
                refused = null,
                // An ask that never got its answer must not block the next want (AC-26).
                asking = false,
                want = if (forgetWant) null else s.want,
            )
        }

        fun wanted(input: VpnInput.Wanted) {
            s = s.copy(
                otherVpnActive = input.otherVpnActive,
                otherReported = if (input.otherVpnActive) s.otherReported else false,
                permissionGranted = input.permissionGranted,
            )
            val want = input.want
            if (want == null) {
                stopAll(forgetWant = true)
                return
            }
            val old = s.want
            val sameCycle = old != null && old.server == want.server && old.changedAt == want.changedAt
            if (old != null && !sameCycle) stopAll(forgetWant = false)
            s = s.copy(want = want)
            if (sameCycle) {
                // Only the login, the client number or the backup moved: the next Connect reads them.
                val cycle = s.cycle
                if (cycle != null && !cycle.onBackup && want.server != VPN_AUTO) {
                    s = s.copy(cycle = cycle.copy(backup = backupFor(want, cycle.main)))
                }
                if (s.phase != VpnPhase.Idle) return
            }
            consider()
        }

        /** Item 1: decide whether a cycle may start now, ask for permission, or wait. */
        private fun consider() {
            val want = s.want ?: return
            if (s.phase != VpnPhase.Idle) return
            if (fallbackStored()) {
                val same = s.fallbackServer == want.server && s.fallbackChangedAt == want.changedAt
                if (!same) clearFallback() else if (!fallbackDue()) return
            }
            if (s.otherVpnActive) {
                reportOtherVpnOnce()
                return
            }
            if (!s.permissionGranted) {
                if (s.asking || s.refused == want || !s.foreground) return
                emit(VpnAction.AskPermission)
                s = s.copy(asking = true)
                return
            }
            if (!s.foreground) return
            startCycle()
        }

        /** Item 2. A pending [VpnState.carryHost] counts once if it turns out to be the main server. */
        private fun startCycle() {
            val want = s.want ?: return
            clearFallback()
            val carry = s.carryHost
            s = s.copy(carryHost = null, cycle = null)
            if (want.server == VPN_AUTO) {
                s = s.copy(phase = VpnPhase.Picking(carry))
                emit(VpnAction.PickServers)
            } else {
                beginCycle(want.server, backupFor(want, want.server), carry)
            }
        }

        private fun backupFor(want: VpnWant, main: String): String? =
            want.backup?.takeIf { it.isNotBlank() && it != main }

        private fun beginCycle(main: String, backup: String?, carry: String?) {
            val counted = carry == main
            s = s.copy(
                pickFailures = 0,
                cycle = VpnCycle(
                    main = main,
                    backup = backup?.takeIf { it != main },
                    onBackup = false,
                    failures = if (counted) 1 else 0,
                    tried = if (counted) listOf(main) else emptyList(),
                ),
            )
            connect(main, resume = false)
        }

        private fun connect(host: String, resume: Boolean) {
            val want = s.want ?: return
            val cycle = s.cycle
            if (cycle != null && host !in cycle.tried) s = s.copy(cycle = cycle.copy(tried = cycle.tried + host))
            emit(VpnAction.Connect(host, want))
            s = s.copy(phase = VpnPhase.Connecting(host, now + CONNECT_TIMEOUT_MS, resume))
        }

        fun picked(input: VpnInput.Picked) {
            val phase = s.phase as? VpnPhase.Picking ?: return
            val main = input.main
            if (main == null) {
                // The server list could not be read: one failed try, read again after the usual
                // wait, and the cycle ends after the third, as on a fixed server (AC-30).
                val failures = s.pickFailures + 1
                if (failures >= TRIES_PER_SERVER) {
                    s = s.copy(cycle = null)
                    fallBack()
                } else {
                    s = s.copy(
                        cycle = null,
                        carryHost = phase.carryHost,
                        pickFailures = failures,
                        phase = VpnPhase.Waiting(now + RETRY_WAIT_MS),
                    )
                }
                return
            }
            beginCycle(main, input.backup, phase.carryHost)
        }

        /** Automatic: read the server list again for the same cycle; a dropped server still counts once. */
        private fun pickAgain() {
            s = s.copy(phase = VpnPhase.Picking(s.carryHost), carryHost = null)
            emit(VpnAction.PickServers)
        }

        fun permission(granted: Boolean) {
            s = s.copy(asking = false)
            if (granted) {
                s = s.copy(permissionGranted = true)
                consider()
            } else {
                s = s.copy(permissionGranted = false, refused = s.want)
                report(VpnEventKind.PERMISSION_DENIED, null)
            }
        }

        fun engineUp() {
            when (val p = s.phase) {
                is VpnPhase.Connecting -> {
                    s = s.copy(phase = VpnPhase.Up(p.host))
                    if (!p.resume) report(VpnEventKind.CONNECTED, p.host)
                    tunnelUp()
                }
                is VpnPhase.Dropped -> s = s.copy(phase = VpnPhase.Up(p.host))
                else -> Unit
            }
        }

        fun engineDown() {
            val p = s.phase as? VpnPhase.Up ?: return
            s = s.copy(phase = VpnPhase.Dropped(p.host, now + DROP_GRACE_MS))
        }

        fun engineFailed() {
            when (val p = s.phase) {
                is VpnPhase.Connecting -> {
                    emit(VpnAction.Disconnect)
                    if (p.resume) dropped(p.host) else failure()
                }
                // The engine gave up on a tunnel that was up: it will not come back, so the drop ends now.
                is VpnPhase.Up -> {
                    emit(VpnAction.Disconnect)
                    dropped(p.host)
                }
                is VpnPhase.Dropped -> {
                    emit(VpnAction.Disconnect)
                    dropped(p.host)
                }
                else -> Unit
            }
        }

        /** Item 4: one failed try on the cycle's current server. The engine is already stopped. */
        private fun failure() {
            val cycle = s.cycle
            if (cycle == null) {
                s = s.copy(phase = VpnPhase.Idle)
                return
            }
            val failures = cycle.failures + 1
            val next = when {
                failures < TRIES_PER_SERVER -> cycle.copy(failures = failures)
                !cycle.onBackup && cycle.backup != null -> cycle.copy(onBackup = true, failures = 0)
                else -> null
            }
            if (next == null) {
                fallBack()
            } else {
                s = s.copy(cycle = next, phase = VpnPhase.Waiting(now + RETRY_WAIT_MS))
            }
        }

        private fun fallBack() {
            val want = s.want
            val tried = s.cycle?.tried.orEmpty()
            report(VpnEventKind.FELL_BACK, tried.joinToString(",").ifEmpty { null })
            s = s.copy(phase = VpnPhase.Idle, cycle = null, carryHost = null, pickFailures = 0)
            if (want != null) {
                emit(VpnAction.SaveFallback(now, want.server, want.changedAt))
                s = s.copy(fallbackAtMs = now, fallbackServer = want.server, fallbackChangedAt = want.changedAt)
            }
        }

        /** Item 5: a drop that lasted past the grace. The engine is already stopped. */
        private fun dropped(host: String) {
            tunnelDown()
            s = s.copy(phase = VpnPhase.NewCycleAt(now + RETRY_WAIT_MS, host), cycle = null)
        }

        fun otherVpn() {
            if (s.phase is VpnPhase.Up || s.phase is VpnPhase.Dropped) emit(VpnAction.Disconnect)
            tunnelDown()
            // Also the answer to an AskPermission the runner dropped for another app's VPN.
            s = s.copy(otherVpnActive = true, asking = false, phase = VpnPhase.Idle, cycle = null, carryHost = null)
            reportOtherVpnOnce()
        }

        /** The try found the VPN right gone: nothing started, so ask again (AC-26). */
        fun permissionNeeded() {
            if (s.phase !is VpnPhase.Connecting) return
            s = s.copy(permissionGranted = false, phase = VpnPhase.Idle, cycle = null, carryHost = null)
            consider()
        }

        fun foreground() {
            s = s.copy(foreground = true, pauseAtMs = null)
            when (val p = s.phase) {
                is VpnPhase.Paused -> {
                    val host = p.host
                    val cycle = s.cycle
                    when {
                        host != null -> connect(host, resume = true)
                        cycle != null -> connect(cycle.current, resume = false)
                        else -> s = s.copy(phase = VpnPhase.Idle)
                    }
                }
                else -> Unit
            }
            if (s.phase == VpnPhase.Idle) consider()
        }

        fun background() {
            if (!s.foreground && s.pauseAtMs != null) return
            s = s.copy(foreground = false, pauseAtMs = s.pauseAtMs ?: (now + BACKGROUND_STOP_MS))
        }

        /** Item 9: the off-screen stop. Not a failure; the fall-back time stays as it is. */
        private fun pause() {
            s = s.copy(pauseAtMs = null)
            when (val p = s.phase) {
                is VpnPhase.Up -> {
                    emit(VpnAction.Disconnect)
                    tunnelDown()
                    s = s.copy(phase = VpnPhase.Paused(p.host))
                }
                is VpnPhase.Dropped -> {
                    emit(VpnAction.Disconnect)
                    tunnelDown()
                    s = s.copy(phase = VpnPhase.Paused(p.host))
                }
                is VpnPhase.Connecting -> {
                    emit(VpnAction.Disconnect)
                    s = s.copy(phase = VpnPhase.Paused(if (p.resume) p.host else null))
                }
                is VpnPhase.Waiting -> s = s.copy(phase = VpnPhase.Paused(null))
                is VpnPhase.NewCycleAt -> s = s.copy(phase = VpnPhase.Idle, carryHost = p.carryHost)
                is VpnPhase.Picking -> s = s.copy(phase = VpnPhase.Idle, carryHost = p.carryHost)
                VpnPhase.Idle, is VpnPhase.Paused -> Unit
            }
        }

        fun tick() {
            val pauseAt = s.pauseAtMs
            if (pauseAt != null && now >= pauseAt) {
                pause()
                return
            }
            when (val p = s.phase) {
                is VpnPhase.Connecting -> if (now >= p.deadlineMs) {
                    emit(VpnAction.Disconnect)
                    if (p.resume) dropped(p.host) else failure()
                }
                is VpnPhase.Waiting -> if (now >= p.atMs) {
                    val cycle = s.cycle
                    when {
                        cycle != null -> connect(cycle.current, resume = false)
                        s.want?.server == VPN_AUTO -> pickAgain()
                        else -> s = s.copy(phase = VpnPhase.Idle)
                    }
                }
                is VpnPhase.NewCycleAt -> if (now >= p.atMs) {
                    // A new cycle never starts off screen: it waits for the next Foreground.
                    s = s.copy(phase = VpnPhase.Idle, carryHost = p.carryHost)
                    consider()
                }
                is VpnPhase.Dropped -> if (now >= p.deadlineMs) {
                    emit(VpnAction.Disconnect)
                    dropped(p.host)
                }
                VpnPhase.Idle -> if (fallbackStored() && fallbackDue()) consider()
                is VpnPhase.Picking, is VpnPhase.Up, is VpnPhase.Paused -> Unit
            }
        }
    }
}
