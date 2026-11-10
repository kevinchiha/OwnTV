// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.kevbox.supabase.VPN_AUTO
import tv.own.owntv.kevbox.supabase.VpnServer
import tv.own.owntv.kevbox.vpn.VpnAction.AskPermission
import tv.own.owntv.kevbox.vpn.VpnAction.Connect
import tv.own.owntv.kevbox.vpn.VpnAction.Disconnect
import tv.own.owntv.kevbox.vpn.VpnAction.PickServers
import tv.own.owntv.kevbox.vpn.VpnAction.Report
import tv.own.owntv.kevbox.vpn.VpnAction.RestartChannel
import tv.own.owntv.kevbox.vpn.VpnAction.SaveFallback
import tv.own.owntv.kevbox.vpn.VpnInput.Background
import tv.own.owntv.kevbox.vpn.VpnInput.EngineDown
import tv.own.owntv.kevbox.vpn.VpnInput.EngineFailed
import tv.own.owntv.kevbox.vpn.VpnInput.EngineUp
import tv.own.owntv.kevbox.vpn.VpnInput.Foreground
import tv.own.owntv.kevbox.vpn.VpnInput.OtherVpnActive
import tv.own.owntv.kevbox.vpn.VpnInput.Permission
import tv.own.owntv.kevbox.vpn.VpnInput.Picked
import tv.own.owntv.kevbox.vpn.VpnInput.SignedOut
import tv.own.owntv.kevbox.vpn.VpnInput.Wanted

class VpnRulesTest {

    /**
     * Drives the rules on a fake clock, the way the runner does: every input at a given time, and a
     * Tick whenever the clock reaches the state's dueAtMs. Every action is logged with its time.
     */
    private class Driver(var state: VpnState) {
        val log = mutableListOf<Pair<Long, VpnAction>>()
        var now = 0L

        fun send(input: VpnInput, at: Long = now): List<VpnAction> {
            now = at
            val step = VpnRules.step(state, input, at)
            state = step.state
            step.actions.forEach { log += at to it }
            return step.actions
        }

        /** Ticks at every due time up to and including [until]; the engine never answers. */
        fun runUntil(until: Long): List<Pair<Long, VpnAction>> {
            val from = log.size
            while (true) {
                val due = state.dueAtMs ?: break
                if (due > until) break
                send(VpnInput.Tick, maxOf(due, now))
            }
            now = maxOf(now, until)
            return log.subList(from, log.size).toList()
        }

        fun connectTimes(): List<Pair<Long, String>> =
            log.mapNotNull { (at, a) -> (a as? Connect)?.let { at to it.host } }
    }

    private fun want(
        server: String = DE,
        backup: String? = NL,
        password: String = "secret",
        changedAt: String? = CHANGED_1,
    ) = VpnWant(CLIENT, server, backup, "user", password, changedAt)

    private fun wanted(want: VpnWant?, otherVpn: Boolean = false, granted: Boolean = true) =
        Wanted(want, otherVpn, granted)

    /** A driver in the foreground with no fall-back stored. */
    private fun onScreen(): Driver = Driver(VpnRules.initial(null, null, null)).also { it.send(Foreground, 0L) }

    private fun s(seconds: Long) = seconds * 1_000L

    private fun fellBack(server: String?) = Report(VpnEventKind.FELL_BACK, server, CLIENT)

    @Test
    fun `three tries a minute apart, then the backup, then fall-back`() {
        val d = onScreen()
        assertEquals(listOf(Connect(DE, want())), d.send(wanted(want()), 0L))
        val rest = d.runUntil(s(3600))

        assertEquals(
            listOf(0L to DE, s(90) to DE, s(180) to DE, s(270) to NL, s(360) to NL, s(450) to NL),
            d.connectTimes(),
        )
        // Every timed-out try was stopped, and nothing was counted twice.
        assertEquals(6, rest.count { it.second == Disconnect })
        val tail = rest.filter { it.first == s(480) }.map { it.second }
        assertEquals(
            listOf(Disconnect, fellBack("$DE,$NL"), SaveFallback(s(480), DE, CHANGED_1)),
            tail,
        )
        // Tries that never came up change no route, so nothing restarts the channel.
        assertFalse(rest.any { it.second == RestartChannel })
        // The fall-back is timed for 3 hours later.
        assertEquals(s(480) + FALLBACK_WAIT_MS, d.state.dueAtMs)
    }

