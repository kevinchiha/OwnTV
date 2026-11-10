// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.network

import android.content.SharedPreferences
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.network.ProxyConfig
import tv.own.owntv.kevbox.supabase.PlaylistProxy
import tv.own.owntv.kevbox.supabase.ProxyTls
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

    /** The relay as ManagedProxy sees it: where it sends connections, or null while stopped. */
    private class FakeRelay(var port: Int = RELAY_PORT) : Relay {
        var canBind = true
        var target: RelayTarget? = null
        val starts = mutableListOf<RelayTarget>()
        var stops = 0
        override fun start(target: RelayTarget): Int {
            if (!canBind) throw IOException("no port")
            starts += target
            this.target = target
            return port
        }
        override fun stop() { stops += 1; target = null }
    }

    /** In-memory SharedPreferences: the JVM's android.jar stub would silently drop every write. */
    private class MemoryPrefs : SharedPreferences {
        val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun getBoolean(key: String, defValue: Boolean) = defValue
        override fun contains(key: String) = key in values
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removed += key }
            override fun clear() = apply { removed += values.keys }
            override fun commit(): Boolean {
                removed.forEach { values.remove(it) }
                values.putAll(pending)
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }

    private val tls = ProxyTls("proxy.kevbox.dev", 443, "203.0.113.7")
    private val encrypted = PlaylistProxy(true, "plain.example", 3128, "kb12", "secret", tls)
    private val relayTarget = RelayTarget("proxy.kevbox.dev", 443, "203.0.113.7")
    private fun loopback(port: Int = RELAY_PORT) = PlaylistProxy(true, RELAY_LOOPBACK, port, "kb12", "secret")

    /** Core's setting while no relay port is open: loopback, a port no unprivileged app can bind, no login (AC-16). */
    private fun assertClosed(p: PlaylistProxy) {
        assertTrue("enabled, so nothing goes direct", p.enabled)
        assertEquals(RELAY_LOOPBACK, p.host)
        assertTrue("port ${p.port} below 1024", p.port in 1..1023)
        assertEquals("no login", "", p.username)
        assertEquals("no password", "", p.password)
        // Core still routes through it (a setting it found unusable would send requests direct).
        val config = ProxyConfig(p.enabled, p.host, p.port, p.username, p.password)
        assertTrue("Core counts it as a usable proxy", runBlocking { ProxyInUse { config }.current() })
    }

    private fun managed(target: ProxyTarget, relay: Relay = FakeRelay(), kept: KeptRelay = KeptRelay(MemoryPrefs())) =
        ManagedProxy(target, relay, kept)

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
        val managed = managed(target)

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
        val managed = managed(target)

        assertEquals(false, managed.apply(listOf(row("1")), "1"))
        assertEquals(true, managed.apply(listOf(row("1", proxyA)), "1"))
        assertEquals(false, managed.apply(listOf(row("1", proxyA)), "1"))
        assertEquals(true, managed.apply(listOf(row("1", proxyB)), "1"))
        assertEquals(true, managed.apply(listOf(row("1")), "1"))
    }

    @Test
    fun `an encrypted address runs the relay and saves only the loopback proxy`() = runBlocking {
        val target = Target(stored = PlaylistProxy.OFF)
        val relay = FakeRelay()
        val kept = KeptRelay(MemoryPrefs())
        val managed = managed(target, relay, kept)

        assertTrue(managed.apply(listOf(row("1", encrypted)), "1"))

        assertEquals(listOf(loopback()), target.saves)
        assertTrue(target.saves.none { it.host == "plain.example" })
        assertEquals(relayTarget, relay.target)
        assertEquals(relayTarget, kept.load())
        // Error reports and play notes read Core's setting: the loopback counts as a usable proxy (AC-23).
        val stored = target.stored
        val config = ProxyConfig(stored.enabled, stored.host, stored.port, stored.username, stored.password)
        assertTrue(ProxyInUse { config }.current())
    }

    @Test
    fun `the same address writes nothing, and an address-only change still counts`() = runBlocking {
        val target = Target(stored = PlaylistProxy.OFF)
        val relay = FakeRelay()
        val kept = KeptRelay(MemoryPrefs())
        val managed = managed(target, relay, kept)
        managed.apply(listOf(row("1", encrypted)), "1")

        assertFalse(managed.apply(listOf(row("1", encrypted)), "1"))
        assertEquals(1, target.saves.size)

        val moved = encrypted.copy(tls = tls.copy(addr = "203.0.113.8"))
        assertTrue(managed.apply(listOf(row("1", moved)), "1"))
        assertEquals(1, target.saves.size)
        assertEquals(relayTarget.copy(addr = "203.0.113.8"), relay.target)
        assertEquals(relayTarget.copy(addr = "203.0.113.8"), kept.load())

        assertTrue(managed.apply(listOf(row("1", moved.copy(tls = moved.tls!!.copy(host = "tls2.kevbox.dev")))), "1"))
        assertTrue(managed.apply(listOf(row("1", moved.copy(tls = moved.tls!!.copy(host = "tls2.kevbox.dev", port = 8443)))), "1"))
        assertEquals(1, target.saves.size)
        assertEquals(RelayTarget("tls2.kevbox.dev", 8443, "203.0.113.8"), relay.target)
    }

    @Test
    fun `a new login on the same address rewrites Core's setting`() = runBlocking {
        val target = Target(stored = PlaylistProxy.OFF)
        val managed = managed(target)
        managed.apply(listOf(row("1", encrypted)), "1")

        assertTrue(managed.apply(listOf(row("1", encrypted.copy(password = "new"))), "1"))
        assertEquals(loopback().copy(password = "new"), target.stored)
    }

    @Test
    fun `plain, off, VPN on and sign-out stop the relay and drop the kept address`() = runBlocking {
        val vpn = PlaylistVpn(true, "vpn-de.premiumize.me", null, "vu", "vp", null)
        for (path in listOf("plain", "off", "VPN on", "no rows", "sign-out")) {
            val target = Target(stored = PlaylistProxy.OFF)
            val relay = FakeRelay()
            val kept = KeptRelay(MemoryPrefs())
            val managed = managed(target, relay, kept)
            managed.apply(listOf(row("1", encrypted)), "1")

            val core = when (path) {
                "plain" -> proxyA.also { managed.apply(listOf(row("1", proxyA)), "1") }
                "off" -> PlaylistProxy.OFF.also { managed.apply(listOf(row("1", encrypted.copy(enabled = false))), "1") }
                "VPN on" -> PlaylistProxy.OFF.also { managed.apply(listOf(row("1", encrypted).copy(vpn = vpn)), "1") }
                "no rows" -> PlaylistProxy.OFF.also { managed.apply(emptyList(), null) }
                else -> PlaylistProxy.OFF.also { managed.clear() }
            }

            assertNull("$path: relay still running", relay.target)
            assertTrue("$path: relay never stopped", relay.stops > 0)
            assertNull("$path: address still kept", kept.load())
            assertEquals(path, core, ManagedProxy.normalized(target.stored))
        }
    }

    @Test
    fun `turning the encrypted address off says so, and a plain re-check after it writes nothing`() = runBlocking {
        val target = Target(stored = PlaylistProxy.OFF)
        val managed = managed(target)
        managed.apply(listOf(row("1", encrypted)), "1")

        assertTrue(managed.apply(listOf(row("1", encrypted.copy(tls = null))), "1"))
        assertEquals(encrypted.copy(tls = null), target.stored)
        assertFalse(managed.apply(listOf(row("1", encrypted.copy(tls = null))), "1"))
    }

    @Test
    fun `resume restarts from the kept address and moves Core's port when it must`() = runBlocking {
        val prefs = MemoryPrefs()
        KeptRelay(prefs).save(relayTarget)
        val target = Target(stored = loopback(RELAY_PORT))
        val relay = FakeRelay(port = RELAY_PORT + 1)

        managed(target, relay, KeptRelay(prefs)).resume()

        assertEquals(relayTarget, relay.target)
        assertEquals(listOf(loopback(RELAY_PORT + 1)), target.saves)
        assertEquals(relayTarget, KeptRelay(prefs).load())
    }

    @Test
    fun `resume on the same port writes nothing, and the first check after it changes nothing`() = runBlocking {
        val prefs = MemoryPrefs()
        KeptRelay(prefs).save(relayTarget)
        val target = Target(stored = loopback())
        val relay = FakeRelay()
        val managed = managed(target, relay, KeptRelay(prefs))

        managed.resume()
        assertEquals(relayTarget, relay.target)
        assertEquals(emptyList<PlaylistProxy>(), target.saves)

        assertFalse(managed.apply(listOf(row("1", encrypted)), "1"))
        assertEquals(emptyList<PlaylistProxy>(), target.saves)
    }

    @Test
    fun `resume with Core holding a plain proxy drops the kept address and starts nothing`() = runBlocking {
        val prefs = MemoryPrefs()
        KeptRelay(prefs).save(relayTarget)
        val target = Target(stored = proxyA)
        val relay = FakeRelay()

        managed(target, relay, KeptRelay(prefs)).resume()

        assertEquals(emptyList<RelayTarget>(), relay.starts)
        assertNull(KeptRelay(prefs).load())
        assertEquals(emptyList<PlaylistProxy>(), target.saves)

        // Core's proxy switched off with the loopback still in its old fields: not the relay's either.
        KeptRelay(prefs).save(relayTarget)
        managed(Target(stored = loopback().copy(enabled = false)), relay, KeptRelay(prefs)).resume()
        assertEquals(emptyList<RelayTarget>(), relay.starts)
        assertNull(KeptRelay(prefs).load())
    }

    @Test
    fun `resume with nothing kept changes nothing`() = runBlocking {
        val target = Target(stored = loopback())
        val relay = FakeRelay()
        managed(target, relay).resume()
        assertEquals(emptyList<RelayTarget>(), relay.starts)
        assertEquals(emptyList<PlaylistProxy>(), target.saves)
    }

    @Test
    fun `resume with a relay that cannot bind takes the login off the loopback until a port opens`() = runBlocking {
        val prefs = MemoryPrefs()
        KeptRelay(prefs).save(relayTarget)
        val target = Target(stored = loopback())
        val relay = FakeRelay().apply { canBind = false }
        val managed = managed(target, relay, KeptRelay(prefs))

        // Another app could be listening on the port the relay no longer holds.
        managed.resume()
        assertEquals(1, target.saves.size)
        assertClosed(target.stored)
        assertTrue(target.saves.none { it.username == "kb12" || it.port == RELAY_PORT })
        // Still kept, so the next check or start tries again.
        assertEquals(relayTarget, KeptRelay(prefs).load())
        managed.resume()
        assertEquals(1, target.saves.size)

        // A port opens: the relay is behind Core's setting again, and the next check brings the login back.
        relay.canBind = true
        managed.resume()
        assertEquals(relayTarget, relay.target)
        assertEquals(RELAY_PORT, target.stored.port)
        assertTrue(managed.apply(listOf(row("1", encrypted)), "1"))
        assertEquals(loopback(), target.stored)
    }

    @Test
    fun `a relay that cannot bind fails closed without saving the login`() = runBlocking {
        val target = Target(stored = proxyA)
        val relay = FakeRelay().apply { canBind = false }
        val kept = KeptRelay(MemoryPrefs())
        val managed = managed(target, relay, kept)

        assertTrue(managed.apply(listOf(row("1", encrypted)), "1"))
        assertClosed(target.stored)
        assertTrue(target.saves.none { it.host == "plain.example" || it.username == "kb12" })
        // Still kept, so the next check or start tries again.
        assertEquals(relayTarget, kept.load())
        assertFalse(managed.apply(listOf(row("1", encrypted)), "1"))

        // Already on the loopback with the login, on a port the relay no longer holds: the login comes off.
        target.stored = loopback(RELAY_PORT + 5)
        assertTrue(managed.apply(listOf(row("1", encrypted)), "1"))
        assertClosed(target.stored)

        // The port frees up: the next check binds and points Core at it.
        relay.canBind = true
        assertTrue(managed.apply(listOf(row("1", encrypted)), "1"))
        assertEquals(loopback(RELAY_PORT), target.stored)
        assertEquals(relayTarget, relay.target)
    }
}
