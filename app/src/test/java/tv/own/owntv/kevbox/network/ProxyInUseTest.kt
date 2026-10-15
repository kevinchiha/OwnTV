// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.network.ProxyConfig

class ProxyInUseTest {
    private fun inUse(config: ProxyConfig) = runBlocking { ProxyInUse { config }.current() }

    @Test
    fun `ProxyInUse is true only for a usable proxy`() {
        assertTrue(inUse(ProxyConfig(enabled = true, host = "proxy.example", port = 3128, username = "u", password = "p")))
        assertTrue(inUse(ProxyConfig(enabled = true, host = "proxy.example", port = 3128)))

        assertFalse(inUse(ProxyConfig(enabled = true, host = " ", port = 3128)))
        assertFalse(inUse(ProxyConfig(enabled = true, host = "proxy.example", port = 0)))
        assertFalse(inUse(ProxyConfig(enabled = false, host = "proxy.example", port = 3128)))
        assertFalse(inUse(ProxyConfig()))
    }
}