    @Test
    fun `with no backup the cycle falls back after the main server's third failure`() {
        val d = onScreen()
        d.send(wanted(want(backup = null)), 0L)
        d.runUntil(s(3600))
        assertEquals(listOf(0L to DE, s(90) to DE, s(180) to DE), d.connectTimes())
        val fell = d.log.filter { it.second is Report }
        assertEquals(listOf(s(210) to fellBack(DE)), fell)
        assertTrue(d.log.contains(s(210) to SaveFallback(s(210), DE, CHANGED_1)))
    }

    @Test
    fun `a failed engine counts as a failed try at once`() {
        val d = onScreen()
        d.send(wanted(want()), 0L)
        assertEquals(listOf(Disconnect), d.send(EngineFailed, s(5)))
        assertEquals(s(65), d.state.dueAtMs)
        d.runUntil(s(65))
        assertEquals(listOf(0L to DE, s(65) to DE), d.connectTimes())
    }

    @Test
    fun `a connect within 30 seconds reports connected and restarts the channel`() {
        val d = onScreen()
        d.send(wanted(want()), 0L)
        assertEquals(s(30), d.state.dueAtMs)
        assertEquals(
            listOf(Report(VpnEventKind.CONNECTED, DE, CLIENT), RestartChannel),
            d.send(EngineUp, s(29)),
        )
        assertNull(d.state.dueAtMs)
    }

    @Test
    fun `a drop under 30 s is not a failure, over 30 s is`() {
        val d = onScreen()
        d.send(wanted(want()), 0L)
        d.send(EngineUp, s(5))

        assertEquals(emptyList<VpnAction>(), d.send(EngineDown, s(10)))
        assertEquals(s(40), d.state.dueAtMs)
        assertEquals(emptyList<VpnAction>(), d.send(EngineUp, s(39)))
        assertNull(d.state.dueAtMs)

        assertEquals(emptyList<VpnAction>(), d.send(EngineDown, s(100)))
        assertEquals(listOf(Disconnect, RestartChannel), d.send(VpnInput.Tick, s(130)))
        // The next try comes 60 seconds later, on the main server.
        assertEquals(s(190), d.state.dueAtMs)
        assertEquals(listOf(s(190) to Connect(DE, want())), d.runUntil(s(190)))

        // The drop was the first failure on main: two more there, then the backup.
        d.runUntil(s(1000))
        assertEquals(
            listOf(0L to DE, s(190) to DE, s(280) to DE, s(370) to NL, s(460) to NL, s(550) to NL),
            d.connectTimes(),
        )
        assertTrue(d.log.contains(s(580) to fellBack("$DE,$NL")))
    }

    @Test
    fun `a drop on the backup starts the next cycle on main with no failure counted`() {
        val d = onScreen()
        d.send(wanted(want()), 0L)
        d.runUntil(s(270))
        d.send(EngineUp, s(275)) // up on the backup
        d.send(EngineDown, s(300))
        d.runUntil(s(390))
        assertEquals(s(390) to DE, d.connectTimes().last())
        // Three fresh tries on main.
        d.runUntil(s(2000))
        val afterDrop = d.connectTimes().filter { it.first >= s(390) }
        assertEquals(listOf(DE, DE, DE, NL, NL, NL), afterDrop.map { it.second })
    }

