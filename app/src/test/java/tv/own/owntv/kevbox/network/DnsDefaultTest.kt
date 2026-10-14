// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.network.DnsConfig

class DnsDefaultTest {
    @Test
    fun `a TV with no DNS of its own gets Cloudflare`() {
        assertTrue(DnsDefault.shouldSet(DnsConfig()))
        // Off, whatever was typed before, is not a DNS in use.
        assertTrue(DnsDefault.shouldSet(DnsConfig(enabled = false, host = "1.2.3.4", dohUrl = "https://dns.example/dns-query")))
        // On with nothing to ask is not one either.
        assertTrue(DnsDefault.shouldSet(DnsConfig(enabled = true)))
    }

    @Test
    fun `a DNS the member set themselves is kept`() {
        assertFalse(DnsDefault.shouldSet(DnsConfig(enabled = true, host = "9.9.9.9", port = 53)))
        assertFalse(DnsDefault.shouldSet(DnsConfig(enabled = true, dohUrl = "https://dns.google/dns-query")))
    }
}
