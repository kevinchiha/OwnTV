// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.kevbox.network.ManagedProxy
import tv.own.owntv.kevbox.supabase.PlaylistProxy
import tv.own.owntv.kevbox.supabase.PlaylistRow
import tv.own.owntv.kevbox.supabase.PlaylistVpn

class ManagedVpnTest {
    private val vpnB = PlaylistVpn(
        enabled = true,
        server = "vpn-nl.premiumize.me",
        backupServer = "vpn-de.premiumize.me",
        username = "vu",
        password = "vp",
        changedAt = "2026-11-10T09:00:00+00:00",
    )
    private val proxyA = PlaylistProxy(true, "a.example", 3128, "u", "p")

    private fun row(n: String, vpn: PlaylistVpn = PlaylistVpn.OFF, proxy: PlaylistProxy = PlaylistProxy.OFF) =
        PlaylistRow(n, "Line $n", "http://s$n", "u", "p", proxy, vpn)

    @Test
    fun `vpnFor follows the chosen row and the VPN wins over the proxy`() {
        val rows = listOf(row("A"), row("B", vpnB))

        val want = ManagedVpn.vpnFor(rows, "B")
        assertEquals(
            VpnWant(
                clientNumber = "B",
                server = "vpn-nl.premiumize.me",
                backup = "vpn-de.premiumize.me",
                username = "vu",
                password = "vp",
                changedAt = "2026-11-10T09:00:00+00:00",
            ),
            want,
        )
        // Several rows and none chosen: no VPN, as proxyFor gives no proxy.
        assertNull(ManagedVpn.vpnFor(rows, null))
        assertNull(ManagedVpn.vpnFor(rows, "A"))
        assertNull(ManagedVpn.vpnFor(listOf(row("A", vpnB.copy(enabled = false))), null))

        // One row with both on: the VPN goes to the controller and the proxy goes off (AC-25).
        val both = listOf(row("A", vpnB, proxyA))
        assertEquals("A", ManagedVpn.vpnFor(both, null)?.clientNumber)
        assertEquals(PlaylistProxy.OFF, ManagedProxy.proxyFor(both, null))
    }

    @Test
    fun `apply hands the want over and clear signs out`() {
        val wants = mutableListOf<VpnWant?>()
        var signOuts = 0
        val managed = ManagedVpn(wanted = { wants += it }, signedOut = { signOuts += 1 })

        managed.apply(listOf(row("B", vpnB)), "B")
        managed.apply(listOf(row("B")), "B")
        managed.clear()

        assertEquals(listOf("B", null), wants.map { it?.clientNumber })
        assertEquals(1, signOuts)
    }
}