    @Test
    fun `fall-back waits 3 hours, survives a restart and needs the switch still on`() {
        val start = 10 * HOUR
        val storedAt = start - 2 * HOUR

        // A restart 2 hours into a fall-back: no connect now.
        val d = Driver(VpnRules.initial(storedAt, DE, CHANGED_1))
        d.now = start
        d.send(Foreground, start)
        assertEquals(emptyList<VpnAction>(), d.send(wanted(want()), start))
        assertEquals(storedAt + FALLBACK_WAIT_MS, d.state.dueAtMs)
        // 3 hours after the stored time, the switch still on: a cycle.
        assertEquals(
            listOf(storedAt + FALLBACK_WAIT_MS to SaveFallback(null, null, null), storedAt + FALLBACK_WAIT_MS to Connect(DE, want())),
            d.runUntil(storedAt + FALLBACK_WAIT_MS),
        )

        // Switched off before the 3 hours: nothing then.
        val off = Driver(VpnRules.initial(storedAt, DE, CHANGED_1))
        off.now = start
        off.send(Foreground, start)
        off.send(wanted(want()), start)
        assertEquals(listOf(SaveFallback(null, null, null)), off.send(wanted(null), start + HOUR / 2))
        assertEquals(emptyList<Pair<Long, VpnAction>>(), off.runUntil(start + 2 * HOUR))
        // Off then on: a cycle at once.
        assertEquals(listOf(Connect(DE, want())), off.send(wanted(want()), start + 2 * HOUR))

        // The same want with only a new changedAt (an off then on the TV never read): a cycle at once.
        val toggled = Driver(VpnRules.initial(storedAt, DE, CHANGED_1))
        toggled.now = start
        toggled.send(Foreground, start)
        toggled.send(wanted(want()), start)
        val again = want(changedAt = CHANGED_2)
        assertEquals(listOf(SaveFallback(null, null, null), Connect(DE, again)), toggled.send(wanted(again), start + 1))
    }

    @Test
    fun `a clock that went back past the fall-back time makes it due`() {
        val storedAt = 10 * HOUR
        val d = Driver(VpnRules.initial(storedAt, DE, CHANGED_1))
        d.send(Foreground, storedAt - HOUR)
        assertEquals(
            listOf(SaveFallback(null, null, null), Connect(DE, want())),
            d.send(wanted(want()), storedAt - HOUR),
        )
    }

    @Test
    fun `a fall-back due off screen waits for the foreground`() {
        val storedAt = 10 * HOUR
        val due = storedAt + FALLBACK_WAIT_MS
        val d = Driver(VpnRules.initial(storedAt, DE, CHANGED_1))
        d.send(wanted(want()), storedAt + HOUR)
        assertEquals(emptyList<VpnAction>(), d.send(VpnInput.Tick, due).filterIsInstance<Connect>())
        assertEquals(
            listOf(SaveFallback(null, null, null), Connect(DE, want())),
            d.send(Foreground, due + HOUR),
        )
    }

    @Test
    fun `the prompt comes once per process and per settings`() {
        val d = Driver(VpnRules.initial(null, null, null))
        // In the background: no prompt.
        assertEquals(emptyList<VpnAction>(), d.send(wanted(want(), granted = false), 0L))
        assertEquals(listOf(AskPermission), d.send(Foreground, s(1)))
        // The prompt is still open: a second Wanted or Foreground asks nothing.
        assertEquals(emptyList<VpnAction>(), d.send(wanted(want(), granted = false), s(2)))
        assertEquals(emptyList<VpnAction>(), d.send(Foreground, s(3)))
        // Refused: one report, and no second ask on the same want.
        assertEquals(
            listOf(Report(VpnEventKind.PERMISSION_DENIED, null, CLIENT)),
            d.send(Permission(false), s(4)),
        )
        assertEquals(emptyList<VpnAction>(), d.send(wanted(want(), granted = false), s(5)))
        d.send(Background, s(6))
        assertEquals(emptyList<VpnAction>(), d.send(Foreground, s(7)))
        // kevbox.dev changed the password: ask again, and OK connects.
        val changed = want(password = "new")
        assertEquals(listOf(AskPermission), d.send(wanted(changed, granted = false), s(8)))
        assertEquals(listOf(Connect(DE, changed)), d.send(Permission(true), s(9)))
    }

