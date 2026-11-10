// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.kevbox.supabase.PlaylistProxy
import tv.own.owntv.kevbox.supabase.PlaylistRow
import tv.own.owntv.kevbox.supabase.PlaylistVpn

class ManagedProxyTest {
    private val proxyA = PlaylistProxy(true, "a.example", 3128, "u", "p")
    private val proxyB = PlaylistProxy(true, "b.example", 8080, "", "")

    private fun row(n: String, proxy: PlaylistProxy = PlaylistProxy.OFF) = PlaylistRow(n, "Line $n", "http://s$n", "u", "p", proxy)

    private class Target(var stored: PlaylistProxy) : ProxyTarget {
        var saves = mutableListOf<PlaylistProxy>()
        override suspend fun current() = stored
        override suspend fun save(proxy: PlaylistProxy) { stored = proxy; saves += proxy }
    }

    @Test
    fun `the chosen playlist decides, and with no choice the only playlist does`() {
        val rows = listOf(row("1", proxyA), row("2", proxyB))
        assertEquals(proxyB, ManagedProxy.proxyFor(rows, "2"))
        assertEquals(proxyA, ManagedProxy.proxyFor(listOf(row("1", proxyA)), null))
        assertEquals(PlaylistProxy.OFF, ManagedProxy.proxyFor(rows, null))
        assertEquals(PlaylistProxy.OFF, ManagedProxy.proxyFor(emptyList(), "1"))
    }

    @Test
    fun `a playlist whose proxy is off turns the proxy off`() {
        assertEquals(PlaylistProxy.OFF, ManagedProxy.proxyFor(listOf(row("1", proxyA.copy(enabled = false))), "1"))
    }

    @Test
    fun `a playlist whose VPN is on gets no proxy - the VPN wins`() {
        val vpn = PlaylistVpn(true, "vpn-de.premiumize.me", null, "vu", "vp", null)
        val rows = listOf(row("1", proxyA).copy(vpn = vpn), row("2", proxyB))
        assertEquals(PlaylistProxy.OFF, ManagedProxy.proxyFor(rows, "1"))
        assertEquals(PlaylistProxy.OFF, ManagedProxy.proxyFor(listOf(rows[0]), null))
        assertEquals(proxyB, ManagedProxy.proxyFor(rows, "2"))
        assertEquals(proxyA, ManagedProxy.proxyFor(listOf(row("1", proxyA).copy(vpn = vpn.copy(enabled = false))), null))
    }

    @Test
    fun `apply writes only when the proxy changes, and an off proxy with old fields counts as off`() = runBlocking {
        val target = Target(stored = PlaylistProxy(false, "old.example", 1, "x", "y"))
        val managed = ManagedProxy(target)

        managed.apply(listOf(row("1")), "1")
        assertEquals(emptyList<PlaylistProxy>(), target.saves)

        managed.apply(listOf(row("1", proxyA)), "1")
        managed.apply(listOf(row("1", proxyA)), "1")
        assertEquals(listOf(proxyA), target.saves)

        managed.clear()
        assertEquals(listOf(proxyA, PlaylistProxy.OFF), target.saves)
    }

    @Test
    fun `apply says whether it saved a different proxy`() = runBlocking {
        val target = Target(stored = PlaylistProxy.OFF)
        val managed = ManagedProxy(target)

        assertEquals(false, managed.apply(listOf(row("1")), "1"))
        assertEquals(true, managed.apply(listOf(row("1", proxyA)), "1"))
        assertEquals(false, managed.apply(listOf(row("1", proxyA)), "1"))
        assertEquals(true, managed.apply(listOf(row("1", proxyB)), "1"))
        assertEquals(true, managed.apply(listOf(row("1")), "1"))
    }
}
