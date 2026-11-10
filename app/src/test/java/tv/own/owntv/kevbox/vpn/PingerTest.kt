// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PingerTest {

    @Test
    fun `parsePingAverageMs reads both ping formats`() {
        val iputils = """
            PING vpn-de.premiumize.me (1.2.3.4) 56(84) bytes of data.
            64 bytes from 1.2.3.4: icmp_seq=1 ttl=55 time=9.1 ms

            --- vpn-de.premiumize.me ping statistics ---
            3 packets transmitted, 3 received, 0% packet loss, time 402ms
            rtt min/avg/max/mdev = 9.1/12.6/15.0/2.1 ms
        """.trimIndent()
        assertEquals(13L, parsePingAverageMs(iputils))

        val toybox = """
            --- vpn-nl.premiumize.me ping statistics ---
            3 packets transmitted, 3 received, 0% packet loss
            round-trip min/avg/max = 9/12/15 ms
        """.trimIndent()
        assertEquals(12L, parsePingAverageMs(toybox))

        val lost = """
            --- vpn-fr.premiumize.me ping statistics ---
            3 packets transmitted, 0 received, 100% packet loss, time 2003ms
        """.trimIndent()
        assertNull(parsePingAverageMs(lost))
        assertNull(parsePingAverageMs(""))
    }
}