    @Test
    fun `a new login reaches the next connect without stopping anything`() {
        val d = onScreen()
        d.send(wanted(want()), 0L)
        val changed = want(password = "new", backup = "vpn-ch.premiumize.me")
        assertEquals(emptyList<VpnAction>(), d.send(wanted(changed), s(10)))
        d.runUntil(s(1000))
        val connects = d.log.mapNotNull { it.second as? Connect }
        assertEquals(want(), connects.first().want)
        assertTrue(connects.drop(1).all { it.want == changed })
        assertEquals(listOf(DE, DE, DE, "vpn-ch.premiumize.me", "vpn-ch.premiumize.me", "vpn-ch.premiumize.me"), connects.map { it.host })
    }

    @Test
    fun `switching off or a new server stops at once`() {
        val d = onScreen()
        d.send(wanted(want()), 0L)
        d.send(EngineUp, s(5))
        val moved = want(server = NL, backup = null, changedAt = CHANGED_2)
        assertEquals(listOf(Disconnect, RestartChannel, Connect(NL, moved)), d.send(wanted(moved), s(10)))
        d.send(EngineUp, s(12))
        assertEquals(listOf(Disconnect, RestartChannel), d.send(wanted(null), s(20)))
        assertNull(d.state.dueAtMs)
        d.send(wanted(want()), s(30))
        assertEquals(listOf(Disconnect), d.send(SignedOut, s(31)))
    }

    @Test
    fun `pickFastest, the background and another VPN`() {
        val servers = listOf(
            VpnServer(CH, "Switzerland", "cfg", null, 3),
            VpnServer(DE, "Germany", "cfg", NL, 1),
            VpnServer(NL, "Netherlands", "cfg", DE, 2),
        )
        assertEquals(NL to DE, VpnRules.pickFastest(servers, mapOf(DE to 40L, NL to 25L, CH to null)))
        assertEquals(DE to NL, VpnRules.pickFastest(servers, mapOf(DE to null, NL to null, CH to null)))
        assertEquals(CH to null, VpnRules.pickFastest(servers, mapOf(CH to 90L)))
        assertEquals(null to null, VpnRules.pickFastest(emptyList(), emptyMap()))

        // With auto, the pick fixes main and backup for the cycle.
        val auto = onScreen()
        val autoWant = want(server = VPN_AUTO, backup = null)
        assertEquals(listOf(PickServers), auto.send(wanted(autoWant), 0L))
        assertEquals(listOf(Connect(NL, autoWant)), auto.send(Picked(NL, DE), s(2)))
        auto.runUntil(s(1000))
        assertEquals(listOf(NL, NL, NL, DE, DE, DE), auto.connectTimes().map { it.second })
        assertTrue(auto.log.any { it.second == fellBack("$NL,$DE") })
        // An unreadable server list three times in a row ends the cycle with nothing tried.
        val blind = onScreen()
        blind.send(wanted(autoWant), 0L)
        blind.send(Picked(null, null), s(1))
        blind.runUntil(s(61))
        blind.send(Picked(null, null), s(62))
        blind.runUntil(s(122))
        assertEquals(listOf(fellBack(null), SaveFallback(s(123), VPN_AUTO, CHANGED_1)), blind.send(Picked(null, null), s(123)))

        // A process started off screen never connects until the app is on screen.
        val boot = Driver(VpnRules.initial(null, null, null))
        assertEquals(emptyList<VpnAction>(), boot.send(wanted(want()), 0L))
        assertNull(boot.state.dueAtMs)
        assertEquals(listOf(Connect(DE, want())), boot.send(Foreground, s(60)))

        // 10 minutes off screen: a plain disconnect; back on screen: the last host again.
        val bg = onScreen()
        bg.send(wanted(want()), 0L)
        bg.send(EngineUp, s(5))
        assertEquals(emptyList<VpnAction>(), bg.send(Background, s(100)))
        assertEquals(s(100) + BACKGROUND_STOP_MS, bg.state.dueAtMs)
        assertEquals(listOf(s(100) + BACKGROUND_STOP_MS to Disconnect), bg.runUntil(s(100) + BACKGROUND_STOP_MS + HOUR))
        assertEquals(listOf(Connect(DE, want())), bg.send(Foreground, 2 * HOUR))
        // Not a new cycle: no second connected report, but the channel moves onto the tunnel.
        assertEquals(listOf(RestartChannel), bg.send(EngineUp, 2 * HOUR + s(3)))
        assertFalse(bg.log.any { it.second is SaveFallback || it.second == fellBack(DE) })

        // Back on screen before the 10 minutes: nothing stops.
        val brief = onScreen()
        brief.send(wanted(want()), 0L)
        brief.send(EngineUp, s(5))
        brief.send(Background, s(100))
        assertEquals(emptyList<VpnAction>(), brief.send(Foreground, s(200)))
        assertEquals(emptyList<Pair<Long, VpnAction>>(), brief.runUntil(2 * HOUR))

        // Another VPN in a Wanted: one report, no connect, until a Wanted finds the TV clear.
        val other = onScreen()
        assertEquals(
            listOf(Report(VpnEventKind.OTHER_VPN_ACTIVE, null, CLIENT)),
            other.send(wanted(want(), otherVpn = true), 0L),
        )
        assertEquals(emptyList<VpnAction>(), other.send(wanted(want(), otherVpn = true), s(900)))
        assertEquals(emptyList<VpnAction>(), other.send(Foreground, s(901)))
        assertEquals(listOf(Connect(DE, want())), other.send(wanted(want()), s(1800)))

        // OtherVpnActive at a retry: one report, no fall-back, no saved time, and idle after.
        val retry = onScreen()
        retry.send(wanted(want()), 0L)
        retry.runUntil(s(90))
        assertEquals(
            listOf(Report(VpnEventKind.OTHER_VPN_ACTIVE, null, CLIENT)),
            retry.send(OtherVpnActive, s(90)),
        )
        assertNull(retry.state.dueAtMs)
        assertEquals(emptyList<Pair<Long, VpnAction>>(), retry.runUntil(10 * HOUR))
        assertFalse(retry.log.any { it.second is SaveFallback || (it.second as? Report)?.kind == VpnEventKind.FELL_BACK })
        assertEquals(listOf(Connect(DE, want())), retry.send(wanted(want()), 10 * HOUR))
    }

    @Test
    fun `a cycle carries on off screen, and a drop off screen waits for the foreground`() {
        val d = onScreen()
        d.send(wanted(want()), 0L)
        d.send(Background, s(10))
        // Retries go on off screen until the stop at 610 s.
        d.runUntil(s(10) + BACKGROUND_STOP_MS)
        assertEquals(listOf(0L to DE, s(90) to DE, s(180) to DE, s(270) to NL, s(360) to NL, s(450) to NL), d.connectTimes())
        // The last backup try timed out at 480 s: the cycle fell back before the stop.
        assertTrue(d.log.any { it.second == fellBack("$DE,$NL") })

        // A drop off screen: no new cycle starts until the app is back, and the drop still counts.
        val dropped = onScreen()
        dropped.send(wanted(want()), 0L)
        dropped.send(EngineUp, s(5))
        dropped.send(Background, s(10))
        dropped.send(EngineDown, s(20))
        // Off screen the channel is not restarted when the tunnel goes.
        assertEquals(listOf(Disconnect), dropped.send(VpnInput.Tick, s(50)))
        assertEquals(emptyList<Pair<Long, VpnAction>>(), dropped.runUntil(HOUR))
        assertEquals(listOf(Connect(DE, want())), dropped.send(Foreground, HOUR))
        dropped.runUntil(2 * HOUR)
        val afterReturn = dropped.connectTimes().filter { it.first >= HOUR }.map { it.second }
        assertEquals(listOf(DE, DE, NL, NL, NL), afterReturn)
    }

    @Test
    fun `an unreadable server list is one failed try, read again a minute later`() {
        val d = onScreen()
        val autoWant = want(server = VPN_AUTO, backup = null)
        assertEquals(listOf(PickServers), d.send(wanted(autoWant), 0L))

        // No fall-back, no report: the read is tried again after the usual wait (AC-30).
        assertEquals(emptyList<VpnAction>(), d.send(Picked(null, null), s(2)))
        assertEquals(VpnPhase.Waiting(s(62)), d.state.phase)
        assertEquals(listOf(s(62) to PickServers), d.runUntil(s(62)))

        // The second read works: the cycle starts with no failure counted against the server.
        assertEquals(listOf(Connect(NL, autoWant)), d.send(Picked(NL, DE), s(63)))
        assertEquals(0, d.state.cycle?.failures)
        assertFalse(d.log.any { it.second is SaveFallback || it.second is Report })
    }

    @Test
    fun `a fresh look at other VPNs lets a Foreground start once the other VPN went away`() {
        val d = Driver(VpnRules.initial(null, null, null))
        d.send(Foreground, 0L)
        assertEquals(
            listOf(Report(VpnEventKind.OTHER_VPN_ACTIVE, null, CLIENT)),
            d.send(wanted(want(), otherVpn = true), s(1)),
        )
        d.send(Background, s(2))

        // The runner looks again before the Foreground: the other VPN is gone.
        d.state = VpnRules.withOtherVpn(d.state, otherVpnActive = false)
        assertEquals(listOf(Connect(DE, want())), d.send(Foreground, s(3)))

        // Still there: still blocked, and not reported twice.
        val blocked = onScreen()
        blocked.send(wanted(want(), otherVpn = true), 0L)
        blocked.state = VpnRules.withOtherVpn(blocked.state, otherVpnActive = true)
        assertEquals(emptyList<VpnAction>(), blocked.send(VpnInput.Tick, s(1)))
        assertEquals(emptyList<VpnAction>(), blocked.send(Foreground, s(2)))
    }

    @Test
    fun `a VPN right lost before a connect asks again, and an open ask ends with the want`() {
        val d = onScreen()
        assertEquals(listOf(Connect(DE, want())), d.send(wanted(want()), 0L))
        // Another VPN app took the right since: nothing started, the prompt comes again.
        assertEquals(listOf(AskPermission), d.send(VpnInput.PermissionNeeded, s(1)))
        assertNull(d.state.dueAtMs)
        assertEquals(listOf(Connect(DE, want())), d.send(Permission(true), s(2)))

        // An ask that never got its answer: switching off and on again asks once more (AC-26).
        val asked = onScreen()
        assertEquals(listOf(AskPermission), asked.send(wanted(want(), granted = false), 0L))
        asked.send(wanted(null), s(1))
        assertFalse(asked.state.asking)
        val again = want(changedAt = CHANGED_2)
        assertEquals(listOf(AskPermission), asked.send(wanted(again, granted = false), s(2)))

        // So does an ask the runner dropped for another app's VPN.
        val other = onScreen()
        other.send(wanted(want(), granted = false), 0L)
        other.send(OtherVpnActive, s(1))
        assertFalse(other.state.asking)
        assertEquals(listOf(AskPermission), other.send(wanted(want(), granted = false), s(2)))
    }

    @Test
    fun `the 30-second connect window runs from the engine's start`() {
        val d = onScreen()
        d.send(wanted(want()), 0L)
        assertEquals(CONNECT_TIMEOUT_MS, d.state.dueAtMs)

        // The runner waited out the linger and read the config: the engine starts 6 s later.
        d.state = VpnRules.engineStarted(d.state, s(6))
        assertEquals(s(6) + CONNECT_TIMEOUT_MS, d.state.dueAtMs)
        assertEquals(emptyList<VpnAction>(), d.send(VpnInput.Tick, CONNECT_TIMEOUT_MS))
        assertEquals(listOf(Disconnect), d.send(VpnInput.Tick, s(6) + CONNECT_TIMEOUT_MS))

        // Outside a try it changes nothing.
        val idle = onScreen()
        assertEquals(idle.state, VpnRules.engineStarted(idle.state, s(6)))
    }

    private companion object {
        const val DE = "vpn-de.premiumize.me"
        const val NL = "vpn-nl.premiumize.me"
        const val CH = "vpn-ch.premiumize.me"
        const val CLIENT = "C1042"
        const val CHANGED_1 = "2026-11-10T18:00:00.000+00:00"
        const val CHANGED_2 = "2026-11-10T19:30:00.000+00:00"
        const val HOUR = 60 * 60_000L
    }
}
